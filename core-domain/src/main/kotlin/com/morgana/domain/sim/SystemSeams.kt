package com.morgana.domain.sim

import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.script.ScriptPluginManifest

/*
 * Seams between the kernel and the systems built on it (ADR 0018, see wiki/systems/system_seams.md). A system is an object that
 * implements the seams it needs and is registered with `WorldSimulation.install`; the kernel only ever calls the interfaces.
 * Seams run inside the deterministic simulation: they are pure queries, or change the world only through the recorded paths the
 * kernel itself uses (`players`, the `out` event list). Two more seams, RequirementGate and EventCommandHandler, have their own files.
 */

/** Where in a tick a [TickParticipant] runs. The order inside one stage is the order the systems were installed. */
enum class TickStage {
    /** After monsters have thought, fought and respawned. */
    AFTER_NPCS,

    /** After timed event waits and timer common events, before instances are closed. */
    AFTER_EVENTS
}

/** A system that does something every tick, such as pairing arena fights or moving pets. */
interface TickParticipant {
    val stage: TickStage
    fun onTick(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>)
}

/**
 * A system with a say in combat: who may hurt whom, what a death means and whether a hit on a monster is taken over. Every
 * method defaults to "no opinion", so a system implements only what it needs. The first arbiter with an opinion wins.
 */
interface CombatArbiter {
    /** Whether [attacker] may hurt [defender] (an arena decides by team), or null for no opinion. Asked before the PK mode rules. */
    fun mayFight(sim: WorldSimulation, attacker: CharacterState, defender: CharacterState): Boolean? = null

    /** Takes over the death of [player] and returns how they end up (an arena sends them back out), or null to let the normal death happen. */
    fun deathOutcome(sim: WorldSimulation, player: CharacterState, out: MutableList<WorldSimulationEvent>): CharacterState? = null

    /** Takes over a hit of [damage] on [npc] (a guild war counts it for a boss) and returns true, or false to let the hit land normally. */
    fun takesHitOnNpc(
        sim: WorldSimulation, attacker: CharacterState, npc: MapNpcInstance, npcDef: NpcDefinition, damage: Int,
        out: MutableList<WorldSimulationEvent>
    ): Boolean = false
}

/** A system that owns slash commands (`/arena`, `/pet`, `/guildwar`). [parts] is the command split on spaces, the command name first. */
interface ChatCommandHandler {
    /** Carries out the command and returns true, or returns false when [parts] is not one of this system's commands. */
    fun handle(sim: WorldSimulation, player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>): Boolean
}

/** A system that shares in what a player earns by a kill (a pet earns experience beside its owner). */
interface KillObserver {
    /** [killer] has just been awarded [xpReward] experience for killing a monster. */
    fun onKillReward(sim: WorldSimulation, killer: CharacterState, xpReward: Long, out: MutableList<WorldSimulationEvent>)
}

/** What a character is trying to do when an [ActionGate] is asked. */
enum class PlayerActivity { MOVE, ATTACK, CAST, USE_ITEM, TRADE }

/** A system that can stop a character doing something for now (an open stall keeps its owner in place). */
interface ActionGate {
    /** True when this system stops [characterId] doing [activity] right now. The first gate that blocks wins. */
    fun blocks(sim: WorldSimulation, characterId: String, activity: PlayerActivity): Boolean
}

/** A system that has to wind down when a character is pulled away from what they were doing (they die, or a match starts). */
interface ActivityInterrupter {
    fun interrupt(sim: WorldSimulation, characterId: String, out: MutableList<WorldSimulationEvent>)
}

/** A system that reacts to the blows of a fight (gear wears out). Both methods default to doing nothing. */
interface CombatObserver {
    /** [attackerId] has just landed a hit with their weapon. */
    fun afterAttack(sim: WorldSimulation, attackerId: String, out: MutableList<WorldSimulationEvent>) {}

    /** [victim] has just taken a hit; returns the character as it should now be (observers are applied one after another). */
    fun afterStruck(sim: WorldSimulation, victim: CharacterState, out: MutableList<WorldSimulationEvent>): CharacterState = victim
}

/**
 * A system that follows real-world time. The simulation has no clock of its own (it counts ticks), so the server tells it the wall
 * clock now and then through the recorded `SimInput.WallClock` input; replays feed the same times back and agree. Games and systems
 * that only need "something at 10:00 on Saturday" use the `scheduler` system and its [ScheduleHandler] instead.
 */
interface WallClockListener {
    /** The wall clock is now [nowMillis] (epoch milliseconds, UTC). Called about once a second, never with a time before the last call's. */
    fun onWallClock(sim: WorldSimulation, nowMillis: Long, out: MutableList<WorldSimulationEvent>)
}

/** Which end of a scheduled occurrence a [ScheduleTrigger] is. */
enum class SchedulePhase { WARNING, FIRE }

/**
 * One thing the scheduler raised: a warning [secondsBefore] seconds ahead of an occurrence, or the occurrence itself ([SchedulePhase.FIRE]).
 * [occurrenceMillis] is when the occurrence is due (epoch milliseconds). [recurring] is false for a one-off. [data] is what a
 * one-off was created with (see `WorldSimulation.scheduleOnce`), empty for schedules from `game.json`.
 */
data class ScheduleTrigger(
    val scheduleId: String,
    val name: String,
    val phase: SchedulePhase,
    val secondsBefore: Int,
    val occurrenceMillis: Long,
    val recurring: Boolean,
    val data: Map<String, String> = emptyMap()
)

/** A system that reacts to the scheduler: a weekly reset begins, a warning goes out. Lua plugins use `register.schedule`. */
interface ScheduleHandler {
    fun onSchedule(sim: WorldSimulation, trigger: ScheduleTrigger, out: MutableList<WorldSimulationEvent>)
}

/** What happened to a world object, for a [WorldObjectObserver]. */
enum class WorldObjectChange { PLACED, CHANGED, REMOVED }

/**
 * A system with a say in where things may be built (a claim system refuses a tile near a resource node or inside another's land).
 * Asked after the world state's own checks (a free tile, a known kind, room on the map); the first refusal wins. Lua plugins use
 * `register.object_gate`.
 */
interface WorldObjectGate {
    /** Why [kind] may not be placed at the tile (fit to show a player), or null to allow it. [ownerCharacterId] is null for a placement the game makes. */
    fun refusal(sim: WorldSimulation, kind: com.morgana.domain.game.WorldObjectKind, mapId: Int, tileX: Int, tileY: Int, ownerCharacterId: String?): String?
}

/** A system that can force characters into the PvP stance where they stand (a siege window forces everyone inside the claim). */
interface StanceForcer {
    /** True when [player] is in PvP stance right now whatever they chose, and cannot leave it. */
    fun forcesPvp(sim: WorldSimulation, player: CharacterState): Boolean
}

/** A system with a say in whether a placed object may be hurt (a claim in its protected state shrugs off blows). */
interface WorldObjectDamageGate {
    /** Why [attacker] may not hurt [obj] now (a short reason; nothing is shown when it is blank), or null to allow it. The first refusal wins. */
    fun damageRefusal(sim: WorldSimulation, attacker: CharacterState, obj: com.morgana.domain.entity.WorldObject, kind: com.morgana.domain.game.WorldObjectKind): String?
}

/** A system that hears every world object placed, changed or removed (a claim tracks its pieces; a quest counts builds). */
interface WorldObjectObserver {
    fun onWorldObject(sim: WorldSimulation, obj: com.morgana.domain.entity.WorldObject, change: WorldObjectChange, reason: String, out: MutableList<WorldSimulationEvent>)
}

/**
 * A system with a say in what happens when a character uses (faces and interacts with) a placed world object: a storage chest
 * opens, a crafting station starts work, a damaged structure refuses. Lua plugins use `register.object_use`.
 */
interface WorldObjectInteraction {
    /** Why [player] may not use [obj] (fit to show them), or null to allow it. Asked first; the first refusal wins and ends the use. */
    fun interactionRefusal(
        sim: WorldSimulation, player: CharacterState, obj: com.morgana.domain.entity.WorldObject, kind: com.morgana.domain.game.WorldObjectKind
    ): String? = null

    /** Carries out the use and returns true, or returns false to leave it to the next system. */
    fun interact(
        sim: WorldSimulation, player: CharacterState, obj: com.morgana.domain.entity.WorldObject, kind: com.morgana.domain.game.WorldObjectKind,
        out: MutableList<WorldSimulationEvent>
    ): Boolean = false
}

/** A system that gives some items a use of their own (a pet item adopts its pet instead of being eaten). */
interface ItemUseHandler {
    /** Carries out the use of [item] from bag slot [slotIndex] and returns true, or returns false to let the item be used normally. */
    fun use(
        sim: WorldSimulation, player: CharacterState, slotIndex: Int, item: ItemDefinition,
        out: MutableList<WorldSimulationEvent>
    ): Boolean
}

/** A "system" chat line shown to [player] as a warning (the notice style the client draws in red), for kernel and systems alike. */
internal fun warningNotice(player: CharacterState, text: String): WorldSimulationEvent = WorldSimulationEvent.ChatEmitted(
    channel = "SYSTEM", mapId = player.position.mapId, senderId = "system", senderName = "System",
    targetCharacterId = player.id, message = text, showOverheadBubble = false, notice = "WARNING"
)

/**
 * A system that has an id in the unified system list: [key] is the `OptionalModule.configKey` it answers to, which is the name
 * used in `server.json` `modules`, the F1 Systems panel, templates and a Lua plugin's `requires` and `replaces`. The kernel skips
 * a system whose switch is off, or that a running plugin replaces, in every seam. An installed object that is not a
 * [GameSystem] (a test's, say) is always on.
 */
interface GameSystem {
    val key: String
}

/**
 * Whether the system [key] is in play right now: its `server.json` switch is on and no running Lua plugin replaces it.
 * A plugin replaces a system with `register.plugin{ replaces = {...} }` and only while it runs: while it requires a system that
 * is off, or after its module was switched off for repeated errors, the replaced system is back.
 */
internal fun WorldSimulation.systemOn(key: String): Boolean {
    if (!modules.isEnabled(key)) return false
    return pluginReplacing(key) == null
}

/** The running plugin that replaces the system [key], or null. */
internal fun WorldSimulation.pluginReplacing(key: String): ScriptPluginManifest? {
    val registry = scripts.registry
    if (!registry.hasReplacements()) return null
    return registry.allPlugins().firstOrNull { key in it.replaces && pluginRunning(it) }
}

/** Whether the plugin runs now: every system it requires is on and its script module has not been disabled. */
internal fun WorldSimulation.pluginRunning(plugin: ScriptPluginManifest): Boolean =
    plugin.requires.all { modules.isEnabled(it) } && !scripts.isModuleDisabled(plugin.moduleId)

/** Whether [system] takes part in the seams right now (see [GameSystem]). */
internal fun WorldSimulation.isOn(system: Any): Boolean = system !is GameSystem || systemOn(system.key)

/** One row of the unified system list: what a system is, whether it is on and why not. */
data class SystemStatus(
    val key: String,
    val name: String,
    val description: String,
    val tags: Set<String>,
    val requires: Set<String>,
    /** The `server.json` switch. */
    val switchedOn: Boolean,
    /** The id of the running Lua plugin that replaces this system, or null. */
    val replacedBy: String?,
    /** True when an object for this system is installed in the seams; false for switches the kernel checks itself (parties, trading...). */
    val installed: Boolean
) {
    /** Whether the system is in play: switched on and not replaced. */
    val active: Boolean get() = switchedOn && replacedBy == null
}

/** Every engine system in one list (the built-in `OptionalModule`s), with its switch, any replacing plugin and whether it is installed. */
fun WorldSimulation.systemStatus(): List<SystemStatus> = OptionalModule.entries.map { m ->
    SystemStatus(
        key = m.configKey, name = m.displayName, description = m.description, tags = m.tags, requires = m.requires,
        switchedOn = modules.isEnabled(m),
        replacedBy = pluginReplacing(m.configKey)?.id,
        installed = systems.any { it is GameSystem && it.key == m.configKey }
    )
}

internal fun WorldSimulation.notifyWorldObject(
    obj: com.morgana.domain.entity.WorldObject, change: WorldObjectChange, reason: String, out: MutableList<WorldSimulationEvent>
) {
    for (observer in worldObjectObservers) if (isOn(observer)) observer.onWorldObject(this, obj, change, reason, out)
}

internal fun WorldSimulation.notifyWallClock(nowMillis: Long, out: MutableList<WorldSimulationEvent>) {
    for (listener in wallClockListeners) if (isOn(listener)) listener.onWallClock(this, nowMillis, out)
}

internal fun WorldSimulation.notifySchedule(trigger: ScheduleTrigger, out: MutableList<WorldSimulationEvent>) {
    out.add(WorldSimulationEvent.ScheduleTriggered(trigger))
    for (handler in scheduleHandlers) if (isOn(handler)) handler.onSchedule(this, trigger, out)
}

internal fun WorldSimulation.tickSystems(stage: TickStage, out: MutableList<WorldSimulationEvent>) {
    for (participant in tickParticipants) if (participant.stage == stage && isOn(participant)) participant.onTick(this, out)
}

internal fun WorldSimulation.arbitratedMayFight(attacker: CharacterState, defender: CharacterState): Boolean? =
    combatArbiters.firstNotNullOfOrNull { if (isOn(it)) it.mayFight(this, attacker, defender) else null }

internal fun WorldSimulation.arbitratedDeath(player: CharacterState, out: MutableList<WorldSimulationEvent>): CharacterState? =
    combatArbiters.firstNotNullOfOrNull { if (isOn(it)) it.deathOutcome(this, player, out) else null }

internal fun WorldSimulation.hitOnNpcTakenOver(
    attacker: CharacterState, npc: MapNpcInstance, npcDef: NpcDefinition, damage: Int, out: MutableList<WorldSimulationEvent>
): Boolean = combatArbiters.any { isOn(it) && it.takesHitOnNpc(this, attacker, npc, npcDef, damage, out) }

internal fun WorldSimulation.notifyKillReward(killer: CharacterState, xpReward: Long, out: MutableList<WorldSimulationEvent>) {
    for (observer in killObservers) if (isOn(observer)) observer.onKillReward(this, killer, xpReward, out)
}

internal fun WorldSimulation.actionBlocked(characterId: String, activity: PlayerActivity): Boolean =
    actionGates.any { isOn(it) && it.blocks(this, characterId, activity) }

internal fun WorldSimulation.interruptActivities(characterId: String, out: MutableList<WorldSimulationEvent>) {
    for (interrupter in activityInterrupters) if (isOn(interrupter)) interrupter.interrupt(this, characterId, out)
}

internal fun WorldSimulation.afterAttack(attackerId: String, out: MutableList<WorldSimulationEvent>) {
    for (observer in combatObservers) if (isOn(observer)) observer.afterAttack(this, attackerId, out)
}

internal fun WorldSimulation.afterStruck(victim: CharacterState, out: MutableList<WorldSimulationEvent>): CharacterState =
    combatObservers.fold(victim) { current, observer -> if (isOn(observer)) observer.afterStruck(this, current, out) else current }

internal fun WorldSimulation.runChatCommand(player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>): Boolean =
    chatCommandHandlers.any { isOn(it) && it.handle(this, player, parts, out) }

internal fun WorldSimulation.runItemUse(
    player: CharacterState, slotIndex: Int, item: ItemDefinition, out: MutableList<WorldSimulationEvent>
): Boolean = itemUseHandlers.any { isOn(it) && it.use(this, player, slotIndex, item, out) }
