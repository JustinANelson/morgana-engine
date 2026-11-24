package com.morgana.domain.script

/**
 * What a Lua plugin hands the engine to take part in the kernel's system seams (ADR 0018). The Lua runtime fills these from
 * `register.plugin`, `register.requirement` and `register.monster`; `ScriptedSystem` (in `core-domain/.../systems`) turns them
 * into a `RequirementGate` and a `MonsterBehavior` that are installed in every world. The runtime itself knows nothing about seams.
 *
 * Every hook carries the id of the script module that registered it ([moduleId]), so the engine can keep a plugin's hooks
 * switched off while a system the plugin [ScriptPluginManifest.requires] is switched off.
 */

/**
 * What a plugin says about itself with `register.plugin{ id = ..., requires = {...} }`: a name for logs and editors and the
 * systems (`OptionalModule` config keys such as `parties` or `instancing`) that must be on for its hooks to run, and the engine systems it
 * replaces (`replaces`: the engine treats them as switched off while the plugin runs). A module
 * that never declares a manifest simply has no requirements.
 */
data class ScriptPluginManifest(
    val moduleId: String,
    val id: String,
    val name: String = "",
    val version: String = "",
    val description: String = "",
    val requires: Set<String> = emptySet(),
    /** Engine systems (`OptionalModule` keys) this plugin replaces while it runs: the engine treats them as switched off. */
    val replaces: Set<String> = emptySet()
)

/** The spell or item a requirement hook is asked about. [kind] is `spell` or `item`. */
data class ScriptRequirementSubject(val kind: String, val id: Int, val name: String, val levelReq: Int)

/**
 * A plugin's requirement gate: [check] returns the reason a character may not use the [ScriptRequirementSubject], or null
 * for no objection. It is a pure query; it runs under the formula instruction budget and cannot cause effects.
 */
class ScriptRequirementHook(
    val moduleId: String,
    val check: (subject: ScriptRequirementSubject, player: ScriptPlayerSnapshot, world: ScriptWorldView) -> String?
)

/**
 * A plugin's ruling on who may hurt whom: [check] returns true or false for this pair of players, or null for no opinion (the
 * normal PK and map rules decide). Like every query hook it is pure and runs under the formula budget.
 */
class ScriptFightHook(
    val moduleId: String,
    val check: (attacker: ScriptPlayerSnapshot, defender: ScriptPlayerSnapshot, world: ScriptWorldView) -> Boolean?
)

/** Multipliers a plugin folds into a monster's strength, in percent (100 changes nothing). */
data class ScriptVariantMultipliers(val attackPercent: Int = 100, val moveSpeedPercent: Int = 100)

/**
 * A plugin's monster behaviour. Every function is optional. [npcIds] limits the hook to those monster definitions (empty means
 * every monster); without it a hook costs a Lua call per monster per tick, so name the monsters you change.
 *
 * The queries ([holdsStill], [variant], [retarget], [mayBeElite], [killLockoutTicks]) are pure and run under the formula
 * budget. [step] may cause effects (damage, healing, messages, projectiles, state...) through the `world` functions; they are
 * applied inline in the tick, checked against the game's script capabilities, and replay identically because Lua is deterministic.
 */
class ScriptMonsterHook(
    val moduleId: String,
    val npcIds: Set<Int> = emptySet(),
    val holdsStill: ((npc: ScriptNpcSnapshot, world: ScriptWorldView) -> Boolean)? = null,
    val variant: ((npc: ScriptNpcSnapshot, world: ScriptWorldView) -> ScriptVariantMultipliers?)? = null,
    /** The id of the player the monster should fight now, or null for no opinion. Anyone not alive on the monster's map is ignored. */
    val retarget: ((npc: ScriptNpcSnapshot, world: ScriptWorldView) -> String?)? = null,
    val step: ((npc: ScriptNpcSnapshot, world: ScriptWorldView) -> List<ScriptEffect>)? = null,
    val mayBeElite: ((npcId: Int, world: ScriptWorldView) -> Boolean)? = null,
    val killLockoutTicks: ((npcId: Int, world: ScriptWorldView) -> Long?)? = null
) {
    /** Whether this hook applies to the monster definition [npcId]. */
    fun covers(npcId: Int): Boolean = npcIds.isEmpty() || npcId in npcIds
}

/** When in a tick a [ScriptTickHook] runs: after the monsters have acted (`after_npcs`) or after timed events (`after_events`). */
object ScriptTickStage {
    const val AFTER_NPCS = "after_npcs"
    const val AFTER_EVENTS = "after_events"
    val ALL: Set<String> = setOf(AFTER_NPCS, AFTER_EVENTS)
}

/**
 * Something a plugin does every [everyTicks] ticks (1 means every tick) at [stage]. [run] may cause effects, which are applied
 * inline in the tick under the game's script capabilities and replay identically because Lua is deterministic.
 */
class ScriptTickHook(
    val moduleId: String,
    val stage: String,
    val everyTicks: Int,
    val run: (world: ScriptWorldView) -> List<ScriptEffect>
)

/** The item a use hook is asked about. [type] is the item type in lower case (`consumable`, `weapon`, ...). */
data class ScriptItemSubject(val id: Int, val name: String, val type: String, val levelReq: Int)

/** What a use hook decided: [handled] stops the engine's own use of the item (and applies [effects]); otherwise the item is used as usual. */
data class ScriptItemUseResult(val handled: Boolean, val effects: List<ScriptEffect> = emptyList())

/** A plugin giving some items a use of their own. [itemIds] limits it to those items (empty means every item). */
class ScriptItemUseHook(
    val moduleId: String,
    val itemIds: Set<Int> = emptySet(),
    val use: (item: ScriptItemSubject, player: ScriptPlayerSnapshot, slotIndex: Int, world: ScriptWorldView) -> ScriptItemUseResult
) {
    fun covers(itemId: Int): Boolean = itemIds.isEmpty() || itemId in itemIds
}

/** The activities an action gate can stop, as scripts name them: `move`, `attack`, `cast`, `use_item`, `trade`. */
object ScriptActivity {
    val ALL: Set<String> = setOf("move", "attack", "cast", "use_item", "trade")
}

/**
 * A plugin stopping a character doing something for now: [blocks] returns true to stop them. [activities] limits it to those
 * activities (empty means all); movement is asked on every step, so name only what you need. Pure query, formula budget.
 */
class ScriptActionGateHook(
    val moduleId: String,
    val activities: Set<String> = emptySet(),
    val blocks: (player: ScriptPlayerSnapshot, activity: String, world: ScriptWorldView) -> Boolean
) {
    fun covers(activity: String): Boolean = activities.isEmpty() || activity in activities
}

/**
 * A plugin winding something down when a character is pulled away from what they were doing (they die, or an arena match starts).
 * [run] may cause effects, but any that would change that character are dropped: the engine is part-way through changing them.
 */
class ScriptInterruptHook(
    val moduleId: String,
    val run: (player: ScriptPlayerSnapshot, world: ScriptWorldView) -> List<ScriptEffect>
)

/**
 * A plugin reacting to the blows of a fight. [afterAttack] runs when a character lands a weapon hit; [afterStruck] when a character
 * has taken a hit. Both may cause effects. Effects that would change the character who was struck are dropped from [afterStruck]
 * (the engine is part-way through changing them); react on other characters, monsters, messages and state instead.
 */
class ScriptCombatObserverHook(
    val moduleId: String,
    val afterAttack: ((attacker: ScriptPlayerSnapshot, world: ScriptWorldView) -> List<ScriptEffect>)? = null,
    val afterStruck: ((victim: ScriptPlayerSnapshot, world: ScriptWorldView) -> List<ScriptEffect>)? = null
)

/**
 * A scheduled warning or start as a plugin sees it. [phase] is `warning` or `fire`; [secondsBefore] is how long before the start a
 * warning is (0 for the start); [occurrenceMillis] is the start time (epoch milliseconds); [data] is what a one-off was booked with.
 */
data class ScriptScheduleTrigger(
    val scheduleId: String,
    val name: String,
    val phase: String,
    val secondsBefore: Int,
    val occurrenceMillis: Long,
    val recurring: Boolean,
    val data: Map<String, String>
)

/**
 * A plugin reacting to the scheduler (`register.schedule`). [scheduleIds] limits it to those schedules (empty means all of them);
 * [onWarning] and [onFire] may each be absent. They may cause effects, applied inline and replayed identically.
 */
class ScriptScheduleHook(
    val moduleId: String,
    val scheduleIds: Set<String> = emptySet(),
    val onWarning: ((trigger: ScriptScheduleTrigger, world: ScriptWorldView) -> List<ScriptEffect>)? = null,
    val onFire: ((trigger: ScriptScheduleTrigger, world: ScriptWorldView) -> List<ScriptEffect>)? = null
) {
    fun covers(scheduleId: String): Boolean = scheduleIds.isEmpty() || scheduleId in scheduleIds
}

/** A world object as a script sees it (see `WorldObject`). */
data class ScriptObjectSnapshot(
    val id: String,
    val kind: String,
    val mapId: Int,
    val x: Int,
    val y: Int,
    val ownerId: String?,
    val guildId: String?,
    val hp: Int,
    val data: Map<String, String>
)

/** A placement a [ScriptObjectGateHook] is asked about: [kind] at ([x], [y]) on [mapId], by [ownerId] (null when the game places it). */
data class ScriptObjectRequest(val kind: String, val mapId: Int, val x: Int, val y: Int, val ownerId: String?)

/**
 * A plugin ruling on placements (`register.object_gate`). [kinds] limits it to those kinds (empty means every kind). [check] returns the
 * reason to refuse (shown to the player), or null to allow. A pure query: it may read the world but causes no effects.
 */
class ScriptObjectGateHook(
    val moduleId: String,
    val kinds: Set<String> = emptySet(),
    val check: (request: ScriptObjectRequest, world: ScriptWorldView) -> String?
) {
    fun covers(kind: String): Boolean = kinds.isEmpty() || kind in kinds
}

/** A plugin sharing in a kill: [run] is told the killer and the experience the kill earned them, and may cause effects. */
class ScriptKillHook(
    val moduleId: String,
    val run: (killer: ScriptPlayerSnapshot, xpReward: Long, world: ScriptWorldView) -> List<ScriptEffect>
)

/** A warp as a plugin sees it: the map the player is going to ([mapId], [mapName], whether it is an [instanced] template) and the tile. */
data class ScriptWarpRequest(val mapId: Int, val mapName: String, val x: Int, val y: Int, val instanced: Boolean)

/** What a plugin decides about a warp: refuse it (telling the player [message] if there is one) or send the player to another map. */
sealed interface ScriptWarpRuling {
    data class Refuse(val message: String? = null) : ScriptWarpRuling
    data class Redirect(val mapId: Int) : ScriptWarpRuling
}

/**
 * A plugin ruling on warps: [check] returns a [ScriptWarpRuling], or null to leave the warp alone. A redirect goes to the other map
 * by the normal routing (so a redirect to an instanced template still gets its private copy). Pure query, formula budget.
 */
class ScriptWarpHook(
    val moduleId: String,
    val check: (player: ScriptPlayerSnapshot, warp: ScriptWarpRequest, world: ScriptWorldView) -> ScriptWarpRuling?
)

