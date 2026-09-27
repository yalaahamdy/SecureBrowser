package com.securebrowser.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.securebrowser.app.data.db.entity.DownloadRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadRecordDao {

    @Insert
    suspend fun insert(record: DownloadRecordEntity): Long

    @Query("SELECT * FROM downloads ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DownloadRecordEntity>

    /** v1.9.0 — كل سجلات التنزيلات (تصدير النسخة الاحتياطية). */
    @Query("SELECT * FROM downloads ORDER BY timestamp ASC")
    suspend fun getAll(): List<DownloadRecordEntity>

    /** v1.9.0 — عدد سجلات التنزيلات (حوار التصدير). */
    @Query("SELECT COUNT(*) FROM downloads")
    suspend fun count(): Int

    @Query("SELECT * FROM downloads ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DownloadRecordEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): DownloadRecordEntity?

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observeById(id: Long): Flow<DownloadRecordEntity?>

    @Query("SELECT * FROM downloads WHERE downloadId = :downloadId LIMIT 1")
    suspend fun findByDownloadId(downloadId: Long): DownloadRecordEntity?

    @Query("SELECT * FROM downloads WHERE state = 'PENDING_APPROVAL' ORDER BY timestamp DESC")
    fun observePendingApprovals(): Flow<List<DownloadRecordEntity>>

    @Query("UPDATE downloads SET state = :state, completedAt = :completedAt, sizeBytes = COALESCE(:sizeBytes, sizeBytes), updatedAt = :updatedAt WHERE downloadId = :downloadId")
    suspend fun updateStateByDownloadId(downloadId: Long, state: String, completedAt: Long?, sizeBytes: Long?, updatedAt: Long?)

    @Query("UPDATE downloads SET state = :state, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStateById(id: Long, state: String, updatedAt: Long)

    @Query("UPDATE downloads SET downloadedBytes = :downloadedBytes, sizeBytes = COALESCE(:sizeBytes, sizeBytes), updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProgress(id: Long, downloadedBytes: Long, sizeBytes: Long?, updatedAt: Long)

    @Query("UPDATE downloads SET filePath = :filePath, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateFilePath(id: Long, filePath: String, updatedAt: Long)

    @Query("UPDATE downloads SET state = :state, downloadedBytes = :downloadedBytes, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStateAndProgress(id: Long, state: String, downloadedBytes: Long, updatedAt: Long)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM downloads")
    suspend fun clearAll()
}
