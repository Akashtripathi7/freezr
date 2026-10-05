package com.freezr.app.domain.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 PIN hashing. Encoded form: "pbkdf2v1:<iterations>:<saltB64>:<hashB64>".
 * The encoded string is additionally encrypted at rest with an Android Keystore key
 * (see platform.security.KeystoreCipher).
 */
class PinHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    fun hash(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derive(pin, salt, iterations)
        val b64 = Base64.getEncoder()
        return "$PREFIX:$iterations:${b64.encodeToString(salt)}:${b64.encodeToString(derived)}"
    }

    fun verify(pin: String, encoded: String): Boolean {
        val parts = encoded.split(':')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iter = parts[1].toIntOrNull() ?: return false
        val dec = Base64.getDecoder()
        val salt = runCatching { dec.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { dec.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iter), expected)
    }

    private fun derive(pin: String, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iter, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val DEFAULT_ITERATIONS = 120_000
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
        private const val PREFIX = "pbkdf2v1"

        fun isValidPin(pin: String): Boolean = pin.length in 4..12 && pin.all(Char::isDigit)
    }
}

/** Exponential back-off after [FREE_ATTEMPTS] consecutive failures: 30 s, 60 s, 120 s … capped at 1 h. */
object PinLockoutPolicy {
    const val FREE_ATTEMPTS = 5
    private val BASE: Duration = Duration.ofSeconds(30)
    private val MAX: Duration = Duration.ofHours(1)

    fun lockoutFor(consecutiveFailures: Int): Duration {
        if (consecutiveFailures < FREE_ATTEMPTS) return Duration.ZERO
        val exp = (consecutiveFailures - FREE_ATTEMPTS).coerceAtMost(16)
        val d = BASE.multipliedBy(1L shl exp)
        return if (d > MAX) MAX else d
    }

    fun lockedUntil(consecutiveFailures: Int, lastFailureAt: Instant?): Instant? {
        val d = lockoutFor(consecutiveFailures)
        if (d.isZero || lastFailureAt == null) return null
        return lastFailureAt.plus(d)
    }
}
