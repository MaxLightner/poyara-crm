package com.poyara.crm

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Strict, free and entirely local action extractor for *common Persian CRM commands*.
 * Google SpeechRecognizer supplies text. This is NOT a general-purpose AI model.
 * Ambiguous dates, 12-hour clock times and multi-person references are NOT executed.
 * Original speech must always be retained in the notes for correction.
 */
internal data class VoiceAppointmentAction(
    val category: String,
    val dueAt: Long?,
    val minuteOfDay: Int?,
    val leadDays: Int,
    val correction: Boolean,
    val warning: String?
)
internal data class VoiceActions(
    val person: String?,
    val company: String?,
    val phone: String?,
    val correctPerson: Boolean,
    val correctCompany: Boolean,
    val correctPhone: Boolean,
    val phoneWasMentioned: Boolean,
    val appointment: VoiceAppointmentAction?,
    val alerts: List<String>
)

internal object PersianVoiceInterpreter {
    private val phoneHint = Regex("""شماره|تلفن|موبایل|همراه""")
    private val correctionHint = Regex("""اشتباه|غلط|تصحیح|اصلاح|درستش|درستِ|درست |صحیح|عوض کن|تغییر بده|جایگزین""")
    private val spokenDigits = mapOf(
        "صفر" to 0, "یک" to 1, "یه" to 1, "دو" to 2, "سه" to 3, "چهار" to 4,
        "پنج" to 5, "شش" to 6, "شیش" to 6, "هفت" to 7, "هشت" to 8,
        "نه" to 9, "نُه" to 9, "ده" to 10, "یازده" to 11, "دوازده" to 12,
        "سیزده" to 13, "چهارده" to 14, "پانزده" to 15, "پونزده" to 15,
        "شانزده" to 16, "هفده" to 17, "هجده" to 18, "نوزده" to 19,
        "بیست" to 20, "سی" to 30, "چهل" to 40, "پنجاه" to 50,
        "شصت" to 60, "هفتاد" to 70, "هشتاد" to 80, "نود" to 90,
        "صد" to 100, "یکصد" to 100, "دویست" to 200, "سیصد" to 300,
        "چهارصد" to 400, "پانصد" to 500, "ششصد" to 600,
        "هفتصد" to 700, "هشتصد" to 800, "نهصد" to 900
    )
    // More specific weekdays first: شنبه is a suffix of پنجشنبه/سه شنبه.
    private val dayNames = listOf(
        "یک شنبه" to DayOfWeek.SUNDAY, "یکشنبه" to DayOfWeek.SUNDAY,
        "دو شنبه" to DayOfWeek.MONDAY, "دوشنبه" to DayOfWeek.MONDAY,
        "سه شنبه" to DayOfWeek.TUESDAY, "سه‌شنبه" to DayOfWeek.TUESDAY,
        "چهار شنبه" to DayOfWeek.WEDNESDAY, "چهارشنبه" to DayOfWeek.WEDNESDAY,
        "پنج شنبه" to DayOfWeek.THURSDAY, "پنجشنبه" to DayOfWeek.THURSDAY,
        "جمعه" to DayOfWeek.FRIDAY, "شنبه" to DayOfWeek.SATURDAY
    )

    fun normalize(text: String): String = buildString {
        for (ch in text.replace('ي', 'ی').replace('ك', 'ک').replace('\u200c', ' ')) {
            val fa = "۰۱۲۳۴۵۶۷۸۹".indexOf(ch)
            val ar = "٠١٢٣٤٥٦٧٨٩".indexOf(ch)
            append(when {
                fa >= 0 -> ('0'.code + fa).toChar()
                ar >= 0 -> ('0'.code + ar).toChar()
                else -> ch
            })
        }
    }.replace(Regex("""\s+"""), " ").trim()

    fun mobileNumbers(text: String): List<String> {
        val normal = normalize(text)
        val direct = Regex("""(?<!\d)(?:\+98|0098|98|0)?9(?:[\s\-]*\d){9}(?!\d)""")
            .findAll(normal)
            .mapNotNull { canonicalPhone(it.value.filter { ch -> ch.isDigit() }) }
            .toList()
        val fromWords = if (phoneHint.containsMatchIn(normal)) spokenMobile(normal) else null
        return (direct + listOfNotNull(fromWords)).distinct()
    }

    private fun canonicalPhone(raw: String): String? {
        val number = when {
            raw.matches(Regex("""09\d{9}""")) -> raw
            raw.matches(Regex("""989\d{9}""")) -> "0" + raw.drop(2)
            raw.matches(Regex("""00989\d{9}""")) -> "0" + raw.drop(4)
            raw.matches(Regex("""9\d{9}""")) -> "0" + raw
            else -> return null
        }
        return number
    }

    private fun spokenMobile(normal: String): String? {
        val cue = Regex("""(?:شماره(?: تلفن| موبایل| تماس| همراه)?|تلفنش|موبایلش|شماره ش|شماره اش|شماره‌اش)""")
            .findAll(normal).lastOrNull() ?: return null
        val tail = normal.substring(cue.range.last + 1).take(185)
        val words = Regex("""[\p{L}0-9]+""").findAll(tail).map { it.value }.take(38).toList()
        val digits = StringBuilder()
        var i = 0
        var started = false
        while (i < words.size && digits.length < 14) {
            val word = words[i]
            val value = spokenDigits[word] ?: word.toIntOrNull()?.takeIf { it in 0..999 }
            if (value == null) {
                if (word == "و" && started) { i++; continue }
                if (started) break
                i++
                continue
            }
            started = true
            var merged = value
            var skip = 0
            // "نهصد و پانزده" -> 915; "هشتاد و دو" -> 82.
            if (value in 100..900 && value % 100 == 0 || value in 20..90 && value % 10 == 0) {
                val nextOffset = if (words.getOrNull(i + 1) == "و") 2 else 1
                val next = words.getOrNull(i + nextOffset)?.let { spokenDigits[it] }
                val cap = if (value >= 100) 99 else 9
                if (next != null && next in 1..cap) {
                    merged += next
                    skip = nextOffset
                    if (value >= 100 && next in 20..90 && next % 10 == 0) {
                        val k = i + skip + 1
                        val lastOffset = if (words.getOrNull(k) == "و") 1 else 0
                        val unit = words.getOrNull(k + lastOffset)?.let { spokenDigits[it] }
                        if (unit != null && unit in 1..9) {
                            merged += unit
                            skip += 1 + lastOffset
                        }
                    }
                }
            }
            digits.append(merged)
            i += 1 + skip
            canonicalPhone(digits.toString())?.let { return it }
        }
        return canonicalPhone(digits.toString())
    }

    fun parse(
        rawText: String,
        currentPerson: String = "",
        currentCompany: String = "",
        currentPhone: String = "",
        nowEpochMillis: Long = System.currentTimeMillis(),
        timezone: ZoneId = ZoneId.systemDefault()
    ): VoiceActions {
        val text = normalize(rawText)
        val correcting = correctionHint.containsMatchIn(text)
        val correctPhone = correcting && phoneHint.containsMatchIn(text)
        val correctCompany = correcting && Regex("""مجموعه|فروشگاه|شرکت|برند|مبلمان|کابینت|کمپانی|بنگاه""")
            .containsMatchIn(text) && Regex("""اسم|نام|مجموعه|شرکت|برند""").containsMatchIn(text)
        val correctPerson = correcting &&
            Regex("""اسم|نام|آقای|خانم|مشتری""").containsMatchIn(text) &&
            !correctCompany

        val people = Regex("""(?:آقای|خانم)\s+([\p{L}]{2,30})""")
            .findAll(text).map { it.groupValues[1] }.filterNot {
                it in setOf("شماره", "مجموعه", "فروشگاه", "شرکت", "فردا")
            }.toList()
        val companies = Regex("""(?:مبلمان|کابینت|فروشگاه|شرکت|مجموعه|برند|کارخانه|استودیو)\s+([\p{L}]{2,30})""")
            .findAll(text).map { it.value.trim() }.filterNot {
                it.endsWith("آقای") || it.endsWith("خانم") || it.endsWith("شماره")
            }.distinct().toList()
        val phones = mobileNumbers(text)
        val chosenPerson = if (correctPerson) people.lastOrNull() else people.distinct().singleOrNull()
        val chosenCompany = if (correctCompany) companies.lastOrNull() else companies.singleOrNull()
        // Even a valid number is unsafe to attach when the user mentioned
        // several people and did not explicitly identify a correction.
        val chosenPhone = if (people.distinct().size > 1 && !correctPhone) null
            else phones.singleOrNull()
        val phoneWasMentioned = phoneHint.containsMatchIn(text) && !Regex("""شماره(?:\s+تماس)?\s*(?:ندارم|نداریم|نامشخص|معلوم نیست)""").containsMatchIn(text)
        val alerts = mutableListOf<String>()
        if (phoneWasMentioned && chosenPhone == null)
            alerts += "شماره موبایل در متن گوگل واضح نیست؛ شماره را یک بار جداگانه و شمرده بگو."
        if (people.distinct().size > 1 && !correctPerson)
            alerts += "بیش از یک شخص گفته شد؛ نام و شماره به فرد اشتباه نسبت داده نمی‌شود."
        if (phones.size > 1)
            alerts += "چند شماره گفته شد؛ برای ثبت شماره یک مخاطب آن را مشخص کن."

        // A new voice may correct existing values only with an explicit correction phrase.
        // Otherwise, MainActivity fills EMPTY fields only.
        val appointment = parseAppointment(text, correcting, nowEpochMillis, timezone)
        appointment?.warning?.let { alerts += it }
        if (correctPhone && currentPhone.isNotBlank() && chosenPhone == null) {
            alerts += "شماره قبلی حفظ شد، چون شماره جایگزین قابل تشخیص نبود."
        }
        return VoiceActions(chosenPerson, chosenCompany, chosenPhone,
            correctPerson, correctCompany, correctPhone, phoneWasMentioned, appointment,
            alerts.distinct())
    }

    private fun parseAppointment(text: String, correcting: Boolean, nowMs: Long,
                                 zone: ZoneId): VoiceAppointmentAction? {
        val mentionsMeeting = Regex("""جلسه|قرار|ملاقات""").containsMatchIn(text)
        val mentionsCall = Regex("""زنگ|تماس (?:بگیر|بزن|دارم|کنیم|کنم|بگیریم|تلفنی)|پیگیری""")
            .containsMatchIn(text)
        if (!mentionsMeeting && !mentionsCall) return null
        val category = if (mentionsMeeting) "جلسه" else "تماس / پیگیری"
        val appointmentCorrection = correcting &&
            (Regex("""(?:ساعت|زمان|روز|تاریخ)\s*(?:قرار|جلسه|تماس)|(?:قرار|جلسه|تماس).{0,45}(?:اشتباه|غلط|اصلاح|تغییر|درستش)""")
                .containsMatchIn(text))
        val timeMatch = Regex("""ساعت\s*(?:(?:درست|جدید|اصلاح شده)\s*)?([0-9]{1,2}|[\p{L}]+)(?:[:٫.]([0-9]{1,2}))?""")
            .findAll(text)
            .firstOrNull { match ->
                val word = match.groupValues[1]
                word.toIntOrNull() != null || spokenDigits.containsKey(word)
            }
        val hourWord = timeMatch?.groupValues?.getOrNull(1)
        var hour = hourWord?.toIntOrNull() ?: hourWord?.let { spokenDigits[it] }
        var minute = timeMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        val afterHour = timeMatch?.range?.last?.plus(1)?.let { text.substring(it).take(22) }.orEmpty()
        if (afterHour.contains("نیم")) minute = 30
        else if (afterHour.contains("ربع")) minute = 15
        if (hour == null || hour !in 0..23 || minute !in 0..59)
            return VoiceAppointmentAction(category, null, null, leadDays(text), appointmentCorrection,
                "برای ثبت $category، ساعت را هم بگو؛ مثلاً فردا ساعت 4 عصر.")

        val mentionsPm = Regex("""بعدازظهر|بعد از ظهر|عصر|شب""").containsMatchIn(text)
        val mentionsAm = Regex("""صبح|بامداد""").containsMatchIn(text)
        val mentionsNoon = Regex("""ظهر""").containsMatchIn(text) && !mentionsPm
        if (hour in 1..11) {
            hour = when {
                mentionsPm -> hour + 12
                mentionsAm -> hour
                mentionsNoon && hour == 11 -> hour
                mentionsNoon -> hour + 12
                else -> hour
            }
        } else if (hour == 12 && mentionsAm) hour = 0

        val minuteOfDay = hour * 60 + minute
        if (hourWord != null && (hourWord.toIntOrNull() ?: 0) <= 12 &&
            !mentionsPm && !mentionsAm && !mentionsNoon && hour in 1..12) {
            return VoiceAppointmentAction(category, null, minuteOfDay, leadDays(text), appointmentCorrection,
                "ساعت $category صبح است یا عصر؟ با ویس کوتاه اصلاح کن.")
        }

        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val day = when {
            text.contains("پس فردا") || text.contains("پسفردا") -> today.plusDays(2)
            text.contains("فردا") -> today.plusDays(1)
            text.contains("امروز") -> today
            else -> {
                val dayMatch = dayNames.firstOrNull { (word, _) -> text.contains(word) }
                if (dayMatch != null && Regex("""این |آینده|هفته بعد|هفته آینده|روز """).containsMatchIn(text))
                    today.with(TemporalAdjusters.next(dayMatch.second))
                else null
            }
        }
        if (day == null) {
            return VoiceAppointmentAction(category, null, minuteOfDay, leadDays(text),
                appointmentCorrection, "روز $category مشخص نیست؛ مثلاً بگو «فردا ساعت 4 عصر» یا «این سه شنبه ساعت 4 عصر».")
        }
        val due = LocalDateTime.of(day, java.time.LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()
        if (due <= nowMs) return VoiceAppointmentAction(category, null, minuteOfDay, leadDays(text),
            appointmentCorrection, "زمان گفته‌شده در گذشته است؛ تاریخ یا ساعت آینده را بگو.")
        return VoiceAppointmentAction(category, due, minuteOfDay, leadDays(text), appointmentCorrection, null)
    }

    private fun leadDays(text: String): Int {
        val literal = Regex("""([0-7])\s*روز\s*(?:قبل|زودتر|پیش)""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (literal != null) return literal
        val words = mapOf("صفر" to 0, "یک" to 1, "دو" to 2,
            "سه" to 3, "چهار" to 4, "پنج" to 5, "شش" to 6, "هفت" to 7)
        return words.entries.firstOrNull {
            text.contains(it.key + " روز قبل") || text.contains(it.key + " روز زودتر")
        }?.value ?: 2
    }
}
