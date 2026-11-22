package com.morgana.client.net

/** Tunable timings, in milliseconds. */
data class ReconnectPolicy(
    /** How often a quiet connection is pinged; the server also drops connections silent for much longer than this. */
    val pingIntervalMs: Long = 10_000,
    /** No packet at all for this long means the connection is dead even if the OS has not said so (half-open socket). */
    val deadAfterMs: Long = 30_000,
    /** After the app returns to the foreground the connection gets this long to answer a ping before it is declared dead. */
    val probeTimeoutMs: Long = 4_000,
    /** One reconnect attempt (connect plus resume handshake) must finish within this long. */
    val attemptTimeoutMs: Long = 10_000,
    /** Wait before attempt 2, 3, ...; the last value repeats. Attempt 1 is immediate. */
    val backoffMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000),
    /** After this many failed attempts the controller stops and waits for the player to press Retry. */
    val maxAttempts: Int = 12
)

/**
 * Decides when a dropped game connection should be re-established. Pure and clock-free (the caller passes `now`), so the
 * whole policy is unit-tested without sockets or timers.
 *
 * Lifecycle: [loggedIn] arms it; every received packet calls [heard]; each frame calls [update], which asks the caller to
 * ping, drop a silent connection, or start a reconnect attempt; the caller reports attempt outcomes back. Phones and
 * browsers freeze timers and kill sockets in the background, so [appResumed] makes the next [update] probe the
 * connection immediately instead of trusting it.
 */
class ReconnectController(private val policy: ReconnectPolicy = ReconnectPolicy()) {

    enum class State {
        /** Not logged in (login screen, or the player logged out); nothing to maintain. */
        OFFLINE,
        ONLINE,
        /** Connection lost; the next attempt starts at [nextAttemptAtMs]. */
        WAITING,
        /** A reconnect attempt is in flight. */
        ATTEMPTING,
        /** Out of attempts; waits for [retryNow]. */
        GAVE_UP
    }

    sealed interface Action {
        data object None : Action
        data object SendPing : Action

        /** Close the transport: it is silent or stuck. [update] will then schedule a reconnect. */
        data object DropConnection : Action

        /** Open a new connection and resume the session. Report the outcome with [attemptFailed] or [loggedIn]. */
        data class StartAttempt(val attempt: Int) : Action
    }

    var state: State = State.OFFLINE
        private set

    /** Number of the attempt in flight or last made (1-based); 0 when none. */
    var attempt: Int = 0
        private set

    var nextAttemptAtMs: Long = 0
        private set

    private var lastHeardMs = 0L
    private var lastPingMs = 0L
    private var probeDeadlineMs: Long? = null
    private var attemptDeadlineMs = 0L

    /** A login or resume succeeded: the connection is healthy. */
    fun loggedIn(now: Long) {
        state = State.ONLINE
        attempt = 0
        lastHeardMs = now
        lastPingMs = now
        probeDeadlineMs = null
    }

    /** Any packet from the server arrived. */
    fun heard(now: Long) {
        lastHeardMs = now
        probeDeadlineMs = null
    }

    /** The app came back to the foreground (or the frame loop was frozen for a long time): do not trust the connection. */
    fun appResumed(now: Long) {
        if (state == State.ONLINE && probeDeadlineMs == null) {
            probeDeadlineMs = now + policy.probeTimeoutMs
            lastPingMs = 0
        }
    }

    /**
     * The device reports it has no network (a browser's `offline` event): an open socket cannot be trusted, so the next
     * [update] drops it and starts reconnecting rather than waiting out the silence timeout.
     */
    fun networkLost(now: Long) {
        if (state == State.ONLINE) probeDeadlineMs = now
    }

    /**
     * The network is back (`online` event, or Wi-Fi/mobile data handover): probe a connection that claims to be up, and
     * skip the waiting for one that was already retrying or had given up.
     */
    fun networkReturned(now: Long) {
        when (state) {
            State.ONLINE -> appResumed(now)
            State.WAITING, State.GAVE_UP -> retryNow(now)
            else -> {}
        }
    }

    /** Begin trying to resume a session saved by an earlier run (no connection exists yet). */
    fun resumeAtLaunch(now: Long) {
        state = State.WAITING
        attempt = 0
        nextAttemptAtMs = now
    }

    /** The player pressed Retry. */
    fun retryNow(now: Long) {
        if (state == State.OFFLINE || state == State.ONLINE) return
        if (state == State.GAVE_UP) attempt = 0
        if (state != State.ATTEMPTING) {
            state = State.WAITING
            nextAttemptAtMs = now
        }
    }

    /** The attempt could not connect or was refused for a recoverable reason; schedule the next one. */
    fun attemptFailed(now: Long) {
        if (state != State.ATTEMPTING) return
        if (attempt >= policy.maxAttempts) {
            state = State.GAVE_UP
        } else {
            state = State.WAITING
            val index = (attempt - 1).coerceIn(0, policy.backoffMs.lastIndex)
            nextAttemptAtMs = now + policy.backoffMs[index]
        }
    }

    /** The session is gone for good (logout, evicted elsewhere, token rejected): stop. */
    fun stop() {
        state = State.OFFLINE
        attempt = 0
        probeDeadlineMs = null
    }

    /** True while the player should see the "reconnecting" banner. */
    val isReconnecting: Boolean get() = state == State.WAITING || state == State.ATTEMPTING || state == State.GAVE_UP

    /** Connected but not heard from for [thresholdMs]: worth a subtle "waiting for server" hint. */
    fun isStalled(now: Long, thresholdMs: Long = 8_000): Boolean = state == State.ONLINE && now - lastHeardMs >= thresholdMs

    /** One-line status for the banner. */
    fun statusText(now: Long): String = when (state) {
        State.WAITING -> {
            val secs = ((nextAttemptAtMs - now + 999) / 1000).coerceAtLeast(0)
            if (attempt == 0 || secs == 0L) "Connection lost. Reconnecting..." else "Connection lost. Retrying in ${secs}s (attempt ${attempt + 1})"
        }
        State.ATTEMPTING -> "Reconnecting... (attempt $attempt)"
        State.GAVE_UP -> "Could not reach the server."
        else -> ""
    }

    /** Call once per frame with the transport's current connection state. */
    fun update(now: Long, connected: Boolean): Action {
        when (state) {
            State.OFFLINE -> return Action.None
            State.ONLINE -> {
                if (!connected) {
                    state = State.WAITING
                    attempt = 0
                    nextAttemptAtMs = now
                    return update(now, connected) // starts attempt 1 immediately
                }
                val probe = probeDeadlineMs
                if ((probe != null && now >= probe) || now - lastHeardMs >= policy.deadAfterMs) {
                    probeDeadlineMs = null
                    return Action.DropConnection
                }
                val due = lastPingMs == 0L || now - lastPingMs >= policy.pingIntervalMs
                if (due) {
                    lastPingMs = now.coerceAtLeast(1)
                    return Action.SendPing
                }
                return Action.None
            }
            State.WAITING -> {
                if (now < nextAttemptAtMs) return Action.None
                attempt += 1
                attemptDeadlineMs = now + policy.attemptTimeoutMs
                state = State.ATTEMPTING
                return Action.StartAttempt(attempt)
            }
            State.ATTEMPTING -> {
                if (now >= attemptDeadlineMs) {
                    attemptFailed(now)
                    return Action.DropConnection
                }
                return Action.None
            }
            State.GAVE_UP -> return Action.None
        }
    }
}
