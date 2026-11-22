package com.morgana.tilegen

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThemedTilesetGeneratorTest {

    @Test
    fun `farm, dungeon and spaceship themes generate 32x32 tiles and bundle to 512x64`() {
        val gen = ThemedTilesetGenerator(seed = 1337L)
        for (theme in listOf(TilesetTheme.FARM, TilesetTheme.DUNGEON, TilesetTheme.SPACESHIP)) {
            val tiles = gen.generateTiles(theme)
            assertEquals(32, tiles.size, "Expected 32 tiles for $theme")
            tiles.forEach { named ->
                assertEquals(32, named.image.width, "${named.name} width")
                assertEquals(32, named.image.height, "${named.name} height")
            }

            val sheet = gen.generateTileset(theme)
            assertEquals(512, sheet.width)
            assertEquals(64, sheet.height)
        }
    }

    @Test
    fun `every theme yields uniquely named non-empty tiles`() {
        val gen = ThemedTilesetGenerator()
        for (theme in TilesetTheme.entries) {
            val tiles = gen.generateTiles(theme)
            assertEquals(tiles.size, tiles.map { it.name }.toSet().size, "Duplicate tile names in $theme")
            tiles.forEach { named ->
                assertTrue(named.name.matches(Regex("[a-z0-9_]+")), "${named.name} is not filesystem-safe")
                assertTrue(named.image.pixels.any { (it and 0xFF) != 0 }, "${named.name} in $theme is fully transparent")
            }
        }
    }

    @Test
    fun `dungeon tiles read darker than farm tiles`() {
        // A coarse check that each theme keeps its mood: dungeon stone and shadow vs sunlit farmland.
        val gen = ThemedTilesetGenerator()
        val farm = averageLuma(gen.generateTileset(TilesetTheme.FARM))
        val dungeon = averageLuma(gen.generateTileset(TilesetTheme.DUNGEON))
        assertTrue(dungeon < farm, "Expected dungeon ($dungeon) to be darker than farm ($farm)")
    }

    @Test
    fun `spaceship tiles read as cool metal rather than warm earth`() {
        // Hull steel, cyan lights and space should outweigh the amber and red accents.
        val sheet = ThemedTilesetGenerator().generateTileset(TilesetTheme.SPACESHIP)
        val farm = ThemedTilesetGenerator().generateTileset(TilesetTheme.FARM)
        assertTrue(blueMinusRed(sheet) > 5.0, "Spaceship should lean blue, got ${blueMinusRed(sheet)}")
        assertTrue(blueMinusRed(sheet) > blueMinusRed(farm), "Spaceship should be cooler than farm")
    }

    @Test
    fun `classic theme slices the starter sheet into 48 tiles`() {
        val tiles = ThemedTilesetGenerator(seed = 7L).generateTiles(TilesetTheme.CLASSIC)
        assertEquals(48, tiles.size)
        val sheet = ProceduralTilesetGenerator(seed = 7L).generateTileset(columns = 16, rows = 3)
        assertArrayEquals(sheet.crop(32 * 5, 32, 32, 32).pixels, tiles[21].image.pixels)
    }

    @Test
    fun `themed generation is reproducible for a seed and varies across seeds`() {
        val pm1 = ThemedTilesetGenerator(seed = 42L).generateTileset(TilesetTheme.DUNGEON)
        val pm2 = ThemedTilesetGenerator(seed = 42L).generateTileset(TilesetTheme.DUNGEON)
        val pm3 = ThemedTilesetGenerator(seed = 999L).generateTileset(TilesetTheme.DUNGEON)

        assertArrayEquals(pm1.pixels, pm2.pixels)
        val diffs = pm1.pixels.indices.count { pm1.pixels[it] != pm3.pixels[it] }
        assertTrue(diffs > 100, "Expected seed variation in dungeon tileset, found $diffs differences")
    }

    private fun blueMinusRed(image: TileImage): Double {
        val opaque = image.pixels.filter { (it and 0xFF) != 0 }
        return opaque.sumOf { p -> (((p ushr 8) and 0xFF) - ((p ushr 24) and 0xFF)).toDouble() } / opaque.size
    }

    private fun averageLuma(image: TileImage): Double {
        val opaque = image.pixels.filter { (it and 0xFF) != 0 }
        return opaque.sumOf { p ->
            0.299 * ((p ushr 24) and 0xFF) + 0.587 * ((p ushr 16) and 0xFF) + 0.114 * ((p ushr 8) and 0xFF)
        } / opaque.size
    }
}
