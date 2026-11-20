package com.securebrowser.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * قناة يوتيوب مسموحة — المعرّف الثابت (UC...) هو المرجع الأمني الحاسم الذي لا يتغيّر
 * حتى لو بدّل صاحب القناة الاسم المستعار (@handle) أو الاسم الظاهر.
 */
@Entity(
    tableName = "youtube_allowed_channels",
    indices = [
        Index(value = ["handle"], unique = false),
        Index(value = ["enabled"], unique = false)
    ]
)
data class YoutubeChannelEntity(
    @PrimaryKey val channelId: String,
    val handle: String?,
    val title: String,
    val avatarUrl: String?,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)
