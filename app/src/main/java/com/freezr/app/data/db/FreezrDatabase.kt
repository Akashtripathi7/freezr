package com.freezr.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun fromList(list: List<String>): String = list.joinToString("\n")

    @TypeConverter
    fun toList(raw: String): List<String> = if (raw.isEmpty()) emptyList() else raw.split("\n")
}

@Database(
    entities = [
        ScheduleEntity::class,
        SchedulePackageEntity::class,
        SelectedAppEntity::class,
        AppGroupEntity::class,
        AppGroupPackageEntity::class,
        UsageLimitEntity::class,
        LimitBonusEntity::class,
        EmergencyPassEntity::class,
        EmergencyLogEntity::class,
        QuickFreezeEntity::class,
        WhitelistEntity::class,
        BlockAttemptEntity::class,
        BypassLogEntity::class,
        DailyUsageEntity::class,
        ContextRuleEntity::class,
        ContextRulePackageEntity::class,
        BlockedDomainEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FreezrDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
    abstract fun appSelectionDao(): AppSelectionDao
    abstract fun limitDao(): LimitDao
    abstract fun emergencyDao(): EmergencyDao
    abstract fun quickFreezeDao(): QuickFreezeDao
    abstract fun whitelistDao(): WhitelistDao
    abstract fun insightsDao(): InsightsDao
    abstract fun contextRuleDao(): ContextRuleDao
    abstract fun domainDao(): DomainDao

    companion object {
        const val NAME = "freezr.db"
    }
}
