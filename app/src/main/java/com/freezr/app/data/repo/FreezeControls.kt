package com.freezr.app.data.repo

import com.freezr.app.data.db.QuickFreezeDao
import com.freezr.app.data.db.QuickFreezeEntity
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.domain.time.TimeSource
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

class NothingToFreezeException : IllegalStateException("No apps selected to freeze")

/** Master toggle and Quick Freeze, shared by the app UI, notification actions, tiles and the widget. */
@Singleton
class FreezeControls @Inject constructor(
    private val settings: SettingsRepository,
    private val guard: StrictGuard,
    private val notifier: RuleChangeNotifier,
    private val quickDao: QuickFreezeDao,
    private val selection: AppSelectionRepository,
    private val time: TimeSource,
) {
    suspend fun setMasterEnabled(enabled: Boolean) {
        if (!enabled) guard.requireUnlocked()
        settings.update { masterEnabled = enabled }
        notifier.rulesChanged()
    }

    /**
     * Starts (or extends) a Quick Freeze. [packages] = null means "all my selected apps".
     * The absolute end time is persisted so the session survives process death.
     */
    suspend fun startQuickFreeze(duration: Duration, packages: Set<String>? = null, label: String = "Focus") {
        val targets = packages ?: selection.selectedNow()
        if (targets.isEmpty()) throw NothingToFreezeException()
        val now = time.now()
        val current = quickDao.get()?.takeIf { now.toEpochMilli() < it.endsAt }
        val endsAt = now.plus(duration).toEpochMilli()
        quickDao.set(
            QuickFreezeEntity(
                startedAt = current?.startedAt ?: now.toEpochMilli(),
                endsAt = maxOf(endsAt, current?.endsAt ?: 0),
                packages = ((current?.packages ?: emptyList()) + targets).distinct(),
                label = label,
            ),
        )
        // Turning on a focus session implies protection is on.
        settings.update { masterEnabled = true }
        notifier.rulesChanged()
    }

    /** Ending early is a loosening change: blocked under Strict Mode. */
    suspend fun cancelQuickFreeze() {
        guard.requireUnlocked()
        quickDao.clear()
        notifier.rulesChanged()
    }
}
