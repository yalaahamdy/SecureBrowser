package com.securebrowser.app.security

import com.securebrowser.app.core.url.ParsedUrl
import java.util.Locale

/**
 * سياسة الشبكة المحلية (v1.9.0) — طلب المستخدم الصريح:
 * روابط الأجهزة على الشبكة المنزلية (الراوتر، NAS، الطابعة، خوادم التعليم
 * المحلية، كاميرات المراقبة…) تعمل مباشرة **دون إضافتها إلى القائمة البيضاء**.
 *
 * النطاق المحلي المعتمد (fail-closed لكل ما عداه):
 * - IPv4 حرفي داخل النطاقات غير القابلة للتوجيه عبر الإنترنت:
 *   10.0.0.0/8، 172.16.0.0/12، 192.168.0.0/16، 127.0.0.0/8 (loopback)،
 *   169.254.0.0/16 (link-local)، 100.64.0.0/10 (CGNAT)، 0.0.0.0/8 (this network).
 * - صيغ IPv4 غير القياسية التي يطبّعها Chromium (radix مختلفة: 127.1 =
 *   127.0.0.1، 0x7f000001، 0177.0.0.1، 3232235521 = 192.168.0.225…) —
 *   تُحلَّل ويفحص العنوان الناتج بالدلالات نفسها حتى لا تُستخدم صيغة
 *   غريبة لالتفاف السياسة أو للاختلاف مع ما يحمله WebView فعليًا.
 * - IPv6: ::1 (loopback)، :: (unspecified)، fe80::/10 (link-local)،
 *   fc00::/7 (ULA fc/fd)، ::ffff:0:0/96 (IPv4-mapped → يُفحص العنوان
 *   المدمج بنطاقات IPv4 نفسها).
 * - أسماء مضيفين محلية: localhost و*.localhost (RFC 6761)، *.local
 *   (mDNS — RFC 6762)، *.home.arpa (RFC 8375)، *.lan و*.internal
 *   (شائعة في الراوترات المنزلية وخوادم الشبكة المحلية).
 *
 * حدود أمنية متعمدة:
 * - **لا يوجد DNS resolution داخل هذه السياسة** — اسم نطاق عام يُوجَّه
 *   إلى IP خاص (DNS rebinding) لا يمر هنا لأن الاسم ليس IP حرفيًا ولا
 *   من اللاحقات المحلية، فيبقى خاضعًا للقائمة البيضاء كالمعتاد.
 * - كل ما عدا ما سبق → ليس محليًا (يقرر بعدها WhiteListEngine كالمعتاد).
 * - المخططات الخطرة تبقى محظورة قبل وصول أي قرار هنا (SchemePolicy
 *   تسبق هذه السياسة في SecurityEngine).
 *
 * pure-Kotlin — قابل للاختبار آليًا على JVM.
 */
object LocalNetworkPolicy {

    /** لاحقات أسماء المضيفين المحلية (تُفحص بعد نقطة كاملة — مطابقة *.suffix). */
    private val LOCAL_HOST_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal")

    // نطاقات IPv4 الخاصة/غير القابلة للتوجيه — (أدنى قيمة، أعلى قيمة) بصيغة uint32
    private val PRIVATE_IPV4_RANGES: List<Pair<Long, Long>> = listOf(
        rangeOf("0.0.0.0", "0.255.255.255"),        // 0/8 — this network (يشمل 0.0.0.0)
        rangeOf("10.0.0.0", "10.255.255.255"),      // 10/8 — خاص
        rangeOf("100.64.0.0", "100.127.255.255"),   // 100.64/10 — CGNAT (غير موجه عبر الإنترنت)
        rangeOf("127.0.0.0", "127.255.255.255"),    // 127/8 — loopback
        rangeOf("169.254.0.0", "169.254.255.255"),  // 169.254/16 — link-local
        rangeOf("172.16.0.0", "172.31.255.255"),    // 172.16/12 — خاص
        rangeOf("192.168.0.0", "192.168.255.255")   // 192.168/16 — خاص
    )

    /** هل العنوان المُحلَّل رابط شبكة محلية (يُسمح به دون قائمة بيضاء)؟ */
    fun isLocalNetwork(url: ParsedUrl): Boolean =
        url.isWeb && isLocalHost(url.host)

    /**
     * هل المضيف محلي؟ (pure — يُستخدم في الاختبارات والقرار)
     * يفحص: localhost و*.localhost، اللاحقات المحلية، IPv4 (كل الصيغ
     * التي يطبّعها Chromium)، IPv6 (loopback/link-local/ULA/mapped).
     */
    fun isLocalHost(hostRaw: String): Boolean {
        var host = hostRaw.trim().lowercase(Locale.ROOT)
        while (host.endsWith(".")) host = host.dropLast(1)
        if (host.isEmpty()) return false

        if (host == "localhost" || host.endsWith(".localhost")) return true
        for (suffix in LOCAL_HOST_SUFFIXES) {
            if (host.endsWith(suffix)) return true
        }
        if (host.contains(':')) return isLocalIpv6(host)
        val v4 = parseIpv4Flexible(host) ?: return false
        return isPrivateIpv4(v4)
    }

    // ————————————————— IPv4 —————————————————

    /**
     * محلل IPv4 متسامح الصيغ متطابق مع تطبيع Chromium:
     * 1–4 أجزاء، كل جزء عشري أو ثماني (بادئة 0) أو سداسي عشر (بادئة 0x)،
     * والجزء الأخير يمتص البايتات المتبقية (127.1 → 127.0.0.1،
     * 3232235521 → 192.168.0.225). يُرجع null إن لم يكن IPv4 صالحًا
     * (فيبقى الاسم خاضعًا لمسار القائمة البيضاء — fail-closed).
     */
    fun parseIpv4Flexible(host: String): Long? {
        if (host.isEmpty()) return null
        // أحرف مسموحة في صيغ IP فقط — أي حرف آخر يعني اسم نطاق فورًا
        for (c in host) {
            if (c !in '0'..'9' && c !in 'a'..'f' && c != '.' && c != 'x') return null
        }
        val parts = host.split('.')
        if (parts.size > 4) return null

        val values = LongArray(parts.size)
        for ((index, part) in parts.withIndex()) {
            val value = parseIpPart(part) ?: return null
            values[index] = value
        }

        // الجزء الأخير يمتص البايتات المتبقية: n أجزاء → آخر جزء يسع (5-n) بايتات
        val lastCapacity = 1L shl (8 * (5 - parts.size))
        var value = values[parts.size - 1]
        if (value >= lastCapacity) return null
        for (i in 0 until parts.size - 1) {
            if (values[i] > 255) return null
            value += values[i] shl (8 * (3 - i))
        }
        return value
    }

    /** جزء واحد: عشري، أو ثماني ببادئة 0، أو سداسي عشر ببادئة 0x. */
    private fun parseIpPart(part: String): Long? {
        if (part.isEmpty()) return null
        if (part.startsWith("0x")) {
            if (part.length == 2) return null
            return part.substring(2).toLongOrNull(16)
        }
        if (part.length > 1 && part[0] == '0') {
            // ثماني — يجب أن يكون كل محارفه 0-7 وإلا فهو غير صالح (سلوك Chromium)
            if (!part.all { it in '0'..'7' }) return null
            return part.toLongOrNull(8)
        }
        return part.toLongOrNull()
    }

    /** هل القيمة (uint32) داخل أحد نطاقات الشبكة الخاصة؟ */
    fun isPrivateIpv4(value: Long): Boolean {
        if (value < 0 || value > 0xFFFFFFFFL) return false
        return PRIVATE_IPV4_RANGES.any { value >= it.first && value <= it.second }
    }

    private fun rangeOf(from: String, to: String): Pair<Long, Long> =
        dottedToLong(from) to dottedToLong(to)

    private fun dottedToLong(dotted: String): Long {
        val parts = dotted.split('.').map { it.toLong() }
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    // ————————————————— IPv6 —————————————————

    /**
     * IPv6 المطبوع بعد إزالة الأقواس (UrlNormalizer يُبقي النقطتين):
     * يوسَّع إلى 8 مجموعات ثم يُفحص النطاق.
     */
    private fun isLocalIpv6(host: String): Boolean {
        // IPv4-mapped (::ffff:a.b.c.d) — يفحص العنوان المدمج بنطاقات IPv4
        if (host.startsWith("::ffff:")) {
            val rest = host.substring("::ffff:".length)
            if (rest.contains('.')) {
                val v4 = parseIpv4Flexible(rest) ?: return false
                return isPrivateIpv4(v4)
            }
            // صيغة المجموعات السداسية (::ffff:c0a8:1 = ::ffff:192.168.0.1)
            val parts = rest.split(':')
            if (parts.size == 2 &&
                parts[0].isNotEmpty() && parts[0].length <= 4 &&
                parts[1].isNotEmpty() && parts[1].length <= 4
            ) {
                val hi = parts[0].toIntOrNull(16) ?: return false
                val lo = parts[1].toIntOrNull(16) ?: return false
                return isPrivateIpv4(((hi shl 16) or lo).toLong() and 0xFFFFFFFFL)
            }
            return false // أي شكل آخر — fail-closed
        }
        if (host == "::") return true // unspecified — غير قابل للتوجيه إطلاقًا

        val groups = expandIpv6(host) ?: return false
        return when {
            groups.all { it == 0 } -> true                                    // ::
            groups[0] == 0 && groups[1] == 0 && groups[2] == 0 &&
                groups[3] == 0 && groups[4] == 0 && groups[5] == 0 &&
                groups[6] == 0 && groups[7] == 1 -> true                      // ::1
            groups[0] == 0 && groups[1] == 0 && groups[2] == 0 &&
                groups[3] == 0 && groups[4] == 0 && groups[5] == 0xffff -> {
                // ::ffff:aabb:ccdd بصيغة مجموعات — دمج البايتات الأربعة الأخيرة
                // (g6 عاليان ثم واطئان، g7 عاليان ثم واطئان)
                val v4 = ((((groups[6] ushr 8) shl 24) or
                    ((groups[6] and 0xFF) shl 16) or
                    ((groups[7] ushr 8) shl 8) or
                    (groups[7] and 0xFF)).toLong() and 0xFFFFFFFFL)
                isPrivateIpv4(v4)
            }
            groups[0] in 0xFE80..0xFEBF -> true                               // fe80::/10 link-local
            groups[0] in 0xFC00..0xFDFF -> true                               // fc00::/7 ULA (fc/fd)
            else -> false
        }
    }

    /**
     * توسيع IPv6 المضغوط إلى 8 مجموعات — يرجع null عند أي شكل غير صالح
     * (fail-closed: الشكل الغامض ليس محليًا).
     */
    private fun expandIpv6(host: String): IntArray? {
        val doubleColon = host.count { it == ':' } >= 2 && host.contains("::")
        val head: String
        val tail: String
        if (doubleColon) {
            if (host.count { it == ':' } > 1 && host.countOf("::") > 1) return null // "::" مرة واحدة فقط
            val split = host.split("::")
            if (split.size > 2) return null
            head = split[0]
            tail = split.getOrElse(1) { "" }
        } else {
            head = host
            tail = ""
        }
        val headGroups = if (head.isEmpty()) emptyList() else head.split(':')
        val tailGroups = if (tail.isEmpty()) emptyList() else tail.split(':')
        val missing = 8 - (headGroups.size + tailGroups.size)
        if (missing < 0 || (!doubleColon && missing != 0)) return null
        val all = headGroups + List(missing) { "0" } + tailGroups
        if (all.size != 8) return null
        val out = IntArray(8)
        for ((index, group) in all.withIndex()) {
            if (group.isEmpty() || group.length > 4) return null
            val value = group.toIntOrNull(16) ?: return null
            out[index] = value
        }
        return out
    }

    private fun String.countOf(needle: String): Int =
        split(needle).size - 1
}
