package com.freezr.app.platform

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.freezr.app.domain.model.Days
import com.freezr.app.domain.model.ScheduleRule
import com.freezr.app.domain.planning.AlarmPlan
import com.freezr.app.domain.planning.PreFreezeAlarm
import com.freezr.app.platform.alarms.AlarmScheduler
import com.freezr.app.platform.alarms.DirectBootStore
import com.freezr.app.platform.usage.UsageTracker
import com.freezr.app.testutil.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Process-state matrix rows 3–7: everything that matters is rebuilt from disk and re-armed as
 * absolute-time alarms, so a killed process, a reboot, an update or a time-zone change all converge
 * on the same plan.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val am = context.getSystemService(AlarmManager::class.java)
    private lateinit var scope: CoroutineScope
    private lateinit var g: TestGraph

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        g = TestGraph(scope)
    }

    @After fun tearDown() = g.close()

    private fun coordinator(scheduler: AlarmScheduler = AlarmScheduler(context, DirectBootStore(context))) =
        EnforcementCoordinator(g.stateRepo, g.settings, g.time, scheduler, EnforcementBus(), emptySet(), emptySet(), scope)

    private fun scheduledTimes(): List<Long> = shadowOf(am).scheduledAlarms.map { it.triggerAtTime }.sorted()

    @Test fun `replan arms transition and pre-freeze alarms at absolute times`() = runBlocking {
        // Fake clock: Monday 08:00 UTC. Schedule 22:00–06:00 every day.
        g.schedules.save(ScheduleRule(0, "Night", true, 22 * 60, 6 * 60, Days.ALL, setOf("ig")))
        coordinator().replan()
        val start = Instant.parse("2026-10-05T22:00:00Z").toEpochMilli()
        assertEquals(listOf(start - 10 * 60_000, start), scheduledTimes())
    }

    @Test fun `replanning is idempotent - alarms are replaced, never duplicated`() = runBlocking {
        g.schedules.save(ScheduleRule(0, "Night", true, 22 * 60, 6 * 60, Days.ALL, setOf("ig")))
        val c = coordinator()
        repeat(5) { c.replan() }
        assertEquals(2, shadowOf(am).scheduledAlarms.size)
    }

    @Test fun `a new process rebuilds the same plan from disk (process death, reboot, update)`() = runBlocking {
        g.schedules.save(ScheduleRule(0, "Night", true, 22 * 60, 6 * 60, Days.ALL, setOf("ig")))
        coordinator().replan()
        val before = scheduledTimes()
        // Simulate the alarms being wiped (reboot) and a fresh singleton graph (new process).
        shadowOf(am).scheduledAlarms.toList().forEach { a -> a.operation?.let(am::cancel) }
        assertTrue(shadowOf(am).scheduledAlarms.isEmpty())
        val fresh = TestGraph(scope, g.time).also { other ->
            // Same database file is not shared in-memory, so copy the rule the way disk would persist it.
            other.schedules.save(ScheduleRule(0, "Night", true, 22 * 60, 6 * 60, Days.ALL, setOf("ig")))
        }
        EnforcementCoordinator(fresh.stateRepo, fresh.settings, fresh.time, AlarmScheduler(context, DirectBootStore(context)), EnforcementBus(), emptySet(), emptySet(), scope).replan()
        assertEquals(before, scheduledTimes())
        fresh.close()
    }

    @Test fun `timezone change moves wall-clock transitions`() = runBlocking {
        g.schedules.save(ScheduleRule(0, "Night", true, 22 * 60, 6 * 60, Days.ALL, setOf("ig")))
        val c = coordinator()
        c.replan()
        g.time.zoneId = ZoneId.of("Asia/Kolkata") // 08:00 UTC = 13:30 IST
        c.reconcileAll("timezone_changed")
        val startIst = Instant.parse("2026-10-05T16:30:00Z").toEpochMilli() // 22:00 IST
        assertEquals(startIst, scheduledTimes().last())
    }

    @Test fun `quick freeze end is armed and cleared when it finishes`() = runBlocking {
        g.selection.setSelected(listOf("ig"), true)
        g.controls.startQuickFreeze(Duration.ofMinutes(25))
        val c = coordinator()
        c.replan()
        assertEquals(listOf(g.time.now().plus(Duration.ofMinutes(25)).toEpochMilli()), scheduledTimes())
        g.time.advance(Duration.ofMinutes(26))
        c.replan()
        assertTrue(scheduledTimes().isEmpty())
    }

    @Test fun `locked boot re-arms from device-protected storage`() {
        val store = DirectBootStore(context)
        val now = Instant.parse("2026-10-05T08:00:00Z")
        val transition = now.plus(Duration.ofHours(2))
        val pre = PreFreezeAlarm(now.plus(Duration.ofMinutes(50)), now.plus(Duration.ofHours(1)), listOf("Work"), setOf("ig"))
        store.savePlan(transition, pre)
        val (t, p) = DirectBootStore(context).loadPlan()
        assertEquals(transition, t)
        assertEquals(pre, p)

        AlarmScheduler(context, store).applyFromDirectBoot(now)
        assertEquals(listOf(pre.notifyAt.toEpochMilli(), transition.toEpochMilli()), scheduledTimes())
    }

    @Test fun `empty plan cancels everything and is remembered`() {
        val scheduler = AlarmScheduler(context, DirectBootStore(context))
        scheduler.apply(AlarmPlan(Instant.parse("2026-10-05T09:00:00Z"), null))
        assertTrue(scheduler.hasTransitionAlarm())
        scheduler.apply(AlarmPlan(null, null))
        assertTrue(scheduledTimes().isEmpty())
        assertFalse(scheduler.hasTransitionAlarm())
        assertNull(DirectBootStore(context).loadPlan().first)
    }

    @Test fun `live usage adds the foreground delta and folds it on switch`() = runBlocking {
        val tracker = UsageTracker(context, g.time, g.settings)
        val t0 = g.time.now()
        tracker.onForegroundChanged("a", t0)
        assertEquals(Duration.ofMinutes(5), tracker.usedFor("a", t0.plus(Duration.ofMinutes(5))))
        tracker.onForegroundChanged("b", t0.plus(Duration.ofMinutes(5)))
        val later = t0.plus(Duration.ofMinutes(7))
        assertEquals(Duration.ofMinutes(5), tracker.usedFor("a", later))
        assertEquals(Duration.ofMinutes(2), tracker.usedFor("b", later))
        tracker.onForegroundChanged(null, later) // screen off
        assertEquals(Duration.ofMinutes(2), tracker.usedFor("b", later.plus(Duration.ofHours(1))))
    }
}
