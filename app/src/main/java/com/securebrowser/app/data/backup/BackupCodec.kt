package com.securebrowser.app.data.backup

import com.securebrowser.app.data.db.entity.AppSettingEntity
import com.securebrowser.app.data.db.entity.BlockedActivityEntity
import com.securebrowser.app.data.db.entity.DownloadRecordEntity
import com.securebrowser.app.data.db.entity.HistoryEntity
import com.securebrowser.app.data.db.entity.WhiteListRuleEntity
import com.securebrowser.app.security.storage.PinManager
import com.securebrowser.app.security.whitelist.RuleType
import com.securebrowser.app.security.whitelist.SubdomainPolicy
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * مرمّز/فاكّ نسخة البيانات الاحتياطية (v1.9.0) — منطق خالص قابل للاختبار.
 *
 * الملف: JSON بمخطط موثق:
 * {
 *   "format": "securebrowser-backup",
 *   "formatVersion": 1,
 *   "createdAt": epoch-ms,
 *   "appVersion": "1.9.0",
 *   "sections": { whitelist:[], history:[], downloads:[], blocked:[], settings:[], pin:{}? },
 *   "checksum": "<sha256(sections.toString()) hex>"
 * }
 *
 * الضمانات:
 * - fail-closed: أي خلل هيكلي (format/version/checksum) → [BackupException]
 *   والاستيراد يُرفض كليًا.
 * - العناصر التالفة **داخل** قسم سليم تُتخطى فرديًا (لا تُفشل النسخة كلها).
 * - مفتاح الإعداد "policy_schema" لا يعبر النسخ الاحتياطي أبدًا (ترحيل
 *   السياسة ملك للتطبيق وحده).
 * - لا تُصدَّر نصوص رمز صريحة إطلاقًا — قسم الرمز هاش PBKDF2 فقط واختياري.
 */
object BackupCodec {

    const val FORMAT = "securebrowser-backup"
    const val FORMAT_VERSION = 1

    /** السقف الأقصى لحجم ملف الاستيراد (24MB — وفرة سخية لأي قاعدة محلية). */
    const val MAX_BACKUP_BYTES = 24L * 1024 * 1024

    /** مفاتيح الإعدادات التي لا تعبر النسخ الاحتياطي أبدًا. */
    private val EXCLUDED_SETTING_KEYS = setOf("policy_schema")

    // ————————————————— أسماء أقسام المخطط —————————————————

    private const val SECTION_WHITELIST = "whitelist"
    private const val SECTION_HISTORY = "history"
    private const val SECTION_DOWNLOADS = "downloads"
    private const val SECTION_BLOCKED = "blocked"
    private const val SECTION_SETTINGS = "settings"
    private const val SECTION_PIN = "pin"

    /** خطأ هيكلي موصوف — تُترجمه الواجهة إلى رسالة عربية مناسبة. */
    class BackupException(val reason: String) : Exception(reason)

    /** ملخص النسخة قبل الاستيراد (حوار التأكيد). */
    data class BackupSummary(
        val createdAt: Long,
        val appVersion: String,
        val whitelist: Int,
        val history: Int,
        val downloads: Int,
        val blocked: Int,
        val settings: Int,
        val hasPin: Boolean
    )

    /** نتيجة الاستيراد (تُعرض للوالد). */
    data class ImportOutcome(
        val rulesAdded: Int,
        val rulesSkipped: Int,
        val historyAdded: Int,
        val downloadsAdded: Int,
        val blockedAdded: Int,
        val settingsApplied: Int,
        val pinRestored: Boolean
    ) {
        fun totalAdded(): Int = rulesAdded + historyAdded + downloadsAdded + blockedAdded
    }

    /** بيانات مُحلَّلة ومتحقق منها — جاهزة للتطبيق على القاعدة. */
    class ParsedBackup(
        val createdAt: Long,
        val appVersion: String,
        val whitelist: List<WhiteListRuleEntity>,
        val history: List<HistoryEntity>,
        val downloads: List<DownloadRecordEntity>,
        val blocked: List<BlockedActivityEntity>,
        val settings: List<AppSettingEntity>,
        val pin: PinManager.PinSnapshot?
    )

    // ————————————————— الترميز —————————————————

    fun encode(
        whitelist: List<WhiteListRuleEntity>,
        history: List<HistoryEntity>,
        downloads: List<DownloadRecordEntity>,
        blocked: List<BlockedActivityEntity>,
        settings: List<AppSettingEntity>,
        pin: PinManager.PinSnapshot?,
        appVersion: String
    ): JSONObject {
        val sections = JSONObject()
            .put(SECTION_WHITELIST, whitelist.map { whitelistToJson(it) }.toJsonArray())
            .put(SECTION_HISTORY, history.map { historyToJson(it) }.toJsonArray())
            .put(SECTION_DOWNLOADS, downloads.map { downloadToJson(it) }.toJsonArray())
            .put(SECTION_BLOCKED, blocked.map { blockedToJson(it) }.toJsonArray())
            .put(
                SECTION_SETTINGS,
                settings.filter { it.key !in EXCLUDED_SETTING_KEYS }
                    .map { JSONObject().put("key", it.key).put("value", it.value) }
                    .toJsonArray()
            )
        if (pin != null) sections.put(SECTION_PIN, pinToJson(pin))
        return JSONObject()
            .put("format", FORMAT)
            .put("formatVersion", FORMAT_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("appVersion", appVersion)
            .put("sections", sections)
            .put("checksum", sha256Hex(sections.toString()))
    }

    // ————————————————— الفك والتحقق —————————————————

    /** تحقق هيكلي كامل + فك العناصر الصالحة. يرمي [BackupException] عند أي خلل هيكلي. */
    fun parse(text: String): ParsedBackup {
        val root = requireValidRoot(text)
        val sections = root.optJSONObject("sections")
            ?: throw BackupException("not_backup")
        val checksum = root.optString("checksum", "")
        if (checksum.isBlank() || checksum != sha256Hex(sections.toString())) {
            throw BackupException("corrupted")
        }

        return ParsedBackup(
            createdAt = root.optLong("createdAt", 0L),
            appVersion = root.optString("appVersion", ""),
            whitelist = parseWhitelist(sections.optJSONArray(SECTION_WHITELIST) ?: JSONArray()),
            history = parseHistory(sections.optJSONArray(SECTION_HISTORY) ?: JSONArray()),
            downloads = parseDownloads(sections.optJSONArray(SECTION_DOWNLOADS) ?: JSONArray()),
            blocked = parseBlocked(sections.optJSONArray(SECTION_BLOCKED) ?: JSONArray()),
            settings = parseSettings(sections.optJSONArray(SECTION_SETTINGS) ?: JSONArray()),
            pin = parsePin(sections.optJSONObject(SECTION_PIN))
        )
    }

    /** فحص خفيف للعرض في حوار التأكيد — بلا فك تفصيلي للعناصر. */
    fun summarize(text: String): BackupSummary {
        val root = requireValidRoot(text)
        val sections = root.optJSONObject("sections") ?: throw BackupException("not_backup")
        val checksum = root.optString("checksum", "")
        if (checksum.isBlank() || checksum != sha256Hex(sections.toString())) {
            throw BackupException("corrupted")
        }
        val count = { name: String -> sections.optJSONArray(name)?.length() ?: 0 }
        return BackupSummary(
            createdAt = root.optLong("createdAt", 0L),
            appVersion = root.optString("appVersion", ""),
            whitelist = count(SECTION_WHITELIST),
            history = count(SECTION_HISTORY),
            downloads = count(SECTION_DOWNLOADS),
            blocked = count(SECTION_BLOCKED),
            settings = count(SECTION_SETTINGS),
            hasPin = sections.has(SECTION_PIN)
        )
    }

    private fun requireValidRoot(text: String): JSONObject {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw BackupException("not_backup")
        }
        if (root.optString("format") != FORMAT) throw BackupException("not_backup")
        val version = root.optInt("formatVersion", -1)
        if (version < 1) throw BackupException("not_backup")
        if (version > FORMAT_VERSION) throw BackupException("unsupported_version")
        return root
    }

    // ————————————————— قسم القائمة البيضاء —————————————————

    private fun whitelistToJson(e: WhiteListRuleEntity): JSONObject = JSONObject()
        .put("scheme", e.scheme ?: JSONObject.NULL)
        .put("host", e.host)
        .put("path", e.path ?: JSONObject.NULL)
        .put("port", e.port ?: JSONObject.NULL)
        .put("ruleType", e.ruleType)
        .put("subdomainPolicy", e.subdomainPolicy)
        .put("enabled", e.enabled)
        .put("createdAt", e.createdAt)
        .put("updatedAt", e.updatedAt)

    private fun parseWhitelist(array: JSONArray): List<WhiteListRuleEntity> {
        val out = ArrayList<WhiteListRuleEntity>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            try {
                val host = item.optString("host", "").trim().lowercase()
                    .trimEnd('.')
                if (host.isEmpty() || host.length > 253) continue
                if (host.any { it.isWhitespace() }) continue
                val ruleType = try {
                    RuleType.valueOf(item.optString("ruleType", ""))
                } catch (e: Exception) {
                    continue
                }
                val subdomainPolicy = try {
                    SubdomainPolicy.valueOf(item.optString("subdomainPolicy", ""))
                } catch (e: Exception) {
                    continue
                }
                val schemeRaw = item.optString("scheme", "")
                val scheme = if (schemeRaw == "http" || schemeRaw == "https") schemeRaw else null
                val pathRaw = item.optString("path", "")
                val path = if (pathRaw.isBlank()) null
                else if (pathRaw.startsWith("/")) pathRaw.take(2048) else "/${pathRaw.take(2047)}"
                // منفذ موجود لكنه غير صالح = عنصر مرفوض fail-closed
                // (تجريده إلى null سيغيّر دلالة القاعدة إلى "المنافذ القياسية")
                val port = if (item.isNull("port")) null else {
                    val p = item.optInt("port", -1)
                    if (p !in 1..65535) continue
                    p
                }
                val now = System.currentTimeMillis()
                out.add(
                    WhiteListRuleEntity(
                        id = 0,
                        scheme = scheme,
                        host = host,
                        path = if (ruleType == RuleType.ALLOW_DOMAIN) null else path,
                        port = port,
                        ruleType = ruleType.name,
                        subdomainPolicy = subdomainPolicy.name,
                        enabled = item.optBoolean("enabled", true),
                        createdAt = item.optLong("createdAt", now).coerceAtLeast(0),
                        updatedAt = item.optLong("updatedAt", now).coerceAtLeast(0)
                    )
                )
            } catch (e: Exception) {
                continue // عنصر تالف — تخطٍّ فردي
            }
        }
        return out
    }

    // ————————————————— قسم السجل —————————————————

    private fun historyToJson(e: HistoryEntity): JSONObject = JSONObject()
        .put("url", e.url)
        .put("title", e.title ?: JSONObject.NULL)
        .put("host", e.host ?: JSONObject.NULL)
        .put("timestamp", e.timestamp)
        .put("visitResult", e.visitResult)

    private fun parseHistory(array: JSONArray): List<HistoryEntity> {
        val out = ArrayList<HistoryEntity>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            try {
                val url = item.optString("url", "").trim()
                if (url.isEmpty() || url.length > 2048) continue
                out.add(
                    HistoryEntity(
                        id = 0,
                        url = url,
                        title = item.optString("title", "").takeIf { it.isNotEmpty() }?.take(300),
                        host = item.optString("host", "").takeIf { it.isNotEmpty() }?.take(253),
                        timestamp = item.optLong("timestamp", 0L).coerceAtLeast(0),
                        visitResult = item.optString("visitResult", "ALLOWED")
                            .uppercase().take(20).ifEmpty { "ALLOWED" }
                    )
                )
            } catch (e: Exception) {
                continue
            }
        }
        return out
    }

    // ————————————————— قسم التنزيلات —————————————————

    private fun downloadToJson(e: DownloadRecordEntity): JSONObject = JSONObject()
        .put("fileName", e.fileName)
        .put("url", e.url)
        .put("sourceUrl", e.sourceUrl ?: JSONObject.NULL)
        .put("mimeType", e.mimeType ?: JSONObject.NULL)
        .put("state", e.state)
        .put("sizeBytes", e.sizeBytes ?: JSONObject.NULL)
        .put("downloadedBytes", e.downloadedBytes ?: JSONObject.NULL)
        .put("filePath", e.filePath ?: JSONObject.NULL)
        .put("timestamp", e.timestamp)
        .put("completedAt", e.completedAt ?: JSONObject.NULL)
        .put("updatedAt", e.updatedAt ?: JSONObject.NULL)

    private fun parseDownloads(array: JSONArray): List<DownloadRecordEntity> {
        val out = ArrayList<DownloadRecordEntity>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            try {
                val fileName = item.optString("fileName", "").trim()
                val url = item.optString("url", "").trim()
                if (fileName.isEmpty() || fileName.length > 255) continue
                if (url.isEmpty() || url.length > 2048) continue
                val state = item.optString("state", "COMPLETED")
                    .uppercase().take(30).ifEmpty { "COMPLETED" }
                fun nullableLong(name: String): Long? =
                    if (item.isNull(name)) null
                    else item.optLong(name, -1L).takeIf { it >= 0 }
                out.add(
                    DownloadRecordEntity(
                        id = 0,
                        downloadId = null, // معرف DownloadManager النظام غير قابل للترحيل بين الأجهزة
                        fileName = fileName,
                        url = url,
                        sourceUrl = item.optString("sourceUrl", "")
                            .takeIf { it.isNotEmpty() }?.take(2048),
                        mimeType = item.optString("mimeType", "")
                            .takeIf { it.isNotEmpty() }?.take(128),
                        state = state,
                        sizeBytes = nullableLong("sizeBytes"),
                        downloadedBytes = nullableLong("downloadedBytes"),
                        filePath = item.optString("filePath", "")
                            .takeIf { it.isNotEmpty() }?.take(512),
                        timestamp = item.optLong("timestamp", 0L).coerceAtLeast(0),
                        completedAt = nullableLong("completedAt"),
                        updatedAt = nullableLong("updatedAt")
                    )
                )
            } catch (e: Exception) {
                continue
            }
        }
        return out
    }

    // ————————————————— قسم النشاط المحظور —————————————————

    private fun blockedToJson(e: BlockedActivityEntity): JSONObject = JSONObject()
        .put("url", e.url ?: JSONObject.NULL)
        .put("host", e.host ?: JSONObject.NULL)
        .put("reason", e.reason)
        .put("navigationType", e.navigationType ?: JSONObject.NULL)
        .put("timestamp", e.timestamp)

    private fun parseBlocked(array: JSONArray): List<BlockedActivityEntity> {
        val out = ArrayList<BlockedActivityEntity>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            try {
                val reason = item.optString("reason", "").trim()
                if (reason.isEmpty() || reason.length > 60) continue
                out.add(
                    BlockedActivityEntity(
                        id = 0,
                        url = item.optString("url", "")
                            .takeIf { it.isNotEmpty() }?.take(2048),
                        host = item.optString("host", "")
                            .takeIf { it.isNotEmpty() }?.take(253),
                        reason = reason,
                        navigationType = item.optString("navigationType", "")
                            .takeIf { it.isNotEmpty() }?.take(30),
                        timestamp = item.optLong("timestamp", 0L).coerceAtLeast(0)
                    )
                )
            } catch (e: Exception) {
                continue
            }
        }
        return out
    }

    // ————————————————— قسم الإعدادات —————————————————

    private fun parseSettings(array: JSONArray): List<AppSettingEntity> {
        val out = ArrayList<AppSettingEntity>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            try {
                val key = item.optString("key", "").trim()
                val value = item.optString("value", "")
                if (key.isEmpty() || key.length > 64) continue
                if (value.length > 8192) continue
                if (key in EXCLUDED_SETTING_KEYS) continue // ترحيل السياسة ملك للتطبيق وحده
                out.add(AppSettingEntity(key, value))
            } catch (e: Exception) {
                continue
            }
        }
        return out
    }

    // ————————————————— قسم الرمز —————————————————

    private fun pinToJson(p: PinManager.PinSnapshot): JSONObject = JSONObject()
        .put("hash", p.hashB64)
        .put("salt", p.saltB64)
        .put("iterations", p.iterations)
        .put("length", p.length)
        .put("createdAt", p.createdAt)

    private fun parsePin(obj: JSONObject?): PinManager.PinSnapshot? {
        obj ?: return null
        return try {
            val hash = obj.optString("hash", "")
            val salt = obj.optString("salt", "")
            if (hash.isBlank() || salt.isBlank()) return null
            val iterations = obj.optLong("iterations", 0L)
            if (iterations !in PinManager.MIN_ITERATIONS..PinManager.MAX_ITERATIONS) return null
            PinManager.PinSnapshot(
                hashB64 = hash,
                saltB64 = salt,
                iterations = iterations,
                length = obj.optLong("length", 0L),
                createdAt = obj.optLong("createdAt", 0L).coerceAtLeast(0)
            )
        } catch (e: Exception) {
            null
        }
    }

    // ————————————————— أدوات —————————————————

    private fun List<JSONObject>.toJsonArray(): JSONArray = JSONArray().apply {
        for (item in this@toJsonArray) put(item)
    }

    /** بصمة سلامة الأقسام — SHA-256 hex صغيرة الأحرف. */
    fun sha256Hex(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format("%02x", it) }
    }
}
