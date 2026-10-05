package com.freezr.app.platform.receivers

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.freezr.app.data.repo.AppSelectionRepository
import com.freezr.app.platform.PlatformEntryPoint
import com.freezr.app.platform.alarms.AlarmScheduler
import com.freezr.app.platform.alarms.DirectBootStore
import com.freezr.app.platform.apps.AppIconCache
import com.freezr.app.platform.goAsyncWithTimeout
import com.freezr.app.platform.health.PermissionHealthChecker
import com.freezr.app.platform.work.WatchdogWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant

/**
 * Rebuilds everything from disk after events that invalidate alarms or wall-clock maths:
 * boot, app update, time / timezone change, exact-alarm permission change. (DST needs no broadcast:
 * transitions are planned as instants using the zone rules, so they already include DST.)
 */
class SystemEventReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun health(): PermissionHealthChecker
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return
        val app = context.applicationContext
        val um = app.getSystemService(UserManager::class.java)
        if (um != null && !um.isUserUnlocked) return // LockedBootReceiver covers this phase.
        val ep = EntryPointAccessors.fromApplication(app, PlatformEntryPoint::class.java)
        goAsyncWithTimeout {
            ep.coordinator().reconcileAll(action.substringAfterLast('.'))
            WatchdogWorker.schedule(app)
            if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                // Detect a disabled / not-yet-rebound accessibility service right after boot.
                val health = EntryPointAccessors.fromApplication(app, Deps::class.java).health()
                health.resetAlertThrottle()
                health.alertIfBroken(health.check())
            }
        }
    }

    companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}

/** Before first unlock: only device-protected storage is readable, so re-arm alarms from there. */
class LockedBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        AlarmScheduler(context, DirectBootStore(context)).applyFromDirectBoot(Instant.now())
    }
}

/** Forget an uninstalled app in the "my apps" selection and drop its cached icon. */
class PackageRemovedReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun selection(): AppSelectionRepository
        fun icons(): AppIconCache
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        goAsyncWithTimeout {
            deps.selection().onPackageRemoved(pkg)
            deps.icons().evict(pkg)
        }
    }
}
