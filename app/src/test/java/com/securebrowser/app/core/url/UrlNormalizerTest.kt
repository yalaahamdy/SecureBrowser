package com.securebrowser.app.core.url

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات محلل/مطوّع URL الصارم. */
class UrlNormalizerTest {

    private fun parse(url: String): ParsedUrl? =
        when (val r = UrlNormalizer.normalize(url)) {
            is NormalizeResult.Success -> r.url
            is NormalizeResult.Invalid -> null
        }

    @Test
    fun `parses simple https url`() {
        val url = parse("https://example.com")!!
        assertEquals("https", url.scheme)
        assertEquals("example.com", url.host)
        assertEquals("/", url.path)
        assertNull(url.port)
        assertEquals(443, url.effectivePort)
    }

    @Test
    fun `lowercases host and scheme`() {
        val url = parse("HTTPS://EXAMPLE.COM/News")!!
        assertEquals("https", url.scheme)
        assertEquals("example.com", url.host)
        assertEquals("/News", url.path) // المسار حساس لحالة الأحرف
    }

    @Test
    fun `strips trailing dot from host`() {
        assertEquals("example.com", parse("https://example.com.")!!.host)
        assertEquals("example.com", parse("https://example.com..")!!.host)
    }

    @Test
    fun `strips tabs newlines crlf anywhere`() {
        val url = parse("https://exa\nmple\t.com/pa\rr")!!
        assertEquals("example.com", url.host)
        assertEquals("/par", url.path)
    }

    @Test
    fun `extracts explicit port`() {
        val url = parse("https://example.com:8443/x")!!
        assertEquals(8443, url.port)
        assertEquals(8443, url.effectivePort)
    }

    @Test
    fun `rejects out of range port`() {
        assertNull(parse("https://example.com:99999/x"))
        assertNull(parse("https://example.com:0/x"))
        assertNull(parse("https://example.com:abc/x"))
    }

    @Test
    fun `decodes percent encoded host`() {
        assertEquals("example.com", parse("https://ex%61mple.com")!!.host)
    }

    @Test
    fun `blocks double encoded host tricks`() {
        // يفك الترميز مرتين → يظهر '/' داخل الـ host → مرفوض
        assertNull(parse("https://example.com%2F.evil.com"))
        assertNull(parse("https://example.com%252F.evil.com"))
    }

    @Test
    fun `rejects percent residue in host`() {
        assertNull(parse("https://exam%ple.com"))
    }

    @Test
    fun `rejects forbidden chars inside host`() {
        // سليم: السلاش في المسار لا يفسد الـ host
        org.junit.Assert.assertNotNull(parse("https://example.com/evil"))
        assertNull(parse("https://exam ple.com"))
    }

    @Test
    fun `backslash-at trick surfaces as userinfo for validation layer`() {
        // evil.com\@example.com → الـ host الظاهر example.com مع userinfo مشبوه
        // (القرار: حظر عبر UrlValidator وليس التحليل)
        val url = parse("https://evil.com\\@example.com")!!
        assertEquals("example.com", url.host)
        assertTrue(url.userInfo!!.contains("evil.com"))
    }

    @Test
    fun `detects embedded userinfo`() {
        val url = parse("https://example.com@evil.com")!!
        assertEquals("evil.com", url.host)
        assertEquals("example.com", url.userInfo)
    }

    @Test
    fun `handles ipv6 literal`() {
        val url = parse("https://[2001:db8::1]:8443/")!!
        assertTrue(url.isIpLiteral)
        assertEquals("2001:db8::1", url.host)
        assertEquals(8443, url.port)
    }

    @Test
    fun `detects ipv4 literal`() {
        val url = parse("https://93.184.216.34/page")!!
        assertTrue(url.isIpLiteral)
        assertEquals("93.184.216.34", url.host)
    }

    @Test
    fun `converts idn to punycode`() {
        val url = parse("https://مثال.إختبار/path")!!
        assertTrue(url.host.startsWith("xn--"))
        assertFalse(url.host.contains("مثال"))
    }

    @Test
    fun `keeps non hierarchical scheme as opaque`() {
        val url = parse("tel:+201234567890")!!
        assertEquals("tel", url.scheme)
        assertEquals("+201234567890", url.opaquePart)
    }

    @Test
    fun `assumes https when scheme missing`() {
        val url = parse("example.com/news")!!
        assertEquals("https", url.scheme)
        assertEquals("example.com", url.host)
        assertEquals("/news", url.path)
    }

    @Test
    fun `normalizes http colon without slashes`() {
        val url = parse("http:example.com/x")!!
        assertEquals("http", url.scheme)
        assertEquals("example.com", url.host)
        assertEquals("/x", url.path)
    }

    @Test
    fun `rejects empty and malformed`() {
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("https://"))
        assertNull(parse("https:///path"))
    }

    @Test
    fun `decodes path repeatedly for matching`() {
        // %252F → %2F → / — فك الترميز التكراري يكشف المقاطع المخفية
        val url = parse("https://example.com/a%252Fb")!!
        assertEquals("/a/b", url.path)
    }

    @Test
    fun `canonical roundtrip is stable`() {
        val parsed = parse("https://example.com/news/1?x=1")!!
        assertEquals("https://example.com/news/1?x=1", parsed.canonical())
    }
}
