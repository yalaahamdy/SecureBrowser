package com.securebrowser.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** مصفوفة سياسة التنزيلات — مواصفة المرحلة 2 §12-§13. */
class DownloadPolicyTest {

    private fun policy(
        executable: DownloadPolicy.Policy = DownloadPolicy.Policy.BLOCK,
        archive: DownloadPolicy.Policy = DownloadPolicy.Policy.APPROVAL,
        unknown: DownloadPolicy.Policy = DownloadPolicy.Policy.APPROVAL
    ) = DownloadPolicy(executable, archive, unknown)

    // ————— الأنواع الخطرة: محظورة افتراضيًا —————

    @Test
    fun `apk blocked by default`() {
        assertEquals(
            DownloadDecision.BLOCKED_DANGEROUS,
            policy().decide(true, "https", "app.apk", "application/vnd.android.package-archive")
        )
    }

    @Test
    fun `exe msi bat blocked by default`() {
        for (name in listOf("setup.exe", "installer.msi", "run.bat", "game.scr", "macro.vbs", "lib.jar")) {
            assertEquals(
                "file=$name",
                DownloadDecision.BLOCKED_DANGEROUS,
                policy().decide(true, "https", name, null)
            )
        }
    }

    @Test
    fun `mime wins when extension missing - apk mime blocked`() {
        assertEquals(
            DownloadDecision.BLOCKED_DANGEROUS,
            policy().decide(true, "https", "download", "application/vnd.android.package-archive")
        )
    }

    @Test
    fun `executable can be set to approval by parent`() {
        assertEquals(
            DownloadDecision.REQUIRES_APPROVAL,
            policy(executable = DownloadPolicy.Policy.APPROVAL)
                .decide(true, "https", "app.apk", null)
        )
    }

    // ————— الأرشيفات: موافقة افتراضيًا —————

    @Test
    fun `archives require approval by default`() {
        for (name in listOf("docs.zip", "backup.rar", "pack.7z", "bundle.tar.gz")) {
            assertEquals(
                "file=$name",
                DownloadDecision.REQUIRES_APPROVAL,
                policy().decide(true, "https", name, null)
            )
        }
    }

    // ————— الوسائط والمستندات: مسموحة —————

    @Test
    fun `pdf images video audio documents allowed`() {
        val p = policy()
        val allowed = listOf(
            "doc.pdf" to "application/pdf",
            "photo.jpg" to "image/jpeg",
            "movie.mp4" to "video/mp4",
            "song.mp3" to "audio/mpeg",
            "data.csv" to "text/csv",
            "notes.txt" to "text/plain",
            "page.webp" to null
        )
        for ((name, mime) in allowed) {
            assertEquals(
                "file=$name",
                DownloadDecision.ALLOW,
                p.decide(true, "https", name, mime)
            )
        }
    }

    @Test
    fun `unknown files require approval by default`() {
        assertEquals(
            DownloadDecision.REQUIRES_APPROVAL,
            policy().decide(true, "https", "mystery.xyz_abc", null)
        )
    }

    // ————— المصدر والمخطط —————

    @Test
    fun `download from blocked source is always rejected`() {
        assertEquals(
            DownloadDecision.BLOCK_SOURCE_NOT_ALLOWED,
            policy().decide(false, "https", "video.mp4", "video/mp4")
        )
    }

    @Test
    fun `non web scheme rejected`() {
        // v1.2.0: blob:/data: من صفحة مسموحة = ملف يولّده المتصفح لطلب الصفحة
        // المسموحة نفسها — بوابة المصدر هي الحكم (كما هو حال blob: في SecurityEngine)
        // فيُصنَّف الملف ويُقرَّر حسب فئته، ولا يُحظر بسبب المخطط.
        assertEquals(
            DownloadDecision.ALLOW,
            policy(
                DownloadPolicy.Policy.ALLOW,
                DownloadPolicy.Policy.ALLOW,
                DownloadPolicy.Policy.ALLOW
            ).decide(true, "blob", "video.mp4", "video/mp4")
        )
        assertEquals(
            DownloadDecision.ALLOW,
            policy(
                DownloadPolicy.Policy.ALLOW,
                DownloadPolicy.Policy.ALLOW,
                DownloadPolicy.Policy.ALLOW
            ).decide(true, "data", "x.png", "image/png")
        )
        // مخططات غير ويب أخرى تبقى محظورة
        assertEquals(
            DownloadDecision.BLOCK_SCHEME,
            policy().decide(true, "ftp", "video.mp4", "video/mp4")
        )
        // والمصدر غير المسموح يبقى حاجزًا الأول حتى مع blob:
        assertEquals(
            DownloadDecision.BLOCK_SOURCE_NOT_ALLOWED,
            policy().decide(false, "blob", "video.mp4", "video/mp4")
        )
    }

    @Test
    fun `dangerous extension cannot sneak through faked safe mime`() {
        // الامتداد هو الحاسم الأمني — MIME مزور لا ينقذ apk
        assertEquals(
            DownloadDecision.BLOCKED_DANGEROUS,
            policy().decide(true, "https", "app.apk", "video/mp4")
        )
    }

    // ————— التصنيف مباشرة —————

    @Test
    fun `classification matrix`() {
        assertEquals(FileCategory.DANGEROUS_EXECUTABLE, DownloadPolicy.classify("x.apk", null))
        assertEquals(FileCategory.ARCHIVE, DownloadPolicy.classify("x.zip", null))
        assertEquals(FileCategory.VIDEO, DownloadPolicy.classify(null, "video/webm"))
        assertEquals(FileCategory.AUDIO, DownloadPolicy.classify(null, "audio/ogg"))
        assertEquals(FileCategory.IMAGE, DownloadPolicy.classify(null, "image/png"))
        assertEquals(FileCategory.DOCUMENT, DownloadPolicy.classify(null, "application/pdf"))
        assertEquals(FileCategory.UNKNOWN, DownloadPolicy.classify(null, null))
        assertEquals(FileCategory.UNKNOWN, DownloadPolicy.classify("file", "application/octet-stream"))
    }

    @Test
    fun `no decision weakens source policy`() {
        // حتى مع سياسة سماح كاملة، المصدر المحظور يظل محظورًا
        val permissive = policy(
            executable = DownloadPolicy.Policy.ALLOW,
            archive = DownloadPolicy.Policy.ALLOW,
            unknown = DownloadPolicy.Policy.ALLOW
        )
        assertEquals(
            DownloadDecision.BLOCK_SOURCE_NOT_ALLOWED,
            permissive.decide(false, "https", "app.apk", null)
        )
    }
}
