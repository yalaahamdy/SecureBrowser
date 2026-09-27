package com.securebrowser.app.security.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * غلاف Android Keystore — مفتاح AES-256-GCM مقيم في العتاد الآمن (TEE/StrongBox حين يوجد).
 *
 * الضمانات:
 * - المفتاح لا يغادر Keystore مطلقًا ولا يظهر في الكود أو السجلات أو التفضيلات.
 * - كل قيمة تُشفَّر بـ IV جديد عشوائي، والمخرجات (IV ‖ ciphertext) تُخزَّن مشفرة.
 */
object CryptoManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "secure_browser_master_aes"
    private const val GCM_TAG_BITS = 128
    private const val IV_SIZE = 12

    private val lock = Any()

    private fun getOrCreateKey(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv ?: error("GCM IV missing")
        val cipherText = cipher.doFinal(plain)
        return iv + cipherText
    }

    fun decrypt(data: ByteArray): ByteArray {
        require(data.size > IV_SIZE) { "ciphertext too short" }
        val iv = data.copyOfRange(0, IV_SIZE)
        val cipherText = data.copyOfRange(IV_SIZE, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherText)
    }
}
