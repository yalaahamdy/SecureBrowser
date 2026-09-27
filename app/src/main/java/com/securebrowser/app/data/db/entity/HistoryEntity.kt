package com.securebrowser.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** سجل التصفح المحلي. */
@Entity(
    tableName = "history",
    indices = [Index(value = ["timestamp"]), Index(value = ["host"])]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String?,
    val host: String?,
    val timestamp: Long,
    /** نتيجة الزيارة: ALLOWED / BLOCKED / ERROR */
    val visitResult: String
)
