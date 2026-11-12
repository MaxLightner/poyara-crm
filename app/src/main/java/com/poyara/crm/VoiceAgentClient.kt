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

/** Uploads audio once, then polls server stages without an email account.
 * progress(phase, percent) uses a measured upload fraction; server stages have null percent.
 */
internal class VoiceAgentClient(private val context: Context) {
    private val settings = VoiceServerSettings(context)
    val isConfigured: Boolean get() = settings.configured

    fun analyze(file: File, jobId: String, contacts: List<ContactEntry>,
                progress: (String, Int?) -> Unit = { _, _ -> }): JSONObject {
        require(settings.configured) { "سرور صوتی هنوز به این گوشی متصل نشده است" }
        require(file.isFile && file.length() in 64L..(25L * 1024 * 1024)) {
            "فایل صوتی باید موجود و کمتر از ۲۵ مگابایت باشد"
        }
        val base = settings.endpoint
        val token = settings.token()
        val boundary = "poyara" + UUID.randomUUID().toString().replace("-", "")
        val connection = (URL(base + "/v1/jobs").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 40_000
            setChunkedStreamingMode(32 * 1024)
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("X-Voice-Job-Id", jobId)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
        }
        fun head(name: String, filename: String? = null): String =
            "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + name + "\"" +
                (if (filename == null) "" else "; filename=\"" + filename + "\"") +
                "\r\n" +
                (if (filename == null) "" else "Content-Type: audio/mp4\r\n") + "\r\n"
        try {
            val candidates = JSONArray()
            contacts.take(5).forEach { entry ->
                candidates.put(JSONObject().put("name",entry.name.take(100))
                    .put("company",entry.company.take(100))
                    .put("phone",entry.phone.take(30)))
            }
            connection.outputStream.use { stream ->
                stream.write(head("context_json").toByteArray())
                stream.write(candidates.toString().toByteArray())
                stream.write("\r\n".toByteArray())
                stream.write(head("audio","voice.m4a").toByteArray())
                var done = 0L
                file.inputStream().use { input ->
                    val buffer = ByteArray(32*1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        stream.write(buffer,0,n)
                        done += n
                        progress("uploading",(done*100/file.length()).toInt().coerceIn(0,99))
                    }
                }
                stream.write(("\r\n--" + boundary + "--\r\n").toByteArray())
                stream.flush()
            }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw VoiceHttpException(status,
                "سرور ارسال را نپذیرفت: " + JSONObject(body).optString("detail").take(150))
            val result = JSONObject(body)
            require(result.optString("job_id") == jobId) { "شناسه پاسخ سرور اشتباه است" }
            progress("queued",100)
        } finally { connection.disconnect() }

        // Poll a durable server job. If Android interrupts this worker, WorkManager
        // repeats the upload with the same job ID; server deduplicates it.
        repeat(120) {
            val poll = URL(base + "/v1/jobs/" + jobId).openConnection() as HttpURLConnection
            try {
                poll.requestMethod = "GET"
                poll.connectTimeout = 20_000
                poll.readTimeout = 20_000
                poll.setRequestProperty("Authorization","Bearer " + token)
                val httpCode = poll.responseCode
                val text = (if (httpCode in 200..299) poll.inputStream else poll.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (httpCode !in 200..299) throw VoiceHttpException(httpCode,
                    "خطای دریافت نتیجه " + httpCode)
                val result = JSONObject(text)
                require(result.optString("job_id") == jobId) { "شناسه پردازش یکسان نیست" }
                val stage = result.optString("stage",result.optString("status"))
                if (result.optString("status") == "needs_review") {
                    progress("complete",null)
                    return result
                }
                if (result.optString("status") == "failed") {
                    throw VoiceHttpException(422,result.optString("detail","خطا در پردازش صوت"))
                }
                progress(stage,null)
            } finally { poll.disconnect() }
            Thread.sleep(3000)
        }
        throw IOException("پردازش سرور طولانی شده؛ بعداً دوباره بررسی می‌شود")
    }
}
