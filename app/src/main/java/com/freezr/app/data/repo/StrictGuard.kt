package com.freezr.app.data.repo

import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.domain.engine.FreezeDecisionEngine
import com.freezr.app.domain.strict.StrictModePolicy
import com.freezr.app.domain.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import javax.inject.Inject
import javax.inject.Singleton

class StrictModeLockedException : IllegalStateException("Strict Mode is active while a freeze is running")

/** Anything that changes a rule calls this so alarms, notifications and the service re-plan. */
interface RuleChangeNotifier {
    fun rulesChanged()
}

/**
 * Enforces Strict Mode at the data layer, so no UI path (screen, tile, widget, notification)
 * can loosen a restriction while a freeze is active.
 */
@Singleton
class StrictGuard @Inject constructor(
    private val stateRepo: FreezeStateRepository,
    private val settings: SettingsRepository,
    private val time: TimeSource,
) {
    suspend fun isLocked(): Boolean {
        val s = settings.current()
        if (!s.strictEnabled) return false
        val now = time.now()
        val active = FreezeDecisionEngine.isAnyFreezeActive(now, time.zone(), stateRepo.loadFresh())
        return StrictModePolicy.isLocked(true, active, s.strictUnlockedUntil, now)
    }

    suspend fun requireUnlocked() {
        if (isLocked()) throw StrictModeLockedException()
    }

    /** Live lock state for the UI. Re-evaluated on every snapshot / settings change. */
    val locked: Flow<Boolean> = combine(stateRepo.snapshot.filterNotNull(), settings.settings) { ctx, s ->
        val now = time.now()
        StrictModePolicy.isLocked(
            s.strictEnabled,
            FreezeDecisionEngine.isAnyFreezeActive(now, time.zone(), ctx),
            s.strictUnlockedUntil,
            now,
        )
    }.distinctUntilChanged()
}
