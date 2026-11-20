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
import com.securebrowser.app.data.db.dao.YoutubeChannelDao
import com.securebrowser.app.data.db.entity.AppSettingEntity
import com.securebrowser.app.data.db.entity.BlockedActivityEntity
import com.securebrowser.app.data.db.entity.DownloadRecordEntity
import com.securebrowser.app.data.db.entity.HistoryEntity
import com.securebrowser.app.data.db.entity.WhiteListRuleEntity
import com.securebrowser.app.data.db.entity.YoutubeChannelEntity
import com.securebrowser.app.data.db.entity.YoutubeVideoCacheEntity

/**
 * قاعدة البيانات المحلية — v3.
 * الجداول: white_list_rules, history, blocked_activity, downloads, app_settings,
 * youtube_allowed_channels, youtube_video_cache.
 * v2: أعمدة التنزيلات الكاملة (downloadedBytes, filePath, updatedAt) + فهارس الحالة.
 * v3: جداول قنوات يوتيوب المسموحة وكاش ربط الفيديو بالقناة.
 */
@Database(
    entities = [
        WhiteListRuleEntity::class,
        HistoryEntity::class,
        BlockedActivityEntity::class,
        DownloadRecordEntity::class,
        AppSettingEntity::class,
        YoutubeChannelEntity::class,
        YoutubeVideoCacheEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun whiteListRuleDao(): WhiteListRuleDao
    abstract fun historyDao(): HistoryDao
    abstract fun blockedActivityDao(): BlockedActivityDao
    abstract fun downloadRecordDao(): DownloadRecordDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun youtubeChannelDao(): YoutubeChannelDao

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

        /** v2 → v3: جداول قنوات يوتيوب المسموحة وكاش الفيديوهات. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS youtube_allowed_channels (
                        channelId TEXT NOT NULL PRIMARY KEY,
                        handle TEXT DEFAULT NULL,
                        title TEXT NOT NULL,
                        avatarUrl TEXT DEFAULT NULL,
                        enabled INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_youtube_allowed_channels_handle ON youtube_allowed_channels(handle)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_youtube_allowed_channels_enabled ON youtube_allowed_channels(enabled)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS youtube_video_cache (
                        videoId TEXT NOT NULL PRIMARY KEY,
                        channelId TEXT NOT NULL,
                        cachedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_youtube_video_cache_channelId ON youtube_video_cache(channelId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_youtube_video_cache_cachedAt ON youtube_video_cache(cachedAt)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "secure_browser.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
