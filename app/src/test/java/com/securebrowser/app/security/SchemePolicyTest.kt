package com.securebrowser.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** مصفوفة سياسة المخططات والمدقق الصارم. */
class SchemePolicyTest {

    @Test
    fun `web schemes categorized as content`() {
        assertEquals(SchemePolicy.SchemeCategory.WEB_CONTENT, SchemePolicy.categorize("https"))
        assertEquals(SchemePolicy.SchemeCategory.WEB_CONTENT, SchemePolicy.categorize("http"))
    }

    @Test
    fun `unsafe schemes are blocked by category`() {
        // v1.4.0: قائمة الممنوعات المتميزة — مخططات تنفيذ كود/موارد جهاز/نظام
        val unsafe = listOf(
            "javascript", "data", "file", "content", "ws", "wss",
            "chrome", "vnd.android", "android-app", "package", "jar"
        )
        for (scheme in unsafe) {
            assertEquals("scheme=$scheme", SchemePolicy.SchemeCategory.UNSAFE, SchemePolicy.categorize(scheme))
        }
    }

    @Test
    fun `unknown app schemes route through external gate v1_4_0`() {
        // v1.4.0: أي مخطط تطبيق غير متميز يفتح عبر بوابة التطبيقات الخارجية
        // (بوابة الوالدين + تأكيد + إطلاق محصّن) بدل الحظر الأعمى — اتصال الهاتف بتطبيقه
        for (scheme in listOf("viber", "zoommtg", "playit", "spotify", "fb", "ms-excel", "weirdapp")) {
            assertEquals("scheme=$scheme", SchemePolicy.SchemeCategory.EXTERNAL_APP, SchemePolicy.categorize(scheme))
            assertTrue(SchemePolicy.isExternalApp(scheme))
        }
    }

    @Test
    fun `external app schemes routed to app policy`() {
        for (scheme in listOf("tg", "whatsapp", "vnd.youtube", "geo", "market", "intent")) {
            assertEquals("scheme=$scheme", SchemePolicy.SchemeCategory.EXTERNAL_APP, SchemePolicy.categorize(scheme))
            assertTrue(SchemePolicy.isExternalApp(scheme))
        }
    }

    @Test
    fun `contact schemes routed externally`() {
        for (scheme in listOf("tel", "sms", "smsto", "mailto")) {
            assertEquals(SchemePolicy.SchemeCategory.EXTERNAL_CONTACT, SchemePolicy.categorize(scheme))
            assertTrue(SchemePolicy.isExternalContact(scheme))
        }
        assertFalse(SchemePolicy.isExternalContact("intent"))
    }

    @Test
    fun `intent is external app not contact`() {
        assertFalse(SchemePolicy.isExternalContact("intent"))
        assertTrue(SchemePolicy.isExternalApp("intent"))
    }

    @Test
    fun `about is internal category`() {
        assertEquals(SchemePolicy.SchemeCategory.INTERNAL_BLANK, SchemePolicy.categorize("about"))
    }

    @Test
    fun `blob is media category`() {
        assertEquals(SchemePolicy.SchemeCategory.BLOB, SchemePolicy.categorize("blob"))
    }

    @Test
    fun `validator flags embedded credentials`() {
        val parsed = com.securebrowser.app.core.url.UrlNormalizer.normalize("https://user:pass@example.com")
        assertTrue(parsed is com.securebrowser.app.core.url.NormalizeResult.Success)
        assertEquals(
            ValidationError.EMBEDDED_CREDENTIALS,
            UrlValidator.check((parsed as com.securebrowser.app.core.url.NormalizeResult.Success).url)
        )
    }

    @Test
    fun `validator passes clean url`() {
        val parsed = com.securebrowser.app.core.url.UrlNormalizer.normalize("https://example.com/clean?x=1")
        assertNull(
            UrlValidator.check((parsed as com.securebrowser.app.core.url.NormalizeResult.Success).url)
        )
    }

    @Test
    fun `download policy decisions`() {
        val policy = com.securebrowser.app.policy.DownloadPolicy()
        // ملف وسائط معروف + مصدر مسموح → سماح
        assertEquals(
            com.securebrowser.app.policy.DownloadDecision.ALLOW,
            policy.decide(sourceAllowed = true, scheme = "https", fileName = "clip.mp4", mimeType = null)
        )
        // مصدر غير مسموح → حظر بغض النظر عن النوع
        assertEquals(
            com.securebrowser.app.policy.DownloadDecision.BLOCK_SOURCE_NOT_ALLOWED,
            policy.decide(sourceAllowed = false, scheme = "https", fileName = "doc.pdf", mimeType = "application/pdf")
        )
        // مخطط غير ويب → حظر
        assertEquals(
            com.securebrowser.app.policy.DownloadDecision.BLOCK_SCHEME,
            policy.decide(sourceAllowed = true, scheme = "intent", fileName = "f.bin", mimeType = null)
        )
    }
}
