package com.morgana.domain

import com.morgana.domain.explore.Exploration
import com.morgana.domain.explore.ExploredMap
import com.morgana.domain.explore.GridPos
import com.morgana.domain.explore.MapNode
import com.morgana.domain.explore.WorldMapLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExplorationTest {
    @Test
    fun `a map never visited has nothing explored`() {
        assertFalse(Exploration.isTileExplored(null, 50, 3, 3))
        assertEquals(0, Exploration.percent(null, 50, 50))
    }

    @Test
    fun `revealing marks the chunks around the player and nothing far away`() {
        val seen = Exploration.reveal(null, 50, 50, 25, 25)!!
        assertTrue(Exploration.isTileExplored(seen, 50, 25, 25))
        assertTrue(Exploration.isTileExplored(seen, 50, 30, 25), "5 tiles away is within sight")
        assertTrue(Exploration.isTileExplored(seen, 50, 25, 18), "7 tiles up is the edge of sight")
        assertFalse(Exploration.isTileExplored(seen, 50, 25, 5))
        assertFalse(Exploration.isTileExplored(seen, 50, 0, 0))
    }

    @Test
    fun `revealing the same place again changes nothing, and moving reveals more`() {
        val first = Exploration.reveal(null, 50, 50, 10, 10)!!
        assertNull(Exploration.reveal(first, 50, 50, 10, 10))
        assertNull(Exploration.reveal(first, 50, 50, 11, 10), "one step inside the same chunks")
        val more = Exploration.reveal(first, 50, 50, 30, 10)!!
        assertTrue(Exploration.isTileExplored(more, 50, 10, 10), "what was seen stays seen")
        assertTrue(Exploration.isTileExplored(more, 50, 30, 10))
        assertTrue(Exploration.exploredChunks(more) > Exploration.exploredChunks(first))
    }

    @Test
    fun `edges and tiny maps are handled`() {
        val corner = Exploration.reveal(null, 50, 50, 0, 0)!!
        assertTrue(Exploration.isTileExplored(corner, 50, 0, 0))
        val tiny = Exploration.reveal(null, 3, 2, 1, 1)!!
        assertEquals(100, Exploration.percent(tiny, 3, 2))
        assertNull(Exploration.reveal(null, 0, 0, 0, 0))
        assertFalse(Exploration.isTileExplored(tiny, 3, 99, 99))
        assertFalse(Exploration.isTileExplored(tiny, 3, -1, -1))
    }

    @Test
    fun `percent counts seen chunks and works across word boundaries`() {
        val everything = Exploration.reveal(null, 40, 40, 20, 20, radius = 60)!!
        assertEquals(100, Exploration.percent(everything, 40, 40))
        val big = Exploration.reveal(null, 200, 200, 100, 100, radius = 300)!!
        assertEquals(100, Exploration.percent(big, 200, 200))
        assertEquals(Exploration.chunkCount(200, 200), Exploration.exploredChunks(big))
        assertEquals(ExploredMap(emptyList()), ExploredMap())
    }

    @Test
    fun `a straight road lays out in a row`() {
        val nodes = listOf(MapNode(1, east = 2), MapNode(2, west = 1, east = 3), MapNode(3, west = 2))
        assertEquals(mapOf(1 to GridPos(0, 0), 2 to GridPos(1, 0), 3 to GridPos(2, 0)), WorldMapLayout.layout(nodes))
    }

    @Test
    fun `layout follows all four directions and is shifted to start at the origin`() {
        val nodes = listOf(MapNode(1, north = 2, west = 3), MapNode(2, south = 1), MapNode(3, east = 1))
        val pos = WorldMapLayout.layout(nodes)
        assertEquals(GridPos(1, 1), pos[1])
        assertEquals(GridPos(1, 0), pos[2])
        assertEquals(GridPos(0, 1), pos[3])
    }

    @Test
    fun `links to unknown maps are ignored and unlinked maps form separate groups side by side`() {
        val nodes = listOf(MapNode(1, east = 99), MapNode(5), MapNode(6, east = 7), MapNode(7))
        val pos = WorldMapLayout.layout(nodes)
        assertEquals(setOf(1, 5, 6, 7), pos.keys)
        assertEquals(4, pos.values.toSet().size, "no two maps share a cell")
        assertEquals(GridPos(0, 0), pos[1])
        assertTrue(pos.getValue(5).x > pos.getValue(1).x)
        assertEquals(pos.getValue(6).x + 1, pos.getValue(7).x)
        assertEquals(pos.getValue(6).y, pos.getValue(7).y)
    }

    @Test
    fun `inconsistent links never put two maps in one cell`() {
        val nodes = listOf(MapNode(1, east = 2, south = 3), MapNode(2, west = 1), MapNode(3, north = 1, east = 4), MapNode(4, west = 3, north = 2))
        val pos = WorldMapLayout.layout(nodes)
        assertEquals(4, pos.values.toSet().size)
        assertNotNull(pos[4])
    }

    @Test
    fun `layout is deterministic`() {
        val nodes = listOf(MapNode(2, west = 1), MapNode(1, east = 2, south = 3), MapNode(3, north = 1))
        assertEquals(WorldMapLayout.layout(nodes), WorldMapLayout.layout(nodes))
    }
}
