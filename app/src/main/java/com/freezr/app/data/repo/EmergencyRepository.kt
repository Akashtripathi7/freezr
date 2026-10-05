package com.freezr.app.data.repo

import com.freezr.app.data.db.EmergencyDao
import com.freezr.app.data.db.EmergencyLogEntity
import com.freezr.app.data.db.EmergencyPassEntity
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.domain.engine.FreezeDecisionEngine
import com.freezr.app.domain.engine.TimeWindows
import com.freezr.app.domain.model.Decision
import com.freezr.app.domain.model.FreezeReason
import com.freezr.app.domain.strict.EmergencyPolicy
import com.freezr.app.domain.time.TimeSource
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GrantResult {
    /** A pass for one package until [expiresAt]. */
    data class Pass(val expiresAt: Instant) : GrantResult
    /** Extra minutes added to today's daily limit. */
    data class ExtraMinutes(val minutes: Int) : GrantResult
    data object NoPassesLeft : GrantResult
    data object NotFrozen : GrantResult
    data object NotAllowed : GrantResult
}

@Singleton
class EmergencyRepository @Inject constructor(
    private val dao: EmergencyDao,
    private val limits: LimitRepository,
    private val settings: SettingsRepository,
    private val stateRepo: FreezeStateRepository,
    private val usage: UsageSource,
    private val notifier: RuleChangeNotifier,
    private val time: TimeSource,
) {
    fun log(): Flow<List<EmergencyLogEntity>> =
        dao.observeLog(time.now().minus(Duration.ofDays(30)).toEpochMilli())

    /**
     * Grants an emergency unlock for exactly one package, after the UI friction chain completed.
     * Persisted as an absolute expiry (or as bonus minutes for a limit) and always logged.
     */
    suspend fun grant(packageName: String): GrantResult {
        val s = settings.current()
        if (!s.emergencyEnabled) return GrantResult.NotAllowed
        val ctx = stateRepo.loadFresh()
        if (ctx.emergencyPassesRemaining <= 0) return GrantResult.NoPassesLeft
        val now = time.now()
        val decision = FreezeDecisionEngine.evaluate(packageName, now, time.zone(), ctx.copy(usageToday = usage.usageToday.value))
        if (decision !is Decision.Frozen) return GrantResult.NotFrozen
        if (!decision.canEmergencyUnlock) return GrantResult.NotAllowed

        return if (decision.reason == FreezeReason.USAGE_LIMIT) {
            val minutes = s.emergencyExtraLimitMinutes
            limits.addBonus(packageName, TimeWindows.usageDay(now, time.zone(), s.usageResetMinute), minutes)
            dao.insertLog(EmergencyLogEntity(packageName = packageName, timestamp = now.toEpochMilli(), kind = KIND_EXTRA, minutes = minutes))
            notifier.rulesChanged()
            GrantResult.ExtraMinutes(minutes)
        } else {
            val expires = now.plus(Duration.ofMinutes(s.emergencyPassMinutes.toLong()))
            dao.grantPass(
                EmergencyPassEntity(packageName = packageName, grantedAt = now.toEpochMilli(), expiresAt = expires.toEpochMilli()),
                EmergencyLogEntity(packageName = packageName, timestamp = now.toEpochMilli(), kind = KIND_PASS, minutes = s.emergencyPassMinutes),
            )
            notifier.rulesChanged()
            GrantResult.Pass(expires)
        }
    }

    suspend fun passesRemaining(): Int {
        val s = settings.current()
        val used = dao.countSince(time.now().minus(EmergencyPolicy.WEEK).toEpochMilli())
        return EmergencyPolicy.passesRemaining(s.emergencyPassesPerWeek, used)
    }

    companion object {
        const val KIND_PASS = "PASS"
        const val KIND_EXTRA = "EXTRA_MINUTES"
    }
}
