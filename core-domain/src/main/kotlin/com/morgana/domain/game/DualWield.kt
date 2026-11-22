package com.morgana.domain.game

import kotlinx.serialization.Serializable

/**
 * Dual-wielding: a character of an allowed class may wear a second one-handed melee weapon in the off-hand (`SHIELD`)
 * slot instead of a shield. Every landed melee hit then gives the off-hand weapon a [strikeChancePercent] chance to strike
 * the same target for [damagePercent] of a normal hit made with it, drawn from the seeded simulation RNG so it replays.
 *
 * The defaults are chosen so the second weapon is worth more than the shield it replaces: at 65% and 80% the off-hand weapon
 * adds about half a weapon's damage to every swing on average, where a shield only adds a few points of defence.
 * Games without this section never allow a weapon in the off-hand.
 */
@Serializable
data class DualWieldDefinition(
    /** Easy kill-switch: set false in `game.json` to disable dual-wielding. */
    val enabled: Boolean = true,
    /** Class ids that may dual-wield; empty means every class. */
    val classIds: Set<Int> = emptySet(),
    /** Chance in percent that the off-hand weapon strikes after a landed main-hand hit. */
    val strikeChancePercent: Int = 65,
    /** Damage of an off-hand strike as a percent of a full hit with that weapon. */
    val damagePercent: Int = 80
) {
    fun allows(classId: Int): Boolean = enabled && (classIds.isEmpty() || classId in classIds)
}
