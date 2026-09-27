package com.securebrowser.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.securebrowser.app.R
import com.securebrowser.app.browser.BrowserActivity
import com.securebrowser.app.databinding.ActivitySetupPinBinding
import com.securebrowser.app.di.ServiceLocator
import com.securebrowser.app.parental.ParentalAuthManager

/**
 * إنشاء أول رمز والدين (تشغيل أول) — hash آمن عبر PBKDF2 + تخزين مشفر Keystore.
 *
 * v1.3.0: تجربة إدخال جديدة كاملة عبر [PinPadView]:
 * - مرحلتان: "اختيار الرمز" ثم "تأكيده" — انتقال تلقائي بلا لوحة مفاتيح نظام.
 * - الاختيار (4–12 خانة) بزر تأكيد، والتأكيد يُرسل تلقائيًا عند مطابقة الطول.
 * - عدم التطابق: اهتزاز بصري + رسالة + عودة مباشرة لإعادة الاختيار.
 */
class SetupPinActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupPinBinding
    private var firstPin: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupPinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pinPad.setSubtitle(getString(R.string.setup_subtitle))
        binding.pinPad.setTargetLength(null) // اختيار حر 4–12 مع زر تأكيد

        binding.pinPad.onComplete = { pin ->
            val first = firstPin
            if (first == null) {
                // المرحلة 1: اختيار الرمز
                firstPin = pin
                enterConfirmPhase(pin)
            } else if (pin == first) {
                // المرحلة 2: تأكيد مطابق — إنشاء
                binding.pinPad.markSuccess()
                createPin(first, pin)
            } else {
                // عدم تطابق — رسالة واضحة وعودة للاختيار
                binding.pinPad.clear(clearStatus = false)
                firstPin = null
                enterChoosePhase()
                binding.pinPad.setError(getString(R.string.pin_pad_confirm_mismatch))
            }
        }
        binding.pinPad.onChanged = {
            if (firstPin != null) {
                binding.pinPad.setInfo(null)
            }
        }
    }

    private fun enterChoosePhase() {
        binding.headerText.setText(R.string.setup_title)
        binding.pinPad.setTitle(getString(R.string.setup_title))
        binding.pinPad.setSubtitle(getString(R.string.setup_subtitle))
        binding.pinPad.setTargetLength(null)
    }

    private fun enterConfirmPhase(pin: String) {
        binding.headerText.setText(R.string.pin_pad_step_confirm_title)
        binding.pinPad.setTitle(getString(R.string.pin_pad_step_confirm_title))
        binding.pinPad.setSubtitle(getString(R.string.pin_pad_step_confirm_hint, pin.length))
        binding.pinPad.setTargetLength(pin.length) // إرسال تلقائي عند بلوغ الطول
        binding.pinPad.setInfo(getString(R.string.pin_pad_reenter_note))
    }

    private fun createPin(pin: String, confirm: String) {
        binding.pinPad.isEnabled = false
        val result = ServiceLocator.parentalAuthManager.setupPin(pin, confirm)
        when (result) {
            ParentalAuthManager.SetupResult.SUCCESS -> {
                startActivity(Intent(this, BrowserActivity::class.java))
                finish()
            }
            ParentalAuthManager.SetupResult.ALREADY_SET -> {
                // رمز موجود مسبقًا — تابع للمتصفح (تغيير الرمز من لوحة الوالدين)
                startActivity(Intent(this, BrowserActivity::class.java))
                finish()
            }
            else -> {
                firstPin = null
                binding.pinPad.isEnabled = true
                enterChoosePhase()
                binding.pinPad.setError(
                    when (result) {
                        ParentalAuthManager.SetupResult.NOT_DIGITS -> getString(R.string.setup_error_digits)
                        ParentalAuthManager.SetupResult.WEAK_PIN -> getString(R.string.setup_error_short)
                        else -> getString(R.string.error_generic)
                    }
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (firstPin != null) {
            // رجوع من مرحلة التأكيد إلى مرحلة الاختيار
            firstPin = null
            binding.pinPad.clear()
            enterChoosePhase()
        } else {
            super.onBackPressed()
        }
    }
}
