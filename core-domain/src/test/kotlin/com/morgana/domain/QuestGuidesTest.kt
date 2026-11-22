package com.morgana.domain

import com.morgana.domain.event.PlayerQuestState
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.event.QuestObjective
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.domain.event.QuestStatus
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNeighborLinks
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.QuestGuides
import com.morgana.domain.map.TileAttribute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Cross-map quest directions: the first step of the route to a target on another map. */
class QuestGuidesTest {
    // 1 (east border) -> 2 -> 3 (a warp tile at 4,4), 4 is far away with nothing leading to it
    private fun map(id: Int, name: String, neighbors: MapNeighborLinks = MapNeighborLinks(), spawns: List<MapNpcSpawnSlot> = emptyList(), warp: Triple<Int, Int, Int>? = null): MapDefinition {
        val cells = MutableList(10 * 10) { MapCell() }
        if (warp != null) cells[4 * 10 + 4] = MapCell(attribute = TileAttribute.Warp(warp.first, warp.second, warp.third))
        return MapDefinition(id = id, name = name, width = 10, height = 10, neighbors = neighbors, npcSpawns = spawns, cells = cells)
    }

    private val maps = listOf(
        map(1, "Town", MapNeighborLinks(eastMapId = 2)),
        map(2, "Road", MapNeighborLinks(westMapId = 1), warp = Triple(3, 1, 1)),
        map(3, "Cave", spawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 7, spawnX = 5, spawnY = 5))),
        map(4, "Island", spawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 8, spawnX = 5, spawnY = 5)))
    ).associateBy { it.id }

    private fun quest(type: QuestObjectiveType, target: Int, id: Int = 1) =
        QuestDefinition(id = id, name = "Q$id", objectives = listOf(QuestObjective(type, target, 1)))

    private fun active(id: Int = 1) = mapOf(id to PlayerQuestState(id, QuestStatus.IN_PROGRESS, listOf(0)))

    @Test
    fun `a target two maps away is reached by the border toward the road`() {
        val guides = QuestGuides.compute(maps, 1, 2, 5, active(), mapOf(1 to quest(QuestObjectiveType.KILL_NPC, 7)))
        val g = guides.single()
        assertEquals(1, g.mapId)
        assertEquals(9, g.tileX, "the east edge of the town")
        assertEquals(5, g.tileY, "the border tile level with the player")
        assertEquals("Cave", g.targetMapName)
        assertEquals("Road", g.viaMapName)
    }

    @Test
    fun `from the road the way to the cave is its warp tile`() {
        val g = QuestGuides.compute(maps, 2, 0, 0, active(), mapOf(1 to quest(QuestObjectiveType.TALK_TO_NPC, 7))).single()
        assertEquals(4 to 4, g.tileX to g.tileY)
        assertEquals("Cave", g.viaMapName)
    }

    @Test
    fun `no guide when the target is on this map, unreachable, or the objective is done`() {
        val defs = mapOf(1 to quest(QuestObjectiveType.KILL_NPC, 7), 2 to quest(QuestObjectiveType.KILL_NPC, 8, id = 2))
        assertTrue(QuestGuides.compute(maps, 3, 5, 5, active(), defs).isEmpty(), "already on the cave map: the client marks the spawn itself")
        assertTrue(QuestGuides.compute(maps, 1, 2, 5, active(2), defs).isEmpty(), "the island cannot be reached from the town")
        val done = mapOf(1 to PlayerQuestState(1, QuestStatus.IN_PROGRESS, listOf(1)))
        assertTrue(QuestGuides.compute(maps, 1, 2, 5, done, defs).isEmpty(), "a finished objective needs no direction")
    }

    @Test
    fun `visit-map objectives are guided too`() {
        val g = QuestGuides.compute(maps, 1, 2, 5, active(), mapOf(1 to quest(QuestObjectiveType.VISIT_MAP, 3))).single()
        assertEquals("Cave", g.targetMapName)
    }
}
