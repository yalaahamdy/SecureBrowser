package com.securebrowser.app.parental

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.securebrowser.app.R
import com.securebrowser.app.browser.BrowserActivity
import com.securebrowser.app.data.db.entity.YoutubeChannelEntity
import com.securebrowser.app.databinding.ActivityYoutubeChannelsBinding
import com.securebrowser.app.databinding.ItemYoutubeChannelRowBinding
import com.securebrowser.app.di.ServiceLocator
import com.securebrowser.app.security.youtube.YoutubeChannelVerifier
import com.securebrowser.app.security.youtube.YoutubeUrlParser
import kotlinx.coroutines.launch

/**
 * شاشة إدارة قنوات يوتيوب المسموحة في لوحة الوالدين (v1.15.0):
 * - تفعيل/إيقاف وضع القنوات المسموحة.
 * - حظر Shorts وإخفاء التعليقات.
 * - تنبيه وإزالة youtube.com من القائمة البيضاء العامة إن وجد.
 * - إضافة قناة بلصق أي رابط (قناة / @handle / فيديو) مع معاينة وتأكيد قبل الحفظ.
 */
class YoutubeChannelsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityYoutubeChannelsBinding
    private lateinit var adapter: ChannelsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityYoutubeChannelsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToolbarBack.setOnClickListener { finish() }

        setupToggles()
        setupGeneralWhitelistCheck()
        setupAddChannelForm()

        adapter = ChannelsAdapter(
            onToggle = { channel, enabled ->
                lifecycleScope.launch {
                    ServiceLocator.youtubeChannelRepository.setEnabled(channel.channelId, enabled)
                }
            },
            onOpen = { channel ->
                val intent = Intent(this, BrowserActivity::class.java)
                    .putExtra(BrowserActivity.EXTRA_OPEN_URL, "https://m.youtube.com/channel/${channel.channelId}")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(intent)
                finish()
            },
            onDelete = { channel -> confirmDelete(channel) }
        )
        binding.recyclerChannels.layoutManager = LinearLayoutManager(this)
        binding.recyclerChannels.adapter = adapter

        lifecycleScope.launch {
            ServiceLocator.youtubeChannelRepository.observeAll().collect { list ->
                adapter.submitList(list)
                binding.emptyChannelsText.isVisible = list.isEmpty()
            }
        }
    }

    private fun setupToggles() {
        val settings = ServiceLocator.settingsRepository
        binding.switchYoutubeMode.isChecked = settings.youtubeChannelMode
        binding.switchBlockShorts.isChecked = settings.youtubeBlockShorts
        binding.switchHideComments.isChecked = settings.youtubeHideComments

        binding.switchYoutubeMode.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { settings.putBool("youtube_channel_mode", checked) }
        }
        binding.switchBlockShorts.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { settings.putBool("youtube_block_shorts", checked) }
        }
        binding.switchHideComments.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { settings.putBool("youtube_hide_comments", checked) }
        }
    }

    private fun setupGeneralWhitelistCheck() {
        lifecycleScope.launch {
            ServiceLocator.whiteListEngine.refresh()
            val ytRules = ServiceLocator.whiteListRepository.currentRules().filter {
                it.enabled && YoutubeUrlParser.isYoutubeHost(it.host)
            }
            binding.generalWhitelistBanner.isVisible = ytRules.isNotEmpty()
            if (ytRules.isNotEmpty()) {
                val hosts = ytRules.joinToString(", ") { it.host }
                binding.generalWhitelistWarnText.text =
                    getString(R.string.youtube_general_whitelist_warn, hosts)
                binding.btnRemoveGeneralWhitelist.setOnClickListener {
                    lifecycleScope.launch {
                        for (r in ytRules) {
                            ServiceLocator.whiteListRepository.delete(r)
                        }
                        ServiceLocator.whiteListEngine.refresh()
                        binding.generalWhitelistBanner.isVisible = false
                        Toast.makeText(
                            this@YoutubeChannelsActivity,
                            R.string.site_removed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun setupAddChannelForm() {
        binding.btnAddChannel.setOnClickListener { submitChannelInput() }
        binding.inputChannel.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitChannelInput()
                true
            } else false
        }
    }

    private fun submitChannelInput() {
        val raw = binding.inputChannel.text?.toString().orEmpty().trim()
        if (raw.isEmpty()) return

        binding.btnAddChannel.isEnabled = false
        binding.progressAdd.isVisible = true
        lifecycleScope.launch {
            val result = ServiceLocator.youtubeChannelVerifier.resolveChannelFromInput(raw)
            binding.btnAddChannel.isEnabled = true
            binding.progressAdd.isVisible = false
            if (isFinishing || isDestroyed) return@launch

            when (result) {
                is YoutubeChannelVerifier.ChannelResolveResult.Failure -> {
                    Toast.makeText(
                        this@YoutubeChannelsActivity,
                        R.string.youtube_channel_resolve_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
                is YoutubeChannelVerifier.ChannelResolveResult.Success -> {
                    val info = result.info
                    MaterialAlertDialogBuilder(this@YoutubeChannelsActivity)
                        .setTitle(R.string.youtube_confirm_add_title)
                        .setMessage(
                            getString(
                                R.string.youtube_confirm_add_message,
                                info.title,
                                info.handle ?: info.channelId
                            )
                        )
                        .setPositiveButton(R.string.youtube_btn_add_channel) { _, _ ->
                            lifecycleScope.launch {
                                ServiceLocator.youtubeChannelRepository.upsertChannel(
                                    channelId = info.channelId,
                                    handle = info.handle,
                                    title = info.title,
                                    avatarUrl = info.avatarUrl,
                                    enabled = true
                                )
                                // تفعيل وضع القنوات تلقائيًا عند إضافة أول قناة لتعمل الميزة مباشرة
                                if (!ServiceLocator.settingsRepository.youtubeChannelMode) {
                                    ServiceLocator.settingsRepository.putBool("youtube_channel_mode", true)
                                    binding.switchYoutubeMode.isChecked = true
                                }
                                binding.inputChannel.text?.clear()
                                Toast.makeText(
                                    this@YoutubeChannelsActivity,
                                    getString(R.string.youtube_channel_added, info.title),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun confirmDelete(channel: YoutubeChannelEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(channel.title)
            .setMessage(getString(R.string.youtube_channel_delete_confirm, channel.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    ServiceLocator.youtubeChannelRepository.deleteChannel(channel.channelId)
                    Toast.makeText(this@YoutubeChannelsActivity, R.string.history_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class ChannelsAdapter(
        private val onToggle: (YoutubeChannelEntity, Boolean) -> Unit,
        private val onOpen: (YoutubeChannelEntity) -> Unit,
        private val onDelete: (YoutubeChannelEntity) -> Unit
    ) : RecyclerView.Adapter<ChannelsAdapter.Holder>() {

        private var items: List<YoutubeChannelEntity> = emptyList()

        fun submitList(list: List<YoutubeChannelEntity>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(val binding: ItemYoutubeChannelRowBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemYoutubeChannelRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val b = holder.binding
            val ctx = b.root.context

            b.channelLetter.text = item.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "Y"
            b.channelTitle.text = item.title
            b.channelSubtitle.text = buildString {
                append(item.handle ?: item.channelId)
                if (!item.enabled) {
                    append(" • ")
                    append(ctx.getString(R.string.youtube_channel_disabled_badge))
                }
            }

            b.switchChannelEnabled.setOnCheckedChangeListener(null)
            b.switchChannelEnabled.isChecked = item.enabled
            b.switchChannelEnabled.setOnCheckedChangeListener { _, checked ->
                onToggle(item, checked)
            }
            b.btnDeleteChannel.setOnClickListener { onDelete(item) }
            b.root.setOnClickListener { onOpen(item) }
        }
    }
}
