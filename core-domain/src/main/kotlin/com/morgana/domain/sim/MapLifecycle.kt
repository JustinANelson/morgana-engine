package com.morgana.domain.sim

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.TileCoord
import com.morgana.domain.spatial.WorldPosition

/**
 * Keeping a running world consistent when a content map changes under it (a hot reload of files an author or an LLM wrote, an `F1` edit).
 * [WorldSimulation.registerMap] already keeps unchanged monsters and resource nodes; these cover the other two cases: a tile a player
 * stands on that is no longer open, and a map that was removed.
 */

/** True when a character can stand on ([x], [y]) of [mapId]: inside the map, not blocked and not under a blocking world object. */
fun WorldSimulation.isStandable(mapId: Int, x: Int, y: Int): Boolean {
    val map = maps[mapId] ?: return false
    if (!map.isInBounds(TileCoord(x, y))) return false
    val attribute = map.getCell(x, y)?.attribute
    if (attribute is TileAttribute.Blocked) return false
    return !isTileBlockedByObject(mapId, x, y)
}

/**
 * Moves every player on [mapId] whose tile is no longer standable to the nearest standable tile (searching outwards up to [maxRadius]
 * tiles; a player with none nearby goes to their class start). Returns the events for the clients. Players on open tiles are not touched.
 */
fun WorldSimulation.settlePlayersOn(mapId: Int, maxRadius: Int = 12): List<WorldSimulationEvent> {
    val map = maps[mapId] ?: return emptyList()
    val out = mutableListOf<WorldSimulationEvent>()
    for (id in players.keys.sorted()) {
        val player = players[id] ?: continue
        if (player.position.mapId != mapId) continue
        if (isStandable(mapId, player.position.tileX, player.position.tileY)) continue
        val spot = nearestStandable(mapId, player.position.tileX, player.position.tileY, maxRadius)
        val moved = if (spot != null) {
            player.copy(position = WorldPosition.fromTile(mapId, spot.x, spot.y, player.position.direction, map.tileSize))
        } else {
            player.copy(position = classStart(player))
        }
        players[id] = moved
        out += WorldSimulationEvent.PlayerMoved(moved, mapId, moved.position.mapId)
        out += WorldSimulationEvent.CharacterUpdated(moved)
        out += tellPlayer(moved, "The ground under you changed; you were moved to the nearest open spot.", "INFO")
    }
    return out
}

private fun WorldSimulation.nearestStandable(mapId: Int, x: Int, y: Int, maxRadius: Int): TileCoord? {
    for (radius in 1..maxRadius) {
        var best: TileCoord? = null
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != radius) continue
            val c = TileCoord(x + dx, y + dy)
            if (!isStandable(mapId, c.x, c.y)) continue
            // The closest by straight distance; ties go to the lowest y, then x, so the result is the same on every run.
            val better = best == null || dist2(c, x, y) < dist2(best, x, y) ||
                (dist2(c, x, y) == dist2(best, x, y) && (c.y < best.y || (c.y == best.y && c.x < best.x)))
            if (better) best = c
        }
        if (best != null) return best
    }
    return null
}

private fun dist2(c: TileCoord, x: Int, y: Int): Int = (c.x - x) * (c.x - x) + (c.y - y) * (c.y - y)

private fun WorldSimulation.classStart(player: CharacterState): WorldPosition {
    val cls = classes[player.classId]
    val startMap = maps[cls?.startMapId ?: 1] ?: maps.values.filter { !MapInstances.isInstanceId(it.id) }.minByOrNull { it.id }
    return WorldPosition.fromTile(startMap?.id ?: 1, cls?.startX ?: 12, cls?.startY ?: 9, Direction.DOWN, startMap?.tileSize ?: WorldPosition.DEFAULT_TILE_SIZE)
}

/**
 * Removes a content map that no longer exists on disk. Players on it are first sent to their class start; monsters, loot, projectiles,
 * resource nodes, doors and world objects on it go with it. Refuses (false) for an instance copy, an unknown map, map 1 (the fallback start), or a map a class starts
 * on, because there would be nowhere to send people. The events to tell clients are added to [out].
 */
fun WorldSimulation.unloadContentMap(mapId: Int, out: MutableList<WorldSimulationEvent>): Boolean {
    if (MapInstances.isInstanceId(mapId) || mapId !in maps) return false
    if (mapId == 1 || classes.values.any { it.startMapId == mapId }) return false
    for (id in players.keys.sorted()) {
        val player = players[id] ?: continue
        if (player.position.mapId != mapId) continue
        val moved = player.copy(position = classStart(player), instanceReturn = null)
        players[id] = moved
        out += WorldSimulationEvent.PlayerMoved(moved, mapId, moved.position.mapId)
        out += WorldSimulationEvent.CharacterUpdated(moved)
        out += tellPlayer(moved, "That place no longer exists. You were sent back to the start.", "WARNING")
    }
    for (obj in worldObjectsOn(mapId).toList()) removeWorldObject(obj.id, "map removed", out)
    maps.remove(mapId)
    mapNpcs.remove(mapId)
    mapGroundItems.remove(mapId)
    mapProjectiles.remove(mapId)
    mapResources.remove(mapId)
    unlockedDoors.remove(mapId)
    returnPortals.values.removeIf { it.targetMapId == mapId }
    return true
}
