package com.freezr.app.ui

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.freezr.app.data.repo.GrantResult
import com.freezr.app.domain.model.Days
import com.freezr.app.domain.model.ScheduleRule
import com.freezr.app.domain.strict.StrictUnlockMethod
import com.freezr.app.testutil.TestGraph
import com.freezr.app.ui.emergency.EmergencyStep
import com.freezr.app.ui.emergency.EmergencyViewModel
import com.freezr.app.ui.schedules.ScheduleEvent
import com.freezr.app.ui.schedules.ScheduleListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Process-state matrix row 1: UI state is a live projection of disk, and edits apply immediately. */
@RunWith(RobolectricTestRunner::class)
class ViewModelTest {
    private lateinit var io: CoroutineScope
    private lateinit var g: TestGraph

    @Before fun setUp() {
        io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        g = TestGraph(io)
    }

    @After fun tearDown() {
        g.close()
        Dispatchers.resetMain()
    }

    @Test fun `schedule list reflects writes and reports strict blocks`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = ScheduleListViewModel(g.schedules, g.time, g.guard)
        vm.state.test {
            var s = awaitItem()
            while (s.loading) s = awaitItem()
            assertTrue(s.items.isEmpty())

            val id = g.schedules.save(ScheduleRule(0, "Work", true, 7 * 60, 10 * 60, Days.ALL, setOf("ig")))
            s = awaitItem()
            while (s.items.isEmpty()) s = awaitItem()
            assertEquals("Work", s.items.single().rule.name)
            assertTrue(s.items.single().activeNow)

            g.strict.enable(StrictUnlockMethod.DELAY)
            while (!s.strictLocked) s = awaitItem()
            vm.events.test {
                vm.setEnabled(id, false)
                assertEquals(ScheduleEvent.StrictBlocked, awaitItem())
            }
            assertTrue(g.schedules.get(id)!!.enabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `emergency flow walks wait, phrase and grant`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        g.settings.update { emergencyWaitSeconds = 3 }
        g.schedules.save(ScheduleRule(0, "Work", true, 7 * 60, 10 * 60, Days.ALL, setOf("ig")))
        val vm = EmergencyViewModel(SavedStateHandle(mapOf("pkg" to "ig")), g.emergency, g.settings)
        vm.state.test {
            var s = awaitItem()
            while (s.settings.emergencyWaitSeconds != 3) s = awaitItem()
            assertEquals(EmergencyStep.Intro, s.step)

            vm.begin()
            testScheduler.runCurrent()
            while (s.step !is EmergencyStep.Waiting) s = awaitItem()
            assertEquals(3, (s.step as EmergencyStep.Waiting).secondsLeft)

            advanceTimeBy(3_100)
            while (s.step != EmergencyStep.Phrase) s = awaitItem()

            vm.type("wrong")
            while (s.typed != "wrong") s = awaitItem()
            assertFalse(s.phraseOk)
            vm.type(s.settings.emergencyPhrase.uppercase())
            while (!s.phraseOk) s = awaitItem()

            vm.confirm()
            while (s.step !is EmergencyStep.Done) {
                testScheduler.runCurrent()
                s = awaitItem()
            }
            assertTrue((s.step as EmergencyStep.Done).result is GrantResult.Pass)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
