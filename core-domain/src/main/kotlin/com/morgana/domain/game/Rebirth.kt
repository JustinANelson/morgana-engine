package com.morgana.domain.game

import com.morgana.domain.entity.CharacterState
import kotlinx.serialization.Serializable

/**
 * Rebirth: a max-level character starts over at [resetLevel], optionally as another class, keeping what they
 * earned (spells, gear that still fits, bonus attribute points) and unlocking spells that need rebirths
 * (`SpellDefinition.rebirthReq`). It is started by the `rebirth` event step and counted in the player variable
 * [countVariable], so events can test it with ordinary number conditions and show it as `{rebirths}`.
 */
@Serializable
data class RebirthDefinition(
    /** Easy kill-switch: set false in `game.json` to disable rebirth. */
    val enabled: Boolean = true,
    /** Level a character needs before they may be reborn. */
    val minLevel: Int = 120,
    /** Level a reborn character restarts at (their attributes are those of that level of the chosen class). */
    val resetLevel: Int = 15,
    /** How many times a character can be reborn. */
    val maxRebirths: Int = 2,
    /** The player variable that counts rebirths. */
    val countVariable: String = "rebirths",
    /** Unspent attribute points granted for every rebirth the character holds, kept each time they are reborn again. */
    val bonusStatPointsPerRebirth: Int = 0,
    /** Whether the event step may switch the character to another class. */
    val allowClassChange: Boolean = true,
    /** Spells taught by reaching rebirth number N (key), in addition to the class's own starting spells. */
    val grantSpellIds: Map<Int, List<Int>> = emptyMap()
) {
    fun count(character: CharacterState): Int = (character.variables[countVariable] ?: 0).coerceAtLeast(0)
}
