package com.securebrowser.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.securebrowser.app.data.db.entity.HistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Insert
    suspend fun insert(entry: HistoryEntity): Long

    /** v1.7.0 — آخر زيارة لنفس الرابط (لنافذة دمج التكرارات عند التسجيل). */
    @Query("SELECT timestamp FROM history WHERE url = :url ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastVisitAt(url: String): Long?

    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<HistoryEntity>

    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE host LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<HistoryEntity>

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history")
    suspend fun clearAll()
}
