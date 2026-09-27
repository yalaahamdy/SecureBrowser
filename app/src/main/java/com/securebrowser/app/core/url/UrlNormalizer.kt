package com.securebrowser.app.core.url

import java.io.ByteArrayOutputStream
import java.net.IDN
import java.util.Locale

/**
 * محلل ومطوّع URL صارم (fail-closed) — قلب طبقة URL Validation.
 *
 * يدعم معالجة حالات التحايل:
 * - ترميز مزدوج/متعدد (%252F → %2F → /) عبر فك الترميز التكراري مع سقف دورات.
 * - محارف تحكم مخفية داخل العنوان (\\n \\r \\t) — تُزال كما تفعل المتصفحات.
 * - userinfo مخادع (user@host) — يُكشف ويُرفض في الطبقة الأمنية.
 * - حالة الأحرف المختلطة، النقطة النهائية، المنافذ غير القياسية، IPv4/IPv6، punycode/IDN.
 * - host يحوي محارف ممنوعة بعد فك الترميز (/ \\ ? # @ % : والمسافات) → غير صالح.
 *
 * هذا الصنف pure-Kotlin بلا أي اعتماد على Android — اختباره آلي على JVM.
 */
object UrlNormalizer {

    /** المخططات الهرمية التي نحللها host/path بالكامل. (file: يُعامل مبكرًا كغير آمن) */
    private val HIERARCHICAL_SCHEMES = setOf("http", "https", "ws", "wss", "ftp")

    private val SCHEME_REGEX =
        Regex("^([A-Za-z][A-Za-z0-9+.-]*):(.*)$", setOf(RegexOption.DOT_MATCHES_ALL))

    private val IPV4_REGEX = Regex("""^(\d{1,3})(\.\d{1,3}){3}$""")

    private val CONTROL_AND_WS = Regex("[\\t\\r\\n]")

    /**
     * يحلل ويطبع العنوان. العناوين بلا مخطط تُعامل كـ https (سلوك شريط العنوان).
     */
    fun normalize(input: String?, defaultScheme: String = "https"): NormalizeResult {
        if (input.isNullOrBlank()) return NormalizeResult.Invalid("empty_input")

        // 1) إزالة المحارف الضابطة المخفية + قص المسافات الطرفية (سلوك المتصفحات)
        var s = CONTROL_AND_WS.replace(input, "")
        s = s.trim { it <= ' ' }
        if (s.isEmpty()) return NormalizeResult.Invalid("empty_after_trim")

        // 2) استخراج المخطط
        val match = SCHEME_REGEX.find(s)
        val scheme: String
        val rest: String
        if (match != null) {
            scheme = match.groupValues[1].lowercase(Locale.ROOT)
            rest = match.groupValues[2]
        } else {
            scheme = defaultScheme.lowercase(Locale.ROOT)
            rest = s
        }

        // 3) مخططات غير هرمية: opacity — لا نحتاج host للتحقق من سياسة المخطط
        if (scheme !in HIERARCHICAL_SCHEMES) {
            return NormalizeResult.Success(
                ParsedUrl(
                    scheme = scheme,
                    host = "",
                    port = null,
                    effectivePort = -1,
                    path = "",
                    query = null,
                    fragment = null,
                    userInfo = null,
                    opaquePart = rest.take(512),
                    raw = s,
                    isIpLiteral = false
                )
            )
        }

        // 4) فصل fragment ثم query
        var working = rest
        var fragment: String? = null
        val hashIndex = working.indexOf('#')
        if (hashIndex >= 0) {
            fragment = working.substring(hashIndex + 1)
            working = working.substring(0, hashIndex)
        }
        var query: String? = null
        val queryIndex = working.indexOf('?')
        if (queryIndex >= 0) {
            query = working.substring(queryIndex + 1)
            working = working.substring(0, queryIndex)
        }

        // 5) تحديد authority و path
        var authority: String
        var path: String
        if (working.startsWith("//")) {
            authority = working.substring(2)
            val slashIndex = authority.indexOf('/')
            if (slashIndex >= 0) {
                path = authority.substring(slashIndex)
                authority = authority.substring(0, slashIndex)
            } else {
                path = "/"
            }
        } else if (working.isNotEmpty() && !working.startsWith("/")) {
            // "http:example.com/x" يُعامل مثل "http://example.com/x" (توحيد المتصفحات)
            val slashIndex = working.indexOf('/')
            if (slashIndex >= 0) {
                path = working.substring(slashIndex)
                authority = working.substring(0, slashIndex)
            } else {
                path = "/"
                authority = working
            }
        } else {
            return NormalizeResult.Invalid("missing_host")
        }

        if (authority.isEmpty()) return NormalizeResult.Invalid("empty_authority")

        // 6) كشف userinfo المدمج user@host (حيلة تصيّد كلاسيكية)
        var userInfo: String? = null
        var hostAndPort = authority
        val atIndex = authority.lastIndexOf('@')
        if (atIndex >= 0) {
            userInfo = authority.substring(0, atIndex)
            hostAndPort = authority.substring(atIndex + 1)
        }

        // 7) فصل المنفذ (مع مراعاة أقواس IPv6)
        var port: Int? = null
        var hostRaw: String
        val bracketEnd = hostAndPort.lastIndexOf(']')
        val colonIndex = hostAndPort.lastIndexOf(':')
        if (colonIndex > bracketEnd) {
            hostRaw = hostAndPort.substring(0, colonIndex)
            val portText = hostAndPort.substring(colonIndex + 1)
            if (portText.isNotEmpty()) {
                val parsedPort = portText.toIntOrNull()
                    ?: return NormalizeResult.Invalid("bad_port")
                if (parsedPort !in 1..65535) return NormalizeResult.Invalid("port_out_of_range")
                port = parsedPort
            }
        } else {
            hostRaw = hostAndPort
        }

        if (hostRaw.isEmpty()) return NormalizeResult.Invalid("empty_host")

        // 8) تطبيع host صارم
        var isIpLiteral = false
        val host: String = if (hostRaw.startsWith("[")) {
            // IPv6 حرفي
            if (!hostRaw.endsWith("]")) return NormalizeResult.Invalid("bad_ipv6")
            val inner = hostRaw.substring(1, hostRaw.length - 1).lowercase(Locale.ROOT)
            if (inner.isEmpty() || inner.any { it in " /\\?#@%" }) {
                return NormalizeResult.Invalid("bad_ipv6")
            }
            isIpLiteral = true
            inner
        } else {
            if (hostRaw.contains('[') || hostRaw.contains(']')) {
                return NormalizeResult.Invalid("bad_host_chars")
            }
            val decoded = percentDecodeRepeatedly(hostRaw)
                ?: return NormalizeResult.Invalid("bad_host_encoding")
            if (decoded.contains('%')) return NormalizeResult.Invalid("percent_in_host")
            var h = decoded.lowercase(Locale.ROOT)
            while (h.endsWith(".")) h = h.dropLast(1) // example.com. == example.com
            if (h.isEmpty()) return NormalizeResult.Invalid("empty_host")
            if (h.any { it in " /\\?#@:" }) return NormalizeResult.Invalid("forbidden_char_in_host")
            val ascii = try {
                // punycode للنطاقات الدولية (مثال.موقع → xn--...) بقواعد STD3
                IDN.toASCII(h, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            } catch (e: Exception) {
                return NormalizeResult.Invalid("bad_idn")
            }
            if (ascii.isEmpty()) return NormalizeResult.Invalid("empty_host")
            isIpLiteral = isIpAddressLiteral(ascii) || ascii.contains(':')
            ascii
        }

        // 9) تطبيع path: فك الترميز التكراري للمطابقة (لا يلحق "مثال.موقع" بنطاق آخر)
        val decodedPath = if (path.isBlank()) {
            "/"
        } else {
            percentDecodeRepeatedly(path) ?: return NormalizeResult.Invalid("bad_path_encoding")
        }
        val finalPath = if (decodedPath.startsWith("/")) decodedPath else "/$decodedPath"

        return NormalizeResult.Success(
            ParsedUrl(
                scheme = scheme,
                host = host,
                port = port,
                effectivePort = port ?: ParsedUrl.defaultPortFor(scheme),
                path = finalPath,
                query = query,
                fragment = fragment,
                userInfo = userInfo,
                opaquePart = null,
                raw = s,
                isIpLiteral = isIpLiteral
            )
        )
    }

    /**
     * فك ترميز percent تكراري (حتى 3 دورات) لكشف الترميزات المتداخلة.
     * إخفاق أي تسلسل hex غير صالح → null (يعني حظر).
     */
    fun percentDecodeRepeatedly(input: String, maxRounds: Int = 3): String? {
        var current = input
        repeat(maxRounds) {
            val next = decodeOnce(current) ?: return null
            if (next == current) return next
            current = next
        }
        return current
    }

    private fun decodeOnce(s: String): String? {
        if (!s.contains('%')) return s
        val bytes = s.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b == '%'.code) {
                if (i + 2 >= bytes.size) return null
                val hi = hexValue(bytes[i + 1].toInt().toChar()) ?: return null
                val lo = hexValue(bytes[i + 2].toInt().toChar()) ?: return null
                out.write(hi * 16 + lo)
                i += 3
            } else {
                out.write(b)
                i++
            }
        }
        return try {
            out.toString("UTF-8")
        } catch (e: Exception) {
            null
        }
    }

    private fun hexValue(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    /** هل المضيف عنوان IP حرفي (IPv4)؟ يُستخدم لمنع مطابقة قواعد النطاقات للعناوين الرقمية. */
    fun isIpAddressLiteral(host: String): Boolean = IPV4_REGEX.matches(host)
}
