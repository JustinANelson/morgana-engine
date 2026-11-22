package com.morgana.server

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.sim.WorldSimulation
import com.morgana.protocol.NeighborMapEntities
import com.morgana.protocol.PublicPlayerSnapshot

/**
 * Works out which entities of the neighbouring maps a player should see across the shared border ("soft seams"), so the
 * edge of a town shows the monsters, players and loot beyond it instead of empty ground.
 *
 * Only a player standing within [SEAM_TILES] of a border receives the entities on the other side of it, and only those
 * within [SEAM_TILES] of that same border. Cross-border interaction (attacking, chatting) is deliberately not part of
 * this: the other map's entities are visible but still belong to their own map's simulation.
 */
class SeamViews(
    private val simulation: WorldSimulation,
    private val toPublic: (CharacterState) -> PublicPlayerSnapshot
) {
    private val lastSent = HashMap<String, List<NeighborMapEntities>>()

    fun compute(player: CharacterState): List<NeighborMapEntities> {
        val map = simulation.maps[player.position.mapId] ?: return emptyList()
        val x = player.position.tileX
        val y = player.position.tileY
        val result = ArrayList<NeighborMapEntities>(2)
        val n = map.neighbors

        fun add(neighborId: Int, inRange: (tileX: Int, tileY: Int, neighborWidth: Int, neighborHeight: Int) -> Boolean) {
            if (neighborId <= 0 || result.any { it.mapId == neighborId }) return
            val nm = simulation.maps[neighborId] ?: return
            val npcs = simulation.mapNpcs[neighborId].orEmpty()
                .filter { it.isAlive && inRange(it.tileX, it.tileY, nm.width, nm.height) }
                .sortedBy { it.instanceId }
            val players = simulation.players.values
                .filter { it.position.mapId == neighborId && inRange(it.position.tileX, it.position.tileY, nm.width, nm.height) }
                .sortedBy { it.id }
                .map(toPublic)
            val items = simulation.mapGroundItems[neighborId].orEmpty()
                .filter { inRange(it.tileX, it.tileY, nm.width, nm.height) }
                .sortedBy { it.instanceId }
            if (npcs.isNotEmpty() || players.isNotEmpty() || items.isNotEmpty()) {
                result.add(NeighborMapEntities(neighborId, npcs, players, items))
            }
        }

        if (map.width - 1 - x < SEAM_TILES) add(n.eastMapId) { tx, _, _, _ -> tx < SEAM_TILES }
        if (x < SEAM_TILES) add(n.westMapId) { tx, _, w, _ -> tx >= w - SEAM_TILES }
        if (y < SEAM_TILES) add(n.northMapId) { _, ty, _, h -> ty >= h - SEAM_TILES }
        if (map.height - 1 - y < SEAM_TILES) add(n.southMapId) { _, ty, _, _ -> ty < SEAM_TILES }
        return result
    }

    /** Records [views] as sent to [characterId]; returns true when they differ from what that player last received. */
    fun changed(characterId: String, views: List<NeighborMapEntities>): Boolean {
        val previous = lastSent[characterId].orEmpty()
        if (previous == views) return false
        if (views.isEmpty()) lastSent.remove(characterId) else lastSent[characterId] = views
        return true
    }

    /** Forces the next [changed] call to report a change (new map snapshot, so the client starts from nothing). */
    fun forget(characterId: String) {
        lastSent.remove(characterId)
    }

    companion object {
        /** How far from a shared border, in tiles, entities are shared and the player starts to receive them. */
        const val SEAM_TILES = 16

        /** Seam views are recomputed every this many ticks (4 per second at 20 TPS). */
        const val INTERVAL_TICKS = 5L
    }
}
