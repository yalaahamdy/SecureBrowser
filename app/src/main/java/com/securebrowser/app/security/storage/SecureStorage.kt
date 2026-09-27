package com.securebrowser.app.security.storage

import android.content.Context
import android.util.Base64

/**
 * تخزين آمن فوق SharedPreferences: كل قيمة تُشفَّر AES-GCM بمفتاح Keystore قبل الكتابة.
 *
 * ممنوعات صريحة (موثقة كقواعد مشروع):
 * - لا كلمات مرور نصًا صريحًا.
 * - لا أسرار في الكود أو السجلات أو تفضيلات مكشوفة.
 *
 * ملاحظة تشغيلية: لا يُسجَّل أي محتوى لهذا المخزن في logcat إطلاقًا.
 */
class SecureStorage(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun putString(key: String, value: String) =
        prefs.edit().putString(key, seal(value.toByteArray(Charsets.UTF_8))).apply()

    fun getString(key: String): String? =
        prefs.getString(key, null)?.let { unseal(it) }?.toString(Charsets.UTF_8)

    fun putLong(key: String, value: Long) = putString(key, value.toString())

    fun getLong(key: String, default: Long): Long =
        getString(key)?.toLongOrNull() ?: default

    fun putInt(key: String, value: Int) = putString(key, value.toString())

    fun getInt(key: String, default: Int): Int =
        getString(key)?.toIntOrNull() ?: default

    fun putBoolean(key: String, value: Boolean) = putString(key, if (value) "1" else "0")

    fun getBoolean(key: String, default: Boolean): Boolean =
        getString(key)?.let { it == "1" } ?: default

    fun remove(key: String) = prefs.edit().remove(key).apply()

    fun contains(key: String): Boolean = prefs.contains(key)

    private fun seal(plain: ByteArray): String =
        Base64.encodeToString(CryptoManager.encrypt(plain), Base64.NO_WRAP)

    private fun unseal(sealed: String): ByteArray =
        CryptoManager.decrypt(Base64.decode(sealed, Base64.NO_WRAP))

    companion object {
        private const val FILE_NAME = "sb_secure_storage"
    }
}
