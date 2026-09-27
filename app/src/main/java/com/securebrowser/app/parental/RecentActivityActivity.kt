package com.securebrowser.app.parental

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.securebrowser.app.R
import com.securebrowser.app.databinding.ActivitySimpleListBinding
import com.securebrowser.app.databinding.ItemRecentRowBinding
import com.securebrowser.app.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * قسم Monitoring → Recent Activity (§28):
 * خط زمني موحّد بسيط: 10:42 — example.com opened …
 * (فتح صفحة / حظر / تنزيل) — عرض فقط بلا تحليلات معقدة.
 */
class RecentActivityActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySimpleListBinding

    data class TimelineEntry(
        val timestamp: Long,
        val label: String,
        val detail: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimpleListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarTitle.text = getString(R.string.card_recent)
        binding.btnToolbarBack.setOnClickListener { finish() }
        binding.btnToolbarAction.isVisible = false
        binding.recycler.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            val history = ServiceLocator.historyRepository.recent(80)
            val blocked = ServiceLocator.blockedActivityRepository.recent(80)
            val downloads = ServiceLocator.downloadRepository.recent(80)

            val entries = mutableListOf<TimelineEntry>()
            for (h in history) {
                entries.add(TimelineEntry(h.timestamp, getString(R.string.recent_opened), h.host ?: h.url))
            }
            for (b in blocked) {
                entries.add(TimelineEntry(b.timestamp, getString(R.string.recent_blocked), b.host ?: b.url ?: ""))
            }
            for (d in downloads) {
                when (d.state) {
                    com.securebrowser.app.data.repository.DownloadRepository.STATE_COMPLETED ->
                        entries.add(TimelineEntry(d.timestamp, getString(R.string.recent_downloaded), d.fileName))
                    com.securebrowser.app.data.repository.DownloadRepository.STATE_BLOCKED ->
                        entries.add(TimelineEntry(d.timestamp, getString(R.string.recent_download_blocked), d.fileName))
                    else -> Unit
                }
            }
            entries.sortByDescending { it.timestamp }

            binding.recycler.adapter = TimelineAdapter(entries.take(80))
            binding.emptyText.isVisible = entries.isEmpty()
        }
    }
}

class TimelineAdapter(
    private val entries: List<RecentActivityActivity.TimelineEntry>
) : RecyclerView.Adapter<TimelineAdapter.Holder>() {

    class Holder(val binding: ItemRecentRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemRecentRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        holder.binding.recentTime.text =
            android.text.format.DateFormat.format("HH:mm", entry.timestamp).toString()
        holder.binding.recentIcon.text = entry.label
        holder.binding.recentText.text = entry.detail
    }
}
