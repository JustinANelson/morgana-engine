package com.morgana.tilegen.material

import com.morgana.tilegen.TileImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReliefShadingTest {

    @Test
    fun `heightfield dome shading is deterministic and brighter on top-left than bottom-right`() {
        val hf1 = Heightfield(32, 32)
        hf1.addDome(16f, 16f, 12f, 12f, peak = 1f)
        val img1 = TileImage(32, 32)
        hf1.shadeInto(img1, PalettePresets.DUNGEON_SCULPTED_SLATE)

        val hf2 = Heightfield(32, 32)
        hf2.addDome(16f, 16f, 12f, 12f, peak = 1f)
        val img2 = TileImage(32, 32)
        hf2.shadeInto(img2, PalettePresets.DUNGEON_SCULPTED_SLATE)

        assertTrue(img1.pixels.contentEquals(img2.pixels), "Heightfield shading must be deterministic")

        val topLeftLuma = luma(img1.getPixel(11, 11))
        val botRightLuma = luma(img1.getPixel(21, 21))
        assertTrue(topLeftLuma > botRightLuma, "Top-left ($topLeftLuma) should be lit brighter than bottom-right ($botRightLuma)")
    }

    @Test
    fun `crisp outline wraps opaque silhouette with ink border`() {
        val src = TileImage(16, 16)
        PixelCanvas(src).fillRect(5, 5, 10, 10, PalettePresets.DUNGEON_BONE.colors[4])

        val outlined = PixelOutline.applyCrispOutline(src, PixelOutline.DUNGEON_INK)
        assertEquals(0, outlined.getPixel(3, 8) and 0xFF, "2 px away should stay transparent")
        assertEquals(255, outlined.getPixel(4, 8) and 0xFF, "1 px left of silhouette should be outlined")
        assertEquals(255, outlined.getPixel(11, 8) and 0xFF, "1 px right of silhouette should be outlined")
        assertTrue(
            luma(outlined.getPixel(11, 8)) < luma(src.getPixel(8, 8)),
            "Outline should be darker than the interior fill"
        )
    }

    @Test
    fun `radial glow brightens dark stone around center without clipping outside radius`() {
        val img = TileImage(48, 48)
        val c = PixelCanvas(img)
        val darkStone = PalettePresets.DUNGEON_COBBLE_WALL.colors[1]
        c.fillRect(0, 0, 47, 47, darkStone)

        c.drawGlow(24f, 24f, 14f, PalettePresets.DUNGEON_SPECTRAL_FLAME.colors[3], intensity = 0.65f)

        val centerLuma = luma(img.getPixel(24, 24))
        val cornerLuma = luma(img.getPixel(0, 0))
        assertTrue(centerLuma > cornerLuma + 40f, "Glow center ($centerLuma) should be much brighter than corner ($cornerLuma)")
        assertEquals(darkStone, img.getPixel(0, 0), "Pixels outside glow radius should be unchanged")
    }

    @Test
    fun `flame draws bright core and non-empty pixels`() {
        val img = TileImage(32, 32)
        PixelCanvas(img).drawFlame(16f, 24f, 5f, 14f, PalettePresets.DUNGEON_SPECTRAL_FLAME, seed = 42L)
        val core = img.getPixel(16, 20)
        assertEquals(255, core and 0xFF, "Flame core should be opaque")
        assertTrue(luma(core) > 150f, "Flame core should be luminous")
    }

    private fun luma(rgba: Int): Float {
        val r = (rgba ushr 24) and 0xFF
        val g = (rgba ushr 16) and 0xFF
        val b = (rgba ushr 8) and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
}
