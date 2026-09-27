package com.securebrowser.app.security

import com.securebrowser.app.policy.PolicyManager
import com.securebrowser.app.security.model.NavigationDecision
import com.securebrowser.app.security.model.NavigationType
import com.securebrowser.app.security.whitelist.WhiteListEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * اختبارات تكامل الشبكة المحلية في SecurityEngine (v1.9.0):
 *
 * الضوابط التي يجب أن تثبت:
 * 1. روابط الشبكة المحلية تُسمح **دون أي قاعدة قائمة بيضاء** (الطلب الصريح).
 * 2. البوابة قابلة للإيقاف بقرار والد (localNetworkAllowed=false) — يعود
 *    الحكم للقائمة البيضاء كما قبل v1.9.0.
 * 3. المخططات الخطرة و userinfo تظل محظورة **حتى على العناوين المحلية**
 *    (السياسة تسبق الاستثناء تمامًا كاستثناء محرك البحث).
 * 4. العناوين العامة تبقى محظورة بلا قواعد — لا تغيير سلوكي خارج النطاق.
 */
class SecurityEngineLocalNetworkTest {

    private lateinit var engine: SecurityEngine

    @Before
    fun setup() {
        // قائمة بيضاء فارغة تمامًا — أي سماح هو من سياسة الشبكة المحلية وحدها
        val whiteListEngine = WhiteListEngine { emptyList() }
        runBlocking { whiteListEngine.refresh() }
        engine = SecurityEngine(whiteListEngine, PolicyManager())
    }

    private fun decision(url: String): NavigationDecision =
        engine.validate(url, NavigationType.TYPED)

    private fun allow(url: String): Boolean = decision(url) is NavigationDecision.Allow

    @Test
    fun `local network links are allowed without any whitelist rule`() {
        assertTrue(allow("http://192.168.1.1:8080/"))
        assertTrue(allow("http://10.0.0.5/admin"))
        assertTrue(allow("https://172.16.4.9:8443/"))
        assertTrue(allow("http://127.0.0.1:3000/"))
        assertTrue(allow("https://router.local/"))
        assertTrue(allow("http://nas.lan:9666"))
        assertTrue(allow("https://printer.home.arpa/"))
        assertTrue(allow("http://localhost:9000"))
        assertTrue(allow("http://[::1]:5000/"))
        assertTrue(allow("http://[fd12::1]/"))
    }

    @Test
    fun `public hosts stay blocked with empty whitelist`() {
        val d = decision("https://example.com/")
        assertTrue(d is NavigationDecision.Block)
        assertEquals(
            com.securebrowser.app.security.model.BlockReason.NOT_WHITELISTED,
            (d as NavigationDecision.Block).reason
        )
        assertTrue(!allow("http://8.8.8.8/"))
    }

    @Test
    fun `parent gate off restores whitelist-only behavior`() {
        val whiteListEngine = WhiteListEngine { emptyList() }
        runBlocking { whiteListEngine.refresh() }
        val gated = SecurityEngine(
            whiteListEngine,
            PolicyManager(),
            localNetworkAllowed = { false }
        )
        val d = gated.validate("http://192.168.1.1/", NavigationType.TYPED)
        assertTrue(d is NavigationDecision.Block)
        assertTrue(
            gated.validate("https://router.local/", NavigationType.TYPED)
                is NavigationDecision.Block
        )
    }

    @Test
    fun `dangerous schemes stay blocked even on local addresses`() {
        // ws/wss غير قابلة للعرض كتنقل — تبقى محظورة فوق IP محلي
        assertTrue(!allow("ws://192.168.1.1:8080/"))
        assertTrue(!allow("wss://[::1]/"))
        // javascript/file/data تبقى محظورة دائمًا
        assertTrue(!allow("javascript://192.168.1.1/"))
        assertTrue(!allow("file://192.168.1.1/etc"))
        assertTrue(!allow("data://192.168.1.1/"))
    }

    @Test
    fun `embedded credentials are rejected before the local network check`() {
        // user@host مخادع — التحقق من السلامة يسبق استثناء الشبكة المحلية
        val d = decision("http://admin@192.168.1.1/")
        assertTrue(d is NavigationDecision.Block)
        assertEquals(
            com.securebrowser.app.security.model.BlockReason.EMBEDDED_CREDENTIALS,
            (d as NavigationDecision.Block).reason
        )
    }

    @Test
    fun `whitelist rules keep working alongside local network`() {
        val rules = listOf(
            com.securebrowser.app.security.whitelist.WhiteListRule(
                id = 1,
                host = "example.com",
                path = null,
                scheme = null,
                port = null,
                type = com.securebrowser.app.security.whitelist.RuleType.ALLOW_DOMAIN,
                subdomainPolicy = com.securebrowser.app.security.whitelist.SubdomainPolicy.INCLUDE_SUBDOMAINS,
                enabled = true,
                createdAt = 0,
                updatedAt = 0
            )
        )
        val whiteListEngine = WhiteListEngine { rules }
        runBlocking { whiteListEngine.refresh() }
        val engine2 = SecurityEngine(whiteListEngine, PolicyManager())
        assertTrue(
            engine2.validate("https://example.com/page", NavigationType.TYPED)
                is NavigationDecision.Allow
        )
        assertTrue(
            engine2.validate("http://192.168.0.42/", NavigationType.TYPED)
                is NavigationDecision.Allow
        )
        assertTrue(
            engine2.validate("https://other.org/", NavigationType.TYPED)
                is NavigationDecision.Block
        )
    }
}
