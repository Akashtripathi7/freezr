package com.freezr.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "schedule")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    @ColumnInfo(name = "start_minute") val startMinute: Int,
    @ColumnInfo(name = "end_minute") val endMinute: Int,
    @ColumnInfo(name = "days_mask") val daysMask: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "schedule_package",
    primaryKeys = ["schedule_id", "package_name"],
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("package_name")],
)
data class SchedulePackageEntity(
    @ColumnInfo(name = "schedule_id") val scheduleId: Long,
    @ColumnInfo(name = "package_name") val packageName: String,
)

data class ScheduleWithPackages(
    @Embedded val schedule: ScheduleEntity,
    @Relation(parentColumn = "id", entityColumn = "schedule_id")
    val packages: List<SchedulePackageEntity>,
)

/** The user's "my distracting apps" selection from the app list. */
@Entity(tableName = "selected_app")
data class SelectedAppEntity(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Entity(tableName = "app_group")
data class AppGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "app_group_package",
    primaryKeys = ["group_id", "package_name"],
    foreignKeys = [
        ForeignKey(
            entity = AppGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AppGroupPackageEntity(
    @ColumnInfo(name = "group_id") val groupId: Long,
    @ColumnInfo(name = "package_name") val packageName: String,
)

data class AppGroupWithPackages(
    @Embedded val group: AppGroupEntity,
    @Relation(parentColumn = "id", entityColumn = "group_id")
    val packages: List<AppGroupPackageEntity>,
)

@Entity(tableName = "usage_limit")
data class UsageLimitEntity(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "limit_minutes") val limitMinutes: Int,
    val enabled: Boolean,
)

/** Extra minutes granted for one usage day via emergency unlock. day = ISO local date. */
@Entity(tableName = "limit_bonus", primaryKeys = ["package_name", "day"])
data class LimitBonusEntity(
    @ColumnInfo(name = "package_name") val packageName: String,
    val day: String,
    val minutes: Int,
)

@Entity(tableName = "emergency_pass", indices = [Index("expires_at")])
data class EmergencyPassEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "granted_at") val grantedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
)

@Entity(tableName = "emergency_log", indices = [Index("timestamp")])
data class EmergencyLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "package_name") val packageName: String,
    val timestamp: Long,
    /** PASS or EXTRA_MINUTES */
    val kind: String,
    val minutes: Int,
)

/** Single row (id = 0) holding the current Quick Freeze session, if any. */
@Entity(tableName = "quick_freeze")
data class QuickFreezeEntity(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ends_at") val endsAt: Long,
    val packages: List<String>,
    val label: String,
)

/** User-managed whitelist entries (core safety packages are resolved dynamically, not stored). */
@Entity(tableName = "whitelist_entry")
data class WhitelistEntity(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Entity(tableName = "block_attempt", indices = [Index("timestamp"), Index("package_name")])
data class BlockAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "package_name") val packageName: String,
    val timestamp: Long,
    val reason: String,
)

@Entity(tableName = "bypass_log", indices = [Index("timestamp")])
data class BypassLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val method: String,
    val success: Boolean,
    val detail: String,
)

@Entity(tableName = "daily_usage", primaryKeys = ["day", "package_name"])
data class DailyUsageEntity(
    val day: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "foreground_millis") val foregroundMillis: Long,
)

// ---- Schema v2: location / Wi-Fi rules and website blocking ----

@Entity(tableName = "context_rule")
data class ContextRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** GEOFENCE or WIFI */
    val type: String,
    val enabled: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    @ColumnInfo(name = "radius_meters") val radiusMeters: Float?,
    val ssid: String?,
    /** Persisted "inside the fence" / "connected to the SSID" state. */
    val active: Boolean,
    @ColumnInfo(name = "active_updated_at") val activeUpdatedAt: Long,
    @ColumnInfo(name = "filter_enabled") val filterEnabled: Boolean,
    @ColumnInfo(name = "filter_start") val filterStart: Int,
    @ColumnInfo(name = "filter_end") val filterEnd: Int,
    @ColumnInfo(name = "filter_days") val filterDays: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "context_rule_package",
    primaryKeys = ["rule_id", "package_name"],
    foreignKeys = [
        ForeignKey(
            entity = ContextRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["rule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ContextRulePackageEntity(
    @ColumnInfo(name = "rule_id") val ruleId: Long,
    @ColumnInfo(name = "package_name") val packageName: String,
)

data class ContextRuleWithPackages(
    @Embedded val rule: ContextRuleEntity,
    @Relation(parentColumn = "id", entityColumn = "rule_id")
    val packages: List<ContextRulePackageEntity>,
)

@Entity(tableName = "blocked_domain")
data class BlockedDomainEntity(
    @PrimaryKey val domain: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)
