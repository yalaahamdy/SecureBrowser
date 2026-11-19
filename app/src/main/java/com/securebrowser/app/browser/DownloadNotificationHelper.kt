package com.securebrowser.app.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.securebrowser.app.R
import com.securebrowser.app.parental.DownloadsActivity

/**
 * مساعد إشعارات النظام للتنزيلات الحية (v1.13.0):
 * يعرض شريط تقدم حي في لوحة إشعارات أندرويد مع نسبة الإنجاز والسرعة،
 * وإشعار اكتمال قابل للنقر لفتح شاشة التنزيلات فورًا.
 */
object DownloadNotificationHelper {

    private const val CHANNEL_ID = "secure_browser_downloads_channel"
    private const val BASE_NOTIFICATION_ID = 40_000

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_downloads),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.downloads_save_path_badge)
                    setShowBadge(true)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun downloadsPendingIntent(context: Context, downloadId: Long): PendingIntent {
        val intent = Intent(context, DownloadsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("highlight_download_id", downloadId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(context, downloadId.toInt(), intent, flags)
    }

    fun showProgress(
        context: Context,
        id: Long,
        fileName: String,
        downloadedBytes: Long,
        totalBytes: Long?,
        speedText: String?
    ) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureChannel(context, nm)
            val indeterminate = totalBytes == null || totalBytes <= 0L
            val pct = if (!indeterminate) {
                ((downloadedBytes * 100L) / totalBytes!!).toInt().coerceIn(0, 100)
            } else 0

            val subText = buildString {
                if (!indeterminate) append("$pct%")
                if (!speedText.isNullOrBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append(speedText)
                }
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(fileName)
                .setContentText(
                    subText.ifBlank { context.getString(R.string.state_downloading) }
                )
                .setProgress(100, pct, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(downloadsPendingIntent(context, id))
                .build()

            nm.notify(BASE_NOTIFICATION_ID + (id.toInt() and 0x7FFF), notification)
        } catch (_: Exception) {
        }
    }

    fun showCompleted(context: Context, id: Long, fileName: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureChannel(context, nm)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield_check)
                .setContentTitle(fileName)
                .setContentText(context.getString(R.string.notif_download_completed))
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(downloadsPendingIntent(context, id))
                .build()
            nm.notify(BASE_NOTIFICATION_ID + (id.toInt() and 0x7FFF), notification)
        } catch (_: Exception) {
        }
    }

    fun showFailed(context: Context, id: Long, fileName: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureChannel(context, nm)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_close)
                .setContentTitle(fileName)
                .setContentText(context.getString(R.string.notif_download_failed))
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(downloadsPendingIntent(context, id))
                .build()
            nm.notify(BASE_NOTIFICATION_ID + (id.toInt() and 0x7FFF), notification)
        } catch (_: Exception) {
        }
    }

    fun cancel(context: Context, id: Long) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(BASE_NOTIFICATION_ID + (id.toInt() and 0x7FFF))
        } catch (_: Exception) {
        }
    }
}
