package com.securebrowser.app.policy

/**
 * قرار سياسة التنزيلات النهائي — يُسجَّل في جدول downloads ويُعرض في لوحة الوالدين.
 */
enum class DownloadDecision {
    /** مسموح — يبدأ التنزيل مباشرة. */
    ALLOW,

    /** يتطلب موافقة الوالدين (صف انتظار في لوحة التنزيلات). */
    REQUIRES_APPROVAL,

    /** محظور — نوع ملف خطير بموجب السياسة (لا تجاوز من الواجهة أو الروابط). */
    BLOCKED_DANGEROUS,

    /** محظور — مصدر التنزيل (الصفحة الحالية) ليس موقعًا مسموحًا. */
    BLOCK_SOURCE_NOT_ALLOWED,

    /** محظور — مخطط غير http/https. */
    BLOCK_SCHEME
}

/** تصنيف نوع الملف — الامتداد هو الحاسم (MIME قابل للتزوير) وMIME ملاذ للملفات بلا امتداد. */
enum class FileCategory { DANGEROUS_EXECUTABLE, ARCHIVE, VIDEO, AUDIO, IMAGE, DOCUMENT, UNKNOWN }

/**
 * سياسة التنزيلات الكاملة — مرحلة 2 (§12-§13) + تعديل v1.2.0.
 *
 * القواعد:
 * - المصدر (الصفحة الحالية) يجب أن يكون موقعًا مسموحًا — لا تنزيل من صفحة محظورة.
 * - عنوان الملف يجب أن يكون http/https — أو blob:/data: من صفحة مسموحة
 *   (ملفات يولّدها المتصفح نفسه بناءً على طلب الصفحة: شهادات/تقارير/وسائط مولدة —
 *   المصدر المسموح هو البوابة الحقيقية هنا كما هو حال blob: في SecurityEngine).
 * - التصنيف:
 *   * تنفيذي خطير (apk/exe/...) → حسب إعداد الوالدين (v1.2.0: السماح مع التسجيل هو الافتراضي).
 *   * أرشيف/غير معروف → حسب إعداد الوالدين (v1.2.0: السماح مع التسجيل هو الافتراضي).
 *   * وسائط/صور/مستندات معروفة → مسموح.
 * - كل تنزيل يُسجَّل في جدول downloads ويظهر في لوحة الوالدين — السماح لا يعني إغفال.
 * - القرار يُتخذ هنا فقط؛ الواجهة تعرضه وتنفذه ولا يمكنها تجاوزه.
 *
 * pure-Kotlin — قابل للاختبار الآلي على JVM.
 */
class DownloadPolicy(
    private val executablePolicy: Policy = Policy.ALLOW,
    private val archivePolicy: Policy = Policy.ALLOW,
    private val unknownPolicy: Policy = Policy.ALLOW
) {

    /** سياسة الوالدين لكل فئة: سماح مباشر / موافقة / حظر. */
    enum class Policy { ALLOW, APPROVAL, BLOCK }

    fun decide(
        sourceAllowed: Boolean,
        scheme: String?,
        fileName: String?,
        mimeType: String?
    ): DownloadDecision = when {
        !sourceAllowed -> DownloadDecision.BLOCK_SOURCE_NOT_ALLOWED

        // blob:/data: من صفحة مسموحة = ملف يولّده المتصفح لطلب الصفحة المسموحة نفسها —
        // بوابة المصدر هي الحكم الحقيقي (نفس منطق BLOB في SecurityEngine).
        scheme == "blob" || scheme == "data" -> classifyAndDecide(fileName, mimeType)

        scheme != "http" && scheme != "https" -> DownloadDecision.BLOCK_SCHEME
        else -> classifyAndDecide(fileName, mimeType)
    }

    private fun classifyAndDecide(fileName: String?, mimeType: String?): DownloadDecision =
        when (classify(fileName, mimeType)) {
            FileCategory.DANGEROUS_EXECUTABLE ->
                executablePolicy.toDecision(DownloadDecision.BLOCKED_DANGEROUS)
            FileCategory.ARCHIVE ->
                archivePolicy.toDecision(DownloadDecision.REQUIRES_APPROVAL)
            FileCategory.UNKNOWN ->
                unknownPolicy.toDecision(DownloadDecision.REQUIRES_APPROVAL)
            else -> DownloadDecision.ALLOW
        }

    private fun Policy.toDecision(blocked: DownloadDecision): DownloadDecision = when (this) {
        Policy.ALLOW -> DownloadDecision.ALLOW
        Policy.APPROVAL -> DownloadDecision.REQUIRES_APPROVAL
        Policy.BLOCK -> blocked
    }

    companion object {

        private val DANGEROUS_EXTENSIONS = setOf(
            "apk", "apks", "xapk", "exe", "msi", "msix", "bat", "cmd", "com", "scr",
            "vbs", "vbe", "js", "jse", "wsf", "wsh", "ps1", "jar", "dex", "sh", "deb", "rpm"
        )
        private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso")
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "mov", "avi", "3gp", "ts")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus", "amr")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "ico")
        private val DOCUMENT_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "json", "xml",
            "html", "htm", "epub", "odt", "ods", "odp"
        )

        fun classify(fileName: String?, mimeType: String?): FileCategory {
            val ext = fileName
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
            when (ext) {
                in DANGEROUS_EXTENSIONS -> return FileCategory.DANGEROUS_EXECUTABLE
                in ARCHIVE_EXTENSIONS -> return FileCategory.ARCHIVE
                in VIDEO_EXTENSIONS -> return FileCategory.VIDEO
                in AUDIO_EXTENSIONS -> return FileCategory.AUDIO
                in IMAGE_EXTENSIONS -> return FileCategory.IMAGE
                in DOCUMENT_EXTENSIONS -> return FileCategory.DOCUMENT
            }
            val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
            return when {
                mime.isNullOrBlank() -> FileCategory.UNKNOWN
                mime.startsWith("video/") -> FileCategory.VIDEO
                mime.startsWith("audio/") -> FileCategory.AUDIO
                mime.startsWith("image/") -> FileCategory.IMAGE
                mime == "application/pdf" -> FileCategory.DOCUMENT
                mime in DOCUMENT_MIME -> FileCategory.DOCUMENT
                mime in ARCHIVE_MIME -> FileCategory.ARCHIVE
                mime in DANGEROUS_MIME -> FileCategory.DANGEROUS_EXECUTABLE
                else -> FileCategory.UNKNOWN
            }
        }

        private val DOCUMENT_MIME = setOf(
            "text/plain", "text/csv", "application/json", "application/xml", "text/xml",
            "text/html", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/epub+zip"
        )

        private val ARCHIVE_MIME = setOf(
            "application/zip", "application/x-rar-compressed", "application/vnd.rar",
            "application/x-7z-compressed", "application/gzip", "application/x-tar",
            "application/x-iso9660-image"
        )

        private val DANGEROUS_MIME = setOf(
            "application/vnd.android.package-archive", "application/x-msdownload",
            "application/x-msi", "application/java-archive", "application/x-dex"
        )
    }
}
