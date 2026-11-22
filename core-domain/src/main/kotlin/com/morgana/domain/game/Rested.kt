package com.morgana.domain.game

import kotlinx.serialization.Serializable

/**
 * Rested XP and a catch-up bonus, both for monster kills only (quests and other awards pay as before).
 *
 * Time spent logged out fills a rested pool: [poolPercentPerOfflineHour] percent of the XP needed for the character's current
 * level per hour, up to [poolCapPercent] percent of it. While the pool lasts, a kill pays [bonusPercent] percent extra and that
 * extra is taken from the pool. Characters below [catchUpBelowLevel] also earn [catchUpPercent] percent extra on every kill,
 * which helps a late starter or an alt join friends. Both bonuses are shown to the player and neither needs any upkeep.
 */
@Serializable
data class RestedDefinition(
    /** Easy kill-switch: set false in `game.json` to go back to base XP. */
    val enabled: Boolean = true,
    val poolPercentPerOfflineHour: Int = 10,
    val poolCapPercent: Int = 150,
    /** Extra XP in percent on a kill while the pool has any (100 = double). */
    val bonusPercent: Int = 100,
    /** Characters below this level earn [catchUpPercent] extra on kills; 0 = no catch-up bonus. */
    val catchUpBelowLevel: Int = 0,
    val catchUpPercent: Int = 0
)
