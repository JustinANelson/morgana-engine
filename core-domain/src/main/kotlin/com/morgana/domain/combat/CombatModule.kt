package com.morgana.domain.combat

import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.playerAttackImpl
import com.morgana.domain.sim.playerCastSpellImpl
import com.morgana.domain.sim.tickPlayers
import com.morgana.domain.sim.tickProjectiles

/**
 * Result of handling a generic action by a [CombatModule].
 */
data class CombatActionResult(
    val handled: Boolean,
    val events: List<WorldSimulationEvent> = emptyList()
)

/**
 * Pluggable combat system port for Morgana simulations.
 *
 * All simulation state updates during combat pass through this contract,
 * allowing games to replace combat entirely (e.g. peaceful worlds, turn-based combat,
 * or alternative action mechanics) without modifying the engine core.
 */
interface CombatModule {
    val id: String

    /** Advance projectiles or ranged missiles in flight. */
    fun tickProjectiles(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>)

    /** Tick player status effects, natural vital regeneration, and environmental traps. */
    fun tickPlayers(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>)

    /** Execute player attack (melee or ranged depending on loadout). */
    fun onPlayerAttack(sim: WorldSimulation, characterId: String): List<WorldSimulationEvent>

    /** Execute spell casting by a player. */
    fun onPlayerCastSpell(
        sim: WorldSimulation,
        characterId: String,
        spellId: Int,
        targetEntityId: String?,
        targetTileX: Int,
        targetTileY: Int
    ): List<WorldSimulationEvent>

    /** Handle generic action dispatched to the combat module. */
    fun handleAction(
        sim: WorldSimulation,
        characterId: String,
        actionId: String,
        params: Map<String, String>
    ): CombatActionResult = CombatActionResult(handled = false)

    companion object {
        /** Default module id: the engine's real-time combat. `morgana_classic` and `classic` are older names for it. */
        const val DEFAULT_ID = "realtime"

        val VALID_IDS: Set<String> = setOf(DEFAULT_ID, "morgana_classic", "classic", "noop", "none", "lua_combat", "lua", "action", "action_combat")

        fun isCombatEnabled(moduleId: String): Boolean =
            moduleId.lowercase() !in setOf("noop", "none")

        /** The module for [id]; an unknown id is an error, never a silent fall back to another module. */
        fun forId(id: String): CombatModule = when (id.lowercase()) {
            DEFAULT_ID, "morgana_classic", "classic" -> RealtimeCombatModule()
            "noop", "none" -> NoOpCombatModule()
            "lua_combat", "lua" -> LuaCombatModule()
            "action", "action_combat" -> ActionCombatModule()
            else -> throw IllegalArgumentException("Unknown combat module '$id'; valid: ${VALID_IDS.sorted().joinToString()}")
        }
    }
}

/**
 * The engine's real-time combat module (Kotlin implementation): the default a game gets unless it selects another.
 * The rules it applies (damage, crit, dodge, regeneration) come from the game's `combat` definition.
 */
class RealtimeCombatModule : CombatModule {
    override val id: String get() = CombatModule.DEFAULT_ID

    override fun tickProjectiles(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>) {
        sim.tickProjectiles(out)
    }

    override fun tickPlayers(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>) {
        sim.tickPlayers(out)
    }

    override fun onPlayerAttack(sim: WorldSimulation, characterId: String): List<WorldSimulationEvent> {
        return sim.playerAttackImpl(characterId)
    }

    override fun onPlayerCastSpell(
        sim: WorldSimulation,
        characterId: String,
        spellId: Int,
        targetEntityId: String?,
        targetTileX: Int,
        targetTileY: Int
    ): List<WorldSimulationEvent> {
        return sim.playerCastSpellImpl(characterId, spellId, targetEntityId, targetTileX, targetTileY)
    }

    override fun handleAction(
        sim: WorldSimulation,
        characterId: String,
        actionId: String,
        params: Map<String, String>
    ): CombatActionResult {
        return when (actionId.lowercase()) {
            "attack" -> CombatActionResult(handled = true, events = onPlayerAttack(sim, characterId))
            "cast", "cast_spell" -> {
                val spellId = params["spellId"]?.toIntOrNull()
                    ?: return CombatActionResult(handled = true, events = emptyList())
                val targetEntityId = params["targetEntityId"]
                val targetTileX = params["targetTileX"]?.toIntOrNull() ?: 0
                val targetTileY = params["targetTileY"]?.toIntOrNull() ?: 0
                CombatActionResult(
                    handled = true,
                    events = onPlayerCastSpell(sim, characterId, spellId, targetEntityId, targetTileX, targetTileY)
                )
            }
            else -> CombatActionResult(handled = false)
        }
    }
}

/**
 * Peaceful / non-combat module that disables all attacks, projectiles, and damage.
 */
class NoOpCombatModule(override val id: String = "noop") : CombatModule {
    override fun tickProjectiles(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>) {}
    override fun tickPlayers(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>) {}
    override fun onPlayerAttack(sim: WorldSimulation, characterId: String): List<WorldSimulationEvent> = emptyList()
    override fun onPlayerCastSpell(
        sim: WorldSimulation,
        characterId: String,
        spellId: Int,
        targetEntityId: String?,
        targetTileX: Int,
        targetTileY: Int
    ): List<WorldSimulationEvent> = emptyList()
}

/**
 * Combat module delegating to script-registered combat hooks in [com.morgana.domain.script.ScriptRegistry],
 * falling back to an underlying [fallback] module if a hook is not registered by scripts.
 */
class LuaCombatModule(
    override val id: String = "lua_combat",
    private val fallback: CombatModule = NoOpCombatModule()
) : CombatModule {
    override fun tickProjectiles(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>) {
        val hook = sim.scripts.registry.getCombatHooks()?.onTickProjectiles
        if (hook != null) {
            val effects = hook(sim.asScriptWorldView())
            for (eff in effects) {
                out.addAll(sim.applyScriptEffect(eff))
            }
        } else {
            fallback.tickProjectiles(sim, out)
        }
    }

    override fun tickPlayers(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>) {
        val hook = sim.scripts.registry.getCombatHooks()?.onTickPlayers
        if (hook != null) {
            val effects = hook(sim.asScriptWorldView())
            for (eff in effects) {
                out.addAll(sim.applyScriptEffect(eff))
            }
        } else {
            fallback.tickPlayers(sim, out)
        }
    }

    override fun onPlayerAttack(sim: WorldSimulation, characterId: String): List<WorldSimulationEvent> {
        val hook = sim.scripts.registry.getCombatHooks()?.onPlayerAttack
        if (hook != null) {
            val effects = hook(characterId, sim.asScriptWorldView())
            val out = mutableListOf<WorldSimulationEvent>()
            for (eff in effects) {
                out.addAll(sim.applyScriptEffect(eff))
            }
            return out
        }
        return fallback.onPlayerAttack(sim, characterId)
    }

    override fun onPlayerCastSpell(
        sim: WorldSimulation,
        characterId: String,
        spellId: Int,
        targetEntityId: String?,
        targetTileX: Int,
        targetTileY: Int
    ): List<WorldSimulationEvent> {
        val hook = sim.scripts.registry.getCombatHooks()?.onPlayerCastSpell
        if (hook != null) {
            val effects = hook(characterId, spellId, targetEntityId, targetTileX, targetTileY, sim.asScriptWorldView())
            val out = mutableListOf<WorldSimulationEvent>()
            for (eff in effects) {
                out.addAll(sim.applyScriptEffect(eff))
            }
            return out
        }
        return fallback.onPlayerCastSpell(sim, characterId, spellId, targetEntityId, targetTileX, targetTileY)
    }

    override fun handleAction(
        sim: WorldSimulation,
        characterId: String,
        actionId: String,
        params: Map<String, String>
    ): CombatActionResult {
        val hook = sim.scripts.registry.getCombatHooks()?.handleAction
        if (hook != null) {
            val effects = hook(characterId, actionId, params, sim.asScriptWorldView())
            val out = mutableListOf<WorldSimulationEvent>()
            for (eff in effects) {
                out.addAll(sim.applyScriptEffect(eff))
            }
            return CombatActionResult(handled = true, events = out)
        }
        return fallback.handleAction(sim, characterId, actionId, params)
    }
}

