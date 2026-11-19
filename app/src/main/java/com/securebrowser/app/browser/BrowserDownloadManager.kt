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

    data class LiveMetrics(
        val bytesPerSecond: Long,
        val etaSeconds: Long
    )

    private val jobs = ConcurrentHashMap<Long, Job>()
    private val pauseFlags = ConcurrentHashMap<Long, Boolean>()
    private val liveMetrics = ConcurrentHashMap<Long, LiveMetrics>()
    private val fullUrls = ConcurrentHashMap<Long, String>()
    private val publicPaths = ConcurrentHashMap<Long, String>()
    private val blobStreams = ConcurrentHashMap<Long, BlobStreamSession>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inMemoryDataUrls = ConcurrentHashMap<Long, String>()

    private data class BlobStreamSession(
        val file: File,
        val output: java.io.FileOutputStream,
        val totalBytes: Long,
        var writtenBytes: Long = 0L,
        val mimeType: String?,
        var lastUiUpdateMs: Long = 0L
    )

    @Volatile
    var onDownloadStartedListener: ((id: Long, fileName: String) -> Unit)? = null

    fun getLiveMetrics(id: Long): LiveMetrics? = liveMetrics[id]

    fun getPublicPath(id: Long): String? = publicPaths[id]

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
                    } else {
                        fullUrls[id] = url
                    }
                    mainHandler.post {
                        onDownloadStartedListener?.invoke(id, fileName)
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
                    } else {
                        fullUrls[id] = url
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
        liveMetrics.remove(id)
        DownloadNotificationHelper.cancel(context, id)
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
            // حذف أي بقايا ملف جزئي تالف من المحاولة الفاشلة وتصفير العداد للبدء بنظافة
            record.filePath?.let { File(downloadsDir(), it).delete() }
            repository.updateProgress(id, 0L, record.sizeBytes)
            repository.updateState(id, DownloadRepository.STATE_DOWNLOADING)
            startTransfer(id)
        }
    }

    /** حذف السجل والملف المحلي معًا (أو إلغاء التنزيل الجاري فورًا). */
    fun delete(id: Long) {
        scope.launch(Dispatchers.IO) {
            pauseFlags[id] = true
            jobs.remove(id)?.cancelAndJoin()
            blobStreams.remove(id)?.let { session ->
                try {
                    session.output.close()
                } catch (_: Exception) {
                }
                session.file.delete()
            }
            liveMetrics.remove(id)
            fullUrls.remove(id)
            publicPaths.remove(id)
            DownloadNotificationHelper.cancel(context, id)
            val record = repository.findById(id) ?: return@launch
            record.filePath?.let { relative ->
                File(downloadsDir(), relative).delete()
            }
            repository.delete(id)
        }
    }

    /** مسح سجل التنزيلات المنتهية (المكتملة/الفاشلة/المحظورة) دون المساس بالملفات المحفوظة في مجلد Download بالهاتف. */
    fun clearFinished() {
        scope.launch(Dispatchers.IO) {
            repository.clearFinished()
        }
    }

    /** إضافة رابط تنزيل مباشر يدويًا من شاشة التنزيلات مع إمكانية تحديد اسم الملف. */
    fun enqueueManualDownload(rawUrl: String, customFileName: String? = null) {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return
        val normalizedUrl = if (!trimmed.contains("://") && !trimmed.startsWith("data:", true)) {
            "https://$trimmed"
        } else {
            trimmed
        }
        val contentDisp = customFileName?.trim()?.takeIf { it.isNotBlank() }?.let {
            "attachment; filename=\"$it\""
        }
        handleDownload(
            url = normalizedUrl,
            userAgent = WEB_UA,
            contentDisposition = contentDisp,
            mimeType = null,
            contentLength = -1L,
            sourceUrl = normalizedUrl,
            sourceAllowed = true
        )
    }

    /** إعادة تسمية ملف منزّل (في المجلد المحلي ومجلد Download العام وقاعدة البيانات). */
    fun renameDownload(id: Long, newRawName: String, callback: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val record = repository.findById(id)
            if (record == null) {
                mainHandler.post { callback(false) }
                return@launch
            }
            val oldName = record.filePath?.takeIf { it.isNotBlank() } ?: record.fileName
            val oldExt = oldName.substringAfterLast('.', "")
            var sanitized = sanitizeFileName(newRawName.trim())
            if (!sanitized.contains('.') && oldExt.isNotBlank()) {
                sanitized = "$sanitized.$oldExt"
            }

            val localOld = File(downloadsDir(), oldName)
            var finalLocalName = sanitized
            if (localOld.exists()) {
                val localNew = uniqueFile(sanitized)
                if (localOld.renameTo(localNew)) {
                    finalLocalName = localNew.name
                }
            }

            try {
                val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val pubOld = File(publicDir, oldName)
                if (pubOld.exists()) {
                    val pubNew = File(publicDir, finalLocalName)
                    pubOld.renameTo(pubNew)
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(pubNew.absolutePath),
                        arrayOf(record.mimeType ?: guessMime(finalLocalName)),
                        null
                    )
                }
            } catch (_: Exception) {
            }

            repository.updateFileMetadata(
                id = id,
                fileName = finalLocalName,
                filePath = finalLocalName,
                mimeType = record.mimeType ?: guessMime(finalLocalName)
            )
            mainHandler.post { callback(true) }
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

    /** البحث عن Uri صالح لفتح أو مشاركة الملف (محلي، مجلد Download العام، أو MediaStore). */
    private fun resolveOpenOrShareUri(record: DownloadRecordEntity): Pair<Uri, String>? {
        val file = findExistingDownloadFile(record)
        if (file != null) {
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            return uri to resolveOpenMimeType(file.name, record.mimeType)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val candidates = listOfNotNull(
                record.fileName.takeIf { it.isNotBlank() },
                record.filePath?.takeIf { it.isNotBlank() }
            ).distinct()
            for (name in candidates) {
                val mediaUri = queryMediaStoreDownloadUri(name)
                if (mediaUri != null) {
                    return mediaUri to resolveOpenMimeType(name, record.mimeType)
                }
            }
        }
        return null
    }

    private fun queryMediaStoreDownloadUri(displayName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(displayName),
                "${MediaStore.Downloads.DATE_ADDED} DESC"
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idCol = c.getColumnIndex(MediaStore.Downloads._ID)
                    if (idCol >= 0) {
                        android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            c.getLong(idCol)
                        )
                    } else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** فتح الملف المنزَّل بأي تطبيق يستطيع التعامل معه. */
    fun openFile(id: Long) {
        scope.launch(Dispatchers.IO) {
            val record = repository.findById(id) ?: return@launch
            val (uri, resolvedMime) = resolveOpenOrShareUri(record) ?: run {
                toast(context.getString(R.string.toast_download_failed, record.fileName))
                return@launch
            }
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
            val (uri, resolvedMime) = resolveOpenOrShareUri(record) ?: return@launch
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
            val cachedPublic = publicPaths[id]
            val record = repository.findById(id)
            val fileName = record?.filePath?.takeIf { it.isNotBlank() } ?: record?.fileName
            val localFile = fileName?.let { File(downloadsDir(), it) }
            val publicFile = fileName?.let {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), it)
            }
            val displayPath = when {
                !cachedPublic.isNullOrBlank() -> cachedPublic
                publicFile != null && publicFile.exists() -> publicFile.absolutePath
                localFile != null && localFile.exists() ->
                    "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath}/${localFile.name}"
                else -> null
            }
            mainHandler.post { callback(displayPath) }
        }
    }

    // ————————————————— جسر تنزيل روابط blob: المقسّم (Chunked Streaming) —————————————————

    fun startBlobStream(
        suggestedName: String?,
        mimeType: String?,
        totalBytes: Long,
        sourceUrl: String?
    ): Long {
        val cleanMime = mimeType?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
        val fileName = resolveFileName("blob://local", suggestedName?.let { "attachment; filename=\"$it\"" }, cleanMime)
        val file = uniqueFile(fileName)
        file.parentFile?.mkdirs()
        file.createNewFile()

        val id = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            repository.insert(
                record(
                    url = "blob:${file.name}",
                    fileName = file.name,
                    sourceUrl = sourceUrl,
                    mimeType = cleanMime ?: guessMime(file.name),
                    contentLength = totalBytes
                ).copy(
                    state = DownloadRepository.STATE_DOWNLOADING,
                    filePath = file.name
                )
            )
        }
        blobStreams[id] = BlobStreamSession(
            file = file,
            output = java.io.FileOutputStream(file, false),
            totalBytes = totalBytes,
            mimeType = cleanMime ?: guessMime(file.name)
        )
        mainHandler.post {
            onDownloadStartedListener?.invoke(id, file.name)
        }
        DownloadNotificationHelper.showProgress(context, id, file.name, 0L, totalBytes.takeIf { it > 0 }, null)
        toast(context.getString(R.string.toast_download_started))
        return id
    }

    fun appendBlobChunk(id: Long, base64Chunk: String) {
        val session = blobStreams[id] ?: return
        try {
            val cleanBase64 = if (base64Chunk.contains(",")) base64Chunk.substringAfter(',') else base64Chunk
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            if (bytes.isNotEmpty()) {
                session.output.write(bytes)
                session.writtenBytes += bytes.size
                val now = System.currentTimeMillis()
                if (now - session.lastUiUpdateMs >= PROGRESS_DB_INTERVAL_MS) {
                    session.lastUiUpdateMs = now
                    val total = session.totalBytes.takeIf { it > 0 }
                    val written = session.writtenBytes
                    scope.launch(Dispatchers.IO) {
                        repository.updateProgress(id, written, total)
                    }
                    DownloadNotificationHelper.showProgress(context, id, session.file.name, written, total, null)
                }
            }
        } catch (_: Exception) {
            failBlobStream(id)
        }
    }

    fun finishBlobStream(id: Long) {
        val session = blobStreams.remove(id) ?: return
        scope.launch(Dispatchers.IO) {
            try {
                session.output.flush()
                session.output.close()
                val finalSize = session.file.length().coerceAtLeast(session.writtenBytes)
                repository.updateProgress(id, finalSize, finalSize)
                repository.updateState(id, DownloadRepository.STATE_COMPLETED)
                val pub = publishToPublicDownloads(context, session.file, session.mimeType)
                if (pub != null) publicPaths[id] = pub
                DownloadNotificationHelper.showCompleted(context, id, session.file.name)
                toast(context.getString(R.string.toast_download_completed, session.file.name))
            } catch (_: Exception) {
                repository.updateState(id, DownloadRepository.STATE_FAILED)
                DownloadNotificationHelper.showFailed(context, id, session.file.name)
                toast(context.getString(R.string.toast_download_failed, session.file.name))
            }
        }
    }

    fun failBlobStream(id: Long) {
        val session = blobStreams.remove(id) ?: return
        try {
            session.output.close()
        } catch (_: Exception) {
        }
        scope.launch(Dispatchers.IO) {
            repository.updateState(id, DownloadRepository.STATE_FAILED)
            DownloadNotificationHelper.showFailed(context, id, session.file.name)
            toast(context.getString(R.string.toast_download_failed, session.file.name))
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
            if (path != null) publicPaths[id] = path
            mainHandler.post {
                callback(path != null, path)
            }
        }
    }

    // ————————————————— محرك النقل الفعلي (متعدد الاستراتيجيات + DownloadManager النظامي) —————————————————

    private fun startTransfer(id: Long, customUserAgent: String? = null) {
        val job = scope.launch(Dispatchers.IO) {
            val record = repository.findById(id) ?: return@launch
            val rawTargetUrl = fullUrls[id] ?: record.url
            var targetFile: File? = null
            try {
                val existing = if ((record.downloadedBytes ?: 0) > 0 && !record.filePath.isNullOrBlank()) {
                    File(downloadsDir(), record.filePath).takeIf { it.exists() && it.length() > 0 }
                } else null
                val resumeFrom = existing?.length() ?: 0L

                var file = existing ?: uniqueFile(resolveLocalName(record))
                targetFile = file
                if (existing == null) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                    repository.updateFilePath(id, file.name)
                }

                // 1. معالجة روابط Data URLs في الذاكرة
                val dataPayload = inMemoryDataUrls.remove(id)
                if (dataPayload != null || rawTargetUrl.startsWith("data:", ignoreCase = true)) {
                    val rawData = dataPayload ?: rawTargetUrl
                    processDataUrl(id, rawData, file, record)
                    return@launch
                }

                // 2. المحاولة الأولى: بالـ Cookies الكاملة + Referer
                val firstTry = runDirectHttpTransfer(
                    id = id,
                    record = record,
                    rawUrl = rawTargetUrl,
                    fileInitial = file,
                    initialResumeFrom = resumeFrom,
                    customUserAgent = customUserAgent,
                    sendReferer = true
                )
                file = firstTry.finalFile
                targetFile = file
                if (firstTry.pausedByUser) return@launch
                if (firstTry.completed) {
                    completeTransferSuccess(id, file, firstTry.effectiveMime)
                    return@launch
                }

                // 3. المحاولة الثانية: إعادة ذكية بدون Referer وبدون Range (تحل رفض السيرفرات والـ CDN لطلب الإحالة)
                if (pauseFlags[id] != true) {
                    file.writeBytes(ByteArray(0))
                    val retryTry = runDirectHttpTransfer(
                        id = id,
                        record = record,
                        rawUrl = rawTargetUrl,
                        fileInitial = file,
                        initialResumeFrom = 0L,
                        customUserAgent = customUserAgent,
                        sendReferer = false
                    )
                    file = retryTry.finalFile
                    targetFile = file
                    if (retryTry.pausedByUser) return@launch
                    if (retryTry.completed) {
                        completeTransferSuccess(id, file, retryTry.effectiveMime)
                        return@launch
                    }
                }

                // 4. المحاولة الثالثة: المحرك الاحتياطي DownloadManager النظامي لأندرويد
                val isHttp = rawTargetUrl.startsWith("http://", ignoreCase = true) ||
                    rawTargetUrl.startsWith("https://", ignoreCase = true)
                if (isHttp && pauseFlags[id] != true) {
                    val fallbackSuccess = runSystemDownloadManagerFallback(id, record, rawTargetUrl, customUserAgent)
                    if (fallbackSuccess) return@launch
                }

                throw IllegalStateException("All download strategies failed")
            } catch (e: kotlinx.coroutines.CancellationException) {
                liveMetrics.remove(id)
                throw e
            } catch (e: Exception) {
                liveMetrics.remove(id)
                val isHttp = rawTargetUrl.startsWith("http://", ignoreCase = true) ||
                    rawTargetUrl.startsWith("https://", ignoreCase = true)
                if (isHttp && pauseFlags[id] != true) {
                    val fallbackSuccess = runSystemDownloadManagerFallback(id, record, rawTargetUrl, customUserAgent)
                    if (fallbackSuccess) return@launch
                }

                val current = repository.findById(id)
                if (current?.state == DownloadRepository.STATE_DOWNLOADING) {
                    repository.updateState(id, DownloadRepository.STATE_FAILED)
                }
                val name = targetFile?.name ?: record.fileName
                DownloadNotificationHelper.showFailed(context, id, name)
                toast(context.getString(R.string.toast_download_failed, name))
            } finally {
                liveMetrics.remove(id)
                jobs.remove(id)
                pauseFlags.remove(id)
            }
        }
        jobs[id] = job
    }

    private data class DirectTransferResult(
        val completed: Boolean,
        val pausedByUser: Boolean,
        val finalFile: File,
        val effectiveMime: String?
    )

    private suspend fun runDirectHttpTransfer(
        id: Long,
        record: DownloadRecordEntity,
        rawUrl: String,
        fileInitial: File,
        initialResumeFrom: Long,
        customUserAgent: String?,
        sendReferer: Boolean
    ): DirectTransferResult {
        var file = fileInitial
        var resumeFrom = initialResumeFrom
        var currentUrl = toSafeHttpUrl(rawUrl)
        var redirects = 0
        val maxRedirects = 15
        var conn: HttpURLConnection? = null
        var code = 0
        val accumulatedCookies = LinkedHashMap<String, String>()

        return try {
            try {
                CookieManager.getInstance().flush()
            } catch (_: Exception) {
            }

            while (redirects < maxRedirects) {
                val targetUri = URL(currentUrl)
                val c = (targetUri.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 25_000
                    readTimeout = 45_000
                    instanceFollowRedirects = false
                    useCaches = false
                }

                if (resumeFrom > 0) {
                    c.setRequestProperty("Range", "bytes=$resumeFrom-")
                }
                val cookieHeader = buildMergedCookieHeader(currentUrl, record.sourceUrl, accumulatedCookies)
                if (!cookieHeader.isNullOrBlank()) {
                    c.setRequestProperty("Cookie", cookieHeader)
                }
                c.setRequestProperty("User-Agent", customUserAgent?.takeIf { it.isNotBlank() } ?: WEB_UA)
                c.setRequestProperty("Accept", "*/*")
                c.setRequestProperty("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                if (sendReferer) {
                    val referer = record.sourceUrl?.takeIf { it.startsWith("http", ignoreCase = true) }
                    if (!referer.isNullOrBlank()) {
                        c.setRequestProperty("Referer", referer)
                    }
                }

                c.connect()
                code = c.responseCode
                collectSetCookies(c, currentUrl, accumulatedCookies)

                if (code in 301..303 || code == 307 || code == 308) {
                    val location = c.getHeaderField("Location")
                    c.disconnect()
                    if (location.isNullOrBlank()) {
                        return DirectTransferResult(false, false, file, null)
                    }
                    currentUrl = toSafeHttpUrl(URL(targetUri, location).toString())
                    redirects++
                    continue
                }

                if (resumeFrom > 0 && (code == 416 || code == 400 || code == 403)) {
                    c.disconnect()
                    resumeFrom = 0L
                    file.writeBytes(ByteArray(0))
                    continue
                }

                conn = c
                break
            }

            val activeConn = conn ?: return DirectTransferResult(false, false, file, null)
            if (code !in 200..299 && code != 206) {
                activeConn.disconnect()
                return DirectTransferResult(false, false, file, null)
            }

            val serverDisposition = activeConn.getHeaderField("Content-Disposition")
            val rawContentType = activeConn.contentType?.substringBefore(';')?.trim()?.lowercase()
            val expectedExt = record.fileName.substringAfterLast('.', "").lowercase()
            val expectsNonHtmlFile = expectedExt.isNotBlank() &&
                expectedExt !in setOf("html", "htm", "xhtml", "php", "asp", "aspx", "jsp")

            // إذا طلبنا ملفًا ثنائيًا محددًا (مثل PDF/ZIP/APK) وعاد الخادم بصفحة HTML بدون مرفق -> فشل استجابة مزيفة
            if (expectsNonHtmlFile &&
                rawContentType == "text/html" &&
                serverDisposition?.contains("attachment", ignoreCase = true) != true
            ) {
                activeConn.disconnect()
                return DirectTransferResult(false, false, file, null)
            }

            val serverMime = rawContentType?.takeIf {
                it.isNotBlank() && it != "*/*" && it != "application/octet-stream"
            }
            val effectiveMime = serverMime ?: record.mimeType?.takeIf { it.isNotBlank() && it != "*/*" }

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
            var speedWindowStartMs = System.currentTimeMillis()
            var speedWindowStartBytes = resumeFrom
            var smoothedBps = 0L
            val append = resumeFrom > 0 && code == 206
            if (resumeFrom > 0 && !append) {
                downloaded = 0
                speedWindowStartBytes = 0
                file.writeBytes(ByteArray(0))
            }

            DownloadNotificationHelper.showProgress(
                context = context,
                id = id,
                fileName = file.name,
                downloadedBytes = downloaded,
                totalBytes = total,
                speedText = null
            )

            activeConn.inputStream.use { input ->
                java.io.FileOutputStream(file, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (pauseFlags[id] == true) {
                            liveMetrics.remove(id)
                            DownloadNotificationHelper.cancel(context, id)
                            repository.updateStateAndProgress(
                                id, DownloadRepository.STATE_PAUSED, downloaded
                            )
                            return DirectTransferResult(false, true, file, effectiveMime)
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastDbUpdate > PROGRESS_DB_INTERVAL_MS) {
                            val elapsedMs = (now - speedWindowStartMs).coerceAtLeast(1L)
                            val deltaBytes = (downloaded - speedWindowStartBytes).coerceAtLeast(0L)
                            val instantBps = (deltaBytes * 1000L) / elapsedMs
                            smoothedBps = if (smoothedBps == 0L) instantBps
                            else ((smoothedBps * 6L + instantBps * 4L) / 10L)
                            speedWindowStartMs = now
                            speedWindowStartBytes = downloaded

                            val etaSec = if (total != null && total > downloaded && smoothedBps > 0L) {
                                (total - downloaded) / smoothedBps
                            } else -1L
                            liveMetrics[id] = LiveMetrics(smoothedBps, etaSec)

                            repository.updateProgress(id, downloaded, total)
                            val speedStr = if (smoothedBps > 0) formatBytesPerSec(smoothedBps) else null
                            DownloadNotificationHelper.showProgress(
                                context = context,
                                id = id,
                                fileName = file.name,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                speedText = speedStr
                            )
                            lastDbUpdate = now
                        }
                    }
                    output.flush()
                }
            }

            if (file.length() <= 0L && downloaded <= 0L) {
                return DirectTransferResult(false, false, file, effectiveMime)
            }

            DirectTransferResult(true, false, file, effectiveMime ?: guessMime(file.name))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            DirectTransferResult(false, false, file, null)
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun completeTransferSuccess(id: Long, file: File, effectiveMime: String?) {
        liveMetrics.remove(id)
        fullUrls.remove(id)
        val finalSize = file.length()
        repository.updateProgress(id, finalSize, finalSize.takeIf { it > 0 })
        repository.updateState(id, DownloadRepository.STATE_COMPLETED)
        val pub = publishToPublicDownloads(context, file, effectiveMime ?: guessMime(file.name))
        if (pub != null) publicPaths[id] = pub
        DownloadNotificationHelper.showCompleted(context, id, file.name)
        toast(context.getString(R.string.toast_download_completed, file.name))
    }

    /**
     * محرك احتياطي عبر [DownloadManager] الرسمي لنظام أندرويد:
     * يعمل تلقائيًا إذا رفض خادم معيّن اتصال HttpURLConnection المباشر،
     * ويحفظ الملف مباشرةً في مجلد Download العام للهاتف مع متابعة حالته حتى يكتمل.
     */
    private suspend fun runSystemDownloadManagerFallback(
        id: Long,
        record: DownloadRecordEntity,
        rawUrl: String,
        customUserAgent: String?
    ): Boolean {
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
            val safeUrl = toSafeHttpUrl(rawUrl)
            val baseName = sanitizeFileName(record.fileName.ifBlank { "download_${System.currentTimeMillis()}" })
            val uniquePublicName = uniquePublicFileName(baseName)
            val mime = guessMime(uniquePublicName, record.mimeType)

            val req = DownloadManager.Request(Uri.parse(safeUrl)).apply {
                setTitle(uniquePublicName)
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
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, uniquePublicName)
            }

            val sysId = dm.enqueue(req)
            val query = DownloadManager.Query().setFilterById(sysId)
            var completed = false
            var failed = false
            var attempts = 0
            var stalledPausedCount = 0
            var speedWindowStartMs = System.currentTimeMillis()
            var speedWindowStartBytes = 0L
            var smoothedBps = 0L
            while (!completed && !failed && attempts < 1800) {
                if (pauseFlags[id] == true) {
                    liveMetrics.remove(id)
                    DownloadNotificationHelper.cancel(context, id)
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

                        val now = System.currentTimeMillis()
                        val elapsedMs = (now - speedWindowStartMs).coerceAtLeast(1L)
                        if (elapsedMs >= 500L) {
                            val delta = (soFar - speedWindowStartBytes).coerceAtLeast(0L)
                            val instantBps = (delta * 1000L) / elapsedMs
                            smoothedBps = if (smoothedBps == 0L) instantBps
                            else ((smoothedBps * 6L + instantBps * 4L) / 10L)
                            speedWindowStartMs = now
                            speedWindowStartBytes = soFar
                            val etaSec = if (total != null && total > soFar && smoothedBps > 0L) {
                                (total - soFar) / smoothedBps
                            } else -1L
                            liveMetrics[id] = LiveMetrics(smoothedBps, etaSec)
                            val speedStr = if (smoothedBps > 0) formatBytesPerSec(smoothedBps) else null
                            DownloadNotificationHelper.showProgress(
                                context, id, uniquePublicName, soFar, total, speedStr
                            )
                        }

                        repository.updateProgress(id, soFar, total)
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> completed = true
                            DownloadManager.STATUS_FAILED -> failed = true
                            DownloadManager.STATUS_PAUSED -> {
                                stalledPausedCount++
                                if (stalledPausedCount > 30) failed = true
                            }
                            else -> stalledPausedCount = 0
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
                val localCopy = uniqueFile(uniquePublicName)
                var copiedOk = false
                try {
                    val sysUri = dm.getUriForDownloadedFile(sysId)
                    if (sysUri != null) {
                        context.contentResolver.openInputStream(sysUri)?.use { input ->
                            localCopy.outputStream().use { out -> input.copyTo(out) }
                            copiedOk = true
                        }
                    }
                } catch (_: Exception) {
                }
                if (!copiedOk) {
                    try {
                        dm.openDownloadedFile(sysId)?.use { pfd ->
                            java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                                localCopy.outputStream().use { out -> input.copyTo(out) }
                                copiedOk = true
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                if (!copiedOk) {
                    val pubFile = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        uniquePublicName
                    )
                    if (pubFile.exists()) {
                        pubFile.copyTo(localCopy, overwrite = true)
                    }
                }
                val pubPath = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    uniquePublicName
                ).absolutePath
                publicPaths[id] = pubPath
                val finalSize = localCopy.length().takeIf { it > 0 } ?: record.sizeBytes ?: 0L
                repository.updateFileMetadata(id, uniquePublicName, localCopy.name, mime)
                repository.updateProgress(id, finalSize, finalSize.takeIf { it > 0 })
                repository.updateState(id, DownloadRepository.STATE_COMPLETED)
                fullUrls.remove(id)
                DownloadNotificationHelper.showCompleted(context, id, uniquePublicName)
                toast(context.getString(R.string.toast_download_completed, uniquePublicName))
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun uniquePublicFileName(baseName: String): String {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var candidate = File(publicDir, baseName)
        if (!candidate.exists()) return baseName
        val dot = baseName.lastIndexOf('.')
        val base = if (dot > 0) baseName.substring(0, dot) else baseName
        val ext = if (dot > 0) baseName.substring(dot) else ""
        var i = 1
        while (candidate.exists() && i < 100) {
            val next = "$base ($i)$ext"
            candidate = File(publicDir, next)
            if (!candidate.exists()) return next
            i++
        }
        return "${System.currentTimeMillis()}_$baseName"
    }

    private fun collectSetCookies(
        conn: HttpURLConnection,
        url: String,
        bag: MutableMap<String, String>
    ) {
        try {
            val cm = CookieManager.getInstance()
            val headerCookies = conn.headerFields?.entries
                ?.filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                ?.flatMap { it.value.orEmpty() }
                .orEmpty()
            for (rawCookie in headerCookies) {
                cm.setCookie(url, rawCookie)
                val pair = rawCookie.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    bag[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun buildMergedCookieHeader(
        currentUrl: String,
        sourceUrl: String?,
        bag: Map<String, String>
    ): String? {
        val parts = LinkedHashMap<String, String>()
        val base = resolveCookiesForUrl(currentUrl, sourceUrl)
        if (!base.isNullOrBlank()) {
            for (token in base.split(';')) {
                val eq = token.indexOf('=')
                if (eq > 0) {
                    parts[token.substring(0, eq).trim()] = token.substring(eq + 1).trim()
                }
            }
        }
        parts.putAll(bag)
        if (parts.isEmpty()) return null
        return parts.entries.joinToString("; ") { "${it.key}=${it.value}" }
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
        DownloadNotificationHelper.showCompleted(context, id, file.name)
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
                for (attempt in 0..12) {
                    val candidateName = when {
                        attempt == 0 -> sourceFile.name
                        attempt <= 10 -> "$base ($attempt)$ext"
                        attempt == 11 -> {
                            val asciiBase = base.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                .ifBlank { "download" }.take(60)
                            "${asciiBase}_${System.currentTimeMillis()}$ext"
                        }
                        else -> "download_${System.currentTimeMillis()}$ext"
                    }
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

    private fun toSafeHttpUrl(rawUrl: String): String = toSafeHttpUrlStatic(rawUrl)

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

    private fun formatBytesPerSec(bps: Long): String {
        return when {
            bps >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB/s", bps.toDouble() / (1024.0 * 1024.0))
            bps >= 1024L -> String.format(java.util.Locale.US, "%d KB/s", bps / 1024L)
            else -> "$bps B/s"
        }
    }

    companion object {
        private const val PROGRESS_DB_INTERVAL_MS = 500L
        private const val WEB_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

        fun toSafeHttpUrlStatic(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            val sb = StringBuilder(trimmed.length)
            for (ch in trimmed) {
                when {
                    ch == ' ' -> sb.append("%20")
                    ch.code > 127 -> sb.append(java.net.URLEncoder.encode(ch.toString(), "UTF-8"))
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

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
            "firebasestorage.googleapis.com",
            "storage.googleapis.com",
            "dl.dropboxusercontent.com",
            "dropboxusercontent.com",
            "dl.dropbox.com",
            "1drv.ms",
            "onedrive.live.com",
            "amazonaws.com",
            "digitaloceanspaces.com",
            "cloudflarestorage.com",
            "r2.dev",
            "mediafire.com",
            "cdn-telegram.org",
            "pixeldrain.com",
            "gofile.io",
            "archive.org",
            "sourceforge.net",
            "blob.core.windows.net",
            "akamaized.net",
            "b-cdn.net"
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
                val parsedUri = java.net.URI(toSafeHttpUrlStatic(rawUrl))
                val host = parsedUri.host?.lowercase().orEmpty()
                val path = parsedUri.path?.lowercase().orEmpty()
                val query = parsedUri.rawQuery?.lowercase().orEmpty()

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

        data class ProbeResult(
            val isFileDownload: Boolean,
            val finalUrl: String,
            val contentDisposition: String?,
            val mimeType: String?,
            val contentLength: Long
        )

        /**
         * فحص سريع للترويسات (HEAD ثم GET جزئي) لمعرفة ما إذا كان رابط تحويل (Redirect)
         * من داخل صفحة مسموحة يشير إلى تنزيل ملف ثنائي/مرفق بدل صفحة ويب HTML.
         */
        fun probeUrlForDownload(
            rawUrl: String,
            userAgent: String?,
            sourceUrl: String?
        ): ProbeResult {
            var currentUrl = rawUrl.trim()
            var redirects = 0
            while (redirects < 8) {
                var conn: HttpURLConnection? = null
                try {
                    val u = URL(currentUrl)
                    conn = (u.openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = 4_000
                        readTimeout = 4_000
                        instanceFollowRedirects = false
                        useCaches = false
                        setRequestProperty("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: WEB_UA)
                        val cookie = try { CookieManager.getInstance().getCookie(currentUrl) } catch (_: Exception) { null }
                        if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                        if (!sourceUrl.isNullOrBlank() && sourceUrl.startsWith("http", true)) {
                            setRequestProperty("Referer", sourceUrl)
                        }
                    }
                    conn.connect()
                    var code = conn.responseCode
                    if (code in 301..303 || code == 307 || code == 308) {
                        val loc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (loc.isNullOrBlank()) break
                        currentUrl = URL(u, loc).toString()
                        redirects++
                        continue
                    }
                    // إذا رفض الخادم HEAD (405 / 501 / 403) نجرب طلب GET بالبايت الأول فقط
                    if (code == 405 || code == 501 || code == 403 || code == 400) {
                        conn.disconnect()
                        conn = (u.openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 4_000
                            readTimeout = 4_000
                            instanceFollowRedirects = false
                            useCaches = false
                            setRequestProperty("Range", "bytes=0-0")
                            setRequestProperty("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: WEB_UA)
                            val cookie = try { CookieManager.getInstance().getCookie(currentUrl) } catch (_: Exception) { null }
                            if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                            if (!sourceUrl.isNullOrBlank() && sourceUrl.startsWith("http", true)) {
                                setRequestProperty("Referer", sourceUrl)
                            }
                        }
                        conn.connect()
                        code = conn.responseCode
                        if (code in 301..303 || code == 307 || code == 308) {
                            val loc = conn.getHeaderField("Location")
                            conn.disconnect()
                            if (loc.isNullOrBlank()) break
                            currentUrl = URL(u, loc).toString()
                            redirects++
                            continue
                        }
                    }

                    val disp = conn.getHeaderField("Content-Disposition")
                    val mime = conn.contentType?.substringBefore(';')?.trim()?.lowercase()
                    val len = conn.contentLengthLong
                    conn.disconnect()

                    val isAttachment = disp?.contains("attachment", ignoreCase = true) == true
                    val isNonWebBinaryMime = !mime.isNullOrBlank() &&
                        !mime.startsWith("text/html") &&
                        !mime.startsWith("application/xhtml") &&
                        !mime.startsWith("text/plain") &&
                        (mime.startsWith("application/") ||
                            mime.startsWith("video/") ||
                            mime.startsWith("audio/") ||
                            mime.startsWith("image/"))

                    val isDownload = (code in 200..299 || code == 206) &&
                        (isAttachment || isNonWebBinaryMime || isLikelyFileDownloadUrl(currentUrl))
                    return ProbeResult(isDownload, currentUrl, disp, mime, len)
                } catch (_: Exception) {
                    try {
                        conn?.disconnect()
                    } catch (_: Exception) {
                    }
                    break
                }
            }
            return ProbeResult(false, currentUrl, null, null, -1L)
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

