package com.morgana.tilegen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TileImageTest {

    @Test
    fun `new images are transparent and out-of-bounds drawing is ignored`() {
        val img = TileImage(4, 4)
        assertEquals(0, img.getPixel(1, 1))
        img.drawPixel(-1, 0, 0xFF0000FF.toInt())
        img.drawPixel(4, 4, 0xFF0000FF.toInt())
        assertEquals(0, img.pixels.count { it != 0 })
        assertEquals(0, img.getPixel(9, 9))
    }

    @Test
    fun `opaque draws replace and translucent draws blend source-over`() {
        val img = TileImage(2, 1)
        img.drawPixel(0, 0, 0x000000FF)
        img.drawPixel(0, 0, 0xFFFFFF80.toInt())
        // ~50% white over opaque black: each channel 255 * 128 / 255 = 128, alpha stays opaque.
        assertEquals(0x808080FF.toInt(), img.getPixel(0, 0))

        img.drawPixel(1, 0, 0x11223300) // fully transparent source leaves the destination untouched
        assertEquals(0, img.getPixel(1, 0))
        img.drawPixel(1, 0, 0xFFFFFF80.toInt()) // onto a transparent pixel: copied as-is, not darkened
        assertEquals(0xFFFFFF80.toInt(), img.getPixel(1, 0))
    }

    @Test
    fun `drawImage copies a region and crop extracts it`() {
        val src = TileImage(4, 4)
        src.setPixel(2, 3, 0x123456FF)
        val dst = TileImage(8, 8)
        dst.drawImage(src, 2, 2, 2, 2, 5, 5)
        assertEquals(0x123456FF, dst.getPixel(5, 6))
        assertEquals(0x123456FF, dst.crop(5, 6, 1, 1).getPixel(0, 0))
    }
}
