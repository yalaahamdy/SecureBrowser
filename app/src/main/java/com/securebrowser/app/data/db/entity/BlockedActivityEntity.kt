package com.securebrowser.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** النشاط المحظور — كل محاولة تنقل/تنزيل رُفضت ولماذا. */
@Entity(
    tableName = "blocked_activity",
    indices = [Index(value = ["timestamp"])]
)
data class BlockedActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String?,
    val host: String?,
    /** BlockReason name أو نص حر (مثل DOWNLOAD_POLICY). */
    val reason: String,
    /** NavigationType name أو نوع الحدث. */
    val navigationType: String?,
    val timestamp: Long
)
