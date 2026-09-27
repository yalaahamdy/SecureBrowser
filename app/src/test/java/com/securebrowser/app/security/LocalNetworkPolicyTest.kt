package com.securebrowser.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات سياسة الشبكة المحلية (v1.9.0) — طلب المستخدم الصريح:
 * كل روابط الشبكة المنزلية تعمل دون قائمة بيضاء، وكل ما عداها fail-closed.
 *
 * الأغطية: نطاقات IPv4 الخاصة بصيغها القياسية وغير القياسية (radix
 * المختلفة التي يطبّعها Chromium)، IPv6 (loopback/link-local/ULA/mapped)،
 * أسماء المضيفين المحلية (localhost/.local/.lan/.home.arpa/.internal)،
 * والسلبية الحاسمة (عناوين عامة و rebinding عبر أسماء عامة).
 */
class LocalNetworkPolicyTest {

    // ————— IPv4 قياسية خاصة —————

    @Test
    fun `private class c range is local`() {
        assertTrue(LocalNetworkPolicy.isLocalHost("192.168.1.1"))
        assertTrue(LocalNetworkPolicy.isLocalHost("192.168.0.225"))
        assertTrue(LocalNetworkPolicy.isLocalHost("192.168.255.255"))
        assertFalse(LocalNetworkPolicy.isLocalHost("192.169.1.1"))
        assertFalse(LocalNetworkPolicy.isLocalHost("192.167.1.1"))
    }

    @Test
    fun `private class a range is local`() {
        assertTrue(LocalNetworkPolicy.isLocalHost("10.0.0.5"))
        assertTrue(LocalNetworkPolicy.isLocalHost("10.255.255.255"))
        assertFalse(LocalNetworkPolicy.isLocalHost("11.0.0.1"))
    }

    @Test
    fun `private class b range is local`() {
        assertTrue(LocalNetworkPolicy.isLocalHost("172.16.0.1"))
        assertTrue(LocalNetworkPolicy.isLocalHost("172.31.255.255"))
        assertFalse(LocalNetworkPolicy.isLocalHost("172.15.0.1"))
        assertFalse(LocalNetworkPolicy.isLocalHost("172.32.0.1"))
    }

    @Test
    fun `loopback link-local cgnat and zero ranges are local`() {
        assertTrue(LocalNetworkPolicy.isLocalHost("127.0.0.1"))
        assertTrue(LocalNetworkPolicy.isLocalHost("127.9.9.9"))
        assertTrue(LocalNetworkPolicy.isLocalHost("169.254.10.20"))
        assertTrue(LocalNetworkPolicy.isLocalHost("100.64.0.1"))
        assertTrue(LocalNetworkPolicy.isLocalHost("0.0.0.0"))
        assertFalse(LocalNetworkPolicy.isLocalHost("100.128.0.1"))
        assertFalse(LocalNetworkPolicy.isLocalHost("169.255.0.1"))
    }

    // ————— IPv4 بصيغ Chromium غير القياسية —————

    @Test
    fun `decimal integer form maps to private range`() {
        // 3232235521 = 192.168.0.225
        assertTrue(LocalNetworkPolicy.isLocalHost("3232235521"))
        // 2130706433 = 127.0.0.1
        assertTrue(LocalNetworkPolicy.isLocalHost("2130706433"))
        // 134744072 = 8.8.8.8 (عام)
        assertFalse(LocalNetworkPolicy.isLocalHost("134744072"))
    }

    @Test
    fun `abbreviated dotted form absorbs trailing bytes like chromium`() {
        // 127.1 = 127.0.0.1
        assertTrue(LocalNetworkPolicy.isLocalHost("127.1"))
        // 192.168.257 → آخر جزء يمثل بايتين: 192.168.1.1
        assertTrue(LocalNetworkPolicy.isLocalHost("192.168.257"))
    }

    @Test
    fun `hex and octal forms are handled like chromium`() {
        assertTrue(LocalNetworkPolicy.isLocalHost("0x7f000001")) // 127.0.0.1
        assertTrue(LocalNetworkPolicy.isLocalHost("0177.0.0.1")) // 127.0.0.1 ثماني
        assertTrue(LocalNetworkPolicy.isLocalHost("0xc0a80001")) // 192.168.0.1
        // عام: 0x08080808 = 8.8.8.8
        assertFalse(LocalNetworkPolicy.isLocalHost("0x08080808"))
    }

    @Test
    fun `invalid numeric shapes are not local (fail-closed)`() {
        // 256 في جزء نهائي بأربع أجزاء = ليس IPv4 → اسم مضيف فاشل (لا يمر محليًا)
        assertFalse(LocalNetworkPolicy.isLocalHost("192.168.1.256"))
        // أجزاء أكثر من أربعة
        assertFalse(LocalNetworkPolicy.isLocalHost("1.2.3.4.5"))
        // صفر بادئ غير ثماني صالح (09)
        assertFalse(LocalNetworkPolicy.isLocalHost("09.1.1.1"))
    }

    // ————— IPv6 —————

    @Test
    fun `ipv6 loopback unspecified link-local and ula are local`() {
        assertTrue(LocalNetworkPolicy.isLocalHost("::1"))
        assertTrue(LocalNetworkPolicy.isLocalHost("0:0:0:0:0:0:0:1"))
        assertTrue(LocalNetworkPolicy.isLocalHost("::"))
        assertTrue(LocalNetworkPolicy.isLocalHost("fe80::1"))
        assertTrue(LocalNetworkPolicy.isLocalHost("FE80::A1B2:C3D4:E5F6:0708".lowercase()))
        assertTrue(LocalNetworkPolicy.isLocalHost("fc00::5"))
        assertTrue(LocalNetworkPolicy.isLocalHost("fd12:3456:789a::1"))
        assertFalse(LocalNetworkPolicy.isLocalHost("::2"))
        assertFalse(LocalNetworkPolicy.isLocalHost("2001:db8::1"))
        // نطاق site-local المهجور fec0::/10 ليس ضمن link-local
        assertFalse(LocalNetworkPolicy.isLocalHost("fec0::1"))
    }

    @Test
    fun `ipv4-mapped ipv6 checks the embedded v4`() {
        assertTrue(LocalNetworkPolicy.isLocalHost("::ffff:192.168.1.1"))
        assertTrue(LocalNetworkPolicy.isLocalHost("::ffff:127.0.0.1"))
        assertFalse(LocalNetworkPolicy.isLocalHost("::ffff:8.8.8.8"))
        // صيغة المجموعات: ::ffff:c0a8:1 = ::ffff:192.168.0.1
        assertTrue(LocalNetworkPolicy.isLocalHost("::ffff:c0a8:1"))
        // صيغة المجموعات لعنوان عام: ::ffff:0808:0808 = 8.8.8.8
        assertFalse(LocalNetworkPolicy.isLocalHost("::ffff:0808:0808"))
    }

    // ————— أسماء المضيفين المحلية —————

    @Test
    fun `local hostnames are local`() {
        assertTrue(LocalNetworkPolicy.isLocalHost("localhost"))
        assertTrue(LocalNetworkPolicy.isLocalHost("sub.localhost"))
        assertTrue(LocalNetworkPolicy.isLocalHost("router.local"))
        assertTrue(LocalNetworkPolicy.isLocalHost("nas.lan"))
        assertTrue(LocalNetworkPolicy.isLocalHost("printer.home.arpa"))
        assertTrue(LocalNetworkPolicy.isLocalHost("server.internal"))
    }

    @Test
    fun `public domain names are never local (no dns rebinding path)`() {
        assertFalse(LocalNetworkPolicy.isLocalHost("example.com"))
        assertFalse(LocalNetworkPolicy.isLocalHost("evil.com"))
        assertFalse(LocalNetworkPolicy.isLocalHost("mylocal.example.com"))
        // اسم يحوي كلمة local لكن ينتهي بـ TLD عام
        assertFalse(LocalNetworkPolicy.isLocalHost("local.example.com"))
        // لاحقة ناقصة بدون نقطة كاملة
        assertFalse(LocalNetworkPolicy.isLocalHost("routerlocal"))
        assertFalse(LocalNetworkPolicy.isLocalHost("localhost.com"))
    }

    @Test
    fun `trailing dot and case are normalized`() {
        assertTrue(LocalNetworkPolicy.isLocalHost("LOCALHOST."))
        assertTrue(LocalNetworkPolicy.isLocalHost("Router.LOCAL."))
        assertTrue(LocalNetworkPolicy.isLocalHost("192.168.1.1."))
    }

    // ————— التكامل مع ParsedUrl —————

    @Test
    fun `parsed local urls across ports and schemes classify as local network`() {
        val a = com.securebrowser.app.core.url.UrlNormalizer.normalize("http://192.168.1.1:8080/admin")
        val b = com.securebrowser.app.core.url.UrlNormalizer.normalize("https://router.local/")
        val c = com.securebrowser.app.core.url.UrlNormalizer.normalize("http://[::1]:5000")
        val d = com.securebrowser.app.core.url.UrlNormalizer.normalize("http://nas.lan:9666")
        assertTrue(a is com.securebrowser.app.core.url.NormalizeResult.Success)
        assertTrue(b is com.securebrowser.app.core.url.NormalizeResult.Success)
        assertTrue(c is com.securebrowser.app.core.url.NormalizeResult.Success)
        assertTrue(d is com.securebrowser.app.core.url.NormalizeResult.Success)
        assertTrue(LocalNetworkPolicy.isLocalNetwork((a as com.securebrowser.app.core.url.NormalizeResult.Success).url))
        assertTrue(LocalNetworkPolicy.isLocalNetwork((b as com.securebrowser.app.core.url.NormalizeResult.Success).url))
        assertTrue(LocalNetworkPolicy.isLocalNetwork((c as com.securebrowser.app.core.url.NormalizeResult.Success).url))
        assertTrue(LocalNetworkPolicy.isLocalNetwork((d as com.securebrowser.app.core.url.NormalizeResult.Success).url))
    }

    @Test
    fun `public web url is not local network`() {
        val r = com.securebrowser.app.core.url.UrlNormalizer.normalize("https://example.com/x")
        assertTrue(r is com.securebrowser.app.core.url.NormalizeResult.Success)
        assertFalse(LocalNetworkPolicy.isLocalNetwork((r as com.securebrowser.app.core.url.NormalizeResult.Success).url))
    }

    // ————— صحة محلل IPv4 المرن —————

    @Test
    fun `flexible ipv4 parser returns null for hostnames`() {
        assertNull(LocalNetworkPolicy.parseIpv4Flexible("example.com"))
        assertNull(LocalNetworkPolicy.parseIpv4Flexible("abc"))
        assertNull(LocalNetworkPolicy.parseIpv4Flexible(""))
        assertNull(LocalNetworkPolicy.parseIpv4Flexible("1.2.3.4.5"))
        assertNotNull(LocalNetworkPolicy.parseIpv4Flexible("8.8.8.8"))
    }
}
