package com.morgana.client.input

import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.TileCoord

/** A planned route. [facesGoal] means the last step only turns toward a goal tile that cannot be entered (an NPC, a tree). */
data class TapPath(val steps: List<Direction>, val facesGoal: Boolean)

/**
 * Deterministic breadth-first search over the four cardinal directions. The caller supplies [canStep], so the
 * client uses exactly the walkability rules its own movement prediction uses (blocked tiles, directional blocks,
 * live NPCs, depleted resources). Neighbour order is fixed, so the same map always gives the same route.
 */
object TapPathfinder {
    const val MAX_NODES = 4096

    private val ORDER = listOf(Direction.UP, Direction.DOWN, Direction.LEFT, Direction.RIGHT)

    fun find(
        start: TileCoord,
        goal: TileCoord,
        width: Int,
        height: Int,
        canStep: (from: TileCoord, to: TileCoord, direction: Direction) -> Boolean
    ): TapPath? {
        if (start == goal) return TapPath(emptyList(), facesGoal = false)
        if (goal.x !in 0 until width || goal.y !in 0 until height) return null

        val cameFrom = HashMap<TileCoord, Pair<TileCoord, Direction>>()
        val visited = HashSet<TileCoord>().apply { add(start) }
        val queue = ArrayDeque<TileCoord>().apply { add(start) }

        fun route(end: TileCoord): List<Direction> {
            val out = ArrayList<Direction>()
            var cur = end
            while (cur != start) {
                val (prev, dir) = cameFrom.getValue(cur)
                out.add(dir)
                cur = prev
            }
            out.reverse()
            return out
        }

        while (queue.isNotEmpty() && visited.size <= MAX_NODES) {
            val node = queue.removeFirst()
            for (dir in ORDER) {
                val next = node.step(dir)
                if (next.x !in 0 until width || next.y !in 0 until height) continue
                if (next == goal) {
                    return if (canStep(node, next, dir)) {
                        cameFrom[next] = node to dir
                        TapPath(route(next), facesGoal = false)
                    } else {
                        TapPath(route(node) + dir, facesGoal = true)
                    }
                }
                if (next in visited || !canStep(node, next, dir)) continue
                visited.add(next)
                cameFrom[next] = node to dir
                queue.add(next)
            }
        }
        return null
    }
}

/**
 * Walks the player to a tapped tile by feeding ordinary move directions, one per move tick, so the server sees the same
 * input as key presses. The route is re-planned every step (NPCs move, resources deplete) and the walk is abandoned if
 * the character stops making progress, if the map changes, or on any manual input.
 */
class TapMoveController(private val maxStalledAttempts: Int = 4) {
    private var goal: TileCoord? = null
    private var mapId: Int? = null
    private var lastTile: TileCoord? = null
    private var stalled = 0

    val isActive: Boolean get() = goal != null

    fun setGoal(mapId: Int, tile: TileCoord) {
        this.goal = tile
        this.mapId = mapId
        lastTile = null
        stalled = 0
    }

    fun cancel() {
        goal = null
        mapId = null
        lastTile = null
        stalled = 0
    }

    /**
     * The next direction to send, or null when there is nothing to do (no goal, arrived, no route, or stuck), in which
     * case the walk is finished. In sub-tile mode ([subTile]) arrival also waits until the character is lined up with the tile.
     */
    fun nextDirection(
        currentMapId: Int,
        tile: TileCoord,
        pixelX: Float,
        pixelY: Float,
        tileSize: Int,
        subTile: Boolean,
        width: Int,
        height: Int,
        canStep: (TileCoord, TileCoord, Direction) -> Boolean
    ): Direction? {
        val target = goal ?: return null
        if (currentMapId != mapId) { cancel(); return null }

        if (tile == target) {
            if (subTile) {
                val dx = target.x * tileSize - pixelX
                val dy = target.y * tileSize - pixelY
                val tolerance = 4f
                val alignDir = when {
                    kotlin.math.abs(dx) >= kotlin.math.abs(dy) && kotlin.math.abs(dx) >= tolerance ->
                        if (dx > 0) Direction.RIGHT else Direction.LEFT
                    kotlin.math.abs(dy) >= tolerance -> if (dy > 0) Direction.DOWN else Direction.UP
                    else -> null
                }
                if (alignDir != null && registerAttempt(tile)) return alignDir
            }
            cancel()
            return null
        }

        val path = TapPathfinder.find(tile, target, width, height, canStep)
        if (path == null || path.steps.isEmpty()) { cancel(); return null }
        val first = path.steps.first()
        if (path.facesGoal && path.steps.size == 1) {
            cancel() // one turn toward the thing the player tapped, then done
            return first
        }
        if (!registerAttempt(tile)) return null
        return first
    }

    /** Counts consecutive attempts from the same tile; gives up (and cancels) when the character is stuck. */
    private fun registerAttempt(tile: TileCoord): Boolean {
        if (tile == lastTile) stalled++ else { lastTile = tile; stalled = 0 }
        // Grid steps change the tile every attempt; sub-tile steps need several (4 x 8 px per tile).
        if (stalled >= maxStalledAttempts * 4) { cancel(); return false }
        return true
    }
}
