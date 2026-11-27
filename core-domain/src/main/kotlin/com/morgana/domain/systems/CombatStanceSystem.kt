package com.morgana.domain.systems

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CombatStanceDefinition
import com.morgana.domain.game.Stance
import com.morgana.domain.game.StancePermission
import com.morgana.domain.sim.ChatCommandHandler
import com.morgana.domain.sim.CombatArbiter
import com.morgana.domain.sim.CombatObserver
import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.TickParticipant
import com.morgana.domain.sim.TickStage
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.isOn
import com.morgana.domain.sim.tellPlayer

/**
 * Combat stance: each character chooses SAFE, PvE or PvP ([Stance]) and may only hurt, or be hurt by, another character when both are in
 * PvP stance. It sits over the PK mode (which still decides who among PvP-stance characters may fight) and safe zones.
 *
 * - **Where**: `combatStance.maps` gives each map a [StancePermission]: NONE (the stance is ignored), OPTIONAL or FORCED (everyone is in PvP).
 *   A [com.morgana.domain.sim.StanceForcer] (territory, inside an open attack window) forces it too.
 * - **Arming**: asking for PvP takes `armTicks` and is cancelled by taking a hit. Leaving it needs `disarmTicks` without combat; switching
 *   between SAFE and PvE needs `switchTicks`. Hitting with a weapon while SAFE makes the character PvE.
 * - **In combat** means having dealt or taken damage; [ticksSinceCombat] and [isInCombat] share that with other rules (such as claim lockouts).
 * - State is on the character (`stance`, `stanceArmsAtTick`, `lastCombatTick`), so it is saved, hashed and replayed. `/stance` asks for a change.
 * - Without a `combatStance` section the system does nothing.
 */
object CombatStanceSystem : CombatArbiter, CombatObserver, TickParticipant, ChatCommandHandler, GameSystem {
    override val key: String = OptionalModule.COMBAT_STANCE.configKey
    override val stage: TickStage = TickStage.AFTER_NPCS

    private fun definition(sim: WorldSimulation): CombatStanceDefinition? = sim.game.definition.combatStance

    // ---- reading ----------------------------------------------------------------------------------------------------------------

    /** What the map [mapId] allows (NONE when the game defines no stance). */
    fun permissionOn(sim: WorldSimulation, mapId: Int): StancePermission = definition(sim)?.permissionOn(mapId) ?: StancePermission.NONE

    /** Whether [player] is forced into PvP right now: by the map or by a system such as an open siege window. */
    fun isForced(sim: WorldSimulation, player: CharacterState): Boolean =
        permissionOn(sim, player.position.mapId) == StancePermission.FORCED ||
            sim.stanceForcers.any { sim.isOn(it) && it.forcesPvp(sim, player) }

    /** The stance [player] fights in right now. */
    fun effectiveStance(sim: WorldSimulation, player: CharacterState): Stance = when {
        definition(sim) == null -> Stance.PVP
        isForced(sim, player) -> Stance.PVP
        player.stance == Stance.PVP && permissionOn(sim, player.position.mapId) == StancePermission.NONE -> Stance.PVE
        else -> player.stance
    }

    /** Ticks since [characterId] last dealt or took damage, or null when they never have. */
    fun ticksSinceCombat(sim: WorldSimulation, characterId: String): Long? {
        val last = sim.players[characterId]?.lastCombatTick ?: return null
        return if (last < 0) null else sim.effectiveWorldTick() - last
    }

    /** True when [characterId] dealt or took damage within the last [withinTicks] ticks (e.g. for a combat lockout). */
    fun isInCombat(sim: WorldSimulation, characterId: String, withinTicks: Long): Boolean =
        ticksSinceCombat(sim, characterId)?.let { it < withinTicks } ?: false

    // ---- who may hurt whom (CombatArbiter) --------------------------------------------------------------------------------------

    /** Two characters fight only when both are in PvP stance; then the PK mode and safe zones decide. */
    override fun mayFight(sim: WorldSimulation, attacker: CharacterState, defender: CharacterState): Boolean? {
        if (definition(sim) == null) return null
        return if (effectiveStance(sim, attacker) == Stance.PVP && effectiveStance(sim, defender) == Stance.PVP) null else false
    }

    // ---- combat marks (CombatObserver) ------------------------------------------------------------------------------------------

    override fun afterAttack(sim: WorldSimulation, attackerId: String, out: MutableList<WorldSimulationEvent>) {
        if (definition(sim) == null) return
        val attacker = sim.players[attackerId] ?: return
        var updated = attacker.copy(lastCombatTick = sim.effectiveWorldTick())
        if (updated.stance == Stance.SAFE) {
            updated = updated.copy(stance = Stance.PVE)
            out.add(sim.tellPlayer(updated, "You are no longer in the Safe stance.", "INFO"))
        }
        sim.players[attackerId] = updated
    }

    override fun afterStruck(sim: WorldSimulation, victim: CharacterState, out: MutableList<WorldSimulationEvent>): CharacterState {
        if (definition(sim) == null) return victim
        var updated = victim.copy(lastCombatTick = sim.effectiveWorldTick())
        if (updated.stanceArmsAtTick >= 0) {
            updated = updated.copy(stanceArmsAtTick = -1L)
            out.add(sim.tellPlayer(updated, "Taking a hit stopped the PvP stance arming.", "WARNING"))
        }
        return updated
    }

    // ---- arming (TickParticipant) -----------------------------------------------------------------------------------------------

    override fun onTick(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>) {
        if (definition(sim) == null) return
        val now = sim.effectiveWorldTick()
        for (id in sim.players.keys.sorted()) {
            val p = sim.players[id] ?: continue
            if (p.stanceArmsAtTick < 0 || now < p.stanceArmsAtTick) continue
            val armed = p.copy(stance = Stance.PVP, stanceArmsAtTick = -1L)
            sim.players[id] = armed
            out.add(WorldSimulationEvent.CharacterUpdated(armed))
            out.add(sim.tellPlayer(armed, "You are now in the PvP stance.", "WARNING"))
        }
    }

    // ---- /stance ----------------------------------------------------------------------------------------------------------------

    override fun handle(sim: WorldSimulation, player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>): Boolean {
        if (parts.firstOrNull()?.lowercase() != "stance") return false
        val rules = definition(sim)
        fun say(text: String, kind: String = "INFO") = out.add(sim.tellPlayer(player, text, kind))
        if (rules == null) {
            say("There are no stances in this game.", "WARNING")
            return true
        }
        val wanted = when (parts.getOrNull(1)?.lowercase()) {
            null, "" -> null
            "safe" -> Stance.SAFE
            "pve" -> Stance.PVE
            "pvp" -> Stance.PVP
            else -> { say("Usage: /stance [safe|pve|pvp]", "WARNING"); return true }
        }
        val effective = effectiveStance(sim, player)
        if (wanted == null) {
            val arming = if (player.stanceArmsAtTick >= 0) " (PvP arming)" else ""
            val forced = if (isForced(sim, player)) " (forced here)" else ""
            say("Stance: ${effective.label}$forced$arming. Here PvP is ${permissionOn(sim, player.position.mapId).name.lowercase()}.")
            return true
        }
        val now = sim.effectiveWorldTick()
        if (isForced(sim, player)) { say("You cannot change your stance here.", "WARNING"); return true }
        if (wanted == Stance.PVP) {
            if (permissionOn(sim, player.position.mapId) == StancePermission.NONE) { say("PvP is not allowed here.", "WARNING"); return true }
            if (player.stance == Stance.PVP) { say("You are already in the PvP stance."); return true }
            if (rules.armTicks <= 0) {
                val armed = player.copy(stance = Stance.PVP, stanceArmsAtTick = -1L)
                sim.players[player.id] = armed
                out.add(WorldSimulationEvent.CharacterUpdated(armed))
                say("You are now in the PvP stance.", "WARNING")
            } else {
                val arming = player.copy(stanceArmsAtTick = now + rules.armTicks)
                sim.players[player.id] = arming
                out.add(WorldSimulationEvent.CharacterUpdated(arming))
                say("The PvP stance arms in ${rules.armTicks / 20} seconds. Taking a hit cancels it.", "WARNING")
            }
            return true
        }
        val wait = if (player.stance == Stance.PVP) rules.disarmTicks else rules.switchTicks
        val since = ticksSinceCombat(sim, player.id)
        if (since != null && since < wait) {
            say("You are still in combat. Try again in ${(wait - since + 19) / 20} seconds.", "WARNING")
            return true
        }
        val changed = player.copy(stance = wanted, stanceArmsAtTick = -1L)
        sim.players[player.id] = changed
        out.add(WorldSimulationEvent.CharacterUpdated(changed))
        say("Stance: ${wanted.label}.")
        return true
    }
}
