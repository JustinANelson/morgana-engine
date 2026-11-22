package com.morgana.domain.game

import kotlinx.serialization.Serializable

/**
 * The bestiary (`bestiary` in `game.json`): every kill is counted per monster kind on the character
 * (`CharacterState.bestiary`), and the Bestiary window shows more about a monster the more of them the player has
 * killed: its name and level from the first kill, its attributes from [statsAfterKills], and what it drops from
 * [dropsAfterKills]. Games without this section keep no count and have no bestiary window.
 */
@Serializable
data class BestiaryDefinition(
    val enabled: Boolean = true,
    /** Kills of one kind before its fighting stats are shown. */
    val statsAfterKills: Int = 5,
    /** Kills of one kind before its drops are shown. */
    val dropsAfterKills: Int = 10
)
