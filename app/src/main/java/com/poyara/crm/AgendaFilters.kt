package com.poyara.crm

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The agenda must not silently discard an appointment for next Saturday:
 * "this week" is a filter, not the only view. All upcoming is default.
 */
internal object AgendaFilters {
    fun visible(
        all: List<WorkReminder>,
        nowMillis: Long,
        thisWeekOnly: Boolean,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<WorkReminder> {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val saturday = now.toLocalDate().with(
            TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))
        val weekStart = saturday.atStartOfDay(zone).toInstant().toEpochMilli()
        val weekEnd = saturday.plusDays(7).atStartOfDay(zone)
            .toInstant().toEpochMilli()
        return all.asSequence().filter { reminder ->
            if (thisWeekOnly) reminder.dueAt in weekStart until weekEnd
            else reminder.dueAt >= nowMillis - 12 * 60 * 60 * 1000L
        }.sortedBy { it.dueAt }.toList()
    }
}
