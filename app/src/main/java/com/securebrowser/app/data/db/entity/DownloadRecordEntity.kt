package com.securebrowser.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * سجل التنزيلات ومراقبتها — v2 (مرحلة 2).
 * الحالات: PENDING_APPROVAL / DOWNLOADING / PAUSED / COMPLETED / FAILED / BLOCKED.
 * التنزيل الفعلي بمدير التطبيق الداخلي (BrowserDownloadManager) — downloadId يحفظ
 * معرف DownloadManager النظام فقط للسجلات القديمة.
 */
@Entity(
    tableName = "downloads",
    indices = [Index(value = ["timestamp"]), Index(value = ["state"]), Index(value = ["downloadId"], unique = false)]
)
data class DownloadRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** معرف DownloadManager النظام إن وُجد (سجلات قديمة فقط). */
    val downloadId: Long? = null,
    val fileName: String,
    val url: String,
    val sourceUrl: String?,
    val mimeType: String?,
    val state: String,
    val sizeBytes: Long?,
    val downloadedBytes: Long? = null,
    /** مسار الملف المحلي النسبي داخل مجلد تنزيلات التطبيق. */
    val filePath: String? = null,
    val timestamp: Long,
    val completedAt: Long? = null,
    val updatedAt: Long? = null
)
