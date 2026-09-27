package com.securebrowser.app.parental

import com.securebrowser.app.security.storage.BruteForceProtector
import com.securebrowser.app.security.storage.PinManager

/**
 * أساس نظام إدارة الوالدين (Parental Lock foundation).
 *
 * يوفر: إنشاء أول رمز + تأكيده + hash آمن + حماية brute force + إدارة حالة القفل.
 * لوحة التحكم الكاملة (إدارة القائمة البيضاء، السجلات، الإعدادات) تُبنى في المرحلة الثانية فوق هذا الأساس.
 */
class ParentalAuthManager(
    private val pinManager: PinManager,
    private val bruteForceProtector: BruteForceProtector
) {

    sealed class AuthResult {
        object Success : AuthResult()
        object WrongPin : AuthResult()
        data class Locked(val remainingMs: Long) : AuthResult()
    }

    /** هل وُجد رمز والدين؟ */
    fun hasPin(): Boolean = pinManager.hasPin()

    /**
     * طول الرمز المخزّن إن كان معروفًا (v1.3.0 — بيانات عرض للوحة الإدخال،
     * لتفعيل الإرسال التلقائي). null = مجهول (تثبيت قديم) → إدخال يدوي مع تأكيد.
     */
    fun storedPinLength(): Int? = pinManager.storedPinLength().takeIf { it > 0 }

    /**
     * إنشاء أول رمز (تشغيل أول).
     * القواعد: أرقام فقط، من 4 إلى 12 خانة، تطابق التأكيد.
     */
    fun setupPin(pin: String, confirm: String): SetupResult {
        if (pinManager.hasPin()) return SetupResult.ALREADY_SET
        if (pin != confirm) return SetupResult.MISMATCH
        if (pin.length < MIN_LENGTH || pin.length > MAX_LENGTH) return SetupResult.WEAK_PIN
        if (pin.any { !it.isDigit() }) return SetupResult.NOT_DIGITS
        pinManager.storePin(pin)
        bruteForceProtector.recordSuccess()
        return SetupResult.SUCCESS
    }

    /** تحقق موثّق من الرمز مع حماية القوة الغاشمة. */
    fun verify(pin: String): AuthResult {
        if (!bruteForceProtector.canAttempt()) {
            return AuthResult.Locked(bruteForceProtector.remainingLockMs())
        }
        return if (pinManager.verifyPin(pin)) {
            // تعلّم الطول للوحات القديمة (بلا طول مخزّن) — تثبيت أول بعد الترقية
            pinManager.noteObservedLength(pin.length)
            bruteForceProtector.recordSuccess()
            AuthResult.Success
        } else {
            bruteForceProtector.recordFailure()
            AuthResult.WrongPin
        }
    }

    /**
     * تحقق حيوي (بصمة/وجه) — v1.4.0، اختياري للوالد (biometric_unlock).
     *
     * الأمن: نجاح البيانات الحيوية = تحقق هوية مالك الجهاز المسجّل، فيُعامَل
     * كرمز صحيح (تصفير عداد المحاولات). **يحترم قفل brute force الحالي** —
     * أثناء القفل المؤقت لا تفتح البصمة البوابة (القفل جزاء محاولات حتى
     * على مالك الجهاز). الإعداد الافتراضي معطل لأن جهاز الطفل قد يحمل
     * بصمات أخرى غير بصمة الوالد.
     */
    fun verifyBiometric(): AuthResult {
        if (!bruteForceProtector.canAttempt()) {
            return AuthResult.Locked(bruteForceProtector.remainingLockMs())
        }
        bruteForceProtector.recordSuccess()
        return AuthResult.Success
    }

    fun remainingLockMs(): Long = bruteForceProtector.remainingLockMs()

    /**
     * تغيير الرمز — يتطلب الرمز الحالي الصحيح (من لوحة الوالدين بعد المصادقة).
     * القواعد نفسها: أرقام 4–12.
     */
    fun changePin(currentPin: String, newPin: String, confirm: String): SetupResult {
        if (!pinManager.hasPin()) return SetupResult.NOT_DIGITS
        if (!bruteForceProtector.canAttempt()) return SetupResult.LOCKED_OUT
        if (!pinManager.verifyPin(currentPin)) {
            bruteForceProtector.recordFailure()
            return SetupResult.WrongCurrent
        }
        if (newPin != confirm) return SetupResult.MISMATCH
        if (newPin.length < MIN_LENGTH || newPin.length > MAX_LENGTH) return SetupResult.WEAK_PIN
        if (newPin.any { !it.isDigit() }) return SetupResult.NOT_DIGITS
        pinManager.storePin(newPin)
        bruteForceProtector.recordSuccess()
        return SetupResult.SUCCESS
    }

    enum class SetupResult { SUCCESS, ALREADY_SET, MISMATCH, WEAK_PIN, NOT_DIGITS, WrongCurrent, LOCKED_OUT }

    companion object {
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 12
    }
}
