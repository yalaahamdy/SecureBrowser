package com.securebrowser.app.data.backup

import com.securebrowser.app.data.db.entity.AppSettingEntity
import com.securebrowser.app.data.db.entity.BlockedActivityEntity
import com.securebrowser.app.data.db.entity.DownloadRecordEntity
import com.securebrowser.app.data.db.entity.HistoryEntity
import com.securebrowser.app.data.db.entity.WhiteListRuleEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات مرمّز/فاكّ النسخة الاحتياطية (v1.9.0):
 *
 * الضوابط:
 * 1. ترميز/فك دورة كاملة (round-trip) بعدد صحيح للعناصر.
 * 2. أي عبث بالبصمة/المخطط/الإصدار → رفض صريح موصوف (fail-closed).
 * 3. العناصر التالفة داخل قسم سليم تُتخطى فرديًا دون إفشال النسخة.
 * 4. "policy_schema" لا يعبر النسخة إطلاقًا.
 * 5. قسم الرمز اختياري وفحص مدى التكرارات مطبق.
 */
class BackupCodecTest {

    private fun sampleRule(
        host: String = "example.com",
        ruleType: String = "ALLOW_DOMAIN",
        port: Int? = null
    ) = WhiteListRuleEntity(
        id = 7,
        scheme = null,
        host = host,
        path = null,
        port = port,
        ruleType = ruleType,
        subdomainPolicy = "INCLUDE_SUBDOMAINS",
        enabled = true,
        createdAt = 1_000L,
        updatedAt = 2_000L
    )

    private fun encodeAll(
        pin: com.securebrowser.app.security.storage.PinManager.PinSnapshot? = null
    ): JSONObject = BackupCodec.encode(
        whitelist = listOf(sampleRule(), sampleRule("khanacademy.org", "ALLOW_DOMAIN")),
        history = listOf(
            HistoryEntity(id = 1, url = "https://example.com/a", title = "A",
                host = "example.com", timestamp = 111L, visitResult = "ALLOWED")
        ),
        downloads = listOf(
            DownloadRecordEntity(id = 1, fileName = "video.mp4", url = "https://example.com/v",
                sourceUrl = null, mimeType = "video/mp4", state = "COMPLETED",
                sizeBytes = 1024L, downloadedBytes = 1024L, filePath = "video.mp4",
                timestamp = 222L, completedAt = 333L, updatedAt = 333L)
        ),
        blocked = listOf(
            BlockedActivityEntity(id = 1, url = "https://evil.com/", host = "evil.com",
                reason = "NOT_WHITELISTED", navigationType = "LINK", timestamp = 444L)
        ),
        settings = listOf(
            AppSettingEntity("search_engine", "duckduckgo"),
            AppSettingEntity("policy_schema", "2")
        ),
        pin = pin,
        appVersion = "1.9.0"
    )

    /** تعديل الأقسام ثم إعادة توقيعها بالبصمة — لمحاكاة نسخة سليمة بمحتوى شاذ. */
    private fun resign(root: JSONObject): JSONObject {
        val sections = root.getJSONObject("sections")
        root.put("checksum", BackupCodec.sha256Hex(sections.toString()))
        return root
    }

    // ————— دورة كاملة —————

    @Test
    fun `encode parse round trip preserves all sections`() {
        val json = encodeAll().toString()
        val parsed = BackupCodec.parse(json)

        assertEquals(2, parsed.whitelist.size)
        assertEquals("example.com", parsed.whitelist[0].host)
        assertEquals(1, parsed.history.size)
        assertEquals("https://example.com/a", parsed.history[0].url)
        assertEquals(1, parsed.downloads.size)
        assertEquals("video.mp4", parsed.downloads[0].fileName)
        assertEquals(1, parsed.blocked.size)
        assertEquals("NOT_WHITELISTED", parsed.blocked[0].reason)
        assertEquals(1, parsed.settings.size) // policy_schema مستبعد
        assertEquals("search_engine", parsed.settings[0].key)
        assertNull(parsed.pin)
    }

    @Test
    fun `ids are never carried across the backup`() {
        val parsed = BackupCodec.parse(encodeAll().toString())
        assertEquals(0L, parsed.whitelist[0].id)
        assertEquals(0L, parsed.history[0].id)
        assertEquals(0L, parsed.downloads[0].id)
        assertEquals(0L, parsed.blocked[0].id)
    }

    // ————— الرفض الهيكلي —————

    @Test
    fun `not a backup file is rejected`() {
        assertFails("not_backup") { BackupCodec.parse("just a random text") }
        assertFails("not_backup") { BackupCodec.parse("""{"a":1}""") }
        assertFails("not_backup") {
            BackupCodec.parse(
                """{"format":"other-app","formatVersion":1,"sections":{},"checksum":"x"}"""
            )
        }
    }

    @Test
    fun `future format version is rejected as unsupported`() {
        val root = encodeAll()
        root.put("formatVersion", 99)
        assertFails("unsupported_version") { BackupCodec.parse(root.toString()) }
    }

    @Test
    fun `tampered checksum is rejected as corrupted`() {
        val root = encodeAll()
        root.put("checksum", "deadbeef")
        assertFails("corrupted") { BackupCodec.parse(root.toString()) }

        // عبث بالمحتوى بعد الترميز — البصمة لا تطابق
        val clean = encodeAll()
        val sections = clean.getJSONObject("sections")
        sections.getJSONArray("history").getJSONObject(0).put("url", "https://tampered.com/")
        assertFails("corrupted") { BackupCodec.parse(clean.toString()) }
    }

    @Test
    fun `summarize rejects structural failures the same way`() {
        assertFails("not_backup") { BackupCodec.summarize("{}") }
        val summary = BackupCodec.summarize(encodeAll().toString())
        assertEquals(2, summary.whitelist)
        assertEquals(1, summary.history)
        assertFalse(summary.hasPin)
    }

    // ————— التخطي الفردي —————

    @Test
    fun `damaged items are skipped without failing the whole backup`() {
        val root = encodeAll()
        val sections = root.getJSONObject("sections")
        val whitelist = sections.getJSONArray("whitelist")
        whitelist.getJSONObject(0).put("host", "") // مضيف فارغ → تخطٍّ
        whitelist.getJSONObject(1).put("ruleType", "NOT_A_TYPE") // نوع غير صالح → تخطٍّ
        whitelist.put(
            JSONObject()
                .put("host", "good.org")
                .put("ruleType", "ALLOW_DOMAIN")
                .put("subdomainPolicy", "EXACT_HOST_ONLY")
                .put("enabled", true)
                .put("createdAt", 5L)
                .put("updatedAt", 5L)
        )
        val parsed = BackupCodec.parse(resign(root).toString())
        assertEquals(1, parsed.whitelist.size)
        assertEquals("good.org", parsed.whitelist[0].host)
    }

    @Test
    fun `port out of range and whitespace hosts are dropped`() {
        val root = encodeAll()
        val sections = root.getJSONObject("sections")
        val arr = sections.getJSONArray("whitelist")
        arr.getJSONObject(0).put("port", 99999)
        arr.getJSONObject(1).put("host", "two hosts")
        assertEquals(0, BackupCodec.parse(resign(root).toString()).whitelist.size)
    }

    // ————— سياسة المخطط —————

    @Test
    fun `policy_schema key never crosses the backup`() {
        val root = encodeAll()
        val sections = root.getJSONObject("sections")
        sections.put(
            "settings",
            JSONArray().put(JSONObject().put("key", "policy_schema").put("value", "1"))
        )
        val parsed = BackupCodec.parse(resign(root).toString())
        assertEquals(0, parsed.settings.size)
    }

    // ————— قسم الرمز —————

    @Test
    fun `pin snapshot round trip and iteration range guard`() {
        val pin = com.securebrowser.app.security.storage.PinManager.PinSnapshot(
            hashB64 = "AAAA".repeat(16), // 48 بايت بعد الفك — الفحص الطويل في PinManager نفسه
            saltB64 = "BBBB".repeat(4),
            iterations = 310_000L,
            length = 6L,
            createdAt = 9L
        )
        val parsed = BackupCodec.parse(encodeAll(pin).toString())
        assertNotNull(parsed.pin)
        assertEquals(310_000L, parsed.pin!!.iterations)
        assertEquals(6L, parsed.pin.length)

        val bad = encodeAll(pin.copy(iterations = 10L))
        assertNull(BackupCodec.parse(bad.toString()).pin)
    }

    // ————— أدوات —————

    private fun assertFails(reason: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected BackupException($reason)")
        } catch (e: BackupCodec.BackupException) {
            assertEquals(reason, e.reason)
        }
    }
}
