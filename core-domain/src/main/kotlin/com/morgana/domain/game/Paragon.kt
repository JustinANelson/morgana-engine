package com.morgana.domain.game

import com.morgana.domain.entity.CharacterState
import kotlinx.serialization.Serializable

/**
 * Paragon: a non-destructive endgame track. A character at the maximum level keeps earning XP; every
 * [xpBase] + [xpStep] x (paragon level) XP they gain a paragon level worth [statPointsPerLevel] unspent
 * attribute points, up to [maxLevels]. The level is kept in the player variable [variable], so events can test
 * it with ordinary number conditions and show it as `{paragon}`. Nothing resets: a rebirth-style restart is
 * the alternative ([RebirthDefinition]) and the two can be used together or not at all.
 */
@Serializable
data class ParagonDefinition(
    /** Easy kill-switch: set false in `game.json` to disable the track. */
    val enabled: Boolean = true,
    /** The player variable that holds the paragon level. */
    val variable: String = "paragon",
    /** XP for the first paragon level. */
    val xpBase: Long = 100_000L,
    /** Extra XP added to the cost of each further paragon level. */
    val xpStep: Long = 10_000L,
    /** Unspent attribute points granted per paragon level. */
    val statPointsPerLevel: Int = 1,
    /** The highest paragon level; XP past it is ignored. */
    val maxLevels: Int = 500
) {
    fun level(character: CharacterState): Int = (character.variables[variable] ?: 0).coerceAtLeast(0)

    /** XP needed to go from paragon level [current] to the next. */
    fun costToNext(current: Int): Long = xpBase + xpStep * current.coerceAtLeast(0)
}
