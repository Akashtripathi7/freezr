package com.freezr.app.ui.insights

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freezr.app.R
import com.freezr.app.ui.components.AppIcon
import com.freezr.app.ui.components.Format
import com.freezr.app.ui.components.FrostCard
import com.freezr.app.ui.components.IconBadge
import com.freezr.app.ui.components.LocalAppCatalog
import com.freezr.app.ui.components.SectionHeader
import com.freezr.app.ui.theme.FreezrThemeExtras
import java.time.Duration
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun InsightsScreen(vm: InsightsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_insights)) }) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    InsightsRange.entries.forEachIndexed { i, r ->
                        SegmentedButton(
                            selected = state.range == r,
                            onClick = { vm.setRange(r) },
                            shape = SegmentedButtonDefaults.itemShape(i, InsightsRange.entries.size),
                        ) { Text(stringResource(if (r == InsightsRange.DAY) R.string.today else R.string.this_week)) }
                    }
                }
            }
            if (!state.hasUsageAccess) {
                item {
                    FrostCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                        Text(stringResource(R.string.insights_need_usage), color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text(stringResource(R.string.fix)) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(Icons.Rounded.LocalFireDepartment, pluralStringResource(R.plurals.days, state.streak, state.streak), stringResource(R.string.streak_label, state.bestStreak), Modifier.weight(1f), FreezrThemeExtras.current.warning)
                    StatTile(Icons.Rounded.AcUnit, state.attemptCount.toString(), stringResource(R.string.attempts_blocked), Modifier.weight(1f), MaterialTheme.colorScheme.primary)
                    StatTile(Icons.Rounded.Savings, Format.duration(state.timeSaved), stringResource(R.string.time_saved_est), Modifier.weight(1f), FreezrThemeExtras.current.success)
                }
            }
            item {
                FrostCard {
                    Text(stringResource(R.string.screen_time), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Format.duration(state.total), style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(16.dp))
                    WeekChart(state.week)
                }
            }
            if (state.topApps.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.most_used)) }
                item {
                    FrostCard {
                        val max = state.topApps.maxOf { it.time.toMillis() }.coerceAtLeast(1)
                        state.topApps.forEachIndexed { i, a ->
                            UsageBarRow(a.packageName, a.time, a.time.toMillis().toFloat() / max, FreezrThemeExtras.current.chart[i % FreezrThemeExtras.current.chart.size])
                        }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.most_attempted)) }
            if (state.attempts.isEmpty()) {
                item { Text(stringResource(R.string.no_attempts), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.attempts, key = { it.packageName }) { a -> AttemptRow(a) }
            }
            item {
                Text(stringResource(R.string.insights_local_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatTile(icon: ImageVector, value: String, label: String, modifier: Modifier, tint: Color) {
    FrostCard(modifier = modifier, contentPadding = PaddingValues(14.dp)) {
        IconBadge(icon, tint, size = 32.dp)
        Spacer(Modifier.height(10.dp))
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
private fun WeekChart(days: List<DayBar>) {
    if (days.isEmpty()) return
    val max = days.maxOf { it.total.toMillis() }.coerceAtLeast(1)
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.surfaceVariant
    val desc = days.joinToString { "${it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${Format.duration(it.total)}" }
    Column(Modifier.semantics { contentDescription = desc }) {
        val fractions = days.map { day ->
            val target = day.total.toMillis().toFloat() / max
            animateFloatAsState(target, label = "bar").value
        }
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val gap = 10.dp.toPx()
            val w = (size.width - gap * (days.size - 1)) / days.size
            fractions.forEachIndexed { i, f ->
                val x = i * (w + gap)
                drawRoundRect(muted, Offset(x, 0f), Size(w, size.height), CornerRadius(8.dp.toPx()))
                val h = size.height * f.coerceAtLeast(0.02f)
                drawRoundRect(if (i == days.lastIndex) primary else primary.copy(alpha = 0.55f), Offset(x, size.height - h), Size(w, h), CornerRadius(8.dp.toPx()))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            days.forEach { d ->
                Text(
                    d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun UsageBarRow(pkg: String, time: Duration, fraction: Float, color: Color) {
    val catalog = LocalAppCatalog.current
    val animated by animateFloatAsState(fraction, label = "usage")
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(pkg, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(catalog.label(pkg), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                Text(Format.duration(time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(animated.coerceIn(0.01f, 1f)).height(6.dp).clip(RoundedCornerShape(50)).background(color))
            }
        }
    }
}

@Composable
private fun AttemptRow(a: AppAttempts) {
    val catalog = LocalAppCatalog.current
    FrostCard(contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(a.packageName, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Text(catalog.label(a.packageName), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(pluralStringResource(R.plurals.attempts, a.count, a.count), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
