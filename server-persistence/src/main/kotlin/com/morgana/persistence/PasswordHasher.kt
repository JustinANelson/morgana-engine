package com.morgana.persistence

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted, iterated password hashing (PBKDF2-HMAC-SHA256) using only the JDK, so it also works on Android.
 *
 * Stored format: `pbkdf2-sha256$<iterations>$<salt b64url>$<hash b64url>`.
 * Legacy accounts stored an unsalted SHA-256 hex digest; [verify] still accepts those and [needsUpgrade]
 * tells the caller to re-hash with [hash] after a successful login so old hashes disappear over time.
 *
 * Cost note: the server coordinator is single-locked, so each hash briefly blocks the game tick. [ITERATIONS] is
 * deliberately moderate; raise it if login latency allows (old hashes are upgraded automatically on next login).
 */
object PasswordHasher {
    const val ITERATIONS: Int = 120_000
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val PREFIX = "pbkdf2-sha256"
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private val LEGACY_SHA256_HEX = Regex("^[0-9a-f]{64}$")
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun hash(rawPassword: String): String {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val key = derive(rawPassword, salt, ITERATIONS)
        return "$PREFIX$$ITERATIONS$${encoder.encodeToString(salt)}$${encoder.encodeToString(key)}"
    }

    fun verify(rawPassword: String, stored: String): Boolean {
        if (LEGACY_SHA256_HEX.matches(stored)) {
            val digest = MessageDigest.getInstance("SHA-256").digest(rawPassword.toByteArray(Charsets.UTF_8))
            return MessageDigest.isEqual(digest, stored.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        return try {
            val salt = decoder.decode(parts[2])
            val expected = decoder.decode(parts[3])
            MessageDigest.isEqual(derive(rawPassword, salt, iterations), expected)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /** True for legacy hashes and for hashes made with fewer iterations than the current setting. */
    fun needsUpgrade(stored: String): Boolean {
        if (LEGACY_SHA256_HEX.matches(stored)) return true
        val parts = stored.split('$')
        val iterations = parts.getOrNull(1)?.toIntOrNull() ?: return true
        return parts.size != 4 || parts[0] != PREFIX || iterations < ITERATIONS
    }

    private fun derive(raw: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(raw.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
