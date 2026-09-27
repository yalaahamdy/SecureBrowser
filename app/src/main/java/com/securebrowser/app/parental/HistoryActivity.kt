package com.securebrowser.app.parental

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.securebrowser.app.R
import com.securebrowser.app.browser.BrowserActivity
import com.securebrowser.app.browser.HistoryGrouper
import com.securebrowser.app.browser.HistoryRowAdapter
import com.securebrowser.app.browser.showHistoryActions
import com.securebrowser.app.data.db.entity.HistoryEntity
import com.securebrowser.app.databinding.ActivitySimpleListBinding
import com.securebrowser.app.databinding.SectionHeaderBinding
import com.securebrowser.app.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * قسم Monitoring → History (§9-§10) — نسخة الوالدين:
 * تجميع زمني (اليوم/أمس/هذا الأسبوع/أقدم)، إجراءات كل عنصر،
 * حذف عنصر ومسح كامل (الوالد فقط). الطفل يرى نسخة قراءة فقط من المتصفح.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySimpleListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimpleListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarTitle.text = getString(R.string.card_history)
        binding.btnToolbarBack.setOnClickListener { finish() }
        binding.btnToolbarAction.isVisible = true

        binding.btnToolbarAction.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.card_clear_history))
                .setMessage(R.string.confirm_clear_history)
                .setPositiveButton(R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        ServiceLocator.historyRepository.clearAll()
                        Toast.makeText(this@HistoryActivity, R.string.cleared, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.recycler.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            ServiceLocator.historyRepository.observeRecent(500).collect { entries ->
                val rows = HistoryGrouper.buildRows(entries) { group ->
                    when (group) {
                        HistoryGrouper.Group.TODAY -> getString(R.string.history_group_today)
                        HistoryGrouper.Group.YESTERDAY -> getString(R.string.history_group_yesterday)
                        HistoryGrouper.Group.THIS_WEEK -> getString(R.string.history_group_week)
                        HistoryGrouper.Group.OLDER -> getString(R.string.history_group_older)
                    }
                }
                binding.recycler.adapter = GroupedHistoryAdapter(rows)
                binding.emptyText.isVisible = entries.isEmpty()
            }
        }
    }
}

/** محول مزدوج: ترويسة مجموعة + صف سجل بإجراءات الوالدين. */
class GroupedHistoryAdapter(
    private val rows: List<HistoryGrouper.Row>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class HeaderHolder(val binding: SectionHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class ItemHolder(val binding: com.securebrowser.app.databinding.ItemHistoryRowBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is HistoryGrouper.Row.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderHolder(SectionHeaderBinding.inflate(inflater, parent, false))
        } else {
            ItemHolder(com.securebrowser.app.databinding.ItemHistoryRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HistoryGrouper.Row.Header -> (holder as HeaderHolder).binding.sectionTitle.text = row.title
            is HistoryGrouper.Row.Item -> {
                val entry = row.entry
                val binding = (holder as ItemHolder).binding
                val ctx = binding.root.context
                val title = entry.title?.takeIf { it.isNotBlank() } ?: entry.host ?: entry.url
                binding.historyTitle.text = title
                binding.historyTile.text =
                    (entry.host ?: title).trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                binding.historySubtitle.text =
                    android.text.format.DateFormat.format("HH:mm", entry.timestamp).toString() +
                        (if (entry.visitResult == com.securebrowser.app.data.repository.HistoryRepository.RESULT_TEMPORARY)
                            " • " + ctx.getString(R.string.history_temp_badge) else "")
                binding.root.setOnClickListener { openInBrowser(ctx, entry) }
                binding.btnMore.setOnClickListener {
                    (ctx as? AppCompatActivity)?.let { activity ->
                        // إجراءات العنصر نفسها المتاحة للطفل + حذف للوالد
                        showParentActions(activity, entry)
                    }
                }
            }
        }
    }

    private fun openInBrowser(ctx: android.content.Context, entry: HistoryEntity) {
        ctx.startActivity(
            Intent(ctx, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_OPEN_URL, entry.url)
        )
    }

    private fun showParentActions(activity: AppCompatActivity, entry: HistoryEntity) {
        val options = arrayOf(
            activity.getString(R.string.history_open),
            activity.getString(R.string.history_open_new_tab),
            activity.getString(R.string.history_copy_url),
            activity.getString(R.string.history_share),
            activity.getString(R.string.history_add_whitelist),
            activity.getString(R.string.history_site_info),
            activity.getString(R.string.delete)
        )
        AlertDialog.Builder(activity)
            .setTitle(entry.host ?: entry.url.take(60))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openInBrowser(activity, entry)
                    1 -> openInBrowser(activity, entry) // تبويب جديد يعالجه المتصفح عبر القائمة الأمنية
                    2 -> {
                        val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("url", entry.url)
                        )
                        Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, entry.url)
                        }
                        activity.startActivity(
                            android.content.Intent.createChooser(intent, null)
                        )
                    }
                    4 -> showAddWhitelistFromParent(activity, entry.url)
                    5 -> showSiteInfoFromParent(activity, entry.url)
                    6 -> {
                        lifecycleScope(activity).launch {
                            ServiceLocator.historyRepository.delete(entry.id)
                            Toast.makeText(activity, R.string.history_deleted, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddWhitelistFromParent(activity: AppCompatActivity, url: String) {
        val binding = com.securebrowser.app.databinding.DialogAddWhitelistBinding.inflate(activity.layoutInflater)
        val parsed = com.securebrowser.app.core.url.UrlNormalizer.normalize(url)
        val host = (parsed as? com.securebrowser.app.core.url.NormalizeResult.Success)?.url?.host
        if (host == null) {
            Toast.makeText(activity, R.string.add_whitelist_error, Toast.LENGTH_SHORT).show()
            return
        }
        binding.whitelistHostPreview.text = host
        AlertDialog.Builder(activity)
            .setTitle(R.string.menu_add_whitelist)
            .setView(binding.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val option = when {
                    binding.optSiteOnly.isChecked ->
                        com.securebrowser.app.data.repository.AddOption.SITE_ONLY
                    binding.optAllPaths.isChecked ->
                        com.securebrowser.app.data.repository.AddOption.ALL_PATHS
                    else -> com.securebrowser.app.data.repository.AddOption.WITH_SUBDOMAINS
                }
                lifecycleScope(activity).launch {
                    ServiceLocator.whiteListRepository.addSiteRule(host, option)
                    ServiceLocator.whiteListEngine.refresh()
                    Toast.makeText(activity, R.string.add_whitelist_done, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSiteInfoFromParent(activity: AppCompatActivity, url: String) {
        val parsed = com.securebrowser.app.core.url.UrlNormalizer.normalize(url)
        val success = parsed as? com.securebrowser.app.core.url.NormalizeResult.Success
        val host = success?.url?.host ?: url.take(80)
        val https = success?.url?.scheme == "https"
        AlertDialog.Builder(activity)
            .setTitle(R.string.site_info_title)
            .setMessage(
                buildString {
                    append(activity.getString(R.string.site_info_security) + ": ")
                    append(activity.getString(if (https) R.string.site_info_https else R.string.site_info_http))
                    append("\n")
                    append(activity.getString(R.string.site_info_domain) + ": ")
                    append(host)
                }
            )
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun lifecycleScope(activity: AppCompatActivity) = activity.lifecycleScope
}
