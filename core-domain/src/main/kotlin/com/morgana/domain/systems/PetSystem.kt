package com.morgana.domain.systems

import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.tellPlayer
import com.morgana.domain.sim.ChatCommandHandler
import com.morgana.domain.sim.KillObserver
import com.morgana.domain.sim.applyDamageToNpc
import com.morgana.domain.sim.canNpcStepTo
import com.morgana.domain.sim.isHostileNpc
import com.morgana.domain.sim.CombatArbiter
import com.morgana.domain.sim.EventCommandHandler
import com.morgana.domain.sim.ItemUseHandler
import com.morgana.domain.sim.TickParticipant
import com.morgana.domain.sim.TickStage

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.entity.PetState
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.spatial.Direction
import com.morgana.domain.stats.VitalState
import kotlin.math.abs

private fun petInstanceId(ownerId: String) = "pet-$ownerId"

/** The live pet NPC of [ownerId], wherever it stands. */
internal fun WorldSimulation.petOf(ownerId: String): MapNpcInstance? =
    mapNpcs.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.instanceId == petInstanceId(ownerId) } }

/** The pet's damage at its level, in whole points (`attackDamage` grown by `damagePerLevelPercent` for each level above the first). */
internal fun WorldSimulation.petDamage(def: com.morgana.domain.content.NpcDefinition, level: Int): Int {
    val traits = def.pet ?: return 0
    return (def.attackDamage.toLong() * (100 + (level - 1).coerceAtLeast(0) * traits.damagePerLevelPercent) / 100).toInt().coerceAtLeast(1)
}

/** Using a pet item: adopts the pet it names (the item is used up), or says the character already has it. */
internal fun WorldSimulation.adoptPetFromItem(player: CharacterState, slotIndex: Int, itemDef: ItemDefinition, out: MutableList<WorldSimulationEvent>) {
    val def = npcs[itemDef.adoptPetNpcId]
    if (def?.pet == null) {
        out.add(tellPlayer(player, "Nothing happens.", "WARNING"))
        return
    }
    if (itemDef.adoptPetNpcId in player.pets) {
        out.add(tellPlayer(player, "You already have ${def.name}.", "WARNING"))
        return
    }
    val (inv, _) = player.inventory.removeFromSlot(slotIndex, 1) ?: return
    val updated = player.copy(inventory = inv, pets = player.pets + (def.id to PetState()), activePetNpcId = def.id)
    players[player.id] = updated
    out.add(tellPlayer(updated, "${def.name} joins you! /pet shows your pets.", "GOOD"))
    out.add(WorldSimulationEvent.CharacterUpdated(updated))
    out.add(WorldSimulationEvent.ItemUsed(player.id, itemDef.id, slotIndex))
}

/** `/pet`, `/pet summon <name or id>`, `/pet dismiss`, `/pet name <new name>`. */
internal fun WorldSimulation.petCommand(player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>) {
    fun label(id: Int): String {
        val st = player.pets[id]
        val kind = npcs[id]?.name ?: "pet $id"
        return if (st == null) kind else (if (st.name.isNotBlank()) "${st.name} the $kind" else kind) + " (level ${st.level})"
    }
    when (parts.getOrNull(1)?.lowercase()) {
        null, "list" -> {
            if (player.pets.isEmpty()) out.add(tellPlayer(player, "You have no pets. Pet whistles are sold by pet handlers.", "INFO"))
            else {
                out.add(tellPlayer(player, "Your pets: " + player.pets.keys.joinToString("; ") { label(it) + if (it == player.activePetNpcId) " [with you]" else "" } + ". /pet summon <name>, /pet dismiss", "INFO"))
            }
        }
        "summon" -> {
            val want = parts.drop(2).joinToString(" ").trim()
            val id = player.pets.keys.firstOrNull { it.toString() == want } ?: player.pets.keys.firstOrNull { k ->
                want.isNotEmpty() && (npcs[k]?.name.orEmpty().contains(want, ignoreCase = true) || player.pets[k]?.name.orEmpty().contains(want, ignoreCase = true))
            } ?: if (want.isEmpty() && player.pets.size == 1) player.pets.keys.first() else null
            if (id == null) out.add(tellPlayer(player, "Name one of your pets: /pet summon <name>.", "WARNING"))
            else {
                val updated = player.copy(activePetNpcId = id)
                players[player.id] = updated
                out.add(tellPlayer(updated, "${label(id)} comes to your side.", "INFO"))
                out.add(WorldSimulationEvent.CharacterUpdated(updated))
            }
        }
        "dismiss" -> {
            if (player.activePetNpcId == 0) out.add(tellPlayer(player, "You have no pet with you.", "WARNING"))
            else {
                val updated = player.copy(activePetNpcId = 0)
                players[player.id] = updated
                out.add(tellPlayer(updated, "${label(player.activePetNpcId)} goes home.", "INFO"))
                out.add(WorldSimulationEvent.CharacterUpdated(updated))
            }
        }
        "name" -> {
            val name = parts.drop(2).joinToString(" ").trim().take(16)
            val active = player.activePetNpcId
            if (active == 0 || name.isEmpty()) out.add(tellPlayer(player, "Summon a pet, then /pet name <new name>.", "WARNING"))
            else {
                val updated = player.copy(pets = player.pets + (active to (player.pets[active] ?: PetState()).copy(name = name)))
                players[player.id] = updated
                out.add(tellPlayer(updated, "Your pet is now called $name.", "GOOD"))
                out.add(WorldSimulationEvent.CharacterUpdated(updated))
            }
        }
        else -> out.add(tellPlayer(player, "Usage: /pet [list] | summon <name> | dismiss | name <new name>", "WARNING"))
    }
}

/** Gives the owner's active pet its share of [ownerXp] and levels it up when it earns enough. */
internal fun WorldSimulation.grantPetXp(ownerId: String, ownerXp: Long, out: MutableList<WorldSimulationEvent>) {
    val owner = players[ownerId] ?: return
    val id = owner.activePetNpcId.takeIf { it != 0 } ?: return
    val traits = npcs[id]?.pet ?: return
    var st = owner.pets[id] ?: return
    if (st.level >= traits.maxLevel) return
    var xp = st.xp + (ownerXp * traits.xpSharePercent / 100).toInt().coerceAtLeast(1)
    var level = st.level
    var grew = false
    while (level < traits.maxLevel && xp >= level * traits.xpPerLevel) {
        xp -= level * traits.xpPerLevel
        level++
        grew = true
    }
    if (level >= traits.maxLevel) xp = 0
    st = st.copy(level = level, xp = xp)
    val updated = owner.copy(pets = owner.pets + (id to st))
    players[ownerId] = updated
    if (grew) {
        out.add(tellPlayer(updated, "${npcs[id]?.name} reached level $level!", "GOOD"))
        out.add(WorldSimulationEvent.CharacterUpdated(updated))
    }
}

/** Pets as a system: followers that fight for their owner, moved each tick, adopted from pet items and managed with `/pet`. */
object PetSystem : TickParticipant, ChatCommandHandler, ItemUseHandler, KillObserver, GameSystem {
    override val key: String = OptionalModule.PETS.configKey
    override val stage = TickStage.AFTER_EVENTS

    override fun onTick(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>) = sim.tickPets(out)

    override fun handle(sim: WorldSimulation, player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>): Boolean {
        if (parts.firstOrNull()?.lowercase() != "pet") return false
        sim.petCommand(player, parts, out)
        return true
    }

    override fun onKillReward(sim: WorldSimulation, killer: CharacterState, xpReward: Long, out: MutableList<WorldSimulationEvent>) =
        sim.grantPetXp(killer.id, xpReward, out)

    override fun use(sim: WorldSimulation, player: CharacterState, slotIndex: Int, item: ItemDefinition, out: MutableList<WorldSimulationEvent>): Boolean {
        if (item.type != ItemType.CONSUMABLE || item.adoptPetNpcId <= 0) return false
        sim.adoptPetFromItem(player, slotIndex, item, out)
        return true
    }
}

/** Each tick: summons, moves, fights for and dismisses pets (see `PetTraits`). */
internal fun WorldSimulation.tickPets(out: MutableList<WorldSimulationEvent>) {
    // Pets whose owner left, dismissed them or swapped to another pet go away.
    for ((mapId, list) in mapNpcs) {
        val gone = list.filter { n -> n.ownerId != null && players[n.ownerId]?.activePetNpcId != n.npcId }
        if (gone.isEmpty()) continue
        list.removeAll(gone.toSet())
        for (pet in gone) out.add(WorldSimulationEvent.NpcUpdated(pet.copy(vitals = deadLife(pet), respawnAtTick = 0L)))
    }
    for (owner in players.values.toList()) {
        val id = owner.activePetNpcId.takeIf { it != 0 } ?: continue
        val def = npcs[id]?.takeIf { it.pet != null } ?: continue
        val map = maps[owner.position.mapId] ?: continue
        var pet = petOf(owner.id)
        if (pet != null && pet.mapId != owner.position.mapId) {
            mapNpcs[pet.mapId]?.removeAll { it.instanceId == pet!!.instanceId }
            out.add(WorldSimulationEvent.NpcUpdated(pet.copy(vitals = deadLife(pet), respawnAtTick = 0L)))
            pet = null
        }
        if (pet == null) {
            pet = placePet(owner, def, map, out)
            continue
        }
        stepPet(owner, pet, def, map, out)
    }
}

private fun WorldSimulation.deadLife(pet: MapNpcInstance): Map<String, VitalState> =
    pet.vitals + (game.lifeVitalId to VitalState(0, pet.vitals[game.lifeVitalId]?.max ?: 1))

private fun WorldSimulation.placePet(owner: CharacterState, def: com.morgana.domain.content.NpcDefinition, map: MapDefinition, out: MutableList<WorldSimulationEvent>): MapNpcInstance {
    val ox = owner.position.tileX
    val oy = owner.position.tileY
    val spot = listOf(-1 to 0, 1 to 0, 0 to 1, 0 to -1, -1 to 1, 1 to 1).map { (dx, dy) -> ox + dx to oy + dy }
        .firstOrNull { (x, y) -> map.isInBounds(x, y) && map.getCell(x, y)?.attribute !is TileAttribute.Blocked } ?: (ox to oy)
    val state = owner.pets[def.id]
    val pet = MapNpcInstance(
        instanceId = petInstanceId(owner.id), spawnSlotIndex = -1, npcId = def.id, mapId = map.id,
        tileX = spot.first, tileY = spot.second, homeX = spot.first, homeY = spot.second,
        vitals = spawnVitalsForNpc(def, null), ownerId = owner.id,
        displayName = state?.name.orEmpty()
    )
    mapNpcs.getOrPut(map.id) { mutableListOf() }.add(pet)
    out.add(WorldSimulationEvent.NpcUpdated(pet))
    return pet
}

private fun WorldSimulation.stepPet(owner: CharacterState, start: MapNpcInstance, def: com.morgana.domain.content.NpcDefinition, map: MapDefinition, out: MutableList<WorldSimulationEvent>) {
    val traits = def.pet ?: return
    val list = mapNpcs[start.mapId] ?: return
    fun idx() = list.indexOfFirst { it.instanceId == start.instanceId }
    var pet = start
    val name = owner.pets[def.id]?.name.orEmpty()
    if (pet.displayName != name) {
        pet = pet.copy(displayName = name)
        list[idx()] = pet
        out.add(WorldSimulationEvent.NpcUpdated(pet))
    }
    fun dist(x: Int, y: Int) = abs(pet.tileX - x) + abs(pet.tileY - y)

    // 1. Help against a monster that is hunting the owner.
    val foe = if (traits.fights) list.filter { isHostileNpc(it) && it.targetEntityId == owner.id && dist(it.tileX, it.tileY) <= 8 }
        .minWithOrNull(compareBy({ dist(it.tileX, it.tileY) }, { it.instanceId })) else null
    val target = foe?.let { it.tileX to it.tileY } ?: (owner.position.tileX to owner.position.tileY)
    val wanted = if (foe != null) 1 else traits.followDistance.coerceAtLeast(1)
    val far = dist(owner.position.tileX, owner.position.tileY) > 14
    if (far && foe == null) {
        list.removeAt(idx())
        out.add(WorldSimulationEvent.NpcUpdated(pet.copy(vitals = deadLife(pet), respawnAtTick = 0L)))
        placePet(owner, def, map, out)
        return
    }
    if (foe != null && dist(foe.tileX, foe.tileY) == 1) {
        if (currentTick - pet.lastAttackTick >= def.attackSpeedTicks) {
            val dir = Direction.fromDelta(foe.tileX - pet.tileX, foe.tileY - pet.tileY) ?: pet.direction
            pet = pet.copy(direction = dir, lastAttackTick = currentTick)
            list[idx()] = pet
            out.add(WorldSimulationEvent.NpcUpdated(pet))
            val level = owner.pets[def.id]?.level ?: 1
            applyDamageToNpc(owner, foe, petDamage(def, level), isCrit = false, out = out)
        }
        return
    }
    if (dist(target.first, target.second) > wanted && currentTick - pet.lastMoveTick >= def.moveSpeedTicks.coerceAtLeast(4)) {
        // Try the way straight at the target first, then the other axis, then around: anything that does not take it further off.
        val dx = (target.first - pet.tileX).coerceIn(-1, 1)
        val dy = (target.second - pet.tileY).coerceIn(-1, 1)
        val horizontalFirst = abs(target.first - pet.tileX) >= abs(target.second - pet.tileY)
        val order = listOfNotNull(
            if (horizontalFirst) Direction.fromDelta(dx, 0) else Direction.fromDelta(0, dy),
            if (horizontalFirst) Direction.fromDelta(0, dy) else Direction.fromDelta(dx, 0),
            if (horizontalFirst) Direction.UP else Direction.LEFT, if (horizontalFirst) Direction.DOWN else Direction.RIGHT
        ).distinct()
        val here = dist(target.first, target.second)
        // A second pass lets it sidestep an obstacle (its owner, another monster) by a step that does not close in.
        passes@ for (closer in listOf(true, false)) {
            for (dir in order) {
                val next = pet.tileCoord.step(dir)
                if (closer && abs(next.x - target.first) + abs(next.y - target.second) > here) continue
                if (next.x == owner.position.tileX && next.y == owner.position.tileY) continue
                if (list.any { it.instanceId != pet.instanceId && it.isAlive && it.tileX == next.x && it.tileY == next.y }) continue
                if (canNpcStepTo(map, pet.tileCoord, next, dir)) {
                    pet = pet.copy(tileX = next.x, tileY = next.y, direction = dir, lastMoveTick = currentTick)
                    list[idx()] = pet
                    out.add(WorldSimulationEvent.NpcUpdated(pet))
                    break@passes
                }
            }
        }
    }
}
