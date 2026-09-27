package com.securebrowser.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** مصفوفة سياسة التطبيقات الخارجية — مواصفة المرحلة 2 §19-§20. */
class ExternalAppPolicyTest {

    // ————— اقتراح فتح التطبيقات لروابط الويب —————

    @Test
    fun `telegram link suggests telegram app`() {
        val s = ExternalAppPolicy.suggestForWebUrl("https://t.me/durov")
        assertNotNull(s)
        assertEquals(ExternalAppPolicy.AppKind.TELEGRAM, s!!.kind)
        assertEquals("Telegram", s.appLabel)
    }

    @Test
    fun `whatsapp links suggest whatsapp app`() {
        for (url in listOf("https://wa.me/123456789", "https://api.whatsapp.com/send?phone=123")) {
            val s = ExternalAppPolicy.suggestForWebUrl(url)
            assertNotNull("url=$url", s)
            assertEquals(ExternalAppPolicy.AppKind.WHATSAPP, s!!.kind)
        }
    }

    @Test
    fun `youtube links suggest youtube app`() {
        for (url in listOf("https://www.youtube.com/watch?v=x", "https://youtu.be/x", "https://m.youtube.com/")) {
            val s = ExternalAppPolicy.suggestForWebUrl(url)
            assertNotNull("url=$url", s)
            assertEquals(ExternalAppPolicy.AppKind.YOUTUBE, s!!.kind)
        }
    }

    @Test
    fun `maps links suggest maps app`() {
        assertNotNull(ExternalAppPolicy.suggestForWebUrl("https://maps.google.com/?q=cairo"))
        assertNotNull(ExternalAppPolicy.suggestForWebUrl("https://www.google.com/maps/place/Cairo"))
        assertNull(ExternalAppPolicy.suggestForWebUrl("https://www.google.com/search?q=cairo"))
    }

    @Test
    fun `regular sites never suggest an app`() {
        assertNull(ExternalAppPolicy.suggestForWebUrl("https://example.com"))
        assertNull(ExternalAppPolicy.suggestForWebUrl("https://evil.com/t.me/fake"))
        assertNull(ExternalAppPolicy.suggestForWebUrl("https://evil.com/?url=https://t.me/x"))
    }

    // ————— المخططات المباشرة —————

    @Test
    fun `known app schemes classified`() {
        assertEquals(ExternalAppPolicy.AppKind.TELEGRAM, ExternalAppPolicy.kindForScheme("tg"))
        assertEquals(ExternalAppPolicy.AppKind.WHATSAPP, ExternalAppPolicy.kindForScheme("whatsapp"))
        assertEquals(ExternalAppPolicy.AppKind.YOUTUBE, ExternalAppPolicy.kindForScheme("vnd.youtube"))
        assertEquals(ExternalAppPolicy.AppKind.MAPS, ExternalAppPolicy.kindForScheme("geo"))
        assertEquals(ExternalAppPolicy.AppKind.MAIL, ExternalAppPolicy.kindForScheme("mailto"))
        assertEquals(ExternalAppPolicy.AppKind.PHONE, ExternalAppPolicy.kindForScheme("tel"))
        assertEquals(ExternalAppPolicy.AppKind.SMS, ExternalAppPolicy.kindForScheme("sms"))
        assertEquals(ExternalAppPolicy.AppKind.GENERIC, ExternalAppPolicy.kindForScheme("market"))
        assertNull(ExternalAppPolicy.kindForScheme("javascript"))
        assertNull(ExternalAppPolicy.kindForScheme("data"))
        assertNull(ExternalAppPolicy.kindForScheme("file"))
    }

    // ————— intent fallback —————

    @Test
    fun `intent fallback url extracted`() {
        val fallback = ExternalAppPolicy.intentFallbackUrl(
            "intent://example.com/#Intent;scheme=https;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fpage;end"
        )
        assertEquals("https://example.com/page", fallback)
    }

    @Test
    fun `intent without fallback returns null`() {
        assertNull(ExternalAppPolicy.intentFallbackUrl("intent://evil.com/#Intent;scheme=https;package=com.evil.app;end"))
    }

    @Test
    fun `intent fallback must be web url`() {
        assertNull(
            ExternalAppPolicy.intentFallbackUrl(
                "intent://x/#Intent;S.browser_fallback_url=file%3A%2F%2F%2Fetc%2Fhosts;end"
            )
        )
    }

    @Test
    fun `isExternalAppScheme gate`() {
        assertTrue(ExternalAppPolicy.isExternalAppScheme("tg"))
        assertTrue(ExternalAppPolicy.isExternalAppScheme("intent"))
        assertFalse(ExternalAppPolicy.isExternalAppScheme("https"))
        assertFalse(ExternalAppPolicy.isExternalAppScheme("javascript"))
    }
}
