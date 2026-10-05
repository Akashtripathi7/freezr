package com.freezr.app.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freezr.app.R
import com.freezr.app.data.repo.AppGroup
import com.freezr.app.ui.components.AppIcon
import com.freezr.app.ui.components.AppPickerDialog
import com.freezr.app.ui.components.ConfirmDialog
import com.freezr.app.ui.components.EmptyState
import com.freezr.app.ui.components.Format
import com.freezr.app.ui.theme.FreezrThemeExtras
import kotlin.math.roundToInt

@Composable
fun AppsScreen(onOpenStrict: () -> Unit, vm: AppsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val strictMsg = stringResource(R.string.strict_blocked_action)
    var limitFor by remember { mutableStateOf<AppRow?>(null) }
    var editingGroup by remember { mutableStateOf<AppGroup?>(null) }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(strictMsg) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.tab_apps))
                        Text(
                            stringResource(R.string.apps_subtitle, state.selectedCount, state.totalApps),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.apps_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.search_apps)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { FilterChip(state.filter == AppFilter.ALL, { vm.setFilter(AppFilter.ALL) }, { Text(stringResource(R.string.filter_all)) }) }
                    item { FilterChip(state.filter == AppFilter.SELECTED, { vm.setFilter(AppFilter.SELECTED) }, { Text(stringResource(R.string.my_apps)) }) }
                    item { FilterChip(state.filter == AppFilter.LIMITED, { vm.setFilter(AppFilter.LIMITED) }, { Text(stringResource(R.string.filter_limited)) }) }
                }
            }
            item {
                Text(
                    stringResource(R.string.groups),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.groups, key = { it.id }) { g ->
                        AssistChip(
                            onClick = { editingGroup = g },
                            label = { Text(stringResource(R.string.group_chip, g.name, g.packages.size)) },
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { editingGroup = AppGroup(0, "", emptySet()) },
                            label = { Text(stringResource(R.string.new_group)) },
                            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        )
                    }
                }
            }
            when {
                state.loading -> item {
                    Row(Modifier.fillMaxWidth().padding(48.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                }
                state.rows.isEmpty() -> item {
                    EmptyState(Icons.Rounded.Apps, stringResource(R.string.no_apps_title), stringResource(R.string.no_apps_body))
                }
                else -> items(state.rows, key = { it.app.packageName }) { row ->
                    AppListRow(row, onToggle = { vm.toggle(row.app.packageName, it) }, onLimit = { limitFor = row })
                }
            }
        }
    }

    limitFor?.let { row ->
        LimitDialog(
            appLabel = row.app.label,
            current = row.limit?.limitMinutes,
            onDismiss = { limitFor = null },
            onSave = { vm.setLimit(row.app.packageName, it); limitFor = null },
            onRemove = { vm.removeLimit(row.app.packageName); limitFor = null },
        )
    }
    editingGroup?.let { g ->
        GroupEditor(
            group = g,
            state = state,
            onDismiss = { editingGroup = null },
            onSave = { vm.saveGroup(it); editingGroup = null },
            onDelete = { vm.deleteGroup(g.id); editingGroup = null },
        )
    }
}

@Composable
private fun AppListRow(row: AppRow, onToggle: (Boolean) -> Unit, onLimit: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(row.selected, role = Role.Checkbox, onValueChange = onToggle)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(row.app.packageName, size = 44.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(row.app.label, style = MaterialTheme.typography.bodyLarge)
            val limit = row.limit
            if (limit != null) {
                val fraction = (row.usedToday.toMinutes().toFloat() / limit.limitMinutes).coerceIn(0f, 1f)
                Text(
                    stringResource(R.string.limit_row, Format.duration(row.usedToday), limit.limitMinutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(0.8f),
                    color = if (fraction >= 0.8f) FreezrThemeExtras.current.warning else MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(onClick = onLimit) {
            Icon(
                Icons.Rounded.Timer,
                contentDescription = stringResource(R.string.cd_set_limit, row.app.label),
                tint = if (row.limit != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = row.selected, onCheckedChange = null)
    }
}

@Composable
fun LimitDialog(appLabel: String, current: Int?, onDismiss: () -> Unit, onSave: (Int) -> Unit, onRemove: () -> Unit) {
    var minutes by remember { mutableFloatStateOf((current ?: 30).toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.limit_dialog_title, appLabel)) },
        text = {
            Column {
                Text(
                    pluralStringResource(R.plurals.minutes_per_day, minutes.roundToInt(), minutes.roundToInt()),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Slider(value = minutes, onValueChange = { minutes = (it / 5).roundToInt() * 5f }, valueRange = 5f..240f, steps = 46)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 90).forEach { m ->
                        FilterChip(minutes.roundToInt() == m, { minutes = m.toFloat() }, { Text("${m}m") })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.limit_dialog_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(minutes.roundToInt()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = onRemove) { Text(stringResource(R.string.remove_limit), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun GroupEditor(group: AppGroup, state: AppsUiState, onDismiss: () -> Unit, onSave: (AppGroup) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(group.name) }
    var packages by remember { mutableStateOf(group.packages) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (group.id == 0L) R.string.new_group else R.string.edit_group)) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.group_name)) }, singleLine = true)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { picking = true }) {
                    Text(pluralStringResource(R.plurals.choose_apps_count, packages.size, packages.size))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(group.copy(name = name, packages = packages)) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                if (group.id != 0L) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
    if (picking) {
        AppPickerDialog(
            title = stringResource(R.string.choose_apps),
            apps = state.allApps,
            initial = packages,
            groups = emptyList(),
            myApps = state.selected,
            onDismiss = { picking = false },
            onConfirm = { packages = it; picking = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_group_title),
            body = stringResource(R.string.delete_group_body, group.name),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
