package com.securebrowser.app.security

import com.securebrowser.app.core.url.ParsedUrl

/**
 * سياسة المخططات (schemes) — أي مخطط غير مسموح يُحظر قبل أي شيء آخر.
 *
 * التصنيفات:
 * - WEB_CONTENT: http/https → تخضع لقائمة المسموح.
 * - INTERNAL_BLANK: about:blank فقط (حالة داخلية للمحرك).
 * - BLOB: blob: وسائط same-origin من صفحة مسموحة أصلاً — لا يمكن استخدامه لإدخال نطاق محظور.
 * - EXTERNAL_CONTACT: tel/sms/smsto/mailto → سياسة الاتصال النظامي (بدون تحميل محتوى).
 * - EXTERNAL_APP: مخططات فتح التطبيقات — المدرجة صراحة (tg/whatsapp/geo/market/intent/مشغلات)
 *   **وأي مخطط تطبيق آخر غير متميز** (viber:// zoommtg:// playit:// ... — v1.4.0):
 *   كانت المخططات غير المدرجة تُحظر فشل-مغلق فتعذّر الاتصال ببقية تطبيقات الهاتف
 *   (طلب المستخدم الصريح). تفتح كلها عبر بوابة الوالدين (external_apps/video_players)
 *   + التأكيد النظامي + الإطلاق المحصّن في ExternalNavigationHandler.
 * - UNSAFE: **قائمة ممنوعات متميزة** — مخططات يمكنها الوصول لموارد الجهاز أو
 *   تنفيذ كود: javascript:, file:, content:, data:, ws/wss, chrome:, vnd.android*,
 *   android-app:, package:, jar:, about:(غير blank أعلاه). كل ما عداها يمر عبر EXTERNAL_APP.
 */
object SchemePolicy {

    enum class SchemeCategory { WEB_CONTENT, INTERNAL_BLANK, BLOB, EXTERNAL_CONTACT, EXTERNAL_APP, UNSAFE }

    const val EXTERNAL_CONTACT_ALLOW: Boolean = true // قرار السياسة الحالي: الاتصال/البريد مسموح عبر النظام

    val EXTERNAL_CONTACT_SCHEMES: Set<String> = setOf("tel", "sms", "smsto", "mailto")

    /** مخططات التطبيقات الخارجية المدعومة (تفتح عبر بوابة سياسة التطبيقات الخارجية). */
    val EXTERNAL_APP_SCHEMES: Set<String> = setOf(
        "tg", "whatsapp", "vnd.youtube", "youtube", "geo", "market", "intent"
    )

    /**
     * مخططات مشغلات الوسائط الخارجية (v1.2.0) — تفتح عبر بوابة التطبيقات الخارجية
     * + إعداد الوالدين "مشغلات الفيديو". القائمة مغلقة (fail-closed لكل ما عداه)،
     * v1.4.0: أُضيفت بروتوكولات بث شائعة تفتحها المشغلات نفسها.
     */
    val MEDIA_APP_SCHEMES: Set<String> =
        com.securebrowser.app.policy.ExternalAppPolicy.MEDIA_PLAYER_SCHEMES

    /**
     * قائمة الممنوعات المتميزة (v1.4.0) — مخططات لا تُفتح كتطبيق خارجي مهما كانت
     * البوابات لأنها تصل لموارد الجهاز أو تنفذ كودًا أو تتجاوز نموذج الأمان:
     * - javascript/file/content/data: تنفيذ كود وقراءة موارد محلية.
     * - ws/wss: بروتوكول شبكة غير قابل للعرض (لا معنى للتنقل إليه).
     * - chrome/ وكل ما يبدأ بـ vnd.android وandroid-app/package/jar: مخططات نظام/مجمّع متميزة.
     * كل مخطط آخر غير معروف → EXTERNAL_APP (بوابة الوالدين + إطلاق محصّن).
     */
    val PRIVILEGED_UNSAFE_SCHEMES: Set<String> = setOf(
        "javascript", "file", "content", "data", "ws", "wss",
        "chrome", "vnd.android", "vnd.android.cursor.item", "vnd.android.cursor.dir",
        "android-app", "package", "jar", "classpath", "resource"
    )

    fun categorize(scheme: String): SchemeCategory = when {
        scheme == "http" || scheme == "https" -> SchemeCategory.WEB_CONTENT
        scheme == "about" -> SchemeCategory.INTERNAL_BLANK
        scheme == "blob" -> SchemeCategory.BLOB
        scheme in EXTERNAL_CONTACT_SCHEMES -> SchemeCategory.EXTERNAL_CONTACT
        scheme in EXTERNAL_APP_SCHEMES || scheme in MEDIA_APP_SCHEMES -> SchemeCategory.EXTERNAL_APP
        scheme in PRIVILEGED_UNSAFE_SCHEMES -> SchemeCategory.UNSAFE
        // v1.4.0: أي مخطط تطبيق آخر (viber/zoommtg/spotify/playit/...) يمر عبر
        // بوابة التطبيقات الخارجية بدل الحظر الأعمى — الاتصال بتطبيقات الهاتف مطلب أساسي.
        else -> SchemeCategory.EXTERNAL_APP
    }

    /** هل المخطط اتصال/مراسلة نظامية؟ */
    fun isExternalContact(scheme: String): Boolean =
        categorize(scheme) == SchemeCategory.EXTERNAL_CONTACT

    /** هل المخطط يفتح تطبيقًا خارجيًا (سياسة التطبيقات الخارجية)؟ */
    fun isExternalApp(scheme: String): Boolean =
        categorize(scheme) == SchemeCategory.EXTERNAL_APP
}

/** أخطاء التحقق من سلامة العنوان قبل المطابقة. */
enum class ValidationError {
    EMBEDDED_CREDENTIALS,
    EMPTY_HOST,
    MALFORMED
}

/**
 * تحقق صارم إضافي فوق التطبيع — يفحص ما لا تغطيه المطابقة.
 */
object UrlValidator {

    fun check(url: ParsedUrl): ValidationError? {
        // user@host مخادع → حظر دائمًا حتى لو طابق الـ host النهائي قاعدة مسموحة
        if (!url.userInfo.isNullOrBlank()) return ValidationError.EMBEDDED_CREDENTIALS
        if (url.scheme == "http" || url.scheme == "https") {
            if (url.host.isEmpty()) return ValidationError.EMPTY_HOST
        }
        return null
    }
}
