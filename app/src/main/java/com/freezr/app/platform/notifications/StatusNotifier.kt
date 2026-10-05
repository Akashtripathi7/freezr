package com.freezr.app.platform.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.freezr.app.R
import com.freezr.app.data.repo.StrictGuard
import com.freezr.app.data.repo.StrictModeLockedException
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.domain.engine.FreezeDecisionEngine
import com.freezr.app.domain.model.EngineContext
import com.freezr.app.domain.time.TimeSource
import com.freezr.app.platform.PlatformEntryPoint
import com.freezr.app.platform.StateChangeListener
import com.freezr.app.platform.goAsyncWithTimeout
import com.freezr.app.ui.components.Format
import dagger.hilt.android.EntryPointAccessors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent status notification with the master-toggle action. When Strict Mode is locked the
 * action opens the Strict screen directly (an Activity PendingIntent, no trampoline) instead of
 * toggling.
 */
@Singleton
class StatusNotifier @Inject constructor(
    private val context: Context,
    private val notifications: FreezrNotifications,
    private val settings: SettingsRepository,
    private val guard: StrictGuard,
    private val time: TimeSource,
) : StateChangeListener {

    override suspend fun onStateChanged(ctx: EngineContext) {
        if (!settings.current().statusNotificationEnabled) {
            notifications.cancelStatus()
            return
        }
        val now = time.now()
        val zone = time.zone()
        val active = FreezeDecisionEngine.activeFreezes(now, zone, ctx)
        val frozen = active.flatMap { it.packages }.toSet().size
        val title = when {
            !ctx.masterEnabled -> context.getString(R.string.status_paused)
            frozen > 0 -> context.resources.getQuantityString(R.plurals.status_frozen, frozen, frozen)
            else -> context.getString(R.string.status_protecting)
        }
        val until = active.mapNotNull { it.until }.maxOrNull()
        val next = FreezeDecisionEngine.nextTransitionAfter(now, zone, ctx)
        val text = when {
            !ctx.masterEnabled -> context.getString(R.string.status_paused_text)
            until != null -> context.getString(R.string.hero_until, Format.dayTime(until, now, zone))
            next != null -> context.getString(R.string.hero_next_change, Format.dayTime(next, now, zone))
            else -> context.getString(R.string.hero_idle_sub)
        }
        val locked = guard.isLocked()
        val action = if (locked && ctx.masterEnabled) {
            notifications.openApp(FreezrNotifications.ROUTE_STRICT)
        } else {
            PendingIntent.getBroadcast(
                context, 0,
                Intent(context, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_TOGGLE_MASTER),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        notifications.postStatus(title, text, ctx.masterEnabled, action)
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE_MASTER) return
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, PlatformEntryPoint::class.java)
        goAsyncWithTimeout {
            val enabled = ep.settings().current().masterEnabled
            try {
                ep.controls().setMasterEnabled(!enabled)
            } catch (_: StrictModeLockedException) {
                // Refused: re-post so the notification shows the locked state.
                ep.coordinator().replan()
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_MASTER = "com.freezr.app.action.TOGGLE_MASTER"
    }
}
