package com.securebrowser.app.parental

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.securebrowser.app.R
import com.securebrowser.app.browser.BrowserActivity
import com.securebrowser.app.databinding.ActivityParentalBinding
import com.securebrowser.app.databinding.ItemMenuRowBinding
import com.securebrowser.app.databinding.RowActionCardBinding
import com.securebrowser.app.di.ServiceLocator
import com.securebrowser.app.ui.PinGate
import kotlinx.coroutines.launch

/**
 * لوحة الوالدين — مغلقة افتراضيًا، الدخول يتطلب الرمز (§6)،
 * قفل تلقائي بعد مدة خمول قابلة للضبط (§27)، وحالة النظام بنظرة واحدة (§26).
 */
class ParentalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentalBinding
    private val lockHandler = Handler(Looper.getMainLooper())
    private var lockedOut = true

    private val lockRunnable = object : Runnable {
        override fun run() {
            // قفل تلقائي (§27): خروج + طلب رمز عند العودة
            lockedOut = true
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToolbarBack.setOnClickListener { finish() }
        binding.btnLockNow.setOnClickListener {
            lockedOut = true
            finish()
        }

        // بوابة الرمز — لا محتوى تفاعلي قبل المصادقة
        PinGate.show(this) {
            lockedOut = false
            restartLockTimer()
            renderDashboard()
        }

        // تحديث مؤشر الوصول المؤقت حيًا
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceLocator.temporaryAccess.uiState.collect {
                    if (!lockedOut) updateTempStatus()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!lockedOut) restartLockTimer()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!lockedOut) restartLockTimer()
    }

    /** القفل التلقائي (§27) — المدة من إعدادات الأمان. */
    private fun restartLockTimer() {
        lockHandler.removeCallbacks(lockRunnable)
        val minutes = ServiceLocator.settingsRepository.lockTimeoutMinutes.toLong()
        lockHandler.postDelayed(lockRunnable, minutes * 60_000)
    }

    override fun onDestroy() {
        lockHandler.removeCallbacks(lockRunnable)
        super.onDestroy()
    }

    // ————————————————— اللوحة —————————————————

    private fun renderDashboard() {
        renderStatusCards()
        setupSectionCards()
    }

    private fun statusRow(iconRes: Int, label: String, value: String): View {
        val row = ItemMenuRowBinding.inflate(layoutInflater)
        row.rowIcon.setImageResource(iconRes)
        row.rowText.text = label
        row.rowValue.isVisible = true
        row.rowValue.text = value
        row.root.isClickable = false
        row.root.isFocusable = false
        return row.root
    }

    private fun renderStatusCards() {
        val settings = ServiceLocator.settingsRepository
        binding.statusContainer.removeAllViews()
        binding.statusContainer.addView(
            statusRow(R.drawable.ic_shield_check, getString(R.string.status_whitelist), getString(R.string.value_on))
        )
        val tempRow = statusRow(
            R.drawable.ic_timer,
            getString(R.string.status_temp_access),
            getString(R.string.value_off)
        )
        tempRow.tag = "temp_row"
        binding.statusContainer.addView(tempRow)
        binding.statusContainer.addView(
            statusRow(R.drawable.ic_history, getString(R.string.status_history), getString(R.string.value_enabled))
        )
        binding.statusContainer.addView(
            statusRow(R.drawable.ic_download, getString(R.string.status_downloads), getString(R.string.value_monitoring_on))
        )
        binding.statusContainer.addView(
            statusRow(
                R.drawable.ic_globe,
                getString(R.string.status_external_apps),
                if (settings.externalApps) getString(R.string.value_allowed) else getString(R.string.value_blocked)
            )
        )
        binding.statusContainer.addView(
            statusRow(
                R.drawable.ic_shield_block,
                getString(R.string.status_apk),
                when (settings.apkPolicy) {
                    "allow" -> getString(R.string.value_allowed)
                    "approval" -> getString(R.string.state_pending_approval)
                    else -> getString(R.string.value_blocked)
                }
            )
        )
    }

    private fun updateTempStatus() {
        val temp = ServiceLocator.temporaryAccess
        // صف الوصول المؤقت يُعاد بناؤه في مكانه (الثاني دائمًا)
        val existing = binding.statusContainer.getChildAt(1)
        val remaining = temp.remainingMs()
        val value = if (temp.isActive()) {
            val totalSec = remaining / 1000
            String.format(java.util.Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
        } else {
            getString(R.string.value_off)
        }
        if (existing != null && existing.tag == "temp_row") {
            (existing as? android.view.ViewGroup)?.let { group ->
                val valueView = group.getChildAt(2) as? android.widget.TextView
                valueView?.text = value
            }
            return
        }
        val row = statusRow(R.drawable.ic_timer, getString(R.string.status_temp_access), value)
        row.tag = "temp_row"
        binding.statusContainer.removeView(existing)
        binding.statusContainer.addView(row, 1)
    }

    private fun setupSectionCards() {
        bindCard(binding.cardWhitelist, R.drawable.ic_shield_check, getString(R.string.card_whitelist), getString(R.string.card_whitelist_sub)) {
            startActivity(Intent(this, WhitelistActivity::class.java))
        }
        bindCard(binding.cardAddWebsite, R.drawable.ic_add, getString(R.string.card_add_website), getString(R.string.card_add_website_sub)) {
            startActivity(Intent(this, AddWebsiteActivity::class.java))
        }
        bindCard(binding.cardTempAccess, R.drawable.ic_timer, getString(R.string.card_temp_access), getString(R.string.card_temp_access_sub)) {
            startActivity(Intent(this, TempAccessActivity::class.java))
        }
        bindCard(binding.cardHistory, R.drawable.ic_history, getString(R.string.card_history), getString(R.string.card_history_sub)) {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        bindCard(binding.cardBlocked, R.drawable.ic_shield_block, getString(R.string.card_blocked), getString(R.string.card_blocked_sub)) {
            startActivity(Intent(this, BlockedActivityActivity::class.java))
        }
        bindCard(binding.cardDownloads, R.drawable.ic_download, getString(R.string.card_downloads), getString(R.string.card_downloads_sub)) {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        bindCard(binding.cardRecent, R.drawable.ic_timer, getString(R.string.card_recent), getString(R.string.card_recent_sub)) {
            startActivity(Intent(this, RecentActivityActivity::class.java))
        }
        bindCard(binding.cardTabsAndApps, R.drawable.ic_globe, getString(R.string.card_tabs_apps), getString(R.string.card_tabs_apps_sub)) {
            startActivity(Intent(this, ParentalSettingsActivity::class.java))
        }
        bindCard(binding.cardChildAppearance, R.drawable.ic_gear, getString(R.string.card_child_appearance), getString(R.string.card_child_appearance_sub)) {
            startActivity(Intent(this, com.securebrowser.app.browser.SettingsActivity::class.java))
        }
        bindCard(binding.cardSecuritySettings, R.drawable.ic_key, getString(R.string.card_security_settings), getString(R.string.card_security_settings_sub)) {
            startActivity(Intent(this, ParentalSettingsActivity::class.java))
        }
        // ترويسات الأقسام
        binding.secSecurity.sectionTitle.text = getString(R.string.parental_section_security)
        binding.secMonitoring.sectionTitle.text = getString(R.string.parental_section_monitoring)
        binding.secBrowser.sectionTitle.text = getString(R.string.parental_section_browser)
        binding.secSecuritySettings.sectionTitle.text = getString(R.string.parental_section_settings)
    }

    private fun bindCard(
        card: RowActionCardBinding,
        iconRes: Int,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ) {
        card.cardIcon.setImageResource(iconRes)
        card.cardTitle.text = title
        card.cardValue.text = subtitle
        card.cardValue.textSize = 12f
        card.root.setOnClickListener { onClick() }
    }
}
