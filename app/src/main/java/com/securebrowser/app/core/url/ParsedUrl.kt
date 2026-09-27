package com.securebrowser.app.core.url

/**
 * نتيجة تطبيع عنوان URL.
 *
 * [Invalid] تعني دائمًا "حظر" — مبدأ fail-closed.
 */
sealed class NormalizeResult {
    data class Success(val url: ParsedUrl) : NormalizeResult()
    data class Invalid(val reason: String) : NormalizeResult()
}

/**
 * عنوان URL بعد التحليل والتطبيع الصارم.
 *
 * للـ host قواعد صارمة:
 * - أحرف صغيرة، مُفككة الترميز (percent-decoded)، punycode (IDN → ASCII)، بدون نقطة نهاية.
 * - لا يحتوي على أي من / \ ? # @ % أو مسافات أو ":" (باستثناء IPv6 الحرفي).
 *
 * [path] مُفكك الترميز لمطابقة القواعد (حتى لا تتخطى الهجمات عبر %2F و %252F).
 */
data class ParsedUrl(
    val scheme: String,
    val host: String,
    val port: Int?,
    val effectivePort: Int,
    val path: String,
    val query: String?,
    val fragment: String?,
    val userInfo: String?,
    val opaquePart: String?,
    val raw: String,
    val isIpLiteral: Boolean
) {
    /** هل المخطط http/https (محتوى ويب قابل للتصفح)؟ */
    val isWeb: Boolean
        get() = scheme == "http" || scheme == "https"

    /** هل العنوان هرمي مع host حقيقي؟ */
    val hasHost: Boolean
        get() = host.isNotEmpty()

    fun hostWithPort(): String = if (port != null) "$host:$port" else host

    /**
     * إعادة بناء العنوان بصيغة متعارف عليها.
     * ما تم التحقق منه هو ما سيُحمَّل — يقضي على فروق التحليل بين طبقة الأمان والمحرك.
     * (للمخططات غير الهرمية مثل blob/about استخدم [raw]).
     */
    fun canonical(): String = buildString {
        append(scheme)
        append("://")
        append(host)
        if (port != null) {
            append(':')
            append(port)
        }
        append(if (path.isBlank()) "/" else path)
        if (!query.isNullOrBlank()) {
            append('?')
            append(query)
        }
    }

    companion object {
        fun defaultPortFor(scheme: String): Int = when (scheme) {
            "https", "wss" -> 443
            "http", "ws" -> 80
            "ftp" -> 21
            else -> -1
        }
    }
}
