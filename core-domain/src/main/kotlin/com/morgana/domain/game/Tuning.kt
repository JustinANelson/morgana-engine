package com.morgana.domain.game

import com.morgana.domain.item.GearRules
import kotlinx.serialization.Serializable

/**
 * Gameplay numbers a game may want to differ, declared in `game.json` under `tuning` so no engine code is needed to change them
 * (engine mandate 12). Every field is optional and its default is the value the engine always used, so a game without a `tuning`
 * section, or leaving a field out, plays exactly as before. Changing a field changes the rule in the simulation and on the client.
 */
@Serializable
data class GameplayTuning(
    /** Ticks between resource-node harvest swings (20 ticks is one second). A survival game wants this small, with generous repetition. */
    val harvestCooldownTicks: Int = DEFAULT_HARVEST_COOLDOWN_TICKS,
    /** How far, in tiles, a player may walk from where they opened a shop and still use it. */
    val shopRangeTiles: Int = DEFAULT_SHOP_RANGE_TILES,
    /** How near, in tiles, a player must be to pick up a ground item. */
    val pickupRangeTiles: Int = DEFAULT_PICKUP_RANGE_TILES,
    /** Damage of a bare-handed swing. */
    val unarmedDamage: Int = GearRules.DEFAULT_UNARMED_DAMAGE,
    /** Weapon damage each `+` level adds, flat. */
    val plusWeaponDamage: Int = GearRules.DEFAULT_PLUS_WEAPON_DAMAGE,
    /** Defence each `+` level adds to armour, shields, helmets and boots, flat. */
    val plusArmorDefense: Int = GearRules.DEFAULT_PLUS_ARMOR_DEFENSE,
    /** Maximum health each `+` level adds to rings and amulets. */
    val plusRingHp: Int = GearRules.DEFAULT_PLUS_RING_HP,
    /** Percent of a weapon's own damage or armour's own defence each `+` level adds, on top of the flat amounts (3 means +3% per level). 0 is off. */
    val plusPercentPerLevel: Int = 0,
    /** Most members a guild may have. */
    val maxGuildMembers: Int = DEFAULT_MAX_GUILD_MEMBERS,
    /** Most members a party may have; 0 means no limit. */
    val maxPartySize: Int = 0,
    /** How close, in pixels, a body must be to an action-combat hitbox to be struck (a body is a disc this wide). */
    val actionTargetRadiusPixels: Int = DEFAULT_ACTION_TARGET_RADIUS_PIXELS,
    /**
     * Health orbs: a slain monster drops one with this chance in percent (rare and special variants always drop three); walking near one
     * while hurt heals [healthOrbHealPercent] of life, and an orb fades after [healthOrbLifeTicks]. 0 chance turns orbs off.
     */
    val healthOrbChancePercent: Int = 0,
    val healthOrbHealPercent: Int = 12,
    val healthOrbLifeTicks: Int = 600
) {
    companion object {
        const val DEFAULT_HARVEST_COOLDOWN_TICKS = 10
        const val DEFAULT_SHOP_RANGE_TILES = 6
        const val DEFAULT_PICKUP_RANGE_TILES = 2
        const val DEFAULT_MAX_GUILD_MEMBERS = 50
        const val DEFAULT_ACTION_TARGET_RADIUS_PIXELS = 12

        const val MAX_HARVEST_COOLDOWN_TICKS = 1_200
        const val MAX_RANGE_TILES = 50
        const val MAX_DAMAGE = 100_000
        const val MAX_PLUS_PERCENT = 100
        const val MAX_GUILD_MEMBERS = 1_000
        const val MAX_PARTY_SIZE = 1_000
        const val MAX_TARGET_RADIUS_PIXELS = 200
    }
}
