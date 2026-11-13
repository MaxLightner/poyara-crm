package com.poyara.crm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small, opt-in personal install: user brings a restricted Google AI Studio key.
 * It stays encrypted using Android Keystore; it is never embedded in source/APK,
 * cloud sync, voice text, logs or ZIP exports. A real team should use a backend.
 */
internal class GeminiLocalSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "poyara_gemini_direct", Context.MODE_PRIVATE)
    private val alias = "poyara_gemini_user_key_v1"

    val configured: Boolean get() = apiKey().isNotBlank()

    fun save(raw: String) {
        val clean = raw.trim()
        require(clean.length in 24..256 && clean.none { it.isWhitespace() }) {
            "کلید Gemini معتبر نیست؛ کلید ساخته‌شده در Google AI Studio را وارد کن"
        }
        prefs.edit().putString("encrypted_key", encrypt(clean)).apply()
    }

    fun clear() { prefs.edit().remove("encrypted_key").apply() }

    fun apiKey(): String = runCatching {
        val data = Base64.decode(
            prefs.getString("encrypted_key", "").orEmpty(), Base64.NO_WRAP)
        require(data.size > 13)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(),
            GCMParameterSpec(128, data.copyOfRange(0, 12)))
        String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    }.getOrDefault("")

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(
            cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP)
    }
}
