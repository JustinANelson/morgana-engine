package com.morgana.persistence

import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.MapNeighborLinks
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.TilePlacement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Phase3TiledAndInMemoryTest {

    @Test
    fun `in memory document collection supports concurrent CRUD operations`() {
        val collection = InMemoryDocumentCollection<String, String>()
        assertEquals(0, collection.count())

        collection.save("item-1", "Wooden Sword")
        collection.save("item-2", "Iron Shield")
        assertEquals(2, collection.count())
        assertEquals("Wooden Sword", collection.findById("item-1"))

        val all = collection.findAll()
        assertEquals(2, all.size)
        assertTrue(all.contains("Iron Shield"))

        assertTrue(collection.deleteById("item-1"))
        assertFalse(collection.deleteById("item-1"))
        assertEquals(1, collection.count())

        collection.clear()
        assertEquals(0, collection.count())
    }

    @Test
    fun `tiled map converter round-trips Morgana map to standard tiled json and back`() {
        val width = 10
        val height = 8
        val totalCells = width * height
        val cellList = MutableList(totalCells) { MapCell() }

        // Set ground tile (grass) everywhere
        for (i in 0 until totalCells) {
            cellList[i] = MapCell(
                layers = mapOf(MapLayer.GROUND to TilePlacement(tilesetId = 1, srcTileX = 0, srcTileY = 0))
            )
        }

        // Add stone wall on cell (2, 2) on MASK_1 layer and Blocked attribute
        val idxWall = 2 * width + 2
        cellList[idxWall] = MapCell(
            layers = mapOf(
                MapLayer.GROUND to TilePlacement(1, 0, 0),
                MapLayer.MASK_1 to TilePlacement(1, 1, 0)
            ),
            attribute = TileAttribute.Blocked
        )

        // Add warp tile on cell (5, 5)
        val idxWarp = 5 * width + 5
        cellList[idxWarp] = MapCell(
            layers = mapOf(MapLayer.GROUND to TilePlacement(1, 0, 0)),
            attribute = TileAttribute.Warp(targetMapId = 2, targetX = 1, targetY = 1)
        )

        val originalMap = MapDefinition(
            id = 42,
            name = "Verdant Glade",
            revision = 3,
            width = width,
            height = height,
            moral = MapMoral.ARENA,
            bgmTrackId = "arena_theme",
            ambientLightHex = "#E0E0FF",
            neighbors = MapNeighborLinks(northMapId = 10, southMapId = 11, eastMapId = 12, westMapId = 13),
            cells = cellList
        )

        // Export to Tiled JSON
        val jsonStr = TiledMapConverter.exportToTiledJson(originalMap)
        assertTrue(jsonStr.contains("Verdant Glade"))
        assertTrue(jsonStr.contains("GROUND"))
        assertTrue(jsonStr.contains("COLLISION"))

        // Import back from Tiled JSON
        val imported = TiledMapConverter.importFromTiledJson(jsonStr)
        assertEquals(42, imported.id)
        assertEquals("Verdant Glade", imported.name)
        assertEquals(width, imported.width)
        assertEquals(height, imported.height)
        assertEquals(MapMoral.ARENA, imported.moral)
        assertEquals("arena_theme", imported.bgmTrackId)
        assertEquals(10, imported.neighbors.northMapId)

        // Verify wall cell
        val wallCell = imported.getCell(2, 2)
        assertNotNull(wallCell)
        assertEquals(0, wallCell!!.layers[MapLayer.GROUND]?.srcTileX)
        assertEquals(1, wallCell.layers[MapLayer.MASK_1]?.srcTileX)
        assertEquals(TileAttribute.Blocked, wallCell.attribute)

        // Verify warp cell
        val warpCell = imported.getCell(5, 5)
        assertNotNull(warpCell)
        assertTrue(warpCell!!.attribute is TileAttribute.Warp)
    }
}

