package com.securebrowser.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات مدير تثبيت المواقع كاختصارات/تطبيقات على الشاشة الرئيسية (v1.10.0).
 */
class WebShortcutManagerTest {

    @Test
    fun `canPinUrl accepts valid web urls`() {
        assertTrue(WebShortcutManager.canPinUrl("https://example.com"))
        assertTrue(WebShortcutManager.canPinUrl("https://example.com/math/algebra"))
        assertTrue(WebShortcutManager.canPinUrl("http://my-school.edu"))
        assertTrue(WebShortcutManager.canPinUrl("http://my-school.edu:8080/portal"))
        assertTrue(WebShortcutManager.canPinUrl("https://192.168.1.1:8443/"))
        assertTrue(WebShortcutManager.canPinUrl("http://router.local/home"))
        assertTrue(WebShortcutManager.canPinUrl("http://localhost:3000/dashboard"))
    }

    @Test
    fun `canPinUrl accepts urls with leading or trailing whitespace`() {
        assertTrue(WebShortcutManager.canPinUrl("  https://example.com  \n"))
        assertTrue(WebShortcutManager.canPinUrl("\thttp://sub.school.org/index.html\r\n"))
    }

    @Test
    fun `canPinUrl rejects invalid, blank, or non-web schemes`() {
        assertFalse(WebShortcutManager.canPinUrl(null))
        assertFalse(WebShortcutManager.canPinUrl(""))
        assertFalse(WebShortcutManager.canPinUrl("   "))
        assertFalse(WebShortcutManager.canPinUrl("about:blank"))
        assertFalse(WebShortcutManager.canPinUrl("javascript:alert(1)"))
        assertFalse(WebShortcutManager.canPinUrl("file:///sdcard/test.html"))
        assertFalse(WebShortcutManager.canPinUrl("data:text/html,<h1>test</h1>"))
        assertFalse(WebShortcutManager.canPinUrl("content://media/external/images/media/1"))
        assertFalse(WebShortcutManager.canPinUrl("intent://example.com#Intent;scheme=https;end"))
        assertFalse(WebShortcutManager.canPinUrl("tg://resolve?domain=test"))
        assertFalse(WebShortcutManager.canPinUrl("mailto:parent@example.com"))
        assertFalse(WebShortcutManager.canPinUrl("not_a_url"))
    }

    @Test
    fun `extractHost extracts canonical host or fallback`() {
        assertEquals("example.com", WebShortcutManager.extractHost("https://example.com/page?x=1"))
        assertEquals("school.edu", WebShortcutManager.extractHost("http://school.edu:8080/portal"))
        assertEquals("192.168.1.1", WebShortcutManager.extractHost("http://192.168.1.1/"))
        assertEquals("router.local", WebShortcutManager.extractHost("https://router.local:8443/config"))
    }

    @Test
    fun `createShortcutId produces consistent deterministic identifier`() {
        val id1 = WebShortcutManager.createShortcutId("https://example.com")
        val id2 = WebShortcutManager.createShortcutId("https://example.com")
        val idCase = WebShortcutManager.createShortcutId("HTTPS://EXAMPLE.COM")
        val idOther = WebShortcutManager.createShortcutId("https://other.org")

        assertEquals(id1, id2)
        assertEquals(id1, idCase)
        assertTrue(id1.startsWith("web_shortcut_"))
        assertFalse(id1 == idOther)
    }
}
