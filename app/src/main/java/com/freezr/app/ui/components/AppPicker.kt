package com.freezr.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.freezr.app.R
import com.freezr.app.data.repo.AppGroup
import com.freezr.app.platform.apps.InstalledApp

/** Filters an app list by a free-text query against label and package name. */
fun List<InstalledApp>.search(query: String): List<InstalledApp> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
}

@Composable
fun AppPickerDialog(
    title: String,
    apps: List<InstalledApp>,
    initial: Set<String>,
    groups: List<AppGroup>,
    myApps: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var selection by remember { mutableStateOf(initial) }
    var query by rememberSaveable { mutableStateOf("") }
    var onlySelected by rememberSaveable { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel)) }
                    },
                )
            },
            bottomBar = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        pluralStringResource(R.plurals.apps_selected, selection.size, selection.size),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = { onConfirm(selection) }) { Text(stringResource(R.string.done)) }
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .imePadding(),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.search_apps)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = MaterialTheme.shapes.large,
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(selected = onlySelected, onClick = { onlySelected = !onlySelected }, label = { Text(stringResource(R.string.filter_selected)) })
                    }
                    if (myApps.isNotEmpty()) {
                        item {
                            FilterChip(
                                selected = myApps.isNotEmpty() && selection.containsAll(myApps),
                                onClick = { selection = if (selection.containsAll(myApps)) selection - myApps else selection + myApps },
                                label = { Text(stringResource(R.string.my_apps)) },
                            )
                        }
                    }
                    items(groups, key = { it.id }) { g ->
                        FilterChip(
                            selected = g.packages.isNotEmpty() && selection.containsAll(g.packages),
                            onClick = { selection = if (selection.containsAll(g.packages)) selection - g.packages else selection + g.packages },
                            label = { Text(g.name) },
                        )
                    }
                }
                val visible = remember(apps, query, onlySelected, selection) {
                    apps.search(query).filter { !onlySelected || it.packageName in selection }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(visible, key = { it.packageName }) { app ->
                        AppCheckRow(app, app.packageName in selection) { on ->
                            selection = if (on) selection + app.packageName else selection - app.packageName
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppCheckRow(app: InstalledApp, checked: Boolean, trailing: (@Composable () -> Unit)? = null, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            trailing?.invoke()
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun AppCheckRow(app: InstalledApp, checked: Boolean, onToggle: (Boolean) -> Unit) =
    AppCheckRow(app, checked, null, onToggle)
