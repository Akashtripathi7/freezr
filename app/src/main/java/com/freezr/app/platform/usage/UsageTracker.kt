package com.freezr.app.platform.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.freezr.app.data.repo.UsageSource
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.domain.engine.TimeWindows
import com.freezr.app.domain.time.TimeSource
import com.freezr.app.domain.usage.UsageAggregator
import com.freezr.app.domain.usage.UsageEventKind
import com.freezr.app.domain.usage.UsageEventRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-package foreground time for the current usage day.
 *
 * The base numbers always come from the system's UsageStats store (reconciled on service start,
 * screen-on, day reset and periodically), so they are correct after process death. While an app is
 * in the foreground, the accessibility service reports switches here and [liveUsage] adds the
 * not-yet-reconciled delta for the current app.
 */
@Singleton
class UsageTracker @Inject constructor(
    private val context: Context,
    private val time: TimeSource,
    private val settings: SettingsRepository,
) : UsageSource {

    private val _usage = MutableStateFlow<Map<String, Duration>>(emptyMap())
    override val usageToday: StateFlow<Map<String, Duration>> = _usage

    private val mutex = Mutex()
    @Volatile private var foreground: String? = null
    @Volatile private var accountedFrom: Instant = Instant.EPOCH
    @Volatile private var dayStart: Instant = Instant.EPOCH

    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Rebuild today's totals from the system store. Cheap enough to call on every service start. */
    suspend fun reconcile() = mutex.withLock {
        if (!hasPermission()) {
            _usage.value = emptyMap()
            return@withLock
        }
        val now = time.now()
        val start = TimeWindows.usageDayStart(now, time.zone(), settings.current().usageResetMinute)
        val events = withContext(Dispatchers.IO) { queryEvents(start.minus(LOOKBACK), now) }
        _usage.value = UsageAggregator.foregroundTime(events, start, now)
        dayStart = start
        val open = UsageAggregator.openPackages(events)
        if (foreground == null) foreground = open.lastOrNull()
        accountedFrom = now
    }

    /** Called by the accessibility service on every foreground change. */
    fun onForegroundChanged(packageName: String?, at: Instant) {
        val prev = foreground
        if (prev == packageName) return
        if (prev != null && at.isAfter(accountedFrom) && !at.isBefore(dayStart)) {
            val delta = Duration.between(maxOf(accountedFrom, dayStart), at)
            _usage.value = _usage.value + (prev to ((_usage.value[prev] ?: Duration.ZERO) + delta))
        }
        foreground = packageName
        accountedFrom = at
    }

    /** Today's usage including the live, unreconciled delta for the foreground app. */
    fun liveUsage(now: Instant): Map<String, Duration> {
        val fg = foreground ?: return _usage.value
        if (!now.isAfter(accountedFrom)) return _usage.value
        val delta = Duration.between(maxOf(accountedFrom, dayStart), now)
        if (delta.isNegative) return _usage.value
        return _usage.value + (fg to ((_usage.value[fg] ?: Duration.ZERO) + delta))
    }

    fun usedFor(packageName: String, now: Instant): Duration = liveUsage(now)[packageName] ?: Duration.ZERO

    /** Per-package totals for each local calendar day in [from, to], straight from the system store. */
    suspend fun dailyTotals(from: LocalDate, to: LocalDate): Map<LocalDate, Map<String, Duration>> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyMap()
        val zone = time.zone()
        val now = time.now()
        val events = queryEvents(from.atStartOfDay(zone).toInstant().minus(LOOKBACK), minOf(now, to.plusDays(1).atStartOfDay(zone).toInstant()))
        var d = from
        val out = LinkedHashMap<LocalDate, Map<String, Duration>>()
        while (!d.isAfter(to)) {
            val s = d.atStartOfDay(zone).toInstant()
            val e = minOf(now, d.plusDays(1).atStartOfDay(zone).toInstant())
            if (s.isBefore(e)) out[d] = UsageAggregator.foregroundTime(events, s, e)
            d = d.plusDays(1)
        }
        out
    }

    private fun queryEvents(from: Instant, to: Instant): List<UsageEventRecord> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val result = ArrayList<UsageEventRecord>(2048)
        val events = runCatching { usm.queryEvents(from.toEpochMilli(), to.toEpochMilli()) }.getOrNull() ?: return emptyList()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val kind = when (e.eventType) {
                EVENT_RESUMED -> UsageEventKind.RESUMED
                EVENT_PAUSED -> UsageEventKind.PAUSED
                EVENT_STOPPED -> UsageEventKind.STOPPED
                EVENT_SCREEN_OFF -> UsageEventKind.SCREEN_OFF
                EVENT_SHUTDOWN -> UsageEventKind.SHUTDOWN
                else -> null
            } ?: continue
            result += UsageEventRecord(e.packageName, e.className, kind, Instant.ofEpochMilli(e.timeStamp))
        }
        return result
    }

    private companion object {
        val LOOKBACK: Duration = Duration.ofHours(12)
        // Raw values keep compatibility with API 26 (named constants arrived in 29).
        const val EVENT_RESUMED = 1
        const val EVENT_PAUSED = 2
        const val EVENT_SCREEN_OFF = 16
        const val EVENT_STOPPED = 23
        const val EVENT_SHUTDOWN = 26
    }
}
