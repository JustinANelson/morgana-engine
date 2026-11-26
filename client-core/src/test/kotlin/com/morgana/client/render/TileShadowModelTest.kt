package com.morgana.client.render

import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.TilePlacement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TileShadowModelTest {
    private val tile = TilePlacement(1, 0, 0)

    private fun map(vararg cells: Pair<Pair<Int, Int>, MapCell>): MapDefinition {
        val grid = MutableList(10 * 10) { MapCell() }
        for ((pos, cell) in cells) grid[pos.second * 10 + pos.first] = cell
        return MapDefinition(id = 1, name = "t", width = 10, height = 10, cells = grid)
    }

    private val sky = SkyState(1f, 1f, 1f, 1f, 0f, shadowShear = -1f, shadowLength = 1f, shadowAlpha = 0.3f, isMoon = false)

    @Test
    fun `a blocked mask tile with a crown above it is a base with a stack`() {
        val m = map(
            (3 to 5) to MapCell(mapOf(MapLayer.MASK_1 to tile), TileAttribute.Blocked),
            (3 to 4) to MapCell(mapOf(MapLayer.FRINGE_1 to tile)),
            (3 to 3) to MapCell(mapOf(MapLayer.FRINGE_1 to tile))
        )
        assertTrue(TileShadowModel.isBase(m, 3, 5))
        assertEquals(2, TileShadowModel.stackHeight(m, 3, 5))
    }

    @Test
    fun `ground detail on the mask layer casts nothing`() {
        val m = map((2 to 2) to MapCell(mapOf(MapLayer.MASK_1 to tile)))
        assertFalse(TileShadowModel.isBase(m, 2, 2))
    }

    @Test
    fun `a passable doorway base with a roof above still roots a shadow`() {
        val m = map(
            (4 to 6) to MapCell(mapOf(MapLayer.MASK_1 to tile)),
            (4 to 5) to MapCell(mapOf(MapLayer.ROOF to tile))
        )
        assertTrue(TileShadowModel.isBase(m, 4, 6))
    }

    @Test
    fun `a light source is never a base`() {
        val m = map((1 to 1) to MapCell(mapOf(MapLayer.MASK_1 to tile), TileAttribute.LightSource()))
        assertFalse(TileShadowModel.isBase(m, 1, 1))
    }

    @Test
    fun `stacks stop at the first gap and at the cap`() {
        val tall = (0 until 9).map { (2 to it) to MapCell(mapOf(MapLayer.FRINGE_1 to tile)) }.toTypedArray()
        val m = map(*tall, (2 to 9) to MapCell(mapOf(MapLayer.MASK_1 to tile), TileAttribute.Blocked))
        assertEquals(TileShadowModel.MAX_STACK, TileShadowModel.stackHeight(m, 2, 9))
    }

    @Test
    fun `shadows fall down the screen and lean away from the light`() {
        val p = TileShadowModel.project(32f, sky)
        assertTrue(p[1] < 0f)
        assertTrue(p[0] < 0f) // shear -1 leans west
        assertEquals(0f, TileShadowModel.project(0f, sky)[0] + 0f)
    }
}
