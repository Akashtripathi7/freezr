package com.freezr.app.domain.engine

import com.freezr.app.domain.model.ActiveFreeze
import com.freezr.app.domain.model.ContextRule
import com.freezr.app.domain.model.ContextRuleType
import com.freezr.app.domain.model.Decision
import com.freezr.app.domain.model.EngineContext
import com.freezr.app.domain.model.FreezeReason
import com.freezr.app.domain.model.ScheduleRule
import com.freezr.app.domain.model.UpcomingFreeze
import com.freezr.app.domain.model.UsageLimitRule
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Pure, deterministic blocking logic. No Android dependencies, no clock reads, no I/O:
 * every input arrives through the arguments, so the same inputs always give the same answer.
 *
 * Precedence:
 *  1. Protected (never-blockable) packages and our own app -> Allowed
 *  2. Master toggle off -> Allowed
 *  3. Active emergency pass for the package -> Allowed
 *  4. Quick Freeze -> Frozen
 *  5. Active schedule -> Frozen
 *  6. Daily usage limit exceeded -> Frozen
 *  7. Active location / Wi-Fi rule -> Frozen
 *  8. Allowed
 */
object FreezeDecisionEngine {

    /** How far ahead schedule windows are expanded when merging / planning. */
    private const val HORIZON_DAYS = 8L

    fun evaluate(packageName: String, now: Instant, zone: ZoneId, ctx: EngineContext): Decision {
        if (packageName == ctx.ownPackage || packageName in ctx.protectedPackages) return Decision.Allowed
        if (!ctx.masterEnabled) return Decision.Allowed
        if (ctx.emergencyPasses.any { it.packageName == packageName && now.isBefore(it.expiresAt) }) {
            return Decision.Allowed
        }

        ctx.quickFreeze?.let { qf ->
            if (packageName in qf.packages && !now.isBefore(qf.startedAt) && now.isBefore(qf.endsAt)) {
                return frozen(ctx, FreezeReason.QUICK_FREEZE, qf.endsAt, QUICK_FREEZE_ID, "Focus session")
            }
        }

        scheduleDecision(packageName, now, zone, ctx)?.let { return it }

        ctx.limits.firstOrNull { it.enabled && it.packageName == packageName }?.let { limit ->
            if (isLimitExceeded(limit, ctx)) {
                return frozen(
                    ctx, FreezeReason.USAGE_LIMIT,
                    TimeWindows.nextUsageReset(now, zone, ctx.usageResetMinute),
                    limitId(packageName), "Daily limit",
                )
            }
        }

        ctx.contextRules.firstOrNull { packageName in it.packages && isContextRuleActive(it, now, zone) }?.let { rule ->
            val until = rule.filter?.let { f ->
                TimeWindows.activeWindow(f.startMinute, f.endMinute, f.daysMask, now, zone)?.end
            }
            val reason = if (rule.type == ContextRuleType.GEOFENCE) FreezeReason.LOCATION else FreezeReason.WIFI
            return frozen(ctx, reason, until, contextId(rule.id), rule.name)
        }

        return Decision.Allowed
    }

    /** Earliest instant strictly after [now] at which any decision can change, or null if none. */
    fun nextTransitionAfter(now: Instant, zone: ZoneId, ctx: EngineContext): Instant? {
        val today = now.atZone(zone).toLocalDate()
        val candidates = ArrayList<Instant>()

        ctx.quickFreeze?.let { candidates += it.startedAt; candidates += it.endsAt }
        ctx.emergencyPasses.forEach { candidates += it.expiresAt }

        ctx.schedules.filter { it.enabled && it.packages.isNotEmpty() }.forEach { s ->
            TimeWindows.windows(s.startMinute, s.endMinute, s.daysMask, today.minusDays(1), today.plusDays(HORIZON_DAYS), zone)
                .forEach { candidates += it.start; candidates += it.end }
        }
        if (ctx.limits.any { it.enabled }) {
            candidates += TimeWindows.nextUsageReset(now, zone, ctx.usageResetMinute)
        }
        ctx.contextRules.filter { it.enabled }.mapNotNull { it.filter }.forEach { f ->
            TimeWindows.windows(f.startMinute, f.endMinute, f.daysMask, today.minusDays(1), today.plusDays(HORIZON_DAYS), zone)
                .forEach { candidates += it.start; candidates += it.end }
        }
        return candidates.filter { it.isAfter(now) }.minOrNull()
    }

    /** True if any rule is freezing anything right now (drives Strict Mode). */
    fun isAnyFreezeActive(now: Instant, zone: ZoneId, ctx: EngineContext): Boolean =
        activeFreezes(now, zone, ctx).isNotEmpty()

    /** Every rule currently in force, for display and for Strict Mode. */
    fun activeFreezes(now: Instant, zone: ZoneId, ctx: EngineContext): List<ActiveFreeze> {
        if (!ctx.masterEnabled) return emptyList()
        val out = ArrayList<ActiveFreeze>()
        ctx.quickFreeze?.let { qf ->
            if (qf.packages.isNotEmpty() && !now.isBefore(qf.startedAt) && now.isBefore(qf.endsAt)) {
                out += ActiveFreeze(QUICK_FREEZE_ID, "Focus session", FreezeReason.QUICK_FREEZE, qf.packages, qf.endsAt)
            }
        }
        ctx.schedules.filter { it.enabled && it.packages.isNotEmpty() }.forEach { s ->
            TimeWindows.activeWindow(s.startMinute, s.endMinute, s.daysMask, now, zone)?.let { w ->
                out += ActiveFreeze(scheduleId(s.id), s.name, FreezeReason.SCHEDULE, s.packages, w.end)
            }
        }
        ctx.limits.filter { it.enabled && isLimitExceeded(it, ctx) }.forEach { l ->
            out += ActiveFreeze(
                limitId(l.packageName), "Daily limit", FreezeReason.USAGE_LIMIT, setOf(l.packageName),
                TimeWindows.nextUsageReset(now, zone, ctx.usageResetMinute),
            )
        }
        ctx.contextRules.filter { it.packages.isNotEmpty() && isContextRuleActive(it, now, zone) }.forEach { r ->
            val until = r.filter?.let { f -> TimeWindows.activeWindow(f.startMinute, f.endMinute, f.daysMask, now, zone)?.end }
            val reason = if (r.type == ContextRuleType.GEOFENCE) FreezeReason.LOCATION else FreezeReason.WIFI
            out += ActiveFreeze(contextId(r.id), r.name, reason, r.packages, until)
        }
        return out
    }

    /** Schedule windows that start within (now, now + horizon], earliest first. */
    fun upcomingScheduleFreezes(now: Instant, zone: ZoneId, ctx: EngineContext, horizon: Duration): List<UpcomingFreeze> {
        if (!ctx.masterEnabled) return emptyList()
        val today = now.atZone(zone).toLocalDate()
        val limit = now.plus(horizon)
        val days = horizon.toDays() + 1
        return ctx.schedules.filter { it.enabled && it.packages.isNotEmpty() }.flatMap { s ->
            TimeWindows.windows(s.startMinute, s.endMinute, s.daysMask, today, today.plusDays(days), zone)
                .filter { it.start.isAfter(now) && !it.start.isAfter(limit) }
                .map { UpcomingFreeze(it.start, s.id, s.name, s.packages) }
        }.sortedBy { it.startsAt }
    }

    fun isLimitExceeded(limit: UsageLimitRule, ctx: EngineContext): Boolean {
        val used = ctx.usageToday[limit.packageName] ?: Duration.ZERO
        val allowed = Duration.ofMinutes(limit.limitMinutes.toLong() + (ctx.limitBonusMinutes[limit.packageName] ?: 0))
        return used >= allowed
    }

    fun isContextRuleActive(rule: ContextRule, now: Instant, zone: ZoneId): Boolean {
        if (!rule.enabled || !rule.active) return false
        val f = rule.filter ?: return true
        return TimeWindows.activeWindow(f.startMinute, f.endMinute, f.daysMask, now, zone) != null
    }

    private fun scheduleDecision(packageName: String, now: Instant, zone: ZoneId, ctx: EngineContext): Decision.Frozen? {
        val relevant = ctx.schedules.filter { it.enabled && packageName in it.packages }
        if (relevant.isEmpty()) return null
        val active = relevant.mapNotNull { s ->
            TimeWindows.activeWindow(s.startMinute, s.endMinute, s.daysMask, now, zone)?.let { s to it }
        }
        if (active.isEmpty()) return null
        val primary = active.maxBy { it.second.end }.first
        return frozen(
            ctx, FreezeReason.SCHEDULE, continuousEnd(relevant, now, zone),
            scheduleId(primary.id), primary.name,
        )
    }

    /** Latest end of the chain of overlapping / touching windows that contains [now]. */
    internal fun continuousEnd(schedules: List<ScheduleRule>, now: Instant, zone: ZoneId): Instant {
        val today = now.atZone(zone).toLocalDate()
        val windows = schedules.flatMap { s ->
            TimeWindows.windows(s.startMinute, s.endMinute, s.daysMask, today.minusDays(1), today.plusDays(HORIZON_DAYS), zone)
        }.sortedBy { it.start }
        var end = windows.filter { now in it }.maxOf { it.end }
        for (w in windows) {
            if (w.start.isAfter(end)) break
            if (w.end.isAfter(end)) end = w.end
        }
        return end
    }

    private fun frozen(ctx: EngineContext, reason: FreezeReason, until: Instant?, ruleId: String, name: String) =
        Decision.Frozen(
            reason = reason,
            unfreezeAt = until,
            ruleId = ruleId,
            ruleName = name,
            canEmergencyUnlock = ctx.emergencyPassesRemaining > 0 &&
                !(reason == FreezeReason.QUICK_FREEZE && ctx.strictModeEnabled),
        )

    const val QUICK_FREEZE_ID = "quick"
    fun scheduleId(id: Long) = "schedule:$id"
    fun limitId(pkg: String) = "limit:$pkg"
    fun contextId(id: Long) = "context:$id"
}
