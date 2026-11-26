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
