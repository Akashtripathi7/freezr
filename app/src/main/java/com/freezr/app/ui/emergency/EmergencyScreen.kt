package com.freezr.app.ui.emergency

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freezr.app.R
import com.freezr.app.data.repo.EmergencyRepository
import com.freezr.app.data.repo.GrantResult
import com.freezr.app.ui.components.AppIcon
import com.freezr.app.ui.components.Format
import com.freezr.app.ui.components.FrostCard
import com.freezr.app.ui.components.LocalAppCatalog
import com.freezr.app.ui.components.SectionHeader
import com.freezr.app.ui.theme.TabularNumbers
import java.time.Instant
import java.time.ZoneId

@Composable
fun EmergencyScreen(onBack: () -> Unit, onDone: () -> Unit, vm: EmergencyViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val catalog = LocalAppCatalog.current
    val context = LocalContext.current
    val pkg = state.packageName

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.emergency_unlock)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FrostCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (pkg != null) AppIcon(pkg, size = 48.dp) else Icon(Icons.Rounded.LockOpen, null, Modifier.size(40.dp))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                if (pkg != null) catalog.label(pkg) else stringResource(R.string.emergency_passes),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                pluralStringResource(R.plurals.passes_left_week, state.passesRemaining, state.passesRemaining, state.settings.emergencyPassesPerWeek),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (pkg != null) {
                item {
                    AnimatedContent(state.step, label = "step", contentKey = { it::class }) { step ->
                        StepCard(step, state, vm, onDone = {
                            context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it) }
                            onDone()
                        }, onBack = onBack)
                    }
                }
            } else {
                item {
                    Text(
                        stringResource(
                            R.string.emergency_info, state.settings.emergencyWaitSeconds, state.settings.emergencyPassMinutes,
                            state.settings.emergencyPassesPerWeek, state.settings.emergencyExtraLimitMinutes,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (state.log.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.recent_unlocks)) }
                items(state.log, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(e.packageName, size = 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(catalog.label(e.packageName), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                (if (e.kind == EmergencyRepository.KIND_PASS) stringResource(R.string.log_pass, e.minutes) else stringResource(R.string.log_extra, e.minutes)) +
                                    " · " + Format.dayTime(Instant.ofEpochMilli(e.timestamp), Instant.now(), ZoneId.systemDefault()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepCard(step: EmergencyStep, state: EmergencyUiState, vm: EmergencyViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    FrostCard {
        when (step) {
            EmergencyStep.Intro -> {
                Text(stringResource(R.string.emergency_intro_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.emergency_intro_body, state.settings.emergencyWaitSeconds), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                if (state.passesRemaining > 0) {
                    Button(onClick = vm::begin, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.start_unlock)) }
                } else {
                    Text(stringResource(R.string.no_passes_left), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stay_frozen)) }
            }
            is EmergencyStep.Waiting -> {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.HourglassTop, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.emergency_breathe), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text("${step.secondsLeft}", style = MaterialTheme.typography.displayLarge.merge(TabularNumbers))
                    OutlinedButton(onClick = vm::cancel) { Text(stringResource(R.string.cancel_unlock)) }
                }
            }
            EmergencyStep.Phrase -> {
                Text(stringResource(R.string.type_phrase), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("“${state.settings.emergencyPhrase}”", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.typed,
                    onValueChange = vm::type,
                    modifier = Modifier.fillMaxWidth(),
                    isError = state.typed.isNotEmpty() && !state.phraseOk,
                    label = { Text(stringResource(R.string.phrase)) },
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::confirm, enabled = state.phraseOk) {
                        Text(pluralStringResource(R.plurals.unlock_for_minutes, state.settings.emergencyPassMinutes, state.settings.emergencyPassMinutes))
                    }
                    TextButton(onClick = vm::cancel) { Text(stringResource(R.string.cancel)) }
                }
            }
            EmergencyStep.Granting -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is EmergencyStep.Done -> {
                val (title, ok) = when (val r = step.result) {
                    is GrantResult.Pass -> stringResource(R.string.unlocked_until, Format.time(r.expiresAt, ZoneId.systemDefault())) to true
                    is GrantResult.ExtraMinutes -> pluralStringResource(R.plurals.extra_minutes_added, r.minutes, r.minutes) to true
                    GrantResult.NoPassesLeft -> stringResource(R.string.no_passes_left) to false
                    GrantResult.NotFrozen -> stringResource(R.string.not_frozen_now) to true
                    GrantResult.NotAllowed -> stringResource(R.string.unlock_not_allowed) to false
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = if (ok) onDone else onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (ok) R.string.open_app else R.string.close))
                }
            }
        }
    }
}
