package com.freezr.app.ui.strict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freezr.app.data.db.BypassLogEntity
import com.freezr.app.data.db.InsightsDao
import com.freezr.app.data.repo.DelayRequestResult
import com.freezr.app.data.repo.PartnerApprovalResult
import com.freezr.app.data.repo.PinResult
import com.freezr.app.data.repo.StrictGuard
import com.freezr.app.data.repo.StrictModeLockedException
import com.freezr.app.data.repo.StrictRepository
import com.freezr.app.data.settings.AppSettings
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.domain.strict.DelayUnlockState
import com.freezr.app.domain.strict.StrictUnlockMethod
import com.freezr.app.domain.time.TimeSource
import com.freezr.app.platform.health.PermissionHealthChecker
import com.freezr.app.ui.schedules.ticker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

data class StrictUiState(
    val settings: AppSettings = AppSettings(),
    val locked: Boolean = false,
    val hasPin: Boolean = false,
    val adminActive: Boolean = false,
    val partnerAvailable: Boolean = false,
    val delay: DelayUnlockState = DelayUnlockState.Idle,
    val unlockedRemaining: Duration? = null,
    val log: List<BypassLogEntity> = emptyList(),
    val now: Instant = Instant.EPOCH,
)

sealed interface StrictMessage {
    data object Blocked : StrictMessage
    data class Pin(val result: PinResult) : StrictMessage
    data class Delay(val result: DelayRequestResult) : StrictMessage
    data object DelayConfirmed : StrictMessage
    data class Partner(val result: PartnerApprovalResult) : StrictMessage
    data object PinSaved : StrictMessage
}

@HiltViewModel
class StrictViewModel @Inject constructor(
    private val repo: StrictRepository,
    settings: SettingsRepository,
    guard: StrictGuard,
    insights: InsightsDao,
    private val health: PermissionHealthChecker,
    private val time: TimeSource,
) : ViewModel() {
    private val _messages = MutableSharedFlow<StrictMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<StrictMessage> = _messages
    private val adminActive = MutableStateFlow(health.isDeviceAdminActive())
    private val pinVersion = MutableStateFlow(0)

    val state: StateFlow<StrictUiState> = combine(
        settings.settings,
        guard.locked,
        insights.observeBypasses(time.now().minus(Duration.ofDays(14)).toEpochMilli()),
        time.ticker(1_000),
        combine(adminActive, pinVersion) { a, _ -> a },
    ) { s, locked, log, now, admin ->
        StrictUiState(
            settings = s,
            locked = locked,
            hasPin = repo.hasPin(),
            adminActive = admin,
            partnerAvailable = repo.partnerAvailable,
            delay = repo.delayState(s.strictDelayRequestedAt, s.strictDelayMinutes, s.strictConfirmWindowMinutes),
            unlockedRemaining = s.strictUnlockedUntil?.takeIf { now.isBefore(it) }?.let { Duration.between(now, it) },
            log = log.take(20),
            now = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StrictUiState())

    fun refreshAdmin() { adminActive.value = health.isDeviceAdminActive() }

    fun enable(method: StrictUnlockMethod) = launchGuarded { repo.enable(method) }
    fun disable() = launchGuarded { repo.disable() }
    fun setMethod(method: StrictUnlockMethod) = launchGuarded { repo.setMethod(method) }
    fun setDelayMinutes(m: Int) = launchGuarded { repo.setDelayMinutes(m) }

    /** Saves the PIN, switches the unlock method to PIN and optionally turns Strict Mode on — in order. */
    fun setPinAndUse(pin: String, enable: Boolean) = launchGuarded {
        repo.setPin(pin)
        pinVersion.value++
        if (enable) repo.enable(StrictUnlockMethod.PIN) else repo.setMethod(StrictUnlockMethod.PIN)
        _messages.emit(StrictMessage.PinSaved)
    }

    fun verifyPin(pin: String) = viewModelScope.launch { _messages.emit(StrictMessage.Pin(repo.verifyPin(pin))) }
    fun requestDelay() = viewModelScope.launch { _messages.emit(StrictMessage.Delay(repo.requestDelayUnlock())) }
    fun confirmDelay() = viewModelScope.launch { if (repo.confirmDelayUnlock()) _messages.emit(StrictMessage.DelayConfirmed) }
    fun cancelDelay() = viewModelScope.launch { repo.cancelDelayRequest() }
    fun requestPartner() = viewModelScope.launch { _messages.emit(StrictMessage.Partner(repo.requestPartnerApproval())) }
    fun relock() = viewModelScope.launch { repo.relock() }

    private fun launchGuarded(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (_: StrictModeLockedException) {
            _messages.emit(StrictMessage.Blocked)
        }
    }
}
