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

    @Test fun recoversReversedPhoneOnlyWithExplicitPhoneCue() {
        assertEquals(listOf("09158151204"),
            PersianVoiceInterpreter.mobileNumbers("شماره تماس 40215185190"))
        assertEquals(emptyList<String>(),
            PersianVoiceInterpreter.mobileNumbers("فاکتور 40215185190"))
    }

    @Test fun extractsBusinessRoleAndIndustryCategory() {
        val result = PersianVoiceInterpreter.parse(
            "آقای حسینی مدیر مجموعه مبلمان لیپاریس است، شماره ۰۹۱۵۸۱۵۱۲۰۴")
        assertEquals("حسینی", result.person)
        assertTrue(result.role.orEmpty().contains("مدیر"))
        assertEquals("مبل‌فروشی", result.category)
        assertEquals("09158151204", result.phone)
    }

    @Test fun noDateOrAmPmDoesNotInventMeeting() {
        val result = PersianVoiceInterpreter.parse(
            "برای ساعت ۲:۳۰ برای آقای حسینی جلسه بذار", nowEpochMillis = now, timezone = zone)
        assertNull(result.appointment?.dueAt)
        assertNotNull(result.appointment?.warning)
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

    @Test fun multiPersonPhoneIsNotAttachedAutomatically() {
        val value = PersianVoiceInterpreter.parse(
            "آقای احمدی و خانم حسینی در مبلمان لیپاریس شماره ۰۹۱۵۸۱۵۱۲۰۴")
        assertNull(value.phone)
        assertTrue(value.alerts.any { it.contains("بیش از یک شخص") })
    }

    @Test fun phoneCorrectionDoesNotTurnNewMeetingIntoMeetingCorrection() {
        val value = PersianVoiceInterpreter.parse(
            "شماره اشتباه بود شماره درست ۰۹۱۵۸۱۵۱۲۰۴ و فردا ساعت ۵ عصر هم جلسه داریم",
            nowEpochMillis = now, timezone = zone)
        assertTrue(value.correctPhone)
        assertNotNull(value.appointment?.dueAt)
        assertFalse(value.appointment!!.correction)
    }

    @Test fun explicitMeetingTimeCorrectionIsRecognized() {
        val value = PersianVoiceInterpreter.parse(
            "ساعت جلسه رو اشتباه نوشتی، ساعت درست پنج عصر هست",
            nowEpochMillis = now, timezone = zone)
        assertTrue(value.appointment!!.correction)
        assertNull(value.appointment!!.dueAt)
        assertEquals(17 * 60, value.appointment!!.minuteOfDay)
    }

    @Test fun correctionsPreserveOriginalUtterances() {
        val earlier = VoiceCaptureDocument.merge("", "آقای احمدی شماره 09150000000",
            "احمدی", "مبلمان لیپاریس", "09150000000", emptyList(), emptyList())
        val updated = VoiceCaptureDocument.merge(earlier, "شماره درست 09158151204",
            "احمدی", "مبلمان لیپاریس", "09158151204", emptyList(), emptyList())
        assertFalse(updated.contains("【خلاصه خودکار پویارا】\\nشماره:"))
        assertTrue(updated.contains("شماره 09150000000"))
        assertTrue(updated.contains("شماره درست 09158151204"))
        assertEquals(0, "【خلاصه خودکار پویارا】".toRegex().findAll(updated).count())
    }
}
