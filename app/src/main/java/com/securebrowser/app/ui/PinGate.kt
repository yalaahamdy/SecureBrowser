package com.securebrowser.app.ui

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.securebrowser.app.R
import com.securebrowser.app.di.ServiceLocator
import com.securebrowser.app.parental.ParentalAuthManager

/**
 * بوابة الرمز الأبوي — تُستخدم قبل أي مسار حساس (لوحة الوالدين، إضافة للقائمة، تغيير الإعدادات).
 *
 * v1.3.0: واجهة لوحة أرقام كاملة ([PinPadView]) بدل الحقل النصي:
 * - إرسال تلقائي عند بلوغ الطول المخزن (تثبيتات جديدة) — بلا زر موافق.
 * - التثبيتات القديمة بلا طول مخزّن: زر تأكيد يدوي + تعلّم الطول بعد أول نجاح.
 * - عدّاد المحاولات المتبقية قبل القفل المؤقت، وعدّاد تنازلي حي أثناء القفل.
 *
 * v1.4.0: **فتح بالبصمة** ([BiometricGate]) — اختياري للوالد من إعدادات الأمان
 * (biometric_unlock، افتراضي معطل لأن جهاز الطفل قد يحمل بصمات أخرى):
 * - عند التفعيل وتوفر البصمة: زر "فتح بالبصمة" تحت اللوحة + عرض تلقائي
 *   للماسح عند فتح البوابة — أسرع مسار للوالد بلا إدخال رقمي.
 * - نجاح البصمة يمر عبر ParentalAuthManager.verifyBiometric — يحترم قفل
 *   المحاولات (القفل أثناءه لا تفتحه بصمة) ويسجل النجاح كأي رمز صحيح.
 *
 * v1.5.0: **حاوية بصرية حديثة** — بطاقة عائمة بزوايا 24dp بلا شريط عنوان
 * نظام ([PinDialogStyle])، وزر البصمة بأيقونة + نمط tonal — تبدو البوابة
 * شاشة قفل مخصصة أنيقة لا حوارًا عامًا.
 *
 * التحقق وحماية brute force تبقى في ParentalAuthManager حصريًا.
 */
object PinGate {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(activity: AppCompatActivity, onSuccess: () -> Unit) {
        if (!ServiceLocator.parentalAuthManager.hasPin()) {
            // لا رمز بعد — لا يمكن فتح المسارات الحساسة بلا حماية
            android.widget.Toast.makeText(activity, R.string.setup_title, android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val auth = ServiceLocator.parentalAuthManager
        val pad = PinPadView(activity).apply {
            setTitle(activity.getString(R.string.pin_dialog_title))
            setSubtitle(activity.getString(R.string.pin_pad_subtitle_verify))
            setTargetLength(auth.storedPinLength())
        }

        // زر البصمة — يظهر فقط عند تفعيل الإعداد + توفر الجهاز/البصمة
        val biometricEnabled = ServiceLocator.settingsRepository.biometricUnlock &&
            BiometricGate.isAvailable(activity)
        val biometricButton = MaterialButton(activity).apply {
            if (biometricEnabled) {
                text = activity.getString(R.string.pin_biometric_button)
                textSize = 14f
                cornerRadius = PinDialogStyle.dp(activity, 16)
                insetTop = PinDialogStyle.dp(activity, 4)
                insetBottom = PinDialogStyle.dp(activity, 4)
                icon = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_fingerprint)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = PinDialogStyle.dp(activity, 8)
                visibility = android.view.View.VISIBLE
            } else {
                visibility = android.view.View.GONE
            }
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = PinDialogStyle.dp(activity, 320)
            val padParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(pad, padParams)
            if (biometricEnabled) {
                val btnParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                val margin = PinDialogStyle.dp(activity, 20)
                btnParams.setMargins(margin, 0, margin, PinDialogStyle.dp(activity, 14))
                addView(biometricButton, btnParams)
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(container)
            .setCancelable(true)
            .create()

        PinDialogStyle.apply(activity, dialog)

        var countdownJob: Runnable? = null

        fun showLocked(remainingMs: Long) {
            pad.setLocked(true)
            biometricButton.isEnabled = false
            var remaining = (remainingMs / 1000L).coerceAtLeast(1L)
            fun tick() {
                pad.setInfo(activity.getString(R.string.parental_locked, remaining))
                if (remaining <= 0L) {
                    pad.setLocked(false)
                    biometricButton.isEnabled = true
                    pad.setInfo(null)
                    return
                }
                remaining -= 1L
                val next = Runnable { tick() }
                countdownJob = next
                mainHandler.postDelayed(next, 1000L)
            }
            tick()
        }

        fun finishSuccess() {
            pad.markSuccess()
            countdownJob?.let { mainHandler.removeCallbacks(it) }
            dialog.dismiss()
            onSuccess()
        }

        // مسار البصمة: تحقق منه عبر مدير المصادقة (يحترم قفل المحاولات)
        fun promptBiometric() {
            if (!biometricEnabled || !auth.hasPin()) return
            val ok = BiometricGate.authenticate(
                activity = activity,
                title = activity.getString(R.string.pin_biometric_title),
                subtitle = activity.getString(R.string.pin_biometric_subtitle),
                negativeText = activity.getString(R.string.pin_biometric_use_pin),
                onSuccess = {
                    when (val result = auth.verifyBiometric()) {
                        is ParentalAuthManager.AuthResult.Success -> finishSuccess()
                        is ParentalAuthManager.AuthResult.Locked -> showLocked(result.remainingMs)
                        else -> Unit
                    }
                },
                onError = { /* المستخدم ألغى — تبقى اللوحة الرقمية */ }
            )
            if (!ok) biometricButton.visibility = android.view.View.GONE
        }

        biometricButton.setOnClickListener { promptBiometric() }

        pad.onComplete = { pin ->
            when (val result = auth.verify(pin)) {
                is ParentalAuthManager.AuthResult.Success -> {
                    finishSuccess()
                }
                is ParentalAuthManager.AuthResult.WrongPin -> {
                    pad.clear(clearStatus = false)
                    val remaining = attemptsRemaining()
                    val message = if (remaining > 0) {
                        "${activity.getString(R.string.parental_wrong)} — " +
                            activity.getString(R.string.pin_pad_attempts_left, remaining)
                    } else {
                        activity.getString(R.string.parental_wrong)
                    }
                    pad.setError(message)
                }
                is ParentalAuthManager.AuthResult.Locked -> {
                    pad.clear()
                    showLocked(result.remainingMs)
                }
            }
        }

        dialog.setOnDismissListener {
            countdownJob?.let { mainHandler.removeCallbacks(it) }
        }
        dialog.show()

        // حالة قفل قائمة مسبقًا قبل أول إدخال
        val preLock = auth.remainingLockMs()
        if (preLock > 0L) {
            showLocked(preLock)
        } else if (biometricEnabled) {
            // أسرع مسار: الماسح يُعرض تلقائيًا عند فتح البوابة — ضغطة واحدة تصبح صفرًا
            mainHandler.postDelayed({ promptBiometric() }, 250L)
        }
    }

    private fun attemptsRemaining(): Int = try {
        ServiceLocator.bruteForceProtector.attemptsRemaining()
    } catch (e: Exception) {
        0
    }
}
