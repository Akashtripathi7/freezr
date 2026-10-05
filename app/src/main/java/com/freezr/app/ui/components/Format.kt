package com.freezr.app.ui.components

import com.freezr.app.domain.engine.TimeWindows
import com.freezr.app.domain.model.Days
import com.freezr.app.domain.model.ScheduleRule
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

object Format {
    private val timeFmt: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

    fun minuteOfDay(m: Int): String = LocalTime.of(m / 60, m % 60).format(timeFmt)

    fun time(instant: Instant, zone: ZoneId): String = instant.atZone(zone).toLocalTime().format(timeFmt)

    /** "Tue 8:00 AM" when not today, "8:00 AM" when today. */
    fun dayTime(instant: Instant, now: Instant, zone: ZoneId): String {
        val d = instant.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when (d) {
            today -> time(instant, zone)
            today.plusDays(1) -> "Tomorrow ${time(instant, zone)}"
            else -> "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${time(instant, zone)}"
        }
    }

    /** 1h 05m / 12m / 45s */
    fun duration(d: Duration): String {
        val s = d.seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        return when {
            h > 0 -> "%dh %02dm".format(h, m)
            m > 0 -> "%dm".format(m)
            else -> "%ds".format(s)
        }
    }

    /** 01:04:09 or 04:09 — for live countdowns. */
    fun countdown(d: Duration): String {
        val s = d.seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    fun days(mask: Int): String = when (mask) {
        Days.ALL -> "Every day"
        Days.WEEKDAYS -> "Weekdays"
        Days.WEEKEND -> "Weekends"
        Days.NONE -> "No days"
        else -> DayOfWeek.entries.filter { Days.contains(mask, it) }
            .joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }

    fun timeRange(start: Int, end: Int): String =
        if (start == end) "All day" else "${minuteOfDay(start)} → ${minuteOfDay(end)}"

    /**
     * Human preview of a schedule relative to now:
     *  "Frozen now until 8:00 AM", "Frozen tonight 11:00 PM → 8:00 AM",
     *  "Next: Wed 11:00 PM → 8:00 AM", "Pick at least one day".
     */
    fun schedulePreview(rule: ScheduleRule, now: Instant, zone: ZoneId): String {
        if (rule.daysMask == Days.NONE) return "Pick at least one day"
        val active = TimeWindows.activeWindow(rule.startMinute, rule.endMinute, rule.daysMask, now, zone)
        if (active != null) return "Frozen now until ${dayTime(active.end, now, zone)}"
        val today = now.atZone(zone).toLocalDate()
        val next = TimeWindows.windows(rule.startMinute, rule.endMinute, rule.daysMask, today, today.plusDays(7), zone)
            .firstOrNull { it.start.isAfter(now) } ?: return "Never"
        val startDate = next.start.atZone(zone).toLocalDate()
        val range = if (rule.startMinute == rule.endMinute) {
            "all day"
        } else {
            "${time(next.start, zone)} → ${time(next.end, zone)}"
        }
        return when (startDate) {
            today -> if (rule.startMinute >= 17 * 60) "Frozen tonight $range" else "Frozen today $range"
            today.plusDays(1) -> "Frozen tomorrow $range"
            else -> "Next: ${startDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} $range"
        }
    }
}
