package com.morgana.domain.sim

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition

/**
 * Instanced maps: a map marked `instanced` in its content is a template. Warping into it puts the player in a private
 * copy shared with their party (or alone when they have none), created on first entry and closed once it has been
 * empty for [InstanceSettings.emptyTicks].
 *
 * A copy is an ordinary [MapDefinition] registered in [WorldSimulation.maps] under an id of at least [FIRST_ID], so
 * every per-map system (NPCs, ground items, projectiles, chat, snapshots) works unchanged. Copies are never content:
 * they are left out of [WorldSimulation.contentMaps], the editor catalogue and the content fingerprint, and content
 * may not use ids in their range. Events, quests and other content that name a map refer to the template; use
 * [WorldSimulation.contentMapId] to translate.
 *
 * This file is the kernel's map-copy infrastructure, shared with the arena: the reserved ids, [MapInstance], closing copies that
 * stand empty, the wipe rule, and putting a character back after login. The *policy* of who gets which copy (per party, per guild,
 * generated floors, difficulty tiers and scaling) is the `instancing` system, `InstancingSystem`, reached through the
 * [WarpRouter] seam.
 */
object MapInstances {
    /** The first id handed to an instance. Content map ids must stay below it. */
    const val FIRST_ID: Int = 1_000_000

    fun isInstanceId(mapId: Int): Boolean = mapId >= FIRST_ID

    /** Whose instance a player uses: their party's, or their own when they are not in one. */
    fun ownerKey(player: CharacterState): String = player.partyId?.let { "party:$it" } ?: "solo:${player.id}"

    /** The key for a guild hall: one copy per guild. */
    fun guildKey(guildId: String): String = "guild:$guildId"
}

/**
 * Operator limits for instances (the `instances` section of `server.json`).
 *
 * @property emptyTicks how long an instance with nobody in it is kept, so a player who drops can come back (default 60 s)
 * @property maxInstances how many instances may be open at once; entry is refused beyond it (default 200)
 */
data class InstanceSettings(val emptyTicks: Long = DEFAULT_EMPTY_TICKS, val maxInstances: Int = DEFAULT_MAX_INSTANCES) {
    init {
        require(emptyTicks >= 0) { "emptyTicks must not be negative" }
        require(maxInstances >= 1) { "maxInstances must be at least 1" }
    }

    companion object {
        const val DEFAULT_EMPTY_TICKS: Long = 60L * 20L
        const val DEFAULT_MAX_INSTANCES: Int = 200
    }
}

/**
 * One open instance.
 *
 * @property mapId the copy's id (at least [MapInstances.FIRST_ID])
 * @property templateMapId the content map it was copied from
 * @property ownerKey the party or solo player it belongs to ([MapInstances.ownerKey])
 * @property memberIds every character that has entered it; only they may be put back into it at login
 * @property emptySinceTick the tick it was first seen empty, or null while someone is inside
 * @property entryX where players arriving from elsewhere are put, for a generated floor (null: the warp's own tile)
 * @property seed the seed a generated floor was built from (0 for a plain copy), for reproducing or sharing it
 */
data class MapInstance(
    val mapId: Int,
    val templateMapId: Int,
    val ownerKey: String,
    val createdTick: Long,
    val memberIds: Set<String>,
    val emptySinceTick: Long? = null,
    val entryX: Int? = null,
    val entryY: Int? = null,
    val seed: Long = 0L,
    /** The difficulty tier (index into `scaling.tiers`) the opener chose; fixed for the life of the copy. */
    val tier: Int = 0
)

/** Temporary two-way return portal opened by a Town Portal inside an instanced dungeon (use `/return` from town). */
data class ReturnPortal(
    val ownerKey: String,
    val targetMapId: Int,
    val targetX: Int,
    val targetY: Int,
    val createdTick: Long
)

/** The content map behind [mapId]: the template for an instance, otherwise the map itself. */
fun WorldSimulation.contentMapId(mapId: Int): Int = instances[mapId]?.templateMapId ?: mapId

/** The maps that are content (everything except open instances), in id order of insertion. */
fun WorldSimulation.contentMaps(): Map<Int, MapDefinition> = maps.filterKeys { !MapInstances.isInstanceId(it) }

/**
 * Moves [player] to a tile on [targetMapId], entering (or opening) their instance when the target is an instanced
 * template (the [WarpRouter]s decide that; with the `instancing` system off the warp goes to the map it named). Returns the moved character, which the caller stores and announces, or null when the move cannot happen
 * (unknown map, or every instance slot is in use; the player is told). Clamps the tile to the destination map.
 *
 * Entering an instance from a normal map remembers where the player came from ([CharacterState.instanceReturn]), so
 * a player who logs in after their instance closed is put back there. Warping from one instance into another keeps
 * the original return point; returning to a normal map clears it.
 */
internal fun WorldSimulation.warpedPlayer(
    player: CharacterState,
    targetMapId: Int,
    tileX: Int,
    tileY: Int,
    direction: Direction,
    out: MutableList<WorldSimulationEvent>
): CharacterState? {
    val target = maps[targetMapId] ?: return null
    val dest = routedMap(player, target, tileX, tileY, out) ?: return null
    // Arriving on a generated floor from elsewhere puts the player at its entry: the warp tile cannot know the layout.
    val instance = instances[dest.id]
    val arriving = player.position.mapId != dest.id
    val (x, y) = if (arriving && instance?.entryX != null && instance.entryY != null) instance.entryX to instance.entryY else tileX to tileY
    val position = WorldPosition.fromTile(dest.id, x.coerceIn(0, dest.width - 1), y.coerceIn(0, dest.height - 1), direction, dest.tileSize)
    val fromInstance = MapInstances.isInstanceId(player.position.mapId)
    val intoInstance = MapInstances.isInstanceId(dest.id)
    val instanceReturn = when {
        !intoInstance -> null
        fromInstance -> player.instanceReturn
        else -> player.position
    }
    return player.copy(position = position, instanceReturn = instanceReturn)
}

/**
 * Where a character logging in should appear: inside an instance only if it is still open and they had entered it,
 * otherwise back where they entered from (or their class start when that is unknown).
 */
internal fun WorldSimulation.positionAfterLogin(character: CharacterState): CharacterState {
    val mapId = character.position.mapId
    if (!MapInstances.isInstanceId(mapId)) return character
    val open = instances[mapId]
    val guildOwned = open?.ownerKey?.startsWith("guild:") == true
    val stillWelcome = !guildOwned || open?.ownerKey == character.guildId?.let { MapInstances.guildKey(it) }
    if (open?.memberIds?.contains(character.id) == true && stillWelcome) return character
    return character.copy(position = outsidePosition(character), instanceReturn = null)
}

/** Where [character] goes when an instance can no longer hold them: where they entered from, else their class start. */
private fun WorldSimulation.outsidePosition(character: CharacterState): WorldPosition =
    character.instanceReturn?.takeIf { it.mapId in maps && !MapInstances.isInstanceId(it.mapId) }
        ?: classes[character.classId].let { cls ->
            val startMap = maps[cls?.startMapId ?: 1] ?: maps.values.firstOrNull { !MapInstances.isInstanceId(it.id) }
            WorldPosition.fromTile(startMap?.id ?: 1, cls?.startX ?: 12, cls?.startY ?: 9, Direction.DOWN, startMap?.tileSize ?: WorldPosition.DEFAULT_TILE_SIZE)
        }

/**
 * Puts a player who is standing in a guild hall back outside when they no longer belong to that guild (kicked, left,
 * or the guild was disbanded). Does nothing for anyone else.
 */
internal fun WorldSimulation.ejectFromGuildHallImpl(characterId: String): List<WorldSimulationEvent> {
    val player = players[characterId] ?: return emptyList()
    val open = instances[player.position.mapId] ?: return emptyList()
    if (!open.ownerKey.startsWith("guild:")) return emptyList()
    if (open.ownerKey == player.guildId?.let { MapInstances.guildKey(it) }) return emptyList()
    val oldMap = player.position.mapId
    val updated = player.copy(position = outsidePosition(player), instanceReturn = null)
    players[characterId] = updated
    return listOf(
        WorldSimulationEvent.PlayerMoved(updated, oldMap, updated.position.mapId),
        WorldSimulationEvent.CharacterUpdated(updated),
        warningNotice(updated, "You are no longer a member of that guild, so you were shown out of its hall.")
    )
}

/** Closes instances that have been empty for [InstanceSettings.emptyTicks], dropping their map and everything on it. */
internal fun WorldSimulation.tickInstances(out: MutableList<WorldSimulationEvent>) {
    if (instances.isEmpty()) return
    if (game.definition.ghost?.takeIf { it.enabled && it.wipeEndsRun } != null) endWipedRuns(out)
    val occupied = players.values.mapTo(HashSet()) { it.position.mapId }
    for (instance in instances.values.toList()) {
        when {
            instance.mapId in occupied ->
                if (instance.emptySinceTick != null) instances[instance.mapId] = instance.copy(emptySinceTick = null)
            instance.emptySinceTick == null -> instances[instance.mapId] = instance.copy(emptySinceTick = currentTick)
            currentTick - instance.emptySinceTick >= instanceSettings.emptyTicks -> {
                closeInstance(instance.mapId)
                out.add(WorldSimulationEvent.InstanceClosed(instance.mapId, instance.templateMapId))
            }
        }
    }
}

/**
 * A generated floor where nobody is left standing ends the run for everyone on it: the fallen are sent home with full life
 * (`GhostDefinition.wipeEndsRun`). A floor with a living player is untouched, so a party mate can still revive.
 */
private fun WorldSimulation.endWipedRuns(out: MutableList<WorldSimulationEvent>) {
    for (instance in instances.values.toList()) {
        if (maps[instance.templateMapId]?.generator == null) continue
        val here = players.values.filter { it.position.mapId == instance.mapId }
        if (here.isEmpty() || here.any { it.isAlive(game.lifeVitalId) && !it.isGhost }) continue
        for (fallen in here.sortedBy { it.id }) {
            if (!fallen.isGhost) continue
            out.addAll(releaseGhostImpl(fallen.id))
            players[fallen.id]?.let { out.add(tellPlayer(it, "Your party was wiped out. The run is over.", "WARN")) }
        }
    }
}

internal fun WorldSimulation.closeInstance(mapId: Int) {
    instances.remove(mapId)
    maps.remove(mapId)
    mapNpcs.remove(mapId)
    mapGroundItems.remove(mapId)
    mapProjectiles.remove(mapId)
    mapResources.remove(mapId)
    unlockedDoors.remove(mapId)
    returnPortals.values.removeIf { it.targetMapId == mapId }
}
