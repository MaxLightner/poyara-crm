package com.poyara.crm

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Personal free-tier Google Gemini adapter: one direct, opt-in request. */
internal class GeminiDirectClient(context: Context) {
    private val settings = GeminiLocalSettings(context)
    private val model = "gemini-3.5-flash-lite"
    val configured: Boolean get() = settings.configured

    private fun str() = JSONObject().put("type", "string")
    private fun nullableStr() = JSONObject()
        .put("type", JSONArray(listOf("string", "null")))
    private fun nullableId() = JSONObject()
        .put("type", JSONArray(listOf("integer", "null")))
    private fun obj(props: JSONObject, required: List<String>) = JSONObject()
        .put("type", "object")
        .put("properties", props)
        .put("required", JSONArray(required))
        .put("additionalProperties", false)

    private fun schema(): JSONObject {
        val contactFields = listOf("name", "phone", "company", "role",
            "category", "address", "note", "related_to")
        val contacts = JSONObject().apply {
            contactFields.forEach { put(it, nullableStr()) }
        }
        val reminderFields = JSONObject()
            .put("person_name", nullableStr())
            .put("company", nullableStr())
            .put("title", str())
            .put("date_iso", nullableStr())
            .put("time_local", nullableStr())
            .put("remind_days_before", JSONObject().put("type", "integer"))
            .put("requires_confirmation", JSONObject().put("type", "boolean"))
        val actionsFields = JSONObject()
            .put("type", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf(
                    "correct_name", "add_phone", "set_role", "set_category",
                    "create_reminder", "update_reminder", "delete_reminder",
                    "append_note"))))
            .put("value", nullableStr())
            .put("target_reminder_id", nullableId())
            .put("title", nullableStr())
            .put("date_iso", nullableStr())
            .put("time_local", nullableStr())
            .put("requires_confirmation", JSONObject().put("type", "boolean"))
        val actionNames = listOf("type", "value", "target_reminder_id",
            "title", "date_iso", "time_local", "requires_confirmation")
        val rootFields = JSONObject()
            .put("corrected_text", str())
            .put("contacts", JSONObject().put("type", "array")
                .put("items", obj(contacts, contactFields)))
            .put("reminders", JSONObject().put("type", "array")
                .put("items", obj(reminderFields,
                    listOf("person_name", "company", "title", "date_iso",
                        "time_local", "remind_days_before", "requires_confirmation"))))
            .put("actions", JSONObject().put("type", "array")
                .put("items", obj(actionsFields, actionNames)))
            .put("questions", JSONObject().put("type", "array")
                .put("items", str()))
        return obj(rootFields, listOf("corrected_text", "contacts",
            "reminders", "actions", "questions"))
    }

    fun analyze(
        transcript: String, audio: File?, contact: ContactEntry?,
        reminders: List<WorkReminder>
    ): JSONObject {
        val key = settings.apiKey()
        require(key.isNotBlank()) { "کلید Gemini در تنظیمات فعال نشده است" }
        require(transcript.isNotBlank() || (audio != null && audio.isFile)) {
            "متن یا صوتی برای پردازش وجود ندارد"
        }
        val clock = ZonedDateTime.now()
        val context = JSONObject()
            .put("now_local", clock.toString())
            .put("timezone", ZoneId.systemDefault().id)
            .put("transcript_hint", transcript.take(10000))
            .put("known_contact", JSONObject()
                .put("name", contact?.name.orEmpty().take(100))
                .put("company", contact?.company.orEmpty().take(100))
                .put("role", contact?.role.orEmpty().take(100))
                .put("category", contact?.category.orEmpty().take(100)))
            .put("known_reminders", JSONArray().apply {
                reminders.take(15).forEach { event ->
                    put(JSONObject().put("id", event.id)
                        .put("title", event.title.take(150))
                        .put("due_local", Instant.ofEpochMilli(event.dueAt)
                            .atZone(ZoneId.systemDefault()).toString()))
                }
            })
        val instructions = """
You are the restricted Persian POYARA CRM planning agent.
Use the spoken audio (when supplied) as the best evidence. For loud locations,
focus on the main speaker, not bystanders. Keep all digits in spoken order.
If the person first gives a wrong surname/number then explicitly corrects
themselves, keep only the later correction as the proposed final fact.
Produce corrected_text in accurate Persian spelling, without inventing words.
Return structured JSON only, exactly matching the schema.
Extract people, phone number, organization, role, business category and notes.
For this ONE active contact, emit actions as appropriate: correct_name,
add_phone, set_role, set_category, create_reminder, update_reminder,
delete_reminder, append_note. Do not execute actions yourself.
Only assign a target_reminder_id from known_reminders. Do not guess IDs.
For a new meeting with an explicit future day and a colloquial hour of 3,
use 15:00 if the language clearly implies working afternoon, otherwise ask
"صبح یا عصر؟". If day/date is omitted, ask which date and mark needs review.
Use Gregorian YYYY-MM-DD and 24-hour HH:mm in user's device timezone.
Preserve an existing date on a specific meeting time correction when possible.
Do not invent missing names, exact phone digits or appointments.
For any uncertainty set requires_confirmation=true and add questions.
If multiple people are mentioned with unclear phone ownership, ask rather
than change existing contact. Avoid duplicate creation.
Actions must specify type, value (or null), target_reminder_id (or null),
title (or null), date_iso (or null), time_local (or null), and boolean
requires_confirmation. Return empty arrays for no suggested changes.
""".trimIndent()
        val parts = JSONArray().put(JSONObject().put("text",
            instructions + "\n\nCONTEXT:\n" + context.toString()))
        if (audio != null && audio.isFile) {
            require(audio.length() in 256L..(10L * 1024 * 1024)) {
                "فایل صوتی برای Gemini باید زیر ۱۰ مگابایت باشد"
            }
            val encoded = Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP)
            parts.put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", "audio/m4a")
                .put("data", encoded)))
        }
        val payload = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject()
                .put("temperature", 0)
                .put("maxOutputTokens", 4096)
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema()))
        val req = (URL("https://generativelanguage.googleapis.com/v1beta/" +
            "models/$model:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("x-goog-api-key", key)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            req.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val status = req.responseCode
            val data = (if (status in 200..299) req.inputStream
                else req.errorStream)?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }.orEmpty()
            when (status) {
                429 -> error("سهمیه رایگان Gemini تمام شده است؛ متن و صوت ذخیره هستند")
                401, 403 -> error("دسترسی کلید Gemini مجاز نیست یا مدل در این حساب فعال نیست")
                in 200..299 -> {}
                else -> error("خطای پردازش Gemini (HTTP $status)")
            }
            val response = JSONObject(data)
            val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
                ?: error("Gemini پاسخی برنگرداند")
            val reason = candidate.optString("finishReason")
            if (reason.isNotBlank() && reason != "STOP")
                error("تحلیل Gemini کامل نشد؛ درخواست را دوباره امتحان کن")
            val fragments = candidate.optJSONObject("content")?.optJSONArray("parts")
                ?: error("متنی در پاسخ Gemini نبود")
            val raw = buildString {
                for (i in 0 until fragments.length()) {
                    fragments.optJSONObject(i)?.optString("text")?.let { append(it) }
                }
            }.trim()
            val result = JSONObject(raw)
            check(result.optJSONArray("contacts") != null &&
                result.optJSONArray("reminders") != null &&
                result.optJSONArray("questions") != null &&
                result.optJSONArray("actions") != null) {
                "ساختار نتیجه Gemini معتبر نیست"
            }
            return result
        } finally {
            req.disconnect()
        }
    }
}
