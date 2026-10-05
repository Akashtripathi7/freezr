package com.freezr.app.platform.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.freezr.app.data.db.DailyUsageEntity
import com.freezr.app.data.db.EmergencyDao
import com.freezr.app.data.db.InsightsDao
import com.freezr.app.data.db.LimitDao
import com.freezr.app.data.db.QuickFreezeDao
import com.freezr.app.domain.time.TimeSource
import com.freezr.app.platform.EnforcementCoordinator
import com.freezr.app.platform.ReconcileStep
import com.freezr.app.platform.alarms.AlarmScheduler
import com.freezr.app.platform.health.PermissionHealthChecker
import com.freezr.app.platform.usage.UsageTracker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Retention pruning (90 days) and daily usage snapshots for long-range insights. */
@Singleton
class Housekeeping @Inject constructor(
    private val insights: InsightsDao,
    private val emergency: EmergencyDao,
    private val limits: LimitDao,
    private val quick: QuickFreezeDao,
    private val usage: UsageTracker,
    private val time: TimeSource,
) : ReconcileStep {
    override val name = "housekeeping"

    override suspend fun reconcile() {
        val now = time.now()
        val today = now.atZone(time.zone()).toLocalDate()
        val cutoff = now.minus(RETENTION).toEpochMilli()
        insights.pruneAttempts(cutoff)
        insights.pruneBypasses(cutoff)
        insights.pruneDailyUsage(today.minus(RETENTION.toDays(), java.time.temporal.ChronoUnit.DAYS).toString())
        emergency.pruneLog(cutoff)
        emergency.pruneExpired(now.toEpochMilli())
        limits.pruneBonuses(today.minusDays(7).toString())
        quick.get()?.let { if (it.endsAt <= now.toEpochMilli()) quick.clear() }

        // UsageStats keeps detailed events for a limited time; snapshot daily totals into Room.
        val rows = usage.dailyTotals(today.minusDays(6), today).flatMap { (day, perApp) ->
            perApp.map { (pkg, d) -> DailyUsageEntity(day.toString(), pkg, d.toMillis()) }
        }
        if (rows.isNotEmpty()) insights.upsertDailyUsage(rows)
    }

    companion object {
        val RETENTION: Duration = Duration.ofDays(90)
    }
}

/**
 * Periodic (15 min) watchdog. Doze-safe enforcement comes from exact alarms; this only verifies
 * that the alarm chain and the accessibility service are still alive and repairs/alerts if not.
 */
@HiltWorker
class WatchdogWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: EnforcementCoordinator,
    private val alarms: AlarmScheduler,
    private val health: PermissionHealthChecker,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Full reconcile re-arms alarms (covers a missing transition alarm), re-syncs geofences,
        // reconciles usage and prunes old data.
        coordinator.reconcileAll(if (alarms.hasTransitionAlarm()) "watchdog" else "watchdog_alarm_missing")
        health.alertIfBroken(health.check())
        return Result.success()
    }

    companion object {
        private const val NAME = "freezr_watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
