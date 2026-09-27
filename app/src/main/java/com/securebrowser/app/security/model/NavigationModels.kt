package com.securebrowser.app.security.model

/** نوع التنقل — يُسجَّل مع القرارات ولا يُضعف أي سياسة أمنية. */
enum class NavigationType {
    INITIAL,      // أول تحميل في التبويب
    TYPED,        // كتبه المستخدم في شريط العنوان
    LINK,         // نقر رابط داخل صفحة
    REDIRECT,     // إعادة توجيه (HTTP 3xx / meta refresh)
    FORM,         // إرسال نموذج
    POPUP,        // window.open / target=_blank
    NEW_WINDOW,   // محاولة فتح نافذة جديدة
    JS,           // تنقل من JavaScript (location.*)
    RELOAD,       // إعادة تحميل
    HISTORY       // تنقل في سجل الرجوع/التقدم
}

/** سبب الحظر — يُخزَّن في جدول النشاط المحظور. */
enum class BlockReason {
    MALFORMED_URL,
    UNSAFE_SCHEME,
    EMBEDDED_CREDENTIALS,
    NOT_WHITELISTED,
    SECURITY_ERROR,
    DOWNLOAD_POLICY
}

/** قرار طبقة الأمان النهائي لأي تنقل. */
sealed class NavigationDecision {
    /** مسموح — الشعار المعاد بناؤه هو ما سيُحمَّل فعليًا. */
    data class Allow(val url: com.securebrowser.app.core.url.ParsedUrl) : NavigationDecision()

    /** محظور — بدون أي خيار تجاوز في الواجهة. */
    data class Block(
        val reason: BlockReason,
        val detail: String,
        val rawUrl: String?
    ) : NavigationDecision()

    /** فتح في تطبيق خارجي (tel/mailto/sms…) — يمر عبر سياسة التطبيقات الخارجية. */
    data class OpenExternal(val scheme: String, val target: String) : NavigationDecision()
}
