package com.morgana.domain

import com.morgana.domain.explore.WorldMapTerrain
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.TilePlacement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorldMapTerrainTest {
    private val water = MapCell(layers = mapOf(MapLayer.GROUND to TilePlacement(srcTileX = 3)))
    private val wall = MapCell(attribute = TileAttribute.Blocked)
    private val warp = MapCell(attribute = TileAttribute.Warp(targetMapId = 2, targetX = 1, targetY = 1))

    private fun map(w: Int, h: Int, cell: (Int, Int) -> MapCell) =
        MapDefinition(id = 1, name = "T", width = w, height = h, cells = List(w * h) { cell(it % w, it / w) })

    @Test
    fun `a small map is one character per tile in row order, read from the real tiles`() {
        val t = WorldMapTerrain.thumbnail(map(5, 5) { x, y ->
            when {
                x == 0 && y == 0 -> water
                x == 1 && y == 0 -> wall
                x == 4 && y == 4 -> warp
                else -> MapCell()
            }
        })
        assertEquals(1, t.scale)
        assertEquals(5, t.cols)
        assertEquals(5, t.rows)
        assertEquals("wbggg" + "ggggg".repeat(3) + "ggggp", t.cells)
    }

    @Test
    fun `a big map is shrunk to at most 64 blocks a side and still covers all of it`() {
        val t = WorldMapTerrain.thumbnail(map(140, 100) { _, _ -> MapCell() })
        assertEquals(3, t.scale)
        assertEquals(47, t.cols) // ceil(140 / 3)
        assertEquals(34, t.rows) // ceil(100 / 3)
        assertEquals(t.cols * t.rows, t.cells.length)
    }

    @Test
    fun `the most common ground wins a block but a warp always shows`() {
        // 128 wide gives scale 2, so each 2x2 block is summarised: the first block is 3 water and 1 grass.
        val m = map(128, 6) { x, y ->
            when {
                x < 2 && y < 2 && !(x == 1 && y == 1) -> water
                x == 2 && y == 0 -> warp
                else -> MapCell()
            }
        }
        val t = WorldMapTerrain.thumbnail(m)
        assertEquals(2, t.scale)
        assertEquals(WorldMapTerrain.WATER, t.at(0, 0))
        assertEquals(WorldMapTerrain.WARP, t.at(1, 0))
        assertEquals(WorldMapTerrain.GRASS, t.at(2, 0))
    }
}
