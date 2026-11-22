package com.morgana.server

import com.morgana.protocol.ClientPacket

/**
 * Holds one connection's movement steps that arrived sooner than the rate limit allows, so they are applied on a later
 * tick instead of dropped.
 *
 * A client sends a free-movement step every tick (50 ms) and the server accepts at most one per tick, so over the
 * internet two steps often land in the same tick (TCP and proxies such as Tailscale Funnel deliver in bursts). Dropping
 * the second one snapped the player back on every burst, which looked like jittery movement. Steps keep their order:
 * a new step goes behind any that are waiting. The queue is short ([capacity]); a step that does not fit is dropped and
 * still acknowledged, and the client's prediction corrects itself from the next [ClientPacket.MoveInput.seq]
 * acknowledgement. Speed is unchanged: steps still leave the queue no faster than the simulation's rate limit.
 *
 * Not thread-safe: the coordinator calls it under its own lock.
 */
class MoveInputQueue(private val capacity: Int = DEFAULT_CAPACITY) {
    private val waiting = ArrayDeque<ClientPacket.MoveInput>()

    /** Highest step number dealt with (applied or dropped) so far; sent back as `CharacterSync.ackMoveSeq`. */
    var lastHandledSeq: Int = 0
        private set

    val size: Int get() = waiting.size

    /**
     * Takes a newly received [step]. Returns the step to apply right now (when nothing is waiting and [allowedNow]), or
     * null when it was queued or dropped.
     */
    fun offer(step: ClientPacket.MoveInput, allowedNow: Boolean): ClientPacket.MoveInput? {
        if (waiting.isEmpty() && allowedNow) return step
        if (waiting.size >= capacity) {
            markHandled(step)
            return null
        }
        waiting.addLast(step)
        return null
    }

    /** The oldest waiting step when [allowedNow], removed from the queue; null when nothing waits or it is still too soon. */
    fun pollReady(allowedNow: Boolean): ClientPacket.MoveInput? =
        if (allowedNow) waiting.removeFirstOrNull() else null

    /** The oldest waiting step without removing it (its mode decides the rate limit), or null when nothing waits. */
    fun peek(): ClientPacket.MoveInput? = waiting.firstOrNull()

    /** Records that [step] has been applied (or refused by the game rules); call it before sending the results. */
    fun markHandled(step: ClientPacket.MoveInput) {
        if (step.seq > lastHandledSeq) lastHandledSeq = step.seq
    }

    /** Forgets waiting steps (the session no longer has a player in the world). */
    fun clear() = waiting.clear()

    companion object {
        /** About 300 ms of free movement: enough for a bad burst, short enough that a stalled client is not replayed late. */
        const val DEFAULT_CAPACITY = 6
    }
}
