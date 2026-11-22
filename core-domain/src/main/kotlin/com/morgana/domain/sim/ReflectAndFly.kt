package com.morgana.domain.sim

import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.stats.StatusEffectType
import com.morgana.domain.stats.VitalState

/** True while the character has a `FLY` status effect (see [StatusEffectType.FLY]). */
val CharacterState.isFlying: Boolean
    get() = statusEffects.any { it.type == StatusEffectType.FLY }

/** Percent of melee damage this character sends back at the attacker while a `REFLECT` effect runs (the strongest one counts). */
val CharacterState.reflectPercent: Int
    get() = statusEffects.filter { it.type == StatusEffectType.REFLECT }.maxOfOrNull { it.magnitude }?.coerceIn(0, 100) ?: 0

/**
 * Flight carries a character over blocked tiles, except on the Guild War map where the wall and its gates must hold
 * (see `GuildWarDefinition.mapId`).
 */
internal fun WorldSimulation.mayFlyOver(map: MapDefinition): Boolean = game.definition.guildWar?.mapId != map.id

/**
 * `REFLECT`: sends [percent] of [damage] taken by [defender] back at the monster [npc] that dealt it, and returns the monster
 * afterwards (dead and respawning if the reflection kills it, with [defender] credited with the kill). Unchanged when the
 * defender has no `REFLECT` effect.
 */
internal fun WorldSimulation.reflectOntoNpc(
    npc: MapNpcInstance,
    npcDef: NpcDefinition,
    defender: CharacterState,
    damage: Int,
    out: MutableList<WorldSimulationEvent>
): MapNpcInstance {
    val back = damage * defender.reflectPercent / 100
    if (back <= 0 || !npc.isAlive) return npc
    val lifeId = game.lifeVitalId
    val cur = npc.vitals[lifeId]?.cur ?: return npc
    val hurt = npc.copy(vitals = npc.vitals + (lifeId to (npc.vitals[lifeId]?.copy(cur = (cur - back).coerceAtLeast(0)) ?: VitalState(0, 0))))
    out.add(WorldSimulationEvent.FloatingTextEmitted(npc.mapId, npc.tileX, npc.tileY, "-$back", "DAMAGE", "#38BDF8"))
    val result = if (!hurt.isAlive(lifeId)) handleNpcKilled(hurt, npcDef, defender, out) else hurt
    out.add(WorldSimulationEvent.NpcUpdated(result))
    return result
}

/**
 * `REFLECT` in player fights: sends [percent] of [damage] back at [attacker]. A reflection never kills (it leaves the attacker
 * on at least 1 life), so a player is only ever killed by a real blow. Returns the attacker afterwards.
 */
internal fun WorldSimulation.reflectOntoPlayer(
    attacker: CharacterState,
    defender: CharacterState,
    damage: Int,
    out: MutableList<WorldSimulationEvent>
): CharacterState {
    val back = damage * defender.reflectPercent / 100
    if (back <= 0) return attacker
    val lifeId = game.lifeVitalId
    val life = attacker.vitals[lifeId] ?: return attacker
    val taken = back.coerceAtMost((life.cur - 1).coerceAtLeast(0))
    if (taken <= 0) return attacker
    out.add(WorldSimulationEvent.FloatingTextEmitted(attacker.position.mapId, attacker.position.tileX, attacker.position.tileY, "-$taken", "DAMAGE", "#38BDF8"))
    val hurt = attacker.copy(vitals = attacker.vitals + (lifeId to life.copy(cur = life.cur - taken)))
    players[hurt.id] = hurt
    out.add(WorldSimulationEvent.CharacterUpdated(hurt))
    return hurt
}
