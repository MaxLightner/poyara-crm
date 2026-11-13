package com.poyara.crm

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class PersianVoiceInterpreterTest {
    private val zone = ZoneId.of("Asia/Tehran")
    private val now = ZonedDateTime.of(
        LocalDate.of(2026, 11, 13), LocalTime.of(9, 30), zone).toInstant().toEpochMilli()

    @Test fun recognizesOrdinaryMobileNumber() {
        assertEquals(listOf("09158151204"),
            PersianVoiceInterpreter.mobileNumbers("شماره آقای احمدی ۰۹۱۵۸۱۵۱۲۰۴"))
    }

    @Test fun recognizesSpokenDigitByDigitMobileNumber() {
        assertEquals(listOf("09158151204"),
            PersianVoiceInterpreter.mobileNumbers(
                "شماره تلفن صفر نه یک پنج هشت یک پنج یک دو صفر چهار"))
    }

    @Test fun recognizesSpokenHundredsChunks() {
        assertEquals(listOf("09158151204"),
            PersianVoiceInterpreter.mobileNumbers(
                "شماره تلفن صفر نهصد و پانزده هشتصد و پانزده دوازده صفر چهار"))
    }

    @Test fun extractsNameBusinessAndUncertainPhone() {
        val result = PersianVoiceInterpreter.parse(
            "نام مشتری آقای احمدی مبلمان لیپاریس شماره تلفن رو بعدا میگم")
        assertEquals("احمدی", result.person)
        assertEquals("مبلمان لیپاریس", result.company)
        assertNull(result.phone)
        assertTrue(result.alerts.any { it.contains("شماره") })
    }

    @Test fun explicitPhoneCorrection() {
        val result = PersianVoiceInterpreter.parse(
            "شماره قبلی اشتباه بود شماره درستش 09158151204",
            currentPhone = "09150000000")
        assertTrue(result.correctPhone)
        assertEquals("09158151204", result.phone)
    }

    @Test fun tomorrowMeetingWithReminderTwoDaysBefore() {
        val result = PersianVoiceInterpreter.parse(
            "فردا ساعت چهار و نیم بعد از ظهر با آقای احمدی جلسه دارم دو روز قبل یادآوری کن",
            nowEpochMillis = now, timezone = zone
        )
        assertEquals("جلسه", result.appointment?.category)
        assertEquals(2, result.appointment?.leadDays)
        val local = java.time.Instant.ofEpochMilli(result.appointment!!.dueAt!!)
            .atZone(zone).toLocalDateTime()
        assertEquals(LocalDate.of(2026,11,14), local.toLocalDate())
        assertEquals(LocalTime.of(16,30), local.toLocalTime())
    }

    @Test fun unclearClockTimeIsNotScheduled() {
        val result = PersianVoiceInterpreter.parse(
            "فردا ساعت سه جلسه داریم",
            nowEpochMillis = now, timezone = zone)
        assertNull(result.appointment!!.dueAt)
        assertNotNull(result.appointment!!.warning)
    }

    @Test fun tuesdayNotMistakenForSaturday() {
        val result = PersianVoiceInterpreter.parse(
            "این سه شنبه ساعت 15:30 جلسه داریم",
            nowEpochMillis = now, timezone = zone)
        val day = java.time.Instant.ofEpochMilli(result.appointment!!.dueAt!!)
            .atZone(zone).dayOfWeek
        assertEquals(java.time.DayOfWeek.TUESDAY, day)
    }

    @Test fun correctionsPreserveOriginalUtterances() {
        val earlier = VoiceCaptureDocument.merge("", "آقای احمدی شماره 09150000000",
            "احمدی", "مبلمان لیپاریس", "09150000000", emptyList(), emptyList())
        val updated = VoiceCaptureDocument.merge(earlier, "شماره درست 09158151204",
            "احمدی", "مبلمان لیپاریس", "09158151204", emptyList(), emptyList())
        assertTrue(updated.contains("شماره: 09158151204"))
        assertTrue(updated.contains("شماره 09150000000"))
        assertTrue(updated.contains("شماره درست 09158151204"))
        assertEquals(1, "【خلاصه خودکار پویارا】".toRegex().findAll(updated).count())
    }
}
