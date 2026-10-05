package com.freezr.app.ui.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freezr.app.R
import com.freezr.app.domain.model.ActiveFreeze
import com.freezr.app.platform.health.HealthItem
import com.freezr.app.platform.health.HealthReport
import com.freezr.app.platform.health.PermissionHealthChecker
import com.freezr.app.ui.components.AppIconStack
import com.freezr.app.ui.components.Format
import com.freezr.app.ui.components.FrostCard
import com.freezr.app.ui.components.IconBadge
import com.freezr.app.ui.components.SectionHeader
import com.freezr.app.ui.freeze.labelRes
import com.freezr.app.ui.theme.FreezrThemeExtras
import com.freezr.app.ui.theme.TabularNumbers
import java.time.Duration
import java.time.LocalTime
import kotlin.math.roundToInt

data class HomeNav(
    val onSettings: () -> Unit,
    val onSchedules: () -> Unit,
    val onNewSchedule: () -> Unit,
    val onApps: () -> Unit,
    val onStrict: () -> Unit,
    val onEmergencyInfo: () -> Unit,
    val onRules: () -> Unit,
    val onWeb: () -> Unit,
    val onOemGuide: () -> Unit,
)

@Composable
fun HomeScreen(nav: HomeNav, vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val strictMsg = stringResource(R.string.strict_blocked_action)
    val nothingMsg = stringResource(R.string.nothing_selected)

    LifecycleResumeEffect(Unit) {
        vm.refreshHealth()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect {
            snackbar.showSnackbar(if (it is HomeMessage.StrictBlocked) strictMsg else nothingMsg)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Header(onSettings = nav.onSettings) }
            item { StatusHero(state, onToggle = vm::setMaster) }
            state.health?.takeIf { !it.isHealthy }?.let { report ->
                item { HealthCard(report, onOemGuide = nav.onOemGuide) }
            }
            item {
                FocusCard(
                    state = state,
                    onStart = vm::startFocus,
                    onCancel = vm::cancelFocus,
                    onPickApps = nav.onApps,
                )
            }
            if (state.active.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.frozen_now)) }
                items(state.active, key = { it.ruleId }) { ActiveRow(it, state) }
            }
            if (!state.hasRules && !state.loading) {
                item { GetStartedCard(nav) }
            }
            item { SectionHeader(stringResource(R.string.your_tools)) }
            item { ToolsGrid(state, nav) }
            state.health?.takeIf { it.isHealthy }?.let {
                item { HealthyChip() }
            }
        }
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    val hour = remember { LocalTime.now().hour }
    val greeting = when (hour) {
        in 5..11 -> R.string.greeting_morning
        in 12..17 -> R.string.greeting_afternoon
        else -> R.string.greeting_evening
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(greeting), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.settings)) }
    }
}

@Composable
private fun StatusHero(state: HomeState, onToggle: (Boolean) -> Unit) {
    val extras = FreezrThemeExtras.current
    val frozen = state.masterEnabled && state.active.isNotEmpty()
    val brush = when {
        !state.masterEnabled -> extras.pausedGradient
        frozen -> extras.frozenGradient
        else -> extras.idleGradient
    }
    val spin = rememberInfiniteTransition(label = "hero")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "angle")

    val title = when {
        !state.masterEnabled -> stringResource(R.string.hero_paused)
        frozen -> pluralStringResource(R.plurals.hero_frozen, state.frozenPackages.size, state.frozenPackages.size)
        else -> stringResource(R.string.hero_protected)
    }
    val subtitle = when {
        !state.masterEnabled -> stringResource(R.string.hero_paused_sub)
        frozen -> state.active.mapNotNull { it.until }.maxOrNull()?.let {
            stringResource(R.string.hero_until, Format.dayTime(it, state.now, state.zone))
        } ?: stringResource(R.string.hero_while_rule)
        state.nextTransition != null -> stringResource(R.string.hero_next_change, Format.dayTime(state.nextTransition, state.now, state.zone))
        else -> stringResource(R.string.hero_idle_sub)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(brush)
            .semantics(mergeDescendants = true) {},
    ) {
        Icon(
            Icons.Rounded.AcUnit,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(180.dp)
                .padding(end = 0.dp)
                .rotate(if (frozen) angle else 0f)
                .alpha(0.14f),
        )
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.18f)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (state.strictLocked) Icons.Rounded.Lock else Icons.Rounded.Shield, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(
                                when {
                                    state.strictLocked -> R.string.strict_mode_on
                                    state.masterEnabled -> R.string.protection_on
                                    else -> R.string.protection_off
                                },
                            ),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                val masterLabel = stringResource(R.string.master_toggle)
                Switch(
                    checked = state.masterEnabled,
                    onCheckedChange = onToggle,
                    enabled = !(state.strictLocked && state.masterEnabled),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF1E1B4B),
                        checkedTrackColor = Color.White,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.25f),
                        uncheckedBorderColor = Color.White.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = masterLabel
                        stateDescription = if (state.masterEnabled) "On" else "Off"
                    },
                )
            }
            Spacer(Modifier.height(28.dp))
            AnimatedContent(title, label = "title") { t ->
                Text(t, style = MaterialTheme.typography.headlineLarge, color = Color.White)
            }
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f))
            if (frozen) {
                Spacer(Modifier.height(16.dp))
                AppIconStack(state.frozenPackages, max = 6, size = 30.dp)
            }
        }
    }
}

@Composable
private fun HealthCard(report: HealthReport, onOemGuide: () -> Unit) {
    val context = LocalContext.current
    FrostCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.health_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    stringResource(if (report.criticalProblems.isNotEmpty()) R.string.health_sub_critical else R.string.health_sub_minor),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        report.problems.forEach { item ->
            HealthRow(item) {
                val intent = PermissionHealthChecker.fixIntent(context, item).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
        if (HealthItem.SERVICE_RUNNING in report.problems || HealthItem.BATTERY in report.problems) {
            TextButton(onClick = onOemGuide) { Text(stringResource(R.string.oem_guide_link)) }
        }
    }
}

@Composable
private fun HealthRow(item: HealthItem, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(item.titleRes), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(stringResource(item.bodyRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f))
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onFix) { Text(stringResource(R.string.fix)) }
    }
}

@Composable
private fun HealthyChip() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CheckCircle, null, tint = FreezrThemeExtras.current.success, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.health_ok), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FocusCard(state: HomeState, onStart: (Int, Set<String>?) -> Unit, onCancel: () -> Unit, onPickApps: () -> Unit) {
    var target by rememberSaveable { mutableStateOf<Long?>(null) } // null = my apps, else group id
    var targetMenu by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    val remaining = state.quickRemaining
    val targetPackages: Set<String>? = target?.let { id -> state.groups.firstOrNull { it.id == id }?.packages }
    val targetLabel = target?.let { id -> state.groups.firstOrNull { it.id == id }?.name }
        ?: pluralStringResource(R.plurals.my_apps_count, state.myApps.size, state.myApps.size)

    FrostCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Timer, MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.focus_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (remaining != null) R.string.focus_running else R.string.focus_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        AnimatedContent(remaining != null, label = "focus") { running ->
            if (running && remaining != null) {
                Column {
                    Text(Format.countdown(remaining), style = MaterialTheme.typography.displaySmall.merge(TabularNumbers))
                    state.quickFreeze?.let { AppIconStack(it.packages, max = 6) }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onStart(15, state.quickFreeze?.packages) }) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.add_15))
                        }
                        OutlinedButton(onClick = onCancel, enabled = !state.strictLocked) {
                            Icon(if (state.strictLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.end_early))
                        }
                    }
                }
            } else {
                Column {
                    Box {
                        TextButton(onClick = { targetMenu = true }, contentPadding = PaddingValues(0.dp)) {
                            Text(stringResource(R.string.focus_target, targetLabel))
                        }
                        DropdownMenu(targetMenu, { targetMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(pluralStringResource(R.plurals.my_apps_count, state.myApps.size, state.myApps.size)) },
                                onClick = { target = null; targetMenu = false },
                            )
                            state.groups.forEach { g ->
                                DropdownMenuItem(text = { Text(g.name) }, onClick = { target = g.id; targetMenu = false })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.edit_my_apps)) }, onClick = { targetMenu = false; onPickApps() })
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60).forEach { m ->
                            Button(onClick = { onStart(m, targetPackages) }) {
                                Text(if (m < 60) "${m}m" else "${m / 60}h")
                            }
                        }
                        OutlinedButton(onClick = { custom = true }) { Text(stringResource(R.string.custom)) }
                    }
                }
            }
        }
    }
    if (custom) {
        CustomDurationDialog(onDismiss = { custom = false }, onConfirm = { onStart(it, targetPackages); custom = false })
    }
}

@Composable
private fun CustomDurationDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var minutes by remember { mutableFloatStateOf(45f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_focus)) },
        text = {
            Column {
                Text(Format.duration(Duration.ofMinutes(minutes.roundToInt().toLong())), style = MaterialTheme.typography.headlineMedium)
                Slider(minutes, { minutes = (it / 5).roundToInt() * 5f }, valueRange = 5f..480f)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(minutes.roundToInt()) }) { Text(stringResource(R.string.start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ActiveRow(f: ActiveFreeze, state: HomeState) {
    FrostCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.ruleName, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(f.reason.labelRes()) + (f.until?.let { " · " + stringResource(R.string.until_time, Format.dayTime(it, state.now, state.zone)) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppIconStack(f.packages, max = 3, size = 26.dp)
        }
    }
}

@Composable
private fun GetStartedCard(nav: HomeNav) {
    FrostCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Text(stringResource(R.string.get_started_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.get_started_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = nav.onApps) { Text(stringResource(R.string.pick_apps)) }
            OutlinedButton(onClick = nav.onNewSchedule) { Text(stringResource(R.string.new_schedule)) }
        }
    }
}

@Composable
private fun ToolsGrid(state: HomeState, nav: HomeNav) {
    val tiles = listOf(
        Tool(Icons.Rounded.Bedtime, stringResource(R.string.tab_schedules), pluralStringResource(R.plurals.active_count, state.scheduleCount, state.scheduleCount), nav.onSchedules),
        Tool(Icons.Rounded.Lock, stringResource(R.string.strict_mode), stringResource(if (state.strictEnabled) R.string.on else R.string.off), nav.onStrict),
        Tool(Icons.Rounded.LockOpen, stringResource(R.string.emergency_passes), pluralStringResource(R.plurals.passes_left, state.passesRemaining, state.passesRemaining), nav.onEmergencyInfo),
        Tool(Icons.Rounded.Place, stringResource(R.string.places_wifi), stringResource(R.string.places_wifi_sub), nav.onRules),
        Tool(Icons.Rounded.Language, stringResource(R.string.web_blocking), stringResource(R.string.web_blocking_sub), nav.onWeb),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t -> ToolTile(t, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class Tool(val icon: ImageVector, val title: String, val sub: String, val onClick: () -> Unit)

@Composable
private fun ToolTile(t: Tool, modifier: Modifier) {
    FrostCard(onClick = t.onClick, modifier = modifier, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(t.icon, MaterialTheme.colorScheme.primary, size = 36.dp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Text(t.title, style = MaterialTheme.typography.titleSmall)
        Text(t.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
