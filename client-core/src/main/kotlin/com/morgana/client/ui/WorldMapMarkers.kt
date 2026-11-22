package com.morgana.client.ui

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.sim.LevelOffsetRules
import com.morgana.protocol.WorldMapSpawn

/**
 * Pure model of the enemy markers on the world map: which spawns are enemies, how they group into one marker per
 * creature per area (so a pack is one "Wolf Lv.12 x4", not four overlapping labels), and what colour shows the danger.
 */
object WorldMapMarkers {
    /** Spawns of one creature closer than this many tiles (in a grid cell) share a marker. */
    const val CLUSTER_TILES = 12

    /** Colour for an enemy tier that would vanish against the dark map (the "black" tier): a light grey instead. */
    private const val HIDDEN_TIER_HEX = "111827"
    private const val VISIBLE_GREY_HEX = "9CA3AF"

    data class Marker(
        /** Average spawn position in tiles. */
        val x: Float,
        val y: Float,
        val npcId: Int,
        val name: String,
        val level: Int,
        /** How many spawn points the marker stands for. */
        val count: Int,
        val boss: Boolean
    ) {
        val label: String get() = "Lv.$level"
        val caption: String get() = if (count > 1) "$name Lv.$level x$count" else "$name Lv.$level"
    }

    /** True for a creature the player fights for experience: the same test the map's level summary uses. */
    fun isEnemy(def: NpcDefinition?): Boolean =
        def != null && (def.behavior == NpcBehavior.PASSIVE || def.behavior == NpcBehavior.ATTACK_ON_SIGHT) && def.xpReward > 0L

    /**
     * One [Marker] per creature per [CLUSTER_TILES] cell, for the spawns where [isVisible] is true (the explored ground).
     * Ordered by level so a stronger creature is drawn (and labelled) on top of a weaker one.
     */
    fun markers(
        spawns: List<WorldMapSpawn>,
        npcsById: Map<Int, NpcDefinition>,
        isVisible: (Int, Int) -> Boolean = { _, _ -> true }
    ): List<Marker> {
        val groups = linkedMapOf<Triple<Int, Int, Int>, MutableList<WorldMapSpawn>>()
        for (s in spawns) {
            val def = npcsById[s.npcId]
            if (!isEnemy(def) || !isVisible(s.x, s.y)) continue
            groups.getOrPut(Triple(s.npcId, Math.floorDiv(s.x, CLUSTER_TILES), Math.floorDiv(s.y, CLUSTER_TILES))) { mutableListOf() } += s
        }
        return groups.map { (key, list) ->
            val def = npcsById.getValue(key.first)
            Marker(
                x = list.map { it.x }.average().toFloat(),
                y = list.map { it.y }.average().toFloat(),
                npcId = def.id,
                name = def.name,
                level = def.level,
                count = list.size,
                boss = DungeonHudModel.isBossNpc(def.id, def)
            )
        }.sortedWith(compareBy({ it.level }, { it.npcId }))
    }

    /** `RRGGBB` colour for a creature of [level] as seen by a player of [playerLevel] (white when the player's level is unknown). */
    fun colourHex(playerLevel: Int?, level: Int): String {
        if (playerLevel == null) return "E5E7EB"
        val hex = LevelOffsetRules.tier(playerLevel, level).colorHex
        return if (hex == HIDDEN_TIER_HEX) VISIBLE_GREY_HEX else hex
    }
}
