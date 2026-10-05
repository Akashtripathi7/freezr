package com.freezr.app.ui.settings

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freezr.app.R
import com.freezr.app.ui.components.AppIcon
import com.freezr.app.ui.components.AppIconStack
import com.freezr.app.ui.components.AppPickerDialog
import com.freezr.app.ui.components.FrostCard
import com.freezr.app.ui.components.LocalAppCatalog
import com.freezr.app.ui.components.SectionHeader

@Composable
fun WhitelistScreen(onBack: () -> Unit, vm: WhitelistViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val catalog = LocalAppCatalog.current
    val snackbar = remember { SnackbarHostState() }
    val strictMsg = stringResource(R.string.strict_blocked_action)
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.blocked.collect { snackbar.showSnackbar(strictMsg) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.whitelist)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picking = true },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(stringResource(R.string.add_app)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(stringResource(R.string.whitelist_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { SectionHeader(stringResource(R.string.whitelist_core)) }
            items(state.core.filter { it.second.isNotEmpty() }, key = { it.first }) { (title, pkgs) ->
                FrostCard(contentPadding = PaddingValues(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, contentDescription = stringResource(R.string.cd_cannot_remove), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        AppIconStack(pkgs, max = 3, size = 24.dp)
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.whitelist_yours)) }
            if (state.user.isEmpty()) {
                item { Text(stringResource(R.string.whitelist_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.user, key = { it.packageName }) { e ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(e.packageName)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(catalog.label(e.packageName), style = MaterialTheme.typography.bodyLarge)
                        if (e.isDefault) Text(stringResource(R.string.whitelist_default), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.remove(e.packageName) }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.cd_remove_whitelist, catalog.label(e.packageName)))
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    if (picking) {
        AppPickerDialog(
            title = stringResource(R.string.add_app),
            apps = state.apps.filter { a -> state.user.none { it.packageName == a.packageName } },
            initial = emptySet(),
            groups = emptyList(),
            myApps = emptySet(),
            onDismiss = { picking = false },
            onConfirm = { vm.add(it); picking = false },
        )
    }
}
