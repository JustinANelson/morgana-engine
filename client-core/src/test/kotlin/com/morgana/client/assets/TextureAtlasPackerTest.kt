package com.morgana.client.assets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextureAtlasPackerTest {

    @Test
    fun `packs sprites and icons without overlap within power-of-two pages`() {
        val inputs = buildList {
            for (i in 1..16) add(AtlasRectInput("icon:$i", 32, 32))
            for (i in 1..12) add(AtlasRectInput("sprite:$i", 96, 128))
            add(AtlasRectInput("anim:1", 160, 32))
        }

        val packed = TextureAtlasPacker.pack(inputs, maxPageWidth = 1024, maxPageHeight = 1024, padding = 1)
        assertTrue(packed.oversizedKeys.isEmpty())
        assertEquals(inputs.size, packed.placementsByKey.size)
        assertTrue(packed.pages.size <= 2, "Expected compact packing into <=2 pages, got ${packed.pages.size}")

        // Verify bounds, power-of-two sizing, and non-overlapping rectangles + 1px padding per page.
        for (page in packed.pages) {
            assertTrue(page.width > 0 && (page.width and (page.width - 1)) == 0, "width ${page.width} not POT")
            assertTrue(page.height > 0 && (page.height and (page.height - 1)) == 0, "height ${page.height} not POT")
            for (i in page.placements.indices) {
                val a = page.placements[i]
                assertTrue(a.x >= 1 && a.y >= 1)
                assertTrue(a.x + a.width + 1 <= page.width)
                assertTrue(a.y + a.height + 1 <= page.height)
                for (j in i + 1 until page.placements.size) {
                    val b = page.placements[j]
                    val sepX = (a.x + a.width + 1 <= b.x - 1) || (b.x + b.width + 1 <= a.x - 1)
                    val sepY = (a.y + a.height + 1 <= b.y - 1) || (b.y + b.height + 1 <= a.y - 1)
                    assertTrue(sepX || sepY, "Overlap between ${a.key} and ${b.key}")
                }
            }
        }
    }

    @Test
    fun `splits into multiple pages cleanly and blits exact RGBA pixels without bleed`() {
        val aPixels = IntArray(48 * 48) { 0xFF0000FF.toInt() }
        val bPixels = IntArray(48 * 48) { 0x00FF00FF }
        val cPixels = IntArray(48 * 48) { 0x0000FFFF }
        val oversized = AtlasRectInput("huge", 2000, 32)

        val result = TextureAtlasPacker.pack(
            listOf(
                AtlasRectInput("a", 48, 48),
                AtlasRectInput("b", 48, 48),
                AtlasRectInput("c", 48, 48),
                oversized
            ),
            maxPageWidth = 128,
            maxPageHeight = 64,
            padding = 1
        )

        assertEquals(setOf("huge"), result.oversizedKeys)
        assertEquals(2, result.pages.size)

        val srcMap = mapOf("a" to aPixels, "b" to bPixels, "c" to cPixels)
        for (page in result.pages) {
            val buf = TextureAtlasPacker.blitPageRgba(page, srcMap)
            for (p in page.placements) {
                val expected = srcMap.getValue(p.key)[0]
                assertEquals(expected, buf[p.y * page.width + p.x])
                assertEquals(expected, buf[(p.y + p.height - 1) * page.width + (p.x + p.width - 1)])
                // 1px outer gutter must stay transparent 0
                assertEquals(0, buf[(p.y - 1) * page.width + p.x])
            }
        }
    }
}

