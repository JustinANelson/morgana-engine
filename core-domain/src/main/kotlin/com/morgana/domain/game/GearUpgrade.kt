package com.morgana.domain.game

import kotlinx.serialization.Serializable

/**
 * Rules for the `upgrade_gear` event step: opening sockets, slotting gems, raising a piece of gear to its next tier
 * (`ItemDefinition.nextTierItemId`) and raising its quality to the next rarity of the game's `loot` rules.
 */
@Serializable
data class GearUpgradeDefinition(
    /** Easy kill-switch: set false in `game.json` to refuse every upgrade. */
    val enabled: Boolean = true,
    /** Most sockets one piece of gear can hold (1 to 6). */
    val maxSockets: Int = 2,
    /** The `+` enhancement ladder (the `ENHANCE` upgrade): guaranteed steps, then chancy steps with pity. Null = no enhancing. */
    val enhancement: EnhancementDefinition? = null,
    /** Chance, pity and socket rules for raising an item's quality (the `UPGRADE_QUALITY` upgrade). Null = the event's own `successPercent`. */
    val quality: QualityUpgradeDefinition? = null
)

/**
 * Raising an item to the next rarity (the `UPGRADE_QUALITY` step) can fail. Each [QualityStep] names the rarity it reaches, its
 * chance and how much material one try uses up. Every failure adds pity stacks, each worth [pityBonusBasisPointsPerStack] on the
 * next try (up to [maxPityStacks]); a success spends them. A success can also open one new empty socket, with a small
 * [socketChanceBasisPoints] chance, while the gear has room (`gearUpgrade.maxSockets`): sockets come only from here and from
 * loot, never from a plain purchase.
 */
@Serializable
data class QualityUpgradeDefinition(
    val steps: List<QualityStep> = emptyList(),
    val pityBonusBasisPointsPerStack: Int = 0,
    val maxPityStacks: Int = 0,
    val pityGainPerFailure: Int = 1,
    /** Chance in basis points that a successful raise also adds an empty socket (0 = never). */
    val socketChanceBasisPoints: Int = 0
)

@Serializable
data class QualityStep(
    /** The id of the rarity (in `loot.rarities`) this step raises the item to. */
    val rarityId: String,
    /** Chance of success in basis points; 10000 never fails. */
    val chanceBasisPoints: Int = 10_000,
    /** How many of the event's material one try uses up. */
    val materialAmount: Int = 1
)

/**
 * A ladder of `+` levels for worn gear, in the style of enhancement systems with guaranteed early steps and chancy late ones.
 * Each [EnhancementTier] says what one attempt to reach its `+` level costs and how likely it is. A tier whose chance is
 * 10000 (100%) never fails and never touches pity. A tier below that can fail; every failure adds pity stacks, and each stack
 * adds [pityBonusBasisPointsPerStack] to the next chancy attempt (up to [maxPityStacks]). A success at a chancy tier spends
 * all the stacks. A failed attempt still uses up its material and can wear the gear down or knock a `+` off.
 */
@Serializable
data class EnhancementDefinition(
    /** One entry per `+` level, `plus` running 1, 2, 3 ... with no gaps (at most [com.morgana.domain.item.ItemStack.MAX_PLUS]). */
    val tiers: List<EnhancementTier> = emptyList(),
    /** Added to a chancy tier's chance for each pity stack, in basis points (100 = 1%). 0 turns pity off. */
    val pityBonusBasisPointsPerStack: Int = 0,
    /** Most pity stacks a character can hold. Set it so stacks times the bonus reaches 10000 for a guaranteed finish. */
    val maxPityStacks: Int = 0,
    /** Pity stacks gained by each failed attempt. */
    val pityGainPerFailure: Int = 1,
    /** Durability a failed attempt takes off the gear (0 = none). Gear that is broken cannot be enhanced until repaired. */
    val failDurabilityLoss: Int = 0,
    /** A failed attempt for this `+` level or higher also drops the gear one `+` level (0 = a failure never drops a level). */
    val dropPlusOnFailFrom: Int = 0
)

@Serializable
data class EnhancementTier(
    /** The `+` level this attempt reaches (1 for the first step). */
    val plus: Int,
    /** Chance of success in basis points: 10000 never fails, below 10000 can fail and uses pity. */
    val chanceBasisPoints: Int = 10_000,
    /** The item one attempt uses up. */
    val materialItemId: Int,
    val materialAmount: Int = 1
)
