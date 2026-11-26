package com.morgana.domain.systems

import com.morgana.domain.sim.isOnOrNearMap
import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.MonsterBehavior
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.afterStruck
import com.morgana.domain.sim.tellPlayer

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance

/**
 * The threat table as a system: damage and healing put threat on a monster, which keeps fighting its target until someone
 * out-threatens them by `threatSwitchPercent`, and a taunt lifts the taunter to the top. With the table off (the default) it
 * has no opinion and monsters turn on whoever hit them last.
 */
object ThreatSystem : MonsterBehavior, GameSystem {
    override val key: String = OptionalModule.THREAT.configKey
    override fun retarget(sim: WorldSimulation, npc: MapNpcInstance): MapNpcInstance {
        if (!sim.threatOn() || npc.threat.isEmpty()) return npc
        // Entries of players who left or died are ignored; nobody left means the table is cleared.
        val wanted = sim.threatTarget(npc, npc.targetEntityId)
        return when {
            wanted == null -> npc.copy(threat = emptyMap())
            wanted != npc.targetEntityId -> npc.copy(targetEntityId = wanted)
            else -> npc
        }
    }

    override fun onTaunted(sim: WorldSimulation, npc: MapNpcInstance, taunter: CharacterState): MapNpcInstance {
        if (!sim.threatOn()) return npc
        val most = npc.threat.values.maxOrNull() ?: 0
        val lifted = most.toLong() * sim.game.definition.combat.npcs.tauntThreatPercent / 100
        if ((npc.threat[taunter.id] ?: 0) >= lifted) return npc
        return npc.copy(threat = npc.threat + (taunter.id to lifted.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)))
    }

    override fun onHit(sim: WorldSimulation, npc: MapNpcInstance, attacker: CharacterState, damage: Int): MapNpcInstance? {
        if (!sim.threatOn()) return null
        // The monster keeps its target until someone out-threatens them.
        val withThreat = sim.withThreat(npc, attacker.id, damage)
        return withThreat.copy(targetEntityId = sim.threatTarget(withThreat, npc.targetEntityId) ?: attacker.id)
    }

    override fun afterHeal(sim: WorldSimulation, healer: CharacterState, healed: CharacterState, amount: Int) =
        sim.addHealThreat(healer, healed, amount)
}

/** Whether the threat table is on (`combat.npcs.threatSwitchPercent` of 100 or more). */
internal fun WorldSimulation.threatOn(): Boolean = game.definition.combat.npcs.threatSwitchPercent >= 100

/** [npc] with [amount] more threat from [characterId] (unchanged when threat is off or the amount is not positive). */
internal fun WorldSimulation.withThreat(npc: MapNpcInstance, characterId: String, amount: Int): MapNpcInstance {
    if (!threatOn() || amount <= 0) return npc
    val next = (npc.threat[characterId] ?: 0).toLong() + amount
    return npc.copy(threat = npc.threat + (characterId to next.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
}

/**
 * Who [npc] should be fighting given its threat table: nobody's threat counts unless they are alive on the monster's map.
 * The current target keeps the fight until another player's threat is more than `threatSwitchPercent` percent of theirs;
 * with no valid current target the highest threat takes over (ties go to the lower character id, so the pick is stable).
 * Null when nobody has any threat.
 */
internal fun WorldSimulation.threatTarget(npc: MapNpcInstance, currentId: String?): String? {
    val life = game.lifeVitalId
    val valid = npc.threat.filter { (id, t) -> t > 0 && players[id]?.let { it.isAlive(life) && isOnOrNearMap(it, npc.mapId) } == true }
    if (valid.isEmpty()) return null
    val top = valid.entries.sortedWith(compareBy({ -it.value }, { it.key })).first()
    val current = currentId?.takeIf { it in valid }
    if (current == null) return top.key
    if (top.key == current) return current
    val switchAt = game.definition.combat.npcs.threatSwitchPercent
    return if (top.value.toLong() * 100 > valid.getValue(current).toLong() * switchAt) top.key else current
}

/** Adds the healer's threat on every monster that is fighting [healed] (or has them in its table), for healing worth [amount]. */
internal fun WorldSimulation.addHealThreat(healer: CharacterState, healed: CharacterState, amount: Int) {
    val percent = game.definition.combat.npcs.healThreatPercent
    if (!threatOn() || percent <= 0 || amount <= 0) return
    val list = mapNpcs[healer.position.mapId] ?: return
    for (i in list.indices) {
        val npc = list[i]
        if (!npc.isAlive || (npc.targetEntityId != healed.id && healed.id !in npc.threat)) continue
        list[i] = withThreat(npc, healer.id, amount * percent / 100)
    }
}
