package com.freezr.app.ui.freeze

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.freezr.app.R
import com.freezr.app.domain.model.FreezeReason
import com.freezr.app.ui.components.AppIcon
import com.freezr.app.ui.components.Format
import com.freezr.app.ui.theme.FreezrThemeExtras
import com.freezr.app.ui.theme.TabularNumbers
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Everything the block screen needs. Built from a Decision.Frozen; holds no blocking logic itself. */
data class FreezeScreenModel(
    val packageName: String,
    val appLabel: String,
    val reason: FreezeReason,
    val ruleName: String,
    val unfreezeAt: Instant?,
    val canEmergencyUnlock: Boolean,
    val passesRemaining: Int,
    val blockedDomain: String? = null,
)

fun FreezeReason.labelRes(): Int = when (this) {
    FreezeReason.QUICK_FREEZE -> R.string.reason_focus
    FreezeReason.SCHEDULE -> R.string.reason_schedule
    FreezeReason.USAGE_LIMIT -> R.string.reason_limit
    FreezeReason.LOCATION -> R.string.reason_location
    FreezeReason.WIFI -> R.string.reason_wifi
    FreezeReason.WEBSITE -> R.string.reason_website
    FreezeReason.IN_APP -> R.string.reason_in_app
}

@Composable
fun FreezeContent(
    model: FreezeScreenModel,
    zone: ZoneId,
    nowProvider: () -> Instant,
    onGoHome: () -> Unit,
    onEmergency: () -> Unit,
    onExpired: () -> Unit,
) {
    var now by remember { mutableStateOf(nowProvider()) }
    LaunchedEffect(model.unfreezeAt) {
        while (true) {
            now = nowProvider()
            val end = model.unfreezeAt
            if (end != null && !now.isBefore(end)) {
                onExpired()
                break
            }
            delay(1_000)
        }
    }
    val flake = rememberInfiniteTransition(label = "flake")
    val spin by flake.animateFloat(0f, 360f, infiniteRepeatable(tween(40_000, easing = LinearEasing)), label = "spin")
    val glow by flake.animateFloat(0.10f, 0.22f, infiniteRepeatable(tween(3_000), RepeatMode.Reverse), label = "glow")

    Box(
        Modifier
            .fillMaxSize()
            .background(FreezrThemeExtras.current.frozenGradient),
    ) {
        Icon(
            Icons.Rounded.AcUnit,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 24.dp)
                .size(320.dp)
                .rotate(spin)
                .alpha(glow),
        )
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = RoundedCornerShape(28.dp), color = Color.White.copy(alpha = 0.18f)) {
                Box(Modifier.padding(14.dp)) {
                    if (model.blockedDomain == null) {
                        AppIcon(model.packageName, size = 72.dp)
                    } else {
                        Icon(Icons.Rounded.AcUnit, contentDescription = null, tint = Color.White, modifier = Modifier.size(72.dp))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                model.blockedDomain ?: model.appLabel,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(if (model.blockedDomain != null) R.string.site_is_frozen else R.string.app_is_frozen),
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.16f)) {
                val reasonLabel = stringResource(model.reason.labelRes())
                Text(
                    if (model.ruleName.startsWith(reasonLabel, ignoreCase = true)) model.ruleName
                    else stringResource(R.string.reason_line, reasonLabel, model.ruleName),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(36.dp))
            val end = model.unfreezeAt
            if (end != null) {
                Text(
                    stringResource(R.string.unfreezes_at, Format.dayTime(end, now, zone)),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    Format.countdown(Duration.between(now, end)),
                    color = Color.White,
                    style = MaterialTheme.typography.displayMedium.merge(TabularNumbers),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                Text(
                    stringResource(R.string.unfreezes_when_rule_ends),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = onGoHome,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1E1B4B)),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .height(56.dp),
            ) {
                Text(stringResource(if (model.blockedDomain != null) R.string.go_back else R.string.go_home), style = MaterialTheme.typography.titleMedium)
            }
            if (model.canEmergencyUnlock) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onEmergency) {
                    Text(
                        pluralStringResource(R.plurals.emergency_unlock_left, model.passesRemaining, model.passesRemaining),
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.freeze_footer),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Compact cover for a frozen app shown in picture-in-picture: hides content without trapping the user. */
@Composable
fun PipCover() {
    Box(
        Modifier
            .fillMaxSize()
            .background(FreezrThemeExtras.current.frozenGradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.AcUnit, contentDescription = stringResource(R.string.app_is_frozen), tint = Color.White, modifier = Modifier.size(40.dp))
    }
}
