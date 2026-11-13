package com.poyara.crm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Centralized POYARA agent: Android never receives the Cloudflare admin token.
 *
 * The embedded app access code is a REVOCABLE pilot key, not a secret secure
 * against APK reverse engineering. The Worker independently rate-limits calls.
 * For public distribution use device attestation and per-device enrollment.
 */
internal class CentralAgentClient {
    private val base = BuildConfig.POYARA_AGENT_URL.trim().trimEnd('/')
    private val access = BuildConfig.POYARA_APP_ACCESS_KEY.trim()

    val configured: Boolean
        get() = base.startsWith("https://") && access.length >= 32 &&
            runCatching { URL(base).host.isNotBlank() }.getOrDefault(false)

    fun testConnection(): Boolean {
        check(configured) { "اتصال مرکزی پویارا هنوز از سوی مدیر فعال نشده است" }
        val c = connection("/v1/auth/check", "POST", "application/json")
        try {
            c.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200)
                error("کلید دسترسی یا سرویس مرکزی آماده نیست (HTTP ${c.responseCode})")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                .optString("status") == "ok"
        } finally { c.disconnect() }
    }

    fun analyze(
        transcript: String,
        audio: File?,
        contact: ContactEntry?,
        reminders: List<WorkReminder>
    ): JSONObject {
        check(configured) { "اتصال مرکزی پویارا هنوز توسط مدیر آماده نشده است" }
        var recognized = transcript.trim()
        if (audio?.isFile == true && audio.length() in 256L..(4L * 1024 * 1024)) {
            // When live dictation fails or is noisy, use Whisper on central Worker.
            // If Whisper fails, the existing local transcript is still usable.
            val fromAudio = runCatching { transcribe(audio) }.getOrNull()
            if (!fromAudio.isNullOrBlank()) recognized = fromAudio
        }
        require(recognized.isNotBlank()) {
            "فایل صوتی هنوز قابل تبدیل نیست؛ از تشخیص گفتار زنده استفاده کن"
        }
        val body = JSONObject()
            .put("transcript", recognized.take(10000))
            .put("now_local", ZonedDateTime.now().toString())
            .put("timezone", ZoneId.systemDefault().id)
            .put("contact", JSONObject()
                .put("name", contact?.name.orEmpty().take(120))
                .put("company", contact?.company.orEmpty().take(120))
                .put("role", contact?.role.orEmpty().take(100))
                .put("category", contact?.category.orEmpty().take(100)))
            .put("reminders", JSONArray().apply {
                reminders.take(15).forEach { reminder ->
                    put(JSONObject()
                        .put("id", reminder.id)
                        .put("title", reminder.title.take(150))
                        .put("due_local", Instant.ofEpochMilli(reminder.dueAt)
                            .atZone(ZoneId.systemDefault()).toString()))
                }
            })
        val connection = connection("/v1/text/analyze", "POST", "application/json")
        try {
            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            when (status) {
                401, 403 -> error("اجازه دسترسی به Agent مرکزی فعال نیست")
                429 -> error("درخواست‌های هوش مصنوعی بیش از حد مجاز است؛ گفتار ذخیره ماند")
                in 200..299 -> Unit
                else -> error("Agent مرکزی در دسترس نیست (HTTP $status)")
            }
            val result = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8)
                .use { it.readText() })
            check(result.optJSONArray("contacts") != null &&
                result.optJSONArray("reminders") != null &&
                result.optJSONArray("actions") != null &&
                result.optJSONArray("questions") != null) {
                "فرمت پاسخ هوش مصنوعی معتبر نیست"
            }
            return result
        } finally { connection.disconnect() }
    }

    private fun transcribe(audio: File): String {
        val c = connection("/v1/audio/transcribe", "POST", "audio/mp4")
        c.readTimeout = 90000
        try {
            c.outputStream.use { output ->
                audio.inputStream().use { it.copyTo(output) }
            }
            if (c.responseCode != 200) error("پشتیبانی از فایل صوتی در سرویس مرکزی فعال نیست")
            return JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8)
                .use { it.readText() }).optString("text")
        } finally { c.disconnect() }
    }

    private fun connection(path: String, method: String, type: String): HttpURLConnection {
        val url = URL(base + path)
        require(url.protocol == "https") { "ارتباط باید HTTPS باشد" }
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            doOutput = true
            connectTimeout = 15000
            readTimeout = 90000
            setRequestProperty("Authorization", "Bearer " + access)
            setRequestProperty("Content-Type", type)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-store")
        }
    }
}
