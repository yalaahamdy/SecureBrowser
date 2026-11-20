package com.securebrowser.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * كاش محلي لربط معرف الفيديو (videoId) بمعرف القناة الثابت (channelId) لتسريع التحقق
 * دون إعادة طلب الشبكة في كل مرة (بصلاحية TTL محددة في YoutubeChannelVerifier).
 */
@Entity(
    tableName = "youtube_video_cache",
    indices = [
        Index(value = ["channelId"], unique = false),
        Index(value = ["cachedAt"], unique = false)
    ]
)
data class YoutubeVideoCacheEntity(
    @PrimaryKey val videoId: String,
    val channelId: String,
    val cachedAt: Long
)
