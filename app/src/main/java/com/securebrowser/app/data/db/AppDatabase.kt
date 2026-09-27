package com.securebrowser.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.securebrowser.app.data.db.dao.AppSettingsDao
import com.securebrowser.app.data.db.dao.BlockedActivityDao
import com.securebrowser.app.data.db.dao.DownloadRecordDao
import com.securebrowser.app.data.db.dao.HistoryDao
import com.securebrowser.app.data.db.dao.WhiteListRuleDao
import com.securebrowser.app.data.db.entity.AppSettingEntity
import com.securebrowser.app.data.db.entity.BlockedActivityEntity
import com.securebrowser.app.data.db.entity.DownloadRecordEntity
import com.securebrowser.app.data.db.entity.HistoryEntity
import com.securebrowser.app.data.db.entity.WhiteListRuleEntity

/**
 * قاعدة البيانات المحلية — v2 (مرحلة 2).
 * الجداول: white_list_rules, history, blocked_activity, downloads, app_settings.
 * v2: أعمدة التنزيلات الكاملة (downloadedBytes, filePath, updatedAt) + فهارس الحالة.
 */
@Database(
    entities = [
        WhiteListRuleEntity::class,
        HistoryEntity::class,
        BlockedActivityEntity::class,
        DownloadRecordEntity::class,
        AppSettingEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun whiteListRuleDao(): WhiteListRuleDao
    abstract fun historyDao(): HistoryDao
    abstract fun blockedActivityDao(): BlockedActivityDao
    abstract fun downloadRecordDao(): DownloadRecordDao
    abstract fun appSettingsDao(): AppSettingsDao

    companion object {

        /** v1 → v2: أعمدة التنزيلات الجديدة (مرحلة 2). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN downloadedBytes INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE downloads ADD COLUMN filePath TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE downloads ADD COLUMN updatedAt INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_state ON downloads(state)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "secure_browser.db"
            )
                .addMigrations(MIGRATION_1_2)
                .addCallback(SeedCallback())
                .build()
    }

    /**
     * بذور أول تشغيل: قواعد قائمة مسموح افتراضية + إعدادات البداية.
     * قابلة للإزالة/التعديل من لوحة الوالدين في المرحلة الثانية.
     */
    private class SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            val now = System.currentTimeMillis()
            val seeds = listOf(
                triple("wikipedia.org", "INCLUDE_SUBDOMAINS", "ALLOW_DOMAIN"),
                triple("example.com", "EXACT_HOST_ONLY", "ALLOW_DOMAIN"),
                triple("openstreetmap.org", "INCLUDE_SUBDOMAINS", "ALLOW_DOMAIN")
            )
            for ((host, subdomainPolicy, ruleType) in seeds) {
                db.execSQL(
                    """
                    INSERT INTO white_list_rules
                        (scheme, host, path, port, ruleType, subdomainPolicy, enabled, createdAt, updatedAt)
                    VALUES (NULL, ?, NULL, NULL, ?, ?, 1, $now, $now)
                    """.trimIndent(),
                    arrayOf(host, ruleType, subdomainPolicy)
                )
            }
            val settings = mapOf(
                "theme" to "system",
                "search_engine" to "duckduckgo",
                "zoom" to "100",
                "homepage" to "start",
                "language" to "system",
                "restore_session" to "1",
                "media_autoplay" to "1",
                "external_apps" to "1",
                "external_confirm" to "1",
                "apk_policy" to "block",
                "archive_policy" to "approval",
                "unknown_policy" to "approval",
                "lock_timeout_minutes" to "5"
            )
            for ((k, v) in settings) {
                db.execSQL(
                    "INSERT OR REPLACE INTO app_settings (`key`, `value`) VALUES (?, ?)",
                    arrayOf(k, v)
                )
            }
        }

        private fun triple(host: String, subdomainPolicy: String, ruleType: String) =
            Triple(host, subdomainPolicy, ruleType)
    }
}
