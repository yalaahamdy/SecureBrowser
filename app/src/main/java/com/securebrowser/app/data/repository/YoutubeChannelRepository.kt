package com.securebrowser.app.data.repository

import com.securebrowser.app.data.db.dao.YoutubeChannelDao
import com.securebrowser.app.data.db.entity.YoutubeChannelEntity
import com.securebrowser.app.data.db.entity.YoutubeVideoCacheEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * مستودع قنوات يوتيوب المسموحة + كاش التحقق من الفيديوهات (VideoId -> ChannelId).
 *
 * يحافظ على لقطات فورية بالذاكرة (@Volatile / ConcurrentHashMap) كي يتخذ
 * [com.securebrowser.app.security.SecurityEngine] قراره بشكل متزامن بدون حظر الخيط الرئيسي.
 */
class YoutubeChannelRepository(
    private val dao: YoutubeChannelDao,
    private val scope: CoroutineScope
) {

    @Volatile
    private var channelsCache: List<YoutubeChannelEntity> = emptyList()

    @Volatile
    private var enabledChannelIdsCache: Set<String> = emptySet()

    @Volatile
    private var enabledHandlesCache: Map<String, String> = emptyMap() // normalized @handle -> channelId

    @Volatile
    private var warmedUp = false

    /** كاش الذاكرة: videoId -> Pair(channelId, cachedAtMs) */
    private val videoCache = ConcurrentHashMap<String, Pair<String, Long>>()

    init {
        scope.launch {
            dao.observeAllChannels().collect { list ->
                updateChannelSnapshots(list)
                warmedUp = true
            }
        }
    }

    suspend fun warmUp() {
        if (warmedUp) return
        updateChannelSnapshots(dao.getAllChannels())
        warmedUp = true
    }

    private fun updateChannelSnapshots(list: List<YoutubeChannelEntity>) {
        channelsCache = list
        val enabled = list.filter { it.enabled }
        enabledChannelIdsCache = enabled.map { it.channelId }.toHashSet()
        val handles = HashMap<String, String>(enabled.size)
        for (ch in enabled) {
            val h = normalizeHandle(ch.handle)
            if (h != null) handles[h] = ch.channelId
        }
        enabledHandlesCache = handles
    }

    fun observeAll(): Flow<List<YoutubeChannelEntity>> = dao.observeAllChannels()

    fun currentChannels(): List<YoutubeChannelEntity> = channelsCache

    fun enabledChannelIds(): Set<String> = enabledChannelIdsCache

    fun channelIdForHandle(handle: String?): String? {
        val norm = normalizeHandle(handle) ?: return null
        return enabledHandlesCache[norm]
    }

    /** يعيد channelId من كاش الذاكرة إن لم تنتهِ صلاحيته (TTL = 7 أيام). */
    fun cachedChannelIdForVideoInMemory(videoId: String, nowMs: Long = System.currentTimeMillis()): String? {
        val entry = videoCache[videoId] ?: return null
        if (nowMs - entry.second > VIDEO_CACHE_TTL_MS) {
            videoCache.remove(videoId)
            return null
        }
        return entry.first
    }

    suspend fun getCachedChannelIdForVideo(videoId: String, nowMs: Long = System.currentTimeMillis()): String? {
        cachedChannelIdForVideoInMemory(videoId, nowMs)?.let { return it }
        val dbEntry = dao.findCachedVideo(videoId) ?: return null
        if (nowMs - dbEntry.cachedAt > VIDEO_CACHE_TTL_MS) return null
        videoCache[videoId] = dbEntry.channelId to dbEntry.cachedAt
        return dbEntry.channelId
    }

    suspend fun putVideoChannelCache(videoId: String, channelId: String, nowMs: Long = System.currentTimeMillis()) {
        videoCache[videoId] = channelId to nowMs
        if (videoCache.size > MAX_MEMORY_CACHE_SIZE) {
            // تقليم خفيف بالذاكرة عند الامتلاء
            val keys = videoCache.keys.take(videoCache.size - MAX_MEMORY_CACHE_SIZE + 100)
            keys.forEach { videoCache.remove(it) }
        }
        dao.upsertVideoCache(YoutubeVideoCacheEntity(videoId, channelId, nowMs))
        val count = dao.countCache()
        if (count > MAX_DB_CACHE_SIZE) {
            dao.pruneExpiredCache(nowMs - VIDEO_CACHE_TTL_MS)
            val remaining = dao.countCache()
            if (remaining > MAX_DB_CACHE_SIZE) {
                dao.trimOldestCache(remaining - MAX_DB_CACHE_SIZE + 200)
            }
        }
    }

    suspend fun upsertChannel(
        channelId: String,
        handle: String?,
        title: String,
        avatarUrl: String?,
        enabled: Boolean = true
    ) {
        val now = System.currentTimeMillis()
        val existing = channelsCache.firstOrNull { it.channelId == channelId }
        val entity = YoutubeChannelEntity(
            channelId = channelId,
            handle = normalizeHandle(handle) ?: existing?.handle,
            title = title.trim().ifBlank { channelId }.take(120),
            avatarUrl = avatarUrl?.takeIf { it.isNotBlank() } ?: existing?.avatarUrl,
            enabled = enabled,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        dao.upsertChannel(entity)
        refreshSnapshot()
    }

    suspend fun setEnabled(channelId: String, enabled: Boolean) {
        dao.setChannelEnabled(channelId, enabled, System.currentTimeMillis())
        refreshSnapshot()
    }

    suspend fun deleteChannel(channelId: String) {
        dao.deleteChannel(channelId)
        refreshSnapshot()
    }

    suspend fun refreshSnapshot() {
        updateChannelSnapshots(dao.getAllChannels())
    }

    companion object {
        const val VIDEO_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_MEMORY_CACHE_SIZE = 1500
        private const val MAX_DB_CACHE_SIZE = 2500

        fun normalizeHandle(raw: String?): String? {
            val cleaned = raw?.trim()?.lowercase()?.removePrefix("@")?.trim()?.trim('/')
                ?.takeIf { it.isNotBlank() } ?: return null
            return "@$cleaned"
        }
    }
}
