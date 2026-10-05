package com.freezr.app.domain.planning

import com.freezr.app.domain.engine.FreezeDecisionEngine
import com.freezr.app.domain.model.EngineContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class PreFreezeAlarm(
    val notifyAt: Instant,
    val freezeAt: Instant,
    val scheduleNames: List<String>,
    val packages: Set<String>,
)

/**
 * The full alarm plan. Only two alarms ever exist: the next decision transition and the next
 * pre-freeze notification. Each alarm re-plans when it fires, so the chain never breaks.
 */
data class AlarmPlan(val transitionAt: Instant?, val preFreeze: PreFreezeAlarm?)

object AlarmPlanner {
    private val HORIZON: Duration = Duration.ofDays(8)

    fun plan(
        now: Instant,
        zone: ZoneId,
        ctx: EngineContext,
        preFreezeEnabled: Boolean,
        preFreezeMinutes: Int,
    ): AlarmPlan {
        val transition = FreezeDecisionEngine.nextTransitionAfter(now, zone, ctx)
        if (!preFreezeEnabled || preFreezeMinutes <= 0 || !ctx.masterEnabled) return AlarmPlan(transition, null)

        val lead = Duration.ofMinutes(preFreezeMinutes.toLong())
        val next = FreezeDecisionEngine.upcomingScheduleFreezes(now, zone, ctx, HORIZON)
            .filter { it.startsAt.minus(lead).isAfter(now) }
        val first = next.firstOrNull() ?: return AlarmPlan(transition, null)
        val sameStart = next.filter { it.startsAt == first.startsAt }
        return AlarmPlan(
            transitionAt = transition,
            preFreeze = PreFreezeAlarm(
                notifyAt = first.startsAt.minus(lead),
                freezeAt = first.startsAt,
                scheduleNames = sameStart.map { it.scheduleName }.distinct(),
                packages = sameStart.flatMap { it.packages }.toSet(),
            ),
        )
    }
}
