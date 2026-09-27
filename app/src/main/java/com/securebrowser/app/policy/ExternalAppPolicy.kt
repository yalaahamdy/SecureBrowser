package com.securebrowser.app.policy

import com.securebrowser.app.core.url.NormalizeResult
import com.securebrowser.app.core.url.UrlNormalizer

/**
 * سياسة التطبيقات الخارجية — مرحلة 2 (§19-§20).
 *
 * تصنيف روابط/مخططات التطبيقات المعروفة (Telegram/WhatsApp/YouTube/Maps/Mail/Phone/SMS)
 * + استخراج رابط المتصفح الاحتياطي من intent:.
 *
 * الضمانة الأمنية: التطبيق الخارجي ليس مخرجًا من الحماية —
 * - روابط https التي يمنعها Whitelist تُقترح على المستخدم فتحها في التطبيق الرسمي فقط
 *   (بعد التحقق من الإعدادات + تأكيد صريح)؛ لا فتح تطبيقي بلا بوابة.
 * - روابط intent: تُحكم على وجهتها الاحتياطية للمتصفح عبر القائمة البيضاء قبل أي شيء.
 *
 * منطق pure-Kotlin؛ حل التطبيقات المثبتة والتأكيد الفعلي في ExternalNavigationHandler.
 */
object ExternalAppPolicy {

    enum class AppKind { TELEGRAM, WHATSAPP, YOUTUBE, MAPS, PHONE, SMS, MAIL, GENERIC }

    data class Suggestion(
        val kind: AppKind,
        /** رابط الويب الأصلي الذي تمنع القائمة البيضاء فتحه. */
        val webUrl: String,
        /** اسم التطبيق المقترح (للعرض في حوار التأكيد). */
        val appLabel: String
    )

    private val TELEGRAM_HOSTS = setOf("t.me", "telegram.me", "m.t.me", "web.telegram.org")
    private val WHATSAPP_HOSTS = setOf("wa.me", "api.whatsapp.com", "chat.whatsapp.com", "web.whatsapp.com")
    private val YOUTUBE_HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
        "music.youtube.com", "www.youtu.be"
    )
    private val MAPS_HOSTS = setOf("maps.google.com", "google.com", "www.google.com", "goo.gl")

    // ————————————————— مشغلات الوسائط الخارجية (v1.2.0) —————————————————
    //
    // المنصات التعليمية تطلق الفيديو عبر مشغلات خارجية: intent:// مع حزمة مشغل
    // أو mime video/audio، أو مخططات خاصة (vlc:// mxplayer:// ...). كانت كلها
    // تُحظر fail-closed (intent بلا fallback قابل للقائمة = حظر) فتتعطل المنصة.
    // السياسة الآن: المشغلات المعروفة أدناه تُفتح عبر بوابة التطبيقات الخارجية
    // (إعداد الوالدين + تأكيد) — وما عداهما يبقى fail-closed كما هو.

    /** حزم مشغلات الفيديو/الصوت المعروفة والموثوقة (قائمة مغلقة — لا wildcard). */
    val KNOWN_MEDIA_PLAYER_PACKAGES: Set<String> = setOf(
        "org.videolan.vlc",                  // VLC
        "com.mxtech.videoplayer.ad",         // MX Player (مجاني)
        "com.mxtech.videoplayer.pro",        // MX Player Pro
        "com.newin.nplayer.pro",             // nPlayer
        "com.kmplayer",                      // KMPlayer
        "com.kmplayerfree",                  // KMPlayer Free
        "video.player.vplayer",              // XPlayer
        "org.bsplayer.v2",                   // BSPlayer
        "com.playit.videoplayer"             // PlayIt (v1.4.0 — شائع بالمنطقة)
    )

    /** مخططات مشغلات الوسائط الشائعة — تُعامل كتطبيقات خارجية عبر البوابة. */
    val MEDIA_PLAYER_SCHEMES: Set<String> = setOf(
        "vlc", "mxplayer", "nplayer", "nplayer-https", "nplayer-http",
        "kmplayer", "splayer", "bsplayer", "rtsp", "rtmp", "rtmps", "mms"
    )

    /**
     * هل مخطط المخطط مخطط مشغل وسائط؟ (تُفتح عبر بوابة مشغلات الفيديو).
     */
    fun isMediaScheme(scheme: String): Boolean = scheme.lowercase() in MEDIA_PLAYER_SCHEMES

    /**
     * استخراج قيمة معامل من نص intent: بصيغة #Intent;key=value;...;end
     * (pure-Kotlin بلا اعتماد على android.content.Intent — للقرار الآلي في SecurityEngine).
     */
    private fun intentParam(intentUri: String, key: String): String? {
        val marker = ";$key="
        val start = intentUri.indexOf(marker)
        if (start < 0) return null
        var value = intentUri.substring(start + marker.length)
        val end = value.indexOf(';')
        if (end >= 0) value = value.substring(0, end)
        return percentDecode(value).takeIf { it.isNotBlank() }
    }

    /** فك ترميز percent بسيط (ASCII) لمعاملات intent — بلا URLDecoder (+→مسافة خاطئة). */
    private fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hi = value[i + 1].digitToIntOrNull(16)
                val lo = value[i + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    sb.append(((hi shl 4) or lo).toChar())
                    i += 3
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** حزمة التطبيق المستهدفة من intent: (من معامل package=). */
    fun intentTargetPackage(intentUri: String): String? = intentParam(intentUri, "package")

    /** نوع MIME المستهدف من intent: (من S.type= أو type=). */
    fun intentTargetMime(intentUri: String): String? =
        intentParam(intentUri, "S.type") ?: intentParam(intentUri, "type")

    /**
     * هل هذا intent: يستهدف تشغيل وسائط في مشغل معروف؟
     * - mime يبدأ بـ video/ أو audio/ صراحة، أو
     * - الحزمة المستهدفة ضمن قائمة المشغلات المعروفة.
     */
    fun isMediaIntent(intentUri: String): Boolean {
        val mime = intentTargetMime(intentUri)?.substringBefore(';')?.trim()?.lowercase()
        if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) return true
        val pkg = intentTargetPackage(intentUri)?.lowercase()
        if (pkg != null && pkg in KNOWN_MEDIA_PLAYER_PACKAGES) return true
        // الحزمة غير معروفة + mime غير محدد → ليست وسائط (fail-closed)
        return false
    }

    /**
     * اقتراح فتح تطبيق لرابط ويب — يُستخدم عندما يحظر Whitelist الرابط
     * وينتمي لنطاقات تطبيقات معروفة. مثال المواصفة: رابط t.me → "Open in Telegram"
     * إن كان مثبتًا، وإلا فتح المتصفح إذا كانت الوجهة مسموحة.
     */
    fun suggestForWebUrl(url: String): Suggestion? {
        val parsed = when (val r = UrlNormalizer.normalize(url)) {
            is NormalizeResult.Success -> r.url
            is NormalizeResult.Invalid -> return null
        }
        if (!parsed.isWeb) return null
        val host = parsed.host
        val path = parsed.path
        return when {
            host in TELEGRAM_HOSTS -> Suggestion(AppKind.TELEGRAM, url, "Telegram")
            host in WHATSAPP_HOSTS -> Suggestion(AppKind.WHATSAPP, url, "WhatsApp")
            host in YOUTUBE_HOSTS -> Suggestion(AppKind.YOUTUBE, url, "YouTube")
            host == "maps.google.com" ||
                (host in MAPS_HOSTS && path.startsWith("/maps")) ->
                Suggestion(AppKind.MAPS, url, "Maps")
            else -> null
        }
    }

    /** تصنيف مخطط خارجي مباشر (tg:/whatsapp:/vnd.youtube:/geo:/mailto:/tel:/sms:...). */
    fun kindForScheme(scheme: String): AppKind? = when (scheme.lowercase()) {
        "tg" -> AppKind.TELEGRAM
        "whatsapp" -> AppKind.WHATSAPP
        "vnd.youtube", "youtube" -> AppKind.YOUTUBE
        "geo" -> AppKind.MAPS
        "mailto" -> AppKind.MAIL
        "tel" -> AppKind.PHONE
        "sms", "smsto" -> AppKind.SMS
        "intent", "market" -> AppKind.GENERIC
        else -> null
    }

    /**
     * استخراج رابط المتصفح الاحتياطي من intent: (S.browser_fallback_url=...).
     * بلا وجهة احتياطية = حظر (fail-closed) في SecurityEngine.
     */
    fun intentFallbackUrl(intentUri: String): String? {
        val key = "S.browser_fallback_url="
        val idx = intentUri.indexOf(key)
        if (idx < 0) return null
        var value = intentUri.substring(idx + key.length)
        val end = value.indexOf(';')
        if (end >= 0) value = value.substring(0, end)
        value = value
            .replace("%3A", ":").replace("%3a", ":")
            .replace("%2F", "/").replace("%2f", "/")
            .replace("%3F", "?").replace("%3f", "?")
            .replace("%3D", "=").replace("%3d", "=")
            .replace("%26", "&")
            .replace("%25", "%")
        return value.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /** هل المخطط يفتح تطبيقًا خارجيًا (يمر عبر بوابة الوالدين + تأكيد)؟ */
    fun isExternalAppScheme(scheme: String): Boolean = kindForScheme(scheme) != null
}
