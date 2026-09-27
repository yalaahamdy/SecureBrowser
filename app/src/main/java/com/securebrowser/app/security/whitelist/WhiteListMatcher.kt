package com.securebrowser.app.security.whitelist

import com.securebrowser.app.core.url.ParsedUrl

/**
 * مطابقة قواعد القائمة البيضاء — منطق خالص قابل للاختبار.
 *
 * ضمانات أمنية جوهرية:
 * - "example.com.evil.com" لا يطابق قاعدة example.com (اللاحقة يجب أن تبدأ بنقطة).
 * - "evil.com/example.com" لا يطابق قاعدة example.com (المطابقة على host الحقيقي، لا النص).
 * - prefix على حدود المقاطع: /news تطابق /news و /news/… ولا تطابق /newsportal.
 * - منفذ غير قياسي في العنوان لا يمر إلا بقاعدة تحدد نفس المنفذ صراحة.
 * - عنوان IP لا يطابق قواعد النطاقات (المطابقة نصية دقيقة على host المطبوع).
 */
object WhiteListMatcher {

    fun matches(rule: WhiteListRule, url: ParsedUrl): Boolean {
        if (!rule.enabled) return false
        if (!schemeMatches(rule, url)) return false
        if (!hostMatches(rule, url)) return false
        if (!portMatches(rule, url)) return false
        return pathMatches(rule, url)
    }

    private fun schemeMatches(rule: WhiteListRule, url: ParsedUrl): Boolean {
        val ruleScheme = rule.scheme?.lowercase()?.trim()
        // null = أي http/https (المضيف هو بوابة الحماية)
        if (ruleScheme == null) return url.isWeb
        return url.scheme == ruleScheme
    }

    private fun hostMatches(rule: WhiteListRule, url: ParsedUrl): Boolean {
        val ruleHost = rule.host.lowercase().trim().trimEnd('.')
        if (ruleHost.isEmpty()) return false
        val urlHost = url.host
        if (urlHost.isEmpty()) return false
        if (url.isIpLiteral != isIpLiteralRule(ruleHost)) return false
        return when (rule.subdomainPolicy) {
            SubdomainPolicy.EXACT_HOST_ONLY -> urlHost == ruleHost
            SubdomainPolicy.INCLUDE_SUBDOMAINS ->
                urlHost == ruleHost || urlHost.endsWith(".$ruleHost")
        }
    }

    private fun isIpLiteralRule(ruleHost: String): Boolean =
        ruleHost.contains(':') || Regex("""^(\d{1,3})(\.\d{1,3}){3}$""").matches(ruleHost)

    private fun portMatches(rule: WhiteListRule, url: ParsedUrl): Boolean {
        // قاعدة بلا منفذ = المنافذ القياسية فقط؛ منفذ غير قياسي يتطلب قاعدة صريحة
        val rulePort = rule.port ?: ParsedUrl.defaultPortFor(url.scheme)
        return url.effectivePort == rulePort
    }

    private fun pathMatches(rule: WhiteListRule, url: ParsedUrl): Boolean = when (rule.type) {
        RuleType.ALLOW_DOMAIN -> true
        RuleType.ALLOW_PATH_PREFIX -> prefixMatches(rule.path, url.path)
        RuleType.ALLOW_EXACT -> exactMatches(rule.path, url.path)
    }

    private fun prefixMatches(rulePath: String?, urlPath: String): Boolean {
        val rp = normalizeRulePath(rulePath) ?: return true
        if (rp == "/") return true
        val up = stripTrailingSlash(urlPath)
        return up == rp || up.startsWith("$rp/")
    }

    private fun exactMatches(rulePath: String?, urlPath: String): Boolean {
        val rp = normalizeRulePath(rulePath) ?: return urlPath == "/" || urlPath.isBlank()
        return stripTrailingSlash(urlPath) == rp
    }

    /** يوحّد مسار القاعدة: يبدأ بـ "/" وبدون "/" نهاية (الجذر "/" يبقى). */
    private fun normalizeRulePath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        var p = if (path.startsWith("/")) path else "/$path"
        while (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        return p
    }

    private fun stripTrailingSlash(path: String): String =
        if (path.length > 1 && path.endsWith("/")) path.dropLast(1) else path
}
