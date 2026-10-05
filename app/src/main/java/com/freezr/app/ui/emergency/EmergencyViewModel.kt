package com.freezr.app.ui.emergency

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freezr.app.data.db.EmergencyLogEntity
import com.freezr.app.data.repo.EmergencyRepository
import com.freezr.app.data.repo.GrantResult
import com.freezr.app.data.settings.AppSettings
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.domain.strict.EmergencyPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Friction chain: forced wait (cancellable) -> type the phrase -> grant. */
sealed interface EmergencyStep {
    data object Intro : EmergencyStep
    data class Waiting(val secondsLeft: Int) : EmergencyStep
    data object Phrase : EmergencyStep
    data object Granting : EmergencyStep
    data class Done(val result: GrantResult) : EmergencyStep
}

data class EmergencyUiState(
    val packageName: String? = null,
    val step: EmergencyStep = EmergencyStep.Intro,
    val settings: AppSettings = AppSettings(),
    val passesRemaining: Int = 0,
    val log: List<EmergencyLogEntity> = emptyList(),
    val typed: String = "",
) {
    val phraseOk: Boolean get() = EmergencyPolicy.phraseMatches(settings.emergencyPhrase, typed)
}

@HiltViewModel
class EmergencyViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: EmergencyRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val pkg: String? = savedState.get<String>("pkg")
    private val step = MutableStateFlow<EmergencyStep>(EmergencyStep.Intro)
    private val typed = MutableStateFlow("")
    private val passes = MutableStateFlow(0)
    private var waitJob: Job? = null

    init {
        viewModelScope.launch { passes.value = repo.passesRemaining() }
    }

    val state: StateFlow<EmergencyUiState> = combine(step, settings.settings, passes, repo.log(), typed) { st, s, p, log, t ->
        EmergencyUiState(pkg, st, s, p, log, t)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EmergencyUiState(packageName = pkg))

    fun begin() {
        val seconds = state.value.settings.emergencyWaitSeconds
        waitJob?.cancel()
        waitJob = viewModelScope.launch {
            for (left in seconds downTo 1) {
                step.value = EmergencyStep.Waiting(left)
                delay(1_000)
            }
            step.value = EmergencyStep.Phrase
        }
    }

    fun cancel() {
        waitJob?.cancel()
        typed.value = ""
        step.value = EmergencyStep.Intro
    }

    fun type(text: String) = typed.update { text.take(200) }

    fun confirm() {
        val p = pkg ?: return
        if (!state.value.phraseOk) return
        viewModelScope.launch {
            step.value = EmergencyStep.Granting
            val result = repo.grant(p)
            passes.value = repo.passesRemaining()
            step.value = EmergencyStep.Done(result)
        }
    }
}
