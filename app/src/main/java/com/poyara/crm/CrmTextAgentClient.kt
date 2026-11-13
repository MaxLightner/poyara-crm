package com.poyara.crm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends speech text + current contact name/company only to an opt-in HTTPS agent.
 * AI provider keys remain on the server, never in the Android APK.
 */
internal class CrmTextAgentClient(context: Context) {
    private val settings = VoiceServerSettings(context)
    val configured: Boolean get() = settings.configured

    fun analyze(transcript: String, contacts: List<ContactEntry>): JSONObject {
        require(configured) { "Agent هنوز متصل نشده است" }
        require(transcript.isNotBlank() && transcript.length <= 10000)
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
