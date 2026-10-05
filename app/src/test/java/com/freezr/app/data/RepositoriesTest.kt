package com.freezr.app.data

import com.freezr.app.data.repo.DelayRequestResult
import com.freezr.app.data.repo.GrantResult
import com.freezr.app.data.repo.NothingToFreezeException
import com.freezr.app.data.repo.PartnerApprovalResult
import com.freezr.app.data.repo.PinResult
import com.freezr.app.data.repo.StrictModeLockedException
import com.freezr.app.domain.engine.FreezeDecisionEngine
import com.freezr.app.domain.model.Days
import com.freezr.app.domain.model.Decision
import com.freezr.app.domain.model.ScheduleRule
import com.freezr.app.domain.strict.StrictUnlockMethod
import com.freezr.app.testutil.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

/**
 * Data-layer behaviour with the real Room / DataStore and a fake clock (08:00 UTC, Monday).
 * Covers Strict Mode guarding (matrix row 11: concurrent edits while a freeze is active),
 * PIN lockout, delay unlock, emergency passes and Quick Freeze.
 */
@RunWith(RobolectricTestRunner::class)
class RepositoriesTest {
    private lateinit var scope: CoroutineScope
    private lateinit var g: TestGraph

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        g = TestGraph(scope)
    }

    @After fun tearDown() = g.close()

    private suspend fun activeWorkSchedule(): Long =
        g.schedules.save(ScheduleRule(0, "Work", true, 7 * 60, 10 * 60, Days.ALL, setOf("ig")))

    private suspend fun assertLocked(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected StrictModeLockedException")
        } catch (_: StrictModeLockedException) {
        }
    }

    @Test fun `strict mode blocks loosening edits only while a freeze is active`() = runBlocking {
        val id = activeWorkSchedule()
        g.strict.enable(StrictUnlockMethod.DELAY)
        assertTrue(g.guard.isLocked())

        assertLocked { g.schedules.setEnabled(id, false) }
        assertLocked { g.schedules.delete(id) }
        assertLocked { g.schedules.save(g.schedules.get(id)!!.copy(endMinute = 8 * 60)) }
        assertLocked { g.controls.setMasterEnabled(false) }
        assertLocked { g.strict.disable() }
        // Tightening is always fine.
        g.schedules.save(ScheduleRule(0, "Extra", true, 0, 0, Days.ALL, setOf("yt")))
        g.limits.setLimit("yt", 30)
        g.limits.setLimit("yt", 20)
        assertLocked { g.limits.setLimit("yt", 60) }

        // 11:00: "Work" is over but the all-day "Extra" schedule still freezes something.
        g.time.advance(Duration.ofHours(3))
        assertTrue(g.guard.isLocked())
    }

    @Test fun `strict mode does not lock when nothing is frozen`() = runBlocking {
        g.schedules.save(ScheduleRule(0, "Night", true, 22 * 60, 6 * 60, Days.ALL, setOf("ig")))
        g.strict.enable(StrictUnlockMethod.DELAY)
        assertFalse(g.guard.isLocked())
        g.controls.setMasterEnabled(false)
        assertFalse(g.settings.current().masterEnabled)
    }

    @Test fun `pin unlock with lockout and unlock session`() = runBlocking {
        activeWorkSchedule()
        g.strict.setPin("4821")
        g.strict.enable(StrictUnlockMethod.PIN)
        repeat(4) { assertTrue(g.strict.verifyPin("0000") is PinResult.Wrong) }
        val fifth = g.strict.verifyPin("0000")
        assertTrue(fifth is PinResult.LockedOut)
        // Even the right PIN is refused during lockout.
        assertTrue(g.strict.verifyPin("4821") is PinResult.LockedOut)
        g.time.advance(Duration.ofSeconds(31))
        assertEquals(PinResult.Success, g.strict.verifyPin("4821"))
        assertFalse(g.guard.isLocked())
        g.time.advance(Duration.ofMinutes(6))
        assertTrue(g.guard.isLocked())
        // Every attempt was logged.
        assertTrue(g.recorder.bypassAttemptsSince(0, "PIN") >= 7)
        assertTrue(g.secrets.map.values.none { it.contains("4821") })
    }

    @Test fun `delay unlock requires waiting and confirming inside the window`() = runBlocking {
        activeWorkSchedule()
        g.strict.enable(StrictUnlockMethod.DELAY)
        assertTrue(g.strict.requestDelayUnlock() is DelayRequestResult.Started)
        assertEquals(DelayRequestResult.AlreadyRunning, g.strict.requestDelayUnlock())
        assertFalse(g.strict.confirmDelayUnlock())
        g.time.advance(Duration.ofMinutes(10))
        assertTrue(g.strict.confirmDelayUnlock())
        assertFalse(g.guard.isLocked())
    }

    @Test fun `delay requests are rate limited`() = runBlocking {
        activeWorkSchedule()
        g.strict.enable(StrictUnlockMethod.DELAY)
        repeat(3) {
            g.strict.requestDelayUnlock()
            g.strict.cancelDelayRequest()
        }
        assertEquals(DelayRequestResult.RateLimited, g.strict.requestDelayUnlock())
    }

    @Test fun `partner approval stub is unavailable and cannot be selected`() = runBlocking {
        assertTrue(g.strict.requestPartnerApproval() is PartnerApprovalResult.Unavailable)
        try {
            g.strict.enable(StrictUnlockMethod.PARTNER)
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `emergency pass unfreezes one package until expiry and consumes a pass`() = runBlocking {
        g.schedules.save(ScheduleRule(0, "Work", true, 7 * 60, 10 * 60, Days.ALL, setOf("ig", "yt")))
        val before = g.emergency.passesRemaining()
        val r = g.emergency.grant("ig")
        assertTrue(r is GrantResult.Pass)
        assertEquals(before - 1, g.emergency.passesRemaining())
        val ctx = g.stateRepo.loadFresh()
        assertEquals(Decision.Allowed, FreezeDecisionEngine.evaluate("ig", g.time.now(), g.time.zone(), ctx))
        assertTrue(FreezeDecisionEngine.evaluate("yt", g.time.now(), g.time.zone(), ctx) is Decision.Frozen)
        g.time.advance(Duration.ofMinutes(5))
        assertTrue(FreezeDecisionEngine.evaluate("ig", g.time.now(), g.time.zone(), g.stateRepo.loadFresh()) is Decision.Frozen)
    }

    @Test fun `emergency on a usage limit adds bonus minutes for today`() = runBlocking {
        g.limits.setLimit("yt", 10)
        g.usage.usageToday.value = mapOf("yt" to Duration.ofMinutes(12))
        assertEquals(GrantResult.ExtraMinutes(10), g.emergency.grant("yt"))
        val ctx = g.stateRepo.loadFresh()
        assertEquals(10, ctx.limitBonusMinutes["yt"])
        assertEquals(Decision.Allowed, FreezeDecisionEngine.evaluate("yt", g.time.now(), g.time.zone(), ctx))
    }

    @Test fun `passes run out`() = runBlocking {
        g.schedules.save(ScheduleRule(0, "All day", true, 0, 0, Days.ALL, setOf("ig")))
        assertEquals(GrantResult.NotFrozen, g.emergency.grant("not.frozen"))
        repeat(3) {
            assertTrue(g.emergency.grant("ig") is GrantResult.Pass)
            g.time.advance(Duration.ofMinutes(6))
        }
        assertEquals(GrantResult.NoPassesLeft, g.emergency.grant("ig"))
        // Rolling week: a week later the passes are back.
        g.time.advance(Duration.ofDays(7))
        assertEquals(3, g.emergency.passesRemaining())
    }

    @Test fun `quick freeze needs targets, extends, and cannot be cancelled under strict`() = runBlocking {
        try {
            g.controls.startQuickFreeze(Duration.ofMinutes(15))
            fail()
        } catch (_: NothingToFreezeException) {
        }
        g.selection.setSelected(listOf("ig"), true)
        g.controls.startQuickFreeze(Duration.ofMinutes(15))
        g.controls.startQuickFreeze(Duration.ofMinutes(30), setOf("yt"))
        val qf = g.stateRepo.loadFresh().quickFreeze!!
        assertEquals(setOf("ig", "yt"), qf.packages)
        assertEquals(g.time.now().plus(Duration.ofMinutes(30)), qf.endsAt)

        g.strict.enable(StrictUnlockMethod.DELAY)
        assertLocked { g.controls.cancelQuickFreeze() }
        val d = FreezeDecisionEngine.evaluate("ig", g.time.now(), g.time.zone(), g.stateRepo.loadFresh()) as Decision.Frozen
        assertFalse(d.canEmergencyUnlock)
        assertEquals(GrantResult.NotAllowed, g.emergency.grant("ig"))

        // The end time is absolute: after it passes, nothing is frozen and strict unlocks.
        g.time.advance(Duration.ofMinutes(31))
        assertFalse(g.guard.isLocked())
        g.controls.cancelQuickFreeze()
    }

    @Test fun `every write notifies the planner`() = runBlocking {
        val before = g.notifier.count
        val id = g.schedules.save(ScheduleRule(0, "A", true, 60, 120, Days.ALL, setOf("x")))
        g.schedules.setEnabled(id, false)
        g.schedules.duplicate(id)
        g.limits.setLimit("x", 10)
        assertEquals(before + 4, g.notifier.count)
        assertEquals(2, g.db.scheduleDao().getAll().size)
        assertEquals(listOf("A", "A (copy)"), g.db.scheduleDao().getAll().map { it.schedule.name }.sorted())
    }
}
