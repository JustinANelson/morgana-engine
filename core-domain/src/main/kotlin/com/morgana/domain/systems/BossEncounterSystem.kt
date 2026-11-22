package com.morgana.domain.systems

import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.MonsterBehavior
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.afterStruck
import com.morgana.domain.sim.handlePlayerDeath
import com.morgana.domain.sim.tellPlayer

import com.morgana.domain.combat.actionDefence
import com.morgana.domain.content.BossAbility
import com.morgana.domain.content.BossZoneShape
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.BossZone
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.game.EnemyVariantDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.stats.ActiveStatusEffect
import com.morgana.domain.stats.VitalState
import kotlin.math.abs
import kotlin.math.max

/**
 * Boss encounters as a system (see `BossEncounter`): scripted phases, telegraphed ground attacks and called adds, a boss is
 * never also an elite, a kill can lock its killers out for a while, and the adds go when the fight is over.
 */
object BossEncounterSystem : MonsterBehavior, GameSystem {
    override val key: String = OptionalModule.BOSS_ENCOUNTERS.configKey
    override fun beforeMap(sim: WorldSimulation, mapId: Int, monsters: List<MapNpcInstance>, out: MutableList<WorldSimulationEvent>) {
        // Adds called by a boss go when the fight is over: its boss is dead, or has reset and has nobody to fight.
        if (monsters.none { it.spawnSlotIndex < 0 && it.ownerId == null }) return
        val bosses = monsters.filter { it.isAlive && sim.npcs[it.npcId]?.encounter != null }
        if (bosses.none { it.targetEntityId != null || it.phaseIndex > 0 }) sim.clearBossAdds(mapId, out)
    }

    override fun step(
        sim: WorldSimulation, npc: MapNpcInstance, def: NpcDefinition, map: MapDefinition, out: MutableList<WorldSimulationEvent>
    ): MapNpcInstance = if (def.encounter != null) sim.tickBossEncounter(npc, def, map, out) else npc

    override fun adjustVariant(sim: WorldSimulation, npc: MapNpcInstance, variant: EnemyVariantDefinition?): EnemyVariantDefinition? {
        val phase = sim.bossPhase(npc) ?: return variant
        if (phase.attackPercent == 100 && phase.moveSpeedPercent == 100) return variant
        val base = variant ?: EnemyVariantDefinition(id = "phase", label = "", levelBonus = 0)
        return base.copy(attackPercent = base.attackPercent * phase.attackPercent / 100, moveSpeedPercent = base.moveSpeedPercent * phase.moveSpeedPercent / 100)
    }

    override fun mayBeElite(sim: WorldSimulation, def: NpcDefinition): Boolean = def.encounter == null

    override fun killLockoutTicks(sim: WorldSimulation, def: NpcDefinition): Long = def.encounter?.lockoutTicks ?: 0L
}

/** The phase [npc] is in right now, or null when its definition has no encounter. */
internal fun WorldSimulation.bossPhase(npc: MapNpcInstance) =
    npcs[npc.npcId]?.encounter?.phases?.let { it.getOrNull(npc.phaseIndex.coerceIn(0, max(0, it.size - 1))) }

/**
 * One tick of a boss's scripted fight (see `BossEncounter`): resolves a danger zone whose time is up, moves to the
 * next phase when the boss's life has dropped far enough (announcing it and calling adds), and, while the boss has a
 * target, starts the next telegraphed ground attack that is ready. Returns the boss as it should be stored.
 */
internal fun WorldSimulation.tickBossEncounter(
    boss: MapNpcInstance,
    def: NpcDefinition,
    map: MapDefinition,
    out: MutableList<WorldSimulationEvent>
): MapNpcInstance {
    val encounter = def.encounter ?: return boss
    if (encounter.phases.isEmpty()) return boss
    var npc = boss

    // 1. A danger zone whose wind-up is over.
    val zone = npc.zone
    if (zone != null && currentTick >= zone.resolveTick) {
        resolveBossZone(npc, def, zone, map, out)
        npc = npc.copy(zone = null)
    }

    // 2. A new phase.
    val life = npc.vitals[game.lifeVitalId]
    val percent = if (life == null || life.max <= 0) 100 else (life.cur * 100L / life.max).toInt()
    val reached = encounter.phases.indexOfLast { percent <= it.fromLifePercent }.coerceAtLeast(0)
    if (reached > npc.phaseIndex) {
        npc = npc.copy(phaseIndex = reached)
        val phase = encounter.phases[reached]
        if (phase.announce.isNotBlank()) {
            out.add(
                WorldSimulationEvent.ChatEmitted(
                    channel = "SYSTEM", mapId = map.id, senderId = npc.instanceId, senderName = def.name,
                    message = phase.announce, showOverheadBubble = false, colorHex = "#F87171", notice = "WARNING"
                )
            )
        }
        for (addId in phase.summonNpcIds) summonAdd(npc, addId, map, out)
        out.add(WorldSimulationEvent.NpcUpdated(npc))
    }

    // 3. A new telegraphed attack, while someone is being fought.
    val target = npc.targetEntityId?.let { players[it] }
    if (npc.zone == null && target != null && target.isAlive(game.lifeVitalId) && target.position.mapId == map.id) {
        val phase = encounter.phases[npc.phaseIndex.coerceIn(0, encounter.phases.size - 1)]
        val ready = phase.abilities.firstOrNull { currentTick >= (npc.abilityReadyTicks[it.spellId] ?: 0L) && spells[it.spellId] != null }
        if (ready != null) {
            npc = startBossAbility(npc, def, ready, target.position.tileX, target.position.tileY, map, out)
        }
    }
    return npc
}

private fun WorldSimulation.startBossAbility(
    boss: MapNpcInstance,
    def: NpcDefinition,
    ability: BossAbility,
    targetX: Int,
    targetY: Int,
    map: MapDefinition,
    out: MutableList<WorldSimulationEvent>
): MapNpcInstance {
    val cx = if (ability.shape == BossZoneShape.AROUND_SELF) boss.tileX else targetX
    val cy = if (ability.shape == BossZoneShape.AROUND_SELF) boss.tileY else targetY
    val label = ability.warning.ifBlank { spells[ability.spellId]?.name.orEmpty() }
    out.add(WorldSimulationEvent.BossTelegraph(map.id, cx, cy, ability.radiusTiles, ability.windUpTicks, label))
    out.add(
        WorldSimulationEvent.FloatingTextEmitted(
            mapId = map.id, tileX = boss.tileX, tileY = boss.tileY, text = label.ifBlank { "..." }, category = "CAST", colorHex = "#F87171"
        )
    )
    return boss.copy(
        zone = BossZone(cx, cy, ability.radiusTiles, currentTick + ability.windUpTicks.coerceAtLeast(1), ability.spellId),
        abilityReadyTicks = boss.abilityReadyTicks + (ability.spellId to currentTick + ability.cooldownTicks.coerceAtLeast(1))
    )
}

/** Hits every living player standing in [zone] with its spell's damage and status effect. */
private fun WorldSimulation.resolveBossZone(
    boss: MapNpcInstance,
    def: NpcDefinition,
    zone: BossZone,
    map: MapDefinition,
    out: MutableList<WorldSimulationEvent>
) {
    val spell = spells[zone.spellId] ?: return
    val lifeId = game.lifeVitalId
    val attackPercent = bossPhase(boss)?.attackPercent ?: 100
    val npcAttrs = game.definition.attributes.associate { a -> a.id to (def.attributes[a.id] ?: a.base) }
    val victims = players.values.filter {
        it.position.mapId == map.id && it.isAlive(lifeId) &&
            max(abs(it.position.tileX - zone.centerX), abs(it.position.tileY - zone.centerY)) <= zone.radius
    }
    for (player in victims) {
        val base = game.spellOutput(
            caster = npcAttrs, target = player.effectiveAttributes(game, items),
            basePower = spell.basePower.toLong(), scales = spell.scales, heals = false
        ).toInt().coerceAtLeast(1)
        val dmg = actionDefence(player, zone.centerX, zone.centerY, (base.toLong() * (boss.scale?.attackPercent ?: 100) / 100 * attackPercent / 100).toInt().coerceAtLeast(1), out)
        val cur = player.vitals[lifeId]?.cur ?: 0
        val left = (cur - dmg).coerceAtLeast(0)
        var hit = player.copy(vitals = player.vitals + (lifeId to (player.vitals[lifeId]?.copy(cur = left) ?: VitalState(left, left))))
        out.add(
            WorldSimulationEvent.FloatingTextEmitted(
                mapId = map.id, tileX = hit.position.tileX, tileY = hit.position.tileY, text = "-$dmg", category = "DAMAGE", colorHex = "#EF4444"
            )
        )
        if (spell.statusEffectType != null && spell.statusDurationTicks > 0) {
            hit = hit.copy(
                statusEffects = hit.statusEffects + ActiveStatusEffect(
                    id = nextId("status"), type = spell.statusEffectType, sourceEntityId = boss.instanceId,
                    magnitude = spell.statusMagnitude, remainingTicks = spell.statusDurationTicks, tickInterval = 20
                )
            )
        }
        if (spell.animationId > 0) {
            out.add(WorldSimulationEvent.AnimationTriggered(map.id, hit.position.tileX, hit.position.tileY, spell.animationId, casterEntityId = boss.instanceId, spellId = spell.id))
        }
        hit = afterStruck(hit, out)
        if (!hit.isAlive(lifeId)) hit = handlePlayerDeath(hit, map, out)
        players[hit.id] = hit
        out.add(WorldSimulationEvent.CharacterUpdated(hit))
    }
}

/** Places a new monster of type [npcId] on a free tile next to [boss]. It is an add: it never respawns and goes when the fight ends. */
private fun WorldSimulation.summonAdd(boss: MapNpcInstance, npcId: Int, map: MapDefinition, out: MutableList<WorldSimulationEvent>) {
    val def = npcs[npcId] ?: return
    val list = mapNpcs.getOrPut(map.id) { mutableListOf() }
    val taken = list.filter { it.isAlive }.map { it.tileX to it.tileY }.toSet()
    val spot = (1..3).asSequence().flatMap { r ->
        (-r..r).asSequence().flatMap { dx -> (-r..r).asSequence().map { dy -> boss.tileX + dx to boss.tileY + dy } }
    }.firstOrNull { (x, y) ->
        map.isInBounds(x, y) && (x to y) !in taken && map.getCell(x, y)?.attribute !is TileAttribute.Blocked
    } ?: return
    val add = MapNpcInstance(
        instanceId = nextId("add"), spawnSlotIndex = -1, npcId = npcId, mapId = map.id,
        tileX = spot.first, tileY = spot.second, homeX = spot.first, homeY = spot.second,
        vitals = spawnVitalsForNpc(def, null, boss.scale), scale = boss.scale,
        targetEntityId = boss.targetEntityId
    )
    list.add(add)
    out.add(WorldSimulationEvent.NpcUpdated(add))
}

/** Removes the adds on [mapId] (monsters with no spawn slot), when their boss resets or dies. */
internal fun WorldSimulation.clearBossAdds(mapId: Int, out: MutableList<WorldSimulationEvent>) {
    val list = mapNpcs[mapId] ?: return
    val gone = list.filter { it.spawnSlotIndex < 0 && it.ownerId == null }
    if (gone.isEmpty()) return
    list.removeAll(gone.toSet())
    for (add in gone) {
        val dead = add.copy(vitals = add.vitals + (game.lifeVitalId to VitalState(0, add.vitals[game.lifeVitalId]?.max ?: 1)), respawnAtTick = 0L)
        out.add(WorldSimulationEvent.NpcUpdated(dead))
    }
}
