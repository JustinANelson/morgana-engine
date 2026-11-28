package com.morgana.client.render

import com.morgana.domain.entity.MapResourceState
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.TilePlacement
import com.morgana.domain.spatial.TileCoord
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DepletedResourceFootprintTest {

    @Test
    fun `depleted 3x3 tree hides trunk and full canopy without hiding unrelated tiles`() {
        val w = 12
        val h = 12
        val grid = MutableList(w * h) { MapCell() }
        // Stamp a 3x3 tree from tileset 125 at sheet (0, 1) anchored at trunk (5, 6) -> sheet (1, 3)
        grid[6 * w + 5] = MapCell(
            layers = mapOf(MapLayer.MASK_1 to TilePlacement(tilesetId = 125, srcTileX = 1, srcTileY = 3)),
            attribute = TileAttribute.ResourceNode(resourceId = 2)
        )
        for (dy in -2..-1) {
            for (dx in -1..1) {
                val tx = 5 + dx
                val ty = 6 + dy
                grid[ty * w + tx] = MapCell(
                    layers = mapOf(MapLayer.FRINGE_1 to TilePlacement(tilesetId = 125, srcTileX = 1 + dx, srcTileY = 3 + dy))
                )
            }
        }
        // Unrelated prop nearby on a different tileset
        grid[6 * w + 4] = MapCell(
            layers = mapOf(MapLayer.MASK_1 to TilePlacement(tilesetId = 105, srcTileX = 0, srcTileY = 0))
        )
        val map = MapDefinition(id = 1, name = "Forest", width = w, height = h, cells = grid)

        val hidden = HashSet<Long>()
        val coord = TileCoord(5, 6)
        // Intact resource hides nothing
        DepletedResourceFootprint.collectHiddenTiles(
            map,
            mapOf(coord to MapResourceState(mapId = 1, tileX = 5, tileY = 6, resourceId = 2, currentHealth = 10, depleted = false)),
            hidden
        )
        assertTrue(hidden.isEmpty())

        // Depleted resource hides trunk + all 6 canopy tiles
        DepletedResourceFootprint.collectHiddenTiles(
            map,
            mapOf(coord to MapResourceState(mapId = 1, tileX = 5, tileY = 6, resourceId = 2, currentHealth = 0, depleted = true)),
            hidden
        )
        assertTrue(DepletedResourceFootprint.pack(5, 6, MapLayer.MASK_1) in hidden)
        for (dy in -2..-1) {
            for (dx in -1..1) {
                assertTrue(
                    DepletedResourceFootprint.pack(5 + dx, 6 + dy, MapLayer.FRINGE_1) in hidden,
                    "Expected canopy tile (${5 + dx}, ${6 + dy}) to be hidden"
                )
            }
        }
        // Unrelated nearby prop stays visible
        assertFalse(DepletedResourceFootprint.pack(4, 6, MapLayer.MASK_1) in hidden)
    }

    @Test
    fun `depleted 2x2 boulder hides all four tiles`() {
        val w = 10
        val h = 10
        val grid = MutableList(w * h) { MapCell() }
        // 2x2 boulder at sheet (8, 1) anchored at bottom-right (5, 5) -> sheet (9, 2)
        grid[5 * w + 5] = MapCell(
            layers = mapOf(MapLayer.MASK_1 to TilePlacement(tilesetId = 135, srcTileX = 9, srcTileY = 2)),
            attribute = TileAttribute.ResourceNode(resourceId = 6)
        )
        grid[5 * w + 4] = MapCell(
            layers = mapOf(MapLayer.MASK_1 to TilePlacement(tilesetId = 135, srcTileX = 8, srcTileY = 2))
        )
        grid[4 * w + 4] = MapCell(
            layers = mapOf(MapLayer.FRINGE_1 to TilePlacement(tilesetId = 135, srcTileX = 8, srcTileY = 1))
        )
        grid[4 * w + 5] = MapCell(
            layers = mapOf(MapLayer.FRINGE_1 to TilePlacement(tilesetId = 135, srcTileX = 9, srcTileY = 1))
        )
        val map = MapDefinition(id = 2, name = "Quarry", width = w, height = h, cells = grid)

        val hidden = HashSet<Long>()
        DepletedResourceFootprint.collectHiddenTiles(
            map,
            mapOf(TileCoord(5, 5) to MapResourceState(mapId = 2, tileX = 5, tileY = 5, resourceId = 6, currentHealth = 0, depleted = true)),
            hidden
        )
        assertTrue(DepletedResourceFootprint.pack(5, 5, MapLayer.MASK_1) in hidden)
        assertTrue(DepletedResourceFootprint.pack(4, 5, MapLayer.MASK_1) in hidden)
        assertTrue(DepletedResourceFootprint.pack(4, 4, MapLayer.FRINGE_1) in hidden)
        assertTrue(DepletedResourceFootprint.pack(5, 4, MapLayer.FRINGE_1) in hidden)
    }
}
