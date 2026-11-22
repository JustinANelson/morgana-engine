package com.morgana.domain.script

/**
 * Result of executing a script-registered slash command.
 */
data class ScriptCommandResult(
    val handled: Boolean,
    val feedbackMessage: String? = null,
    val effects: List<ScriptEffect> = emptyList()
)

/**
 * Definition of a slash command provided by a script.
 */
data class ScriptCommandDefinition(
    val name: String,
    val description: String = "",
    val minAccessLevel: Int = 0,
    val handler: (player: ScriptPlayerSnapshot, args: List<String>, world: ScriptWorldView) -> ScriptCommandResult
)

/**
 * Script-provided hooks for replaceable combat systems.
 */
data class ScriptCombatHooks(
    val onTickProjectiles: ((world: ScriptWorldView) -> List<ScriptEffect>)? = null,
    val onTickPlayers: ((world: ScriptWorldView) -> List<ScriptEffect>)? = null,
    val onPlayerAttack: ((characterId: String, world: ScriptWorldView) -> List<ScriptEffect>)? = null,
    val onPlayerCastSpell: ((characterId: String, spellId: Int, targetEntityId: String?, targetTileX: Int, targetTileY: Int, world: ScriptWorldView) -> List<ScriptEffect>)? = null,
    val handleAction: ((characterId: String, actionId: String, params: Map<String, String>, world: ScriptWorldView) -> List<ScriptEffect>)? = null
)

/**
 * Container of hooks and registries populated by script modules.
 */
class ScriptRegistry {
    private val commands = mutableMapOf<String, ScriptCommandDefinition>()
    private val formulas = mutableMapOf<String, (params: Map<String, Long>, world: ScriptWorldView) -> Long>()
    private val eventCommands = mutableMapOf<String, (player: ScriptPlayerSnapshot, eventId: Int, params: Map<String, String>, world: ScriptWorldView) -> List<ScriptEffect>>()
    private val eventConditions = mutableMapOf<String, (player: ScriptPlayerSnapshot, eventId: Int, params: Map<String, String>, world: ScriptWorldView) -> Boolean>()
    private val actions = mutableMapOf<String, (characterId: String, params: Map<String, String>, world: ScriptWorldView) -> List<ScriptEffect>>()
    private var combatHooks: ScriptCombatHooks? = null
    private val plugins = linkedMapOf<String, ScriptPluginManifest>()
    private var anyReplaces = false
    private val requirementHooks = mutableListOf<ScriptRequirementHook>()
    private val monsterHooks = mutableListOf<ScriptMonsterHook>()
    private val fightHooks = mutableListOf<ScriptFightHook>()
    private val tickHooks = mutableListOf<ScriptTickHook>()
    private val itemUseHooks = mutableListOf<ScriptItemUseHook>()
    private val actionGateHooks = mutableListOf<ScriptActionGateHook>()
    private val interruptHooks = mutableListOf<ScriptInterruptHook>()
    private val combatObserverHooks = mutableListOf<ScriptCombatObserverHook>()
    private val killHooks = mutableListOf<ScriptKillHook>()
    private val warpHooks = mutableListOf<ScriptWarpHook>()

    fun registerCommand(definition: ScriptCommandDefinition) {
        commands[definition.name.lowercase()] = definition
    }

    fun getCommand(name: String): ScriptCommandDefinition? = commands[name.lowercase()]

    fun allCommands(): List<ScriptCommandDefinition> = commands.values.toList()

    fun registerFormula(name: String, formula: (params: Map<String, Long>, world: ScriptWorldView) -> Long) {
        formulas[name] = formula
    }

    fun evaluateFormula(name: String, params: Map<String, Long>, world: ScriptWorldView): Long? {
        return formulas[name]?.invoke(params, world)
    }

    fun hasFormula(name: String): Boolean = formulas.containsKey(name)

    fun registerEventCommand(commandId: String, handler: (player: ScriptPlayerSnapshot, eventId: Int, params: Map<String, String>, world: ScriptWorldView) -> List<ScriptEffect>) {
        eventCommands[commandId] = handler
    }

    fun executeEventCommand(commandId: String, player: ScriptPlayerSnapshot, eventId: Int, params: Map<String, String>, world: ScriptWorldView): List<ScriptEffect>? {
        return eventCommands[commandId]?.invoke(player, eventId, params, world)
    }

    fun registerEventCondition(conditionId: String, predicate: (player: ScriptPlayerSnapshot, eventId: Int, params: Map<String, String>, world: ScriptWorldView) -> Boolean) {
        eventConditions[conditionId] = predicate
    }

    fun checkEventCondition(conditionId: String, player: ScriptPlayerSnapshot, eventId: Int, params: Map<String, String>, world: ScriptWorldView): Boolean? {
        return eventConditions[conditionId]?.invoke(player, eventId, params, world)
    }

    fun registerAction(actionId: String, handler: (characterId: String, params: Map<String, String>, world: ScriptWorldView) -> List<ScriptEffect>) {
        actions[actionId.lowercase()] = handler
    }

    fun executeAction(characterId: String, actionId: String, params: Map<String, String>, world: ScriptWorldView): List<ScriptEffect>? {
        return actions[actionId.lowercase()]?.invoke(characterId, params, world)
    }

    fun hasAction(actionId: String): Boolean = actions.containsKey(actionId.lowercase())

    fun registerCombatHooks(hooks: ScriptCombatHooks) {
        this.combatHooks = hooks
    }

    fun getCombatHooks(): ScriptCombatHooks? = combatHooks

    /** Records what a plugin says about itself (one per script module; a second call replaces the first). */
    fun registerPlugin(manifest: ScriptPluginManifest) {
        plugins[manifest.moduleId] = manifest
        anyReplaces = plugins.values.any { it.replaces.isNotEmpty() }
    }

    /** True when some plugin replaces an engine system (a cheap check the engine makes before every seam call). */
    fun hasReplacements(): Boolean = anyReplaces

    /** The manifest the script module [moduleId] declared, or null when it declared none. */
    fun pluginOf(moduleId: String): ScriptPluginManifest? = plugins[moduleId]

    fun allPlugins(): List<ScriptPluginManifest> = plugins.values.toList()

    fun registerRequirementHook(hook: ScriptRequirementHook) {
        requirementHooks += hook
    }

    /** The registered requirement hooks, in registration order (a live read-only view: the engine asks every tick). */
    fun requirementHooks(): List<ScriptRequirementHook> = requirementHooks

    fun registerMonsterHook(hook: ScriptMonsterHook) {
        monsterHooks += hook
    }

    /** The registered monster hooks, in registration order (a live read-only view: the engine asks every tick). */
    fun monsterHooks(): List<ScriptMonsterHook> = monsterHooks

    fun registerFightHook(hook: ScriptFightHook) {
        fightHooks += hook
    }

    /** The registered fight hooks, in registration order (a live read-only view: the engine asks on every attack). */
    fun fightHooks(): List<ScriptFightHook> = fightHooks

    fun registerTickHook(hook: ScriptTickHook) {
        tickHooks += hook
    }

    /** The registered tick hooks, in registration order (a live read-only view: the engine asks every tick). */
    fun tickHooks(): List<ScriptTickHook> = tickHooks

    fun registerItemUseHook(hook: ScriptItemUseHook) {
        itemUseHooks += hook
    }

    fun itemUseHooks(): List<ScriptItemUseHook> = itemUseHooks

    fun registerActionGateHook(hook: ScriptActionGateHook) {
        actionGateHooks += hook
    }

    fun actionGateHooks(): List<ScriptActionGateHook> = actionGateHooks

    fun registerInterruptHook(hook: ScriptInterruptHook) {
        interruptHooks += hook
    }

    fun interruptHooks(): List<ScriptInterruptHook> = interruptHooks

    fun registerCombatObserverHook(hook: ScriptCombatObserverHook) {
        combatObserverHooks += hook
    }

    fun combatObserverHooks(): List<ScriptCombatObserverHook> = combatObserverHooks

    fun registerKillHook(hook: ScriptKillHook) {
        killHooks += hook
    }

    fun killHooks(): List<ScriptKillHook> = killHooks

    fun registerWarpHook(hook: ScriptWarpHook) {
        warpHooks += hook
    }

    /** The registered warp hooks, in registration order (a live read-only view: the engine asks on every warp). */
    fun warpHooks(): List<ScriptWarpHook> = warpHooks

    fun clear() {
        commands.clear()
        formulas.clear()
        eventCommands.clear()
        eventConditions.clear()
        actions.clear()
        combatHooks = null
        plugins.clear()
        anyReplaces = false
        requirementHooks.clear()
        monsterHooks.clear()
        fightHooks.clear()
        tickHooks.clear()
        itemUseHooks.clear()
        actionGateHooks.clear()
        interruptHooks.clear()
        combatObserverHooks.clear()
        killHooks.clear()
        warpHooks.clear()
    }
}

