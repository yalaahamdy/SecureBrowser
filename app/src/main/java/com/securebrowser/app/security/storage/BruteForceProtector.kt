package com.securebrowser.app.security.storage

/**
 * حماية من القوة الغاشمة (brute force) لرمز الوالدين.
 *
 * السياسة: تأخير أُسّي بعد عتبة المحاولات — يبدأ بعد المحاولة الثالثة،
 * يتضاعف مع كل فشل، بسقف 15 دقيقة. الحالة تُخزَّن مشفرة وتنجو من إعادة تشغيل التطبيق.
 */
class BruteForceProtector(private val storage: SecureStorage) {

    fun canAttempt(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs >= lockUntilMs()

    fun remainingLockMs(nowMs: Long = System.currentTimeMillis()): Long =
        (lockUntilMs() - nowMs).coerceAtLeast(0)

    fun failedAttempts(): Int = storage.getLong(KEY_FAILED_COUNT, 0L).toInt()

    /** المحاولات المتبقية قبل بدء القفل المؤقت (v1.3.0 — للعرض في واجهة الإدخال). */
    fun attemptsRemaining(): Int =
        (FAILURE_THRESHOLD - failedAttempts()).coerceAtLeast(0L).toInt()

    fun recordFailure(nowMs: Long = System.currentTimeMillis()) {
        val count = storage.getLong(KEY_FAILED_COUNT, 0L) + 1
        storage.putLong(KEY_FAILED_COUNT, count)
        if (count < FAILURE_THRESHOLD) return
        val exponent = (count - FAILURE_THRESHOLD).coerceAtMost(MAX_EXPONENT)
        val delay = (BASE_DELAY_MS shl exponent.toInt()).coerceAtMost(MAX_DELAY_MS)
        storage.putLong(KEY_LOCK_UNTIL, nowMs + delay)
    }

    fun recordSuccess() {
        storage.putLong(KEY_FAILED_COUNT, 0L)
        storage.putLong(KEY_LOCK_UNTIL, 0L)
    }

    private fun lockUntilMs(): Long = storage.getLong(KEY_LOCK_UNTIL, 0L)

    companion object {
        private const val KEY_FAILED_COUNT = "bf_failed_count"
        private const val KEY_LOCK_UNTIL = "bf_lock_until"
        private const val FAILURE_THRESHOLD = 3L
        private const val BASE_DELAY_MS = 5_000L      // 5 ثوانٍ
        private const val MAX_DELAY_MS = 15 * 60_000L // 15 دقيقة
        private const val MAX_EXPONENT = 20L
    }
}
