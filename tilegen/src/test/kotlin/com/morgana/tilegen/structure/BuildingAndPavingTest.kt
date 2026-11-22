package com.morgana.tilegen.structure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildingAndPavingTest {
    private fun alpha(p: Int) = p and 0xFF

    @Test
    fun `every building has its tile size, a solid footprint row and a deterministic picture`() {
        val gen = BuildingGenerator(1337L)
        for (kind in BuildingKind.entries) {
            val a = gen.generate(kind)
            val b = BuildingGenerator(1337L).generate(kind)
            assertEquals(kind.widthTiles * 32, a.image.width)
            assertEquals(kind.heightTiles * 32, a.image.height)
            assertEquals(kind.widthTiles * kind.heightTiles, a.tiles.size)
            assertTrue(a.image.pixels.contentEquals(b.image.pixels), "${kind.name} is deterministic")
            // The bottom tile row is the blocked footprint: its walls must be solid across most of the width.
            val y = a.image.height - 12
            val solid = (0 until a.image.width).count { alpha(a.image.getPixel(it, y)) == 255 }
            assertTrue(solid > a.image.width * 0.8, "${kind.name} footprint row is mostly solid, was $solid of ${a.image.width}")
            assertTrue(a.doorColumn in 0 until kind.widthTiles, "${kind.name} door column is inside the building")
            // The top rows are roof or chimney, so the picture is not empty up there.
            val topRowsHavePixels = (0 until a.image.width).any { alpha(a.image.getPixel(it, a.image.height - 32 - 20)) == 255 }
            assertTrue(topRowsHavePixels, "${kind.name} has roof above the footprint row")
        }
    }

    @Test
    fun `a roof override changes the picture`() {
        val gen = BuildingGenerator(1337L)
        val red = gen.generate(BuildingKind.COTTAGE_LARGE, "c", BuildingRoof.RED)
        val thatch = gen.generate(BuildingKind.COTTAGE_LARGE, "c", BuildingRoof.THATCH)
        assertTrue(!red.image.pixels.contentEquals(thatch.image.pixels))
    }

    @Test
    fun `each building theme changes the walls and keeps the footprint solid`() {
        val pictures = BuildingTheme.entries.map { BuildingGenerator(1337L, it).generate(BuildingKind.SMITHY, "s") }
        assertEquals(BuildingTheme.entries.size, pictures.map { it.image.pixels.toList() }.toSet().size, "every theme looks different")
        for (p in pictures) {
            val y = p.image.height - 12
            assertTrue((0 until p.image.width).count { alpha(p.image.getPixel(it, y)) == 255 } > p.image.width * 0.8)
        }
    }

    @Test
    fun `paving tiles are fully opaque, deterministic and seamless`() {
        val gen = PavingGenerator(1337L)
        for (kind in PavingKind.entries) {
            val tiles = gen.generate(kind, 4)
            assertEquals(4, tiles.size)
            assertEquals(tiles.map { it.image.pixels.toList() }, PavingGenerator(1337L).generate(kind, 4).map { it.image.pixels.toList() })
            assertTrue(tiles.map { it.image.pixels.toList() }.toSet().size == 4, "${kind.name} variants differ")
            for (t in tiles) {
                assertTrue(t.image.pixels.all { alpha(it) == 255 }, "${t.name} is opaque")
                // Seamless: the pixel columns on each side of the wrap differ no more than the most different pair of neighbouring columns inside the tile (a mortar edge).
                fun diff(x1: Int, x2: Int) = (0 until 32).sumOf { y -> channelDiff(t.image.getPixel(x1, y), t.image.getPixel(x2, y)) }
                val seam = diff(31, 0)
                val inside = (0 until 31).maxOf { diff(it, it + 1) }
                assertTrue(seam <= inside * 1.25, "${t.name} wraps without a visible seam ($seam vs $inside)")
            }
        }
    }

    private fun channelDiff(a: Int, b: Int): Int {
        var s = 0
        for (sh in intArrayOf(24, 16, 8)) s += kotlin.math.abs(((a ushr sh) and 0xFF) - ((b ushr sh) and 0xFF))
        return s
    }
}
