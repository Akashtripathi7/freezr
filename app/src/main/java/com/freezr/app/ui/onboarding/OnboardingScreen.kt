package com.freezr.app.ui.onboarding

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freezr.app.R
import com.freezr.app.platform.health.PermissionHealthChecker
import com.freezr.app.ui.components.AppPickerDialog
import com.freezr.app.ui.components.AppIconStack
import com.freezr.app.ui.theme.FreezrThemeExtras

@Composable
fun OnboardingScreen(onFinished: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    BackHandler(enabled = state.step != OnboardingStep.WELCOME) { vm.back() }

    fun openFix(step: OnboardingStep) {
        val item = step.item ?: return
        if (step == OnboardingStep.NOTIFICATIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        try {
            context.startActivity(PermissionHealthChecker.fixIntent(context, item).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            vm.next()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        LinearProgressIndicator(
            progress = { (state.step.ordinal + 1f) / OnboardingStep.entries.size },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .clip(RoundedCornerShape(50)),
        )
        AnimatedContent(
            state.step,
            label = "onboarding",
            transitionSpec = { (fadeIn() + slideInHorizontally { it / 6 }) togetherWith fadeOut() },
            modifier = Modifier.weight(1f),
        ) { step ->
            val granted = state.granted(step.item)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                when (step) {
                    OnboardingStep.WELCOME -> Welcome()
                    OnboardingStep.PRIVACY -> Page(Icons.Rounded.PhonelinkLock, R.string.ob_privacy_title, R.string.ob_privacy_body)
                    OnboardingStep.ACCESSIBILITY -> Disclosure(
                        Icons.Rounded.AccessibilityNew, R.string.ob_a11y_title, R.string.ob_a11y_body,
                        listOf(R.string.ob_a11y_point_1, R.string.ob_a11y_point_2, R.string.ob_a11y_point_3, R.string.ob_a11y_point_4),
                        granted,
                    )
                    OnboardingStep.USAGE -> Disclosure(
                        Icons.Rounded.Insights, R.string.ob_usage_title, R.string.ob_usage_body,
                        listOf(R.string.ob_usage_point_1, R.string.ob_usage_point_2, R.string.ob_usage_point_3),
                        granted,
                    )
                    OnboardingStep.NOTIFICATIONS -> Page(Icons.Rounded.NotificationsActive, R.string.ob_notif_title, R.string.ob_notif_body, granted)
                    OnboardingStep.ALARMS -> Page(Icons.Rounded.Alarm, R.string.ob_alarm_title, R.string.ob_alarm_body, granted)
                    OnboardingStep.BATTERY -> Page(Icons.Rounded.BatteryChargingFull, R.string.ob_battery_title, R.string.ob_battery_body, granted)
                    OnboardingStep.OPTIONAL -> Page(Icons.Rounded.Tune, R.string.ob_optional_title, R.string.ob_optional_body)
                    OnboardingStep.APPS -> {
                        Page(Icons.Rounded.Apps, R.string.ob_apps_title, R.string.ob_apps_body)
                        Spacer(Modifier.height(16.dp))
                        if (state.selected.isNotEmpty()) AppIconStack(state.selected, max = 8, size = 36.dp)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { picking = true }) {
                            Text(
                                if (state.selected.isEmpty()) stringResource(R.string.pick_apps)
                                else pluralStringResource(R.plurals.apps_selected, state.selected.size, state.selected.size),
                            )
                        }
                    }
                }
            }
        }
        // Bottom actions
        val step = state.step
        val granted = state.granted(step.item)
        Column(Modifier.padding(24.dp)) {
            when {
                step == OnboardingStep.APPS -> Button(onClick = { vm.finish(onFinished) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(stringResource(R.string.ob_finish))
                }
                step.item != null && !granted -> {
                    Button(onClick = { openFix(step) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(
                            stringResource(
                                when (step) {
                                    OnboardingStep.ACCESSIBILITY, OnboardingStep.USAGE -> R.string.ob_agree_open
                                    OnboardingStep.NOTIFICATIONS -> R.string.ob_allow
                                    else -> R.string.ob_open_settings
                                },
                            ),
                        )
                    }
                    TextButton(onClick = vm::next, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.not_now)) }
                }
                else -> Button(onClick = vm::next, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(stringResource(if (step == OnboardingStep.WELCOME) R.string.ob_get_started else R.string.ob_continue))
                }
            }
        }
    }

    if (picking) {
        AppPickerDialog(
            title = stringResource(R.string.ob_apps_title),
            apps = state.apps,
            initial = state.selected,
            groups = emptyList(),
            myApps = emptySet(),
            onDismiss = { picking = false },
            onConfirm = { vm.setSelected(it); picking = false },
        )
    }
}

@Composable
private fun Welcome() {
    Spacer(Modifier.height(24.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(FreezrThemeExtras.current.frozenGradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.AcUnit, contentDescription = null, tint = Color.White, modifier = Modifier.size(96.dp))
    }
    Spacer(Modifier.height(32.dp))
    Text(stringResource(R.string.ob_welcome_title), style = MaterialTheme.typography.displaySmall)
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.ob_welcome_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Page(icon: ImageVector, title: Int, body: Int, granted: Boolean = false) {
    Spacer(Modifier.height(32.dp))
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(20.dp).size(40.dp))
    }
    Spacer(Modifier.height(24.dp))
    Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (granted) GrantedRow()
}

/** Play-policy "prominent disclosure": shown in-app, before the system settings screen opens. */
@Composable
private fun Disclosure(icon: ImageVector, title: Int, body: Int, points: List<Int>, granted: Boolean) {
    Page(icon, title, body)
    Spacer(Modifier.height(20.dp))
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ob_disclosure_header), style = MaterialTheme.typography.titleSmall)
            points.forEach { p ->
                Row {
                    Text("•", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(p), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if (granted) GrantedRow()
}

@Composable
private fun GrantedRow() {
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = FreezrThemeExtras.current.success)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.ob_granted), style = MaterialTheme.typography.titleSmall, color = FreezrThemeExtras.current.success)
    }
}
