package com.freezr.app.data.repo

import com.freezr.app.data.db.WhitelistDao
import com.freezr.app.data.db.WhitelistEntity
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.domain.time.TimeSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings writes with Strict Mode semantics: a change that makes Freezr less strict is refused
 * while Strict Mode is locked; a change that makes it stricter always goes through.
 */
@Singleton
class PreferencesActions @Inject constructor(
    private val settings: SettingsRepository,
    private val guard: StrictGuard,
    private val notifier: RuleChangeNotifier,
    private val whitelistDao: WhitelistDao,
    private val time: TimeSource,
) {
    suspend fun setPreFreeze(enabled: Boolean, minutes: Int) {
        settings.update {
            preFreezeEnabled = enabled
            preFreezeMinutes = minutes.coerceIn(1, 60)
        }
        notifier.rulesChanged()
    }

    suspend fun setLimitWarnings(enabled: Boolean) = settings.update { limitWarningsEnabled = enabled }

    suspend fun setStatusNotification(enabled: Boolean) {
        settings.update { statusNotificationEnabled = enabled }
        notifier.rulesChanged()
    }

    suspend fun setUsageResetMinute(minute: Int) {
        // Moving the reset can hand back usage early, so it counts as loosening.
        guard.requireUnlocked()
        settings.update { usageResetMinute = minute.coerceIn(0, 24 * 60 - 1) }
        notifier.rulesChanged()
    }

    suspend fun setProtectSettingsScreens(enabled: Boolean) {
        if (!enabled) guard.requireUnlocked()
        settings.update { protectSettingsScreens = enabled }
    }

    suspend fun setEmergency(
        enabled: Boolean,
        waitSeconds: Int,
        phrase: String,
        passesPerWeek: Int,
        passMinutes: Int,
        extraLimitMinutes: Int,
    ) {
        val c = settings.current()
        val loosens = (enabled && !c.emergencyEnabled) ||
            waitSeconds < c.emergencyWaitSeconds ||
            passesPerWeek > c.emergencyPassesPerWeek ||
            passMinutes > c.emergencyPassMinutes ||
            extraLimitMinutes > c.emergencyExtraLimitMinutes ||
            phrase.trim().length < c.emergencyPhrase.trim().length
        if (loosens) guard.requireUnlocked()
        settings.update {
            emergencyEnabled = enabled
            emergencyWaitSeconds = waitSeconds.coerceIn(5, 600)
            emergencyPhrase = phrase.trim().ifEmpty { c.emergencyPhrase }
            emergencyPassesPerWeek = passesPerWeek.coerceIn(0, 21)
            emergencyPassMinutes = passMinutes.coerceIn(1, 60)
            emergencyExtraLimitMinutes = extraLimitMinutes.coerceIn(1, 120)
        }
        notifier.rulesChanged()
    }

    /** Whitelisting an app loosens blocking. */
    suspend fun addToWhitelist(packages: Collection<String>) {
        if (packages.isEmpty()) return
        guard.requireUnlocked()
        whitelistDao.insert(packages.map { WhitelistEntity(it, isDefault = false, addedAt = time.now().toEpochMilli()) })
        notifier.rulesChanged()
    }

    suspend fun removeFromWhitelist(packageName: String) {
        whitelistDao.delete(packageName)
        notifier.rulesChanged()
    }
}
