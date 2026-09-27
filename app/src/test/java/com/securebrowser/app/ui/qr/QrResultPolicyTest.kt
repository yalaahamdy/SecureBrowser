package com.securebrowser.app.ui.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات سياسة نتيجة QR (v1.9.0) — روابط المواقع ونصوص البحث.
 *
 * الضوابط:
 * 1. الروابط (صريحة المخطط أو شكل نطاق plausible أو IP/localhost) → OpenUrl.
 * 2. النصوص (بما فيها العربية متعددة الكلمات) → Search.
 * 3. المحارف الصفرية العرض وتوجيه النص تُزال قبل التصنيف.
 * 4. الفراغ بعد التنظيف → null (لا إجراء).
 * 5. المخططات الخطرة (javascript:/data:) تُصنَّف "رابطًا" لأنها تدخل
 *    مسار التنقل الأمني نفسه الذي يحظرها — لا تُخصَّص هنا.
 */
class QrResultPolicyTest {

    @Test
    fun `explicit urls are classified as links`() {
        val a = QrResultPolicy.handle("https://example.com/page?x=1")
        assertTrue(a is QrResultPolicy.QrAction.OpenUrl)
        assertEquals("https://example.com/page?x=1", (a as QrResultPolicy.QrAction.OpenUrl).url)

        assertTrue(QrResultPolicy.handle("http://192.168.1.1:8080/admin")
            is QrResultPolicy.QrAction.OpenUrl)
        assertTrue(QrResultPolicy.handle("https://router.local/")
            is QrResultPolicy.QrAction.OpenUrl)
        assertTrue(QrResultPolicy.handle("http://localhost:3000/")
            is QrResultPolicy.QrAction.OpenUrl)
    }

    @Test
    fun `plain and arabic texts are classified as search`() {
        val a = QrResultPolicy.handle("كيف أتعلم الرياضيات")
        assertTrue(a is QrResultPolicy.QrAction.Search)
        assertEquals("كيف أتعلم الرياضيات", (a as QrResultPolicy.QrAction.Search).query)

        assertTrue(QrResultPolicy.handle("history of science")
            is QrResultPolicy.QrAction.Search)
        assertTrue(QrResultPolicy.handle("singleword")
            is QrResultPolicy.QrAction.Search)
    }

    @Test
    fun `scheme-less domain shapes go to the url path`() {
        // شكل نطاق plausible → عنوان (SecurityEngine يقرر السماح بعدها)
        assertTrue(QrResultPolicy.handle("example.com")
            is QrResultPolicy.QrAction.OpenUrl)
        assertTrue(QrResultPolicy.handle("sub.example.com/page")
            is QrResultPolicy.QrAction.OpenUrl)
    }

    @Test
    fun `invisible and bidi characters are stripped`() {
        val poisoned = "https://example.com/\u200Bevil"
        val a = QrResultPolicy.handle(poisoned)
        assertEquals("https://example.com/evil", (a as QrResultPolicy.QrAction.OpenUrl).url)

        val rtl = "\u202Ehttps://example.com"
        val b = QrResultPolicy.handle(rtl)
        assertTrue(b is QrResultPolicy.QrAction.OpenUrl)
        assertEquals("https://example.com", (b as QrResultPolicy.QrAction.OpenUrl).url)

        val bom = "\uFEFFنص عربي للبحث"
        val c = QrResultPolicy.handle(bom)
        assertEquals("نص عربي للبحث", (c as QrResultPolicy.QrAction.Search).query)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val a = QrResultPolicy.handle("  https://example.com  \n")
        assertEquals("https://example.com", (a as QrResultPolicy.QrAction.OpenUrl).url)
    }

    @Test
    fun `empty or blank results yield no action`() {
        assertNull(QrResultPolicy.handle(""))
        assertNull(QrResultPolicy.handle("   "))
        assertNull(QrResultPolicy.handle("\u200B\u200C\uFEFF"))
        assertNull(QrResultPolicy.handle("\n\n"))
    }

    @Test
    fun `dangerous schemes still go through the navigation path`() {
        // تُصنَّف روابطًا (لا بحثًا) — الحظر فعليًا يحدث في SecurityEngine
        assertTrue(QrResultPolicy.handle("javascript:alert(1)")
            is QrResultPolicy.QrAction.OpenUrl)
        assertTrue(QrResultPolicy.handle("data:text/plain,x")
            is QrResultPolicy.QrAction.OpenUrl)
    }
}
