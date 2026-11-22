package com.morgana.client.feedback

/**
 * Decides when the phone should buzz, from the local character's life and level as they change. Pure and clock-free (the
 * caller passes `now`), so it is unit-tested. A buzz is a duration in milliseconds, or 0 for none.
 *
 *  - taking damage: a short tick, at most one per [MIN_GAP_MS] so a burst of hits does not become a drone
 *  - dying: one long buzz
 *  - gaining a level: a medium buzz
 * Changing character (or logging out) only re-learns the starting values; it never buzzes.
 */
class HapticModel {
    private var characterId: String? = null
    private var lastHp = -1
    private var lastLevel = -1
    private var lastBuzzMs = Long.MIN_VALUE / 2

    /** Forgets the current character (logout, or a world change that replaces the state). */
    fun reset() {
        characterId = null
        lastHp = -1
        lastLevel = -1
    }

    /** Feed the character's current [hp] and [level] once per frame; returns how long to buzz now, or 0. */
    fun update(id: String, hp: Int, level: Int, now: Long): Int {
        if (id != characterId) {
            characterId = id
            lastHp = hp
            lastLevel = level
            return 0
        }
        var buzz = 0
        if (level > lastLevel && lastLevel >= 0) buzz = LEVEL_UP_MS
        if (hp < lastHp && lastHp > 0) {
            buzz = if (hp <= 0) DEATH_MS else if (now - lastBuzzMs >= MIN_GAP_MS) maxOf(buzz, HIT_MS) else buzz
        }
        lastHp = hp
        lastLevel = level
        if (buzz > 0) lastBuzzMs = now
        return buzz
    }

    companion object {
        const val HIT_MS = 25
        const val LEVEL_UP_MS = 120
        const val DEATH_MS = 250
        const val MIN_GAP_MS = 200L
    }
}
