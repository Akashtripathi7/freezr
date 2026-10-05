package com.freezr.app.domain.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PinHasherTest {
    private val hasher = PinHasher(iterations = 1_000)

    @Test fun `verifies the right pin and rejects others`() {
        val stored = hasher.hash("4821")
        assertTrue(hasher.verify("4821", stored))
        assertFalse(hasher.verify("4822", stored))
        assertFalse(hasher.verify("", stored))
    }

    @Test fun `hash is salted`() {
        assertNotEquals(hasher.hash("1234"), hasher.hash("1234"))
    }

    @Test fun `stored hash does not contain the pin and encodes parameters`() {
        val stored = hasher.hash("987654")
        assertFalse(stored.contains("987654"))
        assertEquals("pbkdf2v1", stored.split(':')[0])
        assertEquals("1000", stored.split(':')[1])
    }

    @Test fun `malformed stored value never verifies`() {
        assertFalse(hasher.verify("1234", "garbage"))
        assertFalse(hasher.verify("1234", "pbkdf2v1:x:y:z"))
        assertFalse(hasher.verify("1234", "pbkdf2v1:10:%%%:%%%"))
    }

    @Test fun `pin validation`() {
        assertTrue(PinHasher.isValidPin("1234"))
        assertFalse(PinHasher.isValidPin("123"))
        assertFalse(PinHasher.isValidPin("12a4"))
        assertFalse(PinHasher.isValidPin("1234567890123"))
    }

    @Test fun `lockout starts after five failures and backs off exponentially with a cap`() {
        assertEquals(Duration.ZERO, PinLockoutPolicy.lockoutFor(4))
        assertEquals(Duration.ofSeconds(30), PinLockoutPolicy.lockoutFor(5))
        assertEquals(Duration.ofSeconds(60), PinLockoutPolicy.lockoutFor(6))
        assertEquals(Duration.ofSeconds(120), PinLockoutPolicy.lockoutFor(7))
        assertEquals(Duration.ofHours(1), PinLockoutPolicy.lockoutFor(40))
        val at = Instant.parse("2026-10-05T10:00:00Z")
        assertNull(PinLockoutPolicy.lockedUntil(3, at))
        assertEquals(at.plusSeconds(30), PinLockoutPolicy.lockedUntil(5, at))
    }
}
