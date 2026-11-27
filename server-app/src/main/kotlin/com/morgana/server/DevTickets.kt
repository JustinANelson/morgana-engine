package com.morgana.server

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * One-time login tickets for tools and automation (an LLM driving a browser client, a test script): a client presents a ticket
 * in a normal resume packet and enters the account's character without a password ever being typed, stored or sent.
 *
 * Off unless the server is started with `--dev-login` (or `MORGANA_DEV_LOGIN=1`). Tickets are minted by whoever can write to the
 * server's data directory (`dev-ticket` tool), which is the same trust level as editing `owners.txt`; a remote client cannot
 * mint one. A ticket is 192 random bits, lives [DEFAULT_TTL_SECONDS] seconds by default, works once, and only the SHA-256 of it
 * is kept on disk (`<data dir>/dev-tickets/<hash>.json`), so a leaked data folder does not leak usable tickets. It is read on every
 * redeem, so tickets minted while the server runs work without a restart.
 *
 * A ticket stands for an existing account, so it grants exactly that account's access level (including Owner if the account is one).
 */
class DevTickets(
    private val dataDir: Path,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom()
) {
    private val dir: Path get() = dataDir.resolve(DIR)

    /** Writes a new ticket for [username] valid for [ttlSeconds] and returns the ticket text (shown once; only its hash is stored). */
    fun mint(username: String, ttlSeconds: Long = DEFAULT_TTL_SECONDS): String {
        val name = username.trim()
        require(name.length in 2..20) { "Username must be 2-20 characters" }
        require(ttlSeconds in 1..MAX_TTL_SECONDS) { "ttl must be 1 to $MAX_TTL_SECONDS seconds" }
        Files.createDirectories(dir)
        purgeExpired()
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        val ticket = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val body = """{"username":${jsonString(name)},"expiresAtMs":${clock() + ttlSeconds * 1000L}}"""
        Files.writeString(dir.resolve(hash(ticket) + ".json"), body)
        return ticket
    }

    /** Redeems [ticket] and returns the account username, or null when it is unknown, expired or already used. Single use. */
    fun redeem(ticket: String): String? {
        if (!ticket.startsWith(PREFIX) || ticket.length > 80) return null
        val file = dir.resolve(hash(ticket) + ".json")
        if (!Files.isRegularFile(file)) return null
        val text = runCatching { Files.readString(file) }.getOrNull()
        // Deleting is the claim: only one of two simultaneous redeemers sees `true`.
        val claimed = runCatching { Files.deleteIfExists(file) }.getOrDefault(false)
        if (!claimed || text == null) return null
        val expires = Regex("\"expiresAtMs\":(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (expires < clock()) return null
        return Regex("\"username\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)?.groupValues?.get(1)?.let(::unescape)
    }

    /** Deletes tickets that have expired, so unused ones do not pile up. */
    fun purgeExpired() {
        if (!Files.isDirectory(dir)) return
        val now = clock()
        Files.list(dir).use { files ->
            files.filter { it.fileName.toString().endsWith(".json") }.forEach { f ->
                val expires = runCatching { Regex("\"expiresAtMs\":(\\d+)").find(Files.readString(f))?.groupValues?.get(1)?.toLong() }.getOrNull()
                if (expires == null || expires < now) runCatching { Files.deleteIfExists(f) }
            }
        }
    }

    private fun hash(ticket: String): String =
        MessageDigest.getInstance("SHA-256").digest(ticket.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun jsonString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun unescape(s: String): String = s.replace("\\\"", "\"").replace("\\\\", "\\")

    companion object {
        const val DIR = "dev-tickets"
        const val PREFIX = "dev_"
        const val DEFAULT_TTL_SECONDS = 600L
        const val MAX_TTL_SECONDS = 86_400L
        const val ENV = "MORGANA_DEV_LOGIN"

        /** True when the environment turns dev login on (`1`, `true` or `on`). */
        fun enabledByEnvironment(): Boolean = System.getenv(ENV)?.trim()?.lowercase() in setOf("1", "true", "on")
    }
}
