package com.morgana.domain.systems

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.WorldObject
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.sim.ChatCommandHandler
import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.ItemUseHandler
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.WorldObjectChange
import com.morgana.domain.sim.isOn
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.notifyWorldObject
import com.morgana.domain.sim.systemOn
import com.morgana.domain.sim.tellPlayer
import com.morgana.domain.spatial.TileCoord

/** What [WorldSimulation.placeWorldObject] did. */
sealed interface WorldObjectResult {
    data class Placed(val obj: WorldObject) : WorldObjectResult

    /** It was not placed; [reason] is fit to show a player. */
    data class Refused(val reason: String) : WorldObjectResult
}

/**
 * World state: things that are placed on a map and stay there (see [WorldObject]). This is the foundation for base building,
 * claims and farm plots; on its own it lets a game place walls, chests and signs from items or scripts.
 *
 * - **Kinds** come from `game.json` `worldObjects`. An item with `placesObject` set puts one on the tile the character faces.
 * - **Rules** are open: a [com.morgana.domain.sim.WorldObjectGate] (or a Lua `register.object_gate`) can refuse a placement, and a
 *   [com.morgana.domain.sim.WorldObjectObserver] hears every placement, change and removal.
 * - **Deterministic**: the objects are simulation state, hashed for replay, and every change is made by a recorded input.
 * - **Persistent**: the server saves them per map (`<data dir>/world-objects/map_<id>.json`) and loads them at boot.
 * - Blocking objects stop characters and monsters walking onto their tile.
 */
object WorldStateSystem : ItemUseHandler, ChatCommandHandler, GameSystem {
    override val key: String = OptionalModule.WORLD_STATE.configKey

    // ---- placing from an item ---------------------------------------------------------------------------------------------------

    override fun use(sim: WorldSimulation, player: CharacterState, slotIndex: Int, item: ItemDefinition, out: MutableList<WorldSimulationEvent>): Boolean {
        if (item.placesObject.isBlank()) return false
        val facing = player.position.tileCoord.step(player.position.direction)
        when (val result = place(sim, item.placesObject, player.position.mapId, facing.x, facing.y, player.id, player.guildId, emptyMap(), out)) {
            is WorldObjectResult.Refused -> out.add(sim.tellPlayer(player, result.reason, "WARNING"))
            is WorldObjectResult.Placed -> {
                val (inventory, _) = player.inventory.removeFromSlot(slotIndex, 1) ?: return true
                val updated = player.copy(inventory = inventory)
                sim.players[player.id] = updated
                out.add(WorldSimulationEvent.CharacterUpdated(updated))
                out.add(WorldSimulationEvent.ItemUsed(player.id, item.id, slotIndex))
            }
        }
        return true
    }

    // ---- the operations ---------------------------------------------------------------------------------------------------------

    internal fun place(
        sim: WorldSimulation, kindId: String, mapId: Int, tileX: Int, tileY: Int, ownerCharacterId: String?, ownerGuildId: String?,
        data: Map<String, String>, out: MutableList<WorldSimulationEvent>
    ): WorldObjectResult {
        if (!sim.systemOn(key)) return WorldObjectResult.Refused("Building is switched off.")
        val definition = sim.game.definition.worldObjects ?: return WorldObjectResult.Refused("Nothing can be built here.")
        val kind = definition.kind(kindId) ?: return WorldObjectResult.Refused("Nothing is known as '$kindId'.")
        val map = sim.maps[mapId] ?: return WorldObjectResult.Refused("There is no such place.")
        val tile = TileCoord(tileX, tileY)
        if (!map.isInBounds(tile)) return WorldObjectResult.Refused("That is outside the map.")
        val attribute = map.getCell(tile)?.attribute
        if (attribute != null && attribute != TileAttribute.None && attribute !is TileAttribute.LightSource) {
            return WorldObjectResult.Refused("You cannot build on that.")
        }
        if (sim.worldObjectAt(mapId, tileX, tileY) != null) return WorldObjectResult.Refused("Something is already built there.")
        if (kind.blocksMovement) {
            val occupied = sim.players.values.any { it.position.mapId == mapId && it.position.tileX == tileX && it.position.tileY == tileY } ||
                sim.mapNpcs[mapId].orEmpty().any { it.isAlive && it.tileX == tileX && it.tileY == tileY }
            if (occupied) return WorldObjectResult.Refused("Someone is standing there.")
        }
        if ((sim.worldObjects[mapId]?.size ?: 0) >= definition.maxPerMap) return WorldObjectResult.Refused("This place cannot hold anything more.")
        for (gate in sim.worldObjectGates) {
            if (!sim.isOn(gate)) continue // a system that is switched off, or replaced by a plugin, has no say
            val refusal = gate.refusal(sim, kind, mapId, tileX, tileY, ownerCharacterId)
            if (refusal != null) return WorldObjectResult.Refused(refusal)
        }
        val obj = WorldObject(
            id = sim.nextId("obj"), kind = kindId, mapId = mapId, tileX = tileX, tileY = tileY, ownerCharacterId = ownerCharacterId,
            ownerGuildId = ownerGuildId, hp = kind.maxHp, createdAtTick = sim.effectiveWorldTick(), data = data.toSortedMap()
        )
        sim.storeWorldObject(obj)
        out.add(WorldSimulationEvent.WorldObjectsChanged(mapId, listOf(obj), emptyList()))
        sim.notifyWorldObject(obj, WorldObjectChange.PLACED, "placed", out)
        return WorldObjectResult.Placed(obj)
    }

    internal fun remove(sim: WorldSimulation, id: String, reason: String, out: MutableList<WorldSimulationEvent>): Boolean {
        val obj = sim.worldObject(id) ?: return false
        sim.eraseWorldObject(obj)
        out.add(WorldSimulationEvent.WorldObjectsChanged(obj.mapId, emptyList(), listOf(id)))
        sim.notifyWorldObject(obj, WorldObjectChange.REMOVED, reason, out)
        return true
    }

    internal fun damage(sim: WorldSimulation, id: String, amount: Int, out: MutableList<WorldSimulationEvent>): Boolean {
        val obj = sim.worldObject(id) ?: return false
        if (amount <= 0) return false
        val left = (obj.hp - amount).coerceAtLeast(0)
        if (left == 0) return remove(sim, id, "destroyed", out)
        val hurt = obj.copy(hp = left)
        sim.storeWorldObject(hurt)
        out.add(WorldSimulationEvent.WorldObjectsChanged(hurt.mapId, listOf(hurt), emptyList()))
        sim.notifyWorldObject(hurt, WorldObjectChange.CHANGED, "damaged", out)
        return true
    }

    internal fun setData(sim: WorldSimulation, id: String, data: Map<String, String>, out: MutableList<WorldSimulationEvent>): Boolean {
        val obj = sim.worldObject(id) ?: return false
        val changed = obj.copy(data = data.toSortedMap())
        if (changed == obj) return true
        sim.storeWorldObject(changed)
        out.add(WorldSimulationEvent.WorldObjectsChanged(changed.mapId, listOf(changed), emptyList()))
        sim.notifyWorldObject(changed, WorldObjectChange.CHANGED, "data", out)
        return true
    }

    // ---- /object ----------------------------------------------------------------------------------------------------------------

    override fun handle(sim: WorldSimulation, player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>): Boolean {
        if (parts.firstOrNull()?.lowercase() != "object") return false
        val facing = player.position.tileCoord.step(player.position.direction)
        val here = sim.worldObjectAt(player.position.mapId, facing.x, facing.y)
        when (parts.getOrNull(1)?.lowercase()) {
            null, "", "look" -> {
                val text = here?.let {
                    val kind = sim.game.definition.worldObjects?.kind(it.kind)
                    "${kind?.displayName ?: it.kind}: ${it.hp}/${kind?.maxHp ?: it.hp} HP" + (it.ownerCharacterId?.let { o -> sim.players[o]?.name?.let { n -> ", built by $n" } } ?: "")
                } ?: "There is nothing built in front of you."
                out.add(sim.tellPlayer(player, text, "INFO"))
            }
            "remove" -> {
                val target = here
                val mayRemove = target != null && (target.ownerCharacterId == player.id || player.accessLevel.hasPermission(com.morgana.domain.access.PermissionFlag.SERVER_MAINTENANCE))
                if (target == null) out.add(sim.tellPlayer(player, "There is nothing built in front of you.", "WARNING"))
                else if (!mayRemove) out.add(sim.tellPlayer(player, "That is not yours to take down.", "WARNING"))
                else {
                    remove(sim, target.id, "removed", out)
                    out.add(sim.tellPlayer(player, "Taken down.", "INFO"))
                }
            }
            else -> out.add(sim.tellPlayer(player, "Usage: /object [look] | remove (the object you are facing)", "WARNING"))
        }
        return true
    }
}
