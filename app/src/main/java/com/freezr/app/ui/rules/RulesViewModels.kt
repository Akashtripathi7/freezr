package com.freezr.app.ui.rules

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freezr.app.data.repo.AppGroup
import com.freezr.app.data.repo.AppSelectionRepository
import com.freezr.app.data.repo.ContextRuleDraft
import com.freezr.app.data.repo.ContextRuleRepository
import com.freezr.app.data.repo.DomainRepository
import com.freezr.app.data.repo.StrictGuard
import com.freezr.app.data.repo.StrictModeLockedException
import com.freezr.app.data.settings.AppSettings
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.data.settings.SurfaceBlockMode
import com.freezr.app.domain.model.ContextRuleType
import com.freezr.app.platform.apps.InstalledApp
import com.freezr.app.platform.apps.InstalledAppsRepository
import com.freezr.app.platform.location.GeofenceManager
import com.freezr.app.platform.location.WifiMonitor
import com.freezr.app.platform.rules.InAppRule
import com.freezr.app.platform.rules.SelectorRulesRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

data class RuleListItem(val draft: ContextRuleDraft, val active: Boolean)

data class ContextRulesState(
    val loading: Boolean = true,
    val items: List<RuleListItem> = emptyList(),
    val locked: Boolean = false,
    val hasLocation: Boolean = true,
)

@HiltViewModel
class ContextRulesViewModel @Inject constructor(
    private val repo: ContextRuleRepository,
    private val geofences: GeofenceManager,
    guard: StrictGuard,
) : ViewModel() {
    private val _blocked = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val blocked: SharedFlow<Unit> = _blocked
    private val hasLocation = MutableStateFlow(geofences.hasPermissions())

    val state: StateFlow<ContextRulesState> = combine(repo.observeDrafts(), guard.locked, hasLocation) { list, locked, loc ->
        ContextRulesState(false, list.map { RuleListItem(it.first, it.second) }, locked, loc)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContextRulesState())

    fun refreshPermissions() { hasLocation.value = geofences.hasPermissions() }

    fun setEnabled(id: Long, enabled: Boolean) = guarded {
        repo.setEnabled(id, enabled)
        geofences.sync()
    }

    fun delete(id: Long) = guarded {
        repo.delete(id)
        geofences.sync()
    }

    private fun guarded(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (_: StrictModeLockedException) { _blocked.emit(Unit) }
    }
}

data class RuleEditState(
    val loaded: Boolean = false,
    val draft: ContextRuleDraft = ContextRuleDraft(),
    val apps: List<InstalledApp> = emptyList(),
    val groups: List<AppGroup> = emptyList(),
    val myApps: Set<String> = emptySet(),
    val locked: Boolean = false,
    val locating: Boolean = false,
    val currentSsid: String? = null,
)

sealed interface RuleEditEvent {
    data object Saved : RuleEditEvent
    data object Blocked : RuleEditEvent
    data object LocationFailed : RuleEditEvent
}

@HiltViewModel
class ContextRuleEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ContextRuleRepository,
    private val geofences: GeofenceManager,
    private val wifi: WifiMonitor,
    @ApplicationContext private val context: Context,
    installed: InstalledAppsRepository,
    selection: AppSelectionRepository,
    guard: StrictGuard,
) : ViewModel() {
    private val id: Long = savedState.get<Long>("id") ?: 0L
    private val draft = MutableStateFlow<ContextRuleDraft?>(null)
    private val locating = MutableStateFlow(false)
    private val ssid = MutableStateFlow<String?>(null)
    private val _events = MutableSharedFlow<RuleEditEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<RuleEditEvent> = _events

    init {
        viewModelScope.launch {
            draft.value = (if (id > 0) repo.draft(id) else null) ?: ContextRuleDraft(packages = selection.selectedNow())
        }
    }

    private data class Lists(val apps: List<InstalledApp>, val groups: List<AppGroup>, val mine: Set<String>, val locked: Boolean)
    private val lists = combine(installed.blockableApps, selection.groups, selection.selected, guard.locked, ::Lists)

    val state: StateFlow<RuleEditState> = combine(draft, lists, locating, ssid) { d, l, loc, s ->
        if (d == null) RuleEditState() else RuleEditState(true, d, l.apps, l.groups, l.mine, l.locked, loc, s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RuleEditState())

    fun update(block: (ContextRuleDraft) -> ContextRuleDraft) = draft.update { it?.let(block) }

    @SuppressLint("MissingPermission") // only called after the permission launcher granted it
    fun useCurrentLocation() = viewModelScope.launch {
        locating.value = true
        try {
            val loc = LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
            if (loc != null) update { it.copy(latitude = loc.latitude, longitude = loc.longitude) } else _events.emit(RuleEditEvent.LocationFailed)
        } catch (_: Exception) {
            _events.emit(RuleEditEvent.LocationFailed)
        } finally {
            locating.value = false
        }
    }

    @Suppress("DEPRECATION")
    fun readCurrentSsid() {
        val raw = runCatching { context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid }.getOrNull()
        val clean = raw?.takeIf { it != WifiManager.UNKNOWN_SSID }?.removeSurrounding("\"")
        ssid.value = clean
        if (clean != null) update { it.copy(ssid = clean) }
    }

    fun save() = viewModelScope.launch {
        val d = draft.value ?: return@launch
        try {
            repo.save(d)
            geofences.sync()
            wifi.start()
            _events.emit(RuleEditEvent.Saved)
        } catch (_: StrictModeLockedException) {
            _events.emit(RuleEditEvent.Blocked)
        }
    }

    val isNew: Boolean get() = id <= 0
    fun setType(t: ContextRuleType) = update { it.copy(type = t) }
}

data class WebState(
    val domains: List<String> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val inAppRules: List<InAppRule> = emptyList(),
    val browsers: List<String> = emptyList(),
    val locked: Boolean = false,
)

sealed interface WebEvent {
    data object Blocked : WebEvent
    data object Invalid : WebEvent
}

@HiltViewModel
class WebBlockingViewModel @Inject constructor(
    private val domains: DomainRepository,
    private val settings: SettingsRepository,
    private val guard: StrictGuard,
    rules: SelectorRulesRepository,
) : ViewModel() {
    private val _events = MutableSharedFlow<WebEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<WebEvent> = _events
    private val selectors = rules.rules

    val state: StateFlow<WebState> = combine(domains.domains, settings.settings, guard.locked) { d, s, l ->
        WebState(d, s, selectors.inAppRules, selectors.browsers.map { it.name }, l)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebState())

    fun add(input: String) = viewModelScope.launch { if (domains.add(input) == null) _events.emit(WebEvent.Invalid) }
    fun remove(domain: String) = guarded { domains.remove(domain) }

    fun setWebMode(mode: SurfaceBlockMode) = guarded {
        if (loosens(state.value.settings.webBlockMode, mode)) guard.requireUnlocked()
        settings.update { webBlockMode = mode }
    }

    fun setInAppMode(mode: SurfaceBlockMode) = guarded {
        if (loosens(state.value.settings.inAppBlockMode, mode)) guard.requireUnlocked()
        settings.update { inAppBlockMode = mode }
    }

    fun toggleInApp(id: String, on: Boolean) = guarded {
        if (!on) guard.requireUnlocked()
        settings.update { enabledInAppRules = if (on) enabledInAppRules + id else enabledInAppRules - id }
    }

    private fun loosens(from: SurfaceBlockMode, to: SurfaceBlockMode): Boolean = strength(to) < strength(from)
    private fun strength(m: SurfaceBlockMode) = when (m) {
        SurfaceBlockMode.OFF -> 0
        SurfaceBlockMode.DURING_ANY_FREEZE -> 1
        SurfaceBlockMode.ALWAYS -> 2
    }

    private fun guarded(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (_: StrictModeLockedException) { _events.emit(WebEvent.Blocked) }
    }
}

data class SelectorHealthState(
    val version: Int = 0,
    val updated: String = "",
    val source: String = "",
    val error: String? = null,
    val overridePath: String = "",
    val rows: List<SelectorRow> = emptyList(),
)

data class SelectorRow(val id: String, val title: String, val target: String, val installed: Boolean, val checks: Long, val matches: Long, val lastMatch: java.time.Instant?)

@HiltViewModel
class SelectorHealthViewModel @Inject constructor(
    private val rules: SelectorRulesRepository,
    private val installed: InstalledAppsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(build())
    val state: StateFlow<SelectorHealthState> = _state

    fun reload() {
        rules.reload()
        _state.value = build()
    }

    fun refresh() { _state.value = build() }

    private fun build(): SelectorHealthState {
        val r = rules.rules
        fun row(id: String, title: String, pkg: String): SelectorRow {
            val s = rules.stats[id]
            return SelectorRow(id, title, pkg, installed.isInstalled(pkg), s?.checks ?: 0, s?.matches ?: 0, s?.lastMatchAt)
        }
        return SelectorHealthState(
            version = r.version,
            updated = r.updated,
            source = rules.source.name.lowercase(),
            error = rules.loadError,
            overridePath = rules.overrideFile.absolutePath,
            rows = r.browsers.map { row("browser:${it.packageName}", it.name, it.packageName) } +
                r.inAppRules.map { row(it.id, it.title, it.packageName) } +
                row("settings_guard", "Settings protection", r.settingsGuard.packages.firstOrNull() ?: "com.android.settings"),
        )
    }
}
