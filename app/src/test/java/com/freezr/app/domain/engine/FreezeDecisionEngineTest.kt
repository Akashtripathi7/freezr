package com.freezr.app.domain.engine

import com.freezr.app.domain.model.ContextRule
import com.freezr.app.domain.model.ContextRuleType
import com.freezr.app.domain.model.Days
import com.freezr.app.domain.model.Decision
import com.freezr.app.domain.model.EmergencyPass
import com.freezr.app.domain.model.EngineContext
import com.freezr.app.domain.model.FreezeReason
import com.freezr.app.domain.model.QuickFreezeState
import com.freezr.app.domain.model.ScheduleRule
import com.freezr.app.domain.model.TimeFilter
import com.freezr.app.domain.model.UsageLimitRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class FreezeDecisionEngineTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val own = "com.freezr.app"

    /** 2026-10-05 is a Monday. */
    private fun t(iso: String): Instant = LocalDateTime.parse(iso).atZone(zone).toInstant()
    private fun hm(h: Int, m: Int = 0) = h * 60 + m

    private fun schedule(
        id: Long = 1,
        start: Int,
        end: Int,
        days: Int = Days.ALL,
        pkgs: Set<String> = setOf(ig),
        enabled: Boolean = true,
    ) = ScheduleRule(id, "S$id", enabled, start, end, days, pkgs)

    private fun ctx(
        schedules: List<ScheduleRule> = emptyList(),
        quick: QuickFreezeState? = null,
        limits: List<UsageLimitRule> = emptyList(),
        usage: Map<String, Duration> = emptyMap(),
        bonus: Map<String, Int> = emptyMap(),
        passes: List<EmergencyPass> = emptyList(),
        passesRemaining: Int = 3,
        context: List<ContextRule> = emptyList(),
        master: Boolean = true,
        strict: Boolean = false,
        protected: Set<String> = setOf("com.android.settings"),
        resetMinute: Int = 0,
    ) = EngineContext(
        ownPackage = own,
        protectedPackages = protected,
        masterEnabled = master,
        strictModeEnabled = strict,
        emergencyPasses = passes,
        emergencyPassesRemaining = passesRemaining,
        quickFreeze = quick,
        schedules = schedules,
        limits = limits,
        usageToday = usage,
        limitBonusMinutes = bonus,
        usageResetMinute = resetMinute,
        contextRules = context,
    )

    private fun eval(pkg: String, at: String, c: EngineContext) = FreezeDecisionEngine.evaluate(pkg, t(at), zone, c)
    private fun frozen(d: Decision) = d as? Decision.Frozen ?: throw AssertionError("Expected Frozen, got $d")

    // ---------- Overnight windows & day ownership ----------

    @Test fun `overnight window active after start on owning day`() {
        val c = ctx(listOf(schedule(start = hm(23), end = hm(8), days = Days.of(DayOfWeek.MONDAY))))
        val d = frozen(eval(ig, "2026-10-05T23:30", c))
        assertEquals(FreezeReason.SCHEDULE, d.reason)
        assertEquals(t("2026-10-06T08:00"), d.unfreezeAt)
    }

    @Test fun `overnight window continues into next morning even though next day is not enabled`() {
        val c = ctx(listOf(schedule(start = hm(23), end = hm(8), days = Days.of(DayOfWeek.MONDAY))))
        assertTrue(eval(ig, "2026-10-06T07:59", c) is Decision.Frozen)
        assertEquals(Decision.Allowed, eval(ig, "2026-10-06T08:00", c))
    }

    @Test fun `overnight window not active on morning of owning day when previous day disabled`() {
        // Monday-only schedule: Monday 07:00 is NOT inside (Sunday 23:00 window does not exist).
        val c = ctx(listOf(schedule(start = hm(23), end = hm(8), days = Days.of(DayOfWeek.MONDAY))))
        assertEquals(Decision.Allowed, eval(ig, "2026-10-05T07:00", c))
        assertEquals(Decision.Allowed, eval(ig, "2026-10-05T22:59", c))
    }

    @Test fun `start equals end is all day`() {
        val c = ctx(listOf(schedule(start = 0, end = 0, days = Days.of(DayOfWeek.MONDAY))))
        assertTrue(eval(ig, "2026-10-05T00:00", c) is Decision.Frozen)
        val d = frozen(eval(ig, "2026-10-05T23:59", c))
        assertEquals(t("2026-10-06T00:00"), d.unfreezeAt)
        assertEquals(Decision.Allowed, eval(ig, "2026-10-06T00:00", c))
    }

    @Test fun `start equals end mid-day spans 24 hours`() {
        val c = ctx(listOf(schedule(start = hm(9), end = hm(9), days = Days.of(DayOfWeek.MONDAY))))
        assertTrue(eval(ig, "2026-10-06T08:59", c) is Decision.Frozen)
        assertEquals(Decision.Allowed, eval(ig, "2026-10-05T08:59", c))
    }

    @Test fun `same-day window is end exclusive`() {
        val c = ctx(listOf(schedule(start = hm(9), end = hm(17), days = Days.WEEKDAYS)))
        assertEquals(Decision.Allowed, eval(ig, "2026-10-05T08:59", c))
        assertTrue(eval(ig, "2026-10-05T09:00", c) is Decision.Frozen)
        assertEquals(Decision.Allowed, eval(ig, "2026-10-05T17:00", c))
        assertEquals(Decision.Allowed, eval(ig, "2026-10-10T10:00", c)) // Saturday
    }

    @Test fun `disabled schedule and other package are allowed`() {
        val c = ctx(listOf(schedule(start = hm(9), end = hm(17), enabled = false)))
        assertEquals(Decision.Allowed, eval(ig, "2026-10-05T10:00", c))
        val c2 = ctx(listOf(schedule(start = hm(9), end = hm(17))))
        assertEquals(Decision.Allowed, eval(yt, "2026-10-05T10:00", c2))
    }

    // ---------- Overlap ----------

    @Test fun `overlapping schedules use latest continuous end`() {
        val c = ctx(
            listOf(
                schedule(1, start = hm(9), end = hm(12)),
                schedule(2, start = hm(11), end = hm(14)),
                schedule(3, start = hm(14), end = hm(15)), // touches -> continuous
                schedule(4, start = hm(16), end = hm(18)), // gap -> not continuous
            ),
        )
        val d = frozen(eval(ig, "2026-10-05T09:30", c))
        assertEquals(t("2026-10-05T15:00"), d.unfreezeAt)
    }

    @Test fun `overlap only counts schedules containing the package`() {
        val c = ctx(
            listOf(
                schedule(1, start = hm(9), end = hm(12), pkgs = setOf(ig)),
                schedule(2, start = hm(11), end = hm(14), pkgs = setOf(yt)),
            ),
        )
        assertEquals(t("2026-10-05T12:00"), frozen(eval(ig, "2026-10-05T10:00", c)).unfreezeAt)
    }

    @Test fun `overnight chains into all-day next day`() {
        val c = ctx(
            listOf(
                schedule(1, start = hm(22), end = hm(7), days = Days.of(DayOfWeek.MONDAY)),
                schedule(2, start = hm(6), end = hm(10), days = Days.of(DayOfWeek.TUESDAY)),
            ),
        )
        assertEquals(t("2026-10-06T10:00"), frozen(eval(ig, "2026-10-05T23:00", c)).unfreezeAt)
    }

    // ---------- DST ----------

    @Test fun `spring forward gap start shifts forward`() {
        // 2026-03-29 02:00 -> 03:00 in Berlin. A 02:30 start becomes 03:30.
        val c = ctx(listOf(schedule(start = hm(2, 30), end = hm(6), days = Days.of(DayOfWeek.SUNDAY))))
        assertEquals(Decision.Allowed, eval(ig, "2026-03-29T01:59", c))
        assertTrue(eval(ig, "2026-03-29T03:30", c) is Decision.Frozen)
        assertEquals(t("2026-03-29T06:00"), frozen(eval(ig, "2026-03-29T04:00", c)).unfreezeAt)
    }

    @Test fun `overnight window across spring forward is one hour shorter but ends on wall clock`() {
        val c = ctx(listOf(schedule(start = hm(23), end = hm(7), days = Days.of(DayOfWeek.SATURDAY))))
        val d = frozen(eval(ig, "2026-03-28T23:30", c))
        assertEquals(t("2026-03-29T07:00"), d.unfreezeAt)
        val w = TimeWindows.windowStartingOn(java.time.LocalDate.parse("2026-03-28"), hm(23), hm(7), zone)
        assertEquals(Duration.ofHours(7), Duration.between(w.start, w.end))
    }

    @Test fun `overnight window across fall back is one hour longer`() {
        val w = TimeWindows.windowStartingOn(java.time.LocalDate.parse("2026-10-24"), hm(23), hm(7), zone)
        assertEquals(Duration.ofHours(9), Duration.between(w.start, w.end))
        val c = ctx(listOf(schedule(start = hm(23), end = hm(7), days = Days.of(DayOfWeek.SATURDAY))))
        // 02:30 occurs twice on 2026-10-25; both instants are inside.
        val first = LocalDateTime.parse("2026-10-25T02:30").atZone(zone).withEarlierOffsetAtOverlap().toInstant()
        val second = LocalDateTime.parse("2026-10-25T02:30").atZone(zone).withLaterOffsetAtOverlap().toInstant()
        assertTrue(FreezeDecisionEngine.evaluate(ig, first, zone, c) is Decision.Frozen)
        assertTrue(FreezeDecisionEngine.evaluate(ig, second, zone, c) is Decision.Frozen)
    }

    @Test fun `timezone change re-evaluates using new wall clock`() {
        val c = ctx(listOf(schedule(start = hm(22), end = hm(23), days = Days.ALL)))
        val instant = t("2026-10-05T22:30") // 22:30 Berlin = 16:30 New York
        assertTrue(FreezeDecisionEngine.evaluate(ig, instant, zone, c) is Decision.Frozen)
        assertEquals(Decision.Allowed, FreezeDecisionEngine.evaluate(ig, instant, ZoneId.of("America/New_York"), c))
    }

    // ---------- Limits ----------

    @Test fun `limit exceeded freezes until next reset`() {
        val c = ctx(limits = listOf(UsageLimitRule(ig, 30, true)), usage = mapOf(ig to Duration.ofMinutes(30)))
        val d = frozen(eval(ig, "2026-10-05T15:00", c))
        assertEquals(FreezeReason.USAGE_LIMIT, d.reason)
        assertEquals(t("2026-10-06T00:00"), d.unfreezeAt)
    }

    @Test fun `limit below threshold allowed and bonus minutes extend it`() {
        val limits = listOf(UsageLimitRule(ig, 30, true))
        assertEquals(Decision.Allowed, eval(ig, "2026-10-05T15:00", ctx(limits = limits, usage = mapOf(ig to Duration.ofMinutes(29)))))
        assertEquals(
            Decision.Allowed,
            eval(ig, "2026-10-05T15:00", ctx(limits = limits, usage = mapOf(ig to Duration.ofMinutes(35)), bonus = mapOf(ig to 10))),
        )
    }

    @Test fun `limit with custom reset time across midnight`() {
        // Reset at 04:00: at 01:00 Tuesday the usage day is still Monday, reset is Tuesday 04:00.
        val c = ctx(limits = listOf(UsageLimitRule(ig, 10, true)), usage = mapOf(ig to Duration.ofMinutes(11)), resetMinute = hm(4))
        assertEquals(t("2026-10-06T04:00"), frozen(eval(ig, "2026-10-06T01:00", c)).unfreezeAt)
        assertEquals(java.time.LocalDate.parse("2026-10-05"), TimeWindows.usageDay(t("2026-10-06T01:00"), zone, hm(4)))
        assertEquals(java.time.LocalDate.parse("2026-10-06"), TimeWindows.usageDay(t("2026-10-06T04:00"), zone, hm(4)))
    }

    @Test fun `limit at 23 59 unfreezes at midnight`() {
        val c = ctx(limits = listOf(UsageLimitRule(ig, 1, true)), usage = mapOf(ig to Duration.ofMinutes(5)))
        assertEquals(t("2026-10-06T00:00"), frozen(eval(ig, "2026-10-05T23:59", c)).unfreezeAt)
    }

    @Test fun `disabled limit is ignored`() {
        val c = ctx(limits = listOf(UsageLimitRule(ig, 1, false)), usage = mapOf(ig to Duration.ofMinutes(5)))
        assertEquals(Decision.Allowed, eval(ig, "2026-10-05T12:00", c))
    }

    // ---------- Emergency pass ----------

    @Test fun `emergency pass allows until expiry then freezes again`() {
        val c = ctx(
            listOf(schedule(start = hm(9), end = hm(17))),
            passes = listOf(EmergencyPass(ig, t("2026-10-05T10:05"))),
        )
        assertEquals(Decision.Allowed, eval(ig, "2026-10-05T10:04", c))
        assertTrue(eval(ig, "2026-10-05T10:05", c) is Decision.Frozen)
    }

    @Test fun `emergency pass is per package`() {
        val c = ctx(
            listOf(schedule(start = hm(9), end = hm(17), pkgs = setOf(ig, yt))),
            passes = listOf(EmergencyPass(ig, t("2026-10-05T11:00"))),
        )
        assertTrue(eval(yt, "2026-10-05T10:00", c) is Decision.Frozen)
    }

    @Test fun `can emergency unlock reflects remaining passes`() {
        val c = ctx(listOf(schedule(start = hm(9), end = hm(17))), passesRemaining = 0)
        assertFalse(frozen(eval(ig, "2026-10-05T10:00", c)).canEmergencyUnlock)
    }

    // ---------- Precedence ----------

    @Test fun `own app and protected packages always allowed`() {
        val c = ctx(
            listOf(schedule(start = 0, end = 0, pkgs = setOf(own, "com.android.settings"))),
            quick = QuickFreezeState(t("2026-10-05T00:00"), t("2026-10-06T00:00"), setOf(own, "com.android.settings")),
        )
        assertEquals(Decision.Allowed, eval(own, "2026-10-05T10:00", c))
        assertEquals(Decision.Allowed, eval("com.android.settings", "2026-10-05T10:00", c))
    }

    @Test fun `master off allows everything`() {
        val c = ctx(listOf(schedule(start = 0, end = 0)), master = false)
        assertEquals(Decision.Allowed, eval(ig, "2026-10-05T10:00", c))
    }

    @Test fun `emergency pass beats quick freeze`() {
        val c = ctx(
            quick = QuickFreezeState(t("2026-10-05T10:00"), t("2026-10-05T11:00"), setOf(ig)),
            passes = listOf(EmergencyPass(ig, t("2026-10-05T10:30"))),
        )
        assertEquals(Decision.Allowed, eval(ig, "2026-10-05T10:10", c))
    }

    @Test fun `quick freeze beats schedule beats limit beats location`() {
        val all = ctx(
            schedules = listOf(schedule(start = hm(9), end = hm(17))),
            quick = QuickFreezeState(t("2026-10-05T10:00"), t("2026-10-05T11:00"), setOf(ig)),
            limits = listOf(UsageLimitRule(ig, 1, true)),
            usage = mapOf(ig to Duration.ofMinutes(10)),
            context = listOf(ContextRule(7, "Office", ContextRuleType.GEOFENCE, true, true, setOf(ig), null)),
        )
        assertEquals(FreezeReason.QUICK_FREEZE, frozen(eval(ig, "2026-10-05T10:30", all)).reason)
        assertEquals(FreezeReason.SCHEDULE, frozen(eval(ig, "2026-10-05T12:00", all)).reason)
        val noSchedule = all.copy(schedules = emptyList())
        assertEquals(FreezeReason.USAGE_LIMIT, frozen(eval(ig, "2026-10-05T12:00", noSchedule)).reason)
        val onlyContext = noSchedule.copy(limits = emptyList())
        val d = frozen(eval(ig, "2026-10-05T12:00", onlyContext))
        assertEquals(FreezeReason.LOCATION, d.reason)
        assertEquals("context:7", d.ruleId)
        assertNull(d.unfreezeAt)
    }

    @Test fun `quick freeze outside its interval does nothing`() {
        val c = ctx(quick = QuickFreezeState(t("2026-10-05T10:00"), t("2026-10-05T11:00"), setOf(ig)))
        assertEquals(Decision.Allowed, eval(ig, "2026-10-05T11:00", c))
        assertEquals(Decision.Allowed, eval(ig, "2026-10-05T09:59", c))
    }

    @Test fun `quick freeze under strict mode cannot be emergency unlocked`() {
        val qf = QuickFreezeState(t("2026-10-05T10:00"), t("2026-10-05T11:00"), setOf(ig))
        assertFalse(frozen(eval(ig, "2026-10-05T10:30", ctx(quick = qf, strict = true))).canEmergencyUnlock)
        assertTrue(frozen(eval(ig, "2026-10-05T10:30", ctx(quick = qf, strict = false))).canEmergencyUnlock)
        // A schedule freeze under strict mode keeps the emergency valve.
        val s = ctx(listOf(schedule(start = hm(9), end = hm(17))), strict = true)
        assertTrue(frozen(eval(ig, "2026-10-05T10:30", s)).canEmergencyUnlock)
    }

    // ---------- Context rules ----------

    @Test fun `wifi rule only while connected and inside its time filter`() {
        val rule = ContextRule(3, "Home Wi-Fi", ContextRuleType.WIFI, true, true, setOf(yt), TimeFilter(hm(20), hm(23), Days.ALL))
        val c = ctx(context = listOf(rule))
        assertEquals(Decision.Allowed, eval(yt, "2026-10-05T19:00", c))
        val d = frozen(eval(yt, "2026-10-05T21:00", c))
        assertEquals(FreezeReason.WIFI, d.reason)
        assertEquals(t("2026-10-05T23:00"), d.unfreezeAt)
        assertEquals(Decision.Allowed, eval(yt, "2026-10-05T21:00", ctx(context = listOf(rule.copy(active = false)))))
        assertEquals(Decision.Allowed, eval(yt, "2026-10-05T21:00", ctx(context = listOf(rule.copy(enabled = false)))))
    }

    // ---------- Transitions ----------

    @Test fun `next transition is the earliest boundary`() {
        val c = ctx(
            schedules = listOf(schedule(start = hm(22), end = hm(7))),
            quick = QuickFreezeState(t("2026-10-05T10:00"), t("2026-10-05T10:30"), setOf(ig)),
            passes = listOf(EmergencyPass(ig, t("2026-10-05T10:20"))),
        )
        assertEquals(t("2026-10-05T10:20"), FreezeDecisionEngine.nextTransitionAfter(t("2026-10-05T10:00"), zone, c))
        assertEquals(t("2026-10-05T10:30"), FreezeDecisionEngine.nextTransitionAfter(t("2026-10-05T10:20"), zone, c))
        assertEquals(t("2026-10-05T22:00"), FreezeDecisionEngine.nextTransitionAfter(t("2026-10-05T10:30"), zone, c))
        assertEquals(t("2026-10-06T07:00"), FreezeDecisionEngine.nextTransitionAfter(t("2026-10-05T22:00"), zone, c))
    }

    @Test fun `next transition includes usage reset when limits exist`() {
        val c = ctx(limits = listOf(UsageLimitRule(ig, 30, true)), resetMinute = hm(4))
        assertEquals(t("2026-10-06T04:00"), FreezeDecisionEngine.nextTransitionAfter(t("2026-10-05T12:00"), zone, c))
    }

    @Test fun `next transition is null with no rules`() {
        assertNull(FreezeDecisionEngine.nextTransitionAfter(t("2026-10-05T12:00"), zone, ctx()))
    }

    @Test fun `next transition skips the spring forward gap correctly`() {
        val c = ctx(listOf(schedule(start = hm(2, 30), end = hm(5), days = Days.of(DayOfWeek.SUNDAY))))
        assertEquals(t("2026-03-29T03:30"), FreezeDecisionEngine.nextTransitionAfter(t("2026-03-29T00:00"), zone, c))
    }

    @Test fun `active freezes and strict detection`() {
        val c = ctx(listOf(schedule(start = hm(9), end = hm(17))))
        assertTrue(FreezeDecisionEngine.isAnyFreezeActive(t("2026-10-05T10:00"), zone, c))
        assertFalse(FreezeDecisionEngine.isAnyFreezeActive(t("2026-10-05T18:00"), zone, c))
        assertFalse(FreezeDecisionEngine.isAnyFreezeActive(t("2026-10-05T10:00"), zone, c.copy(masterEnabled = false)))
        val active = FreezeDecisionEngine.activeFreezes(t("2026-10-05T10:00"), zone, c).single()
        assertEquals(t("2026-10-05T17:00"), active.until)
    }

    @Test fun `upcoming schedule freezes are sorted and bounded`() {
        val c = ctx(
            listOf(
                schedule(1, start = hm(22), end = hm(7)),
                schedule(2, start = hm(13), end = hm(14)),
            ),
        )
        val up = FreezeDecisionEngine.upcomingScheduleFreezes(t("2026-10-05T12:00"), zone, c, Duration.ofHours(12))
        assertEquals(listOf(t("2026-10-05T13:00"), t("2026-10-05T22:00")), up.map { it.startsAt })
    }
}
