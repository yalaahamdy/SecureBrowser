package com.securebrowser.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات مشغلات الوسائط الخارجية (v1.2.0) — الإصلاح 3:
 * المنصات التعليمية تطلق الفيديو عبر intent:// بمشغلات معروفة أو مخططات مشغلات.
 *
 * الضوابط الأمنية:
 * - المشغلات المعروفة فقط (قائمة مغلقة) — أي حزمة مجهولة بلا fallback يبقى محظورة.
 * - mime video/audio صريح يكفي لاعتبار الـ intent وسائطيًا.
 * - فحص isMediaIntent لا يقرع أبدًا على intent عادي (متصفح/مجهول).
 */
class ExternalAppPolicyMediaIntentTest {

    // ————— استخراج معاملات intent —————

    @Test
    fun `target package extracted from intent uri`() {
        val uri = "intent://t.me/durov#Intent;scheme=http;package=org.telegram.messenger;end"
        assertEquals("org.telegram.messenger", ExternalAppPolicy.intentTargetPackage(uri))
    }

    @Test
    fun `target package percent encoded is decoded`() {
        val uri = "intent://x#Intent;package=org.videolan.vlc%3Bend;end"
        assertEquals("org.videolan.vlc;end", ExternalAppPolicy.intentTargetPackage(uri))
    }

    @Test
    fun `missing package returns null`() {
        assertNull(ExternalAppPolicy.intentTargetPackage("intent://x#Intent;scheme=https;end"))
    }

    @Test
    fun `mime extracted from S type and type params`() {
        assertEquals(
            "video/mp4",
            ExternalAppPolicy.intentTargetMime("intent://x#Intent;S.type=video%2Fmp4;end")
        )
        assertEquals(
            "audio/mpeg",
            ExternalAppPolicy.intentTargetMime("intent://x#Intent;type=audio/mpeg;end")
        )
        assertNull(ExternalAppPolicy.intentTargetMime("intent://x#Intent;end"))
    }

    // ————— isMediaIntent —————

    @Test
    fun `vlc package intent is media`() {
        val uri = "intent://open#Intent;scheme=rtsp;package=org.videolan.vlc;end"
        assertTrue(ExternalAppPolicy.isMediaIntent(uri))
    }

    @Test
    fun `mx player package intent is media`() {
        assertTrue(
            ExternalAppPolicy.isMediaIntent(
                "intent://play#Intent;package=com.mxtech.videoplayer.ad;end"
            )
        )
    }

    @Test
    fun `explicit video mime intent is media even with unknown package`() {
        assertTrue(
            ExternalAppPolicy.isMediaIntent(
                "intent://watch#Intent;package=com.unknown.app;S.type=video/mp4;end"
            )
        )
    }

    @Test
    fun `explicit audio mime intent is media`() {
        assertTrue(
            ExternalAppPolicy.isMediaIntent("intent://x#Intent;S.type=audio/aac;end")
        )
    }

    @Test
    fun `unknown package without mime is not media - fail closed`() {
        assertFalse(
            ExternalAppPolicy.isMediaIntent("intent://x#Intent;package=com.unknown.app;end")
        )
    }

    @Test
    fun `browser fallback intent is not media`() {
        assertFalse(
            ExternalAppPolicy.isMediaIntent(
                "intent://x#Intent;scheme=https;package=com.android.browser;S.browser_fallback_url=https%3A%2F%2Fexample.com;end"
            )
        )
    }

    @Test
    fun `mime with parameters is still detected`() {
        assertTrue(
            ExternalAppPolicy.isMediaIntent("intent://x#Intent;S.type=video/mp4;codecs=avc1;end")
        )
    }

    // ————— isMediaScheme —————

    @Test
    fun `known player schemes detected case insensitive`() {
        for (s in listOf("vlc", "VLC", "mxplayer", "rtsp", "nplayer", "kmplayer", "splayer")) {
            assertTrue("scheme=$s", ExternalAppPolicy.isMediaScheme(s))
        }
    }

    @Test
    fun `web and app schemes are not media schemes`() {
        assertFalse(ExternalAppPolicy.isMediaScheme("https"))
        assertFalse(ExternalAppPolicy.isMediaScheme("tg"))
        assertFalse(ExternalAppPolicy.isMediaScheme("intent"))
        assertFalse(ExternalAppPolicy.isMediaScheme("whatsapp"))
    }

    // ————— فشل الاتصال بتلجرام: الحزمة المثبتة تُكشف كتطبيق معروف —————

    @Test
    fun `telegram web link still suggests app`() {
        val s = ExternalAppPolicy.suggestForWebUrl("https://oauth.telegram.org/auth?bot=x")
        assertNull("oauth host ليس نطاق اقتراح تطبيق", s)
        val t = ExternalAppPolicy.suggestForWebUrl("https://t.me/joinchat/abc")
        assertEquals(ExternalAppPolicy.AppKind.TELEGRAM, t!!.kind)
    }
}
