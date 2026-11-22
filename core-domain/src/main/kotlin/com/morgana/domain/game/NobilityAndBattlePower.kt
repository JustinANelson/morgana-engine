package com.morgana.domain.game

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.item.ItemDefinition
import kotlinx.serialization.Serializable

/**
 * Nobility: a rank earned by donating (an event adds to the player variable [donationVariable], for example
 * `take_item` a currency item then `add_variable`). Ranks are plain data, so a game can call them anything. Pure and deterministic.
 */
@Serializable
data class NobilityDefinition(
    /** Easy kill-switch: set false in `game.json` to ignore nobility everywhere. */
    val enabled: Boolean = true,
    /** The player variable that totals a character's donations. */
    val donationVariable: String = "nobility_donated",
    /** Ranks in any order; a character holds the one with the largest [NobilityRank.minDonation] they have reached. */
    val ranks: List<NobilityRank> = emptyList()
) {
    fun rankFor(donated: Int): NobilityRank? =
        ranks.filter { it.minDonation <= donated }.maxByOrNull { it.minDonation }

    fun nextRank(donated: Int): NobilityRank? =
        ranks.filter { it.minDonation > donated }.minByOrNull { it.minDonation }

    fun donated(character: CharacterState): Int = (character.variables[donationVariable] ?: 0).coerceAtLeast(0)

    fun rankOf(character: CharacterState): NobilityRank? = if (enabled) rankFor(donated(character)) else null
}

@Serializable
data class NobilityRank(
    val id: String,
    val name: String,
    /** Total donated to hold this rank. */
    val minDonation: Int,
    /** Battle Power this rank adds. */
    val battlePower: Int = 0
)

/**
 * Battle Power: one number summarising how strong a character is, from level, worn gear (item level, rarity, `+` level)
 * and nobility. Display and ranking only; combat does not read it.
 */
@Serializable
data class BattlePowerDefinition(
    val enabled: Boolean = true,
    val perLevel: Int = 1,
    /** Points per level requirement of each worn item. */
    val perItemLevel: Int = 0,
    /** Points per `+` level of each worn item. */
    val perPlus: Int = 1,
    /** Points per gem socketed into worn gear. */
    val perGem: Int = 0,
    /** Points per worn item by rolled rarity id (items without rolls count as 0). */
    val rarityPoints: Map<String, Int> = emptyMap()
) {
    fun of(character: CharacterState, game: GameDefinition, items: Map<Int, ItemDefinition>): Int {
        if (!enabled) return 0
        var total = character.level.toLong() * perLevel
        for (stack in character.equipment.equipped.values) {
            val def = items[stack.itemId] ?: continue
            total += def.levelReq.toLong() * perItemLevel
            total += stack.plus.toLong() * perPlus
            total += stack.rolls?.let { rarityPoints[it.rarity] }?.toLong() ?: 0L
        }
        total += character.equipment.socketedGemCount().toLong() * perGem
        total += game.nobility?.rankOf(character)?.battlePower ?: 0
        return total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }
}
