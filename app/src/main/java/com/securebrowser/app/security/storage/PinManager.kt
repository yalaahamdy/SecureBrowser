package com.securebrowser.app.security.storage

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * إدارة رمز الوالدين — hash فقط، لا نص صريح أبدًا.
 *
 * الخوارزمية: PBKDF2WithHmacSHA256 — 310,000 دورة — ملح عشوائي 16 بايت — مخرجات 256 بت.
 * المخزن: salt + hash + iterations (كلها مشفرة AES-GCM بمفتاح Keystore عبر [SecureStorage]).
 */
class PinManager(private val storage: SecureStorage) {

    fun hasPin(): Boolean = storage.getString(KEY_HASH) != null

    /** يخزن رمزًا جديدًا (يستدعى من ParentalAuthManager بعد التحقق من القواعد). */
    fun storePin(pin: String) {
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val hash = pbkdf2(pin.toCharArray(), salt, ITERATIONS)
        storage.putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
        storage.putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
        storage.putLong(KEY_ITERATIONS, ITERATIONS.toLong())
        storage.putLong(KEY_CREATED_AT, System.currentTimeMillis())
        // طول الرمز — بيانات عرض فقط (لتعجيل الإرسال التلقائي في لوحة الإدخال)،
        // لا يستخدم في التحقق إطلاقًا والتحقق يبقى timing-safe عبر الهاش.
        storage.putLong(KEY_LENGTH, pin.length.toLong())
    }

    /** تحقق ثابت الزمن (timing-safe). */
    fun verifyPin(pin: String): Boolean {
        val storedHash = storage.getString(KEY_HASH) ?: return false
        val storedSalt = storage.getString(KEY_SALT) ?: return false
        val iterations = storage.getLong(KEY_ITERATIONS, ITERATIONS.toLong()).toInt()
        return try {
            val salt = Base64.decode(storedSalt, Base64.NO_WRAP)
            val candidate = pbkdf2(pin.toCharArray(), salt, iterations)
            val expected = Base64.decode(storedHash, Base64.NO_WRAP)
            MessageDigest.isEqual(candidate, expected)
        } catch (e: Exception) {
            false
        }
    }

    fun createdAt(): Long = storage.getLong(KEY_CREATED_AT, 0L)

    /**
     * طول الرمز المخزن (4–12) أو 0 إن كان مجهولًا — تثبيتات قديمة سابقة v1.3.0.
     * بيانات عرض حصرًا: لوحة الإدخال تعرف متى ترسل تلقائيًا؛ التحقق نفسه عبر الهاش فقط.
     */
    fun storedPinLength(): Int {
        val len = storage.getLong(KEY_LENGTH, 0L).toInt()
        return if (len in MIN_LENGTH..MAX_LENGTH) len else 0
    }

    /**
     * تعلّم الطول بعد أول نجاح تحقق في التثبيتات القديمة (بلا طول مخزّن) —
     * يكتب الطول الفعلي المُدخل الصحيح لتفعيل الإرسال التلقائي لاحقًا.
     */
    fun noteObservedLength(length: Int) {
        if (length in MIN_LENGTH..MAX_LENGTH && storedPinLength() == 0) {
            storage.putLong(KEY_LENGTH, length.toLong())
        }
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(
                PBEKeySpec(password, salt, iterations, KEY_BITS)
            ).encoded

    // ————— v1.9.0 — النسخ الاحتياطي والاستعادة —————

    /**
     * لقطة رمز الوالدين للتصدير — **هاش PBKDF2 فقط، لا نص صريح أبدًا**.
     * ملاحظة أمنية موثقة: القيم مرور عبر Keystore هنا، لكن في ملف النسخة
     * تكون صيغة Hmac+ملح غير معكوسة إلا بقوة غاشمة — لذلك تضمينها في
     * التصدير **اختياري** بقرار صريح من الوالد (خانة تأكيد).
     * يرجع null إن لم يُضبط رمز بعد.
     */
    fun exportData(): PinSnapshot? {
        val hash = storage.getString(KEY_HASH) ?: return null
        val salt = storage.getString(KEY_SALT) ?: return null
        return PinSnapshot(
            hashB64 = hash,
            saltB64 = salt,
            iterations = storage.getLong(KEY_ITERATIONS, ITERATIONS.toLong()),
            length = storage.getLong(KEY_LENGTH, 0L),
            createdAt = storage.getLong(KEY_CREATED_AT, 0L)
        )
    }

    /**
     * استعادة رمز من نسخة احتياطية — نفس بنية [storePin] بلا إعادة احتساب
     * (الهاش محسوب أصلًا بنفس PBKDF2WithHmacSHA256).
     * تحقق صارم قبل الكتابة: مدى التكرارات + أطوال الملح/الهاش بالبايت.
     * @return true عند نجاح الكتابة.
     */
    fun restoreData(snapshot: PinSnapshot): Boolean {
        if (snapshot.iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return false
        return try {
            val salt = Base64.decode(snapshot.saltB64, Base64.NO_WRAP)
            val hash = Base64.decode(snapshot.hashB64, Base64.NO_WRAP)
            if (salt.size != SALT_BYTES || hash.size != KEY_BITS / 8) return false
            storage.putString(KEY_HASH, snapshot.hashB64)
            storage.putString(KEY_SALT, snapshot.saltB64)
            storage.putLong(KEY_ITERATIONS, snapshot.iterations)
            storage.putLong(
                KEY_LENGTH,
                if (snapshot.length in MIN_LENGTH.toLong()..MAX_LENGTH.toLong())
                    snapshot.length else 0L
            )
            storage.putLong(KEY_CREATED_AT, snapshot.createdAt)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** لقطة رمز الوالدين (بلا نص صريح) — للنسخ الاحتياطي v1.9.0. */
    data class PinSnapshot(
        val hashB64: String,
        val saltB64: String,
        val iterations: Long,
        val length: Long,
        val createdAt: Long
    )

    companion object {
        private const val KEY_HASH = "parent_pin_hash"
        private const val KEY_SALT = "parent_pin_salt"
        private const val KEY_ITERATIONS = "parent_pin_iterations"
        private const val KEY_CREATED_AT = "parent_pin_created_at"
        private const val KEY_LENGTH = "parent_pin_length"
        private const val ITERATIONS = 310_000
        private const val KEY_BITS = 256
        private const val SALT_BYTES = 16
        /** v1.9.0 — مدى التكرارات المقبول عند استعادة لقطة رمز من نسخة احتياطية. */
        const val MIN_ITERATIONS = 1_000L
        const val MAX_ITERATIONS = 2_000_000L
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 12
        private val secureRandom = SecureRandom()
    }
}
