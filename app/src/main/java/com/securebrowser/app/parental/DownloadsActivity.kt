package com.securebrowser.app.parental

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.securebrowser.app.R
import com.securebrowser.app.data.db.entity.DownloadRecordEntity
import com.securebrowser.app.data.repository.DownloadRepository
import com.securebrowser.app.databinding.ActivitySimpleListBinding
import com.securebrowser.app.databinding.ItemDownloadRowBinding
import com.securebrowser.app.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * قسم Monitoring → Downloads (§12-§13):
 * الاسم/النوع/الحجم/المصدر/التقدم/السرعة/الحالة/الوقت + الإجراءات
 * (موافقة/رفض/إيقاف/استئناف/إعادة/فتح/مشاركة/حذف/موقع الملف).
 * حالات الموافقات تظهر للأولاد هنا مباشرة — هذا قسم الوالدين.
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySimpleListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimpleListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarTitle.text = getString(R.string.downloads_title)
        binding.btnToolbarBack.setOnClickListener { finish() }
        binding.btnToolbarAction.isVisible = false

        binding.recycler.layoutManager = LinearLayoutManager(this)
        lifecycleScope.launch {
            ServiceLocator.downloadRepository.observeRecent(200).collect { entries ->
                binding.recycler.adapter = DownloadsAdapter(entries) { download ->
                    DownloadActionsSheet.show(this@DownloadsActivity, download)
                }
                binding.emptyText.isVisible = entries.isEmpty()
            }
        }
    }
}

/**
 * صف تنزيل (v1.7.0 — إعادة تصميم): بلاطة ملوّنة بأيقونة نوع الملف،
 * شارة حالة ملوّنة حسب الحالة، ووضوح أنظف (حجم • نسبة • وقت • مصدر).
 */
class DownloadsAdapter(
    private val items: List<DownloadRecordEntity>,
    private val onMore: (DownloadRecordEntity) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.Holder>() {

    class Holder(val binding: ItemDownloadRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDownloadRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val ctx = holder.binding.root.context
        holder.binding.downloadName.text = item.fileName

        // v1.7.0 — نوع الملف يحدد الأيقونة واللون
        val type = fileTypeOf(item.fileName, item.mimeType)
        holder.binding.downloadTile.backgroundTintList =
            android.content.res.ColorStateList.valueOf(tint(ctx, type.colorRes))
        holder.binding.downloadTypeIcon.setImageResource(type.iconRes)
        holder.binding.downloadTypeIcon.setColorFilter(tint(ctx, android.R.color.white))

        val percent = if (item.sizeBytes != null && item.sizeBytes > 0) {
            (((item.downloadedBytes ?: 0) * 100) / item.sizeBytes!!).toInt().coerceIn(0, 100)
        } else 0

        val sizeInfo = if (item.sizeBytes != null && item.sizeBytes > 0 &&
            (item.downloadedBytes ?: 0) > 0
        ) {
            "${formatSize(ctx, item.downloadedBytes ?: 0)} / ${formatSize(ctx, item.sizeBytes!!)}"
        } else if (item.sizeBytes != null && item.sizeBytes > 0) {
            formatSize(ctx, item.sizeBytes!!)
        } else if ((item.downloadedBytes ?: 0) > 0) {
            formatSize(ctx, item.downloadedBytes!!)
        } else ""

        holder.binding.downloadMeta.text = buildString {
            if (item.state == DownloadRepository.STATE_DOWNLOADING && percent > 0) {
                append("$percent%")
                if (sizeInfo.isNotEmpty()) append(" • $sizeInfo")
            } else if (sizeInfo.isNotEmpty()) append(sizeInfo)
            if (isNotEmpty()) append(" • ")
            append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", item.timestamp))
            item.sourceUrl?.let {
                append("\n")
                append(ctx.getString(R.string.download_source, it.take(70)))
            }
        }

        // شارة الحالة — لون حسب الحالة (مكتمل/فشل/محجوب/انتظار/جارٍ)
        val stateColor = when (item.state) {
            DownloadRepository.STATE_COMPLETED -> R.color.chipDone
            DownloadRepository.STATE_FAILED, DownloadRepository.STATE_BLOCKED -> R.color.chipBlocked
            DownloadRepository.STATE_PENDING_APPROVAL -> R.color.chipPending
            else -> R.color.colorPrimary
        }
        holder.binding.downloadState.text = stateLabel(ctx, item.state)
        holder.binding.downloadState.backgroundTintList =
            android.content.res.ColorStateList.valueOf(tint(ctx, stateColor))

        holder.binding.downloadProgress.progress = percent
        holder.binding.downloadProgress.isVisible =
            item.state == DownloadRepository.STATE_DOWNLOADING || item.state == DownloadRepository.STATE_PAUSED

        holder.binding.root.setOnClickListener { onMore(item) }
    }

    private fun tint(ctx: android.content.Context, res: Int): Int =
        androidx.core.content.ContextCompat.getColor(ctx, res)

    private fun stateLabel(ctx: android.content.Context, state: String): String = when (state) {
        DownloadRepository.STATE_DOWNLOADING -> ctx.getString(R.string.state_downloading)
        DownloadRepository.STATE_COMPLETED -> ctx.getString(R.string.state_completed)
        DownloadRepository.STATE_PAUSED -> ctx.getString(R.string.state_paused)
        DownloadRepository.STATE_FAILED -> ctx.getString(R.string.state_failed)
        DownloadRepository.STATE_BLOCKED -> ctx.getString(R.string.state_blocked)
        DownloadRepository.STATE_PENDING_APPROVAL -> ctx.getString(R.string.state_pending_approval)
        else -> state
    }

    private fun formatSize(ctx: android.content.Context, bytes: Long): String = when {
        bytes >= 1 shl 30 -> ctx.getString(R.string.size_gb, bytes.toDouble() / (1 shl 30))
        bytes >= 1 shl 20 -> ctx.getString(R.string.size_mb, bytes.toDouble() / (1 shl 20))
        bytes >= 1 shl 10 -> ctx.getString(R.string.size_kb, bytes.toDouble() / (1 shl 10))
        else -> ctx.getString(R.string.size_bytes, bytes)
    }

    /** أنواع الملفات المدعومة بصريًا — الأيقونة واللون. */
    private enum class FileType(val iconRes: Int, val colorRes: Int) {
        VIDEO(R.drawable.ic_file_video, R.color.fileVideo),
        AUDIO(R.drawable.ic_file_audio, R.color.fileAudio),
        IMAGE(R.drawable.ic_file_image, R.color.fileImage),
        ARCHIVE(R.drawable.ic_file_archive, R.color.fileArchive),
        APK(R.drawable.ic_file_apk, R.color.fileApk),
        DOC(R.drawable.ic_file_doc, R.color.fileDoc),
        OTHER(R.drawable.ic_download, R.color.colorPrimary)
    }

    private fun fileTypeOf(fileName: String, mimeType: String?): FileType {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = mimeType?.lowercase().orEmpty()
        return when {
            ext in setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "flv") ||
                mime.startsWith("video/") -> FileType.VIDEO
            ext in setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus") ||
                mime.startsWith("audio/") -> FileType.AUDIO
            ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg") ||
                mime.startsWith("image/") -> FileType.IMAGE
            ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2") ||
                mime.contains("zip") || mime.contains("compressed") -> FileType.ARCHIVE
            ext == "apk" || mime == "application/vnd.android.package-archive" -> FileType.APK
            ext in setOf("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "txt", "epub") ||
                mime.contains("pdf") || mime.startsWith("text/") -> FileType.DOC
            else -> FileType.OTHER
        }
    }
}

/**
 * لوحة إجراءات التنزيل — تُبنى ديناميكيًا حسب الحالة الحالية.
 */
object DownloadActionsSheet {

    fun show(activity: AppCompatActivity, download: DownloadRecordEntity) {
        val manager = ServiceLocator.downloadManager
        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 14, 0, 12)
        }
        val title = android.widget.TextView(activity).apply {
            text = download.fileName
            val pad = (18 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, pad / 2)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        container.addView(title)

        fun addRow(text: String, onClick: () -> Unit) {
            val button = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                this.text = text
                isAllCaps = false
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setPadding((18 * activity.resources.displayMetrics.density).toInt(), 0, 0, 0)
                insetTop = 0
                insetBottom = 0
            }
            button.minimumHeight = (48 * activity.resources.displayMetrics.density).toInt()
            container.addView(
                button,
                android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (48 * activity.resources.displayMetrics.density).toInt()
                ).apply { setMargins(0, 2, 0, 2) }
            )
            button.setOnClickListener { onClick() }
        }

        when (download.state) {
            DownloadRepository.STATE_PENDING_APPROVAL -> {
                addRow(activity.getString(R.string.download_approve)) { manager.approve(download.id); sheetRef?.dismiss() }
                addRow(activity.getString(R.string.download_reject)) { manager.reject(download.id); sheetRef?.dismiss() }
            }
            DownloadRepository.STATE_DOWNLOADING -> {
                addRow(activity.getString(R.string.download_pause)) { manager.pause(download.id); sheetRef?.dismiss() }
            }
            DownloadRepository.STATE_PAUSED -> {
                addRow(activity.getString(R.string.download_resume)) { manager.resume(download.id); sheetRef?.dismiss() }
            }
            DownloadRepository.STATE_FAILED, DownloadRepository.STATE_BLOCKED -> {
                if (download.state == DownloadRepository.STATE_FAILED) {
                    addRow(activity.getString(R.string.download_retry)) { manager.retry(download.id); sheetRef?.dismiss() }
                }
            }
            DownloadRepository.STATE_COMPLETED -> {
                addRow(activity.getString(R.string.download_open)) { manager.openFile(download.id) }
                addRow(activity.getString(R.string.download_share)) { manager.shareFile(download.id) }
                addRow(activity.getString(R.string.download_location)) {
                    manager.fileLocation(download.id) { path ->
                        android.widget.Toast.makeText(
                            activity, path ?: activity.getString(R.string.toast_no_app),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        if (download.state != DownloadRepository.STATE_DOWNLOADING) {
            addRow(activity.getString(R.string.delete)) { manager.delete(download.id); sheetRef?.dismiss() }
        }

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(activity)
        sheetRef = sheet
        sheet.setContentView(container)
        sheet.show()
    }

    private var sheetRef: com.google.android.material.bottomsheet.BottomSheetDialog? = null
}
