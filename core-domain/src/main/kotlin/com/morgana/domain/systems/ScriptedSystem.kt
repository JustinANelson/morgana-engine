package com.morgana.domain.systems

import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.game.EnemyVariantDefinition
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.script.ScriptEffect
import com.morgana.domain.script.ScriptItemSubject
import com.morgana.domain.script.ScriptMonsterHook
import com.morgana.domain.script.ScriptRequirementSubject
import com.morgana.domain.script.ScriptTickStage
import com.morgana.domain.script.ScriptWarpRequest
import com.morgana.domain.script.ScriptWarpRuling
import com.morgana.domain.sim.ActionGate
import com.morgana.domain.sim.ActivityInterrupter
import com.morgana.domain.sim.CombatArbiter
import com.morgana.domain.sim.CombatObserver
import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.ItemUseHandler
import com.morgana.domain.sim.KillObserver
import com.morgana.domain.sim.MonsterBehavior
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.PlayerActivity
import com.morgana.domain.sim.RequirementGate
import com.morgana.domain.sim.ScheduleHandler
import com.morgana.domain.sim.WorldObjectGate
import com.morgana.domain.script.ScriptObjectRequest
import com.morgana.domain.sim.ScheduleTrigger
import com.morgana.domain.sim.SchedulePhase
import com.morgana.domain.script.ScriptScheduleTrigger
import com.morgana.domain.sim.TickParticipant
import com.morgana.domain.sim.TickStage
import com.morgana.domain.sim.WarpRoute
import com.morgana.domain.sim.WarpRouter
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.tellPlayer

/**
 * Lua plugins as a system (ADR 0018): the bridge between what scripts register (see `ScriptSystemHooks` and
 * `docs/API_REFERENCE.md`) and the kernel seams. It is installed last in every world, so the engine's own systems give their
 * opinion first, and it does nothing at all when no script registered a hook. It is the `luaPlugins` system: switching that
 * off silences every hook. Its tick hooks run through [ScriptedTicks].
 *
 * A hook runs only while the systems its plugin declared in `register.plugin{ requires = ... }` are switched on. A hook that
 * throws, runs out of its instruction budget or returns something of the wrong type counts as "no opinion" and is reported to
 * the runtime circuit breaker, which switches the whole script module off after repeated failures.
 *
 * Effects a hook causes go through `applyScriptEffect`, so the game's script capabilities still limit them. Where the engine is
 * part-way through changing a character (the one just struck, the one being interrupted) effects that would change that
 * character are dropped, because the engine stores its own copy a moment later and would overwrite them.
 */
object ScriptedSystem :
    RequirementGate, MonsterBehavior, CombatArbiter, ActionGate, ActivityInterrupter, CombatObserver, KillObserver, ItemUseHandler,
    ScheduleHandler, WorldObjectGate, com.morgana.domain.sim.WorldObjectInteraction, GameSystem {
    override val key: String = OptionalModule.LUA_PLUGINS.configKey

    private fun WorldSimulation.active(moduleId: String): Boolean =
        scripts.registry.pluginOf(moduleId)?.requires?.all { modules.isEnabled(it) } ?: true

    private fun WorldSimulation.apply(effects: List<ScriptEffect>, out: MutableList<WorldSimulationEvent>, keepOutOf: String? = null) {
        for (effect in effects) {
            if (keepOutOf != null && effect.changesPlayer(keepOutOf)) continue
            out.addAll(applyScriptEffect(effect))
        }
    }

    /** Whether [effect] would change the character [playerId] (a message to them does not count). */
    private fun ScriptEffect.changesPlayer(playerId: String): Boolean = when (this) {
        is ScriptEffect.DamagePlayer -> targetId == playerId
        is ScriptEffect.HealPlayer -> targetId == playerId
        is ScriptEffect.GiveItem -> this.playerId == playerId
        is ScriptEffect.RemoveItem -> this.playerId == playerId
        is ScriptEffect.WarpPlayer -> this.playerId == playerId
        is ScriptEffect.ApplyStatus -> targetPlayerId == playerId
        is ScriptEffect.RemoveStatus -> targetPlayerId == playerId
        is ScriptEffect.AwardExperience -> this.playerId == playerId
        is ScriptEffect.ConsumeVital -> this.playerId == playerId
        else -> false
    }

    // --- RequirementGate

    override fun spellRefusal(sim: WorldSimulation, player: CharacterState, spell: SpellDefinition): String? =
        refusal(sim, player, ScriptRequirementSubject("spell", spell.id, spell.name, spell.levelReq))

    override fun itemRefusal(sim: WorldSimulation, player: CharacterState, item: ItemDefinition): String? =
        refusal(sim, player, ScriptRequirementSubject("item", item.id, item.name, item.levelReq))

    private fun refusal(sim: WorldSimulation, player: CharacterState, subject: ScriptRequirementSubject): String? {
        val hooks = sim.scripts.registry.requirementHooks()
        if (hooks.isEmpty()) return null
        val view = sim.asScriptWorldView()
        val snapshot = view.getPlayer(player.id) ?: return null
        return hooks.firstNotNullOfOrNull { hook ->
            if (sim.active(hook.moduleId)) hook.check(subject, snapshot, view)?.takeIf { it.isNotBlank() } else null
        }
    }

    // --- CombatArbiter

    override fun mayFight(sim: WorldSimulation, attacker: CharacterState, defender: CharacterState): Boolean? {
        val hooks = sim.scripts.registry.fightHooks()
        if (hooks.isEmpty()) return null
        val view = sim.asScriptWorldView()
        val a = view.getPlayer(attacker.id) ?: return null
        val d = view.getPlayer(defender.id) ?: return null
        return hooks.firstNotNullOfOrNull { hook -> if (sim.active(hook.moduleId)) hook.check(a, d, view) else null }
    }

    // --- ActionGate

    override fun blocks(sim: WorldSimulation, characterId: String, activity: PlayerActivity): Boolean {
        val all = sim.scripts.registry.actionGateHooks()
        if (all.isEmpty()) return false
        val name = activity.name.lowercase()
        val hooks = all.filter { it.covers(name) && sim.active(it.moduleId) }
        if (hooks.isEmpty()) return false
        val view = sim.asScriptWorldView()
        val snapshot = view.getPlayer(characterId) ?: return false
        return hooks.any { it.blocks(snapshot, name, view) }
    }

    // --- ActivityInterrupter

    override fun interrupt(sim: WorldSimulation, characterId: String, out: MutableList<WorldSimulationEvent>) {
        val all = sim.scripts.registry.interruptHooks()
        if (all.isEmpty()) return
        val hooks = all.filter { sim.active(it.moduleId) }
        if (hooks.isEmpty()) return
        val view = sim.asScriptWorldView()
        val snapshot = view.getPlayer(characterId) ?: return
        for (hook in hooks) sim.apply(hook.run(snapshot, view), out, keepOutOf = characterId)
    }

    // --- CombatObserver

    override fun afterAttack(sim: WorldSimulation, attackerId: String, out: MutableList<WorldSimulationEvent>) {
        val hooks = sim.scripts.registry.combatObserverHooks().filter { it.afterAttack != null && sim.active(it.moduleId) }
        if (hooks.isEmpty()) return
        val view = sim.asScriptWorldView()
        val snapshot = view.getPlayer(attackerId) ?: return
        for (hook in hooks) sim.apply(hook.afterAttack!!.invoke(snapshot, view), out)
    }

    override fun afterStruck(sim: WorldSimulation, victim: CharacterState, out: MutableList<WorldSimulationEvent>): CharacterState {
        val hooks = sim.scripts.registry.combatObserverHooks().filter { it.afterStruck != null && sim.active(it.moduleId) }
        if (hooks.isEmpty()) return victim
        val view = sim.asScriptWorldView()
        val snapshot = view.getPlayer(victim.id) ?: return victim
        for (hook in hooks) sim.apply(hook.afterStruck!!.invoke(snapshot, view), out, keepOutOf = victim.id)
        return victim
    }

    // --- KillObserver

    override fun onKillReward(sim: WorldSimulation, killer: CharacterState, xpReward: Long, out: MutableList<WorldSimulationEvent>) {
        val hooks = sim.scripts.registry.killHooks().filter { sim.active(it.moduleId) }
        if (hooks.isEmpty()) return
        val view = sim.asScriptWorldView()
        val snapshot = view.getPlayer(killer.id) ?: return
        for (hook in hooks) sim.apply(hook.run(snapshot, xpReward, view), out)
    }

    // --- WorldObjectGate

    override fun refusal(sim: WorldSimulation, kind: com.morgana.domain.game.WorldObjectKind, mapId: Int, tileX: Int, tileY: Int, ownerCharacterId: String?): String? {
        val hooks = sim.scripts.registry.objectGateHooks().filter { it.covers(kind.id) && sim.active(it.moduleId) }
        if (hooks.isEmpty()) return null
        val view = sim.asScriptWorldView()
        val request = ScriptObjectRequest(kind.id, mapId, tileX, tileY, ownerCharacterId)
        return hooks.firstNotNullOfOrNull { it.check(request, view) }
    }

    // --- WorldObjectInteraction

    override fun interactionRefusal(
        sim: WorldSimulation, player: CharacterState, obj: com.morgana.domain.entity.WorldObject, kind: com.morgana.domain.game.WorldObjectKind
    ): String? {
        val hooks = sim.scripts.registry.objectUseHooks().filter { it.gate != null && it.covers(kind.id) && sim.active(it.moduleId) }
        if (hooks.isEmpty()) return null
        val view = sim.asScriptWorldView()
        val who = view.getPlayer(player.id) ?: return null
        val what = view.getWorldObject(obj.id) ?: return null
        return hooks.firstNotNullOfOrNull { it.gate!!(who, what, view) }
    }

    override fun interact(
        sim: WorldSimulation, player: CharacterState, obj: com.morgana.domain.entity.WorldObject, kind: com.morgana.domain.game.WorldObjectKind,
        out: MutableList<WorldSimulationEvent>
    ): Boolean {
        val hooks = sim.scripts.registry.objectUseHooks().filter { it.use != null && it.covers(kind.id) && sim.active(it.moduleId) }
        if (hooks.isEmpty()) return false
        val view = sim.asScriptWorldView()
        val who = view.getPlayer(player.id) ?: return false
        val what = view.getWorldObject(obj.id) ?: return false
        for (hook in hooks) sim.apply(hook.use!!(who, what, view), out)
        return true
    }

    // --- ScheduleHandler

    override fun onSchedule(sim: WorldSimulation, trigger: ScheduleTrigger, out: MutableList<WorldSimulationEvent>) {
        val hooks = sim.scripts.registry.scheduleHooks().filter { it.covers(trigger.scheduleId) && sim.active(it.moduleId) }
        if (hooks.isEmpty()) return
        val view = sim.asScriptWorldView()
        val info = ScriptScheduleTrigger(
            trigger.scheduleId, trigger.name, if (trigger.phase == SchedulePhase.WARNING) "warning" else "fire",
            trigger.secondsBefore, trigger.occurrenceMillis, trigger.recurring, trigger.data
        )
        for (hook in hooks) {
            val run = if (trigger.phase == SchedulePhase.WARNING) hook.onWarning else hook.onFire
            if (run != null) sim.apply(run(info, view), out)
        }
    }

    // --- ItemUseHandler

    override fun use(sim: WorldSimulation, player: CharacterState, slotIndex: Int, item: ItemDefinition, out: MutableList<WorldSimulationEvent>): Boolean {
        val all = sim.scripts.registry.itemUseHooks()
        if (all.isEmpty()) return false
        val hooks = all.filter { it.covers(item.id) && sim.active(it.moduleId) }
        if (hooks.isEmpty()) return false
        val view = sim.asScriptWorldView()
        val snapshot = view.getPlayer(player.id) ?: return false
        val subject = ScriptItemSubject(item.id, item.name, item.type.name.lowercase(), item.levelReq)
        for (hook in hooks) {
            val result = hook.use(subject, snapshot, slotIndex, view)
            if (result.handled) {
                sim.apply(result.effects, out)
                return true
            }
        }
        return false
    }

    // --- MonsterBehavior

    private fun WorldSimulation.monsterHooksFor(npcId: Int, wants: (ScriptMonsterHook) -> Boolean): List<ScriptMonsterHook> {
        val all = scripts.registry.monsterHooks()
        if (all.isEmpty()) return emptyList()
        return all.filter { wants(it) && it.covers(npcId) && active(it.moduleId) }
    }

    override fun holdsStill(sim: WorldSimulation, npc: MapNpcInstance, def: NpcDefinition): Boolean {
        val hooks = sim.monsterHooksFor(def.id) { it.holdsStill != null }
        if (hooks.isEmpty()) return false
        val view = sim.asScriptWorldView()
        val snapshot = view.getNpc(npc.mapId, npc.instanceId) ?: return false
        return hooks.any { it.holdsStill!!.invoke(snapshot, view) }
    }

    override fun step(
        sim: WorldSimulation, npc: MapNpcInstance, def: NpcDefinition, map: MapDefinition, out: MutableList<WorldSimulationEvent>
    ): MapNpcInstance {
        val hooks = sim.monsterHooksFor(def.id) { it.step != null }
        if (hooks.isEmpty()) return npc
        val view = sim.asScriptWorldView()
        val snapshot = view.getNpc(npc.mapId, npc.instanceId) ?: return npc
        for (hook in hooks) sim.apply(hook.step!!.invoke(snapshot, view), out)
        // The effects may have changed this very monster (damage, healing): hand back what is stored now, not the stale copy.
        return sim.mapNpcs[npc.mapId]?.firstOrNull { it.instanceId == npc.instanceId } ?: npc
    }

    override fun adjustVariant(sim: WorldSimulation, npc: MapNpcInstance, variant: EnemyVariantDefinition?): EnemyVariantDefinition? {
        val hooks = sim.monsterHooksFor(npc.npcId) { it.variant != null }
        if (hooks.isEmpty()) return variant
        val view = sim.asScriptWorldView()
        val snapshot = view.getNpc(npc.mapId, npc.instanceId) ?: return variant
        var current = variant
        for (hook in hooks) {
            val multipliers = hook.variant!!.invoke(snapshot, view) ?: continue
            val base = current ?: EnemyVariantDefinition(id = "plugin", label = "", levelBonus = 0)
            current = base.copy(
                attackPercent = base.attackPercent * multipliers.attackPercent / 100,
                moveSpeedPercent = base.moveSpeedPercent * multipliers.moveSpeedPercent / 100
            )
        }
        return current
    }

    override fun retarget(sim: WorldSimulation, npc: MapNpcInstance): MapNpcInstance {
        val hooks = sim.monsterHooksFor(npc.npcId) { it.retarget != null }
        if (hooks.isEmpty()) return npc
        val view = sim.asScriptWorldView()
        val snapshot = view.getNpc(npc.mapId, npc.instanceId) ?: return npc
        val wanted = hooks.firstNotNullOfOrNull { it.retarget!!.invoke(snapshot, view) } ?: return npc
        if (wanted == npc.targetEntityId) return npc
        val player = sim.players[wanted]?.takeIf { it.isAlive(sim.game.lifeVitalId) && it.position.mapId == npc.mapId } ?: return npc
        return npc.copy(targetEntityId = player.id)
    }

    override fun mayBeElite(sim: WorldSimulation, def: NpcDefinition): Boolean {
        val hooks = sim.monsterHooksFor(def.id) { it.mayBeElite != null }
        if (hooks.isEmpty()) return true
        val view = sim.asScriptWorldView()
        return hooks.all { it.mayBeElite!!.invoke(def.id, view) }
    }

    override fun killLockoutTicks(sim: WorldSimulation, def: NpcDefinition): Long {
        val hooks = sim.monsterHooksFor(def.id) { it.killLockoutTicks != null }
        if (hooks.isEmpty()) return 0L
        val view = sim.asScriptWorldView()
        return hooks.maxOf { it.killLockoutTicks!!.invoke(def.id, view)?.coerceAtLeast(0L) ?: 0L }
    }
}

/**
 * The tick hooks of Lua plugins. A [TickParticipant] has one stage, so there is one of these per stage; both answer to the
 * `luaPlugins` system switch and run each hook every `everyTicks` ticks (counted on the world tick, so every hook with the same
 * period runs on the same ticks).
 */
class ScriptedTicks(override val stage: TickStage) : TickParticipant, GameSystem {
    override val key: String = OptionalModule.LUA_PLUGINS.configKey

    override fun onTick(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>) {
        val all = sim.scripts.registry.tickHooks()
        if (all.isEmpty()) return
        val wanted = if (stage == TickStage.AFTER_NPCS) ScriptTickStage.AFTER_NPCS else ScriptTickStage.AFTER_EVENTS
        val now = sim.currentTick
        val due = all.filter { hook ->
            hook.stage == wanted && now % hook.everyTicks == 0L &&
                (sim.scripts.registry.pluginOf(hook.moduleId)?.requires?.all { sim.modules.isEnabled(it) } ?: true)
        }
        if (due.isEmpty()) return
        val view = sim.asScriptWorldView()
        for (hook in due) for (effect in hook.run(view)) out.addAll(sim.applyScriptEffect(effect))
    }
}

/**
 * The warp hooks of Lua plugins (`register.warp`). It is a router of its own, installed **before** the engine's instancing, because
 * a plugin that refuses a warp or sends the player elsewhere has to see the request as it was made; a redirect then goes through
 * the routers again, so the new map gets its private copy if it is an instanced template. It answers to the `luaPlugins` switch.
 */
object ScriptedWarps : WarpRouter, GameSystem {
    override val key: String = OptionalModule.LUA_PLUGINS.configKey

    override fun route(
        sim: WorldSimulation, player: CharacterState, target: MapDefinition, tileX: Int, tileY: Int, out: MutableList<WorldSimulationEvent>
    ): WarpRoute? {
        val all = sim.scripts.registry.warpHooks()
        if (all.isEmpty()) return null
        val hooks = all.filter { hook -> sim.scripts.registry.pluginOf(hook.moduleId)?.requires?.all { sim.modules.isEnabled(it) } ?: true }
        if (hooks.isEmpty()) return null
        val view = sim.asScriptWorldView()
        val snapshot = view.getPlayer(player.id) ?: return null
        val request = ScriptWarpRequest(target.id, target.name, tileX, tileY, target.instanced)
        return when (val ruling = hooks.firstNotNullOfOrNull { it.check(snapshot, request, view) }) {
            null -> null
            is ScriptWarpRuling.Refuse -> {
                ruling.message?.takeIf { it.isNotBlank() }?.let { out.add(sim.tellPlayer(player, it, "WARN")) }
                WarpRoute.Refused
            }
            is ScriptWarpRuling.Redirect -> if (ruling.mapId != target.id && sim.maps[ruling.mapId] != null) WarpRoute.Redirect(ruling.mapId) else null
        }
    }
}
