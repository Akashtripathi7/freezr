package com.freezr.app.domain.model

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant

/** Why a package is frozen. The order of the enum is not the precedence; see FreezeDecisionEngine. */
enum class FreezeReason { QUICK_FREEZE, SCHEDULE, USAGE_LIMIT, LOCATION, WIFI, WEBSITE, IN_APP }

sealed interface Decision {
    data object Allowed : Decision

    data class Frozen(
        val reason: FreezeReason,
        val unfreezeAt: Instant?,
        val ruleId: String,
        val ruleName: String,
        val canEmergencyUnlock: Boolean,
    ) : Decision
}

/** Bitmask helpers: Monday = bit 0 … Sunday = bit 6. */
object Days {
    const val NONE = 0
    const val WEEKDAYS = 0b0011111
    const val WEEKEND = 0b1100000
    const val ALL = 0b1111111

    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)
    fun contains(mask: Int, day: DayOfWeek): Boolean = mask and bit(day) != 0
    fun toggle(mask: Int, day: DayOfWeek): Int = mask xor bit(day)
    fun of(vararg days: DayOfWeek): Int = days.fold(0) { acc, d -> acc or bit(d) }
}

data class ScheduleRule(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val startMinute: Int,
    val endMinute: Int,
    val daysMask: Int,
    val packages: Set<String>,
)

data class UsageLimitRule(
    val packageName: String,
    val limitMinutes: Int,
    val enabled: Boolean,
)

data class EmergencyPass(val packageName: String, val expiresAt: Instant)

data class QuickFreezeState(
    val startedAt: Instant,
    val endsAt: Instant,
    val packages: Set<String>,
)

enum class ContextRuleType { GEOFENCE, WIFI }

/** Optional time-of-day filter on a location / Wi-Fi rule. Same window semantics as schedules. */
data class TimeFilter(val startMinute: Int, val endMinute: Int, val daysMask: Int)

data class ContextRule(
    val id: Long,
    val name: String,
    val type: ContextRuleType,
    val enabled: Boolean,
    /** Persisted "inside the geofence" / "connected to the SSID" state. */
    val active: Boolean,
    val packages: Set<String>,
    val filter: TimeFilter?,
)

/**
 * Complete, immutable input to the decision engine. Built from Room + DataStore (+ the
 * system's own usage-stats store), never from transient in-memory state.
 */
data class EngineContext(
    val ownPackage: String,
    val protectedPackages: Set<String>,
    val masterEnabled: Boolean,
    val strictModeEnabled: Boolean,
    val emergencyPasses: List<EmergencyPass>,
    val emergencyPassesRemaining: Int,
    val quickFreeze: QuickFreezeState?,
    val schedules: List<ScheduleRule>,
    val limits: List<UsageLimitRule>,
    val usageToday: Map<String, Duration>,
    val limitBonusMinutes: Map<String, Int>,
    val usageResetMinute: Int,
    val contextRules: List<ContextRule>,
) {
    companion object {
        fun empty(ownPackage: String) = EngineContext(
            ownPackage = ownPackage,
            protectedPackages = emptySet(),
            masterEnabled = true,
            strictModeEnabled = false,
            emergencyPasses = emptyList(),
            emergencyPassesRemaining = 0,
            quickFreeze = null,
            schedules = emptyList(),
            limits = emptyList(),
            usageToday = emptyMap(),
            limitBonusMinutes = emptyMap(),
            usageResetMinute = 0,
            contextRules = emptyList(),
        )
    }
}

/** A rule currently freezing something, for display on the home screen. */
data class ActiveFreeze(
    val ruleId: String,
    val ruleName: String,
    val reason: FreezeReason,
    val packages: Set<String>,
    val until: Instant?,
)

/** A schedule window that will start in the future (for pre-freeze notifications). */
data class UpcomingFreeze(
    val startsAt: Instant,
    val scheduleId: Long,
    val scheduleName: String,
    val packages: Set<String>,
)
