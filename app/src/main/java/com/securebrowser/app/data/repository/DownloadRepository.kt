package com.securebrowser.app.data.repository

import com.securebrowser.app.data.db.dao.DownloadRecordDao
import com.securebrowser.app.data.db.entity.DownloadRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * مستودع التنزيلات — مرحلة 2.
 * حالات السجل: PENDING_APPROVAL / DOWNLOADING / PAUSED / COMPLETED / FAILED / BLOCKED.
 */
class DownloadRepository(private val dao: DownloadRecordDao) {

    suspend fun insert(record: DownloadRecordEntity): Long = dao.insert(record)

    suspend fun findById(id: Long): DownloadRecordEntity? = dao.findById(id)

    suspend fun findByDownloadId(downloadId: Long): DownloadRecordEntity? =
        dao.findByDownloadId(downloadId)

    fun observeRecent(limit: Int = 200): Flow<List<DownloadRecordEntity>> =
        dao.observeRecent(limit)

    suspend fun recent(limit: Int = 100): List<DownloadRecordEntity> = dao.recent(limit)

    fun observeById(id: Long): Flow<DownloadRecordEntity?> = dao.observeById(id)

    fun observePendingApprovals(): Flow<List<DownloadRecordEntity>> =
        dao.observePendingApprovals()

    suspend fun updateState(id: Long, state: String) =
        dao.updateStateById(id, state, System.currentTimeMillis())

    suspend fun updateProgress(id: Long, downloadedBytes: Long, sizeBytes: Long?) =
        dao.updateProgress(id, downloadedBytes, sizeBytes, System.currentTimeMillis())

    suspend fun updateFilePath(id: Long, filePath: String) =
        dao.updateFilePath(id, filePath, System.currentTimeMillis())

    suspend fun updateStateAndProgress(id: Long, state: String, downloadedBytes: Long) =
        dao.updateStateAndProgress(id, state, downloadedBytes, System.currentTimeMillis())

    /** للسجلات القديمة المربوطة بـ DownloadManager النظام. */
    suspend fun updateStateForSystemDownload(downloadId: Long, state: String, sizeBytes: Long?) =
        dao.updateStateByDownloadId(
            downloadId, state,
            if (state == "COMPLETED" || state == "FAILED") System.currentTimeMillis() else null,
            sizeBytes,
            System.currentTimeMillis()
        )

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun clearAll() = dao.clearAll()

    companion object {
        const val STATE_PENDING_APPROVAL = "PENDING_APPROVAL"
        const val STATE_DOWNLOADING = "DOWNLOADING"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_FAILED = "FAILED"
        const val STATE_BLOCKED = "BLOCKED"
    }
}
