package com.morgana.client.net

import com.morgana.protocol.ClientPacket
import com.morgana.protocol.ServerPacket

/** Tunable limits for [SendGate], in milliseconds unless noted. */
data class SendGatePolicy(
    /** Movement steps that may be on the wire un-acknowledged; beyond this the client waits for the server instead of piling more on. */
    val maxUnackedMoves: Int = 10,
    /** While blocked on un-acked steps, one step is let through this often anyway, so a lost acknowledgement cannot freeze walking. */
    val moveProbeMs: Long = 400,
    /** How often the link is probed with a ping while playing. */
    val pingIntervalMs: Long = 3_000,
    /** A probe unanswered this long means the link is stalled: attacks, spells and the like are not sent. */
    val stallAfterMs: Long = 1_500,
    /** Fewest milliseconds between two sends of the same action (a held or mashed key cannot flood a slow link). */
    val minActionGapMs: Long = 120,
    /** More than this many bytes still waiting to leave the device means the link cannot keep up, however recently it answered. */
    val maxBufferedBytes: Long = 32 * 1024
)

/**
 * Keeps a poor connection from turning into a backlog. Without it the client keeps sending steps and attacks while the
 * link is stalled; they sit in the socket and arrive together when it recovers, so the character lurches and a dozen
 * swings land at once. The gate:
 *  - stops sending movement steps once too many are un-acknowledged ([movesAllowed]);
 *  - drops attacks, spells, interactions and jumps while the link is stalled and spaces repeats ([actionAllowed]);
 *  - measures the link with a ping every [SendGatePolicy.pingIntervalMs] ([pingDue], [pong]) and exposes the round trip ([rttMs]).
 *
 * Pure and clock-free (the caller passes `now`), so it is unit-tested without sockets.
 */
class SendGate(private val policy: SendGatePolicy = SendGatePolicy()) {
    private var pingOutstandingSince = 0L
    private var lastPingSentMs = 0L
    private var lastMoveBlockedProbeMs = 0L
    private val lastActionMs = HashMap<String, Long>()

    /** Bytes the transport has accepted but the network has not taken yet; the caller refreshes it each frame. */
    var bufferedBytes: Long = 0L

    /** Round trip of the latest answered ping, or 0 before the first. */
    var rttMs: Long = 0
        private set

    /** Number of actions refused since the last [takeDroppedCount] (for a "connection is slow" hint). */
    private var dropped = 0

    /** Forgets all measurements; call on every new connection. */
    fun reset() {
        pingOutstandingSince = 0
        lastPingSentMs = 0
        lastMoveBlockedProbeMs = 0
        lastActionMs.clear()
        rttMs = 0
        dropped = 0
        bufferedBytes = 0
    }

    /** True when a ping should be sent now; the caller then sends `Ping(now)` and the gate remembers it. */
    fun pingDue(now: Long): Boolean {
        if (lastPingSentMs != 0L && now - lastPingSentMs < policy.pingIntervalMs) return false
        lastPingSentMs = now
        if (pingOutstandingSince == 0L) pingOutstandingSince = now
        return true
    }

    /** A [ServerPacket.Pong] answered the ping sent at [clientTimestampMs]. */
    fun pong(now: Long, clientTimestampMs: Long) {
        pingOutstandingSince = 0
        rttMs = (now - clientTimestampMs).coerceAtLeast(0)
    }

    /** The link is stalled: a probe has gone unanswered for [SendGatePolicy.stallAfterMs]. */
    fun isStalled(now: Long): Boolean =
        bufferedBytes > policy.maxBufferedBytes || (pingOutstandingSince != 0L && now - pingOutstandingSince >= policy.stallAfterMs)

    /** Whether the next movement step may be sent, given how many steps the server has not acknowledged yet. */
    fun movesAllowed(unackedMoves: Int, now: Long): Boolean {
        if (isStalled(now) && unackedMoves > 0) return probeMove(now)
        if (unackedMoves < policy.maxUnackedMoves) return true
        return probeMove(now)
    }

    private fun probeMove(now: Long): Boolean {
        if (now - lastMoveBlockedProbeMs < policy.moveProbeMs) return false
        lastMoveBlockedProbeMs = now
        return true
    }

    /** Whether an action packet of this [kind] (a stable name such as "attack" or "spell:3") may be sent now. */
    fun actionAllowed(kind: String, now: Long): Boolean {
        if (isStalled(now)) {
            dropped++
            return false
        }
        val last = lastActionMs[kind]
        if (last != null && now - last < policy.minActionGapMs) return false
        lastActionMs[kind] = now
        return true
    }

    /** Actions refused because of a stalled link since the last call. */
    fun takeDroppedCount(): Int = dropped.also { dropped = 0 }

    companion object {
        /** The action [kind] of a client packet, or null when the packet is not gated (everything but combat and interaction). */
        fun actionKind(packet: ClientPacket): String? = when (packet) {
            is ClientPacket.Attack -> "attack"
            is ClientPacket.CastSpell -> "spell:${packet.spellId}"
            is ClientPacket.InteractFacing -> "interact"
            is ClientPacket.PickupGroundItem -> "pickup"
            is ClientPacket.JumpTo -> "jump"
            else -> null
        }
    }
}

/**
 * Wraps a [ClientTransport] so combat and interaction packets pass through a [SendGate]. Movement is gated by the game
 * loop (before the step is predicted), so it is not filtered here.
 */
class GatedTransport(
    private val inner: ClientTransport,
    val gate: SendGate,
    private val clock: () -> Long
) : ClientTransport {
    override val isConnected: Boolean get() = inner.isConnected
    override val isEncrypted: Boolean get() = inner.isEncrypted
    override val isWebSocket: Boolean get() = inner.isWebSocket

    override fun connectAsync(host: String, port: Int, security: ClientSecurity, onResult: (ConnectResult) -> Unit) {
        gate.reset()
        inner.connectAsync(host, port, security, onResult)
    }

    override val bufferedBytes: Long get() = inner.bufferedBytes
    override val pendingPackets: Int get() = inner.pendingPackets
    override fun takeUndecodableCount(): Int = inner.takeUndecodableCount()

    override fun send(packet: ClientPacket) {
        gate.bufferedBytes = inner.bufferedBytes
        val kind = SendGate.actionKind(packet)
        if (kind != null && !gate.actionAllowed(kind, clock())) return
        inner.send(packet)
    }

    override fun drainPackets(maxPerFrame: Int, consumer: (ServerPacket) -> Unit) = inner.drainPackets(maxPerFrame) { packet ->
        if (packet is ServerPacket.Pong) gate.pong(clock(), packet.clientTimestampMs)
        consumer(packet)
    }

    override fun close() = inner.close()
}
