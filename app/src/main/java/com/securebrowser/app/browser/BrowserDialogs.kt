package com.securebrowser.app.browser

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.securebrowser.app.R
import com.securebrowser.app.core.url.NormalizeResult
import com.securebrowser.app.core.url.UrlNormalizer
import com.securebrowser.app.data.repository.AddOption
import com.securebrowser.app.data.repository.SettingsRepository
import com.securebrowser.app.databinding.DialogActionsListBinding
import com.securebrowser.app.databinding.DialogAddWhitelistBinding
import com.securebrowser.app.databinding.DialogInstallShortcutBinding
import com.securebrowser.app.databinding.DialogQuickHistoryBinding
import com.securebrowser.app.databinding.DialogTabsSwitcherBinding
import com.securebrowser.app.databinding.ItemMenuRowBinding
import com.securebrowser.app.databinding.PopupBrowserMenuBinding
import com.securebrowser.app.databinding.PopupSiteInfoBinding
import com.securebrowser.app.di.ServiceLocator
import com.securebrowser.app.parental.ParentalActivity
import com.securebrowser.app.parental.YoutubeChannelsActivity
import com.securebrowser.app.security.whitelist.RuleType
import com.securebrowser.app.security.whitelist.SubdomainPolicy
import com.securebrowser.app.security.youtube.YoutubeUrlParser
import com.securebrowser.app.ui.FaviconLoader
import com.securebrowser.app.ui.PinGate
import kotlinx.coroutines.launch

/**
 * قوائم وحوارات المتصفح العصرية (v1.15.1):
 * - قائمة المتصفح ومعلومات الموقع: بطاقات منبثقة عائمة مرتبطة بأزرار الشريط العلوي (Popover Cards).
 * - لوحة التبويبات: نافذة عائمة واسعة بملء مريح ومعاينات فورية.
 * - السجل السريع وإجراءات العناصر: حوارات وسطية نظيفة مع بحث فوري وبدون قوائم سفلية مزعجة.
 */

/** الصف الواحد في القوائم. */
data class MenuRow(
    val iconRes: Int,
    val text: String,
    val value: String? = null,
    val isDanger: Boolean = false,
    val onClick: () -> Unit
)

private fun dpToPx(activity: BrowserActivity, dp: Int): Int =
    (dp * activity.resources.displayMetrics.density).toInt()

/** القائمة الرئيسية العائمة للمتصفح — تنبثق مباشرة أسفل زر القائمة في الشريط العلوي. */
fun BrowserActivity.showBrowserMenu() {
    val binding = PopupBrowserMenuBinding.inflate(layoutInflater)
    val maxPopupHeight = (resources.displayMetrics.heightPixels * 0.80f).toInt()
    val popup = PopupWindow(
        binding.root,
        dpToPx(this, 304),
        ViewGroup.LayoutParams.WRAP_CONTENT,
        true
    ).apply {
        isOutsideTouchable = true
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        elevation = dpToPx(this@showBrowserMenu, 10).toFloat()
        animationStyle = android.R.style.Animation_Dialog
    }

    // شريط الاختصارات العلوية السريعة
    binding.qaNewTab.setOnClickListener { popup.dismiss(); onNewTabRequested() }
    binding.qaFind.setOnClickListener { popup.dismiss(); startFindInPage() }
    binding.qaShare.setOnClickListener { popup.dismiss(); sharePage() }
    binding.qaSiteInfo.setOnClickListener { popup.dismiss(); showSiteInfo() }

    // شريط التكبير الحي المدمج (تعديل فوري دون إغلاق القائمة)
    fun refreshZoomLabel() {
        binding.txtZoomValue.text = "${ServiceLocator.settingsRepository.zoom}%"
    }
    refreshZoomLabel()
    binding.btnZoomInInline.setOnClickListener {
        changeZoom(+10)
        refreshZoomLabel()
    }
    binding.btnZoomOutInline.setOnClickListener {
        changeZoom(-10)
        refreshZoomLabel()
    }
    binding.txtZoomValue.setOnClickListener {
        changeZoom(reset = true)
        refreshZoomLabel()
    }

    // بناء الإجراءات الثانوية النظيفة
    val rows = mutableListOf<MenuRow>()
    val temp = ServiceLocator.temporaryAccess
    if (temp.isActive()) {
        rows.add(
            MenuRow(R.drawable.ic_timer, getString(R.string.temp_banner_label)) {
                showParentalDashboard()
            }
        )
    }
    rows.add(MenuRow(R.drawable.ic_qr, getString(R.string.menu_scan_qr)) { openQrScanner() })

    val currentUrl = currentTabUrl()
    if (WebShortcutManager.canPinUrl(currentUrl)) {
        rows.add(
            MenuRow(R.drawable.ic_install_shortcut, getString(R.string.menu_install_shortcut)) {
                promptInstallShortcut(currentUrl!!)
            }
        )
    }
    rows.add(MenuRow(R.drawable.ic_download, getString(R.string.menu_downloads)) { openDownloads() })
    rows.add(MenuRow(R.drawable.ic_history, getString(R.string.menu_history)) { showQuickHistory() })
    rows.add(
        MenuRow(R.drawable.ic_play, getString(R.string.menu_youtube_channels)) {
            PinGate.show(this) {
                startActivity(android.content.Intent(this, YoutubeChannelsActivity::class.java))
            }
        }
    )
    rows.add(
        MenuRow(R.drawable.ic_shield_check, getString(R.string.menu_add_whitelist)) {
            val url = currentTabUrl() ?: return@MenuRow
            PinGate.show(this) { showAddToWhitelistDialog(url) }
        }
    )
    rows.add(MenuRow(R.drawable.ic_gear, getString(R.string.menu_settings)) { openChildSettings() })
    rows.add(MenuRow(R.drawable.ic_key, getString(R.string.menu_parental)) { showParentalDashboard() })
    rows.add(MenuRow(R.drawable.ic_close, getString(R.string.menu_exit), isDanger = true) { finishAffinity() })

    for (row in rows) {
        val item = ItemMenuRowBinding.inflate(layoutInflater, binding.menuItemsContainer, false)
        item.rowIcon.setImageResource(row.iconRes)
        item.rowText.text = row.text
        if (row.isDanger) {
            val errColor = com.google.android.material.color.MaterialColors.getColor(
                item.root,
                androidx.appcompat.R.attr.colorError
            )
            item.rowIcon.setColorFilter(errColor)
            item.rowText.setTextColor(errColor)
        }
        if (row.value != null) {
            item.rowValue.isVisible = true
            item.rowValue.text = row.value
        }
        item.root.setOnClickListener {
            popup.dismiss()
            row.onClick()
        }
        binding.menuItemsContainer.addView(item.root)
    }

    binding.root.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(dpToPx(this, 304), android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(maxPopupHeight, android.view.View.MeasureSpec.AT_MOST)
    )
    if (binding.root.measuredHeight > maxPopupHeight) {
        popup.height = maxPopupHeight
    }

    val anchor = menuAnchorView()
    popup.showAsDropDown(anchor, 0, dpToPx(this, 4), Gravity.END)
}

/** قائمة التكبير السريعة — لم تعد تُستدعى منفصلة لأن شريط التكبير مدمج حيًا في القائمة، لكن تبقى متاحة إن استُدعيت. */
fun BrowserActivity.showZoomMenu() {
    showBrowserMenu()
}

/**
 * لوحة التبويبات الحديثة (v1.15.1) — نافذة بطاقات عائمة رحبة بإدارة فورية وبلا BottomSheet.
 */
fun BrowserActivity.showTabsSheet() {
    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val binding = DialogTabsSwitcherBinding.inflate(layoutInflater)
    dialog.setContentView(binding.root)

    val dm = resources.displayMetrics
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(
            (dm.widthPixels * 0.94f).toInt(),
            (dm.heightPixels * 0.80f).toInt()
        )
        setWindowAnimations(android.R.style.Animation_Dialog)
    }

    val adapter = TabsGridAdapter(
        tabsProvider = { tabsManager().tabs },
        activeIdProvider = { tabsManager().activeTab?.id },
        onSwitch = { tabId ->
            tabsManager().activeTab?.capturePreview()
            tabsManager().switchTo(tabId)
            attachActiveTabForDialog()
            syncAddressBarForDialog()
            dialog.dismiss()
        },
        onClose = { tabId ->
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

    val liveListener = tabsManager().addOnTabsChangedListener {
        adapter.sync()
        refreshHeader()
    }

    binding.btnCloseTabs.setOnClickListener { dialog.dismiss() }
    binding.btnNewTab.setOnClickListener {
        dialog.dismiss()
        onNewTabRequested()
    }
    dialog.setOnDismissListener {
        tabsManager().removeOnTabsChangedListener(liveListener)
    }
    tabsManager().activeTab?.capturePreview()
    dialog.show()
}

/** بطاقة معلومات الموقع المنسدلة أسفل أيقونة الدرع في الشريط العلوي. */
fun BrowserActivity.showSiteInfo() {
    val raw = currentTabUrl()
    if (raw.isNullOrBlank()) {
        Toast.makeText(this, R.string.start_title, Toast.LENGTH_SHORT).show()
        return
    }
    lifecycleScope.launch {
        ServiceLocator.whiteListEngine.refresh()
        val parsed = UrlNormalizer.normalize(raw)
        val url = (parsed as? NormalizeResult.Success)?.url
        val infoBinding = PopupSiteInfoBinding.inflate(layoutInflater)
        val popup = PopupWindow(
            infoBinding.root,
            dpToPx(this@showSiteInfo, 308),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dpToPx(this@showSiteInfo, 10).toFloat()
            animationStyle = android.R.style.Animation_Dialog
        }

        infoBinding.siteSecurity.text =
            if (url?.scheme == "https") getString(R.string.site_info_https)
            else getString(R.string.site_info_http)
        infoBinding.siteDomain.text = url?.host ?: raw.take(120)

        val rule = url?.let { ServiceLocator.whiteListEngine.firstMatch(it) }
        val tempActive = ServiceLocator.temporaryAccess.isActive()
        val isYt = url != null && YoutubeUrlParser.isYoutubeHost(url.host)
        val ytMode = ServiceLocator.settingsRepository.youtubeChannelMode

        when {
            isYt && ytMode && !tempActive -> {
                infoBinding.siteWhitelist.text = getString(R.string.site_info_youtube_mode)
                infoBinding.siteRuleType.text = getString(
                    R.string.start_youtube_channels_count,
                    ServiceLocator.youtubeChannelRepository.enabledChannelIds().size
                )
                infoBinding.btnSiteQuickAction.isVisible = true
                infoBinding.btnSiteQuickAction.setText(R.string.menu_youtube_channels)
                infoBinding.btnSiteQuickAction.setOnClickListener {
                    popup.dismiss()
                    PinGate.show(this@showSiteInfo) {
                        startActivity(android.content.Intent(this@showSiteInfo, YoutubeChannelsActivity::class.java))
                    }
                }
            }
            rule != null -> {
                infoBinding.siteWhitelist.text = getString(R.string.site_info_allowed)
                infoBinding.siteRuleType.text = ruleTypeLabel(this@showSiteInfo, rule.type, rule.subdomainPolicy)
                infoBinding.btnSiteQuickAction.isVisible = false
            }
            tempActive -> {
                infoBinding.siteWhitelist.text = getString(R.string.site_info_temp_access)
                infoBinding.siteRuleType.text = ""
                infoBinding.btnSiteQuickAction.isVisible = false
            }
            else -> {
                infoBinding.siteWhitelist.text = getString(R.string.site_info_not_allowed)
                infoBinding.siteRuleType.text = ""
                infoBinding.btnSiteQuickAction.isVisible = true
                if (isYt) {
                    infoBinding.btnSiteQuickAction.setText(R.string.block_btn_add_youtube_channel)
                    infoBinding.btnSiteQuickAction.setOnClickListener {
                        popup.dismiss()
                        promptAddYoutubeChannelFromDialog(raw)
                    }
                } else {
                    infoBinding.btnSiteQuickAction.setText(R.string.menu_add_whitelist)
                    infoBinding.btnSiteQuickAction.setOnClickListener {
                        popup.dismiss()
                        PinGate.show(this@showSiteInfo) { showAddToWhitelistDialog(raw) }
                    }
                }
            }
        }
        popup.showAsDropDown(siteInfoAnchorView(), 0, dpToPx(this@showSiteInfo, 6), Gravity.START)
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
        val parsed = UrlNormalizer.normalize(rawUrl)
        val url = (parsed as? NormalizeResult.Success)?.url
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

/** سجل التصفح السريع للطفل في حوار عصري مزود ببحث فوري وبدون BottomSheet. */
fun BrowserActivity.showQuickHistory() {
    val dialogBinding = DialogQuickHistoryBinding.inflate(layoutInflater)
    val dialog = MaterialAlertDialogBuilder(this)
        .setView(dialogBinding.root)
        .create()

    dialogBinding.btnCloseHistory.setOnClickListener { dialog.dismiss() }
    dialogBinding.historyRecycler.layoutManager = LinearLayoutManager(this)
    dialog.show()

    lifecycleScope.launch {
        val allEntries = ServiceLocator.historyRepository.recent(100)
        fun render(query: String) {
            val filtered = if (query.isBlank()) allEntries
            else allEntries.filter {
                (it.title ?: "").contains(query, ignoreCase = true) ||
                    it.url.contains(query, ignoreCase = true) ||
                    (it.host ?: "").contains(query, ignoreCase = true)
            }
            if (filtered.isEmpty()) {
                dialogBinding.historyRecycler.isVisible = false
                dialogBinding.historyEmptyText.isVisible = true
            } else {
                dialogBinding.historyEmptyText.isVisible = false
                dialogBinding.historyRecycler.isVisible = true
                dialogBinding.historyRecycler.adapter = HistoryRowAdapter(
                    filtered,
                    showDelete = false,
                    onOpen = { entry -> dialog.dismiss(); navigateFromExternal(entry.url, newTab = false) },
                    onMore = { entry -> dialog.dismiss(); showHistoryActions(entry.url) }
                )
            }
        }
        render("")
        dialogBinding.historySearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                render(s?.toString().orEmpty().trim())
            }
        })
    }
}

/** حوار إجراءات عنصر السجل (بدون قائمة سفلية). */
fun BrowserActivity.showHistoryActions(url: String) {
    val rows = mutableListOf(
        MenuRow(R.drawable.ic_open_new, getString(R.string.history_open)) {
            navigateFromExternal(url, newTab = false)
        },
        MenuRow(R.drawable.ic_add, getString(R.string.history_open_new_tab)) {
            navigateFromExternal(url, newTab = true)
        },
        MenuRow(R.drawable.ic_copy, getString(R.string.history_copy_url)) {
            copyToClipboard(url)
        },
        MenuRow(R.drawable.ic_share, getString(R.string.history_share)) { shareText(url) }
    )
    if (WebShortcutManager.canPinUrl(url)) {
        rows.add(
            MenuRow(R.drawable.ic_install_shortcut, getString(R.string.menu_install_shortcut)) {
                promptInstallShortcut(url)
            }
        )
    }
    rows.add(
        MenuRow(R.drawable.ic_shield_check, getString(R.string.history_add_whitelist)) {
            PinGate.show(this) { showAddToWhitelistDialog(url) }
        }
    )
    rows.add(
        MenuRow(R.drawable.ic_info, getString(R.string.history_site_info)) {
            showSiteInfoFor(url)
        }
    )

    val actionsBinding = DialogActionsListBinding.inflate(layoutInflater)
    actionsBinding.actionsHeaderTitle.text = url
    val dialog = MaterialAlertDialogBuilder(this)
        .setView(actionsBinding.root)
        .setNegativeButton(R.string.close, null)
        .create()

    for (row in rows) {
        val item = ItemMenuRowBinding.inflate(layoutInflater, actionsBinding.actionsContainer, false)
        item.rowIcon.setImageResource(row.iconRes)
        item.rowText.text = row.text
        item.root.setOnClickListener {
            dialog.dismiss()
            row.onClick()
        }
        actionsBinding.actionsContainer.addView(item.root)
    }
    dialog.show()
}

/** معلومات موقع لعنوان خارج التبويب الحالي (من إجراءات السجل). */
fun BrowserActivity.showSiteInfoFor(url: String) {
    lifecycleScope.launch {
        ServiceLocator.whiteListEngine.refresh()
        val parsed = UrlNormalizer.normalize(url)
        val infoUrl = (parsed as? NormalizeResult.Success)?.url
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
        MaterialAlertDialogBuilder(this@showSiteInfoFor)
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

/**
 * حوار تثبيت الموقع كتطبيق على الشاشة الرئيسية (v1.10.0).
 */
fun BrowserActivity.promptInstallShortcut(rawUrl: String, customTitle: String? = null) {
    if (!WebShortcutManager.canPinUrl(rawUrl)) {
        Toast.makeText(this, R.string.shortcut_invalid_url, Toast.LENGTH_SHORT).show()
        return
    }
    if (!WebShortcutManager.isSupported(this)) {
        Toast.makeText(this, R.string.shortcut_unsupported, Toast.LENGTH_LONG).show()
        return
    }

    val host = WebShortcutManager.extractHost(rawUrl)
    val activeTab = tabsManager().activeTab
    val defaultTitle = customTitle?.takeIf { it.isNotBlank() }
        ?: if (activeTab != null && (activeTab.uiState.url == rawUrl || activeTab.attemptedUrl == rawUrl)) {
            activeTab.uiState.title?.takeIf { it.isNotBlank() } ?: host
        } else {
            host
        }

    val binding = DialogInstallShortcutBinding.inflate(layoutInflater)
    binding.shortcutUrlPreview.text = host.ifBlank { rawUrl }
    binding.shortcutTitleInput.setText(defaultTitle)
    binding.shortcutTitleInput.selectAll()

    lifecycleScope.launch {
        ServiceLocator.whiteListEngine.refresh()
        val parsed = UrlNormalizer.normalize(rawUrl)
        val normalizedUrl = (parsed as? NormalizeResult.Success)?.url
        val isAllowed = normalizedUrl?.let { ServiceLocator.whiteListEngine.isAllowed(it) } ?: false
        val isTemp = ServiceLocator.temporaryAccess.isActive()

        when {
            isAllowed -> {
                binding.shortcutSecurityBadge.text = getString(R.string.shortcut_security_badge_allowed)
                binding.shortcutSecurityBadge.setTextColor(ContextCompat.getColor(this@promptInstallShortcut, R.color.chipDone))
            }
            isTemp -> {
                binding.shortcutSecurityBadge.text = getString(R.string.shortcut_security_badge_temp)
                binding.shortcutSecurityBadge.setTextColor(ContextCompat.getColor(this@promptInstallShortcut, R.color.chipPending))
            }
            else -> {
                binding.shortcutSecurityBadge.text = getString(R.string.shortcut_security_badge_unlisted)
                binding.shortcutSecurityBadge.setTextColor(ContextCompat.getColor(this@promptInstallShortcut, R.color.chipBlocked))
            }
        }

        val activeFavicon = if (activeTab?.uiState?.url == rawUrl) activeTab.webView.favicon else null
        val resolvedFavicon = activeFavicon ?: FaviconLoader.load(this@promptInstallShortcut, host)
        val previewBitmap = WebShortcutManager.createAppIcon(this@promptInstallShortcut, host, resolvedFavicon)
        binding.shortcutIconPreview.setImageBitmap(previewBitmap)

        val dialog = MaterialAlertDialogBuilder(this@promptInstallShortcut)
            .setTitle(R.string.shortcut_install_dialog_title)
            .setView(binding.root)
            .setPositiveButton(R.string.shortcut_btn_install, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveBtn.setOnClickListener {
                val inputTitle = binding.shortcutTitleInput.text?.toString()?.trim()
                val finalTitle = if (inputTitle.isNullOrBlank()) defaultTitle else inputTitle

                lifecycleScope.launch {
                    val result = WebShortcutManager.pinWebsite(
                        context = this@promptInstallShortcut,
                        url = rawUrl,
                        customTitle = finalTitle,
                        favicon = resolvedFavicon
                    )
                    when (result) {
                        is PinResult.Success -> {
                            Toast.makeText(
                                this@promptInstallShortcut,
                                getString(R.string.shortcut_pinned_success, result.title),
                                Toast.LENGTH_LONG
                            ).show()
                            dialog.dismiss()
                        }
                        is PinResult.Unsupported -> {
                            Toast.makeText(
                                this@promptInstallShortcut,
                                R.string.shortcut_unsupported,
                                Toast.LENGTH_LONG
                            ).show()
                            dialog.dismiss()
                        }
                        is PinResult.InvalidUrl -> {
                            Toast.makeText(
                                this@promptInstallShortcut,
                                R.string.shortcut_invalid_url,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is PinResult.Error -> {
                            Toast.makeText(
                                this@promptInstallShortcut,
                                result.message,
                                Toast.LENGTH_LONG
                            ).show()
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
