package com.morgana.domain.game

import com.morgana.domain.entity.CharacterState
import kotlinx.serialization.Serializable

/**
 * Class promotions: a character climbs named tiers (for example Novice, Journeyman, Expert, Paragon) by
 * passing an event `promote` step at each tier's minimum level. The tier is kept in the player variable [variable]
 * (0 = [startName]), so events can test it with ordinary number conditions and show it as `{promotion}`.
 * Spells and items gate on it with `promotionReq`. Games without this section have no promotions.
 */
@Serializable
data class PromotionDefinition(
    /** Easy kill-switch: set false in `game.json` to disable promotions. */
    val enabled: Boolean = true,
    /** The player variable that holds the tier number. */
    val variable: String = "promotion",
    /** Name of tier 0, the tier every character starts in. */
    val startName: String = "Novice",
    /** Tier 1 is `tiers[0]`, tier 2 is `tiers[1]`, and so on. */
    val tiers: List<PromotionTier> = emptyList()
) {
    fun tierOf(character: CharacterState): Int = (character.variables[variable] ?: 0).coerceIn(0, tiers.size)

    /** The tier's name, or the class's own title for it when the tier lists one for [classId] (0 = the shared name). */
    fun nameOf(tier: Int, classId: Int = 0): String =
        if (tier <= 0) startName else tiers.getOrNull(tier - 1)?.let { it.classNames[classId] ?: it.name } ?: startName
}

@Serializable
data class PromotionTier(
    val name: String,
    /** Level a character needs before they may be promoted into this tier. */
    val minLevel: Int,
    /** Unspent attribute points granted by the promotion. */
    val bonusStatPoints: Int = 0,
    /** Spells taught by the promotion, when the character's class can use them. */
    val grantSpellIds: List<Int> = emptyList(),
    /** A class's own title for this tier (class id to name), shown in place of [name] for that class's characters. */
    val classNames: Map<Int, String> = emptyMap(),
    /** Spells taught only to characters of one class (class id to spell ids), on top of [grantSpellIds]. */
    val classGrantSpellIds: Map<Int, List<Int>> = emptyMap()
)
