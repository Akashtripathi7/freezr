package com.freezr.app.ui.schedules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freezr.app.R
import com.freezr.app.domain.model.Days
import com.freezr.app.ui.components.AppIconStack
import com.freezr.app.ui.components.AppPickerDialog
import com.freezr.app.ui.components.DayChips
import com.freezr.app.ui.components.DayPresetChips
import com.freezr.app.ui.components.Format
import com.freezr.app.ui.components.FrostCard
import com.freezr.app.ui.components.SectionHeader
import com.freezr.app.ui.components.StrictLockedBanner
import com.freezr.app.ui.components.TimePickerDialog

private val TEMPLATES = listOf(
    ScheduleTemplate(R.string.template_sleep, 23 * 60, 7 * 60, Days.ALL),
    ScheduleTemplate(R.string.template_work, 9 * 60, 17 * 60, Days.WEEKDAYS),
    ScheduleTemplate(R.string.template_morning, 6 * 60, 9 * 60, Days.ALL),
    ScheduleTemplate(R.string.template_study, 18 * 60, 21 * 60, Days.WEEKDAYS),
)

@Composable
fun ScheduleEditScreen(onBack: () -> Unit, onOpenStrict: () -> Unit, vm: ScheduleEditViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val strictMsg = stringResource(R.string.strict_blocked_action)
    var pickTime by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    var pickApps by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect {
            when (it) {
                is ScheduleEvent.Saved -> onBack()
                ScheduleEvent.StrictBlocked -> snackbar.showSnackbar(strictMsg)
                ScheduleEvent.Deleted -> Unit
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.new_schedule else R.string.edit_schedule)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Button(
                onClick = vm::save,
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .height(56.dp),
            ) { Text(stringResource(R.string.save_schedule)) }
        },
    ) { padding ->
        if (!state.loaded) {
            Row(Modifier.padding(padding).fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val d = state.draft
        val editable = state.isNew || !state.strictLocked
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.isNew) StrictLockedBanner(state.strictLocked, onOpenStrict)

            // Live preview — the most important line on this screen.
            FrostCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AcUnit, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        Format.schedulePreview(d.copy(packages = d.packages.ifEmpty { setOf("_") }), now, vm.zone),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            if (state.isNew) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(TEMPLATES) { t ->
                        val name = stringResource(t.nameRes)
                        AssistChip(onClick = { vm.applyTemplate(t, name) }, label = { Text(name) })
                    }
                }
            }

            OutlinedTextField(
                value = d.name,
                onValueChange = { v -> vm.update { it.copy(name = v.take(40)) } },
                label = { Text(stringResource(R.string.schedule_name)) },
                placeholder = { Text(stringResource(R.string.schedule_name_hint)) },
                singleLine = true,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            )

            SectionHeader(stringResource(R.string.when_label))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeTile(stringResource(R.string.starts), Format.minuteOfDay(d.startMinute), editable, Modifier.weight(1f)) { pickTime = true }
                TimeTile(stringResource(R.string.ends), Format.minuteOfDay(d.endMinute), editable, Modifier.weight(1f)) { pickTime = false }
            }
            if (d.endMinute <= d.startMinute) {
                Text(
                    stringResource(if (d.endMinute == d.startMinute) R.string.all_day_hint else R.string.overnight_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader(stringResource(R.string.repeat_on))
            DayChips(d.daysMask, { m -> vm.update { it.copy(daysMask = m) } }, enabled = editable)
            DayPresetChips(d.daysMask, { m -> vm.update { it.copy(daysMask = m) } }, enabled = editable)

            SectionHeader(stringResource(R.string.apps_to_freeze))
            FrostCard(onClick = if (editable) ({ pickApps = true }) else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (d.packages.isEmpty()) stringResource(R.string.choose_apps) else pluralStringResource(R.plurals.apps_count, d.packages.size, d.packages.size),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (d.packages.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            AppIconStack(d.packages, max = 6)
                        } else {
                            Text(stringResource(R.string.choose_apps_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pickTime?.let { start ->
        TimePickerDialog(
            initialMinute = if (start) state.draft.startMinute else state.draft.endMinute,
            title = stringResource(if (start) R.string.starts else R.string.ends),
            onDismiss = { pickTime = null },
            onConfirm = { m ->
                vm.update { if (start) it.copy(startMinute = m) else it.copy(endMinute = m) }
                pickTime = null
            },
        )
    }
    if (pickApps) {
        AppPickerDialog(
            title = stringResource(R.string.apps_to_freeze),
            apps = state.apps,
            initial = state.draft.packages,
            groups = state.groups,
            myApps = state.myApps,
            onDismiss = { pickApps = false },
            onConfirm = { set -> vm.update { it.copy(packages = set) }; pickApps = false },
        )
    }
}

@Composable
private fun TimeTile(label: String, value: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FrostCard(onClick = if (enabled) onClick else null, modifier = modifier, contentPadding = PaddingValues(16.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall)
    }
}
