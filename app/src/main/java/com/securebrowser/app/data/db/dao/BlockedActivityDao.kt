package com.securebrowser.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.securebrowser.app.data.db.entity.BlockedActivityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedActivityDao {

    @Insert
    suspend fun insert(entry: BlockedActivityEntity): Long

    @Query("SELECT * FROM blocked_activity ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<BlockedActivityEntity>

    /** v1.9.0 — كل أحداث النشاط المحظور (تصدير النسخة الاحتياطية). */
    @Query("SELECT * FROM blocked_activity ORDER BY timestamp ASC")
    suspend fun getAll(): List<BlockedActivityEntity>

    /** v1.9.0 — عدد أحداث النشاط المحظور (حوار التصدير). */
    @Query("SELECT COUNT(*) FROM blocked_activity")
    suspend fun count(): Int

    @Query("SELECT * FROM blocked_activity ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BlockedActivityEntity>>

    @Query("SELECT COUNT(*) FROM blocked_activity")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM blocked_activity")
    suspend fun clearAll()
}
