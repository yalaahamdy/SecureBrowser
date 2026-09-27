package com.securebrowser.app.policy

/**
 * بناء نص URI للمخططات الخارجية (v1.3.0) — pure-Kotlin للاختبار الآلي.
 *
 * **قاعدة التوحيد:** تلجرام يسجّل intent-filter بنمط `scheme="tg" host="resolve"`
 * (فلاتر مقيدة بالـ host). الرابط المعتم `tg:resolve?domain=x` (بلا `//`)
 * **لا يملك host** إطلاقًا فلا يطابق الفلاتر فيبقى "لا يوجد تطبيق" والصفحة
 * تعلق "تُحمّل". التوحيد الدائم إلى الصيغة المرجعية `tg://...` يضمن المطابقة
 * مع كل عملاء تلجرام (الرسمي/X/لعي).
 *
 * المخططات الأخرى تبقى بصيغتها الأصلية:
 * - whatsapp/youtube: صيغ هرمية عادة — تُمرر كما هي عند بدئها بـ //.
 * - vnd.youtube: تعتمد الصيغة المعتمة (vnd.youtube:VIDEO_ID) — لا توحيد لها.
 * - مشغلات الوسائط (vlc/rtsp...): تُبنى في ExternalNavigationHandler مباشرة.
 */
object ExternalUriBuilder {

    /** المخططات التي تُوحَّد إلى الصيغة المرجعية scheme://authority عند غياب //. */
    private val AUTHORITY_NORMALIZED_SCHEMES = setOf("tg")

    fun buildUriText(scheme: String, target: String): String = when {
        scheme in AUTHORITY_NORMALIZED_SCHEMES && !target.startsWith("//") -> "$scheme://$target"
        else -> "$scheme:$target"
    }
}
