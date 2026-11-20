package com.securebrowser.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.securebrowser.app.data.db.entity.YoutubeChannelEntity
import com.securebrowser.app.data.db.entity.YoutubeVideoCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface YoutubeChannelDao {

    @Query("SELECT * FROM youtube_allowed_channels ORDER BY createdAt DESC")
    suspend fun getAllChannels(): List<YoutubeChannelEntity>

    @Query("SELECT * FROM youtube_allowed_channels ORDER BY createdAt DESC")
    fun observeAllChannels(): Flow<List<YoutubeChannelEntity>>

    @Query("SELECT COUNT(*) FROM youtube_allowed_channels")
    suspend fun countChannels(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannel(channel: YoutubeChannelEntity)

    @Query("UPDATE youtube_allowed_channels SET enabled = :enabled, updatedAt = :updatedAt WHERE channelId = :channelId")
    suspend fun setChannelEnabled(channelId: String, enabled: Boolean, updatedAt: Long)

    @Query("DELETE FROM youtube_allowed_channels WHERE channelId = :channelId")
    suspend fun deleteChannel(channelId: String)

    @Query("DELETE FROM youtube_allowed_channels")
    suspend fun deleteAllChannels()

    // ————————————————— كاش الفيديوهات (VideoId -> ChannelId) —————————————————

    @Query("SELECT * FROM youtube_video_cache WHERE videoId = :videoId LIMIT 1")
    suspend fun findCachedVideo(videoId: String): YoutubeVideoCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVideoCache(entry: YoutubeVideoCacheEntity)

    @Query("DELETE FROM youtube_video_cache WHERE cachedAt < :minTimestamp")
    suspend fun pruneExpiredCache(minTimestamp: Long)

    @Query("DELETE FROM youtube_video_cache WHERE videoId IN (SELECT videoId FROM youtube_video_cache ORDER BY cachedAt ASC LIMIT :limit)")
    suspend fun trimOldestCache(limit: Int)

    @Query("SELECT COUNT(*) FROM youtube_video_cache")
    suspend fun countCache(): Int

    @Query("DELETE FROM youtube_video_cache")
    suspend fun clearVideoCache()
}
