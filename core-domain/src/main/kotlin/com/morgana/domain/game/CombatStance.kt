package com.morgana.domain.game

import kotlinx.serialization.Serializable

/** A character's chosen stance towards other players (see [CombatStanceDefinition]). */
enum class Stance(val label: String) {
    /** Nothing hits them and they hit nothing; hitting a monster switches them to [PVE]. */
    SAFE("Safe"),

    /** Monsters only: other players can neither hit them nor be hit by them. */
    PVE("PvE"),

    /** May hit and be hit by other characters in PvP stance (the PK mode and safe zones still apply). */
    PVP("PvP")
}

/** Whether a place lets characters take the PvP stance. */
enum class StancePermission {
    /** Nobody can be in PvP stance here; the stance is ignored (it is kept, and takes effect again elsewhere). */
    NONE,

    /** Characters choose. */
    OPTIONAL,

    /** Everyone is in PvP stance here whatever they chose and cannot leave it. */
    FORCED
}

/** The maps [mapIds] share one [permission]. */
@Serializable
data class StanceMapRule(val mapIds: List<Int> = emptyList(), val permission: StancePermission = StancePermission.NONE)

/**
 * The `combatStance` section of `game.json`: a stance each character chooses (SAFE, PvE or PvP), layered over the PK mode.
 * Needs the `combatStance` system. Without this section the system does nothing and every game behaves as before.
 */
@Serializable
data class CombatStanceDefinition(
    /** The permission of maps no rule lists. */
    val defaultPermission: StancePermission = StancePermission.NONE,
    val maps: List<StanceMapRule> = emptyList(),
    /** The stance a new or unset character has. Never [Stance.PVP]. */
    val defaultStance: Stance = Stance.PVE,
    /** Ticks the PvP stance takes to arm after it is asked for (taking a hit cancels it). 0 arms at once. */
    val armTicks: Int = DEFAULT_ARM_TICKS,
    /** Ticks without dealing or taking damage before a character may leave the PvP stance. */
    val disarmTicks: Int = DEFAULT_DISARM_TICKS,
    /** Ticks without dealing or taking damage before a character may switch between SAFE and PvE. */
    val switchTicks: Int = DEFAULT_SWITCH_TICKS
) {
    fun permissionOn(mapId: Int): StancePermission = maps.firstOrNull { mapId in it.mapIds }?.permission ?: defaultPermission

    companion object {
        const val DEFAULT_ARM_TICKS = 200
        const val DEFAULT_DISARM_TICKS = 2400
        const val DEFAULT_SWITCH_TICKS = 100
        const val MAX_TICKS = 1_728_000 // 24 hours at 20 ticks per second
    }
}
