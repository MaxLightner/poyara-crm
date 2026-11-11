package com.poyara.crm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Voice MVP network client. No AI provider key is ever stored in the APK.
 * An opt-in pilot bearer token is encrypted with Android Keystore at rest.
 *
 * Production team deployments must replace the pilot token with a user JWT
 * and a dedicated workspace-aware backend.
 */
internal class VoiceAgentClient(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "voice_agent_settings", Context.MODE_PRIVATE
    )

    val endpoint: String
        get() = prefs.getString("url", "").orEmpty().trimEnd('/')

    val isConfigured: Boolean
        get() = endpoint.startsWith("https://") && readToken() != null

    fun saveSettings(url: String, accessToken: String) {
        val cleaned = url.trim().trimEnd('/')
        require(cleaned.startsWith("https://")) { "آدرس سرور باید HTTPS باشد" }
        require(cleaned.length <= 250) { "آدرس سرور طولانی است" }
        if (accessToken.isNotBlank()) {
            require(accessToken.length >= 32) { "کلید دسترسی آزمایشی باید حداقل ۳۲ کاراکتر باشد" }
            prefs.edit().putString("token", encrypt(accessToken.trim())).apply()
        }
        check(readToken() != null) { "کلید دسترسی سرور را وارد کن" }
        prefs.edit().putString("url", cleaned).apply()
    }

    fun clearSettings() {
        prefs.edit().remove("url").remove("token").apply()
    }

    fun analyze(file: File, jobId: String, contextContacts: List<ContactEntry>): JSONObject {
        require(isConfigured) { "ابتدا سرور تحلیل صوت را در تنظیمات وصل کن" }
        require(file.isFile && file.length() >= 64) { "فایل صوتی پیدا نشد" }
        require(file.length() <= 25L * 1024L * 1024L) { "فایل صوتی باید کمتر از ۲۵ مگابایت باشد" }
        val token = readToken() ?: error("کلید ورود ذخیره نشده")
        val url = URL(endpoint + "/v1/voice/analyze")
        val boundary = "poyara" + UUID.randomUUID().toString().replace("-", "")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 150000
            doOutput = true
            setChunkedStreamingMode(32 * 1024)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-Voice-Job-Id", jobId)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        fun partHeader(name: String, filename: String? = null) =
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"$name\"" +
                (if (filename != null) "; filename=\"$filename\"" else "") + "\r\n" +
                (if (filename != null) "Content-Type: audio/mp4\r\n" else "") +
                "\r\n"
        return try {
            connection.outputStream.use { stream ->
                stream.write(partHeader("context_json").toByteArray(Charsets.UTF_8))
                val candidates = JSONArray()
                contextContacts.take(25).forEach { entry ->
                    candidates.put(JSONObject()
                        .put("name", entry.name.take(120))
                        .put("company", entry.company.take(120))
                        .put("phone", entry.phone.take(40)))
                }
                stream.write(candidates.toString().toByteArray(Charsets.UTF_8))
                stream.write("\r\n".toByteArray())
                stream.write(partHeader("audio", "recording.m4a").toByteArray())
                file.inputStream().use { input -> input.copyTo(stream, 32768) }
                stream.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream
                else connection.errorStream)?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching {
                    JSONObject(text).optString("detail").take(200)
                }.getOrDefault("")
                error("سرور تحلیل صوت خطای $status داد${if (detail.isNotBlank()) ": $detail" else ""}")
            }
            val result = JSONObject(text)
            require(result.optString("job_id") == jobId) { "شناسه پاسخ سرور مطابقت ندارد" }
            require(result.optString("status") == "needs_review") { "پاسخ سرور هنوز آماده نیست" }
            result
        } finally {
            connection.disconnect()
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("poyara_voice_pilot_v1", null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(KeyGenParameterSpec.Builder(
            "poyara_voice_pilot_v1",
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(secret: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun readToken(): String? = runCatching {
        val encoded = prefs.getString("token", null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        if (bytes.size <= 12) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, key(),
            GCMParameterSpec(128, bytes.copyOfRange(0, 12))
        )
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()
}
