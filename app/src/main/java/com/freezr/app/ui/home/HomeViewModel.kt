package com.freezr.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freezr.app.data.repo.AppGroup
import com.freezr.app.data.repo.AppSelectionRepository
import com.freezr.app.data.repo.FreezeControls
import com.freezr.app.data.repo.FreezeStateRepository
import com.freezr.app.data.repo.NothingToFreezeException
import com.freezr.app.data.repo.StrictGuard
import com.freezr.app.data.repo.StrictModeLockedException
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.domain.engine.FreezeDecisionEngine
import com.freezr.app.domain.model.ActiveFreeze
import com.freezr.app.domain.model.QuickFreezeState
import com.freezr.app.domain.time.TimeSource
import com.freezr.app.platform.health.HealthReport
import com.freezr.app.platform.health.PermissionHealthChecker
import com.freezr.app.ui.schedules.ticker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class HomeState(
    val loading: Boolean = true,
    val masterEnabled: Boolean = true,
    val strictEnabled: Boolean = false,
    val strictLocked: Boolean = false,
    val active: List<ActiveFreeze> = emptyList(),
    val quickFreeze: QuickFreezeState? = null,
    val nextTransition: Instant? = null,
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneId.systemDefault(),
    val myApps: Set<String> = emptySet(),
    val groups: List<AppGroup> = emptyList(),
    val scheduleCount: Int = 0,
    val limitCount: Int = 0,
    val passesRemaining: Int = 0,
    val health: HealthReport? = null,
) {
    val frozenPackages: Set<String> get() = active.flatMap { it.packages }.toSet()
    val quickRemaining: Duration? get() = quickFreeze?.takeIf { now.isBefore(it.endsAt) }?.let { Duration.between(now, it.endsAt) }
    val hasRules: Boolean get() = scheduleCount > 0 || limitCount > 0 || myApps.isNotEmpty()
}

sealed interface HomeMessage {
    data object StrictBlocked : HomeMessage
    data object NothingSelected : HomeMessage
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    stateRepo: FreezeStateRepository,
    settings: SettingsRepository,
    selection: AppSelectionRepository,
    guard: StrictGuard,
    private val controls: FreezeControls,
    private val health: PermissionHealthChecker,
    private val time: TimeSource,
) : ViewModel() {

    private val _messages = MutableSharedFlow<HomeMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<HomeMessage> = _messages
    private val healthReport = MutableStateFlow<HealthReport?>(null)

    private data class Base(
        val ctx: com.freezr.app.domain.model.EngineContext,
        val strict: Boolean,
        val locked: Boolean,
        val mine: Set<String>,
        val groups: List<AppGroup>,
    )

    private val base = combine(
        stateRepo.snapshot.filterNotNull(),
        settings.settings,
        guard.locked,
        selection.selected,
        selection.groups,
    ) { ctx, s, locked, mine, groups -> Base(ctx, s.strictEnabled, locked, mine, groups) }

    val state: StateFlow<HomeState> = combine(base, time.ticker(1_000), healthReport) { b, now, h ->
        val zone = time.zone()
        HomeState(
            loading = false,
            masterEnabled = b.ctx.masterEnabled,
            strictEnabled = b.strict,
            strictLocked = b.locked,
            active = FreezeDecisionEngine.activeFreezes(now, zone, b.ctx),
            quickFreeze = b.ctx.quickFreeze,
            nextTransition = FreezeDecisionEngine.nextTransitionAfter(now, zone, b.ctx),
            now = now,
            zone = zone,
            myApps = b.mine,
            groups = b.groups,
            scheduleCount = b.ctx.schedules.count { it.enabled },
            limitCount = b.ctx.limits.count { it.enabled },
            passesRemaining = b.ctx.emergencyPassesRemaining,
            health = h,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    /** Called from onResume: every permission / service state is re-checked each time. */
    fun refreshHealth() = viewModelScope.launch {
        healthReport.value = health.check()
        health.alertIfBroken(healthReport.value!!)
    }

    fun setMaster(enabled: Boolean) = guarded { controls.setMasterEnabled(enabled) }

    fun startFocus(minutes: Int, packages: Set<String>?) = guarded {
        controls.startQuickFreeze(Duration.ofMinutes(minutes.toLong()), packages)
    }

    fun cancelFocus() = guarded { controls.cancelQuickFreeze() }

    private fun guarded(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (_: StrictModeLockedException) {
            _messages.emit(HomeMessage.StrictBlocked)
        } catch (_: NothingToFreezeException) {
            _messages.emit(HomeMessage.NothingSelected)
        }
    }
}
