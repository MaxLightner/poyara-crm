package com.poyara.crm

/** Keeps a single canonical, editable capture box with the latest structured
 * facts at the top. The exact Google transcript of every session is retained
 * below; corrections update the latest summary, not the historical evidence.
 */
internal object VoiceCaptureDocument {
    private const val START = "【خلاصه خودکار پویارا】\n"
    private const val END = "\n【پایان خلاصه】"

    fun merge(
        previous: String,
        transcript: String,
        name: String,
        company: String,
        phone: String,
        reminders: List<String>,
        alerts: List<String>
    ): String {
        val withoutLastSummary = if (previous.startsWith(START) && previous.contains(END)) {
            previous.substringAfter(END).trim()
        } else previous.trim()
        val utterance = transcript.trim()
        val history = if (utterance.isEmpty() || withoutLastSummary.contains(utterance))
            withoutLastSummary
        else listOf(withoutLastSummary, "گفتار جدید:\n" + utterance)
            .filter { it.isNotEmpty() }.joinToString("\n\n")

        val summary = buildList {
            // Identity fields are shown in dedicated compact form fields above the notes.
            // Preserve raw speech in history below, never repeat name/phone in this summary.
            reminders.take(4).forEach { add("برنامه: " + it) }
            alerts.distinct().take(4).forEach { add("نیازمند بررسی: " + it) }
        }
        return if (summary.isEmpty()) history else
            START + summary.joinToString("\n") + END +
                if (history.isEmpty()) "" else "\n\n" + history
    }
}
