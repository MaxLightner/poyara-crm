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

/** No accounts or emails. Pair this installation once with a self-hosted server.
 * Token is encrypted under a non-exportable Android Keystore AES key.
 */
internal class VoiceServerSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("poyara_voice_server", Context.MODE_PRIVATE)
    var endpoint: String
        get() = prefs.getString("url", "").orEmpty()
        private set(value) { prefs.edit().putString("url", value).apply() }
    val configured: Boolean get() = endpoint.startsWith("https://") && token().isNotBlank()

    fun save(url: String, pairingToken: String) {
        val canonical = url.trim().trimEnd('/')
        require(canonical.startsWith("https://")) { "آدرس سرور باید HTTPS باشد" }
        require(pairingToken.length >= 32) { "کد اتصال سرور باید حداقل ۳۲ کاراکتر باشد" }
        prefs.edit().putString("url", canonical)
            .putString("token", encrypt(pairingToken.trim())).apply()
    }
    fun token(): String = runCatching {
        val raw = Base64.decode(prefs.getString("token", "") ?: "", Base64.NO_WRAP)
        require(raw.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128,raw.copyOfRange(0,12)))
        String(cipher.doFinal(raw.copyOfRange(12,raw.size)), Charsets.UTF_8)
    }.getOrDefault("")

    private fun getKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("poyara_voice_pairing_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("poyara_voice_pairing_v1",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,getKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()),Base64.NO_WRAP)
    }
}
