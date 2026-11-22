package com.morgana.domain.sim

import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.game.EnemyVariantDefinition
import com.morgana.domain.map.MapDefinition

/**
 * The monster-behaviour seam (ADR 0018): what a system may add to the way monsters think, fight and die. The kernel keeps the
 * basics (respawning, aggro by sight, walking, striking, leashing, status effects, loot); everything below is optional
 * behaviour that systems provide and a game can add to or replace: a boss's phases and ground attacks, a threat table, Guild
 * War poles that stand still. Every method defaults to "no opinion", so a system implements only what it needs.
 *
 * Methods that return a monster return it **as it should now be stored**; the kernel writes it back when it changed.
 * Like every seam they run inside the deterministic simulation, so they must not use clocks or their own randomness
 * (use `sim.rng`) and must change the world only through the monster they return, `out`, or the kernel's recorded paths.
 * Behaviours are applied in the order the systems were installed.
 */
interface MonsterBehavior {
    /** Housekeeping for the whole of [mapId] before its [monsters] are stepped (a boss's adds go when the fight is over). */
    fun beforeMap(sim: WorldSimulation, mapId: Int, monsters: List<MapNpcInstance>, out: MutableList<WorldSimulationEvent>) {}

    /** True when [npc] does nothing at all this tick (Guild War poles and gates stand still and never fight back). */
    fun holdsStill(sim: WorldSimulation, npc: MapNpcInstance, def: NpcDefinition): Boolean = false

    /** A scripted step for a living monster before the kernel thinks for it (a boss fight's phases and danger zones). */
    fun step(
        sim: WorldSimulation, npc: MapNpcInstance, def: NpcDefinition, map: MapDefinition, out: MutableList<WorldSimulationEvent>
    ): MapNpcInstance = npc

    /** Folds extra multipliers into the monster's [variant] (a boss phase hits harder and moves faster); [variant] is null for an ordinary monster. */
    fun adjustVariant(sim: WorldSimulation, npc: MapNpcInstance, variant: EnemyVariantDefinition?): EnemyVariantDefinition? = variant

    /** Reconsiders who the monster fights (a threat table follows the highest threat), by changing its `targetEntityId` or clearing what it tracks. */
    fun retarget(sim: WorldSimulation, npc: MapNpcInstance): MapNpcInstance = npc

    /** [taunter] has just forced the fight onto themselves with a taunt (a threat table lifts them to the top). */
    fun onTaunted(sim: WorldSimulation, npc: MapNpcInstance, taunter: CharacterState): MapNpcInstance = npc

    /**
     * [attacker] dealt [damage] to [npc] (its life is already lowered): returns the monster with whatever the system records
     * about it (threat, who it now fights), or null for no opinion. The first opinion wins; with none the monster turns on [attacker].
     */
    fun onHit(sim: WorldSimulation, npc: MapNpcInstance, attacker: CharacterState, damage: Int): MapNpcInstance? = null

    /** [healer] healed [healed] for [amount] (a threat table puts the healing on every monster fighting them). */
    fun afterHeal(sim: WorldSimulation, healer: CharacterState, healed: CharacterState, amount: Int) {}

    /** False when [def] must never become an elite variant (a scripted boss is not also an elite). One "no" is enough. */
    fun mayBeElite(sim: WorldSimulation, def: NpcDefinition): Boolean = true

    /** How many ticks a kill of [def] locks its killers out of its rewards (0 for none); the longest asked wins. */
    fun killLockoutTicks(sim: WorldSimulation, def: NpcDefinition): Long = 0L
}

internal fun WorldSimulation.beforeMonstersOf(mapId: Int, monsters: List<MapNpcInstance>, out: MutableList<WorldSimulationEvent>) {
    for (b in monsterBehaviors) if (isOn(b)) b.beforeMap(this, mapId, monsters, out)
}

internal fun WorldSimulation.monsterHoldsStill(npc: MapNpcInstance, def: NpcDefinition): Boolean =
    monsterBehaviors.any { isOn(it) && it.holdsStill(this, npc, def) }

internal fun WorldSimulation.stepMonster(
    npc: MapNpcInstance, def: NpcDefinition, map: MapDefinition, out: MutableList<WorldSimulationEvent>
): MapNpcInstance = monsterBehaviors.fold(npc) { current, b -> if (isOn(b)) b.step(this, current, def, map, out) else current }

internal fun WorldSimulation.adjustedVariant(npc: MapNpcInstance, base: EnemyVariantDefinition?): EnemyVariantDefinition? =
    monsterBehaviors.fold(base) { current, b -> if (isOn(b)) b.adjustVariant(this, npc, current) else current }

internal fun WorldSimulation.retargetMonster(npc: MapNpcInstance): MapNpcInstance =
    monsterBehaviors.fold(npc) { current, b -> if (isOn(b)) b.retarget(this, current) else current }

internal fun WorldSimulation.tauntedMonster(npc: MapNpcInstance, taunter: CharacterState): MapNpcInstance =
    monsterBehaviors.fold(npc) { current, b -> if (isOn(b)) b.onTaunted(this, current, taunter) else current }

/** The monster after a hit, as the first behaviour with an opinion records it, or null when none has one. */
internal fun WorldSimulation.monsterHit(npc: MapNpcInstance, attacker: CharacterState, damage: Int): MapNpcInstance? =
    monsterBehaviors.firstNotNullOfOrNull { if (isOn(it)) it.onHit(this, npc, attacker, damage) else null }

internal fun WorldSimulation.afterHealed(healer: CharacterState, healed: CharacterState, amount: Int) {
    for (b in monsterBehaviors) if (isOn(b)) b.afterHeal(this, healer, healed, amount)
}

internal fun WorldSimulation.monsterMayBeElite(def: NpcDefinition): Boolean = monsterBehaviors.all { !isOn(it) || it.mayBeElite(this, def) }

internal fun WorldSimulation.monsterKillLockout(def: NpcDefinition): Long =
    monsterBehaviors.filter { isOn(it) }.maxOfOrNull { it.killLockoutTicks(this, def) } ?: 0L
