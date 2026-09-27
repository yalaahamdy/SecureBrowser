package com.securebrowser.app.parental

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.securebrowser.app.R
import com.securebrowser.app.databinding.ActivityParentalSettingsBinding
import com.securebrowser.app.databinding.RowActionCardBinding
import com.securebrowser.app.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * قسم Security Settings (§6/§25/§27):
 * الرمز، مدة القفل التلقائي، القواعد المتقدمة (تطبيقات خارجية/مشغلات فيديو/تنزيلات/وسائط/جلسة)،
 * وأدوات بيانات الوالدين (مسح السجل/المحظور). كل تعديل هنا موثق بالرمز مسبقًا.
 *
 * ملاحظة إصلاح v1.2.0: كانت بطاقات هذه الشاشة تظهر **بلا عناوين** (لم يكن cardTitle/cardIcon
 * يُضبطان أبدًا هنا) وبلا قيمة الحالية — فبدت الإعدادات "مخفية/محجوبة". الآن كل بطاقة
 * تعرض أيقونتها وعنوانها وقيمتها الحية، والعناوين تلتف على سطرين بدل القص.
 */
class ParentalSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentalSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentalSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToolbarBack.setOnClickListener { finish() }

        // ترويسات الأقسام
        binding.secPassword.sectionTitle.text = getString(R.string.sec_password)
        binding.secLock.sectionTitle.text = getString(R.string.sec_lock)
        binding.secAdvanced.sectionTitle.text = getString(R.string.sec_advanced)
        binding.secData.sectionTitle.text = getString(R.string.sec_data)

        // ————— كلمة المرور والقفل —————
        bindCard(
            binding.cardChangePin, R.drawable.ic_key, getString(R.string.card_change_pin)
        ) { showChangePinDialog() }
        bindCard(
            binding.cardLockTimeout, R.drawable.ic_timer, getString(R.string.card_lock_timeout)
        ) { showLockTimeoutDialog() }

        // ————— التطبيقات الخارجية —————
        bindCard(
            binding.cardExternalApps, R.drawable.ic_globe, getString(R.string.card_external_apps)
        ) { showChoiceDialog(
            getString(R.string.card_external_apps),
            arrayOf(
                getString(R.string.value_allowed),
                getString(R.string.value_blocked)
            )
        ) { index ->
            lifecycleScope.launch { ServiceLocator.settingsRepository.putBool("external_apps", index == 0) }
        } }
        bindCard(
            binding.cardExternalConfirm, R.drawable.ic_shield_check, getString(R.string.card_external_confirm)
        ) { showChoiceDialog(
            getString(R.string.card_external_confirm),
            arrayOf(getString(R.string.value_on), getString(R.string.value_off))
        ) { index ->
            lifecycleScope.launch { ServiceLocator.settingsRepository.putBool("external_confirm", index == 0) }
        } }
        bindCard(
            binding.cardVideoPlayers, R.drawable.ic_open_new, getString(R.string.card_video_players)
        ) { showChoiceDialog(
            getString(R.string.card_video_players),
            arrayOf(getString(R.string.value_on), getString(R.string.value_off))
        ) { index ->
            lifecycleScope.launch {
                ServiceLocator.settingsRepository.putBool("video_players", index == 0)
            }
        } }

        // v1.4.0: فتح بالبصمة — اختياري، الافتراضي معطل (جهاز الطفل قد يحمل بصمات أخرى)
        bindCard(
            binding.cardBiometric, R.drawable.ic_lock, getString(R.string.card_biometric)
        ) { showBiometricChoice() }

        // ————— سياسة التنزيلات — السماح مع التسجيل هو الافتراضي (طلب الوالد الصريح) —————
        // لا شيء يُحظر افتراضيًا؛ كل تنزيل يُسجَّل في شاشة التنزيلات.
        bindCard(
            binding.cardApkPolicy, R.drawable.ic_shield_block, getString(R.string.card_apk_policy)
        ) { showChoiceDialog(
            getString(R.string.card_apk_policy),
            arrayOf(
                getString(R.string.value_allowed),
                getString(R.string.state_pending_approval),
                getString(R.string.value_blocked)
            )
        ) { index ->
            lifecycleScope.launch {
                ServiceLocator.settingsRepository.put(
                    "apk_policy",
                    when (index) { 0 -> "allow"; 1 -> "approval"; else -> "block" }
                )
            }
        } }
        bindCard(
            binding.cardArchivePolicy, R.drawable.ic_download, getString(R.string.card_archive_policy)
        ) { showChoiceDialog(
            getString(R.string.card_archive_policy),
            arrayOf(
                getString(R.string.value_allowed),
                getString(R.string.state_pending_approval),
                getString(R.string.value_blocked)
            )
        ) { index ->
            lifecycleScope.launch {
                ServiceLocator.settingsRepository.put(
                    "archive_policy",
                    when (index) { 0 -> "allow"; 1 -> "approval"; else -> "block" }
                )
            }
        } }
        bindCard(
            binding.cardUnknownPolicy, R.drawable.ic_folder, getString(R.string.card_unknown_policy)
        ) { showChoiceDialog(
            getString(R.string.card_unknown_policy),
            arrayOf(
                getString(R.string.value_allowed),
                getString(R.string.state_pending_approval),
                getString(R.string.value_blocked)
            )
        ) { index ->
            lifecycleScope.launch {
                ServiceLocator.settingsRepository.put(
                    "unknown_policy",
                    when (index) { 0 -> "allow"; 1 -> "approval"; else -> "block" }
                )
            }
        } }

        // ————— الوسائط والجلسة —————
        bindCard(
            binding.cardRestoreSession, R.drawable.ic_history, getString(R.string.card_restore_session)
        ) { showChoiceDialog(
            getString(R.string.card_restore_session),
            arrayOf(getString(R.string.value_on), getString(R.string.value_off))
        ) { index ->
            lifecycleScope.launch { ServiceLocator.settingsRepository.putBool("restore_session", index == 0) }
        } }
        bindCard(
            binding.cardMediaAutoplay, R.drawable.ic_play, getString(R.string.card_media_autoplay)
        ) { showChoiceDialog(
            getString(R.string.card_media_autoplay),
            arrayOf(getString(R.string.value_on), getString(R.string.value_off))
        ) { index ->
            lifecycleScope.launch {
                ServiceLocator.settingsRepository.putBool("media_autoplay", index == 0)
            }
        } }

        // ————— أدوات البيانات —————
        bindCard(
            binding.cardClearHistory, R.drawable.ic_delete, getString(R.string.card_clear_history)
        ) { confirmClear(
            getString(R.string.confirm_clear_history)
        ) {
            lifecycleScope.launch {
                ServiceLocator.historyRepository.clearAll()
                toast(getString(R.string.cleared))
            }
        } }
        bindCard(
            binding.cardClearBlocked, R.drawable.ic_delete, getString(R.string.card_clear_blocked)
        ) { confirmClear(
            getString(R.string.confirm_clear_blocked)
        ) {
            lifecycleScope.launch {
                ServiceLocator.blockedActivityRepository.clearAll()
                toast(getString(R.string.cleared))
            }
        } }

        refreshValues()
    }

    override fun onResume() {
        super.onResume()
        refreshValues()
    }

    private fun refreshValues() {
        val s = ServiceLocator.settingsRepository
        // القيمة الحالية ظاهرة دائمًا تحت العنوان — لا بطاقة بلا سياق
        binding.cardLockTimeout.cardValue.isVisible = true
        binding.cardLockTimeout.cardValue.text =
            getString(R.string.picker_minutes, s.lockTimeoutMinutes)
        binding.cardExternalApps.cardValue.isVisible = true
        binding.cardExternalApps.cardValue.text =
            getString(if (s.externalApps) R.string.value_allowed else R.string.value_blocked)
        binding.cardExternalConfirm.cardValue.isVisible = true
        binding.cardExternalConfirm.cardValue.text =
            getString(if (s.externalConfirm) R.string.value_on else R.string.value_off)
        binding.cardVideoPlayers.cardValue.isVisible = true
        binding.cardVideoPlayers.cardValue.text =
            getString(if (s.videoPlayers) R.string.value_on else R.string.value_off)
        binding.cardBiometric.cardValue.isVisible = true
        binding.cardBiometric.cardValue.text =
            getString(if (s.biometricUnlock) R.string.value_on else R.string.value_off)
        binding.cardApkPolicy.cardValue.isVisible = true
        binding.cardApkPolicy.cardValue.text = policyLabel(s.apkPolicy)
        binding.cardArchivePolicy.cardValue.isVisible = true
        binding.cardArchivePolicy.cardValue.text = policyLabel(s.archivePolicy)
        binding.cardUnknownPolicy.cardValue.isVisible = true
        binding.cardUnknownPolicy.cardValue.text = policyLabel(s.unknownPolicy)
        binding.cardRestoreSession.cardValue.isVisible = true
        binding.cardRestoreSession.cardValue.text =
            getString(if (s.restoreSession) R.string.value_on else R.string.value_off)
        binding.cardMediaAutoplay.cardValue.isVisible = true
        binding.cardMediaAutoplay.cardValue.text =
            getString(if (s.mediaAutoplay) R.string.value_on else R.string.value_off)
        // بطاقات الأدوات لا تحمل قيمة — الوصف يكفي
        binding.cardClearHistory.cardValue.isVisible = false
        binding.cardClearBlocked.cardValue.isVisible = false
    }

    private fun policyLabel(value: String) = when (value) {
        "allow" -> getString(R.string.value_allowed)
        "block" -> getString(R.string.value_blocked)
        else -> getString(R.string.state_pending_approval)
    }

    /**
     * ربط بطاقة كاملة: أيقونة + عنوان + قيمة حية + إجراء.
     * كانت النسخة السابقة تترك العنوان فارغًا — وهذا هو سبب ظهور الإعدادات "محجوبة العناوين".
     */
    private fun bindCard(
        card: RowActionCardBinding,
        iconRes: Int,
        title: String,
        onClick: () -> Unit
    ) {
        card.cardIcon.setImageResource(iconRes)
        card.cardTitle.text = title
        card.cardValue.isVisible = false
        card.root.setOnClickListener { onClick() }
    }

    private fun showLockTimeoutDialog() {
        val options = intArrayOf(1, 5, 10, 15, 30)
        val labels = options.map { getString(R.string.picker_minutes, it) }.toTypedArray()
        val current = ServiceLocator.settingsRepository.lockTimeoutMinutes
        val checked = options.indexOfFirst { it >= current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.card_lock_timeout))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                lifecycleScope.launch {
                    ServiceLocator.settingsRepository.putInt("lock_timeout_minutes", options[which])
                    refreshValues()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showChoiceDialog(title: String, options: Array<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which -> onPick(which); refreshValues() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * خيار فتح البصمة (v1.4.0): فحص توفر الجهاز أولًا، وعند التفعيل
     * تحذير أمني صريح (أي بصمة مسجلة على الجهاز تستطيع فتح البوابة).
     */
    private fun showBiometricChoice() {
        val current = ServiceLocator.settingsRepository.biometricUnlock
        val options = arrayOf(
            getString(R.string.value_on),
            getString(R.string.value_off)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.card_biometric))
            .setSingleChoiceItems(options, if (current) 0 else 1) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    if (!com.securebrowser.app.ui.BiometricGate.isAvailable(this)) {
                        toast(getString(R.string.biometric_not_available))
                        return@setSingleChoiceItems
                    }
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.card_biometric))
                        .setMessage(getString(R.string.biometric_warning))
                        .setPositiveButton(R.string.ok) { _, _ ->
                            lifecycleScope.launch {
                                ServiceLocator.settingsRepository.putBool("biometric_unlock", true)
                                refreshValues()
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    lifecycleScope.launch {
                        ServiceLocator.settingsRepository.putBool("biometric_unlock", false)
                        refreshValues()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * تغيير الرمز عبر لوحة الأرقام (v1.3.0) — ثلاث مراحل متتابعة:
     * الرمز الحالي (تحقق فوري) → الرمز الجديد (4–12 مع تأكيد) → مطابقته
     * (إرسال تلقائي عند بلوغ الطول). بلا لوحة مفاتيح نظام ولا حقول نص.
     */
    private fun showChangePinDialog() {
        val auth = ServiceLocator.parentalAuthManager

        val pad = com.securebrowser.app.ui.PinPadView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                pad,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .setCancelable(true)
            .create()
        // v1.5.0: بطاقة عائمة بزوايا مستديرة بلا شريط عنوان — تجربة موحدة مع PinGate
        com.securebrowser.app.ui.PinDialogStyle.apply(this, dialog)
        // عنوان المرحلة الأولى داخل اللوحة (لا شريط عنوان نظام)
        pad.setTitle(getString(R.string.pin_change_title))

        var stage = 0 // 0: الحالي، 1: الجديد، 2: التأكيد
        var currentPin = ""
        var newPin = ""

        fun showCurrentStage() {
            stage = 0
            pad.setTitle(getString(R.string.pin_pad_step_current))
            pad.setSubtitle(null)
            pad.setTargetLength(auth.storedPinLength())
        }

        fun showNewStage() {
            stage = 1
            pad.clear()
            pad.setTitle(getString(R.string.pin_pad_step_new))
            pad.setSubtitle(getString(R.string.pin_pad_step_new_hint))
            pad.setTargetLength(null) // اختيار حر 4–12 مع زر تأكيد
        }

        fun showConfirmStage() {
            stage = 2
            pad.clear()
            pad.setTitle(getString(R.string.pin_pad_step_confirm_title))
            pad.setSubtitle(getString(R.string.pin_pad_step_confirm_hint, newPin.length))
            pad.setTargetLength(newPin.length)
        }

        pad.onComplete = { pin ->
            when (stage) {
                0 -> {
                    // تحقق فوري من الرمز الحالي عبر verify (يسجّل محاولة brute-force عادية)
                    when (val r = auth.verify(pin)) {
                        is com.securebrowser.app.parental.ParentalAuthManager.AuthResult.Success -> {
                            currentPin = pin
                            showNewStage()
                        }
                        is com.securebrowser.app.parental.ParentalAuthManager.AuthResult.WrongPin -> {
                            pad.clear(clearStatus = false)
                            pad.setError(getString(R.string.pin_change_wrong_current))
                        }
                        is com.securebrowser.app.parental.ParentalAuthManager.AuthResult.Locked -> {
                            dialog.dismiss()
                            toast(
                                getString(
                                    R.string.parental_locked,
                                    (r.remainingMs / 1000).coerceAtLeast(1)
                                )
                            )
                        }
                    }
                }
                1 -> {
                    newPin = pin
                    showConfirmStage()
                }
                else -> {
                    if (pin == newPin) {
                        val result = auth.changePin(currentPin, newPin, pin)
                        when (result) {
                            com.securebrowser.app.parental.ParentalAuthManager.SetupResult.SUCCESS -> {
                                dialog.dismiss()
                                toast(getString(R.string.pin_changed))
                            }
                            com.securebrowser.app.parental.ParentalAuthManager.SetupResult.LOCKED_OUT -> {
                                dialog.dismiss()
                                toast(
                                    getString(
                                        R.string.parental_locked,
                                        (auth.remainingLockMs() / 1000).coerceAtLeast(1)
                                    )
                                )
                            }
                            com.securebrowser.app.parental.ParentalAuthManager.SetupResult.WrongCurrent -> {
                                newPin = ""
                                showCurrentStage()
                                pad.setError(getString(R.string.pin_change_wrong_current))
                            }
                            else -> {
                                newPin = ""
                                showNewStage()
                                pad.setError(getString(R.string.setup_error_mismatch))
                            }
                        }
                    } else {
                        pad.clear(clearStatus = false)
                        showNewStage()
                        pad.setError(getString(R.string.setup_error_mismatch))
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            // لا حالة خارجية — كل شيء داخل الحوار
        }
        dialog.show()
        showCurrentStage()
    }

    private fun confirmClear(message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
