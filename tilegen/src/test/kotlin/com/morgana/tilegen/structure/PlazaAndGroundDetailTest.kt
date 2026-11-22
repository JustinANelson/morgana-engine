package com.morgana.tilegen.structure

import com.morgana.tilegen.TileImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlazaAndGroundDetailTest {
    private fun solid(img: TileImage, x0: Int, y0: Int, w: Int, h: Int) =
        (y0 until y0 + h).sumOf { y -> (x0 until x0 + w).count { x -> (img.getPixel(x, y) and 0xFF) >= 160 } }

    @Test
    fun `plaza props have their tile size, solid base rows and are deterministic`() {
        for (kind in PlazaKind.entries) {
            val a = PlazaGenerator(1337L).generate(kind)
            val b = PlazaGenerator(1337L).generate(kind)
            assertEquals(kind.widthTiles * 32, a.image.width)
            assertEquals(kind.heightTiles * 32, a.image.height)
            assertTrue(a.image.pixels.contentEquals(b.image.pixels), "${kind.name} is deterministic")
            val blockedPixels = solid(a.image, 0, kind.blockedFromRow * 32, a.image.width, a.image.height - kind.blockedFromRow * 32)
            assertTrue(blockedPixels > 100, "${kind.name} has a base to block, had $blockedPixels solid pixels")
        }
    }

    @Test
    fun `the fountain and well have a top that goes behind characters`() {
        for (kind in listOf(PlazaKind.FOUNTAIN, PlazaKind.WELL, PlazaKind.LAMP_POST)) {
            val g = PlazaGenerator(1337L).generate(kind)
            assertTrue(solid(g.image, 0, 0, g.image.width, 32) > 20, "${kind.name} has fringe pixels in its top row")
        }
    }

    @Test
    fun `plaza sheet places every kind inside 7 x 4 tiles`() {
        val (sheet, at) = PlazaGenerator(1337L).sheet()
        assertEquals(224, sheet.width)
        assertEquals(128, sheet.height)
        for ((kind, pos) in at) {
            assertTrue(pos.first + kind.widthTiles <= 7 && pos.second + kind.heightTiles <= 4, "${kind.name} fits")
            assertTrue(solid(sheet, pos.first * 32, pos.second * 32, kind.widthTiles * 32, kind.heightTiles * 32) > 100)
        }
    }

    @Test
    fun `ground decals are sparse, deterministic and the spills hug their own edge`() {
        val tiles = GroundDetailGenerator(1337L).generate()
        assertEquals(GroundDetail.entries.size, tiles.size)
        assertEquals(tiles.map { it.image.pixels.toList() }, GroundDetailGenerator(1337L).generate().map { it.image.pixels.toList() })
        for (t in tiles) {
            val n = solid(t.image, 0, 0, 32, 32)
            assertTrue(n in 1..700, "${t.name} is a sparse overlay, had $n solid pixels")
        }
        val s = tiles.first { it.name == "spill_s" }.image
        assertTrue(solid(s, 0, 24, 32, 8) > 40, "south spill covers the bottom edge")
        assertEquals(0, solid(s, 0, 0, 32, 16), "south spill leaves the top half clear")
        val n = tiles.first { it.name == "spill_n" }.image
        assertTrue(solid(n, 0, 0, 32, 8) > 40)
        assertEquals(0, solid(n, 0, 16, 32, 16))
    }
}
