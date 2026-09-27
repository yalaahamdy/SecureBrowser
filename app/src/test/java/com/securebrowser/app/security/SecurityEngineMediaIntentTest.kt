package com.securebrowser.app.security

import com.securebrowser.app.policy.PolicyManager
import com.securebrowser.app.security.model.BlockReason
import com.securebrowser.app.security.model.NavigationDecision
import com.securebrowser.app.security.model.NavigationType
import com.securebrowser.app.security.whitelist.SubdomainPolicy
import com.securebrowser.app.security.whitelist.WhiteListEngine
import com.securebrowser.app.security.whitelist.WhiteListRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * اختبارات قرار مشغلات الوسائط في SecurityEngine (v1.2.0) — الإصلاح 3.
 *
 * الضوابط الأمنية:
 * - intent وسائطي (مشغل معروف) بلا fallback → OpenExternal عندما تكون البوابة مفعّلة.
 * - نفس الـ intent مع بوابة "مشغلات الفيديو" معطلة → حظر (fail-closed).
 * - intent بحزمة مجهولة بلا fallback → حظر كما كان (لا تغيير).
 * - intent وسائطي مع fallback غير مسموح → OpenExternal (المشغل هو الوجهة الحقيقية).
 * - fallback المسموح ما زال يعطي OpenExternal لكل intent (السلوك القديم محفوظ).
 * - مخططات المشغلات (vlc/rtsp...) تُصنف EXTERNAL_APP، وأي مخطط تطبيق غير متميز
 *   يمر عبر البوابة (v1.4.0) — قائمة الممنوعات المتميزة (javascript/file/...) تبقى UNSAFE.
 */
class SecurityEngineMediaIntentTest {

    private lateinit var whiteListEngine: WhiteListEngine
    private lateinit var policyManager: PolicyManager
    private lateinit var engine: SecurityEngine

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
        policyManager = PolicyManager()
        engine = SecurityEngine(whiteListEngine, policyManager)
    }

    private fun validate(raw: String): NavigationDecision =
        engine.validate(raw, NavigationType.LINK)

    // ————— مشغلات الوسائط — بوابة مفعّلة (الافتراضي) —————

    @Test
    fun `vlc intent without fallback opens external when gate enabled`() {
        policyManager.videoPlayersEnabled = { true }
        val d = validate("intent://open#Intent;package=org.videolan.vlc;end")
        assertTrue("expected OpenExternal got $d", d is NavigationDecision.OpenExternal)
    }

    @Test
    fun `video mime intent without fallback opens external when gate enabled`() {
        policyManager.videoPlayersEnabled = { true }
        val d = validate("intent://watch#Intent;package=com.unknown.edu;S.type=video/mp4;end")
        assertTrue("expected OpenExternal got $d", d is NavigationDecision.OpenExternal)
    }

    @Test
    fun `media intent with blocked fallback opens external when gate enabled`() {
        policyManager.videoPlayersEnabled = { true }
        // fallback → evil.com غير مسموحة؛ المشغل هو الوجهة الحقيقية فيُفتح عبر البوابة
        val d = validate(
            "intent://watch#Intent;package=com.mxtech.videoplayer.ad;" +
                "S.browser_fallback_url=https%3A%2F%2Fevil.com%2Fx;end"
        )
        assertTrue("expected OpenExternal got $d", d is NavigationDecision.OpenExternal)
    }

    // ————— مشغلات الوسائط — بوابة معطلة (قرار الوالد) —————

    @Test
    fun `media intent is blocked when video players gate disabled`() {
        policyManager.videoPlayersEnabled = { false }
        val d = validate("intent://open#Intent;package=org.videolan.vlc;end")
        assertTrue("expected Block got $d", d is NavigationDecision.Block)
        assertEquals(BlockReason.UNSAFE_SCHEME, (d as NavigationDecision.Block).reason)
    }

    // ————— fail-closed يبقى لما ليس وسائطيًا —————

    @Test
    fun `unknown package intent without fallback is still blocked`() {
        policyManager.videoPlayersEnabled = { true }
        val d = validate("intent://x#Intent;package=com.unknown.app;end")
        assertTrue("expected Block got $d", d is NavigationDecision.Block)
        assertEquals(BlockReason.UNSAFE_SCHEME, (d as NavigationDecision.Block).reason)
    }

    @Test
    fun `non media intent with blocked fallback is still blocked`() {
        policyManager.videoPlayersEnabled = { true }
        val d = validate(
            "intent://x#Intent;scheme=https;package=com.android.browser;" +
                "S.browser_fallback_url=https%3A%2F%2Fevil.com%2Fx;end"
        )
        assertTrue("expected Block got $d", d is NavigationDecision.Block)
    }

    @Test
    fun `whitelisted fallback intent still opens external - old behavior kept`() {
        policyManager.videoPlayersEnabled = { false }
        val d = validate(
            "intent://example.com/x#Intent;scheme=https;package=com.android.browser;" +
                "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fx;end"
        )
        assertTrue("expected OpenExternal got $d", d is NavigationDecision.OpenExternal)
    }

    // ————— مخططات المشغلات —————

    @Test
    fun `rtsp scheme is external app`() {
        val d = validate("rtsp://media.example.edu/lesson1")
        assertTrue("expected OpenExternal got $d", d is NavigationDecision.OpenExternal)
    }

    @Test
    fun `vlc scheme is external app`() {
        val d = validate("vlc://https://cdn.example.edu/lecture.mp4")
        assertTrue("expected OpenExternal got $d", d is NavigationDecision.OpenExternal)
    }

    @Test
    fun `unknown app scheme opens external through gate v1_4_0`() {
        // v1.4.0: مخططات التطبيقات غير المدرجة تمر عبر بوابة التطبيقات الخارجية
        // (كانت تُحظر fail-closed فتعذّر الاتصال بتطبيقات الهاتف)
        val d = validate("weirdapp://open/thing")
        assertTrue("expected OpenExternal got $d", d is NavigationDecision.OpenExternal)
    }

    @Test
    fun `privileged schemes stay hard blocked v1_4_0`() {
        // قائمة الممنوعات المتميزة لا تتأثر بالتعمية: تنفيذ كود/موارد جهاز
        for (raw in listOf(
            "javascript:alert(1)",
            "file:///etc/hosts",
            "content://settings/system"
        )) {
            val d = validate(raw)
            assertTrue("expected Block for $raw got $d", d is NavigationDecision.Block)
            assertEquals(BlockReason.UNSAFE_SCHEME, (d as NavigationDecision.Block).reason)
        }
    }

    // ————— تلجرام — لا تراجع عن القرار السابق —————

    @Test
    fun `tg deep link still opens external`() {
        val d = validate("tg://resolve?domain=durov")
        assertTrue("expected OpenExternal got $d", d is NavigationDecision.OpenExternal)
    }
}
