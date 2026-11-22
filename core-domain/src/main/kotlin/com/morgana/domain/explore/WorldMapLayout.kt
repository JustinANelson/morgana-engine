package com.morgana.domain.explore

/** One map and the maps its four borders lead to (0 = no neighbour), as far as the viewer knows them. */
data class MapNode(val id: Int, val north: Int = 0, val south: Int = 0, val east: Int = 0, val west: Int = 0)

/** A cell on the world map grid: [x] grows east, [y] grows south. */
data class GridPos(val x: Int, val y: Int)

/**
 * Pure layout of the world map: maps are placed on a grid by following their north, south, east and west links, so a
 * road that runs east across three maps appears as three boxes in a row. Maps that join the rest only through warps
 * (or not at all) form their own groups, set side by side. Deterministic: the same nodes always lay out the same way.
 */
object WorldMapLayout {
    /**
     * Positions for every id in [nodes], with the top-left of the whole picture at (0, 0). A link to an id that is not in
     * [nodes] is ignored. When two maps claim the same cell (an inconsistent world), the later one moves to the nearest free cell.
     */
    fun layout(nodes: List<MapNode>): Map<Int, GridPos> {
        val byId = nodes.associateBy { it.id }
        val placed = LinkedHashMap<Int, GridPos>()
        var cursorX = 0
        for (start in nodes) {
            if (start.id in placed) continue
            val group = LinkedHashMap<Int, GridPos>()
            val taken = HashSet<GridPos>()
            fun put(id: Int, wanted: GridPos): GridPos {
                var pos = wanted
                var ring = 0
                while (pos in taken) {
                    ring++
                    pos = nearestFree(wanted, ring, taken)
                }
                taken += pos
                group[id] = pos
                return pos
            }
            put(start.id, GridPos(0, 0))
            val queue = ArrayDeque(listOf(start.id))
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                val node = byId.getValue(id)
                val at = group.getValue(id)
                for ((target, dx, dy) in listOf(Triple(node.north, 0, -1), Triple(node.south, 0, 1), Triple(node.east, 1, 0), Triple(node.west, -1, 0))) {
                    if (target == 0 || target !in byId || target in placed || target in group) continue
                    put(target, GridPos(at.x + dx, at.y + dy))
                    queue.addLast(target)
                }
            }
            val minX = group.values.minOf { it.x }
            val minY = group.values.minOf { it.y }
            val maxX = group.values.maxOf { it.x }
            for ((id, p) in group) placed[id] = GridPos(p.x - minX + cursorX, p.y - minY)
            cursorX += maxX - minX + 2
        }
        return placed
    }

    private fun nearestFree(around: GridPos, ring: Int, taken: Set<GridPos>): GridPos {
        for (dy in -ring..ring) for (dx in -ring..ring) {
            if (maxOf(Math.abs(dx), Math.abs(dy)) != ring) continue
            val p = GridPos(around.x + dx, around.y + dy)
            if (p !in taken) return p
        }
        return GridPos(around.x + ring + 1, around.y)
    }
}
