package com.securebrowser.app.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import com.securebrowser.app.R
import com.securebrowser.app.core.url.NormalizeResult
import com.securebrowser.app.core.url.UrlNormalizer
import com.securebrowser.app.data.db.entity.DownloadRecordEntity
import com.securebrowser.app.data.repository.DownloadRepository
import com.securebrowser.app.data.repository.SettingsRepository
import com.securebrowser.app.di.ServiceLocator
import com.securebrowser.app.policy.DownloadDecision
import com.securebrowser.app.policy.DownloadPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * مدير التنزيلات الحقيقي — مرحلة 2 (§12-§14).
 *
 * كل تنزيل يمر إلزاميًا عبر هذا المدير → Policy Layer ([DownloadPolicy]) →
 * قرار (سماح/موافقة/حظر) → تنفيذ بمهام coroutine قابلة للإيقاف/الاستئناف/إعادة المحاولة.
 *
 * الحالات: PENDING_APPROVAL / DOWNLOADING / PAUSED / COMPLETED / FAILED / BLOCKED.
 * الملفات تُكتب في مجلد التنزيلات الخاص بالتطبيق (بلا صلاحيات تخزين حديثة)،
 * والفتح/المشاركة عبر FileProvider.
 */
class BrowserDownloadManager(
    private val context: Context,
    private val repository: DownloadRepository,
    private val scope: CoroutineScope
) {

    private val jobs = ConcurrentHashMap<Long, Job>()
    private val pauseFlags = ConcurrentHashMap<Long, Boolean>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun downloadsDir(): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads").apply { mkdirs() }

    // ————————————————— نقطة الدخول من WebView —————————————————

    fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
        sourceUrl: String?,
        sourceAllowed: Boolean
    ) {
        scope.launch(Dispatchers.IO) {
            val settings = ServiceLocator.settingsRepository
            val policy = buildPolicy(settings)
            val scheme = (UrlNormalizer.normalize(url) as? NormalizeResult.Success)?.url?.scheme
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

            val decision = policy.decide(sourceAllowed, scheme, fileName, mimeType)
            when (decision) {
                DownloadDecision.ALLOW -> {
                    val id = repository.insert(
                        record(url, fileName, sourceUrl, mimeType, contentLength)
                            .copy(state = DownloadRepository.STATE_DOWNLOADING)
                    )
                    toast(context.getString(R.string.toast_download_started))
                    startTransfer(id)
                }

                DownloadDecision.REQUIRES_APPROVAL -> {
                    repository.insert(
                        record(url, fileName, sourceUrl, mimeType, contentLength)
                            .copy(state = DownloadRepository.STATE_PENDING_APPROVAL)
                    )
                    toast(context.getString(R.string.toast_download_needs_approval))
                }

                DownloadDecision.BLOCKED_DANGEROUS,
                DownloadDecision.BLOCK_SOURCE_NOT_ALLOWED,
                DownloadDecision.BLOCK_SCHEME -> {
                    repository.insert(
                        record(url, fileName, sourceUrl, mimeType, contentLength)
                            .copy(state = DownloadRepository.STATE_BLOCKED)
                    )
                    ServiceLocator.blockedActivityRepository.record(
                        url = url,
                        host = (UrlNormalizer.normalize(url) as? NormalizeResult.Success)?.url?.host,
                        reason = "DOWNLOAD_${decision.name}",
                        navigationType = "DOWNLOAD"
                    )
                    toast(context.getString(R.string.toast_download_blocked))
                }
            }
        }
    }

    private fun record(
        url: String,
        fileName: String,
        sourceUrl: String?,
        mimeType: String?,
        contentLength: Long
    ) = DownloadRecordEntity(
        fileName = fileName,
        url = url.take(2048),
        sourceUrl = sourceUrl?.take(2048),
        mimeType = mimeType?.take(120),
        sizeBytes = if (contentLength > 0) contentLength else null,
        downloadedBytes = 0,
        state = DownloadRepository.STATE_DOWNLOADING,
        timestamp = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    /**
     * سياسة التنزيلات من إعدادات الوالدين الحالية (تُقرأ لحظة القرار).
     *
     * إصلاح v1.2.0: كانت توقيعات المفاتيح تُسقط "allow" إلى BLOCK فعلًا
     * (apk_policy: أي قيمة غير "approval" → BLOCK) فلا يمكن للوالد السماح أصلًا.
     * الآن كل الفئات الثلاث تدعم allow/approval/block، والافتراضي عند غياب الإعداد
     * هو السماح مع التسجيل الكامل في شاشة التنزيلات (طلب الوالد الصريح:
     * "لا أريد حظر أي تنزيلات، فقط تسجيلها").
     */
    private fun buildPolicy(settings: SettingsRepository): DownloadPolicy {
        fun policyOf(key: String, default: DownloadPolicy.Policy) = when (settings.string(key, "")) {
            "allow" -> DownloadPolicy.Policy.ALLOW
            "approval" -> DownloadPolicy.Policy.APPROVAL
            "block" -> DownloadPolicy.Policy.BLOCK
            else -> default
        }
        return DownloadPolicy(
            executablePolicy = policyOf("apk_policy", DownloadPolicy.Policy.ALLOW),
            archivePolicy = policyOf("archive_policy", DownloadPolicy.Policy.ALLOW),
            unknownPolicy = policyOf("unknown_policy", DownloadPolicy.Policy.ALLOW)
        )
    }

    // ————————————————— إجراءات الوالدين/المستخدم —————————————————

    /** موافقة الوالدين على تنزيل معلّق. */
    fun approve(id: Long) {
        scope.launch(Dispatchers.IO) {
            val record = repository.findById(id) ?: return@launch
            if (record.state != DownloadRepository.STATE_PENDING_APPROVAL) return@launch
            repository.updateState(id, DownloadRepository.STATE_DOWNLOADING)
            startTransfer(id)
        }
    }

    /** رفض تنزيل معلّق — يُسجَّل محظورًا. */
    fun reject(id: Long) {
        scope.launch(Dispatchers.IO) {
            repository.updateState(id, DownloadRepository.STATE_BLOCKED)
        }
    }

    fun pause(id: Long) {
        pauseFlags[id] = true
        jobs.remove(id)?.cancel()
        scope.launch(Dispatchers.IO) {
            val record = repository.findById(id) ?: return@launch
            if (record.state == DownloadRepository.STATE_DOWNLOADING) {
                repository.updateStateAndProgress(
                    id, DownloadRepository.STATE_PAUSED,
                    record.downloadedBytes ?: 0
                )
            }
        }
    }

    fun resume(id: Long) {
        scope.launch(Dispatchers.IO) {
            val record = repository.findById(id) ?: return@launch
            if (record.state != DownloadRepository.STATE_PAUSED) return@launch
            repository.updateState(id, DownloadRepository.STATE_DOWNLOADING)
            startTransfer(id)
        }
    }

    fun retry(id: Long) {
        scope.launch(Dispatchers.IO) {
            val record = repository.findById(id) ?: return@launch
            if (record.state != DownloadRepository.STATE_FAILED &&
                record.state != DownloadRepository.STATE_BLOCKED
            ) return@launch
            repository.updateState(id, DownloadRepository.STATE_DOWNLOADING)
            startTransfer(id)
        }
    }

    /** حذف السجل والملف المحلي معًا. */
    fun delete(id: Long) {
        scope.launch(Dispatchers.IO) {
            pauseFlags[id] = true
            jobs.remove(id)?.cancelAndJoin()
            val record = repository.findById(id) ?: return@launch
            record.filePath?.let { relative ->
                File(downloadsDir(), relative).delete()
            }
            repository.delete(id)
        }
    }

    /** فتح الملف المنزَّل بأي تطبيق يستطيع التعامل معه. */
    fun openFile(id: Long) {
        scope.launch(Dispatchers.IO) {
            val record = repository.findById(id) ?: return@launch
            val file = record.filePath?.let { File(downloadsDir(), it) } ?: return@launch
            if (!file.exists()) return@launch
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, record.mimeType ?: guessMime(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            mainHandler.post {
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    toast(context.getString(R.string.toast_no_app))
                }
            }
        }
    }

    fun shareFile(id: Long) {
        scope.launch(Dispatchers.IO) {
            val record = repository.findById(id) ?: return@launch
            val file = record.filePath?.let { File(downloadsDir(), it) } ?: return@launch
            if (!file.exists()) return@launch
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = record.mimeType ?: guessMime(file.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            mainHandler.post {
                try {
                    context.startActivity(
                        Intent.createChooser(intent, record.fileName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    // لا مستلم للمشاركة
                }
            }
        }
    }

    /** "موقع الملف" — المسار الفعلي للملف على الجهاز. */
    fun fileLocation(id: Long, callback: (String?) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val record = repository.findById(id)
            val path = record?.filePath?.let { File(downloadsDir(), it).absolutePath }
            mainHandler.post { callback(path) }
        }
    }

    // ————————————————— محرك النقل الفعلي —————————————————

    private fun startTransfer(id: Long) {
        val job = scope.launch(Dispatchers.IO) {
            val record = repository.findById(id) ?: return@launch
            try {
                val existing = if ((record.downloadedBytes ?: 0) > 0) {
                    File(downloadsDir(), record.filePath ?: "").takeIf { it.exists() }
                } else null
                val resumeFrom = existing?.length() ?: 0

                val file = existing ?: uniqueFile(resolveLocalName(record))
                if (existing == null) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                    repository.updateFilePath(id, file.name)
                }

                val conn = URL(record.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                if (resumeFrom > 0) {
                    conn.setRequestProperty("Range", "bytes=$resumeFrom-")
                }
                val cookie = try {
                    CookieManager.getInstance().getCookie(record.url)
                } catch (e: Exception) {
                    null
                }
                if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)
                conn.setRequestProperty("User-Agent", WEB_UA)
                conn.connect()

                val code = conn.responseCode
                if (code !in 200..299 && code != 206) {
                    conn.disconnect()
                    throw IllegalStateException("http_$code")
                }

                val serverTotal = conn.contentLengthLong
                val total = if (resumeFrom > 0 && serverTotal > 0) serverTotal + resumeFrom
                else if (serverTotal > 0) serverTotal
                else record.sizeBytes?.takeIf { it > 0 }
                if (total != null && total > 0) {
                    repository.updateProgress(id, resumeFrom, total)
                }

                pauseFlags[id] = false
                var downloaded = resumeFrom
                var lastDbUpdate = 0L
                // إذا لم يدعم الخادم Range (استجابة 200 رغم طلب استئناف) → إعادة من الصفر
                val append = resumeFrom > 0 && code == 206
                if (resumeFrom > 0 && !append) {
                    downloaded = 0
                    file.writeBytes(ByteArray(0))
                }
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(file, append).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            if (pauseFlags[id] == true) {
                                repository.updateStateAndProgress(
                                    id, DownloadRepository.STATE_PAUSED, downloaded
                                )
                                return@launch
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastDbUpdate > PROGRESS_DB_INTERVAL_MS) {
                                repository.updateProgress(id, downloaded, total)
                                lastDbUpdate = now
                            }
                        }
                    }
                }
                repository.updateProgress(id, downloaded, total)
                repository.updateState(id, DownloadRepository.STATE_COMPLETED)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val current = repository.findById(id)
                if (current?.state == DownloadRepository.STATE_DOWNLOADING) {
                    repository.updateState(id, DownloadRepository.STATE_FAILED)
                }
            } finally {
                jobs.remove(id)
                pauseFlags.remove(id)
            }
        }
        jobs[id] = job
    }

    private fun resolveLocalName(record: DownloadRecordEntity): String {
        val fromUrl = URLUtil.guessFileName(record.url, null, record.mimeType)
        val name = (record.fileName.takeIf { it.isNotBlank() } ?: fromUrl)
            .take(120)
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .ifBlank { "download" }
        return name
    }

    private fun uniqueFile(name: String): File {
        var candidate = File(downloadsDir(), name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(downloadsDir(), "$base ($i)$ext")
            i++
        }
        return candidate
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "zip" -> "application/zip"
        else -> "*/*"
    }

    private fun toast(message: String) {
        mainHandler.post {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val PROGRESS_DB_INTERVAL_MS = 500L
        private const val WEB_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
    }
}
