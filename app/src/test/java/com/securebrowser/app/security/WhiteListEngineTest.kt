package com.securebrowser.app.security

import com.securebrowser.app.core.url.NormalizeResult
import com.securebrowser.app.core.url.UrlNormalizer
import com.securebrowser.app.policy.PolicyManager
import com.securebrowser.app.security.model.BlockReason
import com.securebrowser.app.security.model.NavigationDecision
import com.securebrowser.app.security.model.NavigationType
import com.securebrowser.app.security.whitelist.RuleType
import com.securebrowser.app.security.whitelist.SubdomainPolicy
import com.securebrowser.app.security.whitelist.WhiteListEngine
import com.securebrowser.app.security.whitelist.WhiteListRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * المصفوفة الكاملة لاختبار المحرك الأمني — نفس مصفوفة المطلوب في المواصفة (§17):
 * Allowed: root domain, path, nested path, allowed subdomain.
 * Blocked: evil.com, example.com.evil.com, evil.com/example.com, redirects,
 *          encoded redirect, IP, malicious scheme, popup, new window.
 */
class WhiteListEngineTest {

    private lateinit var engine: SecurityEngine
    private lateinit var policyManager: PolicyManager
    private var rules: List<WhiteListRule> = emptyList()

    @Before
    fun setup() {
        // قاعدة أساسية: example.com (يشمل النطاقات الفرعية) + قاعدة مسار + قاعدة IP
        rules = listOf(
            rule(host = "example.com", subdomains = SubdomainPolicy.INCLUDE_SUBDOMAINS),
            rule(
                host = "example.org",
                path = "/news",
                type = RuleType.ALLOW_PATH_PREFIX,
                subdomains = SubdomainPolicy.INCLUDE_SUBDOMAINS
            ),
            rule(host = "93.184.216.34", type = RuleType.ALLOW_DOMAIN)
        )
        val whiteListEngine = WhiteListEngine { rules }
        policyManager = PolicyManager()
        engine = SecurityEngine(whiteListEngine, policyManager)
        runBlocking { whiteListEngine.refresh() }
    }

    private fun rule(
        host: String,
        path: String? = null,
        type: RuleType = RuleType.ALLOW_DOMAIN,
        subdomains: SubdomainPolicy = SubdomainPolicy.EXACT_HOST_ONLY,
        scheme: String? = null,
        port: Int? = null
    ) = WhiteListRule(
        id = rules.size.toLong() + 1,
        host = host,
        path = path,
        scheme = scheme,
        port = port,
        type = type,
        subdomainPolicy = subdomains,
        enabled = true,
        createdAt = 0,
        updatedAt = 0
    )

    private fun assertAllowed(url: String, type: NavigationType = NavigationType.LINK) {
        val decision = engine.validate(url, type)
        assertTrue(
            "expected ALLOW for [$url] but got $decision",
            decision is NavigationDecision.Allow
        )
    }

    private fun assertBlocked(url: String?, reason: BlockReason? = null, type: NavigationType = NavigationType.LINK) {
        val decision = engine.validate(url, type)
        assertTrue(
            "expected BLOCK for [$url] but got $decision",
            decision is NavigationDecision.Block
        )
        if (reason != null) {
            assertEquals(reason, (decision as NavigationDecision.Block).reason)
        }
    }

    // ————————————————— Allowed —————————————————

    @Test
    fun `allowed - root domain`() = assertAllowed("https://example.com")

    @Test
    fun `allowed - path`() = assertAllowed("https://example.com/news")

    @Test
    fun `allowed - nested path with query and fragment`() =
        assertAllowed("https://example.com/article/123?ref=home#top")

    @Test
    fun `allowed - subdomain`() = assertAllowed("https://www.example.com")

    @Test
    fun `allowed - deep subdomain`() = assertAllowed("https://a.b.c.example.com/x/y")

    @Test
    fun `allowed - uppercase and trailing dot`() {
        assertAllowed("HTTPS://EXAMPLE.COM./Path")
        assertAllowed("https://WWW.Example.COM/")
    }

    @Test
    fun `allowed - path prefix rule respects segment boundary`() {
        assertAllowed("https://example.org/news")
        assertAllowed("https://example.org/news/2024/story-1")
        assertAllowed("https://example.org/news/") // trailing slash موحّد
    }

    @Test
    fun `allowed - explicit default port matches`() = assertAllowed("https://example.com:443/")

    @Test
    fun `allowed - ip literal when whitelisted`() = assertAllowed("https://93.184.216.34/anything")

    @Test
    fun `allowed - punycode host matches idn input`() {
        // النطاق العربي يُطبّع إلى punycode — إضافة القاعدة بنفس الصيغة تجعله مسموحًا
        val arabicHost = UrlNormalizer.normalize("https://مثال.إختبار")
        assertTrue(arabicHost is NormalizeResult.Success)
        val host = (arabicHost as NormalizeResult.Success).url.host
        val wl = WhiteListEngine { listOf(rule(host = host)) }
        runBlocking { wl.refresh() }
        val localEngine = SecurityEngine(wl, PolicyManager())
        assertTrue(localEngine.validate("https://مثال.إختبار", NavigationType.LINK) is NavigationDecision.Allow)
    }

    // ————————————————— Blocked: استبدال النطاقات —————————————————

    @Test
    fun `blocked - evil com`() = assertBlocked("https://evil.com", BlockReason.NOT_WHITELISTED)

    @Test
    fun `blocked - example com evil com (suffix attack)`() =
        assertBlocked("https://example.com.evil.com", BlockReason.NOT_WHITELISTED)

    @Test
    fun `blocked - evil com slash example com (path confusion)`() =
        assertBlocked("https://evil.com/example.com", BlockReason.NOT_WHITELISTED)

    @Test
    fun `blocked - lookalike host without dot boundary`() =
        assertBlocked("https://example.com.evil.io") // ليس نطاقًا فرعيًا حقيقيًا لـ example.com

    @Test
    fun `blocked - prefix must not match segment start`() =
        assertBlocked("https://example.org/newsportal")

    @Test
    fun `blocked - subdomain policy exact host only`() {
        val wl = WhiteListEngine { listOf(rule(host = "example.com", subdomains = SubdomainPolicy.EXACT_HOST_ONLY)) }
        runBlocking { wl.refresh() }
        val local = SecurityEngine(wl, PolicyManager())
        assertTrue(local.validate("https://example.com", NavigationType.LINK) is NavigationDecision.Allow)
        assertTrue(
            local.validate("https://www.example.com", NavigationType.LINK)
                is NavigationDecision.Block
        )
    }

    // ————————————————— Blocked: إعادة التوجيه والترميز —————————————————

    @Test
    fun `blocked - redirect to non whitelisted`() =
        assertBlocked("https://blocked.com", type = NavigationType.REDIRECT)

    @Test
    fun `blocked - redirect context does not weaken policy`() =
        assertBlocked("https://evil.com/step", BlockReason.NOT_WHITELISTED, NavigationType.REDIRECT)

    @Test
    fun `blocked - encoded redirect target executes at navigation time`() {
        // مثال.com نفسه مسموح (رابط يحمل وجهة مشفرة) — الحظر يقع عند محاولة الوصول للوجهة
        assertAllowed("https://example.com/redirect?url=https%3A%2F%2Fevil.com")
        assertBlocked("https://evil.com", BlockReason.NOT_WHITELISTED, NavigationType.REDIRECT)
        assertBlocked("https://evil.com", BlockReason.NOT_WHITELISTED, NavigationType.JS)
    }

    @Test
    fun `blocked - encoded host tricks are caught at parse or validate`() {
        // ترميز المضيف إلى example.com يبقى example.com (مسموح فعليًا — نفس الموقع)
        assertAllowed("https://ex%61mple.com")
        // ترميز يؤدي لمضيف آخر → إما parse invalid أو host مختلف → حظر
        assertBlocked("https://example.com%2F.evil.com", BlockReason.MALFORMED_URL)
    }

    // ————————————————— Blocked: IP ومخططات خبيثة —————————————————

    @Test
    fun `blocked - ip literal not in whitelist`() =
        assertBlocked("https://192.168.1.55/admin", BlockReason.NOT_WHITELISTED)

    @Test
    fun `blocked - ipv6 literal not in whitelist`() =
        assertBlocked("https://[2001:db8::1]/", BlockReason.NOT_WHITELISTED)

    @Test
    fun `blocked - javascript scheme`() =
        assertBlocked("javascript:alert(document.cookie)", BlockReason.UNSAFE_SCHEME)

    @Test
    fun `blocked - data scheme`() =
        assertBlocked("data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==", BlockReason.UNSAFE_SCHEME)

    @Test
    fun `blocked - file scheme`() =
        assertBlocked("file:///system/etc/hosts", BlockReason.UNSAFE_SCHEME)

    @Test
    fun `intent with pinned package opens through external app gate v1_8_0`() {
        // v1.8.0: الوجهة معلنة (package=) → قرار OpenExternal عبر بوابة الوالدين
        // (external_apps + تأكيد) — نفس مسار المخططات المخصصة؛ إطلاق تطبيق
        // خارجي ليس إخراجًا من القائمة لأن القائمة تحكم محتوى الويب الداخلي.
        val d = engine.validate(
            "intent://evil.com/#Intent;scheme=https;package=com.evil.app;end",
            NavigationType.LINK
        )
        assertTrue("expected OpenExternal got $d", d is NavigationDecision.OpenExternal)
    }

    @Test
    fun `blocked - anonymous intent without destination`() =
        assertBlocked("intent://evil.com/#Intent;scheme=https;end", BlockReason.UNSAFE_SCHEME)

    @Test
    fun `blocked - content and ws schemes`() {
        assertBlocked("content://settings/system", BlockReason.UNSAFE_SCHEME)
        assertBlocked("ws://evil.com/socket", BlockReason.UNSAFE_SCHEME)
        assertBlocked("wss://evil.com/socket", BlockReason.UNSAFE_SCHEME)
    }

    @Test
    fun `blocked - malformed urls`() {
        assertBlocked("not a url", BlockReason.MALFORMED_URL)
        assertBlocked("https://", BlockReason.MALFORMED_URL)
        assertBlocked("", BlockReason.MALFORMED_URL)
        assertBlocked(null, BlockReason.MALFORMED_URL)
    }

    @Test
    fun `blocked - embedded credentials always`() {
        // حتى لو طابق host النهائي قاعدة مسموحة: user@host حظر دائم
        assertBlocked("https://evil.com@example.com", BlockReason.EMBEDDED_CREDENTIALS)
        assertBlocked("https://user:pass@example.com", BlockReason.EMBEDDED_CREDENTIALS)
    }

    // ————————————————— Blocked: منافذ —————————————————

    @Test
    fun `blocked - non standard port without explicit rule`() =
        assertBlocked("https://example.com:8443/", BlockReason.NOT_WHITELISTED)

    @Test
    fun `allowed - non standard port with explicit rule`() {
        val wl = WhiteListEngine {
            listOf(rule(host = "example.com", subdomains = SubdomainPolicy.INCLUDE_SUBDOMAINS, port = 8443))
        }
        runBlocking { wl.refresh() }
        val local = SecurityEngine(wl, PolicyManager())
        assertTrue(local.validate("https://example.com:8443/", NavigationType.LINK) is NavigationDecision.Allow)
        assertTrue(local.validate("https://example.com/", NavigationType.LINK) is NavigationDecision.Block)
    }

    // ————————————————— Popup / New window —————————————————

    @Test
    fun `blocked - popup to non whitelisted`() =
        assertBlocked("https://evil.com/popup", BlockReason.NOT_WHITELISTED, NavigationType.POPUP)

    @Test
    fun `blocked - new window to non whitelisted`() =
        assertBlocked("https://evil.com/win", BlockReason.NOT_WHITELISTED, NavigationType.NEW_WINDOW)

    @Test
    fun `popup context never weakens whitelist`() {
        assertAllowed("https://example.com/ok", NavigationType.POPUP)
        assertBlocked("https://evil.com", BlockReason.NOT_WHITELISTED, NavigationType.POPUP)
        assertAllowed("https://example.com", NavigationType.NEW_WINDOW)
        assertBlocked("https://evil.com", BlockReason.NOT_WHITELISTED, NavigationType.NEW_WINDOW)
    }

    // ————————————————— داخلي وخارجي —————————————————

    @Test
    fun `about blank allowed internally`() =
        assertAllowed("about:blank", NavigationType.INITIAL)

    @Test
    fun `about other blocked`() =
        assertBlocked("about:config", BlockReason.UNSAFE_SCHEME)

    @Test
    fun `blob allowed for media`() =
        assertAllowed("blob:https://example.com/8f2c-4b1d", NavigationType.JS)

    @Test
    fun `external contact opens external decision`() {
        val decision = engine.validate("tel:+201234567890", NavigationType.LINK)
        assertTrue(decision is NavigationDecision.OpenExternal)
        decision as NavigationDecision.OpenExternal
        assertEquals("tel", decision.scheme)
    }

    // ————————————————— الوصول المؤقت (المرحلة الثانية) —————————————————

    @Test
    fun `temporary access grants and clears correctly`() {
        assertBlocked("https://temporary-site.com", BlockReason.NOT_WHITELISTED)
        assertTrue(policyManager.temporaryAccess.start(60_000))
        assertAllowed("https://temporary-site.com", NavigationType.TYPED)
        // إنهاء فوري → حظر مجددًا تلقائيًا
        policyManager.temporaryAccess.endNow()
        assertBlocked("https://temporary-site.com", BlockReason.NOT_WHITELISTED)
    }

    @Test
    fun `temporary access state machine expires on time`() {
        var now = 10_000L
        val manager = com.securebrowser.app.policy.TemporaryAccessManager(
            com.securebrowser.app.policy.InMemoryTempAccessStore()
        ) { now }
        manager.start(1_000)
        assertTrue(manager.isActive())
        now = 10_500
        assertTrue(manager.isActive())
        now = 11_000
        assertFalse(manager.isActive())
        assertFalse(manager.uiState.value.active)
    }

    @Test
    fun `temporary access does not become permanent after app restart`() {
        val store = com.securebrowser.app.policy.InMemoryTempAccessStore()
        var now = 0L
        val first = com.securebrowser.app.policy.TemporaryAccessManager(store) { now }
        first.start(5_000)
        // إغلاق التطبيق وفتحه بعد انتهاء النافذة
        now = 6_000
        val second = com.securebrowser.app.policy.TemporaryAccessManager(store) { now }
        second.restoreFromStore()
        assertFalse(second.isActive())
        assertFalse(second.uiState.value.active)
    }

    @Test
    fun `temporary access resumes countdown after restart before expiry`() {
        val store = com.securebrowser.app.policy.InMemoryTempAccessStore()
        var now = 0L
        val first = com.securebrowser.app.policy.TemporaryAccessManager(store) { now }
        first.start(60_000)
        now = 30_000
        val second = com.securebrowser.app.policy.TemporaryAccessManager(store) { now }
        second.restoreFromStore()
        assertTrue(second.isActive())
        assertEquals(30_000, second.remainingMs())
    }

    @Test
    fun `temporary access never weakens dangerous scheme policy`() {
        assertTrue(policyManager.temporaryAccess.start(60_000))
        assertBlocked("javascript:alert(1)", BlockReason.UNSAFE_SCHEME)
        assertBlocked("file:///etc/hosts", BlockReason.UNSAFE_SCHEME)
        assertBlocked("data:text/html,x", BlockReason.UNSAFE_SCHEME)
    }

    @Test
    fun `intent with whitelisted fallback opens externally`() {
        // intent بوجهة احتياطية مسموحة → قرار فتح خارجي (بوابة الوالدين تعمل بعد ذلك)
        val decision = engine.validate(
            "intent://example.com/#Intent;scheme=https;S.browser_fallback_url=https%3A%2F%2Fexample.com;end",
            NavigationType.LINK
        )
        assertTrue(decision is NavigationDecision.OpenExternal)
    }

    @Test
    fun `intent with blocked fallback is blocked`() {
        val decision = engine.validate(
            "intent://evil.com/#Intent;scheme=https;S.browser_fallback_url=https%3A%2F%2Fevil.com;end",
            NavigationType.LINK
        )
        assertTrue(decision is NavigationDecision.Block)
        assertEquals(BlockReason.NOT_WHITELISTED, (decision as NavigationDecision.Block).reason)
    }
}
