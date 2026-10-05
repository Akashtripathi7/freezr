package com.freezr.app.ui.schedules

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freezr.app.R
import com.freezr.app.ui.components.AppIconStack
import com.freezr.app.ui.components.ConfirmDialog
import com.freezr.app.ui.components.EmptyState
import com.freezr.app.ui.components.Format
import com.freezr.app.ui.components.FrostCard
import com.freezr.app.ui.components.IconBadge
import com.freezr.app.ui.components.Pill
import com.freezr.app.ui.components.StrictLockedBanner

@Composable
fun SchedulesScreen(
    onCreate: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenStrict: () -> Unit,
    vm: ScheduleListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val strictMsg = stringResource(R.string.strict_blocked_action)
    val deletedMsg = stringResource(R.string.schedule_deleted)
    var confirmDelete by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect {
            when (it) {
                ScheduleEvent.StrictBlocked -> snackbar.showSnackbar(strictMsg)
                ScheduleEvent.Deleted -> snackbar.showSnackbar(deletedMsg)
                is ScheduleEvent.Saved -> Unit
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_schedules)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.new_schedule)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!state.loading && state.items.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Rounded.Bedtime, stringResource(R.string.no_schedules_title), stringResource(R.string.no_schedules_body))
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StrictLockedBanner(state.strictLocked, onOpenStrict) }
            items(state.items, key = { it.rule.id }) { item ->
                ScheduleCard(
                    item = item,
                    preview = Format.schedulePreview(item.rule, state.now, state.zone),
                    locked = state.strictLocked,
                    onClick = { onEdit(item.rule.id) },
                    onToggle = { vm.setEnabled(item.rule.id, it) },
                    onDuplicate = { vm.duplicate(item.rule.id) },
                    onDelete = { confirmDelete = item.rule.id },
                )
            }
        }
    }

    confirmDelete?.let { id ->
        val name = state.items.firstOrNull { it.rule.id == id }?.rule?.name.orEmpty()
        ConfirmDialog(
            title = stringResource(R.string.delete_schedule_title),
            body = stringResource(R.string.delete_schedule_body, name),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = { vm.delete(id); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun ScheduleCard(
    item: ScheduleItem,
    preview: String,
    locked: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val r = item.rule
    var menu by remember { mutableStateOf(false) }
    FrostCard(
        onClick = onClick,
        modifier = Modifier.animateContentSize(),
        containerColor = if (item.activeNow) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (r.startMinute >= 18 * 60 || r.endMinute <= 8 * 60) Icons.Rounded.Bedtime else Icons.Rounded.Schedule, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                    if (item.activeNow) {
                        Spacer(Modifier.width(8.dp))
                        Pill(stringResource(R.string.active_now))
                    }
                }
                Text(
                    "${Format.timeRange(r.startMinute, r.endMinute)} · ${Format.days(r.daysMask)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = r.enabled,
                onCheckedChange = onToggle,
                enabled = !(locked && r.enabled),
                modifier = Modifier.semantics { contentDescription = r.name },
            )
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        enabled = !locked,
                        onClick = { menu = false; onClick() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.duplicate)) },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = { menu = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        enabled = !locked,
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconStack(r.packages)
            Spacer(Modifier.width(12.dp))
            Text(
                if (r.enabled) preview else stringResource(R.string.schedule_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
