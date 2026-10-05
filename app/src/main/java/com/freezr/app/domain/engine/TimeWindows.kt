package com.freezr.app.domain.engine

import com.freezr.app.domain.model.Days
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Half-open interval [start, end). */
data class Window(val start: Instant, val end: Instant) {
    operator fun contains(t: Instant): Boolean = !t.isBefore(start) && t.isBefore(end)
}

/**
 * Wall-clock window maths shared by schedules, time filters and usage resets.
 *
 * Rules:
 *  - A window belongs to the day it STARTS on.
 *  - end <= start means the window crosses midnight; start == end means a full 24 h.
 *  - Local times that fall into a DST gap are shifted forward (java.time default);
 *    ambiguous local times in a DST overlap resolve to the earlier offset.
 */
object TimeWindows {
    const val MINUTES_PER_DAY = 24 * 60

    fun at(date: LocalDate, minuteOfDay: Int, zone: ZoneId): Instant {
        val m = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        return ZonedDateTime.of(date, LocalTime.of(m / 60, m % 60), zone).toInstant()
    }

    fun windowStartingOn(date: LocalDate, startMinute: Int, endMinute: Int, zone: ZoneId): Window {
        val start = at(date, startMinute, zone)
        val endDate = if (endMinute <= startMinute) date.plusDays(1) else date
        return Window(start, at(endDate, endMinute, zone))
    }

    /** All windows whose start date is in [from, to] (inclusive) and whose weekday is enabled. */
    fun windows(
        startMinute: Int,
        endMinute: Int,
        daysMask: Int,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
    ): List<Window> {
        val out = ArrayList<Window>()
        var d = from
        while (!d.isAfter(to)) {
            if (Days.contains(daysMask, d.dayOfWeek)) out += windowStartingOn(d, startMinute, endMinute, zone)
            d = d.plusDays(1)
        }
        return out
    }

    /** The window containing [now], if any. Only windows started yesterday or today can contain now. */
    fun activeWindow(startMinute: Int, endMinute: Int, daysMask: Int, now: Instant, zone: ZoneId): Window? {
        val today = now.atZone(zone).toLocalDate()
        return windows(startMinute, endMinute, daysMask, today.minusDays(1), today, zone)
            .lastOrNull { now in it }
    }

    /** Start of the usage day containing [now] for a reset at [resetMinute] local time. */
    fun usageDayStart(now: Instant, zone: ZoneId, resetMinute: Int): Instant {
        val today = now.atZone(zone).toLocalDate()
        val todayReset = at(today, resetMinute, zone)
        return if (now.isBefore(todayReset)) at(today.minusDays(1), resetMinute, zone) else todayReset
    }

    /** The usage "day" key (the local date the usage day started on). */
    fun usageDay(now: Instant, zone: ZoneId, resetMinute: Int): LocalDate =
        usageDayStart(now, zone, resetMinute).atZone(zone).toLocalDate()

    fun nextUsageReset(now: Instant, zone: ZoneId, resetMinute: Int): Instant =
        at(usageDay(now, zone, resetMinute).plusDays(1), resetMinute, zone)
}
