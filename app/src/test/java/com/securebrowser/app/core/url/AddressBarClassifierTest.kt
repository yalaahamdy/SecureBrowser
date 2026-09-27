package com.securebrowser.app.core.url

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات مصنِّف شريط العنوان — نقطة إصلاح "نص البحث يتحول إلى نطاق".
 *
 * القاعدة: المدخل الملتبس → بحث (وليس عنوانًا وهميًا يضاف إليه https://).
 */
class AddressBarClassifierTest {

    // ————— نصوص بحث يجب ألا تُعامل كعناوين —————

    @Test
    fun `single english word is a search`() {
        assertFalse(AddressBarClassifier.isLikelyUrl("wikipedia"))
    }

    @Test
    fun `single arabic word is a search`() {
        assertFalse(AddressBarClassifier.isLikelyUrl("أخبار"))
    }

    @Test
    fun `arabic phrase is a search`() {
        assertFalse(AddressBarClassifier.isLikelyUrl("أخبار الرياض اليوم"))
    }

    @Test
    fun `phrase with spaces is a search`() {
        assertFalse(AddressBarClassifier.isLikelyUrl("how to learn kotlin"))
    }

    @Test
    fun `two numbers like version is a search`() {
        assertFalse(AddressBarClassifier.isLikelyUrl("3.5"))
    }

    @Test
    fun `incomplete ipv4 is a search`() {
        assertFalse(AddressBarClassifier.isLikelyUrl("192.168.1"))
    }

    @Test
    fun `leading dot is a search`() {
        assertFalse(AddressBarClassifier.isLikelyUrl(".com"))
    }

    @Test
    fun `double dot is a search`() {
        assertFalse(AddressBarClassifier.isLikelyUrl("example..com"))
    }

    @Test
    fun `empty and blank are not urls`() {
        assertFalse(AddressBarClassifier.isLikelyUrl(""))
        assertFalse(AddressBarClassifier.isLikelyUrl("   "))
        assertFalse(AddressBarClassifier.isLikelyUrl(null))
    }

    @Test
    fun `single letter tld is a search`() {
        assertFalse(AddressBarClassifier.isLikelyUrl("example.c"))
    }

    @Test
    fun `numeric tld is a search`() {
        assertFalse(AddressBarClassifier.isLikelyUrl("site.123"))
    }

    // ————— عناوين حقيقية يجب أن تُعامل كعناوين —————

    @Test
    fun `domain with tld is a url`() {
        assertTrue(AddressBarClassifier.isLikelyUrl("example.com"))
    }

    @Test
    fun `www domain is a url`() {
        assertTrue(AddressBarClassifier.isLikelyUrl("www.example.com"))
    }

    @Test
    fun `multi label domain is a url`() {
        assertTrue(AddressBarClassifier.isLikelyUrl("en.wikipedia.org"))
    }

    @Test
    fun `explicit https is a url`() {
        assertTrue(AddressBarClassifier.isLikelyUrl("https://example.com/page?q=1"))
    }

    @Test
    fun `explicit http uppercase is a url`() {
        assertTrue(AddressBarClassifier.isLikelyUrl("HTTP://EXAMPLE.COM"))
    }

    @Test
    fun `domain with path is a url`() {
        assertTrue(AddressBarClassifier.isLikelyUrl("example.com/wiki/main"))
    }

    @Test
    fun `domain with port is a url`() {
        assertTrue(AddressBarClassifier.isLikelyUrl("example.com:8080"))
    }

    @Test
    fun `ipv4 is a url`() {
        assertTrue(AddressBarClassifier.isLikelyUrl("192.168.1.1"))
    }

    @Test
    fun `bracketed ipv6 is a url`() {
        assertTrue(AddressBarClassifier.isLikelyUrl("[::1]:8080"))
    }

    @Test
    fun `localhost is a url`() {
        assertTrue(AddressBarClassifier.isLikelyUrl("localhost"))
        assertTrue(AddressBarClassifier.isLikelyUrl("localhost:3000"))
    }

    @Test
    fun `trailing dot domain is a url`() {
        assertTrue(AddressBarClassifier.isLikelyUrl("example.com."))
    }

    @Test
    fun `arabic idn domain is a url`() {
        assertTrue(AddressBarClassifier.isLikelyUrl("موقع.كوم"))
    }

    @Test
    fun `two letter tld is a url`() {
        assertTrue(AddressBarClassifier.isLikelyUrl("example.co"))
    }

    @Test
    fun `public suffix chain is a url`() {
        assertTrue(AddressBarClassifier.isLikelyUrl("example.co.uk"))
    }

    @Test
    fun `known schemes are urls`() {
        assertTrue(AddressBarClassifier.isLikelyUrl("tel:+201234567890"))
        assertTrue(AddressBarClassifier.isLikelyUrl("mailto:user@example.com"))
        assertTrue(AddressBarClassifier.isLikelyUrl("tg://resolve?domain=example"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertTrue(AddressBarClassifier.isLikelyUrl("  example.com  "))
        assertFalse(AddressBarClassifier.isLikelyUrl("  hello world  "))
    }

    // ————— مطابقة isPlausibleDomain مباشرة —————

    @Test
    fun `plausible domain edge cases`() {
        assertTrue(AddressBarClassifier.isPlausibleDomain("example.com"))
        assertFalse(AddressBarClassifier.isPlausibleDomain("wikipedia"))
        assertFalse(AddressBarClassifier.isPlausibleDomain("-bad.com"))
        assertFalse(AddressBarClassifier.isPlausibleDomain("bad-.com"))
        assertFalse(AddressBarClassifier.isPlausibleDomain(".com"))
        assertFalse(AddressBarClassifier.isPlausibleDomain("a.b"))
    }
}
