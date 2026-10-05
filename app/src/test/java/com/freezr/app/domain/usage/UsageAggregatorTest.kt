package com.freezr.app.domain.usage

import com.freezr.app.domain.usage.UsageEventKind.PAUSED
import com.freezr.app.domain.usage.UsageEventKind.RESUMED
import com.freezr.app.domain.usage.UsageEventKind.SCREEN_OFF
import com.freezr.app.domain.usage.UsageEventKind.STOPPED
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class UsageAggregatorTest {
    private val day = Instant.parse("2026-10-05T00:00:00Z")
    private fun at(min: Long) = day.plusSeconds(min * 60)
    private fun ev(pkg: String, kind: UsageEventKind, min: Long, cls: String? = "Main") = UsageEventRecord(pkg, cls, kind, at(min))

    @Test fun `sums resumed-paused sessions`() {
        val events = listOf(ev("a", RESUMED, 10), ev("a", PAUSED, 20), ev("a", RESUMED, 30), ev("a", PAUSED, 35))
        assertEquals(Duration.ofMinutes(15), UsageAggregator.foregroundTime(events, day, at(100))["a"])
    }

    @Test fun `session open at window start is clipped`() {
        val events = listOf(ev("a", RESUMED, -30), ev("a", PAUSED, 10))
        assertEquals(Duration.ofMinutes(10), UsageAggregator.foregroundTime(events, day, at(100))["a"])
    }

    @Test fun `open session counts until now`() {
        val events = listOf(ev("a", RESUMED, 90))
        assertEquals(Duration.ofMinutes(10), UsageAggregator.foregroundTime(events, day, at(100))["a"])
        assertEquals(setOf("a"), UsageAggregator.openPackages(events))
    }

    @Test fun `screen off closes all sessions`() {
        val events = listOf(ev("a", RESUMED, 0), ev("b", RESUMED, 5, "B"), ev("x", SCREEN_OFF, 10, null))
        val r = UsageAggregator.foregroundTime(events, day, at(100))
        assertEquals(Duration.ofMinutes(10), r["a"])
        assertEquals(Duration.ofMinutes(5), r["b"])
    }

    @Test fun `activity switch inside the same app does not double count`() {
        val events = listOf(ev("a", RESUMED, 0, "One"), ev("a", RESUMED, 5, "Two"), ev("a", PAUSED, 5, "One"), ev("a", STOPPED, 10, "Two"))
        assertEquals(Duration.ofMinutes(10), UsageAggregator.foregroundTime(events, day, at(100))["a"])
    }

    @Test fun `split screen counts both apps`() {
        val events = listOf(ev("a", RESUMED, 0), ev("b", RESUMED, 0), ev("a", PAUSED, 10), ev("b", PAUSED, 20))
        val r = UsageAggregator.foregroundTime(events, day, at(100))
        assertEquals(Duration.ofMinutes(10), r["a"])
        assertEquals(Duration.ofMinutes(20), r["b"])
    }

    @Test fun `events after window end are ignored`() {
        val events = listOf(ev("a", RESUMED, 50), ev("a", PAUSED, 150))
        assertEquals(Duration.ofMinutes(50), UsageAggregator.foregroundTime(events, day, at(100))["a"])
    }
}
