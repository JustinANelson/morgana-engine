package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.isStandable
import com.morgana.domain.sim.settlePlayersOn
import com.morgana.domain.sim.unloadContentMap
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A map changing under a running world: monsters stay, people are moved off new walls, deleted maps go. */
class MapLifecycleTest {
    private fun map(id: Int, spawns: List<MapNpcSpawnSlot> = emptyList()) = MapDefinition(id = id, name = "Map $id", width = 20, height = 20, npcSpawns = spawns)

    private fun sim(): WorldSimulation {
        val s = WorldSimulation(SeededRandomSource(5L), game = CompiledGame.compile(MorganaClassic.definition))
        s.npcs[1] = NpcDefinition(id = 1, name = "Wolf", behavior = NpcBehavior.PASSIVE)
        s.registerMap(map(1, listOf(MapNpcSpawnSlot(0, 1, 5, 5), MapNpcSpawnSlot(1, 1, 8, 8))))
        s.registerMap(map(2))
        s.spawnOrUpdatePlayer(CharacterState(id = "p", accountId = "a", name = "Pat", position = WorldPosition.fromTile(1, 10, 10, Direction.DOWN)))
        return s
    }

    @Test
    fun `swapping a map keeps the monsters whose spawn slot did not change and respawns the ones that did`() {
        val s = sim()
        val hurt = s.mapNpcs.getValue(1).first { it.spawnSlotIndex == 0 }
        val life = s.game.lifeVitalId
        s.mapNpcs.getValue(1)[s.mapNpcs.getValue(1).indexOf(hurt)] = hurt.copy(vitals = hurt.vitals + (life to hurt.vitals.getValue(life).copy(cur = 1)))

        // Slot 0 is unchanged; slot 1 moved to another tile.
        s.registerMap(map(1, listOf(MapNpcSpawnSlot(0, 1, 5, 5), MapNpcSpawnSlot(1, 1, 12, 12))))
        val after = s.mapNpcs.getValue(1)
        assertEquals(1, after.first { it.spawnSlotIndex == 0 }.vitals.getValue(life).cur, "the wounded wolf is still wounded")
        assertEquals(12, after.first { it.spawnSlotIndex == 1 }.homeX, "the moved slot has a new wolf")
        assertEquals(2, after.size)
    }

    @Test
    fun `a player on a tile that became a wall is moved to the nearest open tile`() {
        val s = sim()
        assertTrue(s.isStandable(1, 10, 10))
        s.registerMap(map(1).withCell(10, 10, MapCell(attribute = TileAttribute.Blocked)))
        assertFalse(s.isStandable(1, 10, 10))
        val events = s.settlePlayersOn(1)
        val p = s.players.getValue("p").position
        assertTrue(s.isStandable(1, p.tileX, p.tileY))
        assertEquals(1, maxOf(kotlin.math.abs(p.tileX - 10), kotlin.math.abs(p.tileY - 10)), "one tile away")
        assertTrue(events.any { it is WorldSimulationEvent.PlayerMoved })
        assertTrue(s.settlePlayersOn(1).isEmpty(), "nobody left to move")
    }

    @Test
    fun `a removed map takes its players back to the start and its contents with it`() {
        val s = sim()
        s.spawnOrUpdatePlayer(s.players.getValue("p").copy(position = WorldPosition.fromTile(2, 3, 3, Direction.DOWN)))
        val out = mutableListOf<WorldSimulationEvent>()
        assertTrue(s.unloadContentMap(2, out))
        assertNull(s.maps[2])
        assertEquals(1, s.players.getValue("p").position.mapId, "back on the start map")
        assertTrue(out.any { it is WorldSimulationEvent.PlayerMoved })
        assertFalse(s.unloadContentMap(2, out), "already gone")
        assertFalse(s.unloadContentMap(1, out), "the class start map is never unloaded")
    }
}
