package com.poyara.crm

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class VoiceImportReport(
    val contactsCreated: Int,
    val contactsUpdated: Int,
    val remindersCreated: Int,
    val warnings: List<String>
)

/** Apply only a user-approved, locally persisted proposal.
 * A database transaction and a unique job ID prevent duplicate import.
 * The model cannot run SQL, send messages, call customers or change GPS.
 */
internal object VoiceImportEngine {
    private fun field(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "").trim()

    private fun sameText(a: String, b: String) =
        a.trim().replace("ي", "ی").replace("ك", "ک")
            .equals(b.trim().replace("ي", "ی").replace("ك", "ک"), true)

    private fun notes(role: String, address: String, detail: String) =
        listOfNotNull(
            role.takeIf { it.isNotBlank() }?.let { "سمت: " + it },
            address.takeIf { it.isNotBlank() }?.let { "آدرس: " + it },
            detail.takeIf { it.isNotBlank() }
        ).joinToString("\n")

    private fun addNote(old: String, new: String): String = when {
        new.isBlank() -> old
        old.contains(new) -> old
        old.isBlank() -> new
        else -> old + "\n\n" + new
    }

    fun apply(context: Context, db: CrmDb, review: VoiceReview): VoiceImportReport {
        require(!review.applied) { "این صوت قبلاً در CRM ثبت شده است" }
        val result = JSONObject(review.resultJson)
        val people = result.optJSONArray("contacts")
        val plans = result.optJSONArray("reminders")
        val observations = result.optJSONArray("observations")
        val warnings = mutableListOf<String>()
        val scheduled = mutableListOf<WorkReminder>()
        var created = 0
        var updated = 0
        var reminders = 0
        val connection = db.writableDatabase
        connection.beginTransaction()
        try {
            val existingReviews = db.voiceReview(review.audioPath)
            require(existingReviews?.applied != true && existingReviews?.jobId == review.jobId) {
                "این فایل تغییر کرده یا قبلاً ثبت شده است"
            }
            val nowPeople = db.all().toMutableList()
            val rootsByCompany = mutableMapOf<String, Long>()
            nowPeople.filter { it.company.isNotBlank() }.forEach { entry ->
                val key = entry.company.trim().lowercase(Locale.ROOT)
                if (entry.groupId == null || key !in rootsByCompany) {
                    rootsByCompany[key] = entry.groupId ?: entry.id
                }
            }

            val orderedPeople = (0 until (people?.length() ?: 0)).sortedBy { index ->
                val role = people?.optJSONObject(index)?.optString("role", "").orEmpty()
                if (listOf("مدیر", "مالک", "صاحب", "manager", "owner")
                        .any { role.contains(it, ignoreCase = true) }) 0 else 1
            }
            for (index in orderedPeople) {
                val person = people!!.optJSONObject(index) ?: continue
                val name = field(person, "name")
                val number = field(person, "phone")
                val company = field(person, "company")
                val related = field(person, "related_to")
                val role = field(person, "role")
                val address = field(person, "address")
                val detail = field(person, "note")
                val tagAllowlist = setOf(
                    "مبلمان", "کابینت", "معماری", "پیگیری", "مشتری ثابت", "مهم"
                )
                val parsedTags = mutableSetOf<String>()
                person.optJSONArray("tags")?.let { tags ->
                    for (j in 0 until tags.length()) {
                        val tag = tags.optString(j).trim()
                        if (tag in tagAllowlist) parsedTags += tag
                    }
                }
                if (name.isBlank() && number.isBlank()) {
                    warnings += "رکورد " + (index + 1) + " بدون نام و شماره بود و وارد نشد."
                    continue
                }
                val normalizedNumber = normalizePhone(number)
                val phoneCandidates = if (normalizedNumber.isBlank()) emptyList() else
                    nowPeople.filter { normalizePhone(it.phone) == normalizedNumber }
                if (phoneCandidates.size > 1) {
                    warnings += "شماره " + number + " بین چند مخاطب تکراری است؛ بررسی دستی لازم است."
                    continue
                }
                val nameCandidates = if (phoneCandidates.isEmpty() && name.isNotBlank())
                    nowPeople.filter {
                        sameText(it.name, name) &&
                            (company.isBlank() || sameText(it.company, company))
                    } else emptyList()
                if (nameCandidates.size > 1) {
                    warnings += "برای " + name + " چند مخاطب هم‌نام پیدا شد."
                    continue
                }
                val previous = phoneCandidates.singleOrNull() ?: nameCandidates.singleOrNull()
                val companyKey = company.lowercase(Locale.ROOT).trim()
                val relatedRoot = nowPeople.firstOrNull {
                    related.isNotBlank() &&
                        (sameText(it.name, related) || sameText(it.company, related))
                }?.let { it.groupId ?: it.id }
                val groupRoot = if (companyKey.isNotBlank()) rootsByCompany[companyKey]
                    else relatedRoot
                val mergedNote = addNote(previous?.note.orEmpty(), notes(role, address, detail))
                val finalCompany = company.ifBlank { previous?.company.orEmpty() }
                val id = db.save(
                    previous?.id ?: 0L,
                    name.ifBlank { previous?.name.orEmpty() },
                    number.ifBlank { previous?.phone.orEmpty() },
                    mergedNote,
                    (previous?.tags ?: emptySet()) + parsedTags,
                    finalCompany,
                    if (previous != null) previous.groupId ?: groupRoot else groupRoot
                )
                if (previous == null) created++ else updated++
                if (companyKey.isNotBlank() && groupRoot == null) {
                    rootsByCompany[companyKey] = id
                } else if (relatedRoot != null && id != relatedRoot && groupRoot == null) {
                    db.setGroup(id, relatedRoot)
                }
                nowPeople.clear()
                nowPeople.addAll(db.all())
            }

            if ((observations?.length() ?: 0) > 0) {
                val additional = buildList {
                    for (i in 0 until observations!!.length()) {
                        observations.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }.joinToString("\n")
                val existingEntry = db.voiceUpload(review.audioPath)?.entryId ?: 0L
                val contact = db.all().firstOrNull { it.id == existingEntry }
                if (contact != null && additional.isNotBlank()) {
                    db.save(
                        contact.id, contact.name, contact.phone,
                        addNote(contact.note, additional), contact.tags,
                        contact.company, contact.groupId
                    )
                } else if (additional.isNotBlank()) {
                    warnings += "نکات تکمیلی بدون مخاطب مشخص در متن ویس حفظ شدند؛ می‌توانی آن‌ها را بررسی کنی."
                }
            }

            val dateParser = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("Asia/Tehran")
            }
            for (index in 0 until (plans?.length() ?: 0)) {
                val plan = plans!!.optJSONObject(index) ?: continue
                val name = field(plan, "person_name")
                val company = field(plan, "company")
                val title = field(plan, "title")
                val date = field(plan, "date_iso")
                val time = field(plan, "time_local")
                if (plan.optBoolean("requires_confirmation", true) ||
                    date.isBlank() || time.isBlank() || title.isBlank()
                ) {
                    warnings += "قرار «" + title + "» تاریخ یا ساعت قطعی ندارد؛ از تقویم ثبت کن."
                    continue
                }
                val due: Date? = runCatching { dateParser.parse(date + " " + time) }.getOrNull()
                if (due == null || due.time <= System.currentTimeMillis()) {
                    warnings += "زمان «" + title + "» معتبر یا آینده نیست."
                    continue
                }
                val candidates = nowPeople.filter {
                    (name.isNotBlank() && sameText(it.name, name)) &&
                        (company.isBlank() || sameText(it.company, company))
                }
                val target = candidates.singleOrNull()
                if (target == null) {
                    warnings += "مخاطب قرار «" + title + "» دقیق شناسایی نشد."
                    continue
                }
                val lead = plan.optInt("remind_days_before", 2).coerceIn(0, 7)
                val id = db.addReminder(target.id, title, due.time, lead)
                val reminder = db.allReminders(includeDone = true).first { it.id == id }
                scheduled += reminder
                reminders++
            }
            db.markVoiceReviewApplied(review.jobId)
            connection.setTransactionSuccessful()
        } finally {
            connection.endTransaction()
        }
        ReminderScheduler.createChannel(context)
        scheduled.forEach { ReminderScheduler.schedule(context, it) }
        return VoiceImportReport(created, updated, reminders, warnings)
    }
}
