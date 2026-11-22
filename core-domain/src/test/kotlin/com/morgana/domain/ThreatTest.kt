package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.NpcCombatDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.systems.addHealThreat
import com.morgana.domain.sim.applyDamageToNpc
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.ActiveStatusEffect
import com.morgana.domain.stats.StatusEffectType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The threat table: a monster follows whoever has done the most, not whoever hit it last. */
class ThreatTest {
    private fun world(npcRules: NpcCombatDefinition): WorldSimulation {
        val base = MorganaClassic.definition
        val sim = WorldSimulation(SeededRandomSource(1L), game = CompiledGame.compile(base.copy(combat = base.combat.copy(npcs = npcRules))))
        sim.npcs[10] = NpcDefinition(
            id = 10, name = "Ogre", behavior = NpcBehavior.ATTACK_ON_SIGHT, aggroRangeTiles = 8, vitals = mapOf("hp" to 100_000),
            attributes = mapOf("str" to 5, "def" to 5, "agi" to 5, "vit" to 10), attackDamage = 1
        )
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

    @Test
    fun `without threat the monster turns on whoever hit it last`() {
        val sim = world(NpcCombatDefinition())
        sim.hit("tank", 500)
        sim.hit("dps", 5)
        assertEquals("dps", sim.ogre().targetEntityId)
        assertTrue(sim.ogre().threat.isEmpty(), "no table is kept")
    }

    @Test
    fun `with threat the monster keeps its target until someone out-threatens them by the margin`() {
        val sim = world(NpcCombatDefinition(threatSwitchPercent = 110))
        sim.hit("tank", 100)
        sim.hit("dps", 105)
        assertEquals("tank", sim.ogre().targetEntityId, "105 is not 10% more than 100")
        sim.hit("dps", 20)
        assertEquals("dps", sim.ogre().targetEntityId, "125 is")
        assertEquals(125, sim.ogre().threat.getValue("dps"))
    }

    @Test
    fun `a taunt lifts the taunter above the top threat and the monster turns on them`() {
        val sim = world(NpcCombatDefinition(threatSwitchPercent = 110, tauntThreatPercent = 120))
        sim.hit("dps", 300)
        sim.hit("tank", 10)
        assertEquals("dps", sim.ogre().targetEntityId)
        val i = 0
        val taunted = sim.ogre().copy(statusEffects = listOf(ActiveStatusEffect(id = "t1", type = StatusEffectType.TAUNT, sourceEntityId = "tank", magnitude = 0, remainingTicks = 100, tickInterval = 20)))
        sim.mapNpcs.getValue(1)[i] = taunted
        sim.stepTick()
        assertEquals("tank", sim.ogre().targetEntityId)
        assertEquals(360, sim.ogre().threat.getValue("tank"), "120% of the highest threat, 300")
        // the taunt ends; the lifted threat keeps the tank in front
        sim.mapNpcs.getValue(1)[i] = sim.ogre().copy(statusEffects = emptyList())
        sim.hit("dps", 20)
        assertEquals("tank", sim.ogre().targetEntityId, "320 is not 10% more than 360")
    }

    @Test
    fun `healing an ally the monster is fighting adds threat for the healer`() {
        val sim = world(NpcCombatDefinition(threatSwitchPercent = 110, healThreatPercent = 50))
        sim.hit("tank", 100)
        sim.addHealThreat(sim.players.getValue("healer"), sim.players.getValue("tank"), 80)
        assertEquals(40, sim.ogre().threat.getValue("healer"))
        sim.addHealThreat(sim.players.getValue("healer"), sim.players.getValue("dps"), 80)
        assertEquals(40, sim.ogre().threat.getValue("healer"), "healing someone it is not fighting adds nothing")
    }

    @Test
    fun `a dead or departed player drops out of the table`() {
        val sim = world(NpcCombatDefinition(threatSwitchPercent = 110))
        sim.hit("tank", 100)
        sim.hit("dps", 130)
        assertEquals("dps", sim.ogre().targetEntityId)
        sim.removePlayer("dps")
        sim.stepTick()
        assertEquals("tank", sim.ogre().targetEntityId, "the monster goes back to the player still there")
    }
}
