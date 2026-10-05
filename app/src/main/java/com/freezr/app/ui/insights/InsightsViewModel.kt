package com.freezr.app.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freezr.app.data.db.DailyUsageEntity
import com.freezr.app.data.db.EmergencyDao
import com.freezr.app.data.db.InsightsDao
import com.freezr.app.data.settings.SettingsRepository
import com.freezr.app.domain.time.TimeSource
import com.freezr.app.domain.usage.StreakCalculator
import com.freezr.app.platform.usage.UsageTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class InsightsRange { DAY, WEEK }

data class AppTime(val packageName: String, val time: Duration)
data class AppAttempts(val packageName: String, val count: Int)
data class DayBar(val date: LocalDate, val total: Duration)

data class InsightsState(
    val loading: Boolean = true,
    val range: InsightsRange = InsightsRange.DAY,
    val hasUsageAccess: Boolean = true,
    val total: Duration = Duration.ZERO,
    val topApps: List<AppTime> = emptyList(),
    val week: List<DayBar> = emptyList(),
    val attempts: List<AppAttempts> = emptyList(),
    val attemptCount: Int = 0,
    val timeSaved: Duration = Duration.ZERO,
    val streak: Int = 0,
    val bestStreak: Int = 0,
)

/** Everything here is computed from on-device data only (UsageStats + our Room tables). */
@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val usage: UsageTracker,
    insights: InsightsDao,
    emergency: EmergencyDao,
    settings: SettingsRepository,
    private val time: TimeSource,
) : ViewModel() {
    private val range = MutableStateFlow(InsightsRange.DAY)
    private val systemDaily = MutableStateFlow<Map<LocalDate, Map<String, Duration>>>(emptyMap())
    private val zone: ZoneId get() = time.zone()
    private val since = time.now().minus(Duration.ofDays(RETENTION_DAYS)).toEpochMilli()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val today = time.now().atZone(zone).toLocalDate()
        systemDaily.value = usage.dailyTotals(today.minusDays(6), today)
    }

    fun setRange(r: InsightsRange) { range.value = r }

    private data class Stored(
        val attempts: List<com.freezr.app.data.db.BlockAttemptEntity>,
        val emergencies: List<Long>,
        val bypasses: List<com.freezr.app.data.db.BypassLogEntity>,
        val daily: List<DailyUsageEntity>,
    )

    private val stored = combine(
        insights.observeAttempts(since),
        emergency.observeLogTimes(since),
        insights.observeBypasses(since),
        insights.observeDailyUsage(time.now().atZone(time.zone()).toLocalDate().minusDays(RETENTION_DAYS).toString()),
        ::Stored,
    )

    val state: StateFlow<InsightsState> = combine(range, systemDaily, stored, settings.settings) { r, sys, st, s ->
        val now = time.now()
        val today = now.atZone(zone).toLocalDate()
        // System data is authoritative for the last 7 days; Room snapshots fill gaps.
        val roomDaily = st.daily.groupBy { LocalDate.parse(it.day) }
            .mapValues { (_, rows) -> rows.associate { it.packageName to Duration.ofMillis(it.foregroundMillis) } }
        fun dayUsage(d: LocalDate): Map<String, Duration> = sys[d] ?: roomDaily[d] ?: emptyMap()

        val days = if (r == InsightsRange.DAY) listOf(today) else (6 downTo 0).map { today.minusDays(it.toLong()) }
        val perApp = HashMap<String, Duration>()
        days.forEach { d -> dayUsage(d).forEach { (p, t) -> perApp.merge(p, t, Duration::plus) } }
        perApp.remove(com.freezr.app.BuildConfig.APPLICATION_ID)

        val rangeStart = days.first().atStartOfDay(zone).toInstant().toEpochMilli()
        val attemptsInRange = st.attempts.filter { it.timestamp >= rangeStart }
        val attempts = attemptsInRange.groupingBy { it.packageName }.eachCount()
            .map { AppAttempts(it.key, it.value) }.sortedByDescending { it.count }

        fun dateOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val badDays = (st.emergencies.map(::dateOf) + st.bypasses.filter { it.success }.map { dateOf(it.timestamp) }).toSet()
        val firstDay = (s.installedAt ?: now).atZone(zone).toLocalDate().coerceAtMost(today)

        InsightsState(
            loading = false,
            range = r,
            hasUsageAccess = usage.hasPermission(),
            total = perApp.values.fold(Duration.ZERO, Duration::plus),
            topApps = perApp.entries.sortedByDescending { it.value }.take(8).map { AppTime(it.key, it.value) },
            week = (6 downTo 0).map { today.minusDays(it.toLong()) }.map { d ->
                DayBar(d, dayUsage(d).filterKeys { it != com.freezr.app.BuildConfig.APPLICATION_ID }.values.fold(Duration.ZERO, Duration::plus))
            },
            attempts = attempts.take(6),
            attemptCount = attemptsInRange.size,
            timeSaved = MINUTES_SAVED_PER_ATTEMPT.multipliedBy(attemptsInRange.size.toLong()),
            streak = StreakCalculator.current(today, firstDay, badDays),
            bestStreak = StreakCalculator.best(today, firstDay, badDays),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsState())

    companion object {
        const val RETENTION_DAYS = 90L
        /** Conservative estimate of time not spent per blocked open; shown as an estimate in the UI. */
        val MINUTES_SAVED_PER_ATTEMPT: Duration = Duration.ofMinutes(4)
    }
}
