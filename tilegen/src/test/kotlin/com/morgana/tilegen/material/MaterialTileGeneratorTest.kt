package com.morgana.tilegen.material

import com.morgana.tilegen.autotile.AutotileGenerator
import com.morgana.tilegen.autotile.AutotileMode
import com.morgana.tilegen.autotile.TerrainPair
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class MaterialTileGeneratorTest {

    @Test
    fun `tile noise wraps seamlessly at arbitrary periods`() {
        for (period in listOf(16, 32, 64)) {
            for (i in 0 until period) {
                val nH0 = TileNoise.wrappedFbm(0f, i.toFloat(), period = period, seed = 42L)
                val nH1 = TileNoise.wrappedFbm(period.toFloat(), i.toFloat(), period = period, seed = 42L)
                assertTrue(abs(nH0 - nH1) < 0.0001f, "Horizontal mismatch at period=$period, y=$i")

                val v0 = TileNoise.periodicVoronoi(i.toFloat(), 0f, period = period, cells = 4, seed = 77L)
                val v1 = TileNoise.periodicVoronoi(i.toFloat(), period.toFloat(), period = period, cells = 4, seed = 77L)
                assertTrue(abs(v0.first - v1.first) < 0.0001f, "Voronoi mismatch at period=$period, x=$i")
            }
        }
    }

    @Test
    fun `palette ramp samples quantized stops and builds hue-shifted ramps`() {
        val ramp = PaletteRamp.fromBaseColor(PaletteRamp.rgba(70, 140, 80), steps = 5)
        assertEquals(5, ramp.colors.size)
        assertEquals(ramp.shadow, ramp.sample(0f))
        assertEquals(ramp.highlight, ramp.sample(1f))
        assertTrue(luma(ramp.shadow) < luma(ramp.highlight), "Shadow should be darker than highlight")
    }

    @Test
    fun `material generator supports 16, 32, and 64 pixel tiles across all patterns`() {
        val specs = listOf(
            MaterialSpec("grass", MaterialPattern.ORGANIC_FBM, PalettePresets.MEADOW_GRASS),
            MaterialSpec("cobble", MaterialPattern.COBBLESTONE, PalettePresets.SLATE_STONE),
            MaterialSpec("brick", MaterialPattern.BRICK_MASONRY, PalettePresets.TERRACOTTA_BRICK),
            MaterialSpec("planks", MaterialPattern.WOOD_PLANKS, PalettePresets.TIMBER_WOOD),
            MaterialSpec("water", MaterialPattern.WATER_CAUSTICS, PalettePresets.OCEAN_WATER),
            MaterialSpec("hull", MaterialPattern.METAL_PLATING, PalettePresets.VOID_METAL)
        )

        for (size in listOf(16, 32, 64)) {
            val gen = MaterialTileGenerator(seed = 123L, tileSize = size)
            for (spec in specs) {
                val tile = gen.generateTile(spec)
                assertEquals(size, tile.width)
                assertEquals(size, tile.height)
                assertTrue(tile.pixels.all { (it and 0xFF) == 0xFF }, "${spec.name} at $size has transparent pixels")
            }

            val sheet = gen.generateSheet(specs, columns = 3)
            assertEquals(3 * size, sheet.width)
            assertEquals(2 * size, sheet.height)
        }
    }

    @Test
    fun `material generation is deterministic and animated water frames vary`() {
        val gen1 = MaterialTileGenerator(seed = 500L, tileSize = 32)
        val gen2 = MaterialTileGenerator(seed = 500L, tileSize = 32)
        val waterSpec = MaterialSpec("water", MaterialPattern.WATER_CAUSTICS, PalettePresets.OCEAN_WATER)

        val frames1 = gen1.generateAnimatedFrames(waterSpec, frameCount = 4)
        val frames2 = gen2.generateAnimatedFrames(waterSpec, frameCount = 4)

        assertEquals(4, frames1.size)
        for (i in 0 until 4) {
            assertArrayEquals(frames1[i].pixels, frames2[i].pixels)
        }
        val diffs = frames1[0].pixels.indices.count { frames1[0].pixels[it] != frames1[1].pixels[it] }
        assertTrue(diffs > 50, "Expected animation variation between frame 0 and frame 1, got $diffs diffs")
    }

    @Test
    fun `autotile generator scales cleanly to 16x16 and 64x64`() {
        val blob16 = AutotileGenerator(seed = 7L, tileSize = 16)
            .generateTerrainAutotile(TerrainPair.GRASS_ON_DIRT, AutotileMode.BLOB_47)
        assertEquals(16 * 8, blob16.image.width)
        assertEquals(16 * 6, blob16.image.height)
        assertEquals(47, blob16.entries.size)

        val card64 = AutotileGenerator(seed = 7L, tileSize = 64)
            .generateTerrainAutotile(TerrainPair.SAND_ON_WATER, AutotileMode.CARDINAL_16)
        assertEquals(64 * 4, card64.image.width)
        assertEquals(64 * 4, card64.image.height)
        assertEquals(16, card64.entries.size)
    }

    private fun luma(rgba: Int): Double =
        0.299 * ((rgba ushr 24) and 0xFF) +
            0.587 * ((rgba ushr 16) and 0xFF) +
            0.114 * ((rgba ushr 8) and 0xFF)
}
