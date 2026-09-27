package com.securebrowser.app.ui

import android.app.Activity
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat

/**
 * بوابة البصمة (v1.4.0) — فتح سريع لبوابة الوالدين عبر بيانات الجهاز الحيوية.
 *
 * - واجهة النظام **android.hardware.biometrics.BiometricPrompt (API 28+)** مباشرة —
 *   بلا مكتبة androidx.biometric إضافية (الحزمة بأقل اعتمادات ممكنة).
 * - [isAvailable]: API 29+ عبر BiometricManager.canAuthenticate (بصمة/وجه مسجّل
 *   ومهيّز جاهز)، API 28 عبر FingerprintManager.
 * - **قرار أمني:** البصمة اختيارية للوالد (إعداد biometric_unlock، افتراضيًا معطل)
 *   لأن جهاز الطفل غالبًا يشارك بصمات متعددة — تُفعّل فقط إذا كانت بصمة الوالد
 *   هي الوحيدة المسجلة. الرمز الرقمي يبقى العامل الأساسي والاحتياطي دائمًا.
 * - نجاح البصمة يمر عبر ParentalAuthManager.verifyBiometric (يحترم قفل brute force).
 */
object BiometricGate {

    /** هل الجهاز يدعم البصمة وبه بصمة/وجه مسجّل وجاهز؟ */
    fun isAvailable(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val bm = activity.getSystemService(
                    android.hardware.biometrics.BiometricManager::class.java
                )
                bm?.canAuthenticate() ==
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                val fm = ContextCompat.getSystemService(activity, FingerprintManager::class.java)
                @Suppress("DEPRECATION")
                fm != null && fm.isHardwareDetected && fm.hasEnrolledFingerprints()
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * عرض نافذة التحقق الحيوي.
     * @param onError يُستدعى عند خطأ/إلغاء المستخدم (وليس عند فشل محاولة واحدة —
     *                النظام يتيح إعادة المحاولة داخليًا عبر onAuthenticationFailed).
     */
    fun authenticate(
        activity: Activity,
        title: String,
        subtitle: String,
        negativeText: String,
        onSuccess: () -> Unit,
        onError: () -> Unit
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try {
            val prompt = android.hardware.biometrics.BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButton(negativeText, activity.mainExecutor) { _, _ -> onError() }
                .build()
            val signal = CancellationSignal()
            prompt.authenticate(signal, activity.mainExecutor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult
                    ) {
                        signal.cancel()
                        onSuccess()
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence?
                    ) {
                        // ERR_USER_CANCELED/NEGATIVE_BUTTON: المستخدم ألغى — نعيد الحالة للوحة
                        onError()
                    }
                }
            )
            true
        } catch (e: Exception) {
            // أجهزة لا تطبّق واجهة BiometricPrompt المنصة بشكل صحيح
            false
        }
    }
}
