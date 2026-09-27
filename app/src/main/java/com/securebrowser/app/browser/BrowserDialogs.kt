package com.securebrowser.app.browser

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.securebrowser.app.R
import com.securebrowser.app.data.repository.AddOption
import com.securebrowser.app.data.repository.SettingsRepository
import com.securebrowser.app.databinding.DialogAddWhitelistBinding
import com.securebrowser.app.databinding.ItemMenuRowBinding
import com.securebrowser.app.databinding.ItemTabCardBinding
import com.securebrowser.app.databinding.SheetSiteInfoBinding
import com.securebrowser.app.databinding.SheetTabsBinding
import com.securebrowser.app.di.ServiceLocator
import com.securebrowser.app.parental.ParentalActivity
import com.securebrowser.app.security.whitelist.RuleType
import com.securebrowser.app.security.whitelist.SubdomainPolicy
import com.securebrowser.app.ui.PinGate
import kotlinx.coroutines.launch

/**
 * قوائم وحوارات المتصفح — مرحلة 2 (Bottom Sheets بأسلوب Mobile-first).
 * كل الوظائف هنا تنفّذ قرارات أمنية جاهزة ولا تقرر الأمن بنفسها.
 */

/** الصف الواحد في القوائم/اللوحات. */
data class MenuRow(
    val iconRes: Int,
    val text: String,
    val value: String? = null,
    val onClick: () -> Unit
)

private fun buildMenuDialog(
    activity: BrowserActivity,
    title: String?,
    rows: List<MenuRow>
): BottomSheetDialog {
    val container = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, if (title == null) 12 else 0, 0, 12)
    }
    if (title != null) {
        val titleView = android.widget.TextView(activity).apply {
            text = title
            val pad = (18 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad / 2)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        container.addView(titleView)
    }
    val sheet = BottomSheetDialog(activity)
    for (row in rows) {
        val binding = ItemMenuRowBinding.inflate(activity.layoutInflater, container, false)
        binding.rowIcon.setImageResource(row.iconRes)
        binding.rowText.text = row.text
        if (row.value != null) {
            binding.rowValue.isVisible = true
            binding.rowValue.text = row.value
        }
        binding.root.setOnClickListener {
            sheet.dismiss()
            row.onClick()
        }
        container.addView(binding.root)
    }
    sheet.setContentView(container)
    return sheet
}

/** القائمة الرئيسية للمتصفح — الوظائف الثانوية كلها هنا لا على الشاشة. */
fun BrowserActivity.showBrowserMenu() {
    val rows = mutableListOf<MenuRow>()
    val temp = ServiceLocator.temporaryAccess
    if (temp.isActive()) {
        rows.add(
            MenuRow(R.drawable.ic_timer, getString(R.string.temp_banner_label)) {
                showParentalDashboard()
            }
        )
    }
    rows.add(MenuRow(R.drawable.ic_add, getString(R.string.menu_new_tab)) { onNewTabRequested() })
    rows.add(MenuRow(R.drawable.ic_search, getString(R.string.menu_find)) { startFindInPage() })
    rows.add(MenuRow(R.drawable.ic_globe, getString(R.string.menu_zoom)) { showZoomMenu() })
    rows.add(MenuRow(R.drawable.ic_share, getString(R.string.menu_share)) { sharePage() })
    rows.add(MenuRow(R.drawable.ic_download, getString(R.string.menu_downloads)) { openDownloads() })
    rows.add(MenuRow(R.drawable.ic_history, getString(R.string.menu_history)) { showQuickHistory() })
    rows.add(MenuRow(R.drawable.ic_info, getString(R.string.menu_site_info)) { showSiteInfo() })
    rows.add(
        MenuRow(R.drawable.ic_shield_check, getString(R.string.menu_add_whitelist)) {
            val url = currentTabUrl() ?: return@MenuRow
            PinGate.show(this) { showAddToWhitelistDialog(url) }
        }
    )
    rows.add(MenuRow(R.drawable.ic_gear, getString(R.string.menu_settings)) { openChildSettings() })
    rows.add(MenuRow(R.drawable.ic_key, getString(R.string.menu_parental)) { showParentalDashboard() })
    rows.add(MenuRow(R.drawable.ic_close, getString(R.string.menu_exit)) { finishAffinity() })
    buildMenuDialog(this, null, rows).show()
}

/** قائمة التكبير — أزرار التكبير تعيش هنا لا أمام المستخدم دائمًا (§17). */
fun BrowserActivity.showZoomMenu() {
    val settings = ServiceLocator.settingsRepository
    val current = settings.zoom
    val rows = listOf(
        MenuRow(R.drawable.ic_zoom_in, getString(R.string.menu_zoom_in), "$current%") {
            changeZoom(+10)
        },
        MenuRow(R.drawable.ic_zoom_out, getString(R.string.menu_zoom_out), null) {
            changeZoom(-10)
        },
        MenuRow(R.drawable.ic_reload, getString(R.string.menu_zoom_reset), null) {
            changeZoom(reset = true)
        }
    )
    buildMenuDialog(this, getString(R.string.menu_zoom), rows).show()
}

/**
 * لوحة التبويبات الاحترافية (v1.5.0) — حية تمامًا (§5):
 * - **تحديث فوري:** اللوحة مسجلة كمستمع على TabsManager ما دامت مفتوحة —
 *   إغلاق/إضافة/تبديل أي تبويب ينعكس على الشبكة والعدّاد لحظيًا بلا إغلاق
 *   اللوحة وإعادة فتحها (كانت تلتقط لقطة ثابتة في v1.4.0).
 * - **معاينات احترافية:** عند الفتح يُلتقط النشط فورًا ثم بقية التبويبات
 *   تدريجيًا بخطوات صغيرة (بلا تجميد الإطار) — كل بطاقة تُحدث وحدها.
 * - **إزالة متحركة:** إغلاق بطاقة ينزلق بأنيميشن عبر notifyItemRemoved.
 */
fun BrowserActivity.showTabsSheet() {
    val sheet = BottomSheetDialog(this)
    val binding = SheetTabsBinding.inflate(layoutInflater)
    sheet.setContentView(binding.root)

    val adapter = TabsGridAdapter(
        tabsProvider = { tabsManager().tabs },
        activeIdProvider = { tabsManager().activeTab?.id },
        onSwitch = { tabId ->
            tabsManager().switchTo(tabId)
            attachActiveTabForDialog()
            syncAddressBarForDialog()
            sheet.dismiss()
        },
        onClose = { tabId ->
            // الإغلاق عبر المدير — المستمع الحي المُسجل أدناه يزامن الشبكة
            // والعدّاد فورًا (قبل v1.5.0 كانت اللقطة القديمة تبقى معروضة)
            tabsManager().closeTab(tabId)
            attachActiveTabForDialog()
            syncAddressBarForDialog()
        }
    )
    binding.tabsRecycler.layoutManager = GridLayoutManager(this, 2)
    binding.tabsRecycler.adapter = adapter

    fun refreshHeader() {
        binding.tabsCountChip.text = getString(R.string.tabs_count_fmt, tabsManager().tabs.size)
    }
    refreshHeader()

    // مستمع حي: كل تغيير في التبويبات أثناء فتح اللوحة يظهر لحظيًا
    val liveListener = tabsManager().addOnTabsChangedListener {
        adapter.sync()
        refreshHeader()
    }

    binding.btnNewTab.setOnClickListener {
        sheet.dismiss()
        onNewTabRequested()
    }
    sheet.setOnDismissListener {
        tabsManager().removeOnTabsChangedListener(liveListener)
    }
    sheet.show()

    // التقاط معاينات: النشط فورًا ثم بقية التبويبات تدريجيًا (بلا تجميد الإطار)
    lifecycleScope.launch {
        kotlinx.coroutines.delay(60)
        tabsManager().activeTab?.capturePreview()
        adapter.sync()
        kotlinx.coroutines.delay(80)
        for (tab in tabsManager().tabs) {
            tab.capturePreview()
            val index = adapter.positionOf(tab.id)
            if (index >= 0) adapter.notifyItemChanged(index)
            kotlinx.coroutines.delay(24)
        }
    }
}

/** معلومات الموقع — بسيطة وواضحة (§23). */
fun BrowserActivity.showSiteInfo() {
    val raw = currentTabUrl()
    if (raw.isNullOrBlank()) {
        Toast.makeText(this, R.string.start_title, Toast.LENGTH_SHORT).show()
        return
    }
    lifecycleScope.launch {
        ServiceLocator.whiteListEngine.refresh()
        val parsed = com.securebrowser.app.core.url.UrlNormalizer.normalize(raw)
        val url = (parsed as? com.securebrowser.app.core.url.NormalizeResult.Success)?.url
        val sheet = BottomSheetDialog(this@showSiteInfo)
        val infoBinding = SheetSiteInfoBinding.inflate(layoutInflater)
        sheet.setContentView(infoBinding.root)

        infoBinding.siteSecurity.text =
            if (url?.scheme == "https") getString(R.string.site_info_https)
            else getString(R.string.site_info_http)
        infoBinding.siteDomain.text = url?.host ?: raw.take(120)

        val rule = url?.let { ServiceLocator.whiteListEngine.firstMatch(it) }
        val tempActive = ServiceLocator.temporaryAccess.isActive()
        when {
            rule != null -> {
                infoBinding.siteWhitelist.text = getString(R.string.site_info_allowed)
                infoBinding.siteRuleType.text = ruleTypeLabel(this@showSiteInfo, rule.type, rule.subdomainPolicy)
            }
            tempActive -> {
                infoBinding.siteWhitelist.text = getString(R.string.site_info_temp_access)
                infoBinding.siteRuleType.text = ""
            }
            else -> {
                infoBinding.siteWhitelist.text = getString(R.string.site_info_not_allowed)
                infoBinding.siteRuleType.text = ""
            }
        }
        sheet.show()
    }
}

fun ruleTypeLabel(context: android.content.Context, type: RuleType, subdomains: SubdomainPolicy): String = when {
    type == RuleType.ALLOW_EXACT -> context.getString(R.string.rule_type_exact)
    type == RuleType.ALLOW_PATH_PREFIX -> context.getString(R.string.rule_type_path)
    subdomains == SubdomainPolicy.INCLUDE_SUBDOMAINS -> context.getString(R.string.rule_type_subdomains)
    else -> context.getString(R.string.rule_type_domain)
}

/** حوار "ما الذي تريد السماح به؟" — الخيارات الثلاثة من المواصفة (§7). */
fun BrowserActivity.showAddToWhitelistDialog(rawUrl: String) {
    val binding = DialogAddWhitelistBinding.inflate(layoutInflater)
    lifecycleScope.launch {
        val parsed = com.securebrowser.app.core.url.UrlNormalizer.normalize(rawUrl)
        val url = (parsed as? com.securebrowser.app.core.url.NormalizeResult.Success)?.url
        if (url == null || !url.isWeb) {
            Toast.makeText(this@showAddToWhitelistDialog, R.string.add_whitelist_error, Toast.LENGTH_SHORT).show()
            return@launch
        }
        binding.whitelistHostPreview.text = url.host

        val dialog = MaterialAlertDialogBuilder(this@showAddToWhitelistDialog)
            .setTitle(R.string.menu_add_whitelist)
            .setView(binding.root)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val option = when {
                    binding.optSiteOnly.isChecked -> AddOption.SITE_ONLY
                    binding.optAllPaths.isChecked -> AddOption.ALL_PATHS
                    else -> AddOption.WITH_SUBDOMAINS
                }
                lifecycleScope.launch {
                    val result = ServiceLocator.whiteListRepository.addSiteRule(url.host, option)
                    ServiceLocator.whiteListEngine.refresh()
                    if (result.isSuccess) {
                        Toast.makeText(this@showAddToWhitelistDialog, R.string.add_whitelist_done, Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this@showAddToWhitelistDialog, R.string.add_whitelist_error, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }
}

/** سجل سريع للطفل (قراءة فقط — لا مسح/حذف) مع إجراءات لكل عنصر (§9-§10). */
fun BrowserActivity.showQuickHistory() {
    val sheet = BottomSheetDialog(this)
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 14, 0, 12)
    }
    val title = android.widget.TextView(this).apply {
        text = getString(R.string.history_title)
        val pad = (18 * resources.displayMetrics.density).toInt()
        setPadding(pad, 0, pad, pad / 2)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    container.addView(title)

    val recycler = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@showQuickHistory)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            resources.displayMetrics.heightPixels / 2
        )
    }
    container.addView(recycler)
    sheet.setContentView(container)
    sheet.show()

    lifecycleScope.launch {
        val entries = ServiceLocator.historyRepository.recent(100)
        recycler.adapter = HistoryRowAdapter(
            entries,
            showDelete = false,
            onOpen = { entry -> sheet.dismiss(); navigateFromExternal(entry.url, newTab = false) },
            onMore = { entry -> sheet.dismiss(); showHistoryActions(entry.url) }
        )
        if (entries.isEmpty()) {
            container.addView(android.widget.TextView(this@showQuickHistory).apply {
                text = getString(R.string.history_empty)
                val pad = (18 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            })
        }
    }
}

/** لوحة إجراءات عنصر السجل: فتح/تبويب جديد/نسخ/مشاركة/إضافة للقائمة/معلومات (§9). */
fun BrowserActivity.showHistoryActions(url: String) {
    val rows = listOf(
        MenuRow(R.drawable.ic_open_new, getString(R.string.history_open)) {
            navigateFromExternal(url, newTab = false)
        },
        MenuRow(R.drawable.ic_add, getString(R.string.history_open_new_tab)) {
            navigateFromExternal(url, newTab = true)
        },
        MenuRow(R.drawable.ic_copy, getString(R.string.history_copy_url)) {
            copyToClipboard(url)
        },
        MenuRow(R.drawable.ic_share, getString(R.string.history_share)) { shareText(url) },
        MenuRow(R.drawable.ic_shield_check, getString(R.string.history_add_whitelist)) {
            PinGate.show(this) { showAddToWhitelistDialog(url) }
        },
        MenuRow(R.drawable.ic_info, getString(R.string.history_site_info)) {
            showSiteInfoFor(url)
        }
    )
    buildMenuDialog(this, null, rows).show()
}

/** معلومات موقع لعنوان خارج التبويب الحالي (من إجراءات السجل). */
fun BrowserActivity.showSiteInfoFor(url: String) {
    lifecycleScope.launch {
        ServiceLocator.whiteListEngine.refresh()
        val parsed = com.securebrowser.app.core.url.UrlNormalizer.normalize(url)
        val infoUrl = (parsed as? com.securebrowser.app.core.url.NormalizeResult.Success)?.url
        val rule = infoUrl?.let { ServiceLocator.whiteListEngine.firstMatch(it) }
        val message = buildString {
            append(getString(R.string.site_info_security) + ": ")
            append(
                if (infoUrl?.scheme == "https") getString(R.string.site_info_https)
                else getString(R.string.site_info_http)
            )
            append("\n")
            append(getString(R.string.site_info_domain) + ": ")
            append(infoUrl?.host ?: url.take(100))
            append("\n")
            append(getString(R.string.site_info_whitelist) + ": ")
            if (rule != null) {
                append(getString(R.string.site_info_allowed))
                append("\n")
                append(getString(R.string.site_info_rule) + ": ")
                append(ruleTypeLabel(this@showSiteInfoFor, rule.type, rule.subdomainPolicy))
            } else if (ServiceLocator.temporaryAccess.isActive()) {
                append(getString(R.string.site_info_temp_access))
            } else {
                append(getString(R.string.site_info_not_allowed))
            }
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@showSiteInfoFor)
            .setTitle(R.string.site_info_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}

/** لوحة الوالدين — تفتح بعد تحقق الرمز (§6). */
fun BrowserActivity.showParentalDashboard() {
    PinGate.show(this) {
        startActivity(android.content.Intent(this, ParentalActivity::class.java))
    }
}

fun BrowserActivity.openDownloads() {
    startActivity(
        android.content.Intent(this, com.securebrowser.app.parental.DownloadsActivity::class.java)
    )
}

fun BrowserActivity.openChildSettings() {
    startActivity(android.content.Intent(this, SettingsActivity::class.java))
}
