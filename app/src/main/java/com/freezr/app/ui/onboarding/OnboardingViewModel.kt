package com.freezr.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freezr.app.data.repo.AppSelectionRepository
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.domain.time.TimeSource
import com.freezr.app.platform.EnforcementCoordinator
import com.freezr.app.platform.apps.InstalledApp
import com.freezr.app.platform.apps.InstalledAppsRepository
import com.freezr.app.platform.health.HealthItem
import com.freezr.app.platform.health.HealthReport
import com.freezr.app.platform.health.PermissionHealthChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep {
    WELCOME, PRIVACY, ACCESSIBILITY, USAGE, NOTIFICATIONS, ALARMS, BATTERY, OPTIONAL, APPS;

    /** The health item this step grants, if any. */
    val item: HealthItem?
        get() = when (this) {
            ACCESSIBILITY -> HealthItem.ACCESSIBILITY
            USAGE -> HealthItem.USAGE_ACCESS
            NOTIFICATIONS -> HealthItem.NOTIFICATIONS
            ALARMS -> HealthItem.EXACT_ALARMS
            BATTERY -> HealthItem.BATTERY
            else -> null
        }
}

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val health: HealthReport? = null,
    val apps: List<InstalledApp> = emptyList(),
    val selected: Set<String> = emptySet(),
) {
    fun granted(item: HealthItem?): Boolean = item != null && health?.checks?.firstOrNull { it.item == item }?.ok == true
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val health: PermissionHealthChecker,
    private val settings: SettingsRepository,
    private val selection: AppSelectionRepository,
    private val coordinator: EnforcementCoordinator,
    private val time: TimeSource,
    installed: InstalledAppsRepository,
) : ViewModel() {
    private val step = MutableStateFlow(OnboardingStep.WELCOME)
    private val report = MutableStateFlow<HealthReport?>(null)

    val state: StateFlow<OnboardingState> = combine(step, report, installed.blockableApps, selection.selected) { s, h, apps, sel ->
        OnboardingState(s, h, apps, sel)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingState())

    fun refresh() = viewModelScope.launch { report.value = health.check() }

    fun next() {
        val all = OnboardingStep.entries
        step.value = all[(step.value.ordinal + 1).coerceAtMost(all.lastIndex)]
    }

    fun back() {
        step.value = OnboardingStep.entries[(step.value.ordinal - 1).coerceAtLeast(0)]
    }

    fun setSelected(packages: Set<String>) = viewModelScope.launch {
        val current = state.value.selected
        selection.setSelected(current - packages, false)
        selection.setSelected(packages - current, true)
    }

    fun finish(onDone: () -> Unit) = viewModelScope.launch {
        settings.update {
            onboardingComplete = true
            if (installedAt == null) installedAt = time.now()
        }
        coordinator.reconcileAll("onboarding_finished")
        onDone()
    }
}
