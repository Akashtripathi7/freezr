package com.freezr.app.data.repo

import androidx.room.withTransaction
import com.freezr.app.data.db.EmergencyPassEntity
import com.freezr.app.data.db.FreezrDatabase
import com.freezr.app.data.db.LimitBonusEntity
import com.freezr.app.data.db.QuickFreezeEntity
import com.freezr.app.data.db.ScheduleWithPackages
import com.freezr.app.data.db.UsageLimitEntity
import com.freezr.app.data.db.WhitelistEntity
import com.freezr.app.data.settings.AppSettings
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.di.ApplicationScope
import com.freezr.app.di.OwnPackage
import com.freezr.app.domain.engine.TimeWindows
import com.freezr.app.domain.model.ContextRule
import com.freezr.app.domain.model.EmergencyPass
import com.freezr.app.domain.model.EngineContext
import com.freezr.app.domain.model.QuickFreezeState
import com.freezr.app.domain.model.ScheduleRule
import com.freezr.app.domain.model.UsageLimitRule
import com.freezr.app.domain.strict.EmergencyPolicy
import com.freezr.app.domain.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Package names that can never be frozen (dialer, launcher, Settings …), resolved by the platform layer. */
interface ProtectedPackagesSource {
    val corePackages: StateFlow<Set<String>>
}

/** Today's per-package foreground time, reconciled from the system's usage-stats store. */
interface UsageSource {
    val usageToday: StateFlow<Map<String, Duration>>
}

/** Location / Wi-Fi rules as engine models. */
interface ContextRuleSource {
    fun observe(): Flow<List<ContextRule>>
    suspend fun get(): List<ContextRule>
}

/**
 * Builds the [EngineContext] snapshot from Room + DataStore. The snapshot is purely derived:
 * it can be rebuilt from disk at any moment (process death, reboot, update).
 */
@Singleton
class FreezeStateRepository @Inject constructor(
    private val db: FreezrDatabase,
    private val settingsRepo: SettingsRepository,
    private val protectedSource: ProtectedPackagesSource,
    private val usageSource: UsageSource,
    private val contextRuleSource: ContextRuleSource,
    private val time: TimeSource,
    @OwnPackage private val ownPackage: String,
    @ApplicationScope scope: CoroutineScope,
) {
    /** Bumped by alarms / time changes so time-dependent derived values are recomputed. */
    private val tick = MutableStateFlow(0L)

    private data class RoomPart(
        val schedules: List<ScheduleWithPackages>,
        val limits: List<UsageLimitEntity>,
        val bonuses: List<LimitBonusEntity>,
        val quick: QuickFreezeEntity?,
        val passes: List<EmergencyPassEntity>,
    )

    private data class SidePart(
        val logTimes: List<Long>,
        val whitelist: List<WhitelistEntity>,
        val contextRules: List<ContextRule>,
        val usage: Map<String, Duration>,
    )

    private val roomPart = combine(
        db.scheduleDao().observeAll(),
        db.limitDao().observeLimits(),
        db.limitDao().observeBonuses(),
        db.quickFreezeDao().observe(),
        db.emergencyDao().observePasses(),
        ::RoomPart,
    )

    private val sidePart = combine(
        db.emergencyDao().observeLogTimes(time.now().minus(Duration.ofDays(30)).toEpochMilli()),
        db.whitelistDao().observe(),
        contextRuleSource.observe(),
        usageSource.usageToday,
        ::SidePart,
    )

    /** Null until the first full load from disk completes. */
    val snapshot: StateFlow<EngineContext?> = combine(
        roomPart, sidePart, settingsRepo.settings, protectedSource.corePackages, tick,
    ) { r, s, settings, core, _ ->
        val now = time.now()
        val weekAgo = now.minus(EmergencyPolicy.WEEK).toEpochMilli()
        assemble(
            ownPackage, now, time, settings, r.schedules, r.limits, r.bonuses, r.quick,
            r.passes.filter { it.expiresAt > now.toEpochMilli() },
            s.logTimes.count { it >= weekAgo }, s.whitelist, core, s.usage, s.contextRules,
        )
    }.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun await(): EngineContext = snapshot.filterNotNull().first()

    fun refresh() {
        tick.value = tick.value + 1
    }

    /** One-shot read straight from disk inside a single transaction (consistent snapshot). */
    suspend fun loadFresh(): EngineContext {
        val now = time.now()
        val settings = settingsRepo.current()
        val day = TimeWindows.usageDay(now, time.zone(), settings.usageResetMinute).toString()
        return db.withTransaction {
            assemble(
                ownPackage, now, time, settings,
                db.scheduleDao().getAll(),
                db.limitDao().getLimits(),
                db.limitDao().getBonuses(day),
                db.quickFreezeDao().get(),
                db.emergencyDao().getActivePasses(now.toEpochMilli()),
                db.emergencyDao().countSince(now.minus(EmergencyPolicy.WEEK).toEpochMilli()),
                db.whitelistDao().getAll(),
                protectedSource.corePackages.value,
                usageSource.usageToday.value,
                contextRuleSource.get(),
            )
        }
    }

    companion object {
        fun assemble(
            ownPackage: String,
            now: Instant,
            time: TimeSource,
            settings: AppSettings,
            schedules: List<ScheduleWithPackages>,
            limits: List<UsageLimitEntity>,
            bonuses: List<LimitBonusEntity>,
            quick: QuickFreezeEntity?,
            passes: List<EmergencyPassEntity>,
            emergencyUsesThisWeek: Int,
            whitelist: List<WhitelistEntity>,
            corePackages: Set<String>,
            usage: Map<String, Duration>,
            contextRules: List<ContextRule>,
        ): EngineContext {
            val zone = time.zone()
            val day = TimeWindows.usageDay(now, zone, settings.usageResetMinute).toString()
            return EngineContext(
                ownPackage = ownPackage,
                protectedPackages = corePackages + whitelist.map { it.packageName },
                masterEnabled = settings.masterEnabled,
                strictModeEnabled = settings.strictEnabled,
                emergencyPasses = passes.map { EmergencyPass(it.packageName, Instant.ofEpochMilli(it.expiresAt)) },
                emergencyPassesRemaining = if (settings.emergencyEnabled) {
                    EmergencyPolicy.passesRemaining(settings.emergencyPassesPerWeek, emergencyUsesThisWeek)
                } else {
                    0
                },
                quickFreeze = quick?.let {
                    QuickFreezeState(Instant.ofEpochMilli(it.startedAt), Instant.ofEpochMilli(it.endsAt), it.packages.toSet())
                },
                schedules = schedules.map { it.toRule() },
                limits = limits.map { UsageLimitRule(it.packageName, it.limitMinutes, it.enabled) },
                usageToday = usage,
                limitBonusMinutes = bonuses.filter { it.day == day }.associate { it.packageName to it.minutes },
                usageResetMinute = settings.usageResetMinute,
                contextRules = contextRules,
            )
        }
    }
}

fun ScheduleWithPackages.toRule() = ScheduleRule(
    id = schedule.id,
    name = schedule.name,
    enabled = schedule.enabled,
    startMinute = schedule.startMinute,
    endMinute = schedule.endMinute,
    daysMask = schedule.daysMask,
    packages = packages.map { it.packageName }.toSet(),
)
