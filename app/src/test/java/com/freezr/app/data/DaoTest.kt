package com.freezr.app.data

import app.cash.turbine.test
import com.freezr.app.data.db.EmergencyLogEntity
import com.freezr.app.data.db.EmergencyPassEntity
import com.freezr.app.data.db.FreezrDatabase
import com.freezr.app.data.db.QuickFreezeEntity
import com.freezr.app.data.db.ScheduleEntity
import com.freezr.app.testutil.inMemoryDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoTest {
    private lateinit var db: FreezrDatabase

    @Before fun setUp() { db = inMemoryDb() }
    @After fun tearDown() { db.close() }

    private fun sched(id: Long = 0, name: String = "Sleep") =
        ScheduleEntity(id, name, true, 23 * 60, 7 * 60, 0b1111111, 0)

    @Test fun `schedule saves with packages atomically and replaces package set on edit`() = runTest {
        val id = db.scheduleDao().save(sched(), setOf("a", "b"))
        assertEquals(setOf("a", "b"), db.scheduleDao().get(id)!!.packages.map { it.packageName }.toSet())
        db.scheduleDao().save(sched(id, "Sleep 2"), setOf("c"))
        val reloaded = db.scheduleDao().get(id)!!
        assertEquals("Sleep 2", reloaded.schedule.name)
        assertEquals(listOf("c"), reloaded.packages.map { it.packageName })
    }

    @Test fun `deleting a schedule cascades to its packages`() = runTest {
        val id = db.scheduleDao().save(sched(), setOf("a"))
        db.scheduleDao().delete(id)
        assertNull(db.scheduleDao().get(id))
        assertEquals(0, db.query("SELECT * FROM schedule_package", null).use { it.count })
    }

    @Test fun `observe emits on change`() = runTest {
        db.scheduleDao().observeAll().test {
            assertEquals(0, awaitItem().size)
            db.scheduleDao().save(sched(), setOf("a"))
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `quick freeze single row round trips package list`() = runTest {
        db.quickFreezeDao().set(QuickFreezeEntity(startedAt = 1, endsAt = 2, packages = listOf("x", "y"), label = "Focus"))
        assertEquals(listOf("x", "y"), db.quickFreezeDao().get()!!.packages)
        db.quickFreezeDao().clear()
        assertNull(db.quickFreezeDao().get())
    }

    @Test fun `emergency pass and log are written together and counted`() = runTest {
        db.emergencyDao().grantPass(EmergencyPassEntity(packageName = "a", grantedAt = 100, expiresAt = 400), EmergencyLogEntity(packageName = "a", timestamp = 100, kind = "PASS", minutes = 5))
        assertEquals(1, db.emergencyDao().countSince(50))
        assertEquals(0, db.emergencyDao().countSince(101))
        assertEquals(1, db.emergencyDao().getActivePasses(399).size)
        assertEquals(0, db.emergencyDao().getActivePasses(400).size)
        db.emergencyDao().pruneExpired(400)
        db.emergencyDao().observePasses().test { assertEquals(0, awaitItem().size) }
    }
}
