package com.freezr.app.platform.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.freezr.app.domain.planning.AlarmPlan
import com.freezr.app.domain.planning.PreFreezeAlarm
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies an [AlarmPlan] to AlarmManager. Exactly two alarms exist (transition + pre-freeze); each
 * is replaced in place (FLAG_UPDATE_CURRENT), so re-planning is idempotent. Uses
 * setExactAndAllowWhileIdle so transitions fire in Doze; falls back to an inexact while-idle alarm
 * if the exact-alarm permission was revoked (surfaced in the health card).
 */
@Singleton
class AlarmScheduler @Inject constructor(
    private val context: Context,
    private val directBootStore: DirectBootStore,
) {
    private val am get() = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am?.canScheduleExactAlarms() == true

    fun apply(plan: AlarmPlan) {
        val transition = plan.transitionAt
        if (transition != null) set(transition, transitionIntent()) else cancel(transitionIntent())

        val pre = plan.preFreeze
        if (pre != null) set(pre.notifyAt, preFreezeIntent(pre)) else cancel(preFreezeIntent(null))

        directBootStore.savePlan(transition, pre)
    }

    /** Re-arm from the device-protected copy (before first unlock after boot). */
    fun applyFromDirectBoot(now: Instant) {
        val (transition, pre) = directBootStore.loadPlan()
        transition?.takeIf { it.isAfter(now) }?.let { set(it, transitionIntent()) }
        pre?.takeIf { it.notifyAt.isAfter(now) }?.let { set(it.notifyAt, preFreezeIntent(it)) }
    }

    /** True if the transition alarm is currently registered with AlarmManager. */
    fun hasTransitionAlarm(): Boolean = PendingIntent.getBroadcast(
        context, REQ_TRANSITION, baseIntent(AlarmReceiver.ACTION_TRANSITION),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    ) != null

    private fun set(at: Instant, pi: PendingIntent) {
        val manager = am ?: return
        val ms = at.toEpochMilli()
        try {
            if (canScheduleExact()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            }
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
    }

    private fun cancel(pi: PendingIntent) {
        am?.cancel(pi)
        pi.cancel()
    }

    private fun baseIntent(action: String) = Intent(context, AlarmReceiver::class.java).setAction(action)

    private fun transitionIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, REQ_TRANSITION, baseIntent(AlarmReceiver.ACTION_TRANSITION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun preFreezeIntent(pre: PreFreezeAlarm?): PendingIntent {
        val intent = baseIntent(AlarmReceiver.ACTION_PRE_FREEZE)
        if (pre != null) {
            intent.putExtra(AlarmReceiver.EXTRA_FREEZE_AT, pre.freezeAt.toEpochMilli())
            intent.putExtra(AlarmReceiver.EXTRA_PACKAGES, pre.packages.toTypedArray())
            intent.putExtra(AlarmReceiver.EXTRA_NAMES, pre.scheduleNames.toTypedArray())
        }
        return PendingIntent.getBroadcast(
            context, REQ_PRE_FREEZE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val REQ_TRANSITION = 1001
        const val REQ_PRE_FREEZE = 1002
    }
}
