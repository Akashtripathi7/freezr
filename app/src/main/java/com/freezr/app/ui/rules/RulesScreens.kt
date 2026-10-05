package com.freezr.app.ui.rules

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freezr.app.R
import com.freezr.app.data.settings.SurfaceBlockMode
import com.freezr.app.domain.model.ContextRuleType
import com.freezr.app.domain.model.Days
import com.freezr.app.domain.model.TimeFilter
import com.freezr.app.ui.components.AppIconStack
import com.freezr.app.ui.components.AppPickerDialog
import com.freezr.app.ui.components.ConfirmDialog
import com.freezr.app.ui.components.DayChips
import com.freezr.app.ui.components.EmptyState
import com.freezr.app.ui.components.Format
import com.freezr.app.ui.components.FrostCard
import com.freezr.app.ui.components.IconBadge
import com.freezr.app.ui.components.Pill
import com.freezr.app.ui.components.SectionHeader
import com.freezr.app.ui.components.StrictLockedBanner
import com.freezr.app.ui.components.TimePickerDialog
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
private fun BackBar(title: String, onBack: () -> Unit) = TopAppBar(
    title = { Text(title) },
    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
)

// ------------------------------------------------------------------ Places & Wi-Fi list

@Composable
fun ContextRulesScreen(onBack: () -> Unit, onEdit: (Long) -> Unit, vm: ContextRulesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val strictMsg = stringResource(R.string.strict_blocked_action)
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { vm.blocked.collect { snackbar.showSnackbar(strictMsg) } }
    LifecycleResumeEffect(Unit) {
        vm.refreshPermissions()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = { BackBar(stringResource(R.string.places_wifi), onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onEdit(0) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(stringResource(R.string.new_rule)) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StrictLockedBanner(state.locked, onUnlock = {}) }
            item { Text(stringResource(R.string.places_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!state.hasLocation && state.items.isNotEmpty()) {
                item {
                    FrostCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                        Text(stringResource(R.string.location_missing), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            if (!state.loading && state.items.isEmpty()) {
                item { EmptyState(Icons.Rounded.Place, stringResource(R.string.no_rules_title), stringResource(R.string.no_rules_body)) }
            }
            items(state.items, key = { it.draft.id }) { item ->
                val d = item.draft
                FrostCard(onClick = { onEdit(d.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(if (d.type == ContextRuleType.WIFI) Icons.Rounded.Wifi else Icons.Rounded.Place, MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(d.name, style = MaterialTheme.typography.titleMedium)
                                if (item.active && d.enabled) {
                                    Spacer(Modifier.width(8.dp))
                                    Pill(stringResource(if (d.type == ContextRuleType.WIFI) R.string.connected else R.string.inside))
                                }
                            }
                            Text(
                                if (d.type == ContextRuleType.WIFI) d.ssid else stringResource(R.string.radius_m, d.radiusMeters.roundToInt()),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(d.enabled, { vm.setEnabled(d.id, it) }, enabled = !(state.locked && d.enabled))
                        IconButton(onClick = { confirmDelete = d.id }, enabled = !state.locked) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    AppIconStack(d.packages)
                }
            }
        }
    }
    confirmDelete?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.delete_rule_title),
            body = stringResource(R.string.delete_rule_body),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = { vm.delete(id); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
}

// ------------------------------------------------------------------ Edit rule

@Composable
fun ContextRuleEditScreen(onBack: () -> Unit, vm: ContextRuleEditViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val strictMsg = stringResource(R.string.strict_blocked_action)
    val locFailed = stringResource(R.string.location_failed)
    var pickApps by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf<Boolean?>(null) }
    var backgroundRationale by remember { mutableStateOf(false) }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val needsBackground = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var afterForeground by remember { mutableStateOf<(() -> Unit)?>(null) }
    val foregroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) afterForeground?.invoke()
        afterForeground = null
    }

    fun withForegroundLocation(then: () -> Unit) {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) then() else {
            afterForeground = then
            foregroundLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect {
            when (it) {
                RuleEditEvent.Saved -> if (needsBackground) backgroundRationale = true else onBack()
                RuleEditEvent.Blocked -> snackbar.showSnackbar(strictMsg)
                RuleEditEvent.LocationFailed -> snackbar.showSnackbar(locFailed)
            }
        }
    }

    Scaffold(
        topBar = { BackBar(stringResource(if (vm.isNew) R.string.new_rule else R.string.edit_rule), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Button(
                onClick = { withForegroundLocation { vm.save() } },
                enabled = state.draft.isValid && (vm.isNew || !state.locked),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp),
            ) { Text(stringResource(R.string.save_rule)) }
        },
    ) { padding ->
        if (!state.loaded) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val d = state.draft
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ContextRuleType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = d.type == t,
                        onClick = { vm.setType(t) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = { Icon(if (t == ContextRuleType.WIFI) Icons.Rounded.Wifi else Icons.Rounded.Place, null, Modifier.size(18.dp)) },
                    ) { Text(stringResource(if (t == ContextRuleType.WIFI) R.string.wifi else R.string.place)) }
                }
            }
            OutlinedTextField(
                d.name, { v -> vm.update { it.copy(name = v.take(40)) } },
                label = { Text(stringResource(R.string.rule_name)) },
                placeholder = { Text(stringResource(if (d.type == ContextRuleType.WIFI) R.string.rule_name_hint_wifi else R.string.rule_name_hint_place)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (d.type == ContextRuleType.GEOFENCE) {
                SectionHeader(stringResource(R.string.place))
                FrostCard {
                    if (d.latitude != null && d.longitude != null) {
                        Text("%.5f, %.5f".format(d.latitude, d.longitude), style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text(stringResource(R.string.no_place_yet), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { withForegroundLocation { vm.useCurrentLocation() } }, enabled = !state.locating) {
                        if (state.locating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.MyLocation, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.use_current_location))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.radius_m, d.radiusMeters.roundToInt()), style = MaterialTheme.typography.labelLarge)
                    Slider(d.radiusMeters, { r -> vm.update { it.copy(radiusMeters = (r / 25).roundToInt() * 25f) } }, valueRange = 50f..1000f)
                }
            } else {
                SectionHeader(stringResource(R.string.wifi))
                OutlinedTextField(
                    d.ssid, { v -> vm.update { it.copy(ssid = v.take(32)) } },
                    label = { Text(stringResource(R.string.ssid)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { withForegroundLocation { vm.readCurrentSsid() } }) {
                    Icon(Icons.Rounded.Wifi, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.use_current_wifi))
                }
            }

            SectionHeader(stringResource(R.string.only_during))
            FrostCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.time_filter), Modifier.weight(1f))
                    Switch(d.filter != null, { on -> vm.update { it.copy(filter = if (on) TimeFilter(9 * 60, 17 * 60, Days.WEEKDAYS) else null) } })
                }
                d.filter?.let { f ->
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickTime = true }) { Text(Format.minuteOfDay(f.startMinute)) }
                        Text("→", Modifier.align(Alignment.CenterVertically))
                        OutlinedButton(onClick = { pickTime = false }) { Text(Format.minuteOfDay(f.endMinute)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    DayChips(f.daysMask, { m -> vm.update { it.copy(filter = f.copy(daysMask = m)) } })
                }
            }

            SectionHeader(stringResource(R.string.apps_to_freeze))
            FrostCard(onClick = { pickApps = true }) {
                Text(
                    if (d.packages.isEmpty()) stringResource(R.string.choose_apps) else pluralStringResource(R.plurals.apps_count, d.packages.size, d.packages.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (d.packages.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    AppIconStack(d.packages, max = 6)
                }
            }
            Text(stringResource(R.string.location_privacy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickApps) {
        AppPickerDialog(
            title = stringResource(R.string.apps_to_freeze), apps = state.apps, initial = state.draft.packages,
            groups = state.groups, myApps = state.myApps,
            onDismiss = { pickApps = false },
            onConfirm = { set -> vm.update { it.copy(packages = set) }; pickApps = false },
        )
    }
    pickTime?.let { start ->
        val f = state.draft.filter ?: return@let
        TimePickerDialog(if (start) f.startMinute else f.endMinute, stringResource(if (start) R.string.starts else R.string.ends), { pickTime = null }) { m ->
            vm.update { it.copy(filter = if (start) f.copy(startMinute = m) else f.copy(endMinute = m)) }
            pickTime = null
        }
    }
    if (backgroundRationale) {
        // Rationale shown before asking for "Allow all the time" — only once a rule actually needs it.
        AlertDialog(
            onDismissRequest = { backgroundRationale = false; onBack() },
            icon = { Icon(Icons.Rounded.Place, null) },
            title = { Text(stringResource(R.string.bg_location_title)) },
            text = { Text(stringResource(R.string.bg_location_body)) },
            confirmButton = {
                TextButton(onClick = {
                    backgroundRationale = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    onBack()
                }) { Text(stringResource(R.string.bg_location_allow)) }
            },
            dismissButton = { TextButton(onClick = { backgroundRationale = false; onBack() }) { Text(stringResource(R.string.not_now)) } },
        )
    }
}

// ------------------------------------------------------------------ Web & in-app blocking

@Composable
fun WebBlockingScreen(onBack: () -> Unit, onSelectors: () -> Unit, vm: WebBlockingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val strictMsg = stringResource(R.string.strict_blocked_action)
    val invalidMsg = stringResource(R.string.invalid_domain)
    var input by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        vm.events.collect { snackbar.showSnackbar(if (it is WebEvent.Blocked) strictMsg else invalidMsg) }
    }

    Scaffold(topBar = { BackBar(stringResource(R.string.web_blocking), onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FrostCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(stringResource(R.string.best_effort_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(stringResource(R.string.best_effort_body, state.browsers.joinToString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            item { SectionHeader(stringResource(R.string.websites)) }
            item { ModeChips(state.settings.webBlockMode, vm::setWebMode) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        input, { input = it.take(253) },
                        placeholder = { Text("youtube.com") },
                        leadingIcon = { Icon(Icons.Rounded.Language, null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { vm.add(input); input = "" }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { vm.add(input); input = "" }, enabled = input.isNotBlank()) { Text(stringResource(R.string.add)) }
                }
            }
            items(state.domains, key = { it }) { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(d, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.and_subdomains), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { vm.remove(d) }, enabled = !state.locked) { Icon(Icons.Rounded.Close, stringResource(R.string.cd_remove_domain, d)) }
                }
            }
            item { SectionHeader(stringResource(R.string.in_app_surfaces)) }
            item { ModeChips(state.settings.inAppBlockMode, vm::setInAppMode) }
            items(state.inAppRules, key = { it.id }) { rule ->
                val on = rule.id in state.settings.enabledInAppRules
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.title, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.in_app_leave_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(on, { vm.toggleInApp(rule.id, it) }, enabled = !(state.locked && on))
                }
            }
            item { TextButton(onClick = onSelectors) { Text(stringResource(R.string.selector_health)) } }
        }
    }
}

@Composable
private fun ModeChips(mode: SurfaceBlockMode, onChange: (SurfaceBlockMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            SurfaceBlockMode.DURING_ANY_FREEZE to R.string.mode_during_freeze,
            SurfaceBlockMode.ALWAYS to R.string.mode_always,
            SurfaceBlockMode.OFF to R.string.off,
        ).forEach { (m, label) -> FilterChip(mode == m, { onChange(m) }, { Text(stringResource(label)) }) }
    }
}

// ------------------------------------------------------------------ Selector health (debug)

@Composable
fun SelectorHealthScreen(onBack: () -> Unit, vm: SelectorHealthViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    Scaffold(topBar = { BackBar(stringResource(R.string.selector_health), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                FrostCard {
                    Text(stringResource(R.string.rules_file_version, state.version, state.updated), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.rules_source, state.source), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.rules_override_path, state.overridePath), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = vm::reload) { Text(stringResource(R.string.reload_rules)) }
                }
            }
            item { Text(stringResource(R.string.selector_health_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.rows, key = { it.id }) { r ->
                FrostCard(contentPadding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleSmall)
                            Text(r.target, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Pill(
                            stringResource(
                                when {
                                    !r.installed -> R.string.sel_not_installed
                                    r.matches > 0 -> R.string.sel_working
                                    r.checks > 0 -> R.string.sel_no_match
                                    else -> R.string.sel_untested
                                },
                            ),
                            color = when {
                                !r.installed -> MaterialTheme.colorScheme.onSurfaceVariant
                                r.matches > 0 -> MaterialTheme.colorScheme.tertiary
                                r.checks > 0 -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    Text(
                        stringResource(R.string.sel_stats, r.checks, r.matches, r.lastMatch?.let { Format.dayTime(it, Instant.now(), ZoneId.systemDefault()) } ?: "—"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
