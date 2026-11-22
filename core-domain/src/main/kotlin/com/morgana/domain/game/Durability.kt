package com.morgana.domain.game

import kotlinx.serialization.Serializable

/**
 * Gear wear. A stack's `durability` runs 100 (new) down to 0 (broken). Landing weapon hits wears the weapon and taking hits
 * wears one random worn armor piece, each with a percent chance (drawn from the seeded simulation RNG, so it replays).
 * A broken piece stays equipped but adds nothing until an event `repair_gear` step repairs it.
 * Games without this section never lose durability.
 */
@Serializable
data class DurabilityDefinition(
    /** Easy kill-switch: set false in `game.json` to stop gear wearing down. */
    val enabled: Boolean = true,
    /** Chance in percent that a landed weapon hit wears the weapon. */
    val weaponWearChancePercent: Int = 10,
    /** Chance in percent that a hit taken wears one worn armor piece (armor, helmet, shield or boots). */
    val armorWearChancePercent: Int = 10,
    /** Durability points lost per wear. */
    val wearPerHit: Int = 1,
    /** Repairing 100 points costs this percent of the item's price (so 20 = a fifth of its price); at least 1 per item. */
    val repairPricePercent: Int = 20
) {
    /** Currency cost to restore [missing] durability points of an item priced [itemPrice]. */
    fun repairCost(itemPrice: Int, missing: Int): Int {
        if (missing <= 0) return 0
        val raw = (itemPrice.toLong() * missing * repairPricePercent + 9_999L) / 10_000L
        return raw.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }
}
