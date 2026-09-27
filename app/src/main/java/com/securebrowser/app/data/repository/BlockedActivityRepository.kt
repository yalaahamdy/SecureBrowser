package com.securebrowser.app.data.repository

import com.securebrowser.app.data.db.dao.BlockedActivityDao
import com.securebrowser.app.data.db.entity.BlockedActivityEntity
import kotlinx.coroutines.flow.Flow

/** مستودع النشاط المحظور. */
class BlockedActivityRepository(private val dao: BlockedActivityDao) {

    suspend fun record(
        url: String?,
        host: String?,
        reason: String,
        navigationType: String?,
        timestamp: Long = System.currentTimeMillis()
    ) {
        dao.insert(
            BlockedActivityEntity(
                url = url?.take(2048),
                host = host?.take(253),
                reason = reason.take(60),
                navigationType = navigationType?.take(30),
                timestamp = timestamp
            )
        )
    }

    suspend fun recent(limit: Int = 200): List<BlockedActivityEntity> = dao.recent(limit)

    fun observeRecent(limit: Int = 200): Flow<List<BlockedActivityEntity>> = dao.observeRecent(limit)

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun clearAll() = dao.clearAll()
}
