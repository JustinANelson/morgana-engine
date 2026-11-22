package com.morgana.client

import com.morgana.client.input.TapMoveController
import com.morgana.client.input.TapPathfinder
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.TileCoord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TapMoveTest {

    private val w = 8
    private val h = 8

    private fun open(blocked: Set<TileCoord> = emptySet()): (TileCoord, TileCoord, Direction) -> Boolean =
        { _, to, _ -> to !in blocked }

    private fun walk(start: TileCoord, dirs: List<Direction>) = dirs.fold(start) { c, d -> c.step(d) }

    @Test
    fun `straight route on open ground`() {
        val path = TapPathfinder.find(TileCoord(0, 0), TileCoord(3, 0), w, h, open())!!
        assertEquals(List(3) { Direction.RIGHT }, path.steps)
        assertFalse(path.facesGoal)
    }

    @Test
    fun `routes around a wall`() {
        val wall = (0..5).map { TileCoord(2, it) }.toSet()
        val path = TapPathfinder.find(TileCoord(0, 0), TileCoord(4, 0), w, h, open(wall))!!
        assertEquals(TileCoord(4, 0), walk(TileCoord(0, 0), path.steps))
        assertTrue(path.steps.size > 4)
        var c = TileCoord(0, 0)
        path.steps.forEach { c = c.step(it); assertFalse(c in wall) }
    }

    @Test
    fun `unreachable goal gives no route`() {
        val ring = listOf(Direction.UP, Direction.DOWN, Direction.LEFT, Direction.RIGHT).map { TileCoord(5, 5).step(it) }.toSet()
        assertNull(TapPathfinder.find(TileCoord(0, 0), TileCoord(5, 5), w, h, open(ring)))
    }

    @Test
    fun `tapping an NPC walks next to it then turns to face it`() {
        val npc = TileCoord(4, 0)
        val path = TapPathfinder.find(TileCoord(0, 0), npc, w, h, open(setOf(npc)))!!
        assertTrue(path.facesGoal)
        assertEquals(Direction.RIGHT, path.steps.last())
        assertEquals(npc, walk(TileCoord(0, 0), path.steps))
        assertEquals(TileCoord(3, 0), walk(TileCoord(0, 0), path.steps.dropLast(1)))
    }

    @Test
    fun `out of bounds and same tile`() {
        assertNull(TapPathfinder.find(TileCoord(0, 0), TileCoord(20, 0), w, h, open()))
        assertEquals(emptyList<Direction>(), TapPathfinder.find(TileCoord(1, 1), TileCoord(1, 1), w, h, open())!!.steps)
    }

    @Test
    fun `route is deterministic`() {
        val a = TapPathfinder.find(TileCoord(0, 0), TileCoord(5, 5), w, h, open())
        val b = TapPathfinder.find(TileCoord(0, 0), TileCoord(5, 5), w, h, open())
        assertEquals(a, b)
    }

    private fun TapMoveController.step(tile: TileCoord, map: Int = 1, canStep: (TileCoord, TileCoord, Direction) -> Boolean = open()) =
        nextDirection(map, tile, tile.x * 32f, tile.y * 32f, 32, false, w, h, canStep)

    @Test
    fun `controller walks to the goal and finishes`() {
        val c = TapMoveController()
        c.setGoal(1, TileCoord(2, 0))
        var tile = TileCoord(0, 0)
        var guard = 0
        while (c.isActive && guard++ < 10) {
            val d = c.step(tile) ?: break
            tile = tile.step(d)
        }
        assertEquals(TileCoord(2, 0), tile)
        assertNull(c.step(tile))
        assertFalse(c.isActive)
    }

    @Test
    fun `controller gives up when stuck`() {
        val c = TapMoveController()
        c.setGoal(1, TileCoord(3, 0))
        // The character never moves, as if blocked by something the client cannot see.
        repeat(40) { c.step(TileCoord(0, 0)) }
        assertFalse(c.isActive)
    }

    @Test
    fun `controller cancels on map change and on request`() {
        val c = TapMoveController()
        c.setGoal(1, TileCoord(3, 0))
        assertNull(c.step(TileCoord(0, 0), map = 2))
        assertFalse(c.isActive)
        c.setGoal(1, TileCoord(3, 0))
        assertNotNull(c.step(TileCoord(0, 0)))
        c.cancel()
        assertNull(c.step(TileCoord(1, 0)))
    }

    @Test
    fun `sub tile mode lines up with the tile before finishing`() {
        val c = TapMoveController()
        c.setGoal(1, TileCoord(2, 0))
        // Standing on the goal tile but 16 px short of its origin: keep walking right.
        val d = c.nextDirection(1, TileCoord(2, 0), 48f, 0f, 32, true, w, h, open())
        assertEquals(Direction.RIGHT, d)
        // Lined up: done.
        assertNull(c.nextDirection(1, TileCoord(2, 0), 64f, 0f, 32, true, w, h, open()))
        assertFalse(c.isActive)
    }
}
