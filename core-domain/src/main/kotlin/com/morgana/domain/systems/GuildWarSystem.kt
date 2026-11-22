package com.morgana.domain.systems

import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.tellPlayer
import com.morgana.domain.sim.ChatCommandHandler
import com.morgana.domain.sim.MonsterBehavior
import com.morgana.domain.sim.CombatArbiter
import com.morgana.domain.sim.EventCommandHandler
import com.morgana.domain.sim.ItemUseHandler
import com.morgana.domain.sim.TickParticipant
import com.morgana.domain.sim.TickStage

import com.morgana.domain.access.PermissionFlag
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.GuildDocument
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.game.GuildWarDefinition
import com.morgana.domain.stats.VitalState

/** Running state of the current Guild War (see `GuildWarDefinition`): whether one is on and the pole damage each guild has dealt. */
class GuildWarState {
    var active: Boolean = false
    var startedTick: Long = 0L

    /** Guild id to pole damage dealt this war. */
    val damage: MutableMap<String, Long> = linkedMapOf()
}

/** The id of the guild that holds the guild area, or null when nobody does. */
fun WorldSimulation.guildAreaHolderId(): String? = guilds.values.firstOrNull { it.holdsGuildArea }?.id

/**
 * The Guild War as a system: starts and ends wars on schedule each tick, takes over blows on its poles and gates, and owns
 * `/guildwar start|end` (maintainers only).
 */
object GuildWarSystem : TickParticipant, CombatArbiter, ChatCommandHandler, MonsterBehavior, GameSystem {
    override val key: String = OptionalModule.GUILD_WAR.configKey
    override val stage = TickStage.AFTER_NPCS

    // Guild War poles and gates stand still and never fight back.
    override fun holdsStill(sim: WorldSimulation, npc: MapNpcInstance, def: NpcDefinition): Boolean = def.guildWarRole.isNotEmpty()

    override fun onTick(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>) = sim.tickGuildWar(out)

    override fun takesHitOnNpc(
        sim: WorldSimulation, attacker: CharacterState, npc: MapNpcInstance, npcDef: NpcDefinition, damage: Int,
        out: MutableList<WorldSimulationEvent>
    ): Boolean = sim.interceptGuildWarHit(attacker, npc, npcDef, damage, out)

    override fun handle(sim: WorldSimulation, player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>): Boolean {
        if (parts.firstOrNull()?.lowercase() != "guildwar") return false
        if (!player.accessLevel.hasPermission(PermissionFlag.SERVER_MAINTENANCE)) return true
        when (parts.getOrNull(1)?.lowercase()) {
            "start" -> if (!sim.startGuildWar(out)) out.add(sim.tellPlayer(player, "No Guild War can start (none configured, or one is already on).", "WARN"))
            "end", "stop" -> if (!sim.endGuildWar(out)) out.add(sim.tellPlayer(player, "No Guild War is running.", "WARN"))
            else -> out.add(sim.tellPlayer(player, "Usage: /guildwar start|end", "WARN"))
        }
        return true
    }
}

/** Starts a scheduled war on its tick and ends the running one when its time is up. */
internal fun WorldSimulation.tickGuildWar(out: MutableList<WorldSimulationEvent>) {
    val rules = game.definition.guildWar?.takeIf { it.enabled } ?: return
    if (guildWar.active) {
        if (currentTick - guildWar.startedTick >= rules.durationTicks) endGuildWar(out)
    } else if (rules.intervalTicks > 0 && currentTick % rules.intervalTicks.toLong() == 0L) {
        startGuildWar(out)
    }
}

/** Opens a war; false when the game has none or one is already on. */
internal fun WorldSimulation.startGuildWar(out: MutableList<WorldSimulationEvent>): Boolean {
    val rules = game.definition.guildWar?.takeIf { it.enabled } ?: return false
    if (guildWar.active) return false
    guildWar.active = true
    guildWar.startedTick = currentTick
    guildWar.damage.clear()
    resetGuildWarGates(out)
    out.add(guildWarAnnouncement("Guild War has begun at ${maps[rules.mapId]?.name ?: "the guild area"}! Break the gates and strike the pole."))
    return true
}

/** Closes the war: the guild with the most pole damage takes the area (a tie, or too little damage, changes nothing). */
internal fun WorldSimulation.endGuildWar(out: MutableList<WorldSimulationEvent>): Boolean {
    val rules = game.definition.guildWar ?: return false
    if (!guildWar.active) return false
    guildWar.active = false
    resetGuildWarGates(out)

    val ranked = guildWar.damage.entries.filter { it.key in guilds }.sortedByDescending { it.value }
    val top = ranked.firstOrNull()?.takeIf { it.value >= rules.minDamage }
    val tied = top != null && ranked.size > 1 && ranked[1].value == top.value
    val holderId = guildAreaHolderId()
    if (top == null || tied) {
        val text = if (holderId != null) "Guild War is over. ${guilds.getValue(holderId).name} keeps the guild area." else "Guild War is over. Nobody took the guild area."
        out.add(guildWarAnnouncement(text))
        guildWar.damage.clear()
        return true
    }
    val winnerId = top.key
    val changed = mutableListOf<GuildDocument>()
    if (holderId != null && holderId != winnerId) {
        guilds[holderId]?.let { prev -> prev.copy(holdsGuildArea = false).also { guilds[prev.id] = it; changed.add(it) } }
    }
    guilds[winnerId]?.let { win -> win.copy(holdsGuildArea = true, warWins = win.warWins + 1).also { guilds[win.id] = it; changed.add(it) } }
    out.add(WorldSimulationEvent.GuildWarResult(changed.toList()))
    val winner = guilds.getValue(winnerId)
    val verdict = if (winnerId == holderId) "${winner.name} defended the guild area!" else "${winner.name} has taken the guild area!"
    out.add(guildWarAnnouncement("Guild War is over. $verdict"))
    for (member in players.values.filter { it.guildId == winnerId }) giveGuildWarReward(member, rules.rewardItems, out)
    guildWar.damage.clear()
    return true
}

private fun WorldSimulation.giveGuildWarReward(member: CharacterState, reward: Map<Int, Int>, out: MutableList<WorldSimulationEvent>) {
    if (reward.isEmpty()) return
    var inv = member.inventory
    for ((itemId, amount) in reward) {
        val def = items[itemId] ?: continue
        inv = inv.addItem(def, amount).updatedInventory
    }
    val updated = member.copy(inventory = inv)
    players[member.id] = updated
    out.add(WorldSimulationEvent.CharacterUpdated(updated))
    out.add(tellPlayer(updated, "Your guild's war prize has been added to your bag.", "INFO"))
}

private fun guildWarAnnouncement(text: String): WorldSimulationEvent =
    WorldSimulationEvent.ChatEmitted(
        channel = "BROADCAST", mapId = 0, senderId = "server", senderName = "Guild War",
        message = text, showOverheadBubble = false, colorHex = "#FACC15"
    )

/** Every gate is made whole again, so each war starts and ends with the walls up. */
private fun WorldSimulation.resetGuildWarGates(out: MutableList<WorldSimulationEvent>) {
    val lifeId = game.lifeVitalId
    for ((_, list) in mapNpcs) {
        for (i in list.indices) {
            val npc = list[i]
            val def = npcs[npc.npcId] ?: continue
            if (def.guildWarRole != "GATE") continue
            val max = def.vitals[lifeId] ?: continue
            val fixed = npc.copy(vitals = npc.vitals + (lifeId to VitalState(max, max)), respawnAtTick = 0L, targetEntityId = null)
            list[i] = fixed
            out.add(WorldSimulationEvent.NpcUpdated(fixed))
        }
    }
}

/**
 * Guild War rules for a blow on a pole or gate. Returns true when the hit is fully handled here (refused, or counted against a
 * pole) and false when it should land as ordinary damage (a gate during a war, or an NPC that is not part of the war).
 */
internal fun WorldSimulation.interceptGuildWarHit(
    attacker: CharacterState,
    npc: MapNpcInstance,
    def: NpcDefinition,
    damage: Int,
    out: MutableList<WorldSimulationEvent>
): Boolean {
    if (def.guildWarRole !in GuildWarDefinition.ROLES) return false
    val guildId = attacker.guildId
    val refusal = when {
        !guildWar.active -> "The ${def.name} cannot be harmed outside a Guild War."
        guildId == null -> "Only guild members may fight in the Guild War."
        guildId == guildAreaHolderId() -> "Your guild holds this area; defend it instead."
        else -> null
    }
    if (refusal != null) {
        out.add(tellPlayer(attacker, refusal, "WARN"))
        return true
    }
    if (def.guildWarRole == "GATE") return false
    guildWar.damage[guildId!!] = (guildWar.damage[guildId] ?: 0L) + damage
    out.add(WorldSimulationEvent.FloatingTextEmitted(npc.mapId, npc.tileX, npc.tileY, "-$damage", "DAMAGE", "#FACC15"))
    return true
}
