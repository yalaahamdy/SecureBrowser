package com.securebrowser.app.browser

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
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

    /** "موقع الملف" — المسار الفعلي للملف على الجهاز (العام أو المحلي). */
    fun fileLocation(id: Long, callback: (String?) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val record = repository.findById(id)
            val localFile = record?.filePath?.let { File(downloadsDir(), it) }
            val publicFile = localFile?.let {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), it.name)
            }
            val displayPath = when {
                publicFile != null && publicFile.exists() -> publicFile.absolutePath
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && localFile != null && localFile.exists() ->
                    "${Environment.DIRECTORY_DOWNLOADS}/${localFile.name}"
                localFile != null && localFile.exists() -> localFile.absolutePath
                else -> null
            }
            mainHandler.post { callback(displayPath) }
        }
    }

    /** تصدير صريح للملف المنزَّل إلى مجلد التنزيلات العام بالجهاز عند طلب المستخدم. */
    fun exportToPublicDownloads(id: Long, callback: (Boolean, String?) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val record = repository.findById(id)
            val file = record?.filePath?.let { File(downloadsDir(), it) }
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

    // ————————————————— محرك النقل الفعلي —————————————————

    private fun startTransfer(id: Long, customUserAgent: String? = null) {
        val job = scope.launch(Dispatchers.IO) {
            val record = repository.findById(id) ?: return@launch
            var targetFile: File? = null
            try {
                val existing = if ((record.downloadedBytes ?: 0) > 0) {
                    File(downloadsDir(), record.filePath ?: "").takeIf { it.exists() }
                } else null
                val resumeFrom = existing?.length() ?: 0

                val file = existing ?: uniqueFile(resolveLocalName(record))
                targetFile = file
                if (existing == null) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                    repository.updateFilePath(id, file.name)
                }

                // 1. معالجة روابط Data URLs في الذاكرة
                val dataPayload = inMemoryDataUrls.remove(id)
                if (dataPayload != null || record.url.startsWith("data:", ignoreCase = true)) {
                    val rawData = dataPayload ?: record.url
                    processDataUrl(id, rawData, file, record)
                    return@launch
                }

                // 2. معالجة طلب الشبكة مع الـ Redirects المتعاقبة (حتى 10 مرات)
                var currentUrl = record.url
                var redirects = 0
                val maxRedirects = 10
                var conn: HttpURLConnection? = null
                var code = 0

                while (redirects < maxRedirects) {
                    val targetUri = URL(currentUrl)
                    val c = targetUri.openConnection() as HttpURLConnection
                    c.connectTimeout = 20_000
                    c.readTimeout = 30_000
                    c.instanceFollowRedirects = false // تتبع يدوي شامل لـ HTTP<->HTTPS و 307/308
                    c.useCaches = false

                    if (resumeFrom > 0 && redirects == 0) {
                        c.setRequestProperty("Range", "bytes=$resumeFrom-")
                    }
                    val cookie = try {
                        CookieManager.getInstance().getCookie(currentUrl)
                    } catch (e: Exception) { null }
                    if (!cookie.isNullOrBlank()) {
                        c.setRequestProperty("Cookie", cookie)
                    }
                    c.setRequestProperty("User-Agent", customUserAgent?.takeIf { it.isNotBlank() } ?: WEB_UA)
                    c.setRequestProperty("Accept", "*/*")
                    c.setRequestProperty("Accept-Encoding", "identity")
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
                        currentUrl = URL(targetUri, location).toString()
                        redirects++
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
                        val buffer = ByteArray(32 * 1024)
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

                // حفظ نسخة في مجلد التنزيلات العام بالجهاز وإشعار النظام
                publishToPublicDownloads(context, file, record.mimeType)
                toast(context.getString(R.string.toast_download_completed, file.name))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
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
     * نشر أو حفظ الملف في مجلد التنزيلات العام بالجهاز (Public Downloads):
     * - على Android 10+ (API 29+): MediaStore.Downloads (يظهر مباشرة في تطبيق التنزيلات ومدير الملفات).
     * - على Android 9 وما قبله: Environment.getExternalStoragePublicDirectory.
     */
    fun publishToPublicDownloads(context: Context, sourceFile: File, mimeType: String?): String? {
        val mime = mimeType ?: guessMime(sourceFile.name)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, sourceFile.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { input -> input.copyTo(out) }
                }
                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                try {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sourceFile.name).absolutePath),
                        arrayOf(mime),
                        null
                    )
                } catch (e: Exception) {
                    // تجاهل
                }
                return "${Environment.DIRECTORY_DOWNLOADS}/${sourceFile.name}"
            } else {
                val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (publicDir.exists() || publicDir.mkdirs()) {
                    var candidate = File(publicDir, sourceFile.name)
                    if (candidate.exists()) {
                        val dot = sourceFile.name.lastIndexOf('.')
                        val base = if (dot > 0) sourceFile.name.substring(0, dot) else sourceFile.name
                        val ext = if (dot > 0) sourceFile.name.substring(dot) else ""
                        var i = 1
                        while (candidate.exists()) {
                            candidate = File(publicDir, "$base ($i)$ext")
                            i++
                        }
                    }
                    sourceFile.copyTo(candidate, overwrite = true)
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(candidate.absolutePath),
                        arrayOf(mime),
                        null
                    )
                    return candidate.absolutePath
                }
            }
        } catch (e: Exception) {
            // فشل النسخ العام لا يقطع حفظ الملف الأصلي
        }
        return null
    }

    private fun resolveFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        val parsed = parseContentDisposition(contentDisposition)
        if (!parsed.isNullOrBlank()) {
            return sanitizeFileName(parsed)
        }
        val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
        if (guessed.isNotBlank() && !guessed.equals("downloadfile.bin", ignoreCase = true)) {
            return sanitizeFileName(guessed)
        }
        val ext = guessExtension(mimeType)
        return "download_${System.currentTimeMillis()}.$ext"
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

        fun guessExtension(mimeType: String?): String = when (mimeType?.lowercase()?.substringBefore(';')?.trim()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/svg+xml" -> "svg"
            "application/pdf" -> "pdf"
            "application/zip" -> "zip"
            "text/plain" -> "txt"
            "text/html" -> "html"
            "audio/mpeg" -> "mp3"
            "video/mp4" -> "mp4"
            else -> "bin"
        }
    }
}

