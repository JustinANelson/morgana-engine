package com.morgana.tilegen

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class ProceduralTilesetGeneratorTest {

    @Test
    fun `generator creates tilesets with 32-pixel aligned dimensions`() {
        val gen = ProceduralTilesetGenerator(seed = 42L)
        val pm3 = gen.generateTileset(columns = 16, rows = 3)
        assertEquals(512, pm3.width)
        assertEquals(96, pm3.height)

        val pm6 = gen.generateTileset(columns = 16, rows = 6)
        assertEquals(512, pm6.width)
        assertEquals(192, pm6.height)
    }

    @Test
    fun `same seed reproduces the same pixels`() {
        val pm1 = ProceduralTilesetGenerator(seed = 12345L).generateTileset(columns = 16, rows = 3)
        val pm2 = ProceduralTilesetGenerator(seed = 12345L).generateTileset(columns = 16, rows = 3)
        assertArrayEquals(pm1.pixels, pm2.pixels)
    }

    @Test
    fun `different seeds produce varied textures`() {
        val pm1 = ProceduralTilesetGenerator(seed = 100L).generateTileset(columns = 16, rows = 3)
        val pm2 = ProceduralTilesetGenerator(seed = 999L).generateTileset(columns = 16, rows = 3)

        var differences = 0
        for (y in 0 until 32) for (x in 0 until 32) {
            if (pm1.getPixel(x, y) != pm2.getPixel(x, y)) differences++
        }
        assertTrue(differences > 100, "Expected seed variation in grass tile, but found only $differences differences")
    }

    @Test
    fun `terrain tiles in the starter sheet are fully opaque`() {
        // Row 0: grass, crypt floor, cobble road, water - ground tiles must cover every pixel.
        val sheet = ProceduralTilesetGenerator().generateTileset(columns = 16, rows = 3)
        for (col in 0..3) {
            val tile = sheet.crop(col * 32, 0, 32, 32)
            assertTrue(tile.pixels.all { (it and 0xFF) == 0xFF }, "Tile $col has transparent pixels")
        }
    }

    @Test
    fun `wrapped value noise wraps seamlessly at period boundary`() {
        val gen = ProceduralTilesetGenerator(seed = 555L)
        for (y in 0..31) {
            val n0 = gen.wrappedNoise2D(0f, y.toFloat(), period = 32)
            val n32 = gen.wrappedNoise2D(32f, y.toFloat(), period = 32)
            assertTrue(abs(n0 - n32) < 0.0001f, "Horizontal seamless mismatch at y=$y: $n0 vs $n32")
        }
        for (x in 0..31) {
            val n0 = gen.wrappedNoise2D(x.toFloat(), 0f, period = 32)
            val n32 = gen.wrappedNoise2D(x.toFloat(), 32f, period = 32)
            assertTrue(abs(n0 - n32) < 0.0001f, "Vertical seamless mismatch at x=$x: $n0 vs $n32")
        }
    }

    @Test
    fun `periodic voronoi wraps seamlessly at period boundary`() {
        val gen = ProceduralTilesetGenerator(seed = 777L)
        for (y in 0..31) {
            val v0 = gen.periodicVoronoi(0f, y.toFloat(), cells = 4)
            val v32 = gen.periodicVoronoi(32f, y.toFloat(), cells = 4)
            assertTrue(abs(v0.first - v32.first) < 0.0001f, "Voronoi seamless mismatch at y=$y")
            assertTrue(abs(v0.second - v32.second) < 0.0001f, "Voronoi 2nd seamless mismatch at y=$y")
        }
    }
}
