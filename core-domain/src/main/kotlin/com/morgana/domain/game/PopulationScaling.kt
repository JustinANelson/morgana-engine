package com.morgana.domain.game

import kotlinx.serialization.Serializable

/**
 * The `populationScaling` section of `game.json`: how much a map keeps supplied depends on how many players are on it. A busy map is
 * refilled in full; a quiet one refills only part of its monsters and is slower to regrow its resource nodes, so it costs the server
 * little. Without the section every map behaves as full, exactly as before.
 *
 * The density of a map is [floorPercent] with nobody on it, rising in a straight line to 100 at [playersForFull] players. It is
 * worked out from the players on the map when a monster is due to respawn or a node is gathered out, so it is deterministic (it
 * reads only simulation state) and a replay agrees. Monsters that are alive are left alone; only respawns are thinned.
 */
@Serializable
data class PopulationScalingDefinition(
    /** Players on a map at which it is fully supplied. 1 to 1000. */
    val playersForFull: Int = DEFAULT_PLAYERS_FOR_FULL,
    /** The density of an empty map, in percent. 1 to 100 (100 turns scaling off). */
    val floorPercent: Int = DEFAULT_FLOOR_PERCENT,
    /** Monsters respawn only for this share of a map's spawn slots (the lowest slot numbers), at the map's density. */
    val scaleMonsters: Boolean = true,
    /** A depleted resource node takes `respawnTicks * 100 / density` to regrow. */
    val scaleResources: Boolean = true,
    /** Maps that are never scaled (a boss arena, a town). */
    val exemptMapIds: List<Int> = emptyList(),
    /** Monsters that are never thinned (a boss, an event monster), by NPC id. */
    val exemptNpcIds: List<Int> = emptyList()
) {
    /** The density, in percent, of a map with [players] players on it. */
    fun densityPercent(players: Int): Int {
        if (floorPercent >= 100) return 100
        val n = players.coerceIn(0, playersForFull)
        return floorPercent + (100 - floorPercent) * n / playersForFull
    }

    companion object {
        const val DEFAULT_PLAYERS_FOR_FULL = 10
        const val DEFAULT_FLOOR_PERCENT = 25
        const val MAX_PLAYERS_FOR_FULL = 1000
    }
}
