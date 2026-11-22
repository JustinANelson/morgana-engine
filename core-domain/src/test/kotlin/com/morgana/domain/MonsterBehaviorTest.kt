package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.EnemyVariantDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.NpcCombatDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.sim.MonsterBehavior
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.adjustedVariant
import com.morgana.domain.sim.afterHealed
import com.morgana.domain.sim.applyDamageToNpc
import com.morgana.domain.sim.monsterKillLockout
import com.morgana.domain.sim.monsterMayBeElite
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.systems.BossEncounterSystem
import com.morgana.domain.systems.GuildWarSystem
import com.morgana.domain.systems.ScriptedSystem
import com.morgana.domain.systems.ThreatSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The monster-behaviour seam (ADR 0018): boss encounters, threat and Guild War poles are installed behaviours, and a game's own
 * behaviour can add to them. These tests act as a game's own system would; the boss, threat and Guild War tests cover the built-ins.
 */
class MonsterBehaviorTest {
    private val ogreDef = NpcDefinition(
        id = 10, name = "Ogre", behavior = NpcBehavior.ATTACK_ON_SIGHT, aggroRangeTiles = 8, vitals = mapOf("hp" to 100_000),
        attributes = mapOf("str" to 5, "def" to 5, "agi" to 5, "vit" to 10), attackDamage = 1
    )

    private fun world(npcRules: NpcCombatDefinition = NpcCombatDefinition()): WorldSimulation {
        val base = MorganaClassic.definition
        val sim = WorldSimulation(SeededRandomSource(1L), game = CompiledGame.compile(base.copy(combat = base.combat.copy(npcs = npcRules))))
        sim.npcs[10] = ogreDef
        sim.registerMap(MapDefinition(id = 1, name = "Field", width = 30, height = 30, npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 10, spawnX = 10, spawnY = 10))))
        for ((id, x) in listOf("tank" to 10, "dps" to 11, "healer" to 12)) {
            sim.spawnOrUpdatePlayer(
                CharacterState(id = id, accountId = "a-$id", name = id, attributes = mapOf("str" to 10, "def" to 10, "agi" to 10, "vit" to 10), position = WorldPosition.fromTile(1, x, 13, Direction.UP))
                    .recalculateVitals(sim.game, sim.items, refill = true)
            )
        }
        return sim
    }

    private fun WorldSimulation.ogre() = mapNpcs.getValue(1).single()
    private fun WorldSimulation.hit(id: String, damage: Int) = applyDamageToNpc(players.getValue(id), ogre(), damage, isCrit = false, out = mutableListOf())
    private fun WorldSimulation.ticks(n: Int) = repeat(n) { stepTick() }

    @Test
    fun `guild war, threat and boss encounters are the built in behaviours, then the Lua plugins, in install order`() {
        assertEquals(listOf(GuildWarSystem, ThreatSystem, BossEncounterSystem, ScriptedSystem), world().monsterBehaviors)
    }

    @Test
    fun `a behaviour that holds a monster still stops it acting at all`() {
        val free = world()
        free.ticks(40)
        assertTrue(free.ogre().targetEntityId != null, "an ogre in range hunts someone by default")

        val held = world()
        held.install(object : MonsterBehavior {
            override fun holdsStill(sim: WorldSimulation, npc: MapNpcInstance, def: NpcDefinition) = def.id == 10
        })
        held.ticks(40)
        assertNull(held.ogre().targetEntityId, "it never noticed anyone")
    }

    @Test
    fun `a behaviour steps living monsters every tick and may change them`() {
        val sim = world()
        var steps = 0
        sim.install(object : MonsterBehavior {
            override fun step(sim: WorldSimulation, npc: MapNpcInstance, def: NpcDefinition, map: MapDefinition, out: MutableList<WorldSimulationEvent>): MapNpcInstance {
                steps++
                return if (npc.phaseIndex == 0) npc.copy(phaseIndex = 3) else npc
            }
        })
        sim.ticks(5)
        assertEquals(5, steps, "once a tick for the one living monster")
        assertEquals(3, sim.ogre().phaseIndex, "and what it returned was stored")
    }

    @Test
    fun `a behaviour can reconsider who the monster fights`() {
        val sim = world()
        sim.install(object : MonsterBehavior {
            override fun retarget(sim: WorldSimulation, npc: MapNpcInstance) =
                if (npc.targetEntityId != null && npc.targetEntityId != "healer") npc.copy(targetEntityId = "healer") else npc
        })
        sim.ticks(40)
        assertEquals("healer", sim.ogre().targetEntityId, "it hunts the healer instead of whoever it noticed")
    }

    @Test
    fun `a behaviour can record a hit, the first opinion wins and with none the monster turns on the attacker`() {
        val plain = world()
        plain.hit("tank", 50)
        plain.hit("dps", 5)
        assertEquals("dps", plain.ogre().targetEntityId, "no opinion: the last attacker")

        val firstHitter = object : MonsterBehavior {
            override fun onHit(sim: WorldSimulation, npc: MapNpcInstance, attacker: CharacterState, damage: Int) =
                npc.copy(targetEntityId = npc.targetEntityId ?: attacker.id)
        }
        val sticky = world().also { it.install(firstHitter) }
        sticky.hit("tank", 50)
        sticky.hit("dps", 5)
        assertEquals("tank", sticky.ogre().targetEntityId, "the game's behaviour keeps the first hitter")

        val threatened = world(NpcCombatDefinition(threatSwitchPercent = 110)).also { it.install(firstHitter) }
        threatened.hit("tank", 100)
        threatened.hit("dps", 200)
        assertEquals("dps", threatened.ogre().targetEntityId, "with the threat table on, it was installed first and has the opinion")
    }

    @Test
    fun `behaviours fold into a monster's variant one after another`() {
        val sim = world()
        sim.install(object : MonsterBehavior {
            override fun adjustVariant(sim: WorldSimulation, npc: MapNpcInstance, variant: EnemyVariantDefinition?) =
                (variant ?: EnemyVariantDefinition(id = "x", label = "", levelBonus = 0)).let { it.copy(attackPercent = it.attackPercent * 3) }
        })
        sim.install(object : MonsterBehavior {
            override fun adjustVariant(sim: WorldSimulation, npc: MapNpcInstance, variant: EnemyVariantDefinition?) =
                variant?.let { it.copy(attackPercent = it.attackPercent * 2) }
        })
        assertEquals(600, sim.adjustedVariant(sim.ogre(), sim.npcVariant(sim.ogre()))?.attackPercent, "100 x 3 x 2")
    }

    @Test
    fun `one behaviour can forbid elites and the longest kill lockout counts`() {
        val sim = world()
        assertTrue(sim.monsterMayBeElite(ogreDef))
        assertEquals(0L, sim.monsterKillLockout(ogreDef))
        sim.install(object : MonsterBehavior {
            override fun mayBeElite(sim: WorldSimulation, def: NpcDefinition) = false
            override fun killLockoutTicks(sim: WorldSimulation, def: NpcDefinition) = 500L
        })
        sim.install(object : MonsterBehavior {
            override fun killLockoutTicks(sim: WorldSimulation, def: NpcDefinition) = 900L
        })
        assertFalse(sim.monsterMayBeElite(ogreDef))
        assertEquals(900L, sim.monsterKillLockout(ogreDef))
    }

    @Test
    fun `behaviours hear about healing`() {
        val sim = world()
        val heard = mutableListOf<Triple<String, String, Int>>()
        sim.install(object : MonsterBehavior {
            override fun afterHeal(sim: WorldSimulation, healer: CharacterState, healed: CharacterState, amount: Int) {
                heard += Triple(healer.id, healed.id, amount)
            }
        })
        sim.afterHealed(sim.players.getValue("healer"), sim.players.getValue("tank"), 40)
        assertEquals(listOf(Triple("healer", "tank", 40)), heard)
    }

    @Test
    fun `without the built in behaviours monsters still hunt and fight`() {
        val sim = world()
        sim.monsterBehaviors.clear()
        sim.ticks(40)
        assertTrue(sim.ogre().targetEntityId != null, "the kernel's own sight, hunting and striking do not depend on them")
    }
}
