package com.securebrowser.app.data.backup

import androidx.room.withTransaction
import com.securebrowser.app.data.backup.BackupCodec.BackupSummary
import com.securebrowser.app.data.backup.BackupCodec.ImportOutcome
import com.securebrowser.app.data.db.AppDatabase
import com.securebrowser.app.security.storage.PinManager

/**
 * مدير النسخ الاحتياطي والاستعادة (v1.9.0) — تنسيق القراءة/الكتابة مع Room.
 *
 * - التصدير: قراءة كل الأقسام داخل معاملة واحدة (اتساق لقطة واحدة) وترميزها
 *   عبر [BackupCodec] مع بصمة سلامة.
 * - الاستيراد: تحقق هيكلي كامل أولًا (fail-closed)، ثم تطبيق داخل معاملة:
 *   - **MERGE**: القائمة البيضاء تتخطى القواعد الموجودة أصلًا (نفس
 *     host+scheme+path+port+type+subdomains)، وبقية الأقسام تُضاف كما هي،
 *     والإعدادات تُدمج (مفاتيح النسخة تغلب الحالية).
 *   - **REPLACE**: القائمة البيضاء/السجل/التنزيلات/المحظور تُمسح ثم تُستورد
 *     بحالتها من النسخة؛ الإعدادات تُدمج دائمًا (حذف مفاتيح غير معروفة
 *     خطير على الإصدارات القادمة).
 * - الرمز يُستعاد فقط بقرار صريح من الوالد (خانة تأكيد) — عبر
 *   [PinManager.restoreData] بتحقق صارم.
 */
class BackupManager(
    private val database: AppDatabase,
    private val pinManager: PinManager
) {

    /** عدّادات حوار التصدير. */
    data class BackupCounts(
        val whitelist: Int,
        val history: Int,
        val downloads: Int,
        val blocked: Int,
        val settings: Int,
        val hasPin: Boolean
    )

    suspend fun counts(): BackupCounts = database.withTransaction {
        BackupCounts(
            whitelist = database.whiteListRuleDao().count(),
            history = database.historyDao().count(),
            downloads = database.downloadRecordDao().count(),
            blocked = database.blockedActivityDao().count(),
            settings = database.appSettingsDao().getAll().size,
            hasPin = pinManager.hasPin()
        )
    }

    /** بناء ملف النسخة الكامل (اتساق لقطة واحدة داخل معاملة). */
    suspend fun export(includePin: Boolean, appVersion: String) =
        database.withTransaction {
            BackupCodec.encode(
                whitelist = database.whiteListRuleDao().getAll(),
                history = database.historyDao().getAll(),
                downloads = database.downloadRecordDao().getAll(),
                blocked = database.blockedActivityDao().getAll(),
                settings = database.appSettingsDao().getAll(),
                pin = if (includePin) pinManager.exportData() else null,
                appVersion = appVersion
            )
        }

    /** فحص خفيف للعرض في حوار التأكيد — بلا كتابة. */
    suspend fun summarize(text: String): BackupSummary =
        BackupCodec.summarize(text)

    /**
     * تطبيق النسخة على القاعدة داخل معاملة واحدة — نجاح كامل أو لا شيء
     * (استثناء هيكلي/قواعدي قبل المعاملة، وفشل القاعدة يلغي كل الكتابات).
     */
    suspend fun import(
        text: String,
        mode: ImportMode,
        restorePin: Boolean
    ): ImportOutcome {
        val parsed = BackupCodec.parse(text) // fail-closed قبل أي كتابة
        return database.withTransaction {
            val whiteListDao = database.whiteListRuleDao()
            val historyDao = database.historyDao()
            val downloadDao = database.downloadRecordDao()
            val blockedDao = database.blockedActivityDao()
            val settingsDao = database.appSettingsDao()

            // ————— القائمة البيضاء —————
            var rulesAdded = 0
            var rulesSkipped = 0
            when (mode) {
                ImportMode.REPLACE -> {
                    whiteListDao.deleteAll()
                    for (rule in parsed.whitelist) {
                        whiteListDao.insert(rule)
                        rulesAdded++
                    }
                }
                ImportMode.MERGE -> {
                    val existing = whiteListDao.getAll()
                    val existingKeys = existing.map { whitelistKey(it) }.toHashSet()
                    for (rule in parsed.whitelist) {
                        if (whitelistKey(rule) in existingKeys) {
                            rulesSkipped++
                            continue
                        }
                        whiteListDao.insert(rule)
                        existingKeys.add(whitelistKey(rule))
                        rulesAdded++
                    }
                }
            }

            // ————— السجل —————
            var historyAdded = 0
            if (mode == ImportMode.REPLACE) historyDao.clearAll()
            for (entry in parsed.history) {
                historyDao.insert(entry)
                historyAdded++
            }

            // ————— التنزيلات —————
            var downloadsAdded = 0
            if (mode == ImportMode.REPLACE) downloadDao.clearAll()
            for (record in parsed.downloads) {
                downloadDao.insert(record)
                downloadsAdded++
            }

            // ————— النشاط المحظور —————
            var blockedAdded = 0
            if (mode == ImportMode.REPLACE) blockedDao.clearAll()
            for (event in parsed.blocked) {
                blockedDao.insert(event)
                blockedAdded++
            }

            // ————— الإعدادات — دمج دائم (مفاتيح النسخة تغلب الحالية) —————
            var settingsApplied = 0
            for (setting in parsed.settings) {
                settingsDao.upsert(setting)
                settingsApplied++
            }

            // ————— الرمز — قرار والد صريح —————
            val pinRestored = restorePin && parsed.pin != null &&
                pinManager.restoreData(parsed.pin)

            ImportOutcome(
                rulesAdded = rulesAdded,
                rulesSkipped = rulesSkipped,
                historyAdded = historyAdded,
                downloadsAdded = downloadsAdded,
                blockedAdded = blockedAdded,
                settingsApplied = settingsApplied,
                pinRestored = pinRestored
            )
        }
    }

    /** هوية القاعدة للتخطي في الدمج — كل الحقول الدلالية بلا المعرفات. */
    private fun whitelistKey(rule: com.securebrowser.app.data.db.entity.WhiteListRuleEntity): String =
        listOf(
            rule.host,
            rule.scheme ?: "",
            rule.path ?: "",
            rule.port?.toString() ?: "",
            rule.ruleType,
            rule.subdomainPolicy
        ).joinToString("|")
}

/** وضع الاستيراد — دمج أو استبدال. */
enum class ImportMode { MERGE, REPLACE }
