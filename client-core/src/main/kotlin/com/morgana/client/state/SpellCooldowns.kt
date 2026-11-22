package com.morgana.client.state

/**
 * Client-side record of which spells are cooling down, for the quickbar sweep only; the server stays the authority.
 * A cast made while a spell is still cooling is ignored (the server would refuse it), so the sweep never restarts early.
 * Time is passed in, so the rules are deterministic and unit-tested.
 */
class SpellCooldowns {
    private class Entry(val startedAt: Float, val seconds: Float)

    private val entries = HashMap<Int, Entry>()

    /** Starts [seconds] of cooldown for [spellId]; false (and no change) when it is already cooling or [seconds] is not positive. */
    fun start(spellId: Int, seconds: Float, now: Float): Boolean {
        if (seconds <= 0f || remaining(spellId, now) > 0f) return false
        entries[spellId] = Entry(now, seconds)
        return true
    }

    /** Sets [spellId]'s cooldown to [seconds] from [now], replacing any estimate; used when the server says exactly how long. */
    fun set(spellId: Int, seconds: Float, now: Float) {
        if (seconds <= 0f) entries.remove(spellId) else entries[spellId] = Entry(now, seconds)
    }

    /** Seconds left, 0 when ready. */
    fun remaining(spellId: Int, now: Float): Float {
        val e = entries[spellId] ?: return 0f
        val left = e.startedAt + e.seconds - now
        if (left <= 0f) {
            entries.remove(spellId)
            return 0f
        }
        return left
    }

    /** How much of the cooldown is still to go, 1 just after the cast down to 0 when ready. */
    fun fractionLeft(spellId: Int, now: Float): Float {
        val e = entries[spellId] ?: return 0f
        return (remaining(spellId, now) / e.seconds).coerceIn(0f, 1f)
    }

    fun clear() = entries.clear()

    companion object {
        /** The simulation's tick rate, for turning a spell's cooldown ticks into seconds. */
        const val TICKS_PER_SECOND = 20f
    }
}
