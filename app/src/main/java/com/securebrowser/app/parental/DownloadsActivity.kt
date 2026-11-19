package com.securebrowser.app.parental

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.securebrowser.app.R
import com.securebrowser.app.browser.BrowserDownloadManager
import com.securebrowser.app.data.db.entity.DownloadRecordEntity
import com.securebrowser.app.data.repository.DownloadRepository
import com.securebrowser.app.databinding.ActivityDownloadsBinding
import com.securebrowser.app.databinding.ItemDownloadRowBinding
import com.securebrowser.app.databinding.ItemMenuRowBinding
import com.securebrowser.app.di.ServiceLocator
import com.securebrowser.app.ui.PinGate
import kotlinx.coroutines.launch

/**
 * مدير التنزيلات الاحترافي (v1.13.0):
 * - بحث فوري + 8 تصنيفات (الكل/الجارية/المستندات/الصور/الفيديو/الصوتيات/APK/المضغوطة).
 * - بطاقة ملخص (عدد الملفات + الحجم الكلي + النشطة الآن + مسار الحفظ: مجلد Download بالهاتف).
 * - بطاقات تفاعلية بـ ListAdapter + DiffUtil لعرض التقدم والسرعة والوقت المتبقي بدون وميض.
 * - أزرار إجراءات سريعة على البطاقة مباشرة + قائمة خيارات شاملة + إضافة رابط مباشر + إعادة تسمية.
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var adapter: DownloadsAdapter

    private var allDownloads: List<DownloadRecordEntity> = emptyList()
    private var currentFilter: DownloadFilter = DownloadFilter.ALL
    private var currentQuery: String = ""

    enum class DownloadFilter {
        ALL, ACTIVE, DOCS, IMAGES, VIDEOS, AUDIO, APK, ARCHIVES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToolbarBack.setOnClickListener { finish() }
        binding.btnAddDownload.setOnClickListener { showAddUrlDialog() }
        binding.btnEmptyAddUrl.setOnClickListener { showAddUrlDialog() }
        binding.btnClearFinished.setOnClickListener { confirmClearFinished() }

        adapter = DownloadsAdapter(
            onPrimaryClick = { item -> handleCardPrimaryAction(item) },
            onQuickPrimary = { item -> handleQuickPrimary(item) },
            onQuickSecondary = { item -> handleQuickSecondary(item) },
            onMore = { item -> DownloadActionsSheet.show(this, item) }
        )

        binding.recycler.layoutManager = LinearLayoutManager(this)
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.adapter = adapter

        setupSearch()
        setupFilters()

        lifecycleScope.launch {
            ServiceLocator.downloadRepository.observeRecent(300).collect { entries ->
                allDownloads = entries
                updateSummaryCard(entries)
                applyFilterAndSubmit()
            }
        }
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString()?.trim().orEmpty()
                applyFilterAndSubmit()
            }
        })
    }

    private fun setupFilters() {
        binding.filterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: R.id.chipAll
            currentFilter = when (id) {
                R.id.chipActive -> DownloadFilter.ACTIVE
                R.id.chipDocs -> DownloadFilter.DOCS
                R.id.chipImages -> DownloadFilter.IMAGES
                R.id.chipVideos -> DownloadFilter.VIDEOS
                R.id.chipAudio -> DownloadFilter.AUDIO
                R.id.chipApk -> DownloadFilter.APK
                R.id.chipArchives -> DownloadFilter.ARCHIVES
                else -> DownloadFilter.ALL
            }
            applyFilterAndSubmit()
        }
    }

    private fun updateSummaryCard(entries: List<DownloadRecordEntity>) {
        val totalCount = entries.size
        val activeCount = entries.count {
            it.state == DownloadRepository.STATE_DOWNLOADING ||
                it.state == DownloadRepository.STATE_PENDING_APPROVAL
        }
        val totalBytes = entries.sumOf {
            when {
                it.state == DownloadRepository.STATE_COMPLETED -> (it.sizeBytes ?: it.downloadedBytes ?: 0L)
                else -> (it.downloadedBytes ?: 0L)
            }.coerceAtLeast(0L)
        }

        binding.summaryStats.text = getString(
            R.string.downloads_summary_stats,
            totalCount,
            formatSize(this, totalBytes)
        )
        binding.summaryActiveBadge.text = if (activeCount > 0) {
            getString(R.string.downloads_summary_active, activeCount)
        } else {
            getString(R.string.downloads_summary_idle)
        }
    }

    private fun applyFilterAndSubmit() {
        val filtered = allDownloads.filter { item ->
            val matchesFilter = when (currentFilter) {
                DownloadFilter.ALL -> true
                DownloadFilter.ACTIVE -> item.state == DownloadRepository.STATE_DOWNLOADING ||
                    item.state == DownloadRepository.STATE_PAUSED ||
                    item.state == DownloadRepository.STATE_PENDING_APPROVAL
                DownloadFilter.DOCS -> fileTypeOf(item.fileName, item.mimeType) == FileType.DOC
                DownloadFilter.IMAGES -> fileTypeOf(item.fileName, item.mimeType) == FileType.IMAGE
                DownloadFilter.VIDEOS -> fileTypeOf(item.fileName, item.mimeType) == FileType.VIDEO
                DownloadFilter.AUDIO -> fileTypeOf(item.fileName, item.mimeType) == FileType.AUDIO
                DownloadFilter.APK -> fileTypeOf(item.fileName, item.mimeType) == FileType.APK
                DownloadFilter.ARCHIVES -> fileTypeOf(item.fileName, item.mimeType) == FileType.ARCHIVE
            }
            if (!matchesFilter) return@filter false
            if (currentQuery.isBlank()) return@filter true
            item.fileName.contains(currentQuery, ignoreCase = true) ||
                (item.sourceUrl?.contains(currentQuery, ignoreCase = true) == true) ||
                item.url.contains(currentQuery, ignoreCase = true)
        }

        adapter.submitList(filtered)
        binding.emptyContainer.isVisible = filtered.isEmpty()
    }

    private fun handleCardPrimaryAction(item: DownloadRecordEntity) {
        if (item.state == DownloadRepository.STATE_COMPLETED) {
            ServiceLocator.downloadManager.openFile(item.id)
        } else {
            DownloadActionsSheet.show(this, item)
        }
    }

    private fun handleQuickPrimary(item: DownloadRecordEntity) {
        val mgr = ServiceLocator.downloadManager
        when (item.state) {
            DownloadRepository.STATE_DOWNLOADING -> mgr.pause(item.id)
            DownloadRepository.STATE_PAUSED -> mgr.resume(item.id)
            DownloadRepository.STATE_FAILED -> mgr.retry(item.id)
            DownloadRepository.STATE_COMPLETED -> mgr.openFile(item.id)
            DownloadRepository.STATE_PENDING_APPROVAL -> PinGate.show(this) { mgr.approve(item.id) }
            else -> DownloadActionsSheet.show(this, item)
        }
    }

    private fun handleQuickSecondary(item: DownloadRecordEntity) {
        val mgr = ServiceLocator.downloadManager
        when (item.state) {
            DownloadRepository.STATE_COMPLETED -> mgr.shareFile(item.id)
            DownloadRepository.STATE_PENDING_APPROVAL -> PinGate.show(this) { mgr.reject(item.id) }
            else -> mgr.delete(item.id)
        }
    }

    private fun confirmClearFinished() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.downloads_clear_menu)
            .setMessage(R.string.downloads_clear_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                ServiceLocator.downloadManager.clearFinished()
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddUrlDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val urlLayout = TextInputLayout(this).apply {
            hint = getString(R.string.downloads_add_url_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val urlInput = TextInputEditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 3
        }
        // اقتراح تلقائي إذا كانت الحافظة تحتوي رابطًا صالحًا
        val clip = clipboardHttpUrlOrNull()
        if (clip != null) {
            urlInput.setText(clip)
            urlInput.selectAll()
        }
        urlLayout.addView(urlInput)

        val nameLayout = TextInputLayout(this).apply {
            hint = getString(R.string.downloads_add_filename_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val nameInput = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        nameLayout.addView(nameInput)

        container.addView(urlLayout)
        container.addView(
            nameLayout,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.downloads_add_url_title)
            .setView(container)
            .setPositiveButton(R.string.downloads_add_start) { _, _ ->
                val rawUrl = urlInput.text?.toString()?.trim().orEmpty()
                val customName = nameInput.text?.toString()?.trim()
                if (rawUrl.isNotBlank()) {
                    ServiceLocator.downloadManager.enqueueManualDownload(rawUrl, customName)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clipboardHttpUrlOrNull(): String? {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: return null
            if (text.startsWith("http://", true) || text.startsWith("https://", true)) text else null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        enum class FileType(val iconRes: Int, val colorRes: Int) {
            VIDEO(R.drawable.ic_file_video, R.color.fileVideo),
            AUDIO(R.drawable.ic_file_audio, R.color.fileAudio),
            IMAGE(R.drawable.ic_file_image, R.color.fileImage),
            ARCHIVE(R.drawable.ic_file_archive, R.color.fileArchive),
            APK(R.drawable.ic_file_apk, R.color.fileApk),
            DOC(R.drawable.ic_file_doc, R.color.fileDoc),
            OTHER(R.drawable.ic_download, R.color.colorPrimary)
        }

        fun fileTypeOf(fileName: String, mimeType: String?): FileType {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mime = mimeType?.lowercase().orEmpty()
            return when {
                ext in setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "flv") ||
                    mime.startsWith("video/") -> FileType.VIDEO
                ext in setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus") ||
                    mime.startsWith("audio/") -> FileType.AUDIO
                ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic") ||
                    mime.startsWith("image/") -> FileType.IMAGE
                ext in setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso") ||
                    mime.contains("zip") || mime.contains("compressed") || mime.contains("rar") -> FileType.ARCHIVE
                ext in setOf("apk", "apks", "xapk") ||
                    mime == "application/vnd.android.package-archive" -> FileType.APK
                ext in setOf("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "txt", "csv", "epub", "odt", "rtf") ||
                    mime.contains("pdf") || mime.startsWith("text/") -> FileType.DOC
                else -> FileType.OTHER
            }
        }

        fun formatSize(ctx: Context, bytes: Long): String = when {
            bytes >= 1L shl 30 -> ctx.getString(R.string.size_gb, bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> ctx.getString(R.string.size_mb, bytes.toDouble() / (1L shl 20))
            bytes >= 1L shl 10 -> ctx.getString(R.string.size_kb, bytes.toDouble() / (1L shl 10))
            else -> ctx.getString(R.string.size_bytes, bytes)
        }
    }
}

class DownloadsAdapter(
    private val onPrimaryClick: (DownloadRecordEntity) -> Unit,
    private val onQuickPrimary: (DownloadRecordEntity) -> Unit,
    private val onQuickSecondary: (DownloadRecordEntity) -> Unit,
    private val onMore: (DownloadRecordEntity) -> Unit
) : ListAdapter<DownloadRecordEntity, DownloadsAdapter.Holder>(DIFF) {

    class Holder(val binding: ItemDownloadRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDownloadRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val ctx = b.root.context

        b.downloadName.text = item.fileName

        val type = DownloadsActivity.fileTypeOf(item.fileName, item.mimeType)
        b.downloadTile.backgroundTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, type.colorRes))
        b.downloadTypeIcon.setImageResource(type.iconRes)
        b.downloadTypeIcon.setColorFilter(ContextCompat.getColor(ctx, android.R.color.white))

        val totalBytes = item.sizeBytes?.takeIf { it > 0 }
        val doneBytes = (item.downloadedBytes ?: 0L).coerceAtLeast(0L)
        val percent = if (totalBytes != null) {
            ((doneBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else 0

        val sizeInfo = when {
            totalBytes != null && doneBytes > 0 && item.state != DownloadRepository.STATE_COMPLETED ->
                "${DownloadsActivity.formatSize(ctx, doneBytes)} / ${DownloadsActivity.formatSize(ctx, totalBytes)}"
            totalBytes != null -> DownloadsActivity.formatSize(ctx, totalBytes)
            doneBytes > 0 -> DownloadsActivity.formatSize(ctx, doneBytes)
            else -> ""
        }

        val live = if (item.state == DownloadRepository.STATE_DOWNLOADING) {
            ServiceLocator.downloadManager.getLiveMetrics(item.id)
        } else null

        val hostName = item.sourceUrl?.let {
            runCatching { Uri.parse(it).host }.getOrNull()
        }?.takeIf { it.isNotBlank() }

        b.downloadMeta.text = buildString {
            if (item.state == DownloadRepository.STATE_DOWNLOADING) {
                if (totalBytes != null) append("$percent%")
                if (sizeInfo.isNotEmpty()) {
                    if (isNotEmpty()) append(" • ")
                    append(sizeInfo)
                }
                if (live != null && live.bytesPerSecond > 0) {
                    if (isNotEmpty()) append(" • ")
                    append(ctx.getString(R.string.downloads_speed_fmt, DownloadsActivity.formatSize(ctx, live.bytesPerSecond)))
                    if (live.etaSeconds > 0) {
                        append(" • ")
                        val eta = live.etaSeconds
                        if (eta >= 60) {
                            append(ctx.getString(R.string.downloads_eta_min, eta / 60, eta % 60))
                        } else {
                            append(ctx.getString(R.string.downloads_eta_sec, eta))
                        }
                    }
                }
            } else {
                if (sizeInfo.isNotEmpty()) append(sizeInfo)
                if (isNotEmpty()) append(" • ")
                append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", item.timestamp))
            }
            if (hostName != null) {
                append("\n")
                append(hostName)
            }
        }

        val stateColor = when (item.state) {
            DownloadRepository.STATE_COMPLETED -> R.color.chipDone
            DownloadRepository.STATE_FAILED, DownloadRepository.STATE_BLOCKED -> R.color.chipBlocked
            DownloadRepository.STATE_PENDING_APPROVAL -> R.color.chipPending
            else -> R.color.colorPrimary
        }
        b.downloadState.text = stateLabel(ctx, item.state)
        b.downloadState.backgroundTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, stateColor))

        val showProgress = item.state == DownloadRepository.STATE_DOWNLOADING ||
            item.state == DownloadRepository.STATE_PAUSED
        b.downloadProgress.isVisible = showProgress
        if (showProgress) {
            val isIndeterminate = item.state == DownloadRepository.STATE_DOWNLOADING && totalBytes == null
            b.downloadProgress.isIndeterminate = isIndeterminate
            if (!isIndeterminate) {
                b.downloadProgress.progress = percent
            }
        }

        // إعداد أزرار الإجراءات السريعة المباشرة على البطاقة
        b.badgeSavedPublic.isVisible = item.state == DownloadRepository.STATE_COMPLETED
        when (item.state) {
            DownloadRepository.STATE_DOWNLOADING -> {
                b.quickActionsRow.isVisible = true
                b.btnQuickPrimary.isVisible = true
                b.btnQuickPrimary.setText(R.string.download_pause)
                b.btnQuickPrimary.setIconResource(R.drawable.ic_pause)
                b.btnQuickSecondary.isVisible = true
                b.btnQuickSecondary.setText(R.string.cancel)
                b.btnQuickSecondary.setIconResource(R.drawable.ic_close)
            }
            DownloadRepository.STATE_PAUSED -> {
                b.quickActionsRow.isVisible = true
                b.btnQuickPrimary.isVisible = true
                b.btnQuickPrimary.setText(R.string.download_resume)
                b.btnQuickPrimary.setIconResource(R.drawable.ic_play)
                b.btnQuickSecondary.isVisible = true
                b.btnQuickSecondary.setText(R.string.delete)
                b.btnQuickSecondary.setIconResource(R.drawable.ic_delete)
            }
            DownloadRepository.STATE_FAILED -> {
                b.quickActionsRow.isVisible = true
                b.btnQuickPrimary.isVisible = true
                b.btnQuickPrimary.setText(R.string.download_retry)
                b.btnQuickPrimary.setIconResource(R.drawable.ic_reload)
                b.btnQuickSecondary.isVisible = true
                b.btnQuickSecondary.setText(R.string.delete)
                b.btnQuickSecondary.setIconResource(R.drawable.ic_delete)
            }
            DownloadRepository.STATE_PENDING_APPROVAL -> {
                b.quickActionsRow.isVisible = true
                b.btnQuickPrimary.isVisible = true
                b.btnQuickPrimary.setText(R.string.download_approve)
                b.btnQuickPrimary.setIconResource(R.drawable.ic_shield_check)
                b.btnQuickSecondary.isVisible = true
                b.btnQuickSecondary.setText(R.string.download_reject)
                b.btnQuickSecondary.setIconResource(R.drawable.ic_close)
            }
            DownloadRepository.STATE_COMPLETED -> {
                b.quickActionsRow.isVisible = true
                b.btnQuickPrimary.isVisible = true
                b.btnQuickPrimary.setText(R.string.download_open)
                b.btnQuickPrimary.setIconResource(R.drawable.ic_open_new)
                b.btnQuickSecondary.isVisible = false
            }
            else -> {
                b.quickActionsRow.isVisible = false
            }
        }

        b.btnQuickPrimary.setOnClickListener { onQuickPrimary(item) }
        b.btnQuickSecondary.setOnClickListener { onQuickSecondary(item) }
        b.btnDownloadMore.setOnClickListener { onMore(item) }
        b.root.setOnClickListener { onPrimaryClick(item) }
    }

    private fun stateLabel(ctx: Context, state: String): String = when (state) {
        DownloadRepository.STATE_DOWNLOADING -> ctx.getString(R.string.state_downloading)
        DownloadRepository.STATE_COMPLETED -> ctx.getString(R.string.state_completed)
        DownloadRepository.STATE_PAUSED -> ctx.getString(R.string.state_paused)
        DownloadRepository.STATE_FAILED -> ctx.getString(R.string.state_failed)
        DownloadRepository.STATE_BLOCKED -> ctx.getString(R.string.state_blocked)
        DownloadRepository.STATE_PENDING_APPROVAL -> ctx.getString(R.string.state_pending_approval)
        else -> state
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadRecordEntity>() {
            override fun areItemsTheSame(oldItem: DownloadRecordEntity, newItem: DownloadRecordEntity): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: DownloadRecordEntity, newItem: DownloadRecordEntity): Boolean =
                oldItem == newItem && newItem.state != DownloadRepository.STATE_DOWNLOADING
        }
    }
}

/**
 * لوحة الإجراءات الكاملة للتنزيل (v1.13.0) — مبنية بأسلوب صفوف القوائم الاحترافية الموحد.
 */
object DownloadActionsSheet {

    fun show(activity: AppCompatActivity, download: DownloadRecordEntity) {
        val manager = ServiceLocator.downloadManager
        val sheet = BottomSheetDialog(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 18)
        }

        val pad = (18 * activity.resources.displayMetrics.density).toInt()
        val headerTitle = TextView(activity).apply {
            text = download.fileName
            setPadding(pad, 0, pad, pad / 3)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        container.addView(headerTitle)

        fun addRow(iconRes: Int, label: String, onClick: () -> Unit) {
            val row = ItemMenuRowBinding.inflate(activity.layoutInflater, container, false)
            row.rowIcon.setImageResource(iconRes)
            row.rowText.text = label
            row.root.setOnClickListener {
                sheet.dismiss()
                onClick()
            }
            container.addView(row.root)
        }

        when (download.state) {
            DownloadRepository.STATE_PENDING_APPROVAL -> {
                addRow(R.drawable.ic_shield_check, activity.getString(R.string.download_approve)) {
                    PinGate.show(activity) { manager.approve(download.id) }
                }
                addRow(R.drawable.ic_close, activity.getString(R.string.download_reject)) {
                    PinGate.show(activity) { manager.reject(download.id) }
                }
            }
            DownloadRepository.STATE_DOWNLOADING -> {
                addRow(R.drawable.ic_pause, activity.getString(R.string.download_pause)) {
                    manager.pause(download.id)
                }
                addRow(R.drawable.ic_close, activity.getString(R.string.downloads_cancel_active)) {
                    manager.delete(download.id)
                }
            }
            DownloadRepository.STATE_PAUSED -> {
                addRow(R.drawable.ic_play, activity.getString(R.string.download_resume)) {
                    manager.resume(download.id)
                }
            }
            DownloadRepository.STATE_FAILED -> {
                addRow(R.drawable.ic_reload, activity.getString(R.string.download_retry)) {
                    manager.retry(download.id)
                }
            }
            DownloadRepository.STATE_COMPLETED -> {
                addRow(R.drawable.ic_open_new, activity.getString(R.string.download_open)) {
                    manager.openFile(download.id)
                }
                addRow(R.drawable.ic_share, activity.getString(R.string.download_share)) {
                    manager.shareFile(download.id)
                }
                addRow(R.drawable.ic_edit, activity.getString(R.string.downloads_rename_action)) {
                    showRenameDialog(activity, download)
                }
                addRow(R.drawable.ic_download, activity.getString(R.string.download_export_public)) {
                    manager.exportToPublicDownloads(download.id) { success, path ->
                        val msg = if (success && path != null) {
                            "${activity.getString(R.string.toast_download_saved_public)}\n($path)"
                        } else {
                            activity.getString(R.string.toast_download_failed, download.fileName)
                        }
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    }
                }
                addRow(R.drawable.ic_folder, activity.getString(R.string.download_location)) {
                    manager.fileLocation(download.id) { path ->
                        Toast.makeText(
                            activity,
                            path ?: activity.getString(R.string.toast_no_app),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        if (download.url.startsWith("http", ignoreCase = true)) {
            addRow(R.drawable.ic_copy, activity.getString(R.string.downloads_copy_url)) {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("download_url", download.url))
                Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }

        if (download.state != DownloadRepository.STATE_DOWNLOADING) {
            addRow(R.drawable.ic_delete, activity.getString(R.string.delete)) {
                manager.delete(download.id)
            }
        }

        sheet.setContentView(container)
        sheet.show()
    }

    private fun showRenameDialog(activity: AppCompatActivity, download: DownloadRecordEntity) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val wrapper = android.widget.FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
        }
        val layout = TextInputLayout(activity).apply {
            hint = activity.getString(R.string.downloads_rename_title)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val input = TextInputEditText(activity).apply {
            setText(download.fileName)
            selectAll()
            maxLines = 1
        }
        layout.addView(input)
        wrapper.addView(layout)

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.downloads_rename_title)
            .setView(wrapper)
            .setPositiveButton(R.string.ok) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isNotBlank()) {
                    ServiceLocator.downloadManager.renameDownload(download.id, newName) { ok ->
                        Toast.makeText(
                            activity,
                            if (ok) R.string.downloads_rename_success else R.string.downloads_rename_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
