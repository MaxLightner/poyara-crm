package com.poyara.crm

/** Conservative no-LLM extraction for the optional voice-to-form shortcut.
 * Never infer links between several people or generate meeting dates.
 * The full transcript is retained as a note for manual review.
 */
internal data class VoiceQuickFields(
    val personName: String,
    val company: String,
    val phone: String
)

internal object VoiceLocalFacts {
    private fun digitsLatin(s: String): String {
        val persian = "۰۱۲۳۴۵۶۷۸۹"
        val arabic = "٠١٢٣٤٥٦٧٨٩"
        return buildString(s.length) {
            s.forEach { ch ->
                val p = persian.indexOf(ch)
                val a = arabic.indexOf(ch)
                append(when { p >= 0 -> ('0'.code + p).toChar()
                    a >= 0 -> ('0'.code + a).toChar()
                    else -> ch })
            }
        }
    }
    /**
     * A deterministic, conservative local summary for the ONE capture textbox.
     * It is not an AI agent. Always preserve every word of the transcription.
     * Never assign a phone to a person when several people are mentioned.
     */
    fun formatForCapture(transcript: String): String {
        val spoken = transcript.trim()
        if (spoken.isBlank()) return ""
        val fact = extract(spoken)
        val overview = buildList {
            if (fact.personName.isNotBlank()) add("شخص: " + fact.personName)
            if (fact.company.isNotBlank()) add("مجموعه: " + fact.company)
            if (fact.phone.isNotBlank()) add("شماره: " + fact.phone)
        }
        return if (overview.isEmpty()) "متن گفتار:\n" + spoken
        else "اطلاعات شناسایی‌شده (نیازمند بررسی):\n" +
            overview.joinToString("\n") + "\n\nمتن گفتار:\n" + spoken
    }

    fun extract(transcript: String): VoiceQuickFields {
        val normalized = digitsLatin(transcript).replace('ي','ی').replace('ك','ک')
        val numbers = Regex("(?<![0-9])(?:\\+?98|0)?9[0-9\\s-]{9,16}(?![0-9])")
            .findAll(normalized)
            .mapNotNull { match ->
                val digits = match.value.filter { it.isDigit() }
                val phone = when {
                    digits.matches(Regex("09[0-9]{9}")) -> digits
                    digits.matches(Regex("989[0-9]{9}")) -> "0" + digits.drop(2)
                    digits.matches(Regex("9[0-9]{9}")) -> "0" + digits
                    else -> null
                }
                phone
            }.distinct().toList()
        val people = Regex("(?:آقای|خانم)\\s+([آ-ی]{2,30})")
            .findAll(normalized)
            .map { it.groupValues[1] }
            .distinct().toList()
        val companies = Regex("(مبلمان|کابینت|فروشگاه|شرکت|مجموعه|برند)\\s+([آ-ی]{2,32})")
            .findAll(normalized)
            .map { it.groupValues[1] + " " + it.groupValues[2] }
            .distinct().toList()
        return VoiceQuickFields(
            personName = people.singleOrNull().orEmpty(),
            company = companies.singleOrNull().orEmpty(),
            phone = numbers.singleOrNull().orEmpty()
        )
    }
}
