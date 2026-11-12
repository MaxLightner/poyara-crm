package com.poyara.crm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal class VoiceHttpException(val statusCode: Int, message: String) : IOException(message)

/**
 * Mobile voice transport: M4A is multipart binary, metadata in JSON.
 * Authorization uses a per-user Supabase JWT, never a shared API secret.
 */
internal class VoiceAgentClient(private val context: Context) {
    val isConfigured: Boolean get() = PoyaraAuth(context).hasSession

    fun analyze(file: File, jobId: String, contextContacts: List<ContactEntry>): JSONObject {
        require(file.isFile && file.length() >= 64L) { "فایل ویس موجود نیست" }
        require(file.length() <= 25L * 1024 * 1024) { "ویس باید کمتر از ۲۵ مگابایت باشد" }
        val jwt = PoyaraAuth(context).accessToken()
        val boundary = "poyara" + UUID.randomUUID().toString().replace("-", "")
        val url = URL(PoyaraCloud.VOICE_ENDPOINT)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 180_000
            setChunkedStreamingMode(32 * 1024)
            setRequestProperty("Authorization", "Bearer " + jwt)
            setRequestProperty("apikey", PoyaraCloud.PUBLISHABLE_KEY)
            setRequestProperty("X-Voice-Job-Id", jobId)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
        }
        fun part(name: String, filename: String? = null): String =
            "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + name + "\"" +
                (if (filename == null) "" else "; filename=\"" + filename + "\"") +
                "\r\n" +
                (if (filename == null) "" else "Content-Type: audio/mp4\r\n") +
                "\r\n"

        return try {
            connection.outputStream.use { stream ->
                stream.write(part("context_json").toByteArray(Charsets.UTF_8))
                val contacts = JSONArray()
                contextContacts.take(1).forEach { entry ->
                    contacts.put(JSONObject()
                        .put("name", entry.name.take(100))
                        .put("company", entry.company.take(100))
                        .put("phone", entry.phone.take(30)))
                }
                stream.write(contacts.toString().toByteArray(Charsets.UTF_8))
                stream.write("\r\n".toByteArray())
                stream.write(part("audio", "voice.m4a").toByteArray(Charsets.UTF_8))
                file.inputStream().use { it.copyTo(stream, 32768) }
                stream.write(("\r\n--" + boundary + "--\r\n").toByteArray())
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("detail") }
                    .getOrDefault("").take(220)
                throw VoiceHttpException(code, "پاسخ سرور " + code + ": " + detail)
            }
            val result = JSONObject(body)
            require(result.optString("job_id") == jobId) { "پاسخ به این ویس تعلق ندارد" }
            require(result.optString("status") == "needs_review") { "تحلیل کامل نشده است" }
            result
        } finally {
            connection.disconnect()
        }
    }
}
