package com.freezr.app.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freezr.app.data.repo.AppGroup
import com.freezr.app.data.repo.AppSelectionRepository
import com.freezr.app.data.repo.LimitRepository
import com.freezr.app.data.repo.StrictGuard
import com.freezr.app.data.repo.StrictModeLockedException
import com.freezr.app.domain.model.UsageLimitRule
import com.freezr.app.platform.apps.InstalledApp
import com.freezr.app.platform.apps.InstalledAppsRepository
import com.freezr.app.platform.usage.UsageTracker
import com.freezr.app.ui.components.search
import com.freezr.app.domain.time.TimeSource
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
import javax.inject.Inject

enum class AppFilter { ALL, SELECTED, LIMITED }

data class AppRow(
    val app: InstalledApp,
    val selected: Boolean,
    val limit: UsageLimitRule?,
    val usedToday: Duration,
)

data class AppsUiState(
    val loading: Boolean = true,
    val rows: List<AppRow> = emptyList(),
    val totalApps: Int = 0,
    val selectedCount: Int = 0,
    val query: String = "",
    val filter: AppFilter = AppFilter.ALL,
    val groups: List<AppGroup> = emptyList(),
    val allApps: List<InstalledApp> = emptyList(),
    val selected: Set<String> = emptySet(),
    val strictLocked: Boolean = false,
)

@HiltViewModel
class AppsViewModel @Inject constructor(
    installed: InstalledAppsRepository,
    private val selection: AppSelectionRepository,
    private val limits: LimitRepository,
    private val usage: UsageTracker,
    private val time: TimeSource,
    guard: StrictGuard,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(AppFilter.ALL)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private data class Inputs(
        val apps: List<InstalledApp>,
        val selected: Set<String>,
        val limits: Map<String, UsageLimitRule>,
        val groups: List<AppGroup>,
        val locked: Boolean,
    )

    private val inputs = combine(installed.blockableApps, selection.selected, limits.limits, selection.groups, guard.locked, ::Inputs)

    val state: StateFlow<AppsUiState> = combine(inputs, query, filter, usage.usageToday) { i, q, f, _ ->
        val live = usage.liveUsage(time.now())
        val rows = i.apps.search(q)
            .map { AppRow(it, it.packageName in i.selected, i.limits[it.packageName], live[it.packageName] ?: Duration.ZERO) }
            .filter {
                when (f) {
                    AppFilter.ALL -> true
                    AppFilter.SELECTED -> it.selected
                    AppFilter.LIMITED -> it.limit != null
                }
            }
        AppsUiState(
            loading = i.apps.isEmpty() && q.isEmpty(),
            rows = rows,
            totalApps = i.apps.size,
            selectedCount = i.selected.size,
            query = q,
            filter = f,
            groups = i.groups,
            allApps = i.apps,
            selected = i.selected,
            strictLocked = i.locked,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppsUiState())

    fun setQuery(q: String) { query.value = q }
    fun setFilter(f: AppFilter) { filter.value = f }

    fun toggle(pkg: String, selected: Boolean) = viewModelScope.launch { selection.setSelected(listOf(pkg), selected) }

    fun selectAllVisible(selected: Boolean) = viewModelScope.launch {
        selection.setSelected(state.value.rows.map { it.app.packageName }, selected)
    }

    fun saveGroup(group: AppGroup) = viewModelScope.launch { selection.saveGroup(group) }
    fun deleteGroup(id: Long) = viewModelScope.launch { selection.deleteGroup(id) }

    fun setLimit(pkg: String, minutes: Int) = guarded { limits.setLimit(pkg, minutes) }
    fun removeLimit(pkg: String) = guarded { limits.removeLimit(pkg) }

    private fun guarded(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (_: StrictModeLockedException) {
            _messages.emit(STRICT_MESSAGE)
        }
    }

    companion object {
        const val STRICT_MESSAGE = "strict"
    }
}
