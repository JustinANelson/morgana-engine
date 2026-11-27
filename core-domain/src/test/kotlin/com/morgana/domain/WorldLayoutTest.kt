package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNeighborLinks
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.TileOffset
import com.morgana.domain.map.WorldLayout
import com.morgana.domain.map.WorldLayoutDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The seamless world: maps of different sizes placed in one grid are walked between without a warp, from any edge or corner. */
class WorldLayoutTest {

    private fun map(id: Int, w: Int, h: Int, x: Int?, y: Int?, blocked: Set<Pair<Int, Int>> = emptySet(), neighbors: MapNeighborLinks = MapNeighborLinks()) = MapDefinition(
        id = id, name = "M$id", width = w, height = h, worldX = x, worldY = y, neighbors = neighbors,
        cells = List(w * h) { i -> if ((i % w) to (i / w) in blocked) MapCell(attribute = TileAttribute.Blocked) else MapCell() }
    )

    /** A is 10 x 10 at the origin; B is 6 x 5 just east of it, three tiles down; C is 10 x 6 directly south of A. */
    private val a = map(1, 10, 10, 0, 0)
    private val b = map(2, 6, 5, 10, 3)
    private val c = map(3, 10, 6, 0, 10)

    @Test
    fun `stepping off a map reaches the map that covers that world tile, at the same place`() {
        val layout = WorldLayout(listOf(a, b, c))
        val east = layout.crossing(a, 10, 4)
        assertNotNull(east)
        assertEquals(2, east!!.map.id)
        assertEquals(0, east.tileX)
        assertEquals(1, east.tileY, "row 4 of A is row 1 of B, which starts three rows down")
        assertEquals(-10, east.shiftTilesX)
        assertEquals(-3, east.shiftTilesY)
        assertEquals(3, layout.crossing(a, 4, 10)!!.map.id)
        assertNull(layout.crossing(a, 10, 1), "no map east of A's first rows: the edge of the world")
        assertNull(layout.crossing(a, -1, 4))
        assertEquals(1, layout.crossing(b, -1, 2)!!.map.id, "and back again")
        assertEquals(9, layout.crossing(b, -1, 2)!!.tileX)
        assertEquals(5, layout.crossing(b, -1, 2)!!.tileY)
    }

    @Test
    fun `neighbours are found by distance including corners, and the links follow the geometry`() {
        val layout = WorldLayout(listOf(a, b, c))
        assertEquals(setOf(2, 3), layout.around(1, 0).map { it.first.id }.toSet())
        assertEquals(TileOffset(10, 3), layout.around(1, 0).first { it.first.id == 2 }.second)
        assertEquals(setOf(1, 2), layout.around(3, 4).map { it.first.id }.toSet(), "B touches C's corner region within four tiles")
        assertEquals(MapNeighborLinks(eastMapId = 2, southMapId = 3), layout.linksOf(a))
        assertEquals(MapNeighborLinks(westMapId = 1), layout.linksOf(b))
        assertEquals(MapNeighborLinks(northMapId = 1), layout.linksOf(c))
    }

    @Test
    fun `overlapping placements and unplaced or instanced maps are told apart`() {
        assertEquals(listOf(1 to 4), WorldLayout(listOf(a, map(4, 5, 5, 8, 8))).overlaps())
        assertTrue(WorldLayout(listOf(a, map(5, 5, 5, null, null), a.copy(id = 6, instanced = true, worldX = 20, worldY = 0))).around(1, 100).isEmpty())
    }

    @Test
    fun `a border that is open on one side and blocked on the other is reported`() {
        val wall = map(7, 6, 5, 10, 3, blocked = setOf(0 to 1))
        assertEquals(listOf(9 to 4), WorldLayout(listOf(a, wall)).mismatchedBorderTiles(a, wall).map { it.x to it.y })
        assertTrue(WorldLayout(listOf(a, b)).mismatchedBorderTiles(a, b).isEmpty())
    }

    private fun sim(vararg maps: MapDefinition, on: Boolean = true): WorldSimulation {
        val def = if (on) MorganaClassic.COMPILED.definition.copy(worldLayout = WorldLayoutDefinition()) else MorganaClassic.COMPILED.definition
        val sim = WorldSimulation(SeededRandomSource(3L), game = CompiledGame.compile(def))
        maps.forEach { sim.registerMap(it) }
        sim.spawnOrUpdatePlayer(CharacterState(id = "p", accountId = "a", name = "P", position = WorldPosition.fromTile(1, 9, 4, Direction.RIGHT)))
        return sim
    }

    @Test
    fun `registering placed maps derives their links, and a game without worldLayout ignores the placement`() {
        assertEquals(2, sim(a, b, c).maps.getValue(1).neighbors.eastMapId)
        assertEquals(0, sim(a, b, c, on = false).maps.getValue(1).neighbors.eastMapId)
    }

    @Test
    fun `a player walks from one map into a differently sized one with no warp`() {
        val s = sim(a, b, c)
        val events = s.movePlayer("p", Direction.RIGHT)
        val moved = events.filterIsInstance<WorldSimulationEvent.PlayerMoved>().single()
        assertEquals(1, moved.previousMapId)
        assertEquals(2, moved.currentMapId)
        val pos = s.players.getValue("p").position
        assertEquals(2, pos.mapId)
        assertEquals(0, pos.tileX)
        assertEquals(1, pos.tileY)
        // ...and straight back.
        s.stepTick(); s.stepTick(); s.stepTick(); s.stepTick(); s.stepTick(); s.stepTick()
        s.movePlayer("p", Direction.LEFT)
        assertEquals(1, s.players.getValue("p").position.mapId)
        assertEquals(9, s.players.getValue("p").position.tileX)
        assertEquals(4, s.players.getValue("p").position.tileY)
    }

    @Test
    fun `the edge of the world and a blocked tile on the far side stop the player`() {
        val edge = sim(a, b, c)
        edge.spawnOrUpdatePlayer(edge.players.getValue("p").copy(position = WorldPosition.fromTile(1, 9, 1, Direction.RIGHT)))
        assertTrue(edge.movePlayer("p", Direction.RIGHT).isEmpty())
        assertEquals(1, edge.players.getValue("p").position.mapId)
        val walled = sim(a, map(2, 6, 5, 10, 3, blocked = setOf(0 to 1)), c)
        assertTrue(walled.movePlayer("p", Direction.RIGHT).isEmpty())
        assertEquals(1, walled.players.getValue("p").position.mapId)
    }

    @Test
    fun `free pixel movement keeps its sub-tile position across the border`() {
        val s = sim(a, b, c)
        s.modules = com.morgana.domain.sim.ModuleFlags.ALL_ENABLED
        s.spawnOrUpdatePlayer(s.players.getValue("p").copy(position = WorldPosition(1, 9, 4, 296f, 128f, Direction.RIGHT)))
        s.movePlayer("p", Direction.RIGHT, com.morgana.domain.spatial.MovementMode.SUB_TILE, 8f, 0f)
        val pos = s.players.getValue("p").position
        assertEquals(2, pos.mapId)
        assertEquals(0, pos.tileX)
        assertEquals(1, pos.tileY)
        assertEquals(-16f, pos.pixelX, 0.001f, "304 px on A is -16 px on B, which starts ten tiles east")
        assertEquals(32f, pos.pixelY, 0.001f, "128 px on A is 32 px on B, which starts three tiles down")
    }
}
