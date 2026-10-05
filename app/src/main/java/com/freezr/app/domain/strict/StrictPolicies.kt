package com.freezr.app.domain.strict

import java.time.Duration
import java.time.Instant

enum class StrictUnlockMethod { PIN, DELAY, PARTNER }

/** State of a delay-timer unlock request: wait [delay], then confirm within [confirmWindow]. */
sealed interface DelayUnlockState {
    data object Idle : DelayUnlockState
    data class Waiting(val readyAt: Instant) : DelayUnlockState
    data class Ready(val expiresAt: Instant) : DelayUnlockState
    data object Expired : DelayUnlockState
}

object DelayUnlockPolicy {
    fun state(requestedAt: Instant?, now: Instant, delay: Duration, confirmWindow: Duration): DelayUnlockState {
        if (requestedAt == null) return DelayUnlockState.Idle
        val readyAt = requestedAt.plus(delay)
        val expiresAt = readyAt.plus(confirmWindow)
        return when {
            now.isBefore(readyAt) -> DelayUnlockState.Waiting(readyAt)
            now.isBefore(expiresAt) -> DelayUnlockState.Ready(expiresAt)
            else -> DelayUnlockState.Expired
        }
    }
}

/**
 * Strict Mode is "locked" while it is enabled, some freeze is active, and no unlock session
 * (granted by PIN / delay / partner) is running.
 */
object StrictModePolicy {
    fun isLocked(strictEnabled: Boolean, anyFreezeActive: Boolean, unlockedUntil: Instant?, now: Instant): Boolean =
        strictEnabled && anyFreezeActive && (unlockedUntil == null || !now.isBefore(unlockedUntil))
}

object EmergencyPolicy {
    val WEEK: Duration = Duration.ofDays(7)

    /** Passes are counted on a rolling 7-day window so the count survives restarts and time changes. */
    fun passesRemaining(perWeek: Int, usesInLastWeek: Int): Int = (perWeek - usesInLastWeek).coerceAtLeast(0)

    fun phraseMatches(expected: String, typed: String): Boolean =
        normalize(expected).isNotEmpty() && normalize(expected) == normalize(typed)

    private fun normalize(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()
}
