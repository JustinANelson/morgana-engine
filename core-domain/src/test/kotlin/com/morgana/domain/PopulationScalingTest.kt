package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.PopulationScalingDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.scaledResourceRespawn
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A quiet map refills only part of its monsters and regrows nodes slowly; a busy one is fully supplied. */
class PopulationScalingTest {
    private val scaling = PopulationScalingDefinition(playersForFull = 4, floorPercent = 25)

    private fun sim(p: PopulationScalingDefinition? = scaling): WorldSimulation {
        val s = WorldSimulation(SeededRandomSource(8L), game = CompiledGame.compile(MorganaClassic.definition.copy(populationScaling = p)))
        s.npcs[1] = NpcDefinition(id = 1, name = "Wolf", behavior = NpcBehavior.PASSIVE)
        s.npcs[2] = NpcDefinition(id = 2, name = "Stag", behavior = NpcBehavior.PASSIVE)
        s.registerMap(MapDefinition(id = 1, name = "Hub", width = 30, height = 30))
        s.registerMap(MapDefinition(id = 2, name = "Wilds", width = 30, height = 30, npcSpawns = (0..3).map { MapNpcSpawnSlot(it, if (it == 3) 2 else 1, 4 + it * 3, 4) }))
        return s
    }

    private fun WorldSimulation.joinMap2(count: Int) = repeat(count) {
        spawnOrUpdatePlayer(CharacterState(id = "p$it", accountId = "a$it", name = "P$it", position = WorldPosition.fromTile(2, 20 + it, 20, Direction.DOWN)))
    }

    private fun WorldSimulation.killAll(mapId: Int) {
        val life = game.lifeVitalId
        val list = mapNpcs.getValue(mapId)
        for (i in list.indices) list[i] = list[i].copy(vitals = list[i].vitals + (life to list[i].vitals.getValue(life).copy(cur = 0)), respawnAtTick = 1L)
    }

    private fun WorldSimulation.aliveSlots(mapId: Int) = mapNpcs.getValue(mapId).filter { it.isAlive }.map { it.spawnSlotIndex }.sorted()

    @Test
    fun `density rises in a straight line from the floor to full`() {
        assertEquals(25, scaling.densityPercent(0))
        assertEquals(43, scaling.densityPercent(1))
        assertEquals(62, scaling.densityPercent(2))
        assertEquals(100, scaling.densityPercent(4))
        assertEquals(100, scaling.densityPercent(40))
        assertEquals(100, PopulationScalingDefinition(floorPercent = 100).densityPercent(0))
    }

    @Test
    fun `an empty map refills a quarter of its slots and each arrival brings more back`() {
        val s = sim()
        s.joinMap2(0)
        s.killAll(2)
        repeat(3) { s.stepTick() }
        assertEquals(listOf(0), s.aliveSlots(2), "25% of four slots is one")

        s.joinMap2(2) // 62%: three of four slots
        repeat(3) { s.stepTick() }
        assertEquals(listOf(0, 1, 2), s.aliveSlots(2))

        s.joinMap2(4) // full
        repeat(3) { s.stepTick() }
        assertEquals(listOf(0, 1, 2, 3), s.aliveSlots(2))
    }

    @Test
    fun `exempt monsters and maps are never thinned, and without the section nothing changes`() {
        val exempt = sim(scaling.copy(exemptNpcIds = listOf(2)))
        exempt.killAll(2)
        repeat(3) { exempt.stepTick() }
        assertEquals(listOf(0, 3), exempt.aliveSlots(2), "the stag in slot 3 is exempt")

        val none = sim(null)
        none.killAll(2)
        repeat(3) { none.stepTick() }
        assertEquals(listOf(0, 1, 2, 3), none.aliveSlots(2))
        assertEquals(100, none.populationPercent(2))

        assertEquals(100, sim(scaling.copy(exemptMapIds = listOf(2))).populationPercent(2))
    }

    @Test
    fun `a node regrows more slowly on a quiet map`() {
        val s = sim()
        assertEquals(400, s.scaledResourceRespawn(2, 100), "empty: a quarter of the supply, four times the wait")
        s.joinMap2(4)
        assertEquals(100, s.scaledResourceRespawn(2, 100))
        assertEquals(100, sim(scaling.copy(scaleResources = false)).scaledResourceRespawn(2, 100))
    }

    @Test
    fun `the section is validated`() {
        fun pointers(p: PopulationScalingDefinition) = GameDefinitionValidator.validate(MorganaClassic.definition.copy(populationScaling = p)).map { it.pointer }
        assertTrue(pointers(scaling).isEmpty())
        assertTrue("/populationScaling/playersForFull" in pointers(scaling.copy(playersForFull = 0)))
        assertTrue("/populationScaling/floorPercent" in pointers(scaling.copy(floorPercent = 0)))
        assertTrue("/populationScaling/floorPercent" in pointers(scaling.copy(floorPercent = 101)))
        assertTrue("/populationScaling/exemptNpcIds" in pointers(scaling.copy(exemptNpcIds = listOf(0))))
    }
}
