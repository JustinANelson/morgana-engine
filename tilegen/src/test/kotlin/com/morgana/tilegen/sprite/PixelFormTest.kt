package com.morgana.tilegen.sprite

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PixelStyle
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class PixelFormTest {
    private fun lum(rgba: Int): Int =
        ((rgba ushr 24) and 0xFF) * 3 + ((rgba ushr 16) and 0xFF) * 4 + ((rgba ushr 8) and 0xFF)

    private fun colors(img: TileImage): Set<Int> {
        val out = HashSet<Int>()
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getPixel(x, y)
            if ((p and 0xFF) != 0) out += p
        }
        return out
    }

    @Test
    fun `a dome uses a handful of flat bands and is lit from the upper left`() {
        val img = TileImage(16, 16)
        PixelForm.dome(img, 8f, 8f, 6f, 6f, PalettePresets.DARK_IRON)
        assertTrue(colors(img).size <= 6, "dome should be banded, got ${colors(img).size} colours")
        assertTrue(lum(img.getPixel(5, 5)) > lum(img.getPixel(11, 11)), "upper left must be lighter than lower right")
    }

    @Test
    fun `a column is lit on the left, shaded on the right and darker underneath`() {
        val img = TileImage(8, 10)
        PixelForm.column(img, 1, 1, 4, 6, PalettePresets.WARM_TIMBER, 0.58f)
        assertTrue(lum(img.getPixel(1, 3)) > lum(img.getPixel(4, 3)))
        assertTrue(lum(img.getPixel(2, 3)) > lum(img.getPixel(2, 6)))
        assertEquals(0, img.getPixel(1, 1) and 0xFF, "rounded corner pixel stays transparent")
    }

    @Test
    fun `a blade is wider than one pixel and ends in a bright tip`() {
        val img = TileImage(16, 16)
        PixelForm.blade(img, 4, 14, 8, 2, PalettePresets.CREAM_MARBLE)
        val row = (7..9).sumOf { y -> (0 until 16).count { x -> (img.getPixel(x, y) and 0xFF) != 0 } }
        assertTrue(row >= 5, "blade should be wider than a 1 px line, found $row pixels over three rows")
    }

    @Test
    fun `morgana paper-doll frames stay deterministic and keep a small colour count per layer`() {
        val gen = PaperDollSpriteGenerator(seed = 7L)
        val loadout = PaperDollLoadout.forJob(PaperDollJob.KNIGHT)
        val (a, _) = gen.generateLayeredFrame(loadout, Direction8.S, PaperDollAction.WALK, 1, PixelStyle.MORGANA)
        val (b, _) = gen.generateLayeredFrame(loadout, Direction8.S, PaperDollAction.WALK, 1, PixelStyle.MORGANA)
        for ((slot, img) in a) {
            assertTrue(img.pixels.contentEquals(b.getValue(slot).pixels), "layer $slot differs between runs")
        }
        val head = a.getValue(PaperDollSlot.BODY)
        assertTrue(colors(head).size <= 40, "body layer should use few tones, got ${colors(head).size}")
    }
}
