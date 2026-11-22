package com.morgana.client.render

import com.morgana.domain.item.PaperdollLayer
import com.morgana.domain.item.PaperdollSlot
import com.morgana.domain.spatial.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class PaperdollCompositeCacheTest {

    private fun solidCell(w: Int, h: Int, rgba: Int, overlay: Boolean = false): RgbaCell =
        RgbaCell(w, h, IntArray(w * h) { rgba }, isOverlay = overlay)

    @Test
    fun `cache hits same appearance and invalidates superseded character gear or LRU overflow`() {
        val evicted = mutableListOf<String>()
        val cache = PaperdollCompositeCache<String>(maxEntries = 3) { evicted += it }

        val armorRed = listOf(PaperdollLayer(PaperdollSlot.TORSO, spriteId = 301, tintHex = "#ff0000"))
        val armorBlue = listOf(PaperdollLayer(PaperdollSlot.TORSO, spriteId = 301, tintHex = "#0000ff"))

        val kDown = PaperdollCompositeKey(1, directionRow = 0, column = 0, cellWidth = 32, cellHeight = 32, layers = armorRed, artEpoch = 0L)
        val kUp = kDown.copy(directionRow = 3)

        assertEquals("p1-down-red", cache.getOrPut("p1", kDown) { "p1-down-red" })
        assertEquals("p1-down-red", cache.getOrPut("p1", kDown) { "should-not-build" })
        assertEquals("p1-up-red", cache.getOrPut("p1", kUp) { "p1-up-red" })
        assertEquals(1L, cache.hitCount)
        assertEquals(2L, cache.missCount)
        assertEquals(2, cache.size)

        // Changing equipment for p1 invalidates p1's prior red frames cleanly.
        val kDownBlue = kDown.copy(layers = armorBlue)
        assertEquals("p1-down-blue", cache.getOrPut("p1", kDownBlue) { "p1-down-blue" })
        assertEquals(listOf("p1-down-red", "p1-up-red"), evicted)
        assertEquals(1, cache.size)

        // Filling beyond maxEntries=3 evicts LRU entries deterministically.
        cache.getOrPut("p2", kDownBlue) { "p2" }
        cache.getOrPut("p3", kDownBlue) { "p3" }
        // Touch p1 so p2 becomes oldest
        assertEquals("p1-down-blue", cache.getOrPut("p1", kDownBlue) { "unused" })
        cache.getOrPut("p4", kDownBlue) { "p4" }
        assertTrueContains(evicted, "p2")

        cache.invalidateAll()
        assertEquals(0, cache.size)
        assertTrueContains(evicted, "p1-down-blue")
        assertTrueContains(evicted, "p3")
        assertTrueContains(evicted, "p4")
    }

    @Test
    fun `composeRgbaCell applies tint multiplication, overlay centering, and direction draw order`() {
        val white = 0xFFFFFFFF.toInt()
        val green = 0x00FF00FF
        val body = solidCell(4, 4, green)
        val sword = solidCell(4, 4, white)

        // Facing DOWN: weapon draws OVER body, tinted red (#ff0000).
        val downPixels = PaperdollCompositeCache.composeRgbaCell(
            4,
            4,
            listOf(
                ComposedPixelLayer(PaperdollSlot.BODY, body),
                ComposedPixelLayer(PaperdollSlot.MAIN_HAND, sword, tintHex = "#ff0000")
            ),
            Direction.DOWN
        )
        assertEquals(0xFF0000FF.toInt(), downPixels[0])

        // Facing UP: opaque body occludes opaque weapon behind it.
        val upPixels = PaperdollCompositeCache.composeRgbaCell(
            4,
            4,
            listOf(
                ComposedPixelLayer(PaperdollSlot.BODY, body),
                ComposedPixelLayer(PaperdollSlot.MAIN_HAND, sword, tintHex = "#ff0000")
            ),
            Direction.UP
        )
        assertEquals(green, upPixels[0])

        // Legacy square 2x2 overlay centered onto a taller 4x6 cell stays transparent outside centered box.
        val overlay = solidCell(2, 2, 0x112233FF, overlay = true)
        val tallPixels = PaperdollCompositeCache.composeRgbaCell(
            4,
            6,
            listOf(ComposedPixelLayer(PaperdollSlot.TORSO, overlay)),
            Direction.DOWN
        )
        assertEquals(0, tallPixels[0]) // (0,0) untouched
        assertNotEquals(0, tallPixels[2 * 4 + 1]) // (1,2) inside centered 2x2 box
        assertEquals(0x112233FF, tallPixels[2 * 4 + 1])
    }

    private fun assertTrueContains(list: List<String>, value: String) {
        if (value !in list) throw AssertionError("Expected $value in $list")
    }

    @Test
    fun `a glowing layer gets a 1 pixel yellow outline just outside its silhouette and nothing else`() {
        val glow = PaperdollCompositeCache.GLOW_RGBA
        // A 2x2 opaque item in the middle of a 6x6 cell: pixels (2..3, 2..3).
        val cell = IntArray(36)
        for (y in 2..3) for (x in 2..3) cell[y * 6 + x] = 0x112233FF
        val item = RgbaCell(6, 6, cell)

        val pixels = PaperdollCompositeCache.composeRgbaCell(6, 6, listOf(ComposedPixelLayer(PaperdollSlot.MAIN_HAND, item, glow = true)), Direction.DOWN)

        for (y in 2..3) for (x in 2..3) assertEquals(0x112233FF, pixels[y * 6 + x], "the item itself is untouched at ($x,$y)")
        // The ring touching the four sides of the 2x2 block.
        for (x in 2..3) { assertEquals(glow, pixels[1 * 6 + x]); assertEquals(glow, pixels[4 * 6 + x]) }
        for (y in 2..3) { assertEquals(glow, pixels[y * 6 + 1]); assertEquals(glow, pixels[y * 6 + 4]) }
        // Corners are diagonal, not touching, so they stay clear; so does everything further out.
        assertEquals(0, pixels[1 * 6 + 1])
        assertEquals(0, pixels[4 * 6 + 4])
        assertEquals(0, pixels[0])
        assertEquals(0, pixels[5 * 6 + 5])
        assertEquals(8, pixels.count { it == glow }, "exactly eight outline pixels")
    }

    @Test
    fun `a layer without glow gets no outline, and the outline sits over layers below but under the glowing layer`() {
        val body = solidCell(4, 4, 0x00FF00FF)
        val plain = PaperdollCompositeCache.composeRgbaCell(4, 4, listOf(ComposedPixelLayer(PaperdollSlot.BODY, body)), Direction.DOWN)
        assertEquals(0, plain.count { it == PaperdollCompositeCache.GLOW_RGBA })

        // A 2x2 helmet at (1..2, 1..2) over a full-cell body: the ring paints over the body, the helmet covers its own pixels.
        val helmetCells = IntArray(16)
        for (y in 1..2) for (x in 1..2) helmetCells[y * 4 + x] = 0xFF0000FF.toInt()
        val glowing = PaperdollCompositeCache.composeRgbaCell(
            4, 4,
            listOf(ComposedPixelLayer(PaperdollSlot.BODY, body), ComposedPixelLayer(PaperdollSlot.HEAD, RgbaCell(4, 4, helmetCells), glow = true)),
            Direction.DOWN
        )
        assertEquals(0xFF0000FF.toInt(), glowing[1 * 4 + 1])
        assertEquals(PaperdollCompositeCache.GLOW_RGBA, glowing[0 * 4 + 1], "outline over the body")
        assertEquals(0x00FF00FF, glowing[0], "a body pixel away from the helmet is untouched")
    }

    @Test
    fun `glow is part of the cache key so toggling it rebuilds the frame`() {
        val a = PaperdollCompositeKey(1, 0, 0, 32, 32, listOf(PaperdollLayer(PaperdollSlot.TORSO, 301)), 0L)
        val b = a.copy(layers = listOf(PaperdollLayer(PaperdollSlot.TORSO, 301, glow = true)))
        assertNotEquals(a, b)
    }
}
