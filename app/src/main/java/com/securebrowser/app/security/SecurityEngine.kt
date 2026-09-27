package com.securebrowser.app.security

import com.securebrowser.app.core.url.ParsedUrl
import com.securebrowser.app.core.url.UrlNormalizer
import com.securebrowser.app.policy.ExternalAppPolicy
import com.securebrowser.app.policy.PolicyManager
import com.securebrowser.app.security.model.BlockReason
import com.securebrowser.app.security.model.NavigationDecision
import com.securebrowser.app.security.model.NavigationType
import com.securebrowser.app.security.whitelist.WhiteListEngine

/**
 * واجهة الأمان الموحدة (Security Layer facade).
 *
 * **كل تنقل في التطبيق يمر من هنا قبل تنفيذه** — شريط العنوان، الروابط،
 * إعادة التوجيه، النوافذ المنبثقة، JavaScript، إعادة التحميل، السجل،
 * التنزيلات، فتح التطبيقات الخارجية.
 *
 * الضمانات:
 * 1. القائمة البيضاء مفعّلة دائمًا — لا يوجد أي مسار لتعطيلها.
 *    (الوصول المؤقت نافذة زمنية يفتحها الوالد بعد المصادقة وتنتهي تلقائيًا؛
 *    حتى أثناءها تبقى المخططات الخطرة محظورة.)
 * 2. fail-closed: أي شك في صحة العنوان = حظر.
 * 3. السياسات تُطبَّق هنا لا في الواجهة — UI يعرض القرار فقط.
 */
class SecurityEngine(
    private val whiteListEngine: WhiteListEngine,
    private val policyManager: PolicyManager,
    private val infraHostsProvider: () -> Set<String> = { emptySet() }
) {

    fun validate(
        rawUrl: String?,
        type: NavigationType,
        depth: Int = 0
    ): NavigationDecision {
        // 1) تطبيع/تحليل صارم
        val parsed: ParsedUrl = when (val result = UrlNormalizer.normalize(rawUrl)) {
            is com.securebrowser.app.core.url.NormalizeResult.Invalid ->
                return NavigationDecision.Block(BlockReason.MALFORMED_URL, result.reason, rawUrl)
            is com.securebrowser.app.core.url.NormalizeResult.Success -> result.url
        }

        // 2) سياسة المخطط أولًا
        return when (SchemePolicy.categorize(parsed.scheme)) {
            SchemePolicy.SchemeCategory.UNSAFE ->
                NavigationDecision.Block(BlockReason.UNSAFE_SCHEME, "scheme:${parsed.scheme}", rawUrl)

            SchemePolicy.SchemeCategory.INTERNAL_BLANK ->
                if (parsed.opaquePart?.trim()?.lowercase() == "blank") {
                    NavigationDecision.Allow(parsed)
                } else {
                    NavigationDecision.Block(BlockReason.UNSAFE_SCHEME, "about:${parsed.opaquePart}", rawUrl)
                }

            SchemePolicy.SchemeCategory.BLOB ->
                // وسائط same-origin مولدة من صفحة مسموحة — لا تنقل عبر النطاقات
                NavigationDecision.Allow(parsed)

            SchemePolicy.SchemeCategory.EXTERNAL_CONTACT ->
                NavigationDecision.OpenExternal(parsed.scheme, parsed.opaquePart ?: rawUrl.orEmpty())

            SchemePolicy.SchemeCategory.EXTERNAL_APP -> validateExternalApp(parsed, rawUrl, depth)

            SchemePolicy.SchemeCategory.WEB_CONTENT -> validateWeb(parsed, type, rawUrl)
        }
    }

    /**
     * مخططات التطبيقات الخارجية:
     * - intent: **سياسة v1.8.0 — وجهة معلنة = بوابة التطبيقات الخارجية:**
     *   1) intent مثبّت بحزمة (`package=`) → قرار OpenExternal يمر عبر بوابة
     *      الوالدين (external_apps + تأكيد نظامي) — **نفس مسار المخططات
     *      المخصصة تمامًا** (zenplayer:// كانت تمر منذ v1.4.0 بينما intent://
     *      المغلّف لنفس التطبيق يُحظر — عدم اتساق لا يحمي شيئًا لأن الموقع
     *      يستطيع استدعاء المخطط المخصص مباشرة). هذا يُصلح المنصات التعليمية
     *      التي تستخدم مشغلها الخاص (Zen Player وغيره) — كانت تُحظر فشل-مغلق
     *      فلا يحدث شيء عند الضغط على «افتح في المشغل». الحماية تبقى قائمة:
     *      بوابات الوالدين + التأكيد + تجريد component/selector في المعالج
     *      (منع intent redirection).
     *   2) intent وسائطي (mime video/audio أو مخطط مشغل معروف داخلي أو حزمة
     *      مشغل معروفة) → بوابة "مشغلات الفيديو" (قرار والد مستقل) — كما كان.
     *   3) بلا حزمة ولا وسائط: fallback مسموح بالقائمة → فتح (سلوك قديم محفوظ)،
     *      وإلا حظر fail-closed (وجهة مجهولة تمامًا).
     * - باقي مخططات التطبيقات (tg/whatsapp/geo/market/vlc/rtsp/zenplayer...)
     *   → قرار OpenExternal عبر بوابة الوالدين + التأكيد في ExternalNavigationHandler.
     */
    private fun validateExternalApp(
        parsed: ParsedUrl,
        rawUrl: String?,
        depth: Int
    ): NavigationDecision {
        if (parsed.scheme != "intent") {
            return NavigationDecision.OpenExternal(parsed.scheme, parsed.opaquePart ?: rawUrl.orEmpty())
        }
        if (depth >= MAX_INTENT_DEPTH) {
            return NavigationDecision.Block(BlockReason.UNSAFE_SCHEME, "intent_depth", rawUrl)
        }
        val raw = parsed.raw
        val media = ExternalAppPolicy.isMediaIntent(raw)
        // 1) وجهة معلنة (package=) → بوابة التطبيقات الخارجية — مثل أي مخطط مخصص.
        //    المشغلات المعروفة تبقى تحت بوابة "مشغلات الفيديو" المستقلة (قرار والد).
        if (ExternalAppPolicy.intentTargetPackage(raw) != null) {
            if (media && !policyManager.videoPlayersEnabled()) {
                return NavigationDecision.Block(BlockReason.UNSAFE_SCHEME, "video_players_disabled", rawUrl)
            }
            return NavigationDecision.OpenExternal("intent", rawUrl.orEmpty())
        }
        // 2) وسائطي بلا حزمة (mime video/audio أو مخطط مشغل داخلي) → بوابة المشغلات
        if (media) {
            return if (policyManager.videoPlayersEnabled()) {
                NavigationDecision.OpenExternal("intent", rawUrl.orEmpty())
            } else {
                NavigationDecision.Block(BlockReason.UNSAFE_SCHEME, "video_players_disabled", rawUrl)
            }
        }
        // 3) fallback مسموح بالقائمة → فتح (السلوك القديم محفوظ لـ intent مجهول الوجهة)
        val fallback = ExternalAppPolicy.intentFallbackUrl(raw)
        if (fallback != null) {
            return when (val fbDecision = validate(fallback, NavigationType.REDIRECT, depth + 1)) {
                is NavigationDecision.Allow ->
                    NavigationDecision.OpenExternal("intent", rawUrl.orEmpty())
                is NavigationDecision.Block -> fbDecision
                is NavigationDecision.OpenExternal -> fbDecision
            }
        }
        // 4) لا حزمة ولا وسائط ولا fallback — وجهة مجهولة تمامًا → fail-closed
        return NavigationDecision.Block(BlockReason.UNSAFE_SCHEME, "intent_no_fallback", rawUrl)
    }

    private fun validateWeb(
        parsed: ParsedUrl,
        type: NavigationType,
        rawUrl: String?
    ): NavigationDecision {
        // 3) تحقق سلامة إضافي (userinfo، host فارغ…)
        val error = UrlValidator.check(parsed)
        if (error != null) {
            return NavigationDecision.Block(BlockReason.EMBEDDED_CREDENTIALS, error.name, rawUrl)
        }

        // 4) نافذة الوصول المؤقت (شاملة، بمؤقّت مخزَّن مشفر يفتحها الوالد بعد المصادقة) —
        //    حتى أثناءها تبقى المخططات الخطرة محظورة (فُحصت أعلاه).
        if (policyManager.temporaryAccess.isActive()) {
            return NavigationDecision.Allow(parsed)
        }

        // 5) القائمة البيضاء — القرار النهائي.
        //
        // استثناء بنية تحتية **محدود وموثق** — مضيف محرك البحث المُعدّ:
        //  • الهدف: جعل البحث قابلًا للاستخدام من أول تشغيل دون فتح باب للإنترنت.
        //  • الحدود:
        //    - مضيف واحد فقط بالتطابق التام (duckduckgo.com أو www.google.com
        //      أو www.bing.com حسب اختيار الإعداد — ثلاثة محركات رئيسية ثابتة
        //      لا يمكن للمستخدم إضافة غيرها).
        //    - يُطبق فقط هنا: بعد فحص سلامة المخطط و userinfo أعلاه — أي أن
        //      المخططات الخطرة تبقى محظورة حتى على مضيف المحرك.
        //    - كل رابط تنقره من نتائج البحث، وكل إعادة توجيه أو نافذة منبثقة
        //      أو JS navigation تخرج من صفحة المحرك تمر من SecurityEngine
        //      من جديد وتخضع للقائمة البيضاء كاملة — صفحة المحرك لا تمنح
        //      أي نطاق آخر قابلية وصول.
        return if (whiteListEngine.isAllowed(parsed) || parsed.host in infraHostsProvider()) {
            NavigationDecision.Allow(parsed)
        } else {
            NavigationDecision.Block(
                BlockReason.NOT_WHITELISTED,
                parsed.host.ifEmpty { parsed.raw.take(120) },
                rawUrl
            )
        }
    }

    companion object {
        private const val MAX_INTENT_DEPTH = 2
    }
}
