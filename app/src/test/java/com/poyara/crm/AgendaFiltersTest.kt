package com.poyara.crm

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class AgendaFiltersTest {
    private val iran = ZoneId.of("Asia/Tehran")
    private fun epoch(day: Int, hour: Int = 15): Long =
        LocalDateTime.of(2026, 11, day, hour, 30).atZone(iran)
            .toInstant().toEpochMilli()
    private fun reminder(id: Long, day: Int): WorkReminder =
        WorkReminder(id, 1L, "جلسه با آقای حسینی", epoch(day), 2, false)

    @Test fun allUpcomingIncludesNextSaturday() {
        // Friday November 13, event Saturday Nov 14 belongs to the NEXT week.
        val visible = AgendaFilters.visible(listOf(reminder(1,14)),
            epoch(13, 10), false, iran)
        assertEquals(listOf(1L), visible.map { it.id })
    }

    @Test fun thisWeekHidesNextSaturdayButShowsThisFriday() {
        val reminders = listOf(reminder(1, 13), reminder(2, 14))
        val result = AgendaFilters.visible(reminders, epoch(13,10), true, iran)
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test fun showUpcomingIsSortedRegardlessOfDatabaseRowOrder() {
        val result = AgendaFilters.visible(
            listOf(reminder(3, 19), reminder(2, 15), reminder(1, 14)),
            epoch(13,10), false, iran)
        assertEquals(listOf(1L,2L,3L), result.map { it.id })
    }

    @Test fun currentSaturdayIsPartOfNewWeek() {
        val result = AgendaFilters.visible(
            listOf(reminder(1, 14), reminder(2, 15)),
            epoch(14, 10), true, iran)
        assertEquals(listOf(1L,2L), result.map { it.id })
    }
}
