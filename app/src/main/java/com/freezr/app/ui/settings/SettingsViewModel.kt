package com.freezr.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freezr.app.data.db.WhitelistDao
import com.freezr.app.data.db.WhitelistEntity
import com.freezr.app.data.repo.PreferencesActions
import com.freezr.app.data.repo.StrictGuard
import com.freezr.app.data.repo.StrictModeLockedException
import com.freezr.app.data.settings.AppSettings
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.platform.apps.InstalledApp
import com.freezr.app.platform.apps.InstalledAppsRepository
import com.freezr.app.platform.whitelist.WhitelistResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val locked: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    settings: SettingsRepository,
    guard: StrictGuard,
    private val actions: PreferencesActions,
) : ViewModel() {
    private val _blocked = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val blocked: SharedFlow<Unit> = _blocked

    val state: StateFlow<SettingsUiState> = combine(settings.settings, guard.locked, ::SettingsUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setPreFreeze(enabled: Boolean, minutes: Int) = run { actions.setPreFreeze(enabled, minutes) }
    fun setLimitWarnings(enabled: Boolean) = run { actions.setLimitWarnings(enabled) }
    fun setStatusNotification(enabled: Boolean) = run { actions.setStatusNotification(enabled) }
    fun setResetMinute(m: Int) = run { actions.setUsageResetMinute(m) }
    fun setProtectSettings(enabled: Boolean) = run { actions.setProtectSettingsScreens(enabled) }
    fun setEmergency(enabled: Boolean, wait: Int, phrase: String, perWeek: Int, minutes: Int, extra: Int) =
        run { actions.setEmergency(enabled, wait, phrase, perWeek, minutes, extra) }

    private fun run(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (_: StrictModeLockedException) {
            _blocked.emit(Unit)
        }
    }
}

data class WhitelistUiState(
    val core: List<Pair<String, Set<String>>> = emptyList(),
    val user: List<WhitelistEntity> = emptyList(),
    val apps: List<InstalledApp> = emptyList(),
    val locked: Boolean = false,
)

@HiltViewModel
class WhitelistViewModel @Inject constructor(
    private val resolver: WhitelistResolver,
    private val actions: PreferencesActions,
    dao: WhitelistDao,
    installed: InstalledAppsRepository,
    guard: StrictGuard,
) : ViewModel() {
    private val core = MutableStateFlow<List<Pair<String, Set<String>>>>(emptyList())
    private val _blocked = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val blocked: SharedFlow<Unit> = _blocked

    init {
        viewModelScope.launch { core.value = withContext(Dispatchers.IO) { resolver.describeCore() } }
    }

    val state: StateFlow<WhitelistUiState> = combine(core, dao.observe(), installed.blockableApps, guard.locked, ::WhitelistUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WhitelistUiState())

    fun add(packages: Set<String>) = viewModelScope.launch {
        try {
            actions.addToWhitelist(packages)
        } catch (_: StrictModeLockedException) {
            _blocked.emit(Unit)
        }
    }

    fun remove(pkg: String) = viewModelScope.launch { actions.removeFromWhitelist(pkg) }
}
