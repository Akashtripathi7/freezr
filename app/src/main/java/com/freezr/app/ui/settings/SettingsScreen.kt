package com.freezr.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freezr.app.BuildConfig
import com.freezr.app.R
import com.freezr.app.data.settings.AppSettings
import com.freezr.app.ui.components.Format
import com.freezr.app.ui.components.SectionHeader
import com.freezr.app.ui.components.StrictLockedBanner
import com.freezr.app.ui.components.TimePickerDialog

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onWhitelist: () -> Unit,
    onStrict: () -> Unit,
    onOem: () -> Unit,
    onPrivacy: () -> Unit,
    onSelectors: () -> Unit,
    onRules: () -> Unit,
    onWeb: () -> Unit,
    onEmergency: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    val snackbar = remember { SnackbarHostState() }
    val strictMsg = stringResource(R.string.strict_blocked_action)
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    LaunchedEffect(Unit) { vm.blocked.collect { snackbar.showSnackbar(strictMsg) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { StrictLockedBanner(state.locked, onStrict, Modifier.padding(16.dp)) }

            item { Header(R.string.settings_section_protection) }
            item { NavRow(Icons.Rounded.Lock, R.string.strict_mode, if (s.strictEnabled) R.string.on else R.string.off, onStrict) }
            item { NavRow(Icons.Rounded.LockOpen, R.string.emergency_unlock, R.string.emergency_settings_sub, onEmergency) }
            item { NavRow(Icons.Rounded.VerifiedUser, R.string.whitelist, R.string.whitelist_sub, onWhitelist) }
            item {
                SwitchRow(Icons.Rounded.AdminPanelSettings, R.string.protect_settings, R.string.protect_settings_sub, s.protectSettingsScreens, vm::setProtectSettings)
            }
            item { NavRow(Icons.Rounded.PhoneAndroid, R.string.oem_title, R.string.oem_settings_sub, onOem) }

            item { Header(R.string.settings_section_notifications) }
            item {
                ValueRow(
                    Icons.Rounded.Notifications, R.string.pre_freeze_setting,
                    if (s.preFreezeEnabled) stringResource(R.string.minutes_before, s.preFreezeMinutes) else stringResource(R.string.off),
                ) { dialog = SettingsDialog.PreFreeze }
            }
            item { SwitchRow(Icons.Rounded.Timer, R.string.limit_warnings, R.string.limit_warnings_sub, s.limitWarningsEnabled, vm::setLimitWarnings) }
            item { SwitchRow(Icons.Rounded.Shield, R.string.status_notification, R.string.status_notification_sub, s.statusNotificationEnabled, vm::setStatusNotification) }

            item { Header(R.string.settings_section_limits) }
            item { ValueRow(Icons.Rounded.RestartAlt, R.string.reset_time, Format.minuteOfDay(s.usageResetMinute)) { dialog = SettingsDialog.ResetTime } }
            item { ValueRow(Icons.Rounded.LockOpen, R.string.emergency_rules, stringResource(R.string.emergency_rules_value, s.emergencyPassesPerWeek, s.emergencyPassMinutes)) { dialog = SettingsDialog.Emergency } }

            item { Header(R.string.settings_section_more) }
            item { NavRow(Icons.Rounded.Place, R.string.places_wifi, R.string.places_wifi_sub, onRules) }
            item { NavRow(Icons.Rounded.Language, R.string.web_blocking, R.string.web_blocking_sub, onWeb) }
            item { NavRow(Icons.Rounded.BugReport, R.string.selector_health, R.string.selector_health_sub, onSelectors) }
            item { NavRow(Icons.Rounded.Policy, R.string.privacy, R.string.privacy_sub, onPrivacy) }
            item {
                Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.version_line, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    when (dialog) {
        SettingsDialog.PreFreeze -> PreFreezeDialog(s, onDismiss = { dialog = null }) { enabled, minutes ->
            vm.setPreFreeze(enabled, minutes)
            dialog = null
        }
        SettingsDialog.ResetTime -> TimePickerDialog(s.usageResetMinute, stringResource(R.string.reset_time), { dialog = null }) {
            vm.setResetMinute(it)
            dialog = null
        }
        SettingsDialog.Emergency -> EmergencyDialog(s, onDismiss = { dialog = null }) { e, w, p, n, m, x ->
            vm.setEmergency(e, w, p, n, m, x)
            dialog = null
        }
        null -> Unit
    }
}

private enum class SettingsDialog { PreFreeze, ResetTime, Emergency }

@Composable
private fun Header(res: Int) = SectionHeader(stringResource(res), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

@Composable
private fun NavRow(icon: ImageVector, title: Int, sub: Int, onClick: () -> Unit) =
    ValueRow(icon, title, stringResource(sub), chevron = true, onClick = onClick)

@Composable
private fun ValueRow(icon: ImageVector, title: Int, value: String, chevron: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (chevron) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: Int, sub: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PreFreezeDialog(s: AppSettings, onDismiss: () -> Unit, onSave: (Boolean, Int) -> Unit) {
    var enabled by remember { mutableStateOf(s.preFreezeEnabled) }
    var minutes by remember { mutableStateOf(s.preFreezeMinutes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pre_freeze_setting)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.pre_freeze_enabled), Modifier.weight(1f))
                    Switch(enabled, { enabled = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30).forEach { m -> FilterChip(minutes == m, { minutes = m }, { Text("${m}m") }, enabled = enabled) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(enabled, minutes) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun EmergencyDialog(s: AppSettings, onDismiss: () -> Unit, onSave: (Boolean, Int, String, Int, Int, Int) -> Unit) {
    var enabled by remember { mutableStateOf(s.emergencyEnabled) }
    var wait by remember { mutableStateOf(s.emergencyWaitSeconds.toString()) }
    var phrase by remember { mutableStateOf(s.emergencyPhrase) }
    var perWeek by remember { mutableStateOf(s.emergencyPassesPerWeek.toString()) }
    var minutes by remember { mutableStateOf(s.emergencyPassMinutes.toString()) }
    var extra by remember { mutableStateOf(s.emergencyExtraLimitMinutes.toString()) }
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.emergency_rules)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.emergency_enabled), Modifier.weight(1f))
                    Switch(enabled, { enabled = it })
                }
                OutlinedTextField(wait, { wait = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.wait_seconds)) }, keyboardOptions = numbers, singleLine = true)
                OutlinedTextField(phrase, { phrase = it.take(120) }, label = { Text(stringResource(R.string.phrase)) })
                OutlinedTextField(perWeek, { perWeek = it.filter(Char::isDigit).take(2) }, label = { Text(stringResource(R.string.passes_per_week)) }, keyboardOptions = numbers, singleLine = true)
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(2) }, label = { Text(stringResource(R.string.minutes_per_pass)) }, keyboardOptions = numbers, singleLine = true)
                OutlinedTextField(extra, { extra = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.extra_limit_minutes)) }, keyboardOptions = numbers, singleLine = true)
                Text(stringResource(R.string.emergency_settings_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    enabled,
                    wait.toIntOrNull() ?: s.emergencyWaitSeconds,
                    phrase,
                    perWeek.toIntOrNull() ?: s.emergencyPassesPerWeek,
                    minutes.toIntOrNull() ?: s.emergencyPassMinutes,
                    extra.toIntOrNull() ?: s.emergencyExtraLimitMinutes,
                )
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
