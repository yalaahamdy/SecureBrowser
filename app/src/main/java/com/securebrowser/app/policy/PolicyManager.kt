package com.securebrowser.app.policy

/**
 * طبقة السياسات — قرارات ما حول التصفح (ليس التنقل ذاته؛ فذلك اختصاص SecurityEngine).
 *
 * مرحلة 2:
 * - [TemporaryAccessManager]: نافذة وصول مؤقت شاملة بسبق استمرار مشفر.
 * - [DownloadPolicy]: سياسة أنواع الملفات الكاملة.
 * - [ExternalAppPolicy]: تصنيف التطبيقات الخارجية وintent fallback.
 * - سياسة الإطارات الفرعية (subframes).
 */
class PolicyManager(
    val temporaryAccess: TemporaryAccessManager = TemporaryAccessManager(InMemoryTempAccessStore())
) {

    /**
     * بوابة مشغلات الفيديو الخارجية (v1.2.0) — يوصلها ServiceLocator بإعداد الوالدين.
     * الافتراضية: مفعّلة (السماح بفتح مشغلات الوسائط المعروفة)، والتأكيد النظامي يبقى بوابة.
     */
    var videoPlayersEnabled: () -> Boolean = { true }

    val downloadPolicy = DownloadPolicy()

    /** سياسة الإطارات الفرعية: موارد الصفحة المسموحة تُحمَّل بحرية (خطاف تدقيق أعمق لاحقًا). */
    var subresourcePolicy: SubresourcePolicy = SubresourcePolicy.ALLOW_ALL

    /** مخططات الاتصال/المراسلة النظامية (tel/mailto/sms). */
    fun isExternalSchemeAllowed(scheme: String): Boolean =
        com.securebrowser.app.security.SchemePolicy.isExternalContact(scheme)

    /** مخططات التطبيقات الخارجية (tg/whatsapp/youtube/geo/intent/market) — بوابة الوالدين تفرضها. */
    fun isExternalAppScheme(scheme: String): Boolean =
        ExternalAppPolicy.isExternalAppScheme(scheme)
}

/** سياسة الإطارات الفرعية/الموارد — خطاف تدقيق أعمق لاحقًا. */
enum class SubresourcePolicy {
    /** موارد وإطارات الصفحة المسموحة تُحمَّل بحرية (سلوك المتصفح الطبيعي). */
    ALLOW_ALL,

    /** تدقيق الإطارات عبر قائمة المسموح (توسعة مستقبلية). */
    WHITELIST_FRAMES
}
