package com.freezr.app.domain.planning

import com.freezr.app.domain.model.Days
import com.freezr.app.domain.model.EngineContext
import com.freezr.app.domain.model.QuickFreezeState
import com.freezr.app.domain.model.ScheduleRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class AlarmPlannerTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun t(iso: String): Instant = LocalDateTime.parse(iso).atZone(zone).toInstant()

    private val base = EngineContext.empty("com.freezr.app").copy(
        schedules = listOf(
            ScheduleRule(1, "Sleep", true, 23 * 60, 7 * 60, Days.ALL, setOf("a", "b")),
            ScheduleRule(2, "Night social", true, 23 * 60, 23 * 60 + 30, Days.ALL, setOf("c")),
            ScheduleRule(3, "Work", true, 9 * 60, 17 * 60, Days.WEEKDAYS, setOf("d")),
        ),
    )

    @Test fun `plans next transition and the next pre-freeze grouped by start`() {
        val plan = AlarmPlanner.plan(t("2026-10-05T18:00"), zone, base, preFreezeEnabled = true, preFreezeMinutes = 10)
        assertEquals(t("2026-10-05T23:00"), plan.transitionAt)
        val pre = plan.preFreeze!!
        assertEquals(t("2026-10-05T22:50"), pre.notifyAt)
        assertEquals(t("2026-10-05T23:00"), pre.freezeAt)
        assertEquals(setOf("a", "b", "c"), pre.packages)
        assertEquals(listOf("Sleep", "Night social"), pre.scheduleNames)
    }

    @Test fun `inside the lead window the pre-freeze moves on to the following start`() {
        val plan = AlarmPlanner.plan(t("2026-10-05T22:55"), zone, base, true, 10)
        // 23:00 notification time already passed -> next is Tuesday 09:00 Work.
        assertEquals(t("2026-10-06T08:50"), plan.preFreeze!!.notifyAt)
        assertEquals(setOf("d"), plan.preFreeze!!.packages)
    }

    @Test fun `firing exactly at notify time does not re-plan the same notification`() {
        val plan = AlarmPlanner.plan(t("2026-10-05T22:50"), zone, base, true, 10)
        assertEquals(t("2026-10-06T08:50"), plan.preFreeze!!.notifyAt)
    }

    @Test fun `pre-freeze disabled or master off yields no notification`() {
        assertNull(AlarmPlanner.plan(t("2026-10-05T18:00"), zone, base, false, 10).preFreeze)
        assertNull(AlarmPlanner.plan(t("2026-10-05T18:00"), zone, base.copy(masterEnabled = false), true, 10).preFreeze)
    }

    @Test fun `quick freeze end is a transition`() {
        val ctx = base.copy(quickFreeze = QuickFreezeState(t("2026-10-05T18:00"), t("2026-10-05T18:30"), setOf("x")))
        assertEquals(t("2026-10-05T18:30"), AlarmPlanner.plan(t("2026-10-05T18:01"), zone, ctx, true, 10).transitionAt)
    }

    @Test fun `empty rules plan nothing`() {
        val plan = AlarmPlanner.plan(t("2026-10-05T18:00"), zone, EngineContext.empty("p"), true, 10)
        assertNull(plan.transitionAt)
        assertNull(plan.preFreeze)
    }
}
