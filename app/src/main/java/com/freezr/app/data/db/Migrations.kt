package com.freezr.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Hand-written migrations, verified against the exported schemas by MigrationTest. */
object Migrations {

    /** v2 adds location / Wi-Fi rules and website blocking. Existing data is untouched. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `context_rule` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `type` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `latitude` REAL, " +
                    "`longitude` REAL, `radius_meters` REAL, `ssid` TEXT, `active` INTEGER NOT NULL, " +
                    "`active_updated_at` INTEGER NOT NULL, `filter_enabled` INTEGER NOT NULL, `filter_start` INTEGER NOT NULL, " +
                    "`filter_end` INTEGER NOT NULL, `filter_days` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `context_rule_package` (`rule_id` INTEGER NOT NULL, `package_name` TEXT NOT NULL, " +
                    "PRIMARY KEY(`rule_id`, `package_name`), FOREIGN KEY(`rule_id`) REFERENCES `context_rule`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `blocked_domain` (`domain` TEXT NOT NULL, `added_at` INTEGER NOT NULL, PRIMARY KEY(`domain`))",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
