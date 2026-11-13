package com.poyara.crm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Personal free-tier Google Cloudflare adapter: one direct, opt-in request. */
internal class CloudflareDirectClient(context: Context) {
    private val settings = CloudflareLocalSettings(context)
    private val model = "@cf/zai-org/glm-4.7-flash"
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
                    "correct_name", "correct_phone", "remove_phone", "add_phone",
                    "set_company", "set_role", "set_category",
                    "create_reminder", "update_reminder", "delete_reminder",
                    "append_note"))))
            .put("value", nullableStr())
            .put("target_reminder_id", nullableId())
            .put("title", nullableStr())
            .put("date_iso", nullableStr())
            .put("time_local", nullableStr())
            .put("requires_confirmation", JSONObject().put("type", "boolean"))
            .put("remind_days_before", JSONObject().put("type", "integer"))
        val actionNames = listOf("type", "value", "target_reminder_id",
            "title", "date_iso", "time_local", "requires_confirmation",
            "remind_days_before")
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
        val accountId = settings.accountId
        require(configured && key.isNotBlank() && accountId.isNotBlank()) {
            "حساب Workers AI در تنظیمات فعال نشده است"
        }
        require(transcript.isNotBlank() || audio?.isFile == true) {
            "متن یا صدایی برای تحلیل وجود ندارد"
        }
        val effectiveTranscript = if (audio?.isFile == true) {
            runCatching { transcribeAudio(audio, accountId, key) }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: transcript
        } else transcript
        require(effectiveTranscript.isNotBlank()) {
            "از فایل صوتی متنی دریافت نشد؛ از حالت گفتار زنده استفاده کن"
        }
        val clock = ZonedDateTime.now()
        val context = JSONObject()
            .put("now_local", clock.toString())
            .put("timezone", ZoneId.systemDefault().id)
            .put("transcript_hint", effectiveTranscript.take(10000))
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
Use the provided speech transcript. Keep digits in spoken order.
Never infer digits that are absent or unclear in the transcript.
If the person first gives a wrong surname/number then explicitly corrects
themselves, keep only the later correction as the proposed final fact.
Produce corrected_text in accurate Persian spelling, without inventing words.
Return structured JSON only, exactly matching the schema.
Extract people, phone number, organization, role, business category and notes.
When the speaker gives TWO or more numbers for one person, put the first valid
number into contacts[0].phone and emit one add_phone action for EACH additional
number. When correcting a number, prefer the speaker's latest explicit correction.
Never reverse or invent an unclear phone number. Different people should not
silently share one record: ask which person owns a number when unclear.
When the current record is empty, you may populate contact name/phone/company
and emit additional add_phone actions. Contact role is a free-text profession,
not a fixed enum. Use concise corrected_text in Persian.
For this ONE active contact, emit actions as appropriate: correct_name,
correct_phone, remove_phone, add_phone, set_company, set_role,
set_category, create_reminder, update_reminder,
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
requires_confirmation, and integer remind_days_before (0..7).
Return empty arrays for no suggested changes.
""".trimIndent()

        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("max_completion_tokens", 2400)
            .put("stream", false)
            // GLM 4.7 Flash is not listed among JSON Mode guaranteed models.
            // Use a strict JSON-only system instruction and validate the response.
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    instructions + "\nOutput ONLY JSON matching the schema.\n" +
                    schema().toString()))
                .put(JSONObject().put("role", "user").put("content",
                    context.toString())))
        val req = (URL("https://api.cloudflare.com/client/v4/accounts/" +
            accountId + "/ai/v1/chat/completions")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 90_000
            setRequestProperty("Authorization", "Bearer " + key)
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
                429 -> error("سهمیه یا ظرفیت Workers AI تمام شده است؛ متن محفوظ است")
                401, 403 -> error("دسترسی حساب یا کلید Cloudflare مجاز نیست")
                in 200..299 -> {}
                else -> error("Workers AI پاسخ نداد (HTTP " + status + ")")
            }
            val response = JSONObject(data)
            val message = response.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message") ?: error("مدل پاسخی برنگرداند")
            val raw = message.optString("content").trim()
            val jsonText = raw.removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val result = JSONObject(jsonText)
            check(result.optJSONArray("contacts") != null &&
                result.optJSONArray("reminders") != null &&
                result.optJSONArray("questions") != null &&
                result.optJSONArray("actions") != null) {
                "پاسخ هوش مصنوعی با ساختار دستورهای CRM سازگار نیست"
            }
            return result
        } finally {
            req.disconnect()
        }
    }

    private fun transcribeAudio(audio: File, account: String, key: String): String {
        require(audio.length() in 256L..(10L * 1024 * 1024)) {
            "فایل صوتی باید کمتر از ده مگابایت باشد"
        }
        val url = URL("https://api.cloudflare.com/client/v4/accounts/" +
            account + "/ai/run/@cf/openai/whisper")
        val req = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 90_000
            setRequestProperty("Authorization", "Bearer " + key)
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        try {
            req.outputStream.use { output -> audio.inputStream().use { it.copyTo(output) } }
            val status = req.responseCode
            if (status !in 200..299) error("تبدیل صوت با Whisper انجام نشد (HTTP $status)")
            val body = req.inputStream.bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val response = JSONObject(body)
            return response.optJSONObject("result")?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?: response.optString("text")
        } finally { req.disconnect() }
    }
}
