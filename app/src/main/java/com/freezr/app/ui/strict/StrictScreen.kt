package com.freezr.app.ui.strict

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freezr.app.R
import com.freezr.app.data.repo.DelayRequestResult
import com.freezr.app.data.repo.PartnerApprovalResult
import com.freezr.app.data.repo.PinResult
import com.freezr.app.domain.security.PinHasher
import com.freezr.app.domain.strict.DelayUnlockState
import com.freezr.app.domain.strict.StrictUnlockMethod
import com.freezr.app.platform.health.HealthItem
import com.freezr.app.platform.health.PermissionHealthChecker
import com.freezr.app.ui.components.Format
import com.freezr.app.ui.components.FrostCard
import com.freezr.app.ui.components.IconBadge
import com.freezr.app.ui.components.SectionHeader
import com.freezr.app.ui.theme.TabularNumbers
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun StrictScreen(onBack: () -> Unit, vm: StrictViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var pinDialog by remember { mutableStateOf<PinMode?>(null) }
    val strictMsg = stringResource(R.string.strict_blocked_action)
    val msgPinSaved = stringResource(R.string.pin_saved)
    val msgUnlocked = stringResource(R.string.strict_unlocked_msg)
    val msgRateLimited = stringResource(R.string.delay_rate_limited)
    val msgAlready = stringResource(R.string.delay_already)
    val msgNoPin = stringResource(R.string.no_pin_set)
    val msgPartner = stringResource(R.string.partner_unavailable)
    val wrongPinFmt = stringResource(R.string.pin_wrong)
    val lockedFmt = stringResource(R.string.pin_locked_out)
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshAdmin() }

    LifecycleResumeEffect(Unit) {
        vm.refreshAdmin()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            val text = when (m) {
                StrictMessage.Blocked -> strictMsg
                StrictMessage.PinSaved -> msgPinSaved
                StrictMessage.DelayConfirmed -> msgUnlocked
                is StrictMessage.Pin -> when (val r = m.result) {
                    PinResult.Success -> { pinDialog = null; msgUnlocked }
                    is PinResult.Wrong -> wrongPinFmt.format(r.attemptsBeforeLockout)
                    is PinResult.LockedOut -> lockedFmt.format(Format.time(r.until, ZoneId.systemDefault()))
                    PinResult.NoPinSet -> msgNoPin
                }
                is StrictMessage.Delay -> when (m.result) {
                    is DelayRequestResult.Started -> null
                    DelayRequestResult.AlreadyRunning -> msgAlready
                    DelayRequestResult.RateLimited -> msgRateLimited
                }
                is StrictMessage.Partner -> if (m.result is PartnerApprovalResult.Unavailable) msgPartner else null
            }
            if (text != null) snackbar.showSnackbar(text)
        }
    }

    val s = state.settings
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.strict_mode)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FrostCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.Shield, MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.strict_commitment_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.strict_commitment_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            item {
                FrostCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.strict_mode), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(
                                    when {
                                        !s.strictEnabled -> R.string.strict_status_off
                                        state.locked -> R.string.strict_status_locked
                                        else -> R.string.strict_status_armed
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = s.strictEnabled,
                            onCheckedChange = { on ->
                                if (on) {
                                    if (s.strictMethod == StrictUnlockMethod.PIN && !state.hasPin) pinDialog = PinMode.SET_AND_ENABLE else vm.enable(s.strictMethod)
                                } else {
                                    vm.disable()
                                }
                            },
                            enabled = !(state.locked && s.strictEnabled),
                        )
                    }
                }
            }

            if (s.strictEnabled && (state.locked || state.unlockedRemaining != null)) {
                item {
                    UnlockCard(state, onPin = { pinDialog = PinMode.VERIFY }, vm = vm)
                }
            }

            item { SectionHeader(stringResource(R.string.unlock_method)) }
            item {
                FrostCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                    MethodRow(
                        title = stringResource(R.string.method_delay),
                        body = stringResource(R.string.method_delay_body, s.strictDelayMinutes),
                        selected = s.strictMethod == StrictUnlockMethod.DELAY,
                        enabled = !state.locked,
                    ) { vm.setMethod(StrictUnlockMethod.DELAY) }
                    MethodRow(
                        title = stringResource(R.string.method_pin),
                        body = stringResource(if (state.hasPin) R.string.method_pin_body_set else R.string.method_pin_body),
                        selected = s.strictMethod == StrictUnlockMethod.PIN,
                        enabled = !state.locked,
                    ) { if (state.hasPin) vm.setMethod(StrictUnlockMethod.PIN) else pinDialog = PinMode.SET }
                    MethodRow(
                        title = stringResource(R.string.method_partner),
                        body = stringResource(R.string.method_partner_body),
                        selected = s.strictMethod == StrictUnlockMethod.PARTNER,
                        enabled = state.partnerAvailable && !state.locked,
                    ) { vm.setMethod(StrictUnlockMethod.PARTNER) }
                }
            }
            if (s.strictMethod == StrictUnlockMethod.DELAY) {
                item {
                    var minutes by remember(s.strictDelayMinutes) { mutableFloatStateOf(s.strictDelayMinutes.toFloat()) }
                    FrostCard {
                        Text(stringResource(R.string.delay_length, minutes.roundToInt()), style = MaterialTheme.typography.titleSmall)
                        Slider(minutes, { minutes = it.roundToInt().toFloat() }, valueRange = 1f..60f, onValueChangeFinished = { vm.setDelayMinutes(minutes.roundToInt()) })
                        Text(stringResource(R.string.delay_length_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.hasPin) {
                item {
                    TextButton(onClick = { pinDialog = PinMode.SET }, enabled = !state.locked) { Text(stringResource(R.string.change_pin)) }
                }
            }

            item { SectionHeader(stringResource(R.string.uninstall_protection)) }
            item {
                FrostCard {
                    Text(stringResource(R.string.uninstall_protection_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    if (state.adminActive) {
                        Text(stringResource(R.string.admin_active), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    } else {
                        FilledTonalButton(onClick = {
                            try {
                                adminLauncher.launch(PermissionHealthChecker.fixIntent(context, HealthItem.DEVICE_ADMIN))
                            } catch (_: ActivityNotFoundException) { }
                        }) { Text(stringResource(R.string.activate_admin)) }
                    }
                }
            }

            if (state.log.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.bypass_log)) }
                items(state.log, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Icon(
                            if (e.success) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null,
                            tint = if (e.success) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("${e.method.lowercase().replace('_', ' ')} · ${e.detail.replace('_', ' ')}", style = MaterialTheme.typography.bodyMedium)
                            Text(Format.dayTime(Instant.ofEpochMilli(e.timestamp), state.now, ZoneId.systemDefault()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    when (val mode = pinDialog) {
        PinMode.SET, PinMode.SET_AND_ENABLE -> PinDialog(
            title = stringResource(if (state.hasPin) R.string.change_pin else R.string.set_pin),
            confirmTwice = true,
            onDismiss = { pinDialog = null },
            onSubmit = { pin ->
                vm.setPinAndUse(pin, enable = mode == PinMode.SET_AND_ENABLE)
                pinDialog = null
            },
        )
        PinMode.VERIFY -> PinDialog(
            title = stringResource(R.string.enter_pin),
            confirmTwice = false,
            onDismiss = { pinDialog = null },
            onSubmit = vm::verifyPin,
        )
        null -> Unit
    }
}

private enum class PinMode { SET, SET_AND_ENABLE, VERIFY }

@Composable
private fun UnlockCard(state: StrictUiState, onPin: () -> Unit, vm: StrictViewModel) {
    val s = state.settings
    FrostCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        val remaining = state.unlockedRemaining
        if (remaining != null) {
            Text(stringResource(R.string.unlocked_for), style = MaterialTheme.typography.titleMedium)
            Text(Format.countdown(remaining), style = MaterialTheme.typography.displaySmall.merge(TabularNumbers))
            Text(stringResource(R.string.unlocked_hint), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = vm::relock) { Text(stringResource(R.string.lock_now)) }
            return@FrostCard
        }
        Text(stringResource(R.string.unlock_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        when (s.strictMethod) {
            StrictUnlockMethod.PIN -> Button(onClick = onPin) { Text(stringResource(R.string.enter_pin)) }
            StrictUnlockMethod.PARTNER -> Button(onClick = vm::requestPartner) { Text(stringResource(R.string.ask_partner)) }
            StrictUnlockMethod.DELAY -> when (val d = state.delay) {
                DelayUnlockState.Idle, DelayUnlockState.Expired -> {
                    if (d == DelayUnlockState.Expired) {
                        Text(stringResource(R.string.delay_expired), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(onClick = vm::requestDelay) { Text(stringResource(R.string.request_unlock, s.strictDelayMinutes)) }
                }
                is DelayUnlockState.Waiting -> {
                    Text(stringResource(R.string.delay_waiting), style = MaterialTheme.typography.bodyMedium)
                    Text(Format.countdown(Duration.between(state.now, d.readyAt)), style = MaterialTheme.typography.displaySmall.merge(TabularNumbers))
                    TextButton(onClick = vm::cancelDelay) { Text(stringResource(R.string.never_mind)) }
                }
                is DelayUnlockState.Ready -> {
                    Text(stringResource(R.string.delay_ready, Format.countdown(Duration.between(state.now, d.expiresAt))), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::confirmDelay) { Text(stringResource(R.string.confirm_unlock)) }
                        TextButton(onClick = vm::cancelDelay) { Text(stringResource(R.string.stay_locked)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MethodRow(title: String, body: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PinDialog(title: String, confirmTwice: Boolean, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = PinHasher.isValidPin(pin) && (!confirmTwice || pin == confirm)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                    label = { Text(stringResource(R.string.pin)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (confirmTwice) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it.filter(Char::isDigit).take(12) },
                        label = { Text(stringResource(R.string.confirm_pin)) },
                        singleLine = true,
                        isError = confirm.isNotEmpty() && confirm != pin,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    Text(stringResource(R.string.pin_rules), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(pin) }, enabled = valid) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
