package com.morgana.domain.map

import com.morgana.domain.event.PlayerQuestState
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.event.QuestGuide
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.domain.event.QuestStatus
import kotlin.math.abs

/**
 * Quest directions for both on-map targets (including those outside the client's AOI window) and cross-map targets.
 *
 * For targets on [mapId], picks the nearest matching spawn or resource node tile on the current map.
 * For targets on another map, runs a deterministic breadth-first search over the world's map graph (four seamless
 * borders + warp tiles) and picks the nearest exit tile on [mapId] toward the first hop.
 */
object QuestGuides {
    /** Most guides sent at once across all active quest objectives. */
    const val MAX_GUIDES = 12

    fun compute(
        maps: Map<Int, MapDefinition>,
        mapId: Int,
        tileX: Int,
        tileY: Int,
        quests: Map<Int, PlayerQuestState>,
        definitions: Map<Int, QuestDefinition>
    ): List<QuestGuide> {
        val here = maps[mapId] ?: return emptyList()
        val out = mutableListOf<QuestGuide>()
        for (state in quests.values) {
            if (out.size >= MAX_GUIDES) break
            if (state.status != QuestStatus.IN_PROGRESS) continue
            val def = definitions[state.questId] ?: continue
            for (index in def.objectives.indices) {
                if (out.size >= MAX_GUIDES) break
                if (state.objectiveCounts.getOrElse(index) { 0 } >= def.objectives[index].requiredCount) continue
                val obj = def.objectives[index]
                val targets = mapsHolding(maps, obj.type, obj.targetId)
                if (targets.isEmpty()) continue
                if (mapId in targets) {
                    val local = localTiles(here, obj.type, obj.targetId)
                        .minWithOrNull(compareBy<Pair<Int, Int>>({ abs(it.first - tileX) + abs(it.second - tileY) }, { it.second }, { it.first }))
                        ?: continue
                    out.add(QuestGuide(state.questId, index, mapId, local.first, local.second, here.name, here.name))
                } else {
                    val route = firstHop(maps, mapId, targets) ?: continue
                    val tile = exits(here, route.hop)
                        .minWithOrNull(compareBy<Pair<Int, Int>>({ abs(it.first - tileX) + abs(it.second - tileY) }, { it.second }, { it.first }))
                        ?: continue
                    out.add(QuestGuide(state.questId, index, mapId, tile.first, tile.second, maps.getValue(route.target).name, maps.getValue(route.hop).name))
                }
            }
        }
        return out
    }

    /** The maps where an objective's target can be found: spawns for NPCs, resource nodes, or the map itself. */
    fun mapsHolding(maps: Map<Int, MapDefinition>, type: QuestObjectiveType, targetId: Int): Set<Int> = when (type) {
        QuestObjectiveType.KILL_NPC, QuestObjectiveType.TALK_TO_NPC -> maps.values.filter { m -> m.npcSpawns.any { it.npcId == targetId } }.map { it.id }.toSet()
        QuestObjectiveType.HARVEST_RESOURCE -> maps.values.filter { m -> m.cells.any { (it.attribute as? TileAttribute.ResourceNode)?.resourceId == targetId } }.map { it.id }.toSet()
        QuestObjectiveType.VISIT_MAP -> if (targetId in maps) setOf(targetId) else emptySet()
        QuestObjectiveType.COLLECT_ITEM, QuestObjectiveType.CRAFT_RECIPE -> emptySet()
    }

    private fun localTiles(map: MapDefinition, type: QuestObjectiveType, targetId: Int): List<Pair<Int, Int>> = when (type) {
        QuestObjectiveType.KILL_NPC, QuestObjectiveType.TALK_TO_NPC ->
            map.npcSpawns.filter { it.npcId == targetId }.map { it.spawnX to it.spawnY }
        QuestObjectiveType.HARVEST_RESOURCE -> buildList {
            for (y in 0 until map.height) for (x in 0 until map.width) {
                if ((map.cells[y * map.width + x].attribute as? TileAttribute.ResourceNode)?.resourceId == targetId) {
                    add(x to y)
                }
            }
        }
        QuestObjectiveType.VISIT_MAP, QuestObjectiveType.COLLECT_ITEM, QuestObjectiveType.CRAFT_RECIPE -> emptyList()
    }

    private class Route(val target: Int, val hop: Int)

    private fun firstHop(maps: Map<Int, MapDefinition>, from: Int, targets: Set<Int>): Route? {
        val hopOf = HashMap<Int, Int>()
        var frontier = listOf(from)
        val seen = hashSetOf(from)
        while (frontier.isNotEmpty()) {
            val found = frontier.filter { it in targets }.minOrNull()
            if (found != null) return Route(found, hopOf.getValue(found))
            val next = sortedSetOf<Int>()
            for (id in frontier) {
                for (n in neighbours(maps[id])) {
                    if (n in maps && seen.add(n)) {
                        hopOf[n] = if (id == from) n else hopOf.getValue(id)
                        next.add(n)
                    }
                }
            }
            frontier = next.toList()
        }
        return null
    }

    private fun neighbours(map: MapDefinition?): List<Int> {
        if (map == null) return emptyList()
        val n = map.neighbors
        val out = sortedSetOf<Int>()
        for (id in listOf(n.northMapId, n.southMapId, n.eastMapId, n.westMapId)) if (id != 0) out.add(id)
        for (cell in map.cells) (cell.attribute as? TileAttribute.Warp)?.let { out.add(it.targetMapId) }
        return out.toList()
    }

    /** The tiles of [map] that lead to map [hop]: its warp tiles, and the open tiles of each border that meets it. */
    private fun exits(map: MapDefinition, hop: Int): List<Pair<Int, Int>> {
        val tiles = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until map.height) for (x in 0 until map.width) {
            if ((map.cells[y * map.width + x].attribute as? TileAttribute.Warp)?.targetMapId == hop) tiles.add(x to y)
        }
        fun open(x: Int, y: Int) = map.cells[y * map.width + x].attribute !is TileAttribute.Blocked
        val n = map.neighbors
        if (n.northMapId == hop) for (x in 0 until map.width) if (open(x, 0)) tiles.add(x to 0)
        if (n.southMapId == hop) for (x in 0 until map.width) if (open(x, map.height - 1)) tiles.add(x to map.height - 1)
        if (n.westMapId == hop) for (y in 0 until map.height) if (open(0, y)) tiles.add(0 to y)
        if (n.eastMapId == hop) for (y in 0 until map.height) if (open(map.width - 1, y)) tiles.add(map.width - 1 to y)
        return tiles
    }
}
