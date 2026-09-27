package com.securebrowser.app.security.whitelist

/** نوع قاعدة السماح. */
enum class RuleType {
    /** النطاق كاملًا بأي مسار. */
    ALLOW_DOMAIN,

    /** مسار محدد + كل ما تحته (مطابقة على حدود المقاطع: /news لا تطابق /newsportal). */
    ALLOW_PATH_PREFIX,

    /** مسار محدد بدقة فقط. */
    ALLOW_EXACT
}

/** سياسة النطاقات الفرعية. */
enum class SubdomainPolicy {
    /** يشمل النطاق نفسه وكل نطاقاته الفرعية (a.example.com, b.a.example.com). */
    INCLUDE_SUBDOMAINS,

    /** النطاق المحدد نفسه فقط — لا نطاقات فرعية. */
    EXACT_HOST_ONLY
}

/**
 * قائمة سماح (نموذج النطاق المستقل عن التخزين).
 * host يجب أن يكون بصيغة مطبعة (lowercase punycode) — يطبّعها المستودع عند الإدخال.
 */
data class WhiteListRule(
    val id: Long,
    val host: String,
    val path: String?,
    val scheme: String?,
    val port: Int?,
    val type: RuleType,
    val subdomainPolicy: SubdomainPolicy,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/** مزود القواعد — يفصل المحرك عن Room ليُختبر بمزيف (fake). */
fun interface WhiteListRuleProvider {
    suspend fun currentRules(): List<WhiteListRule>
}
