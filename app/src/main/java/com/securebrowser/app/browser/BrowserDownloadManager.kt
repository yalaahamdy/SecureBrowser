package com.securebrowser.app.browser

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
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
import java.net.URLDecoder
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
    private val inMemoryDataUrls = ConcurrentHashMap<Long, String>()

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
                ?: url.substringBefore(':', "").lowercase().takeIf { it.isNotBlank() }
            val fileName = resolveFileName(url, contentDisposition, mimeType)

            val isDataUrl = url.startsWith("data:", ignoreCase = true)
            val storedUrl = if (isDataUrl) {
                val mimeSnippet = mimeType ?: "application/octet-stream"
                "data:$mimeSnippet;base64,[in-memory]"
            } else {
                url
            }

            val decision = policy.decide(sourceAllowed, scheme, fileName, mimeType)
            when (decision) {
                DownloadDecision.ALLOW -> {
                    val id = repository.insert(
                        record(storedUrl, fileName, sourceUrl, mimeType, contentLength)
                            .copy(state = DownloadRepository.STATE_DOWNLOADING)
                    )
                    if (isDataUrl) {
                        inMemoryDataUrls[id] = url
                    }
                    toast(context.getString(R.string.toast_download_started))
                    startTransfer(id, userAgent)
                }

                DownloadDecision.REQUIRES_APPROVAL -> {
                    val id = repository.insert(
                        record(storedUrl, fileName, sourceUrl, mimeType, contentLength)
                            .copy(state = DownloadRepository.STATE_PENDING_APPROVAL)
                    )
                    if (isDataUrl) {
                        inMemoryDataUrls[id] = url
                    }
                    toast(context.getString(R.string.toast_download_needs_approval))
                }

                DownloadDecision.BLOCKED_DANGEROUS,
                DownloadDecision.BLOCK_SOURCE_NOT_ALLOWED,
                DownloadDecision.BLOCK_SCHEME -> {
                    repository.insert(
                        record(storedUrl, fileName, sourceUrl, mimeType, contentLength)
                            .copy(state = DownloadRepository.STATE_BLOCKED)
                    )
                    ServiceLocator.blockedActivityRepository.record(
                        url = if (isDataUrl) "data:[content]" else url,
                        host = (UrlNormalizer.normalize(url) as? NormalizeResult.Success)?.url?.host ?: "local",
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

    /** العثور على الملف الفعلي سواء في مجلد التنزيلات العام للهاتف أو المجلد المحلي للتطبيق. */
    private fun findExistingDownloadFile(record: DownloadRecordEntity): File? {
        val nameCandidates = listOfNotNull(
            record.filePath?.takeIf { it.isNotBlank() },
            record.fileName.takeIf { it.isNotBlank() }
        ).distinct()
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        for (name in nameCandidates) {
            val local = File(downloadsDir(), name)
            if (local.exists() && local.length() > 0) return local
            val pub = File(publicDir, name)
            if (pub.exists() && pub.length() > 0) return pub
        }
        return null
    }

    /** فتح الملف المنزَّل بأي تطبيق يستطيع التعامل معه. */
    fun openFile(id: Long) {
        scope.launch(Dispatchers.IO) {
            val record = repository.findById(id) ?: return@launch
            val file = findExistingDownloadFile(record) ?: return@launch
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            val resolvedMime = resolveOpenMimeType(file.name, record.mimeType)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMime)
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
            val file = findExistingDownloadFile(record) ?: return@launch
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            val resolvedMime = resolveOpenMimeType(file.name, record.mimeType)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = resolvedMime
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

    /** "موقع الملف" — المسار الفعلي للملف على الجهاز (العام في Download أو المحلي). */
    fun fileLocation(id: Long, callback: (String?) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val record = repository.findById(id)
            val fileName = record?.filePath?.takeIf { it.isNotBlank() } ?: record?.fileName
            val localFile = fileName?.let { File(downloadsDir(), it) }
            val publicFile = fileName?.let {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), it)
            }
            val displayPath = when {
                publicFile != null && publicFile.exists() -> publicFile.absolutePath
                localFile != null && localFile.exists() ->
                    "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath}/${localFile.name}"
                else -> null
            }
            mainHandler.post { callback(displayPath) }
        }
    }

    /** تصدير صريح للملف المنزَّل إلى مجلد التنزيلات العام بالجهاز عند طلب المستخدم. */
    fun exportToPublicDownloads(id: Long, callback: (Boolean, String?) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val record = repository.findById(id)
            val file = record?.let { findExistingDownloadFile(it) }
            if (file == null || !file.exists()) {
                mainHandler.post { callback(false, null) }
                return@launch
            }
            val path = publishToPublicDownloads(context, file, record.mimeType)
            mainHandler.post {
                callback(path != null, path)
            }
        }
    }

    // ————————————————— محرك النقل الفعلي (مزدوج: مباشر + DownloadManager النظامي الاحتياطي) —————————————————

    private fun startTransfer(id: Long, customUserAgent: String? = null) {
        val job = scope.launch(Dispatchers.IO) {
            val record = repository.findById(id) ?: return@launch
            var targetFile: File? = null
            try {
                val existing = if ((record.downloadedBytes ?: 0) > 0 && !record.filePath.isNullOrBlank()) {
                    File(downloadsDir(), record.filePath).takeIf { it.exists() && it.length() > 0 }
                } else null
                var resumeFrom = existing?.length() ?: 0L

                var file = existing ?: uniqueFile(resolveLocalName(record))
                targetFile = file
                if (existing == null) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                    repository.updateFilePath(id, file.name)
                }

                // 1. معالجة روابط Data URLs في الذاكرة (بما فيها محوّلات blob:)
                val dataPayload = inMemoryDataUrls.remove(id)
                if (dataPayload != null || record.url.startsWith("data:", ignoreCase = true)) {
                    val rawData = dataPayload ?: record.url
                    processDataUrl(id, rawData, file, record)
                    return@launch
                }

                // 2. معالجة طلب الشبكة مع الـ Redirects المتعاقبة (حتى 15 مرة) وإعادة المحاولة التلقائية عند رفض Range
                var currentUrl = toSafeHttpUrl(record.url)
                var redirects = 0
                val maxRedirects = 15
                var conn: HttpURLConnection? = null
                var code = 0

                while (redirects < maxRedirects) {
                    val targetUri = URL(currentUrl)
                    val c = (targetUri.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 25_000
                        readTimeout = 45_000
                        instanceFollowRedirects = false // تتبع يدوي شامل لـ HTTP<->HTTPS و 301/302/303/307/308
                        useCaches = false
                    }

                    if (resumeFrom > 0) {
                        c.setRequestProperty("Range", "bytes=$resumeFrom-")
                    }
                    val cookie = resolveCookiesForUrl(currentUrl, record.sourceUrl)
                    if (!cookie.isNullOrBlank()) {
                        c.setRequestProperty("Cookie", cookie)
                    }
                    c.setRequestProperty("User-Agent", customUserAgent?.takeIf { it.isNotBlank() } ?: WEB_UA)
                    c.setRequestProperty("Accept", "*/*")
                    val referer = record.sourceUrl?.takeIf { it.startsWith("http", ignoreCase = true) }
                    if (!referer.isNullOrBlank()) {
                        c.setRequestProperty("Referer", referer)
                    }

                    c.connect()
                    code = c.responseCode

                    // التوجيهات: 301, 302, 303, 307, 308
                    if (code in 301..303 || code == 307 || code == 308) {
                        val location = c.getHeaderField("Location")
                        c.disconnect()
                        if (location.isNullOrBlank()) {
                            throw IllegalStateException("Redirected with empty location header ($code)")
                        }
                        val resolvedNext = URL(targetUri, location).toString()
                        currentUrl = toSafeHttpUrl(resolvedNext)
                        redirects++
                        continue
                    }

                    // إذا رفض الخادم طلب الاستئناف الجزئي (416 أو خطأ بسبب Range)، أعد الطلب من الصفر تلقائيًا
                    if (resumeFrom > 0 && (code == 416 || code == 400 || code == 403)) {
                        c.disconnect()
                        resumeFrom = 0L
                        file.writeBytes(ByteArray(0))
                        continue
                    }

                    conn = c
                    break
                }

                val activeConn = conn ?: throw IllegalStateException("Too many redirects")

                if (code !in 200..299 && code != 206) {
                    activeConn.disconnect()
                    throw IllegalStateException("http_$code")
                }

                // تحديث اسم الملف الحقيقي ونوعه من ترويسات الاستجابة النهائية بعد انتهاء التوجيهات (Redirects)
                val serverDisposition = activeConn.getHeaderField("Content-Disposition")
                val serverMime = activeConn.contentType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.isNotBlank() && it != "*/*" && it != "application/octet-stream" }
                val effectiveMime = serverMime
                    ?: record.mimeType?.takeIf { it.isNotBlank() && it != "*/*" }

                if (resumeFrom == 0L) {
                    val refinedName = resolveRefinedFileName(
                        initialName = record.fileName,
                        finalUrl = currentUrl,
                        contentDisposition = serverDisposition,
                        mimeType = effectiveMime ?: activeConn.contentType
                    )
                    if (refinedName != file.name) {
                        val renamedFile = uniqueFile(refinedName)
                        if (file.renameTo(renamedFile)) {
                            file = renamedFile
                            targetFile = renamedFile
                        }
                    }
                    repository.updateFileMetadata(
                        id = id,
                        fileName = file.name,
                        filePath = file.name,
                        mimeType = effectiveMime ?: guessMime(file.name)
                    )
                }

                val serverTotal = activeConn.contentLengthLong
                val total = if (resumeFrom > 0 && serverTotal > 0) serverTotal + resumeFrom
                else if (serverTotal > 0) serverTotal
                else record.sizeBytes?.takeIf { it > 0 }
                if (total != null && total > 0) {
                    repository.updateProgress(id, resumeFrom, total)
                }

                pauseFlags[id] = false
                var downloaded = resumeFrom
                var lastDbUpdate = 0L
                val append = resumeFrom > 0 && code == 206
                if (resumeFrom > 0 && !append) {
                    downloaded = 0
                    file.writeBytes(ByteArray(0))
                }

                activeConn.inputStream.use { input ->
                    java.io.FileOutputStream(file, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
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
                        output.flush()
                    }
                }

                val finalSize = file.length().takeIf { it > 0 } ?: downloaded
                repository.updateProgress(id, finalSize, total ?: finalSize)
                repository.updateState(id, DownloadRepository.STATE_COMPLETED)

                // حفظ نسخة مؤكدة في مجلد التنزيلات العام الافتراضي بالهاتف (Download) وإشعار النظام
                publishToPublicDownloads(context, file, effectiveMime ?: guessMime(file.name))
                toast(context.getString(R.string.toast_download_completed, file.name))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // المحرك الاحتياطي: إذا تعثّر الاتصال المباشر وكان الرابط HTTP/HTTPS، استخدم DownloadManager النظامي
                val isHttp = record.url.startsWith("http://", ignoreCase = true) ||
                    record.url.startsWith("https://", ignoreCase = true)
                if (isHttp && pauseFlags[id] != true) {
                    val fallbackSuccess = runSystemDownloadManagerFallback(id, record, customUserAgent)
                    if (fallbackSuccess) return@launch
                }

                val current = repository.findById(id)
                if (current?.state == DownloadRepository.STATE_DOWNLOADING) {
                    repository.updateState(id, DownloadRepository.STATE_FAILED)
                }
                val name = targetFile?.name ?: record.fileName
                toast(context.getString(R.string.toast_download_failed, name))
            } finally {
                jobs.remove(id)
                pauseFlags.remove(id)
            }
        }
        jobs[id] = job
    }

    /**
     * محرك احتياطي عبر [DownloadManager] الرسمي لنظام أندرويد:
     * يعمل تلقائيًا إذا رفض خادم معيّن اتصال HttpURLConnection المباشر،
     * ويحفظ الملف مباشرةً في مجلد Download العام للهاتف مع متابعة حالته حتى يكتمل.
     */
    private suspend fun runSystemDownloadManagerFallback(
        id: Long,
        record: DownloadRecordEntity,
        customUserAgent: String?
    ): Boolean {
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
            val safeUrl = toSafeHttpUrl(record.url)
            val cleanName = sanitizeFileName(record.fileName.ifBlank { "download_${System.currentTimeMillis()}" })
            val mime = guessMime(cleanName, record.mimeType)

            val req = DownloadManager.Request(Uri.parse(safeUrl)).apply {
                setTitle(cleanName)
                setMimeType(mime)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                addRequestHeader("User-Agent", customUserAgent?.takeIf { it.isNotBlank() } ?: WEB_UA)
                val cookie = resolveCookiesForUrl(safeUrl, record.sourceUrl)
                if (!cookie.isNullOrBlank()) {
                    addRequestHeader("Cookie", cookie)
                }
                val referer = record.sourceUrl?.takeIf { it.startsWith("http", ignoreCase = true) }
                if (!referer.isNullOrBlank()) {
                    addRequestHeader("Referer", referer)
                }
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, cleanName)
            }

            val sysId = dm.enqueue(req)
            val query = DownloadManager.Query().setFilterById(sysId)
            var completed = false
            var failed = false
            var attempts = 0
            while (!completed && !failed && attempts < 1800) { // حتى 15 دقيقة كحد أقصى
                if (pauseFlags[id] == true) {
                    dm.remove(sysId)
                    return false
                }
                val cursor: Cursor? = dm.query(query)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val bytesIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val status = if (statusIdx >= 0) c.getInt(statusIdx) else DownloadManager.STATUS_RUNNING
                        val soFar = if (bytesIdx >= 0) c.getLong(bytesIdx).coerceAtLeast(0L) else 0L
                        val total = if (totalIdx >= 0) c.getLong(totalIdx).takeIf { it > 0 } else null
                        scope.launch(Dispatchers.IO) {
                            repository.updateProgress(id, soFar, total)
                        }
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> completed = true
                            DownloadManager.STATUS_FAILED -> failed = true
                        }
                    } else {
                        failed = true
                    }
                } ?: run { failed = true }

                if (!completed && !failed) {
                    delay(500)
                    attempts++
                }
            }

            if (completed) {
                val localCopy = uniqueFile(cleanName)
                try {
                    dm.openDownloadedFile(sysId)?.use { pfd ->
                        java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                            localCopy.outputStream().use { out -> input.copyTo(out) }
                        }
                    }
                } catch (_: Exception) {
                    val pubFile = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        cleanName
                    )
                    if (pubFile.exists()) {
                        pubFile.copyTo(localCopy, overwrite = true)
                    }
                }
                val finalSize = localCopy.length().takeIf { it > 0 } ?: record.sizeBytes ?: 0L
                repository.updateFileMetadata(id, localCopy.name, localCopy.name, mime)
                repository.updateProgress(id, finalSize, finalSize.takeIf { it > 0 })
                repository.updateState(id, DownloadRepository.STATE_COMPLETED)
                toast(context.getString(R.string.toast_download_completed, cleanName))
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun processDataUrl(
        id: Long,
        dataUrl: String,
        file: File,
        record: DownloadRecordEntity
    ) {
        val commaIndex = dataUrl.indexOf(',')
        if (commaIndex == -1) {
            throw IllegalArgumentException("Invalid data URL structure")
        }
        val header = dataUrl.substring(0, commaIndex)
        val dataPart = dataUrl.substring(commaIndex + 1)
        val isBase64 = header.contains(";base64", ignoreCase = true)

        if (isBase64) {
            val bytes = Base64.decode(dataPart, Base64.DEFAULT)
            file.writeBytes(bytes)
            val size = bytes.size.toLong()
            repository.updateProgress(id, size, size)
        } else {
            val decoded = URLDecoder.decode(dataPart, "UTF-8")
            file.writeText(decoded)
            val size = file.length()
            repository.updateProgress(id, size, size)
        }

        repository.updateState(id, DownloadRepository.STATE_COMPLETED)
        publishToPublicDownloads(context, file, record.mimeType)
        toast(context.getString(R.string.toast_download_completed, file.name))
    }

    /**
     * نشر وحفظ الملف في مجلد التنزيلات الافتراضي العام للهاتف (Download):
     * - على Android 10+ (API 29+): MediaStore.Downloads مع معالجة تكرار الأسماء + مسار احتياطي مباشر.
     * - على Android 9 وما قبله: Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS).
     * - ملاحظة حاسمة: MediaStore يرفض النوع العام غير المحدد ويرمي IllegalArgumentException، لذلك يُستخدم دائمًا
     *   نوع MIME قياسي أو "application/octet-stream".
     */
    fun publishToPublicDownloads(context: Context, sourceFile: File, mimeType: String?): String? {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return null
        val safeMime = guessMime(sourceFile.name, mimeType)

        // المسار الأول (Android 10+): MediaStore.Downloads
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val dot = sourceFile.name.lastIndexOf('.')
                val base = if (dot > 0) sourceFile.name.substring(0, dot) else sourceFile.name
                val ext = if (dot > 0) sourceFile.name.substring(dot) else ""

                var chosenName = sourceFile.name
                var uri: Uri? = null
                for (attempt in 0..10) {
                    val candidateName = if (attempt == 0) sourceFile.name else "$base ($attempt)$ext"
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, candidateName)
                        put(MediaStore.Downloads.MIME_TYPE, safeMime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    uri = try {
                        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    } catch (_: Exception) {
                        null
                    }
                    if (uri != null) {
                        chosenName = candidateName
                        break
                    }
                }

                if (uri != null) {
                    var written = false
                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(out, 64 * 1024)
                            out.flush()
                            written = true
                        }
                    }
                    val finishValues = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, finishValues, null, null)
                    if (written) {
                        val publicPath = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            chosenName
                        ).absolutePath
                        try {
                            MediaScannerConnection.scanFile(
                                context,
                                arrayOf(publicPath),
                                arrayOf(safeMime),
                                null
                            )
                        } catch (_: Exception) {
                        }
                        return publicPath
                    }
                }
            } catch (_: Exception) {
                // ننتقل إلى المسار المباشر الاحتياطي بالأسفل
            }
        }

        // المسار الثاني (أندرويد 9 وما قبله، أو احتياطي مباشر على أندرويد 10+):
        try {
            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (publicDir.exists() || publicDir.mkdirs()) {
                var candidate = File(publicDir, sourceFile.name)
                if (candidate.exists() && candidate.absolutePath != sourceFile.absolutePath) {
                    val dot = sourceFile.name.lastIndexOf('.')
                    val base = if (dot > 0) sourceFile.name.substring(0, dot) else sourceFile.name
                    val ext = if (dot > 0) sourceFile.name.substring(dot) else ""
                    var i = 1
                    while (candidate.exists()) {
                        candidate = File(publicDir, "$base ($i)$ext")
                        i++
                    }
                }
                if (candidate.absolutePath != sourceFile.absolutePath) {
                    sourceFile.copyTo(candidate, overwrite = true)
                }
                try {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(candidate.absolutePath),
                        arrayOf(safeMime),
                        null
                    )
                } catch (_: Exception) {
                }
                return candidate.absolutePath
            }
        } catch (_: Exception) {
            // تجاهل
        }
        return null
    }

    private fun resolveFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        val parsed = parseContentDisposition(contentDisposition)
            ?: extractDispositionFromQuery(url)
        if (!parsed.isNullOrBlank()) {
            return ensureExtensionIfNeeded(sanitizeFileName(parsed), mimeType)
        }
        val fromPath = extractFileNameFromUrlPath(url)
        if (!fromPath.isNullOrBlank()) {
            return ensureExtensionIfNeeded(sanitizeFileName(fromPath), mimeType)
        }
        val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
        if (guessed.isNotBlank() && !guessed.equals("downloadfile.bin", ignoreCase = true)) {
            return ensureExtensionIfNeeded(sanitizeFileName(guessed), mimeType)
        }
        val ext = guessExtension(mimeType)
        return "download_${System.currentTimeMillis()}.$ext"
    }

    private fun resolveRefinedFileName(
        initialName: String,
        finalUrl: String,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        val fromHeader = parseContentDisposition(contentDisposition)
            ?: extractDispositionFromQuery(finalUrl)
        if (!fromHeader.isNullOrBlank()) {
            return ensureExtensionIfNeeded(sanitizeFileName(fromHeader), mimeType)
        }
        val initialIsGeneric = initialName.isBlank() ||
            initialName.startsWith("download_") ||
            initialName.endsWith(".bin", ignoreCase = true) ||
            initialName.endsWith(".html", ignoreCase = true) ||
            !initialName.contains('.')
        if (initialIsGeneric) {
            val fromFinalPath = extractFileNameFromUrlPath(finalUrl)
            if (!fromFinalPath.isNullOrBlank()) {
                return ensureExtensionIfNeeded(sanitizeFileName(fromFinalPath), mimeType)
            }
        }
        return ensureExtensionIfNeeded(sanitizeFileName(initialName), mimeType)
    }

    private fun ensureExtensionIfNeeded(name: String, mimeType: String?): String {
        val ext = guessExtension(mimeType)
        if (ext == "bin") return name
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return "$name.$ext"
        val currentExt = name.substring(dot + 1).lowercase()
        if (currentExt == "bin" || currentExt == "tmp" || currentExt.length > 8) {
            return name.substring(0, dot) + "." + ext
        }
        return name
    }

    private fun extractDispositionFromQuery(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val param = uri.getQueryParameter("response-content-disposition")
                ?: uri.getQueryParameter("content-disposition")
                ?: uri.getQueryParameter("filename")
            if (!param.isNullOrBlank()) {
                parseContentDisposition(param) ?: param.trim('"', '\'', ' ')
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractFileNameFromUrlPath(url: String): String? {
        return try {
            val cleanUrl = url.substringBefore('#').substringBefore('?')
            val lastSeg = cleanUrl.substringAfterLast('/', "").trim()
            if (lastSeg.isBlank()) return null
            val decoded = try {
                URLDecoder.decode(lastSeg, "UTF-8")
            } catch (_: Exception) {
                lastSeg
            }
            if (decoded.contains('.') && decoded.length in 3..120 && !decoded.endsWith(".php", true) && !decoded.endsWith(".asp", true) && !decoded.endsWith(".aspx", true) && !decoded.endsWith(".jsp", true)) {
                decoded
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun toSafeHttpUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return try {
            Uri.encode(trimmed, "@#&=*+-_.,:!?()/~'%")
        } catch (_: Exception) {
            trimmed.replace(" ", "%20")
        }
    }

    private fun resolveCookiesForUrl(currentUrl: String, sourceUrl: String?): String? {
        val cm = try {
            CookieManager.getInstance()
        } catch (_: Exception) {
            return null
        }
        val direct = try {
            cm.getCookie(currentUrl)
        } catch (_: Exception) {
            null
        }
        if (!direct.isNullOrBlank()) return direct
        if (!sourceUrl.isNullOrBlank()) {
            return try {
                cm.getCookie(sourceUrl)
            } catch (_: Exception) {
                null
            }
        }
        return null
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

    private fun resolveOpenMimeType(name: String, recordedMime: String?): String {
        val byName = guessMime(name, null)
        if (byName != "application/octet-stream") return byName
        val cleanRecorded = recordedMime?.substringBefore(';')?.trim()?.lowercase()
        if (!cleanRecorded.isNullOrBlank() && cleanRecorded != "*/*" && cleanRecorded != "application/octet-stream") {
            return cleanRecorded
        }
        return "*/*"
    }

    private fun guessMime(name: String, fallbackMime: String? = null): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mapped = when (ext) {
            "apk", "apks", "xapk" -> "application/vnd.android.package-archive"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt", "log", "md" -> "text/plain"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "epub" -> "application/epub+zip"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            "ico" -> "image/x-icon"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz", "tgz" -> "application/gzip"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }
        if (!mapped.isNullOrBlank() && mapped != "*/*") return mapped
        val cleanFallback = fallbackMime?.substringBefore(';')?.trim()?.lowercase()
        if (!cleanFallback.isNullOrBlank() && cleanFallback != "*/*") return cleanFallback
        return "application/octet-stream"
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

        private val DIRECT_DOWNLOAD_EXTENSIONS = setOf(
            "apk", "apks", "xapk", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "odt", "ods", "odp", "rtf", "csv", "epub", "mobi", "zip", "rar", "7z",
            "tar", "gz", "tgz", "bz2", "xz", "iso", "dmg", "exe", "msi", "deb", "rpm",
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "mp4", "mkv", "avi",
            "mov", "webm", "flv", "3gp", "torrent", "bin", "dat", "db", "sqlite", "bak"
        )

        private val KNOWN_DOWNLOAD_CDN_HOSTS = listOf(
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "codeload.github.com",
            "raw.githubusercontent.com",
            "drive.usercontent.google.com",
            "googleusercontent.com",
            "mediafire.com",
            "cloudflarestorage.com",
            "r2.dev"
        )

        /**
         * يكتشف ما إذا كان الرابط يمثّل تنزيل ملف مباشر أو إعادة توجيه لخادم ملفات (CDN)
         * حتى يُمرَّر إلى مدير التنزيلات بدلًا من حظره كصفحة ويب غير مدرجة.
         */
        fun isLikelyFileDownloadUrl(rawUrl: String?): Boolean {
            if (rawUrl.isNullOrBlank()) return false
            val lower = rawUrl.lowercase()
            if (lower.startsWith("blob:") || lower.startsWith("data:")) return true
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false

            return try {
                val uri = Uri.parse(rawUrl)
                val host = uri.host?.lowercase().orEmpty()
                val path = uri.path?.lowercase().orEmpty()
                val query = uri.query?.lowercase().orEmpty()

                // 1) مؤشرات تنزيل صريحة في معاملات الرابط (AWS S3 / GitHub / Google Drive / Dropbox / R2)
                if (query.contains("response-content-disposition=") ||
                    query.contains("content-disposition=attachment") ||
                    query.contains("export=download") ||
                    query.contains("download=1") ||
                    query.contains("download=true") ||
                    query.contains("dl=1") ||
                    query.contains("attachment=1")
                ) {
                    return true
                }

                // 2) امتداد ملف قابل للتنزيل في نهاية المسار
                val lastSegment = path.substringAfterLast('/', "")
                val ext = lastSegment.substringAfterLast('.', "")
                if (ext.isNotEmpty() && ext in DIRECT_DOWNLOAD_EXTENSIONS) {
                    return true
                }

                // 3) نطاقات CDN مخصصة لتسليم الملفات
                if (KNOWN_DOWNLOAD_CDN_HOSTS.any { cdn -> host == cdn || host.endsWith(".$cdn") }) {
                    return true
                }

                false
            } catch (_: Exception) {
                false
            }
        }

        fun parseContentDisposition(disposition: String?): String? {
            if (disposition.isNullOrBlank()) return null
            try {
                // 1. RFC 5987 / RFC 6266: filename*=charset'lang'encoded_filename
                val extMatch = Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(disposition)
                if (extMatch != null) {
                    val raw = extMatch.groupValues[1].trim(' ', '"', '\'')
                    val parts = raw.split("''", limit = 2)
                    if (parts.size == 2) {
                        val charset = parts[0].ifBlank { "UTF-8" }
                        val encoded = parts[1]
                        val decoded = try {
                            URLDecoder.decode(encoded, charset)
                        } catch (e: Exception) {
                            URLDecoder.decode(encoded, "UTF-8")
                        }
                        if (decoded.isNotBlank()) return decoded
                    }
                }
                // 2. filename="name" or filename=name
                val stdMatch = Regex("""filename\s*=\s*(?:"([^"]+)"|([^;\s]+))""", RegexOption.IGNORE_CASE).find(disposition)
                if (stdMatch != null) {
                    val quoted = stdMatch.groupValues[1]
                    val unquoted = stdMatch.groupValues[2]
                    val name = (if (quoted.isNotBlank()) quoted else unquoted).trim()
                    if (name.isNotBlank()) return name
                }
            } catch (e: Exception) {
                // fallback
            }
            return null
        }

        fun sanitizeFileName(name: String): String =
            name.take(120)
                .replace(Regex("[/\\\\:*?\"<>|]"), "_")
                .ifBlank { "download_${System.currentTimeMillis()}" }

        fun guessExtension(mimeType: String?): String {
            val clean = mimeType?.lowercase()?.substringBefore(';')?.trim() ?: return "bin"
            return when (clean) {
                "application/vnd.android.package-archive" -> "apk"
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                "image/svg+xml" -> "svg"
                "application/pdf" -> "pdf"
                "application/zip" -> "zip"
                "application/vnd.rar", "application/x-rar-compressed" -> "rar"
                "application/x-7z-compressed" -> "7z"
                "application/msword" -> "doc"
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
                "application/vnd.ms-excel" -> "xls"
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
                "application/vnd.ms-powerpoint" -> "ppt"
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
                "application/epub+zip" -> "epub"
                "text/plain" -> "txt"
                "text/csv" -> "csv"
                "application/json" -> "json"
                "text/html" -> "html"
                "audio/mpeg" -> "mp3"
                "audio/mp4" -> "m4a"
                "audio/ogg" -> "ogg"
                "audio/wav" -> "wav"
                "video/mp4" -> "mp4"
                "video/x-matroska" -> "mkv"
                "video/webm" -> "webm"
                else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(clean) ?: "bin"
            }
        }
    }
}

