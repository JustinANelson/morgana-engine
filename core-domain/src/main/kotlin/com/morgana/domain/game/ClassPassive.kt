package com.morgana.domain.game

import kotlinx.serialization.Serializable

/**
 * A small standing bonus a class gives its characters, declared in `game.json` under `classPassives` (class id to passive). It is how
 * a "soft" class template, where anyone can use any gear and skill, still has a flavour: a Knight a little tougher, an Archer a
 * little faster. Every field is a percentage and defaults to no change; a class with no entry has no passive.
 */
@Serializable
data class ClassPassive(
    /** Percent added to a vital's maximum, by vital id (`{"hp": 5}` is +5% maximum health). */
    val vitalMaxPercent: Map<String, Int> = emptyMap(),
    /** Percent added to all damage the character deals to monsters and players (melee, ranged, spells and action skills). */
    val damagePercent: Int = 0,
    /** Percent added to the armour defence the character's gear gives. */
    val armorPercent: Int = 0,
    /** Percent added to the healing the character casts on others. */
    val healingPercent: Int = 0,
    /** Percent added to the character's movement speed (stacks with mounts and sprint). */
    val moveSpeedPercent: Int = 0
) {
    companion object {
        const val MIN_PERCENT = -90
        const val MAX_PERCENT = 500
        val NONE = ClassPassive()

        /** [value] raised by [percent] percent, rounded half up (and never below 0 for a non-negative [value]). */
        fun scale(value: Int, percent: Int): Int =
            if (percent == 0) value else Math.floorDiv(value.toLong() * (100 + percent) + 50L, 100L).toInt().coerceAtLeast(if (value >= 0) 0 else Int.MIN_VALUE)
    }
}
