package com.freezr.app.data

import com.freezr.app.data.db.EmergencyLogEntity
import com.freezr.app.data.db.EmergencyPassEntity
import com.freezr.app.data.db.FreezrDatabase
import com.freezr.app.data.db.QuickFreezeEntity
import com.freezr.app.data.db.ScheduleEntity
import com.freezr.app.data.db.WhitelistEntity
import com.freezr.app.data.repo.FreezeStateRepository
import com.freezr.app.domain.engine.FreezeDecisionEngine
import com.freezr.app.domain.model.Decision
import com.freezr.app.testutil.FakeContextRuleSource
import com.freezr.app.testutil.FakeProtectedSource
import com.freezr.app.testutil.FakeTimeSource
import com.freezr.app.testutil.FakeUsageSource
import com.freezr.app.testutil.inMemoryDb
import com.freezr.app.testutil.testSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

/**
 * Process-state matrix row 3 / 11: the full engine context is rebuilt purely from disk, and a
 * brand-new repository instance (simulating a fresh process) sees exactly the same state.
 */
@RunWith(RobolectricTestRunner::class)
class FreezeStateRepositoryTest {
    private lateinit var db: FreezrDatabase
    private lateinit var scope: CoroutineScope
    private val time = FakeTimeSource()

    @Before fun setUp() {
        db = inMemoryDb()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After fun tearDown() {
        runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
        db.close()
    }

    private fun repo(settingsScope: CoroutineScope = scope) = FreezeStateRepository(
        db, testSettings(settingsScope), FakeProtectedSource(), FakeUsageSource(), FakeContextRuleSource(),
        time, "com.freezr.app", scope,
    )

    @Test fun `fresh repository rebuilds identical context from disk`() = runBlocking {
        db.scheduleDao().save(ScheduleEntity(0, "Work", true, 7 * 60, 9 * 60, 0b1111111, 0), setOf("ig"))
        db.quickFreezeDao().set(QuickFreezeEntity(startedAt = time.now().toEpochMilli(), endsAt = time.now().plusSeconds(600).toEpochMilli(), packages = listOf("yt"), label = "Focus"))
        db.whitelistDao().insert(listOf(WhitelistEntity("maps", isDefault = true, addedAt = 0)))

        val a = repo().loadFresh()
        val b = repo().loadFresh()
        assertEquals(a, b)
        assertTrue(FreezeDecisionEngine.evaluate("ig", time.now(), time.zone(), a) is Decision.Frozen)
        assertTrue(FreezeDecisionEngine.evaluate("yt", time.now(), time.zone(), a) is Decision.Frozen)
        assertTrue("maps" in a.protectedPackages && "com.android.settings" in a.protectedPackages)
    }

    @Test fun `snapshot flow matches one-shot load and tracks writes`() = runBlocking {
        val r = repo()
        withTimeout(5_000) { r.snapshot.filterNotNull().first() }
        db.scheduleDao().save(ScheduleEntity(0, "Work", true, 7 * 60, 9 * 60, 0b1111111, 0), setOf("ig"))
        val snap = withTimeout(5_000) { r.snapshot.filterNotNull().first { it.schedules.isNotEmpty() } }
        assertEquals(r.loadFresh().schedules, snap.schedules)
    }

    @Test fun `emergency passes remaining counts a rolling week and expired passes are ignored`() = runBlocking {
        val now = time.now().toEpochMilli()
        val day = Duration.ofDays(1).toMillis()
        db.emergencyDao().insertLog(EmergencyLogEntity(packageName = "a", timestamp = now - 8 * day, kind = "PASS", minutes = 5))
        db.emergencyDao().grantPass(
            EmergencyPassEntity(packageName = "a", grantedAt = now - 3 * day, expiresAt = now - 3 * day + 300_000),
            EmergencyLogEntity(packageName = "a", timestamp = now - 3 * day, kind = "PASS", minutes = 5),
        )
        val ctx = repo().loadFresh()
        assertEquals(2, ctx.emergencyPassesRemaining)
        assertTrue(ctx.emergencyPasses.isEmpty())
        val snap = withTimeout(5_000) { repo().snapshot.filterNotNull().first() }
        assertEquals(2, snap.emergencyPassesRemaining)
        assertFalse(snap.emergencyPasses.any())
    }
}
