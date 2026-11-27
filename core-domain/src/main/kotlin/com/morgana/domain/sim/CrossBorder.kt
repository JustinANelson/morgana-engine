package com.morgana.domain.sim

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.spatial.WorldPosition

/**
 * Fighting across a map border in a seamless world (`worldLayout`). Each map is still its own simulation, so a fight that spans a border
 * works by *translating* the other side into this one's coordinates for the moment of the blow, never by moving anyone:
 *
 *  * a player's skill and arrows find the monsters of the maps around them ([hostileNpcsInReach]) at where they stand in the player's
 *    own map coordinates, and the damage goes to the monster on its own map;
 *  * a monster notices, chases to its map's edge and strikes the players of the maps around it by working with a *ghost* of the player
 *    ([ghostOf]): the same character with their position shifted into the monster's map coordinates. Ghosts are only for the geometry;
 *    anything the monster code writes back is put right at once ([restoreBorderGhosts]) so a player's real position never changes.
 */
const val BORDER_COMBAT_TILES = 12

/** A monster within reach of a player and where its map's top left tile sits from the player's map's, in tiles. */
internal class ReachNpc(val npc: MapNpcInstance, val shiftTilesX: Int, val shiftTilesY: Int)

/**
 * The hostile, living monsters within [reachTiles] of tile ([originX], [originY]) on map [mapId], including those on the maps around it
 * in a seamless world, each with the shift that expresses its position in [mapId]'s coordinates.
 */
internal fun WorldSimulation.hostileNpcsInReach(mapId: Int, originX: Int, originY: Int, reachTiles: Int): List<ReachNpc> {
    val out = ArrayList<ReachNpc>()
    for (npc in mapNpcs[mapId].orEmpty()) {
        if (npc.isAlive && isHostileNpc(npc) && kotlin.math.abs(npc.tileX - originX) <= reachTiles && kotlin.math.abs(npc.tileY - originY) <= reachTiles) out += ReachNpc(npc, 0, 0)
    }
    if (!layout.isPlaced(mapId)) return out
    for ((other, offset) in layout.around(mapId, reachTiles)) {
        for (npc in mapNpcs[other.id].orEmpty()) {
            if (!npc.isAlive || !isHostileNpc(npc)) continue
            if (kotlin.math.abs(npc.tileX + offset.x - originX) <= reachTiles && kotlin.math.abs(npc.tileY + offset.y - originY) <= reachTiles) {
                out += ReachNpc(npc, offset.x, offset.y)
            }
        }
    }
    return out
}

/**
 * [p] as seen from map [toMapId]: [p] itself when they are on it, or (in a seamless world, within [BORDER_COMBAT_TILES] of its edge) a
 * copy whose tile and pixel position are shifted into [toMapId]'s coordinates. The copy keeps its real `mapId`, and the real position is
 * remembered so [restoreBorderGhosts] can undo anything written back. Null when they are too far to matter.
 */
internal fun WorldSimulation.ghostOf(p: CharacterState, toMapId: Int): CharacterState? {
    if (p.position.mapId == toMapId) return p
    if (!layout.isPlaced(toMapId) || !layout.isPlaced(p.position.mapId)) return null
    val toMap = maps[toMapId] ?: return null
    val offset = layout.offsetBetween(toMapId, p.position.mapId) ?: return null
    val tx = p.position.tileX + offset.x
    val ty = p.position.tileY + offset.y
    if (tx < -BORDER_COMBAT_TILES || ty < -BORDER_COMBAT_TILES || tx >= toMap.width + BORDER_COMBAT_TILES || ty >= toMap.height + BORDER_COMBAT_TILES) return null
    val ts = toMap.tileSize
    val ghost = p.position.copy(tileX = tx, tileY = ty, pixelX = p.position.pixelX + offset.x * ts, pixelY = p.position.pixelY + offset.y * ts)
    borderGhosts.getOrPut(p.id) { linkedSetOf() }.add(ghost to p.position)
    return p.copy(position = ghost)
}

/** The player [id] as seen from map [mapId] (see [ghostOf]), or null if they are not there or near enough. */
internal fun WorldSimulation.playerOnOrNear(mapId: Int, id: String): CharacterState? = players[id]?.let { ghostOf(it, mapId) }

/** Every player on map [mapId], and those just across its borders as ghosts. */
internal fun WorldSimulation.playersOnOrNear(mapId: Int): List<CharacterState> =
    if (!layout.isPlaced(mapId)) players.values.filter { it.position.mapId == mapId } else players.values.mapNotNull { ghostOf(it, mapId) }

/** Puts a player's real position back if monster code wrote a ghost's shifted position into the world. */
internal fun WorldSimulation.restoreBorderGhosts() {
    if (borderGhosts.isEmpty()) return
    for ((id, pairs) in borderGhosts) {
        val cur = players[id] ?: continue
        val real = pairs.firstOrNull { it.first == cur.position }?.second ?: continue
        players[id] = cur.copy(position = real)
    }
}

/** Corrects the character updates in [out] (from [from] on) that carry a ghost's shifted position, so a client never receives it. */
internal fun WorldSimulation.fixBorderGhostEvents(out: MutableList<WorldSimulationEvent>, from: Int) {
    if (borderGhosts.isEmpty()) return
    for (i in from until out.size) {
        val ev = out[i]
        if (ev is WorldSimulationEvent.CharacterUpdated) {
            val real = borderGhosts[ev.character.id]?.firstOrNull { it.first == ev.character.position }?.second ?: continue
            out[i] = WorldSimulationEvent.CharacterUpdated(ev.character.copy(position = real))
        }
    }
}

/** A monster's position (tile and pixels) is shifted by ([dx], [dy]) tiles: for geometry in another map's coordinates. */
internal fun shiftPosition(p: WorldPosition, dx: Int, dy: Int, ts: Int): WorldPosition = p.copy(tileX = p.tileX + dx, tileY = p.tileY + dy, pixelX = p.pixelX + dx * ts, pixelY = p.pixelY + dy * ts)

/**
 * The cell at tile ([tx], [ty]) of [map], looking through to the neighbouring map when the tile lies beyond the edge in a seamless
 * world; null where there is no map (the edge of the world). A border is not a wall: anything can stand, walk or fly across it.
 */
internal fun WorldSimulation.cellThrough(map: com.morgana.domain.map.MapDefinition, tx: Int, ty: Int): com.morgana.domain.map.MapCell? {
    if (map.isInBounds(tx, ty)) return map.getCell(tx, ty)
    if (!layout.isPlaced(map.id)) return null
    val across = layout.crossing(map, tx, ty) ?: return null
    return across.map.getCell(across.tileX, across.tileY)
}

/**
 * Whether a body can step from [from] to [to] on [map]: the map's own rules when both tiles are on it, otherwise (a step over a border in
 * a seamless world) only that the tile reached is not blocked and is on a map at all.
 */
internal fun WorldSimulation.walkableThrough(
    map: com.morgana.domain.map.MapDefinition, from: com.morgana.domain.spatial.TileCoord, to: com.morgana.domain.spatial.TileCoord,
    dir: com.morgana.domain.spatial.Direction, isNpc: Boolean
): Boolean {
    if (map.isInBounds(from) && map.isInBounds(to)) return map.canTraverse(from, to, dir, isNpc = isNpc)
    val cell = cellThrough(map, to.x, to.y) ?: return false
    return cell.attribute !is com.morgana.domain.map.TileAttribute.Blocked
}

/** Whether a living body (a monster, or a player) stands on tile ([tx], [ty]) of [map], counting the other side of a border. */
internal fun WorldSimulation.bodyAtTileThrough(map: com.morgana.domain.map.MapDefinition, tx: Int, ty: Int, includePlayers: Boolean = true): Boolean {
    var mapId = map.id
    var x = tx
    var y = ty
    if (!map.isInBounds(tx, ty)) {
        val across = (if (layout.isPlaced(map.id)) layout.crossing(map, tx, ty) else null) ?: return false
        mapId = across.map.id; x = across.tileX; y = across.tileY
    }
    return mapNpcs[mapId]?.any { it.isAlive && it.tileX == x && it.tileY == y } == true ||
        (includePlayers && players.values.any { it.position.mapId == mapId && it.isAlive(game.lifeVitalId) && it.position.tileX == x && it.position.tileY == y })
}

/**
 * Hands over the players whose position has gone beyond their map's edge (a dash, a leap or a dodge that carried them over a border) to
 * the map that lies there, the same way walking does, and moves what their skill is aimed at along with them.
 */
internal fun WorldSimulation.settleBorderCrossings(out: MutableList<WorldSimulationEvent>) {
    if (layout.isEmpty) return
    for (id in players.keys.toList()) {
        val p = players[id] ?: continue
        val map = maps[p.position.mapId] ?: continue
        if (map.isInBounds(p.position.tileX, p.position.tileY)) continue
        val across = layout.crossing(map, p.position.tileX, p.position.tileY) ?: continue
        val ts = across.map.tileSize
        val sx = across.shiftTilesX * ts
        val sy = across.shiftTilesY * ts
        val moved = p.copy(
            position = p.position.copy(mapId = across.map.id, tileX = across.tileX, tileY = across.tileY, pixelX = p.position.pixelX + sx, pixelY = p.position.pixelY + sy)
        )
        players[id] = moved
        actionStates[id]?.let { st -> actionStates[id] = st.copy(aimX = st.aimX + sx, aimY = st.aimY + sy, leapX = st.leapX + sx, leapY = st.leapY + sy) }
        out.add(WorldSimulationEvent.PlayerMoved(moved, map.id, across.map.id))
        out.add(WorldSimulationEvent.CharacterUpdated(moved))
        checkQuestVisitMap(moved, across.map.id, out)
        fireMapEntryEvents(id, out)
    }
}

/**
 * Where player [p] stands in map [mapId]'s pixel space as a shift to add to their own pixel position, when they are on a map around it within
 * [BORDER_COMBAT_TILES] of its edge in a seamless world; null when they are on a map too far away (or none is placed). A pure test: unlike
 * [ghostOf] it remembers nothing.
 */
internal fun WorldSimulation.pixelShiftToMap(p: CharacterState, mapId: Int): Pair<Float, Float>? {
    if (p.position.mapId == mapId) return 0f to 0f
    if (!layout.isPlaced(mapId) || !layout.isPlaced(p.position.mapId)) return null
    val toMap = maps[mapId] ?: return null
    val offset = layout.offsetBetween(mapId, p.position.mapId) ?: return null
    val tx = p.position.tileX + offset.x
    val ty = p.position.tileY + offset.y
    if (tx < -BORDER_COMBAT_TILES || ty < -BORDER_COMBAT_TILES || tx >= toMap.width + BORDER_COMBAT_TILES || ty >= toMap.height + BORDER_COMBAT_TILES) return null
    return (offset.x * toMap.tileSize).toFloat() to (offset.y * toMap.tileSize).toFloat()
}

/** Whether [p] is on map [mapId] or just across one of its borders (see [pixelShiftToMap]). */
internal fun WorldSimulation.isOnOrNearMap(p: CharacterState, mapId: Int): Boolean = pixelShiftToMap(p, mapId) != null

/** A ground item within reach of a player and where its map's top left tile sits from the player's map's, in tiles. */
internal class ReachItem(val sourceMapId: Int, val item: com.morgana.domain.entity.MapGroundItem, val shiftTilesX: Int, val shiftTilesY: Int)

/** Loot lying within [range] tiles of tile ([tx], [ty]) on map [mapId], including that on the maps around it in a seamless world. */
internal fun WorldSimulation.groundItemsInReach(mapId: Int, tx: Int, ty: Int, range: Int): List<ReachItem> {
    val out = ArrayList<ReachItem>()
    for (it in mapGroundItems[mapId].orEmpty()) if (kotlin.math.abs(it.tileX - tx) <= range && kotlin.math.abs(it.tileY - ty) <= range) out += ReachItem(mapId, it, 0, 0)
    if (!layout.isPlaced(mapId)) return out
    for ((other, offset) in layout.around(mapId, range)) {
        for (it in mapGroundItems[other.id].orEmpty()) {
            if (kotlin.math.abs(it.tileX + offset.x - tx) <= range && kotlin.math.abs(it.tileY + offset.y - ty) <= range) out += ReachItem(other.id, it, offset.x, offset.y)
        }
    }
    return out
}

/**
 * How many tiles apart [a] and [b] stand (the larger of the two axes), measuring across a map border in a seamless world; null when they
 * are on different maps that are not next to each other (or no layout is on). On one map it is the plain tile distance.
 */
internal fun WorldSimulation.tileDistanceAcross(a: CharacterState, b: CharacterState): Int? {
    if (a.position.mapId == b.position.mapId) return a.position.tileCoord.chebyshevDistanceTo(b.position.tileCoord)
    if (!layout.isPlaced(a.position.mapId) || !layout.isPlaced(b.position.mapId)) return null
    val offset = layout.offsetBetween(a.position.mapId, b.position.mapId) ?: return null
    val dx = kotlin.math.abs(b.position.tileX + offset.x - a.position.tileX)
    val dy = kotlin.math.abs(b.position.tileY + offset.y - a.position.tileY)
    return maxOf(dx, dy).takeIf { it <= BORDER_COMBAT_TILES * 3 }
}

