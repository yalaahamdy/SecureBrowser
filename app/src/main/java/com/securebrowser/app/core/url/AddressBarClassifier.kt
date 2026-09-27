package com.securebrowser.app.core.url

/**
 * مصنِّف شريط العنوان الذكي — يفصل بين "عنوان موقع" و"نص بحث".
 *
 * المشكلة التي يحلها: محلل URL الصارم يقبل أي كلمة مفردة كمضيف بلا TLD
 * (مثل "wikipedia" أو كلمة عربية) فيضيف المتصفح https:// لها فيتحول النص
 * إلى نطاق غير موجود. القاعدة هنا — مثل المتصفحات الحديثة:
 *
 * - مخطط صريح (https:// ، tel: ، intent: …) → عنوان (طبقة الأمان تحكم عليه).
 * - يحوي مسافة → بحث.
 * - localhost / عنوان IP / [IPv6] / مضيف:منفذ → عنوان.
 * - شكل نطاق plausible: علامتان فأكثر، كل علامة صالحة، والـ TLD أحرف (≥2)
 *   (يدعم IDN العربي: موقع.كوم) → عنوان.
 * - كل ما عدا ذلك → بحث.
 *
 * الاتجاه الآمن: المدخلات الملتبسة تذهب إلى البحث (وهو نفسه يخضع
 * للقائمة البيضاء) — لا يفتح هذا التصنيف أي مسار يتجاوز SecurityEngine.
 *
 * pure-Kotlin — قابل للاختبار آليًا على JVM.
 */
object AddressBarClassifier {

    private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*$")
    private val IPV4_REGEX = Regex("""^(\d{1,3})(\.\d{1,3}){3}$""")

    /** أحرف النطاق ASCII المسموحة (الشرطة داخل العلامة فقط تُفحص لاحقًا). */
    private val ASCII_LABEL = Regex("^[a-z0-9-]+$")

    /**
     * هل يُرجَّح أن المدخل عنوان ويب/مخططًا معروفًا وليس نص بحث؟
     * القرار استرشادي فقط — الحكم الأمني النهائي دائمًا في SecurityEngine.
     */
    fun isLikelyUrl(rawInput: String?): Boolean {
        if (rawInput == null) return false
        var s = rawInput.trim()
        if (s.isEmpty()) return false

        // 1) مخطط صريح → عنوان (javascript:/data:/file: تعترضها طبقة الأمان لاحقًا)
        if (SCHEME_REGEX.matches(s)) return true

        // 2) أي مسافة داخلية → بحث (متصفحات حقيقية تفعل المثل)
        if (s.any { it == ' ' || it == '\t' }) return false

        // 3) عزل المضيف من path/query/fragment ثم من المنفذ
        val hostPart = s.substringBefore('/').substringBefore('?').substringBefore('#')

        // IPv6 الحرفي يُفحص قبل منطق المنفذ (الأقواس تحمل ":" متعددة)
        // [::1] أو [::1]:8080 صالحان — ما بعد "]" إما فارغ أو ":منفذ"
        if (hostPart.startsWith("[")) {
            val close = hostPart.indexOf(']')
            if (close == -1) return false
            val after = hostPart.substring(close + 1)
            return after.isEmpty() || after.startsWith(":")
        }

        val hostNoPort = when (hostPart.count { it == ':' }) {
            0 -> hostPart
            1 -> hostPart.substringBefore(':').ifEmpty { return false }
            else -> return false // أكثر من ":" بلا أقواس → ليس شكل مضيف مفهومًا
        }
        if (hostNoPort.isEmpty()) return false

        // 4) حالات خاصة واضحة
        if (hostNoPort.equals("localhost", ignoreCase = true)) return true
        if (IPV4_REGEX.matches(hostNoPort)) return true

        // 5) شكل نطاق plausible
        return isPlausibleDomain(hostNoPort.lowercase())
    }

    /**
     * فحص شكل نطاق: علامتان فأكثر مفصولتين بنقاط، كل علامة non-empty
     * (أحرف/أرقام/شرطة، بلا شرطة طرفية)، والأخيرة (TLD) أحرف فقط وطولها ≥2.
     * الأحرف غير ASCII تُقبل كأحرف (IDN عربي…) — كل بقيّة تُرفض.
     */
    fun isPlausibleDomain(hostLower: String): Boolean {
        val host = hostLower.trimEnd('.').trim()
        if (host.isEmpty() || !host.contains('.')) return false
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() || it.length > 63 }) return false
        if (labels.any { it.startsWith('-') || it.endsWith('-') }) return false
        val tld = labels.last()
        if (tld.length < 2 || !tld.all { it.isLetter() }) return false
        return labels.all { label ->
            if (isAscii(label)) ASCII_LABEL.matches(label)
            else label.all { it.isLetterOrDigit() || it == '-' || it == '.' }
        }
    }

    private fun isAscii(s: String): Boolean = s.all { it.code < 0x80 }
}
