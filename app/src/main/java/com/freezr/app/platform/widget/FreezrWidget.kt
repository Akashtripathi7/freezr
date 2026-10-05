package com.freezr.app.platform.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.freezr.app.R
import com.freezr.app.data.repo.NothingToFreezeException
import com.freezr.app.domain.engine.FreezeDecisionEngine
import com.freezr.app.domain.model.EngineContext
import com.freezr.app.platform.PlatformEntryPoint
import com.freezr.app.platform.StateChangeListener
import com.freezr.app.ui.MainActivity
import com.freezr.app.ui.components.Format
import dagger.hilt.android.EntryPointAccessors
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

private data class WidgetModel(
    val master: Boolean,
    val frozenCount: Int,
    val focusUntil: String?,
    val subtitle: String,
)

/**
 * Home-screen widget: protection state plus one-tap Focus 15m / 30m / 1h. Shows absolute end times
 * ("until 10:45") so it never needs per-minute refreshes; it is pushed an update on every re-plan.
 */
class FreezrWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, PlatformEntryPoint::class.java)
        val ctx = ep.stateRepo().await()
        val now = ep.time().now()
        val zone = ep.time().zone()
        val active = FreezeDecisionEngine.activeFreezes(now, zone, ctx)
        val qf = ctx.quickFreeze?.takeIf { now.isBefore(it.endsAt) }
        val model = WidgetModel(
            master = ctx.masterEnabled,
            frozenCount = active.flatMap { it.packages }.toSet().size,
            focusUntil = qf?.let { Format.time(it.endsAt, zone) },
            subtitle = when {
                !ctx.masterEnabled -> context.getString(R.string.protection_off)
                qf != null -> context.getString(R.string.widget_focus_until, Format.time(qf.endsAt, zone))
                active.isNotEmpty() -> context.resources.getQuantityString(R.plurals.hero_frozen, active.flatMap { it.packages }.toSet().size, active.flatMap { it.packages }.toSet().size)
                else -> context.getString(R.string.hero_protected)
            },
        )
        provideContent { Content(context, model) }
    }

    @Composable
    private fun Content(context: Context, m: WidgetModel) {
        val bg = if (m.frozenCount > 0 || m.focusUntil != null) Color(0xFF312E81) else Color(0xFF0F2A3D)
        Column(
            GlanceModifier
                .fillMaxSize()
                .cornerRadius(24.dp)
                .background(ColorProvider(bg))
                .padding(16.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.ic_stat_freezr), contentDescription = null, modifier = GlanceModifier.size(18.dp))
                Spacer(GlanceModifier.width(8.dp))
                Text(context.getString(R.string.app_name), style = TextStyle(color = ColorProvider(Color(0xFFE0F2FE)), fontWeight = FontWeight.Medium, fontSize = 13.sp))
            }
            Spacer(GlanceModifier.height(8.dp))
            Text(m.subtitle, style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Bold, fontSize = 18.sp), maxLines = 2)
            Spacer(GlanceModifier.defaultWeight())
            if (m.focusUntil == null) {
                Row(GlanceModifier.fillMaxWidth()) {
                    listOf(15, 30, 60).forEachIndexed { i, minutes ->
                        if (i > 0) Spacer(GlanceModifier.width(8.dp))
                        FocusChip(context, minutes)
                    }
                }
            }
        }
    }

    @Composable
    private fun FocusChip(context: Context, minutes: Int) {
        val label = if (minutes < 60) "${minutes}m" else "${minutes / 60}h"
        Box(
            GlanceModifier
                .cornerRadius(16.dp)
                .background(ColorProvider(Color(0x33FFFFFF)))
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .semantics { contentDescription = context.getString(R.string.widget_focus_cd, label) }
                .clickable(actionRunCallback<StartFocusAction>(actionParametersOf(StartFocusAction.MINUTES to minutes))),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Bold))
        }
    }
}

class StartFocusAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val minutes = parameters[MINUTES] ?: 30
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, PlatformEntryPoint::class.java)
        try {
            ep.controls().startQuickFreeze(Duration.ofMinutes(minutes.toLong()))
        } catch (_: NothingToFreezeException) {
            context.startActivity(MainActivity.routeIntent(context, "apps"))
        }
        FreezrWidget().updateAll(context)
    }

    companion object {
        val MINUTES = ActionParameters.Key<Int>("minutes")
    }
}

class FreezrWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FreezrWidget()
}

@Singleton
class WidgetUpdater @Inject constructor(private val context: Context) : StateChangeListener {
    override suspend fun onStateChanged(ctx: EngineContext) {
        runCatching { FreezrWidget().updateAll(context) }
    }
}
