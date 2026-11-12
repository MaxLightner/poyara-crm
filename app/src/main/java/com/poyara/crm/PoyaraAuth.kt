package com.poyara.crm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The publishable key is public by design; never store an OpenAI secret here. */
internal object PoyaraCloud {
    const val URL = "https://xymutvzuqksqmmfhxuaa.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_CoQtT1I0oHrkvqx-7lgRFQ_0E_7nTmh"
    const val VOICE_ENDPOINT = URL + "/functions/v1/poyara-voice"
}

/** Email/password session with encrypted on-device refresh-token storage. */
internal class PoyaraAuth(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("poyara_auth_v1", Context.MODE_PRIVATE)
    val hasSession: Boolean
        get() = read("refresh")?.isNotBlank() == true

    val email: String get() = prefs.getString("email", "").orEmpty()

    fun signIn(email: String, password: String) {
        require(email.contains("@") && password.isNotBlank()) { "ایمیل و رمز عبور را وارد کن" }
        val result = authRequest("/auth/v1/token?grant_type=password", JSONObject()
            .put("email", email.trim()).put("password", password))
        saveSession(result, email.trim())
    }

    fun signUp(email: String, password: String): Boolean {
        require(email.contains("@") && password.length >= 6) { "ایمیل و رمز حداقل ۶ کاراکتری لازم است" }
        val result = authRequest("/auth/v1/signup", JSONObject()
            .put("email", email.trim()).put("password", password))
        val access = result.optString("access_token")
        if (access.isNotBlank()) {
            saveSession(result, email.trim())
            return true
        }
        return false // Email verification required; never treat it as signed in.
    }

    @Synchronized
    fun accessToken(): String {
        val token = read("access") ?: error("ابتدا با ایمیل وارد پویارا شو")
        val expiry = prefs.getLong("expiry", 0L)
        if (expiry > System.currentTimeMillis() + 120_000L) return token
        val refresh = read("refresh") ?: error("نشست ورود تمام شده؛ دوباره وارد شو")
        val result = authRequest("/auth/v1/token?grant_type=refresh_token", JSONObject()
            .put("refresh_token", refresh))
        saveSession(result, email)
        return read("access") ?: error("توکن جدید دریافت نشد")
    }

    fun logout() { prefs.edit().clear().apply() }

    private fun authRequest(path: String, json: JSONObject): JSONObject {
        val conn = (URL(PoyaraCloud.URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("apikey", PoyaraCloud.PUBLISHABLE_KEY)
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val reason = runCatching {
                    JSONObject(text).optString("msg")
                        .ifBlank { JSONObject(text).optString("error_description") }
                        .ifBlank { JSONObject(text).optString("message") }
                }.getOrDefault("").take(200)
                error("خطای ورود ($code): " + reason)
            }
            JSONObject(text)
        } finally { conn.disconnect() }
    }

    private fun saveSession(result: JSONObject, loginEmail: String) {
        val access = result.optString("access_token")
        val refresh = result.optString("refresh_token")
        require(access.isNotBlank() && refresh.isNotBlank()) { "اطلاعات نشست ناقص است" }
        val expiresIn = result.optLong("expires_in", 3600L)
        prefs.edit()
            .putString("access", encrypt(access))
            .putString("refresh", encrypt(refresh))
            .putString("email", loginEmail)
            .putLong("expiry", System.currentTimeMillis() + expiresIn * 1000L)
            .apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("poyara_auth_tokens_v1", null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(KeyGenParameterSpec.Builder(
            "poyara_auth_tokens_v1",
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(secret: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(
            cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
        )
    }

    private fun read(name: String): String? {
        return runCatching {
            val encoded = prefs.getString(name, null) ?: return@runCatching null
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            if (raw.size <= 12) return@runCatching null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
        }.getOrNull()
    }
}
