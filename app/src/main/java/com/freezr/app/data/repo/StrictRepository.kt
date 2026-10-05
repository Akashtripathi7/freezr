package com.freezr.app.data.repo

import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.domain.security.PinHasher
import com.freezr.app.domain.security.PinLockoutPolicy
import com.freezr.app.domain.strict.DelayUnlockPolicy
import com.freezr.app.domain.strict.DelayUnlockState
import com.freezr.app.domain.strict.StrictUnlockMethod
import com.freezr.app.domain.time.TimeSource
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Small encrypted key/value store (Android Keystore-backed in production). */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

sealed interface PartnerApprovalResult {
    data object Approved : PartnerApprovalResult
    data object Denied : PartnerApprovalResult
    data class Unavailable(val reason: String) : PartnerApprovalResult
}

/**
 * Partner approval needs a second person's device and therefore a backend. The interface is kept so
 * a real provider can be dropped in; this build ships [LocalPartnerApprovalStub].
 */
interface PartnerApprovalProvider {
    val isAvailable: Boolean
    suspend fun requestApproval(reason: String): PartnerApprovalResult
}

/** The only stub in the app (explicitly allowed): partner approval is not available offline. */
class LocalPartnerApprovalStub @Inject constructor() : PartnerApprovalProvider {
    override val isAvailable: Boolean = false
    override suspend fun requestApproval(reason: String): PartnerApprovalResult =
        PartnerApprovalResult.Unavailable("Partner approval requires an online account service, which this build does not include.")
}

sealed interface PinResult {
    data object Success : PinResult
    data class Wrong(val attemptsBeforeLockout: Int) : PinResult
    data class LockedOut(val until: Instant) : PinResult
    data object NoPinSet : PinResult
}

sealed interface DelayRequestResult {
    data class Started(val readyAt: Instant) : DelayRequestResult
    data object AlreadyRunning : DelayRequestResult
    data object RateLimited : DelayRequestResult
}

/**
 * Strict Mode configuration and unlock flows. Every bypass attempt (successful or not) is logged and
 * rate-limited. A successful unlock opens a short, persisted "unlocked until" window.
 */
@Singleton
class StrictRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val guard: StrictGuard,
    private val notifier: RuleChangeNotifier,
    private val recorder: InsightsRecorder,
    private val secrets: SecretStore,
    private val hasher: PinHasher,
    private val partner: PartnerApprovalProvider,
    private val time: TimeSource,
) {
    fun hasPin(): Boolean = secrets.get(KEY_PIN) != null

    val partnerAvailable: Boolean get() = partner.isAvailable

    suspend fun enable(method: StrictUnlockMethod) {
        require(method != StrictUnlockMethod.PIN || hasPin()) { "Set a PIN first" }
        require(method != StrictUnlockMethod.PARTNER || partner.isAvailable) { "Partner approval unavailable" }
        settings.update {
            strictEnabled = true
            strictMethod = method
            strictUnlockedUntil = null
            strictDelayRequestedAt = null
        }
        notifier.rulesChanged()
    }

    suspend fun disable() {
        guard.requireUnlocked()
        settings.update {
            strictEnabled = false
            strictUnlockedUntil = null
            strictDelayRequestedAt = null
        }
        notifier.rulesChanged()
    }

    suspend fun setMethod(method: StrictUnlockMethod) {
        guard.requireUnlocked()
        require(method != StrictUnlockMethod.PIN || hasPin())
        require(method != StrictUnlockMethod.PARTNER || partner.isAvailable)
        settings.update { strictMethod = method }
    }

    suspend fun setDelayMinutes(minutes: Int) {
        val current = settings.current().strictDelayMinutes
        if (minutes < current) guard.requireUnlocked()
        settings.update { strictDelayMinutes = minutes.coerceIn(1, 120) }
    }

    suspend fun setPin(pin: String) {
        require(PinHasher.isValidPin(pin))
        if (hasPin()) guard.requireUnlocked()
        secrets.put(KEY_PIN, hasher.hash(pin))
        settings.update {
            pinFailures = 0
            pinLastFailureAt = null
        }
    }

    suspend fun verifyPin(pin: String): PinResult {
        val stored = secrets.get(KEY_PIN) ?: return PinResult.NoPinSet
        val s = settings.current()
        val now = time.now()
        PinLockoutPolicy.lockedUntil(s.pinFailures, s.pinLastFailureAt)?.let { until ->
            if (now.isBefore(until)) {
                recorder.recordBypass(METHOD_PIN, false, "locked_out")
                return PinResult.LockedOut(until)
            }
        }
        if (hasher.verify(pin, stored)) {
            settings.update {
                pinFailures = 0
                pinLastFailureAt = null
                strictUnlockedUntil = now.plus(UNLOCK_SESSION)
            }
            recorder.recordBypass(METHOD_PIN, true, "unlocked")
            notifier.rulesChanged()
            return PinResult.Success
        }
        val failures = s.pinFailures + 1
        settings.update {
            pinFailures = failures
            pinLastFailureAt = now
        }
        recorder.recordBypass(METHOD_PIN, false, "wrong_pin")
        val until = PinLockoutPolicy.lockedUntil(failures, now)
        return if (until != null) PinResult.LockedOut(until) else PinResult.Wrong(PinLockoutPolicy.FREE_ATTEMPTS - failures)
    }

    fun delayState(requestedAt: Instant?, delayMinutes: Int, confirmMinutes: Int): DelayUnlockState =
        DelayUnlockPolicy.state(
            requestedAt, time.now(),
            Duration.ofMinutes(delayMinutes.toLong()), Duration.ofMinutes(confirmMinutes.toLong()),
        )

    suspend fun requestDelayUnlock(): DelayRequestResult {
        val s = settings.current()
        val state = delayState(s.strictDelayRequestedAt, s.strictDelayMinutes, s.strictConfirmWindowMinutes)
        if (state is DelayUnlockState.Waiting || state is DelayUnlockState.Ready) return DelayRequestResult.AlreadyRunning
        val hourAgo = time.now().minus(Duration.ofHours(1)).toEpochMilli()
        if (recorder.bypassAttemptsSince(hourAgo, METHOD_DELAY) >= MAX_DELAY_REQUESTS_PER_HOUR) {
            recorder.recordBypass(METHOD_DELAY, false, "rate_limited")
            return DelayRequestResult.RateLimited
        }
        val now = time.now()
        settings.update { strictDelayRequestedAt = now }
        recorder.recordBypass(METHOD_DELAY, false, "requested")
        return DelayRequestResult.Started(now.plus(Duration.ofMinutes(s.strictDelayMinutes.toLong())))
    }

    suspend fun confirmDelayUnlock(): Boolean {
        val s = settings.current()
        val state = delayState(s.strictDelayRequestedAt, s.strictDelayMinutes, s.strictConfirmWindowMinutes)
        if (state !is DelayUnlockState.Ready) {
            recorder.recordBypass(METHOD_DELAY, false, "confirm_outside_window")
            return false
        }
        val now = time.now()
        settings.update {
            strictUnlockedUntil = now.plus(UNLOCK_SESSION)
            strictDelayRequestedAt = null
        }
        recorder.recordBypass(METHOD_DELAY, true, "unlocked")
        notifier.rulesChanged()
        return true
    }

    suspend fun cancelDelayRequest() {
        settings.update { strictDelayRequestedAt = null }
        recorder.recordBypass(METHOD_DELAY, false, "cancelled")
    }

    suspend fun requestPartnerApproval(): PartnerApprovalResult {
        val result = partner.requestApproval("Unlock Strict Mode")
        recorder.recordBypass(METHOD_PARTNER, result is PartnerApprovalResult.Approved, result::class.simpleName.orEmpty())
        if (result is PartnerApprovalResult.Approved) {
            settings.update { strictUnlockedUntil = time.now().plus(UNLOCK_SESSION) }
            notifier.rulesChanged()
        }
        return result
    }

    /** End an unlock session early. */
    suspend fun relock() {
        settings.update { strictUnlockedUntil = null }
        notifier.rulesChanged()
    }

    /** Logged by the accessibility service when it blocks a protected Settings screen. */
    suspend fun recordSettingsBypassAttempt(screen: String) = recorder.recordBypass(METHOD_SETTINGS, false, screen)

    companion object {
        val UNLOCK_SESSION: Duration = Duration.ofMinutes(5)
        const val MAX_DELAY_REQUESTS_PER_HOUR = 3
        const val KEY_PIN = "strict_pin_hash"
        const val METHOD_PIN = "PIN"
        const val METHOD_DELAY = "DELAY"
        const val METHOD_PARTNER = "PARTNER"
        const val METHOD_SETTINGS = "SETTINGS_SCREEN"
    }
}
