package com.freezr.app.domain.strict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class StrictPoliciesTest {
    private val t0 = Instant.parse("2026-10-05T10:00:00Z")
    private val delay = Duration.ofMinutes(10)
    private val window = Duration.ofMinutes(3)

    @Test fun `delay unlock walks through waiting ready expired`() {
        assertEquals(DelayUnlockState.Idle, DelayUnlockPolicy.state(null, t0, delay, window))
        assertEquals(DelayUnlockState.Waiting(t0.plus(delay)), DelayUnlockPolicy.state(t0, t0.plusSeconds(60), delay, window))
        assertEquals(DelayUnlockState.Ready(t0.plus(delay).plus(window)), DelayUnlockPolicy.state(t0, t0.plus(delay), delay, window))
        assertEquals(DelayUnlockState.Expired, DelayUnlockPolicy.state(t0, t0.plus(delay).plus(window), delay, window))
    }

    @Test fun `strict lock requires enabled, active freeze, and no unlock session`() {
        assertTrue(StrictModePolicy.isLocked(true, true, null, t0))
        assertFalse(StrictModePolicy.isLocked(false, true, null, t0))
        assertFalse(StrictModePolicy.isLocked(true, false, null, t0))
        assertFalse(StrictModePolicy.isLocked(true, true, t0.plusSeconds(1), t0))
        assertTrue(StrictModePolicy.isLocked(true, true, t0, t0))
    }

    @Test fun `emergency passes and phrase`() {
        assertEquals(2, EmergencyPolicy.passesRemaining(3, 1))
        assertEquals(0, EmergencyPolicy.passesRemaining(3, 5))
        assertTrue(EmergencyPolicy.phraseMatches("I give up", "  i  give UP "))
        assertFalse(EmergencyPolicy.phraseMatches("I give up", "I give"))
        assertFalse(EmergencyPolicy.phraseMatches("", ""))
    }
}
