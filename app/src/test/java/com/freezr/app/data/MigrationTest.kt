package com.freezr.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.freezr.app.data.db.FreezrDatabase
import com.freezr.app.data.db.Migrations
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Process-state matrix row 12: data written by v1 survives the upgrade and v2 matches its exported schema. */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FreezrDatabase::class.java)

    @Test fun `migrate 1 to 2 keeps schedules and adds new tables`() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO schedule (id, name, enabled, start_minute, end_minute, days_mask, created_at) VALUES (7, 'Sleep', 1, 1380, 420, 127, 0)")
            execSQL("INSERT INTO schedule_package (schedule_id, package_name) VALUES (7, 'com.instagram.android')")
            execSQL("INSERT INTO usage_limit (package_name, limit_minutes, enabled) VALUES ('com.google.android.youtube', 30, 1)")
            close()
        }

        // Validates the migrated schema against schemas/2.json (droppedTables = false).
        val db = helper.runMigrationsAndValidate(dbName, 2, true, Migrations.MIGRATION_1_2)

        db.query("SELECT name FROM schedule WHERE id = 7").use { c ->
            c.moveToFirst()
            assertEquals("Sleep", c.getString(0))
        }
        db.query("SELECT COUNT(*) FROM schedule_package").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM usage_limit").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }

        db.execSQL(
            "INSERT INTO context_rule (name, type, enabled, latitude, longitude, radius_meters, ssid, active, active_updated_at, " +
                "filter_enabled, filter_start, filter_end, filter_days, created_at) VALUES ('Office', 'GEOFENCE', 1, 1.0, 2.0, 150, NULL, 0, 0, 0, 0, 0, 0, 0)",
        )
        db.execSQL("INSERT INTO blocked_domain (domain, added_at) VALUES ('youtube.com', 0)")
        db.query("SELECT COUNT(*) FROM context_rule").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
    }
}
