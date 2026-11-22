package com.morgana.server

import java.security.SecureRandom
import java.util.Base64

/**
 * Short-lived, single-use tokens that let a client whose connection dropped (phone locked, tab backgrounded, Wi-Fi roam)
 * return to its character without re-entering the password.
 *
 * A token is valid for as long as the session it was issued to is connected, plus [graceMs] after that session ends.
 * Redeeming retires the token; the caller issues a fresh one, so a captured token is useless once the real client
 * has resumed. A retired token still works once more for [overlapMs] (until the fresh token is first used, whichever
 * comes first, and only while the resumed session has already ended): on a phone the reply carrying the fresh token is often lost with the connection, and without this the
 * player would be sent back to the password screen. Tokens live only in memory: a server restart invalidates them and
 * players simply log in again.
 */
class ResumeTokens(
    private val graceMs: Long = DEFAULT_GRACE_MS,
    private val overlapMs: Long = DEFAULT_OVERLAP_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom()
) {
    private class Entry(val accountId: String, val characterId: String?, var sessionId: String?, var expiresAtMs: Long) {
        /** When this token was redeemed (0 while it is still the account's live token). */
        var retiredAtMs: Long = 0L
    }

    /** What a redeemed token stands for: the account, and the character it was playing (null when the token predates the choice). */
    data class Redeemed(val accountId: String, val characterId: String?)

    private val byToken = HashMap<String, Entry>()

    /** Issues a token for [accountId] (playing [characterId]), bound to the live [sessionId]. */
    @Synchronized
    fun issue(accountId: String, sessionId: String, characterId: String? = null): String {
        purgeExpired()
        // Keep at most one live token per account so abandoned ones cannot pile up (a just-retired one stays for its overlap).
        byToken.values.removeAll { it.accountId == accountId && it.retiredAtMs == 0L }
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        byToken[token] = Entry(accountId, characterId, sessionId, Long.MAX_VALUE)
        return token
    }

    /** Starts the grace period for tokens bound to a session that just ended. */
    @Synchronized
    fun sessionEnded(sessionId: String) {
        val now = clock()
        byToken.values.filter { it.sessionId == sessionId }.forEach {
            it.sessionId = null
            it.expiresAtMs = now + graceMs
        }
    }

    /** Redeems [token] and returns its account id, or null if unknown, expired or already used. */
    fun redeem(token: String): String? = redeemEntry(token)?.accountId

    /** Like [redeem], but also says which character the token was playing. */
    @Synchronized
    fun redeemEntry(token: String): Redeemed? {
        val now = clock()
        val entry = byToken[token] ?: return null
        if (entry.retiredAtMs != 0L) {
            // Second use inside the overlap: the client never saw its fresh token. Allowed once, and only while the session
            // that redeemed the token is gone; if it is still connected the token is simply dead, so a captured one cannot
            // be replayed to take over a live session.
            if (byToken.values.any { it.accountId == entry.accountId && it.retiredAtMs == 0L && it.sessionId != null }) return null
            byToken.remove(token)
            return if (entry.expiresAtMs >= now) Redeemed(entry.accountId, entry.characterId) else null
        }
        if (entry.expiresAtMs < now) {
            byToken.remove(token)
            return null
        }
        // The client holding the live token proves it received the previous fresh token, so older retired ones are void.
        byToken.values.removeAll { it.accountId == entry.accountId && it.retiredAtMs != 0L }
        entry.retiredAtMs = now
        entry.sessionId = null
        entry.expiresAtMs = now + overlapMs
        return Redeemed(entry.accountId, entry.characterId)
    }

    private fun purgeExpired() {
        val now = clock()
        byToken.values.removeAll { it.expiresAtMs < now }
    }

    val size: Int @Synchronized get() = byToken.size

    companion object {
        /** How long a disconnected player can still resume (30 minutes). */
        const val DEFAULT_GRACE_MS = 30 * 60 * 1000L

        /** How long a redeemed token still works once more (one minute). */
        const val DEFAULT_OVERLAP_MS = 60 * 1000L
    }
}
