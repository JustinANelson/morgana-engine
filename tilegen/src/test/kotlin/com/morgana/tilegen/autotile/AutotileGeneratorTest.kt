package com.morgana.tilegen.autotile

import com.morgana.tilegen.ProceduralTilesetGenerator
import com.morgana.tilegen.TileImage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutotileGeneratorTest {

    @Test
    fun `blob bitmask reduces 256 raw masks to 47 unique canonical masks`() {
        assertEquals(47, AutotileBitmask.BLOB_47_MASKS.size)
        assertEquals(47, AutotileBitmask.BLOB_47_MASKS.toSet().size)
        for (raw in 0..255) {
            val idx = AutotileBitmask.blobIndexFor(raw)
            assertTrue(idx in 0 until 47, "Index out of range for rawMask=$raw: $idx")
            assertEquals(AutotileBitmask.canonicalBlobMask(raw), AutotileBitmask.maskForBlobIndex(idx))
        }
    }

    @Test
    fun `all blob and cardinal masks have unique filesystem-safe names`() {
        val blobNames = AutotileBitmask.BLOB_47_MASKS.map {
            AutotileBitmask.describeMask(it, AutotileMode.BLOB_47)
        }
        assertEquals(47, blobNames.toSet().size)
        blobNames.forEach { name ->
            assertTrue(name.matches(Regex("[a-z0-9_]+")), "Unsafe blob name: $name")
        }

        val cardinalNames = AutotileBitmask.CARDINAL_16_MASKS.map {
            AutotileBitmask.describeMask(it, AutotileMode.CARDINAL_16)
        }
        assertEquals(16, cardinalNames.toSet().size)
        cardinalNames.forEach { name ->
            assertTrue(name.matches(Regex("[a-z0-9_]+")), "Unsafe cardinal name: $name")
        }
    }

    @Test
    fun `blob47 and cardinal16 sheets have expected dimensions and metadata`() {
        val gen = AutotileGenerator(seed = 42L)
        val blobSheet = gen.generateTerrainAutotile(TerrainPair.GRASS_ON_DIRT, AutotileMode.BLOB_47)
        assertEquals(256, blobSheet.image.width)
        assertEquals(192, blobSheet.image.height)
        assertEquals(47, blobSheet.entries.size)
        assertEquals(8, blobSheet.columns)
        assertEquals(6, blobSheet.rows)

        val json = blobSheet.toMetadataJson()
        assertTrue(json.contains("\"mode\": \"BLOB_47\""))
        assertTrue(json.contains("\"tileSize\": 32"))
        assertTrue(json.contains("\"name\": \"isolated\""))

        val cardinalSheet = gen.generateTerrainAutotile(TerrainPair.COBBLE_ON_GRASS, AutotileMode.CARDINAL_16)
        assertEquals(128, cardinalSheet.image.width)
        assertEquals(128, cardinalSheet.image.height)
        assertEquals(16, cardinalSheet.entries.size)
    }

    @Test
    fun `full mask matches foreground and isolated mask surrounds center with background`() {
        val fg = TileImage(32, 32).apply {
            for (y in 0 until 32) for (x in 0 until 32) setPixel(x, y, 0x00FF00FF)
        }
        val bg = TileImage(32, 32).apply {
            for (y in 0 until 32) for (x in 0 until 32) setPixel(x, y, 0x0000FFFF)
        }
        val gen = AutotileGenerator(seed = 99L)
        val style = AutotileStyle(margin = 6f, rimWidth = 0f, wobbleAmplitude = 0f, rimColor = null)

        val fullTile = gen.renderAutotileTile(fg, bg, 255, AutotileMode.BLOB_47, style)
        assertArrayEquals(fg.pixels, fullTile.pixels)

        val isolated = gen.renderAutotileTile(fg, bg, 0, AutotileMode.BLOB_47, style)
        assertEquals(0x0000FFFF, isolated.getPixel(0, 0))
        assertEquals(0x0000FFFF, isolated.getPixel(31, 31))
        assertEquals(0x00FF00FF, isolated.getPixel(16, 16))
    }

    @Test
    fun `connected edges stay 100 percent foreground across shared seams`() {
        val fg = TileImage(32, 32).apply {
            for (y in 0 until 32) for (x in 0 until 32) setPixel(x, y, 0x22AA22FF)
        }
        val bg = TileImage(32, 32).apply {
            for (y in 0 until 32) for (x in 0 until 32) setPixel(x, y, 0x885522FF.toInt())
        }
        val gen = AutotileGenerator(seed = 7L)
        val style = AutotileStyle(margin = 5f, rimWidth = 2f, wobbleAmplitude = 3f, rimColor = 0x111111FF)

        // East-West connected pair: left tile has E=true, right tile has W=true (and all other cardinals true)
        val leftMask = AutotileBitmask.blobMask(n = true, ne = true, e = true, se = true, s = true, sw = false, w = false, nw = false)
        val rightMask = AutotileBitmask.blobMask(n = true, ne = false, e = false, se = false, s = true, sw = true, w = true, nw = true)
        val leftTile = gen.renderAutotileTile(fg, bg, leftMask, AutotileMode.BLOB_47, style)
        val rightTile = gen.renderAutotileTile(fg, bg, rightMask, AutotileMode.BLOB_47, style)

        for (y in 0 until 32) {
            assertEquals(0x22AA22FF, leftTile.getPixel(31, y), "Left tile east seam at y=$y should be foreground")
            assertEquals(0x22AA22FF, rightTile.getPixel(0, y), "Right tile west seam at y=$y should be foreground")
        }
    }

    @Test
    fun `all built-in terrain pairs generate deterministically and vary with seed`() {
        for (pair in TerrainPair.entries) {
            val s1 = AutotileGenerator(seed = 101L).generateTerrainAutotile(pair, AutotileMode.CARDINAL_16)
            val s2 = AutotileGenerator(seed = 101L).generateTerrainAutotile(pair, AutotileMode.CARDINAL_16)
            val s3 = AutotileGenerator(seed = 202L).generateTerrainAutotile(pair, AutotileMode.CARDINAL_16)

            assertArrayEquals(s1.image.pixels, s2.image.pixels, "Same seed should match for $pair")
            assertEquals(s1.toMetadataJson(), s2.toMetadataJson())
            val diffs = s1.image.pixels.indices.count { s1.image.pixels[it] != s3.image.pixels[it] }
            assertTrue(diffs > 100, "Expected seed variation for $pair, got $diffs diffs")
        }
    }

    @Test
    fun `entryForMask resolves uncanonical raw masks`() {
        val sheet = AutotileGenerator(seed = 1L).generateTerrainAutotile(TerrainPair.SAND_ON_WATER, AutotileMode.BLOB_47)
        // NE bit set without N or E -> canonicalizes to 0 (isolated)
        val entry = sheet.entryForMask(AutotileBitmask.NE)
        assertEquals(0, entry.mask)
        assertEquals("isolated", entry.name)
    }
}
