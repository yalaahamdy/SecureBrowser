package com.securebrowser.app.policy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * مصفوفة توحيد صيغ مخططات التطبيقات الخارجية (v1.3.0):
 *
 * **السبب الجوهري:** تلجرام يسجّل فلاتره بنمط (scheme + host) مثل
 * `scheme="tg" host="resolve"`. الرابط المعتم `tg:resolve?domain=x` بلا
 * `//` لا يملك host فلا يطابق أي فلتر → "لا يوجد تطبيق" + صفحة تعلق
 * "تُحمّل للأبد". التوحيد إلى `tg://...` يحسم المطابقة.
 *
 * حدود موثقة:
 * - tg: يُوحَّد دائمًا إلى الصيغة المرجعية عند غياب `//`.
 * - tg: الصيغة المرجعية تُمرر كما هي (لا يُضاف // ثاني).
 * - vnd.youtube: الصيغة المعتمة (vnd.youtube:ID) تبقى كما هي — لا توحيد.
 * - whatsapp: صيغته الشائعة `whatsapp://send?...` تُمرر كما هي.
 */
class ExternalUriBuilderTest {

    @Test
    fun `tg opaque form is normalized to authority form`() {
        assertEquals(
            "tg://resolve?domain=example",
            ExternalUriBuilder.buildUriText("tg", "resolve?domain=example")
        )
    }

    @Test
    fun `tg hierarchical form stays intact`() {
        assertEquals(
            "tg://resolve?domain=example",
            ExternalUriBuilder.buildUriText("tg", "//resolve?domain=example")
        )
    }

    @Test
    fun `tg join invite normalizes`() {
        assertEquals(
            "tg://join?invite=abc123",
            ExternalUriBuilder.buildUriText("tg", "join?invite=abc123")
        )
    }

    @Test
    fun `vnd youtube opaque video id stays untouched`() {
        assertEquals(
            "vnd.youtube:dQw4w9WgXcQ",
            ExternalUriBuilder.buildUriText("vnd.youtube", "dQw4w9WgXcQ")
        )
    }

    @Test
    fun `whatsapp hierarchical form stays intact`() {
        assertEquals(
            "whatsapp://send?text=hi",
            ExternalUriBuilder.buildUriText("whatsapp", "//send?text=hi")
        )
    }

    @Test
    fun `youtube scheme passes through`() {
        assertEquals(
            "youtube:watch?v=x",
            ExternalUriBuilder.buildUriText("youtube", "watch?v=x")
        )
    }
}
