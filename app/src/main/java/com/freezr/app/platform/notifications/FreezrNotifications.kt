package com.freezr.app.platform.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.freezr.app.R
import com.freezr.app.domain.time.TimeSource
import com.freezr.app.platform.apps.InstalledAppsRepository
import com.freezr.app.ui.MainActivity
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All notifications. Every post goes through [canPost]: if the user denied notifications we
 * degrade silently (the in-app health card still shows the problem). We never bypass Do Not
 * Disturb: channels use normal importance and no DND-override flags.
 */
@Singleton
class FreezrNotifications @Inject constructor(
    private val context: Context,
    private val apps: InstalledAppsRepository,
    private val time: TimeSource,
) {
    private val nm = NotificationManagerCompat.from(context)

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CH_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.channel_status_desc)
                    setShowBadge(false)
                },
                NotificationChannel(CH_PRE_FREEZE, context.getString(R.string.channel_pre_freeze), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.channel_pre_freeze_desc)
                },
                NotificationChannel(CH_LIMITS, context.getString(R.string.channel_limits), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.channel_limits_desc)
                },
                NotificationChannel(CH_HEALTH, context.getString(R.string.channel_health), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_health_desc)
                },
            ),
        )
    }

    fun canPost(): Boolean {
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return true
    }

    fun showPreFreeze(freezeAt: Instant, packages: Set<String>, scheduleNames: List<String>) {
        if (packages.isEmpty() || !canPost()) return
        val minutes = Duration.between(time.now(), freezeAt).toMinutes().coerceAtLeast(1)
        val names = packages.map(apps::label).sorted()
        val subject = when (names.size) {
            1 -> names[0]
            2 -> context.getString(R.string.two_apps, names[0], names[1])
            else -> context.getString(R.string.many_apps, names[0], names.size - 1)
        }
        val title = context.resources.getQuantityString(R.plurals.pre_freeze_title, minutes.toInt(), subject, minutes.toInt())
        val text = context.getString(R.string.pre_freeze_text, scheduleNames.joinToString())
        post(
            ID_PRE_FREEZE,
            builder(CH_PRE_FREEZE)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setTimeoutAfter(Duration.between(time.now(), freezeAt).toMillis().coerceAtLeast(60_000))
                .build(),
        )
    }

    fun showLimitWarning(packageName: String, percent: Int, remaining: Duration) {
        if (!canPost()) return
        val label = apps.label(packageName)
        val title = if (percent >= 100) {
            context.getString(R.string.limit_reached_title, label)
        } else {
            context.getString(R.string.limit_warning_title, label, percent)
        }
        val text = if (percent >= 100) {
            context.getString(R.string.limit_reached_text)
        } else {
            context.resources.getQuantityString(R.plurals.limit_minutes_left, remaining.toMinutes().toInt(), remaining.toMinutes().toInt())
        }
        post(ID_LIMIT_BASE + (packageName.hashCode() and 0xFFF), builder(CH_LIMITS).setContentTitle(title).setContentText(text).build())
    }

    fun showHealthAlert(problems: List<String>) {
        if (problems.isEmpty() || !canPost()) return
        post(
            ID_HEALTH,
            builder(CH_HEALTH)
                .setContentTitle(context.getString(R.string.health_alert_title))
                .setContentText(problems.first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(problems.joinToString("\n• ", prefix = "• ")))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .addAction(0, context.getString(R.string.action_fix_now), openApp(ROUTE_HEALTH))
                .build(),
        )
    }

    fun cancelHealthAlert() = nm.cancel(ID_HEALTH)

    fun postStatus(title: String, text: String, masterEnabled: Boolean, toggle: PendingIntent) {
        if (!canPost()) return
        val actionLabel = context.getString(if (masterEnabled) R.string.action_pause else R.string.action_resume)
        post(
            ID_STATUS,
            builder(CH_STATUS)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, actionLabel, toggle)
                .build(),
        )
    }

    fun cancelStatus() = nm.cancel(ID_STATUS)

    fun openApp(route: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (route != null) intent.putExtra(MainActivity.EXTRA_ROUTE, route)
        return PendingIntent.getActivity(
            context, route.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun builder(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_freezr)
        .setColor(ContextCompat.getColor(context, R.color.brand_ice))
        .setContentIntent(openApp())
        .setAutoCancel(channel != CH_STATUS)

    @Suppress("MissingPermission") // guarded by canPost()
    private fun post(id: Int, n: android.app.Notification) {
        runCatching { nm.notify(id, n) }
    }

    companion object {
        const val CH_STATUS = "status"
        const val CH_PRE_FREEZE = "pre_freeze"
        const val CH_LIMITS = "limits"
        const val CH_HEALTH = "health"
        const val ID_STATUS = 1
        const val ID_PRE_FREEZE = 2
        const val ID_HEALTH = 3
        const val ID_LIMIT_BASE = 1000
        const val ROUTE_HEALTH = "health"
        const val ROUTE_STRICT = "strict"
    }
}
