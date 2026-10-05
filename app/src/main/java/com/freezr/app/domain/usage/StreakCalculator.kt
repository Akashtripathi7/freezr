package com.freezr.app.domain.usage

import java.time.LocalDate

/**
 * A day counts towards the streak when no emergency unlock was used and no freeze was bypassed
 * (successful Strict Mode unlock). Today counts while it is still clean.
 */
object StreakCalculator {

    fun current(today: LocalDate, firstDay: LocalDate, badDays: Set<LocalDate>): Int {
        var n = 0
        var d = today
        while (!d.isBefore(firstDay) && d !in badDays) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    fun best(today: LocalDate, firstDay: LocalDate, badDays: Set<LocalDate>): Int {
        var best = 0
        var run = 0
        var d = firstDay
        while (!d.isAfter(today)) {
            if (d in badDays) run = 0 else run++
            if (run > best) best = run
            d = d.plusDays(1)
        }
        return best
    }
}
