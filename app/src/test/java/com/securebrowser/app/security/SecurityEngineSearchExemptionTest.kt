package com.securebrowser.app.security

import com.securebrowser.app.policy.PolicyManager
import com.securebrowser.app.security.model.NavigationDecision
import com.securebrowser.app.security.model.NavigationType
import com.securebrowser.app.security.whitelist.SubdomainPolicy
import com.securebrowser.app.security.whitelist.WhiteListEngine
import com.securebrowser.app.security.whitelist.WhiteListRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * اختبارات استثناء مضيف محرك البحث (البنية التحتية) في SecurityEngine.
 *
 * الضوابط التي يجب أن تثبت:
 * 1. مضيف المحرك المُعدّ فقط يُسمح به — بالتطابق التام.
 * 2. أي مضيف آخر (حتى نطاقات قريبة من المحرك) يبقى محظورًا.
 * 3. المخططات الخطرة تبقى محظورة حتى لو حُمّلت من مضيف المحرك.
 * 4. بلا إعفاء (قائمة فارغة) يبقى كل شيء محظورًا كما كان — لا تغيير سلوكي.
 */
class SecurityEngineSearchExemptionTest {

    private lateinit var engine: SecurityEngine
    private lateinit var whiteListEngine: WhiteListEngine

    @Before
    fun setup() {
        val rules = listOf(
            WhiteListRule(
                id = 1,
                host = "example.com",
                path = null,
                scheme = null,
                port = null,
                type = com.securebrowser.app.security.whitelist.RuleType.ALLOW_DOMAIN,
                subdomainPolicy = SubdomainPolicy.INCLUDE_SUBDOMAINS,
                enabled = true,
                createdAt = 0,
                updatedAt = 0
            )
        )
        whiteListEngine = WhiteListEngine { rules }
        runBlocking { whiteListEngine.refresh() }
        // المحرك المُعدّ: duckduckgo → مضيف واحد بالتطابق التام
        engine = SecurityEngine(
            whiteListEngine,
            PolicyManager(),
            infraHostsProvider = { setOf("duckduckgo.com") }
        )
    }

    private fun allow(url: String): Boolean {
        val d = engine.validate(url, NavigationType.TYPED)
        return d is NavigationDecision.Allow
    }

    @Test
    fun `configured engine host is allowed even though not whitelisted`() {
        assertTrue(allow("https://duckduckgo.com/?q=أخبار"))
        assertTrue(allow("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com"))
    }

    @Test
    fun `non-whitelisted site is still blocked`() {
        val d = engine.validate("https://evil.com/", NavigationType.LINK)
        assertTrue(d is NavigationDecision.Block)
    }

    @Test
    fun `engine subdomain lookalikes are still blocked`() {
        // subdomain مختلف → ليس تطابقًا تامًا → حظر
        assertTrue(engine.validate("https://m.duckduckgo.com/", NavigationType.TYPED) is NavigationDecision.Block)
        // نطاق مخادع يضم الاسم → حظر
        assertTrue(engine.validate("https://duckduckgo.com.evil.com/", NavigationType.TYPED) is NavigationDecision.Block)
        assertTrue(engine.validate("https://evil.com/duckduckgo.com", NavigationType.TYPED) is NavigationDecision.Block)
        // محرك آخر غير المُعدّ → حظر
        assertTrue(engine.validate("https://www.bing.com/search?q=x", NavigationType.TYPED) is NavigationDecision.Block)
        assertTrue(engine.validate("https://www.google.com/search?q=x", NavigationType.TYPED) is NavigationDecision.Block)
    }

    @Test
    fun `dangerous schemes stay blocked even with infra host`() {
        assertTrue(engine.validate("javascript:alert(1)", NavigationType.TYPED) is NavigationDecision.Block)
        assertTrue(engine.validate("file:///etc/passwd", NavigationType.TYPED) is NavigationDecision.Block)
        assertTrue(engine.validate("https://user@duckduckgo.com/", NavigationType.TYPED) is NavigationDecision.Block)
    }

    @Test
    fun `whitelisted sites still work normally`() {
        assertTrue(allow("https://example.com/"))
        assertTrue(allow("https://sub.example.com/page"))
    }

    @Test
    fun `empty infra provider keeps legacy behavior`() {
        val legacy = SecurityEngine(whiteListEngine, PolicyManager())
        assertTrue(legacy.validate("https://duckduckgo.com/", NavigationType.TYPED) is NavigationDecision.Block)
        assertTrue(legacy.validate("https://example.com/", NavigationType.TYPED) is NavigationDecision.Allow)
    }
}
