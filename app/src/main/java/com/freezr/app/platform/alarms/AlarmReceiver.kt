package com.freezr.app.platform.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.freezr.app.platform.PlatformEntryPoint
import com.freezr.app.platform.goAsyncWithTimeout
import dagger.hilt.android.EntryPointAccessors
import java.time.Instant

/**
 * Fires at each decision transition and at pre-freeze notification times. Direct-boot aware: if the
 * user has not unlocked yet it only re-arms from device-protected storage (Room is unavailable).
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val um = context.getSystemService(UserManager::class.java)
        if (um != null && !um.isUserUnlocked) {
            AlarmScheduler(context, DirectBootStore(context)).applyFromDirectBoot(Instant.now())
            return
        }
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, PlatformEntryPoint::class.java)
        goAsyncWithTimeout {
            when (intent.action) {
                ACTION_PRE_FREEZE -> ep.notifications().showPreFreeze(
                    freezeAt = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_FREEZE_AT, 0)),
                    packages = intent.getStringArrayExtra(EXTRA_PACKAGES)?.toSet().orEmpty(),
                    scheduleNames = intent.getStringArrayExtra(EXTRA_NAMES)?.toList().orEmpty(),
                )
                ACTION_TRANSITION -> ep.usageTracker().reconcile()
            }
            ep.coordinator().replan()
        }
    }

    companion object {
        const val ACTION_TRANSITION = "com.freezr.app.action.TRANSITION"
        const val ACTION_PRE_FREEZE = "com.freezr.app.action.PRE_FREEZE"
        const val EXTRA_FREEZE_AT = "freeze_at"
        const val EXTRA_PACKAGES = "packages"
        const val EXTRA_NAMES = "names"
    }
}
