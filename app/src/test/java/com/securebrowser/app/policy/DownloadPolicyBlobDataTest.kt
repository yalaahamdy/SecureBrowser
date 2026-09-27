package com.securebrowser.app.policy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * اختبارات سياسة التنزيلات v1.2.0 — الإصلاح 2:
 * "لا أريد حظر أي تنزيلات، فقط تسجيلها".
 *
 * الضوابط:
 * - الافتراضي الجديد: كل الفئات ALLOW (مع بقاء التسجيل في جدول downloads).
 * - blob:/data: من صفحة مسموحة = ملف يولّده المتصفح لطلب الصفحة نفسها → مسموح.
 * - blob:/data: من صفحة غير مسموحة → حظر بوابة المصدر (كما كان).
 * - بقاء خيار التشديد (approval/block) يعمل لمن يفعّله من الوالدين.
 */
class DownloadPolicyBlobDataTest {

    private val defaultPolicy = DownloadPolicy() // الافتراضي v1.2.0: السماح مع التسجيل

    // ————— الافتراضي الجديد: لا حظر —————

    @Test
    fun `apk allowed by default and still recorded downstream`() {
        assertEquals(
            DownloadDecision.ALLOW,
            defaultPolicy.decide(true, "https", "app.apk", null)
        )
    }

    @Test
    fun `archive allowed by default`() {
        assertEquals(
            DownloadDecision.ALLOW,
            defaultPolicy.decide(true, "https", "file.zip", "application/zip")
        )
    }

    @Test
    fun `unknown type allowed by default`() {
        assertEquals(
            DownloadDecision.ALLOW,
            defaultPolicy.decide(true, "https", "data.blob", null)
        )
    }

    @Test
    fun `media documents allowed as before`() {
        assertEquals(
            DownloadDecision.ALLOW,
            defaultPolicy.decide(true, "https", "lesson.pdf", "application/pdf")
        )
    }

    // ————— blob:/data: من صفحة مسموحة —————

    @Test
    fun `blob download from allowed source is allowed`() {
        assertEquals(
            DownloadDecision.ALLOW,
            defaultPolicy.decide(true, "blob", "certificate.pdf", "application/pdf")
        )
    }

    @Test
    fun `data download from allowed source is allowed`() {
        assertEquals(
            DownloadDecision.ALLOW,
            defaultPolicy.decide(true, "data", "report.csv", "text/csv")
        )
    }

    @Test
    fun `blob dangerous executable follows parental policy`() {
        assertEquals(
            DownloadDecision.ALLOW,
            defaultPolicy.decide(true, "blob", "tool.apk", null)
        )
        val strict = DownloadPolicy(
            executablePolicy = DownloadPolicy.Policy.BLOCK,
            archivePolicy = DownloadPolicy.Policy.ALLOW,
            unknownPolicy = DownloadPolicy.Policy.ALLOW
        )
        assertEquals(
            DownloadDecision.BLOCKED_DANGEROUS,
            strict.decide(true, "blob", "tool.apk", null)
        )
    }

    // ————— بوابة المصدر تبقى صارمة —————

    @Test
    fun `blob from non allowed source is still blocked by source gate`() {
        assertEquals(
            DownloadDecision.BLOCK_SOURCE_NOT_ALLOWED,
            defaultPolicy.decide(false, "blob", "certificate.pdf", "application/pdf")
        )
    }

    @Test
    fun `http download from non allowed source is still blocked by source gate`() {
        assertEquals(
            DownloadDecision.BLOCK_SOURCE_NOT_ALLOWED,
            defaultPolicy.decide(false, "https", "app.apk", null)
        )
    }

    @Test
    fun `unknown scheme still blocked`() {
        assertEquals(
            DownloadDecision.BLOCK_SCHEME,
            defaultPolicy.decide(true, "ftp", "file.zip", null)
        )
    }

    // ————— التشديد الاختياري يبقى متاحًا للوالد —————

    @Test
    fun `approval policy still queues when parent chooses it`() {
        val approval = DownloadPolicy(
            executablePolicy = DownloadPolicy.Policy.ALLOW,
            archivePolicy = DownloadPolicy.Policy.APPROVAL,
            unknownPolicy = DownloadPolicy.Policy.ALLOW
        )
        assertEquals(
            DownloadDecision.REQUIRES_APPROVAL,
            approval.decide(true, "https", "pack.rar", "application/vnd.rar")
        )
    }
}
