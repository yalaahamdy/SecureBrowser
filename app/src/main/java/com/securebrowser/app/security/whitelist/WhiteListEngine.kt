package com.securebrowser.app.security.whitelist

/**
 * محرك القائمة البيضاء.
 *
 * القاعدة الأساسية: **مفعّل دائمًا** — لا يوجد أي مسار في التطبيق لإيقافه،
 * ولا يُعتبر إعدادًا يمكن للمستخدم العادي تغييره.
 *
 * يحتفظ بلقطة (snapshot) من القواعد يحدّثها المستودع عند كل تنقل،
 * والقرار خالص (pure) — مستقل تمامًا عن UI وعن Android.
 */
class WhiteListEngine(private val ruleProvider: WhiteListRuleProvider) {

    @Volatile
    private var snapshot: List<WhiteListRule> = emptyList()

    /** يُستدعى قبل كل قرار تنقل لضمان حداثة القواعد. */
    suspend fun refresh() {
        snapshot = ruleProvider.currentRules()
    }

    /** لقطة حالية (للاختبار والتشخيص). */
    fun rulesSnapshot(): List<WhiteListRule> = snapshot

    fun isAllowed(url: com.securebrowser.app.core.url.ParsedUrl): Boolean =
        snapshot.any { WhiteListMatcher.matches(it, url) }

    fun firstMatch(url: com.securebrowser.app.core.url.ParsedUrl): WhiteListRule? =
        snapshot.firstOrNull { WhiteListMatcher.matches(it, url) }
}
