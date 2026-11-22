package com.morgana.domain.systems

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapGroundItem
import com.morgana.domain.item.ItemRoller
import com.morgana.domain.item.ItemStack
import com.morgana.domain.map.FloorGeneration
import com.morgana.domain.map.InstanceOwner
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.MapInstance
import com.morgana.domain.sim.MapInstances
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WarpRoute
import com.morgana.domain.sim.WarpRouter
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.closeInstance
import com.morgana.domain.sim.tellPlayer

/**
 * Instanced maps as a system (ADR 0018): a map marked `instanced` is a template, and a warp into it goes to a private copy shared
 * with the player's party (or their guild, for a guild hall, or alone), opened on first entry. A template with a `generator`
 * becomes a freshly generated floor with its monsters and loot, a copy's monsters are scaled for the people in it, and the opener
 * sets its difficulty tier. The copies themselves, their reserved map ids, closing them once empty and putting players back after
 * login are the kernel's (`InstanceSystem.kt`), because the arena uses them too. With this system off, an instanced map is one
 * shared map; a Lua plugin that `replaces` it takes over routing with `register.warp`.
 */
object InstancingSystem : WarpRouter, GameSystem {
    override val key: String = OptionalModule.INSTANCING.configKey

    override fun route(
        sim: WorldSimulation, player: CharacterState, target: MapDefinition, tileX: Int, tileY: Int, out: MutableList<WorldSimulationEvent>
    ): WarpRoute? {
        val dest = sim.instanceFor(player, target, out) ?: return WarpRoute.Refused
        if (dest.id == target.id) return null // not an instanced template: the warp goes where it named
        if (player.position.mapId != dest.id && sim.instances[dest.id] != null) sim.scaleInstanceMonsters(dest.id, player, out)
        return WarpRoute.To(dest)
    }
}

/** The difficulty tier the player who opens a copy of [template] gets: what their `tierVariable` asks for, as far as their level allows. */
internal fun chosenTier(template: MapDefinition, opener: CharacterState): Int {
    val scaling = template.scaling ?: return 0
    return scaling.tierFor(opener.variables[scaling.tierVariable] ?: 0, opener.level)
}

/**
 * Toughens the monsters of instance [instanceMapId] for the players in it plus [entering] (see `MapDefinition.scaling`).
 * A monster that is dead gets its scale for when it comes back; one that is hurt or fighting is left as it is.
 */
internal fun WorldSimulation.scaleInstanceMonsters(instanceMapId: Int, entering: CharacterState, out: MutableList<WorldSimulationEvent>) {
    val template = instances[instanceMapId]?.let { maps[it.templateMapId] } ?: return
    val scaling = template.scaling ?: return
    val party = players.values.filter { it.position.mapId == instanceMapId && it.id != entering.id } + entering
    val averageLevel = party.sumOf { it.level } / party.size
    val tier = instances[instanceMapId]?.tier ?: 0
    val list = mapNpcs[instanceMapId] ?: return
    if (scaling.tiers.isNotEmpty() && players[entering.id]?.position?.mapId != instanceMapId) {
        out.add(tellPlayer(entering, "Difficulty: ${scaling.tiers.getOrNull(tier)?.name ?: "Normal"}.", "INFO"))
    }
    val life = game.lifeVitalId
    for (i in list.indices) {
        val npc = list[i]
        val def = npcs[npc.npcId] ?: continue
        if (def.behavior != NpcBehavior.PASSIVE && def.behavior != NpcBehavior.ATTACK_ON_SIGHT) continue
        val scale = scaling.scaleFor(party.size, averageLevel, def.level, tier)
        if (npc.scale == scale) continue
        if (!npc.isAlive) {
            list[i] = npc.copy(scale = scale)
            continue
        }
        val hurt = npc.vitals[life]?.let { it.cur < it.max } == true
        if (hurt || npc.targetEntityId != null) continue
        val scaled = npc.copy(scale = scale, vitals = spawnVitalsForNpc(def, rolledVariant(npc), scale))
        list[i] = scaled
        out.add(WorldSimulationEvent.NpcUpdated(scaled))
    }
}

/** The map the player actually goes to for [target]: [target] itself unless it is an instanced template. */
private fun WorldSimulation.instanceFor(player: CharacterState, target: MapDefinition, out: MutableList<WorldSimulationEvent>): MapDefinition? {
    if (!target.instanced || MapInstances.isInstanceId(target.id)) return target
    // Already inside a copy of this template (a warp within the instance): stay in it.
    instances[player.position.mapId]?.takeIf { it.templateMapId == target.id }?.let { return maps[it.mapId] }
    val key = if (target.instanceOwner == InstanceOwner.GUILD) {
        val guildId = player.guildId ?: run {
            out.add(
                WorldSimulationEvent.ChatEmitted(
                    channel = "SYSTEM", mapId = player.position.mapId, senderId = "system", senderName = "System",
                    targetCharacterId = player.id, message = "${target.name} is for guild members only.", showOverheadBubble = false, notice = "WARNING"
                )
            )
            return null
        }
        MapInstances.guildKey(guildId)
    } else MapInstances.ownerKey(player)
    instances.values.firstOrNull { it.templateMapId == target.id && it.ownerKey == key }?.let { open ->
        if (target.generator != null && open.emptySinceTick != null) {
            // A generated floor nobody is on any more was left behind: walking in again starts a fresh one.
            closeInstance(open.mapId)
            out.add(WorldSimulationEvent.InstanceClosed(open.mapId, open.templateMapId))
        } else {
            instances[open.mapId] = open.copy(memberIds = open.memberIds + player.id)
            return maps[open.mapId]
        }
    }
    if (instances.size >= instanceSettings.maxInstances) {
        out.add(
            WorldSimulationEvent.ChatEmitted(
                channel = "SYSTEM", mapId = player.position.mapId, senderId = "system", senderName = "System",
                targetCharacterId = player.id, message = "${target.name} is full right now. Try again in a minute.", showOverheadBubble = false, notice = "WARNING"
            )
        )
        return null
    }
    val id = nextInstanceId++
    if (target.generator != null) return openGeneratedFloor(target, id, key, player)
    val copy = target.copy(id = id)
    registerMap(copy)
    instances[id] = MapInstance(id, target.id, key, currentTick, setOf(player.id), tier = chosenTier(target, player))
    return copy
}

/** Mixed into a floor's seed for the random numbers its loot rolls with, so they differ from the layout's. */
private const val LOOT_SEED_SALT: Long = 0x10075EEDL

/**
 * Builds a fresh floor for [template] (which has a generator) as instance [id]: the generated map, its monsters, and
 * its loot lying on the floor. The seed comes from the template, the instance id and the tick, so it is deterministic
 * (a replay builds the same floor) without drawing from the world's random numbers.
 */
private fun WorldSimulation.openGeneratedFloor(template: MapDefinition, id: Int, key: String, player: CharacterState): MapDefinition {
    val customSeed = customRunSeeds[player.id]?.takeIf { it != 0L && game.definition.runs?.let { r -> r.enabled && r.allowCustomSeed } == true }
    val seed = customSeed?.let { FloorGeneration.mix(it + template.id.toLong() * 31L) }
        ?: FloorGeneration.mix(template.id.toLong() * 1_000_003L + id.toLong() * 7_919L + currentTick)
    val floor = FloorGeneration.generate(template, id, seed)
    registerMap(floor.map)
    if (floor.loot.isNotEmpty()) {
        val ground = mapGroundItems.getOrPut(id) { mutableListOf() }
        // Gear rolls its rarity and affixes from its own sequence seeded by the floor, never the world's random numbers.
        val lootRng = SeededRandomSource(FloorGeneration.mix((template.generator?.fixedSeed?.takeIf { it != 0L } ?: seed) xor LOOT_SEED_SALT))
        val itemLevel = template.generator?.itemLevel ?: 1
        for (pile in floor.loot) {
            val def = items[pile.itemId] ?: continue
            val loot = game.definition.loot
            if (loot != null && ItemRoller.canRoll(def)) {
                repeat(pile.amount) {
                    val rolled = ItemRoller.roll(def, itemLevel, loot, lootRng)
                    val maxSockets = game.definition.gearUpgrade?.takeIf { it.enabled }?.maxSockets ?: 0
                    val stack = ItemStack(pile.itemId, 1, rolls = rolled, sockets = ItemRoller.rollDropSockets(rolled, loot, maxSockets, lootRng))
                    ground += MapGroundItem(instanceId = nextId("loot"), mapId = id, tileX = pile.x, tileY = pile.y, stack = stack)
                }
            } else {
                ground += MapGroundItem(instanceId = nextId("loot"), mapId = id, tileX = pile.x, tileY = pile.y, stack = ItemStack(pile.itemId, pile.amount))
            }
        }
    }
    val usedSeed = template.generator?.fixedSeed?.takeIf { it != 0L } ?: seed
    instances[id] = MapInstance(id, template.id, key, currentTick, setOf(player.id), entryX = floor.entryX, entryY = floor.entryY, seed = usedSeed, tier = chosenTier(template, player))
    return floor.map
}
