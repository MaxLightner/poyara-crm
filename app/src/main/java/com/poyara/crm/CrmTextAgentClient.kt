package com.poyara.crm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.util.UUID

/**
 * Sends speech text + current contact name/company only to an opt-in HTTPS agent.
 * AI provider keys remain on the server, never in the Android APK.
 */
internal class CrmTextAgentClient(context: Context) {
    private val settings = VoiceServerSettings(context)
    val configured: Boolean get() = settings.configured

    private fun verifyFreeGeminiBackend() {
        val request = (URL(settings.endpoint + "/health")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        try {
            if (request.responseCode != 200) error("اتصال به سرور Agent برقرار نشد")
            val status = request.inputStream.bufferedReader(Charsets.UTF_8)
                .use { JSONObject(it.readText()) }
            val provider = status.optString("text_provider")
            val model = status.optString("text_model")
            if (provider != "gemini" || !model.startsWith("gemini-")) {
                error("سرور هنوز روی Gemini رایگان تنظیم نشده است؛ برای جلوگیری از هزینه، درخواست ارسال نشد")
            }
        } finally {
            request.disconnect()
        }
    }

    fun analyzeAudio(
        audio: File, transcriptHint: String,
        contacts: List<ContactEntry>, reminders: List<WorkReminder>
    ): JSONObject {
        require(configured) { "Agent هنوز متصل نشده است" }
        require(audio.isFile && audio.length() in 256L..(10L * 1024 * 1024)) {
            "فایل صوتی پیدا نشد یا بیش از ده مگابایت است"
        }
        verifyFreeGeminiBackend()
        val context = JSONObject()
            .put("transcript", transcriptHint.take(10000))
            .put("timezone", java.time.ZoneId.systemDefault().id)
            .put("contacts", JSONArray().apply {
                contacts.take(1).forEach { contact ->
                    put(JSONObject().put("name", contact.name.take(100))
                        .put("company", contact.company.take(100)))
                }
            })
            .put("reminders", JSONArray().apply {
                reminders.take(15).forEach { event ->
                    put(JSONObject()
                        .put("id", event.id)
                        .put("title", event.title.take(160))
                        .put("due_at_iso", java.time.Instant.ofEpochMilli(event.dueAt)
                            .atZone(java.time.ZoneId.systemDefault()).toString()))
                }
            })
        val boundary = "Poyara" + UUID.randomUUID().toString().replace("-", "")
        val request = (URL(settings.endpoint + "/v1/audio/analyze")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 115_000
            setChunkedStreamingMode(16 * 1024)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Authorization", "Bearer " + settings.token())
        }
        try {
            request.outputStream.use { stream ->
                stream.write(("--$boundary\r\nContent-Disposition: form-data; " +
                    "name=\"context_json\"\r\n\r\n").toByteArray())
                stream.write(context.toString().toByteArray(Charsets.UTF_8))
                stream.write(("\r\n--$boundary\r\nContent-Disposition: form-data; " +
                    "name=\"audio\"; filename=\"recording.m4a\"\r\n" +
                    "Content-Type: audio/m4a\r\n\r\n").toByteArray())
                audio.inputStream().use { it.copyTo(stream) }
                stream.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = request.responseCode
            val result = (if (code in 200..299) request.inputStream else request.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error(
                if (code == 429) "سهمیه رایگان Gemini تمام شده؛ فایل صوتی حفظ شد"
                else "پردازش صوت Gemini ناموفق بود (HTTP $code): " + result.take(90))
            return JSONObject(result)
        } finally { request.disconnect() }
    }

    fun analyze(transcript: String, contacts: List<ContactEntry>): JSONObject {
        require(configured) { "Agent هنوز متصل نشده است" }
        require(transcript.isNotBlank() && transcript.length <= 10000)
        // Never accidentally call an old/paid backend merely because it was
        // paired in a previous version of POYARA.
        verifyFreeGeminiBackend()
        val payload = JSONObject()
            .put("transcript", transcript)
            .put("timezone", java.time.ZoneId.systemDefault().id)
            .put("contacts", JSONArray().apply {
                contacts.take(1).forEach { contact ->
                    put(JSONObject()
                        .put("name", contact.name.take(100))
                        .put("company", contact.company.take(100)))
                }
            })
        val request = (URL(settings.endpoint + "/v1/text/analyze")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 12000
            readTimeout = 85000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer " + settings.token())
        }
        return try {
            request.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = request.responseCode
            val response = (if (code in 200..299) request.inputStream
                else request.errorStream)?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }.orEmpty()
            if (code !in 200..299)
                error("Agent پاسخ نداد (HTTP $code): " + response.take(120))
            JSONObject(response)
        } finally {
            request.disconnect()
        }
    }
}
