package com.securebrowser.app.ui.qr

import com.securebrowser.app.core.url.AddressBarClassifier

/**
 * سياسة معالجة نتيجة مسح QR (v1.9.0) — منطق خالص قابل للاختبار.
 *
 * طلب المستخدم: قراءة أكواد QR **لروابط المواقع ونصوص البحث** افتراضيًا
 * واحترافيًا — أي:
 * - الرمز يحمل رابطًا → يُفتح عبر نفس مسار شريط العنوان (SecurityEngine
 *   يقرر السماح/الحظر لاحقًا — المسح لا يتجاوز أي حماية).
 * - الرمز يحمل نصًا → يذهب إلى محرك البحث المُعدّ (نفس مسار الكتابة).
 *
 * التنظيف قبل التصنيف (أكواد QR حقيقية تحمل أحيانًا):
 * - محارف صفرية العرض وأوراق اتجاه (Bidi/FEFF…) تُزال.
 * - مسافات طرفية وأسطر جديدة طرفية تُقص.
 * - الروابط المكسورة على أسطر (QRات مضغوطة) — لا نضم الأسطر إلا للروابط:
 *   التصنيف يظل حرفيًا، والنص متعدد الأسطر يذهب بحثًا كما هو.
 *
 * pure-Kotlin — قابل للاختبار آليًا على JVM.
 */
object QrResultPolicy {

    /** محارف صفرية العرض/توجيه النص الشائعة في QRات مولدة. */
    private val INVISIBLE_CHARS = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\u2066-\\u2069\\uFEFF]")

    /** الإجراء المطلوب بعد المسح. */
    sealed class QrAction {
        /** الرمز يحمل عنوانًا — يُمرر لمسار التنقل العادي (SecurityEngine يقرر). */
        data class OpenUrl(val url: String) : QrAction()

        /** الرمز يحمل نصًا — يذهب إلى محرك البحث. */
        data class Search(val query: String) : QrAction()
    }

    /**
     * يصنف نص الرمز إلى إجراء، أو null إن كان فارغًا بعد التنظيف.
     * التصنيف نفسه من [AddressBarClassifier] — نفس قواعد شريط العنوان بالضبط،
     * فلا فرق بين "مسح" و"كتابة" من منظور طبقة الأمان.
     */
    fun handle(raw: String): QrAction? {
        val cleaned = clean(raw)
        if (cleaned.isEmpty()) return null
        return if (AddressBarClassifier.isLikelyUrl(cleaned)) {
            QrAction.OpenUrl(cleaned)
        } else {
            QrAction.Search(cleaned)
        }
    }

    /** تنظيف موحد للنص قبل العرض والتصنيف. */
    fun clean(raw: String): String =
        INVISIBLE_CHARS.replace(raw, "").trim { it <= ' ' || it == '\n' || it == '\r' }
}
