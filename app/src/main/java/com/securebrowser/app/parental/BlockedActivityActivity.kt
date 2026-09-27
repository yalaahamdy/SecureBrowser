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
import com.securebrowser.app.data.db.entity.BlockedActivityEntity
import com.securebrowser.app.databinding.ActivitySimpleListBinding
import com.securebrowser.app.databinding.ItemBlockedRowBinding
import com.securebrowser.app.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * قسم Monitoring → Blocked Activity (§11):
 * الموقع المطلوب، الوقت، سبب الرفض، ونوع الطلب — بأسلوب واضح بسيط.
 */
class BlockedActivityActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySimpleListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimpleListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarTitle.text = getString(R.string.blocked_title)
        binding.btnToolbarBack.setOnClickListener { finish() }
        binding.btnToolbarAction.isVisible = true
        binding.btnToolbarAction.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.card_clear_blocked))
                .setMessage(R.string.confirm_clear_blocked)
                .setPositiveButton(R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        ServiceLocator.blockedActivityRepository.clearAll()
                        android.widget.Toast.makeText(
                            this@BlockedActivityActivity, R.string.cleared, android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        lifecycleScope.launch {
            ServiceLocator.blockedActivityRepository.observeRecent(300).collect { entries ->
                binding.recycler.adapter = BlockedAdapter(entries)
                binding.emptyText.isVisible = entries.isEmpty()
            }
        }
    }
}

class BlockedAdapter(
    private val entries: List<BlockedActivityEntity>
) : RecyclerView.Adapter<BlockedAdapter.Holder>() {

    class Holder(val binding: ItemBlockedRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemBlockedRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        val ctx = holder.binding.root.context
        holder.binding.blockedHost.text = entry.host ?: entry.url ?: "—"
        holder.binding.blockedReason.text = reasonLabel(ctx, entry.reason)
        holder.binding.blockedTime.text = buildString {
            append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", entry.timestamp))
            entry.navigationType?.let { append(" • " + ctx.getString(R.string.blocked_request_type, it)) }
        }
    }

    private fun reasonLabel(ctx: android.content.Context, reason: String): String = when (reason) {
        "NOT_WHITELISTED" -> ctx.getString(R.string.blocked_reason_not_whitelisted)
        "UNSAFE_SCHEME" -> ctx.getString(R.string.blocked_reason_unsafe_scheme)
        "MALFORMED_URL" -> ctx.getString(R.string.blocked_reason_malformed)
        "EMBEDDED_CREDENTIALS" -> ctx.getString(R.string.blocked_reason_credentials)
        "SECURITY_ERROR" -> ctx.getString(R.string.blocked_reason_security_error)
        "DOWNLOAD_POLICY" -> ctx.getString(R.string.blocked_reason_download_policy)
        "DOWNLOAD_BLOCKED_DANGEROUS" -> ctx.getString(R.string.blocked_reason_download_blocked)
        "DOWNLOAD_BLOCK_SOURCE_NOT_ALLOWED" -> ctx.getString(R.string.blocked_reason_download_source)
        "EXTERNAL_APPS_DISABLED" -> ctx.getString(R.string.blocked_reason_external_disabled)
        "PAGE_ERROR" -> ctx.getString(R.string.blocked_reason_page_error)
        else -> reason
    }
}
