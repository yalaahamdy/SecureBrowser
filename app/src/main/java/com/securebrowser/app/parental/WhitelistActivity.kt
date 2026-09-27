package com.securebrowser.app.parental

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.securebrowser.app.R
import com.securebrowser.app.browser.ruleTypeLabel
import com.securebrowser.app.data.db.entity.WhiteListRuleEntity
import com.securebrowser.app.data.repository.AddOption
import com.securebrowser.app.databinding.ActivitySimpleListBinding
import com.securebrowser.app.databinding.ItemRuleBinding
import com.securebrowser.app.di.ServiceLocator
import com.securebrowser.app.security.whitelist.RuleType
import com.securebrowser.app.security.whitelist.SubdomainPolicy
import kotlinx.coroutines.launch

/**
 * قسم Security → Allowed Sites (§22) — إدارة مواقع القائمة البيضاء:
 * عرض + بحث + تعديل النطاق (Edit) + حذف (Remove) + الإضافة من [AddWebsiteActivity].
 * كل التعديلات من هنا فقط — المستخدم العادي لا يملك أي مسار للقائمة.
 */
class WhitelistActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySimpleListBinding
    private var allRules: List<WhiteListRuleEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimpleListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarTitle.text = getString(R.string.card_whitelist)
        binding.btnToolbarBack.setOnClickListener { finish() }
        binding.btnToolbarAction.isVisible = false

        // §22 Search — تصفية فورية على host
        binding.searchLayout.isVisible = true
        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                renderRules(s?.toString().orEmpty().trim())
            }
        })

        binding.recycler.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            ServiceLocator.database.whiteListRuleDao().observeAll().collect { rules ->
                allRules = rules
                renderRules(binding.searchInput.text?.toString().orEmpty().trim())
            }
        }
    }

    private fun renderRules(filter: String) {
        val rules = if (filter.isEmpty()) allRules
        else allRules.filter { it.host.contains(filter, ignoreCase = true) }
        binding.recycler.adapter = RulesAdapter(rules,
            onEdit = { rule -> showEditScopeDialog(rule) },
            onDelete = { rule -> confirmDelete(rule) }
        )
        binding.emptyText.isVisible = rules.isEmpty()
    }

    /** §22 Edit — تغيير نطاق السماح من خيارات المواصفة الثلاثة. */
    private fun showEditScopeDialog(rule: WhiteListRuleEntity) {
        val options = arrayOf(
            getString(R.string.add_whitelist_site_only),
            getString(R.string.add_whitelist_all_paths),
            getString(R.string.add_whitelist_subdomains)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rule_edit_title, rule.host))
            .setItems(options) { _, which ->
                val (type, subdomains) = when (which) {
                    0 -> RuleType.ALLOW_EXACT to SubdomainPolicy.EXACT_HOST_ONLY
                    1 -> RuleType.ALLOW_DOMAIN to SubdomainPolicy.EXACT_HOST_ONLY
                    else -> RuleType.ALLOW_DOMAIN to SubdomainPolicy.INCLUDE_SUBDOMAINS
                }
                lifecycleScope.launch {
                    ServiceLocator.whiteListRepository.updateScope(rule.id, type, subdomains)
                    ServiceLocator.whiteListEngine.refresh()
                    Toast.makeText(this@WhitelistActivity, R.string.rule_edited, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.delete) { _, _ -> confirmDelete(rule) }
            .show()
    }

    private fun confirmDelete(rule: WhiteListRuleEntity) {
        AlertDialog.Builder(this)
            .setTitle(rule.host)
            .setMessage(getString(R.string.delete))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    ServiceLocator.whiteListRepository.delete(rule.toDomainRule())
                    ServiceLocator.whiteListEngine.refresh()
                    Toast.makeText(this@WhitelistActivity, R.string.history_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

private fun WhiteListRuleEntity.toDomainRule() = com.securebrowser.app.security.whitelist.WhiteListRule(
    id = id, host = host, path = path, scheme = scheme, port = port,
    type = runCatching { com.securebrowser.app.security.whitelist.RuleType.valueOf(ruleType) }
        .getOrElse { com.securebrowser.app.security.whitelist.RuleType.ALLOW_DOMAIN },
    subdomainPolicy = runCatching { com.securebrowser.app.security.whitelist.SubdomainPolicy.valueOf(subdomainPolicy) }
        .getOrElse { com.securebrowser.app.security.whitelist.SubdomainPolicy.EXACT_HOST_ONLY },
    enabled = enabled, createdAt = createdAt, updatedAt = updatedAt
)

class RulesAdapter(
    private val rules: List<WhiteListRuleEntity>,
    private val onEdit: (WhiteListRuleEntity) -> Unit,
    private val onDelete: (WhiteListRuleEntity) -> Unit
) : RecyclerView.Adapter<RulesAdapter.Holder>() {

    class Holder(val binding: ItemRuleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = rules.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val rule = rules[position]
        val ctx = holder.binding.root.context
        holder.binding.ruleHost.text = rule.host
        holder.binding.ruleHost.textSize = 14f
        // v1.7.0 — أيقونة الموقع الحقيقية بدل الدرع الثابت
        val letter = com.securebrowser.app.ui.FaviconLoader.initialFor(rule.host)
        holder.binding.ruleLetter.text = letter
        com.securebrowser.app.ui.FaviconLoader.bind(
            holder.binding.root, holder.binding.ruleIcon, holder.binding.ruleLetter,
            rule.host, letter
        )
        holder.binding.ruleTypeText.text = ruleTypeLabel(
            ctx,
            runCatching { com.securebrowser.app.security.whitelist.RuleType.valueOf(rule.ruleType) }
                .getOrElse { com.securebrowser.app.security.whitelist.RuleType.ALLOW_DOMAIN },
            runCatching { com.securebrowser.app.security.whitelist.SubdomainPolicy.valueOf(rule.subdomainPolicy) }
                .getOrElse { com.securebrowser.app.security.whitelist.SubdomainPolicy.EXACT_HOST_ONLY }
        )
        holder.binding.btnDeleteRule.setOnClickListener { onDelete(rule) }
        holder.binding.root.setOnClickListener { onEdit(rule) }
    }
}
