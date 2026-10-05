package com.freezr.app.platform.alarms

import android.content.Context
import android.content.SharedPreferences
import com.freezr.app.domain.planning.PreFreezeAlarm
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The minimal state needed before the user first unlocks after a reboot, kept in
 * device-protected storage: the planned alarm instants and the pre-freeze payload.
 * Everything else (Room, DataStore) lives in credential-protected storage and is
 * re-read on BOOT_COMPLETED.
 */
@Singleton
class DirectBootStore @Inject constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences("direct_boot", Context.MODE_PRIVATE)

    fun savePlan(transition: Instant?, pre: PreFreezeAlarm?) {
        prefs.edit().apply {
            if (transition != null) putLong(K_TRANSITION, transition.toEpochMilli()) else remove(K_TRANSITION)
            if (pre != null) {
                putLong(K_PRE_NOTIFY, pre.notifyAt.toEpochMilli())
                putLong(K_PRE_FREEZE, pre.freezeAt.toEpochMilli())
                putStringSet(K_PRE_PACKAGES, pre.packages)
                putString(K_PRE_NAMES, pre.scheduleNames.joinToString("\n"))
            } else {
                remove(K_PRE_NOTIFY); remove(K_PRE_FREEZE); remove(K_PRE_PACKAGES); remove(K_PRE_NAMES)
            }
        }.apply()
    }

    fun loadPlan(): Pair<Instant?, PreFreezeAlarm?> {
        val transition = prefs.getLong(K_TRANSITION, -1).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        val notify = prefs.getLong(K_PRE_NOTIFY, -1)
        val pre = if (notify > 0) {
            PreFreezeAlarm(
                notifyAt = Instant.ofEpochMilli(notify),
                freezeAt = Instant.ofEpochMilli(prefs.getLong(K_PRE_FREEZE, notify)),
                scheduleNames = prefs.getString(K_PRE_NAMES, "").orEmpty().split("\n").filter { it.isNotEmpty() },
                packages = prefs.getStringSet(K_PRE_PACKAGES, emptySet()).orEmpty(),
            )
        } else {
            null
        }
        return transition to pre
    }

    private companion object {
        const val K_TRANSITION = "transition_at"
        const val K_PRE_NOTIFY = "pre_notify_at"
        const val K_PRE_FREEZE = "pre_freeze_at"
        const val K_PRE_PACKAGES = "pre_packages"
        const val K_PRE_NAMES = "pre_names"
    }
}
