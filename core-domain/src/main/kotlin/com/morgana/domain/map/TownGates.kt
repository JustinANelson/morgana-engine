package com.morgana.domain.map

import com.morgana.domain.game.TownGateTown

/**
 * Finds the nearest town for a Town Portal: a breadth-first search over the world's map graph, whose links are the
 * four seamless borders and every warp tile. Fewest hops wins; a tie goes to the town listed first. Pure and deterministic.
 */
object TownGates {

    fun nearest(maps: Map<Int, MapDefinition>, fromMapId: Int, towns: List<TownGateTown>): TownGateTown? {
        if (towns.isEmpty()) return null
        val byMap = towns.associateBy { it.mapId }
        val seen = linkedSetOf(fromMapId)
        var frontier = listOf(fromMapId)
        while (frontier.isNotEmpty()) {
            val hits = frontier.mapNotNull { byMap[it] }
            if (hits.isNotEmpty()) return hits.minBy { towns.indexOf(it) }
            val next = linkedSetOf<Int>()
            for (id in frontier) {
                for (neighbour in links(maps[id])) if (neighbour != 0 && seen.add(neighbour)) next.add(neighbour)
            }
            frontier = next.toList()
        }
        return towns.first()
    }

    private fun links(map: MapDefinition?): List<Int> {
        if (map == null) return emptyList()
        val n = map.neighbors
        val out = mutableListOf(n.northMapId, n.southMapId, n.eastMapId, n.westMapId)
        for (cell in map.cells) (cell.attribute as? TileAttribute.Warp)?.let { out.add(it.targetMapId) }
        return out
    }
}
