package com.freezr.app.ui.schedules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freezr.app.data.repo.AppGroup
import com.freezr.app.data.repo.AppSelectionRepository
import com.freezr.app.data.repo.ScheduleRepository
import com.freezr.app.data.repo.StrictGuard
import com.freezr.app.data.repo.StrictModeLockedException
import com.freezr.app.domain.engine.TimeWindows
import com.freezr.app.domain.model.Days
import com.freezr.app.domain.model.ScheduleRule
import com.freezr.app.domain.time.TimeSource
import com.freezr.app.platform.apps.InstalledApp
import com.freezr.app.platform.apps.InstalledAppsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class ScheduleItem(val rule: ScheduleRule, val activeNow: Boolean)

data class ScheduleListState(
    val loading: Boolean = true,
    val items: List<ScheduleItem> = emptyList(),
    val strictLocked: Boolean = false,
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneId.systemDefault(),
)

sealed interface ScheduleEvent {
    data object StrictBlocked : ScheduleEvent
    data class Saved(val id: Long) : ScheduleEvent
    data object Deleted : ScheduleEvent
}

/** Emits the current time every [periodMs]; used only for display, never for blocking decisions. */
fun TimeSource.ticker(periodMs: Long) = flow {
    while (true) {
        emit(now())
        delay(periodMs)
    }
}

@HiltViewModel
class ScheduleListViewModel @Inject constructor(
    private val repo: ScheduleRepository,
    private val time: TimeSource,
    guard: StrictGuard,
) : ViewModel() {
    private val _events = MutableSharedFlow<ScheduleEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ScheduleEvent> = _events

    val state: StateFlow<ScheduleListState> = combine(repo.schedules, guard.locked, time.ticker(30_000)) { list, locked, now ->
        val zone = time.zone()
        ScheduleListState(
            loading = false,
            items = list.map { r ->
                ScheduleItem(r, r.enabled && TimeWindows.activeWindow(r.startMinute, r.endMinute, r.daysMask, now, zone) != null)
            },
            strictLocked = locked,
            now = now,
            zone = zone,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleListState())

    fun setEnabled(id: Long, enabled: Boolean) = guarded { repo.setEnabled(id, enabled) }
    fun duplicate(id: Long) = guarded { repo.duplicate(id) }
    fun delete(id: Long) = guarded {
        repo.delete(id)
        _events.emit(ScheduleEvent.Deleted)
    }

    private fun guarded(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (_: StrictModeLockedException) {
            _events.emit(ScheduleEvent.StrictBlocked)
        }
    }
}

data class ScheduleTemplate(val nameRes: Int, val start: Int, val end: Int, val days: Int)

data class ScheduleEditState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val draft: ScheduleRule = ScheduleRule(0, "", true, 23 * 60, 7 * 60, Days.ALL, emptySet()),
    val apps: List<InstalledApp> = emptyList(),
    val groups: List<AppGroup> = emptyList(),
    val myApps: Set<String> = emptySet(),
    val strictLocked: Boolean = false,
    val saving: Boolean = false,
) {
    val canSave: Boolean get() = draft.daysMask != Days.NONE && draft.packages.isNotEmpty() && !saving &&
        (isNew || !strictLocked)
}

@HiltViewModel
class ScheduleEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ScheduleRepository,
    installed: InstalledAppsRepository,
    selection: AppSelectionRepository,
    guard: StrictGuard,
    private val time: TimeSource,
) : ViewModel() {
    private val id: Long = savedState.get<Long>("id") ?: 0L
    private val draft = MutableStateFlow<ScheduleRule?>(null)
    private val saving = MutableStateFlow(false)
    private val _events = MutableSharedFlow<ScheduleEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ScheduleEvent> = _events

    private val _now = MutableStateFlow(time.now())
    val now = _now.asStateFlow()
    val zone: ZoneId get() = time.zone()

    init {
        viewModelScope.launch {
            val existing = if (id > 0) repo.get(id) else null
            draft.value = existing ?: ScheduleRule(0, "", true, 23 * 60, 7 * 60, Days.ALL, selection.selectedNow())
        }
        viewModelScope.launch { time.ticker(30_000).collect { _now.value = it } }
    }

    private data class Lists(val apps: List<InstalledApp>, val groups: List<AppGroup>, val mine: Set<String>, val locked: Boolean)

    private val lists = combine(installed.blockableApps, selection.groups, selection.selected, guard.locked, ::Lists)

    val state: StateFlow<ScheduleEditState> = combine(draft, lists, saving) { d, l, s ->
        if (d == null) {
            ScheduleEditState()
        } else {
            ScheduleEditState(true, id <= 0, d, l.apps, l.groups, l.mine, l.locked, s)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleEditState())

    fun update(block: (ScheduleRule) -> ScheduleRule) = draft.update { it?.let(block) }

    /** Name last filled in by a template; a name the user typed themselves is never overwritten. */
    private var templateName: String? = null

    fun applyTemplate(t: ScheduleTemplate, name: String) = update {
        val keepName = it.name.isNotBlank() && it.name != templateName
        templateName = name
        it.copy(name = if (keepName) it.name else name, startMinute = t.start, endMinute = t.end, daysMask = t.days)
    }

    fun save() {
        val d = draft.value ?: return
        viewModelScope.launch {
            saving.value = true
            try {
                val newId = repo.save(d)
                _events.emit(ScheduleEvent.Saved(newId))
            } catch (_: StrictModeLockedException) {
                _events.emit(ScheduleEvent.StrictBlocked)
            } finally {
                saving.value = false
            }
        }
    }
}
