package com.morgana.client.assets

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.utils.GdxNativesLoader
import com.morgana.tilegen.ProceduralTilesetGenerator
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteOrder

class PixmapPixelsTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupNatives() {
            GdxNativesLoader.load()
        }
    }

    private fun readBack(pm: Pixmap): IntArray = IntArray(pm.width * pm.height) { pm.getPixel(it % pm.width, it / pm.width) }

    @Test
    fun `bulk write gives the same pixels as drawPixel without blending`() {
        val image = ProceduralTilesetGenerator(seed = 7L).generateTileset(columns = 3, rows = 2)
        image.setPixel(0, 0, 0x11223380)
        image.setPixel(1, 0, 0)
        image.setPixel(2, 0, 0xFFFFFF01.toInt())
        val reference = Pixmap(image.width, image.height, Pixmap.Format.RGBA8888)
        val fast = rgba8888UploadPixmap(image.width, image.height, image.pixels)
        try {
            reference.blending = Pixmap.Blending.None
            for (y in 0 until image.height) for (x in 0 until image.width) reference.drawPixel(x, y, image.pixels[y * image.width + x])
            assertArrayEquals(readBack(reference), readBack(fast))
            assertArrayEquals(image.pixels, readBack(fast))
        } finally {
            reference.dispose()
            fast.dispose()
        }
    }

    @Test
    fun `bulk write leaves the pixmap buffer's position and byte order alone`() {
        val pm = Pixmap(4, 3, Pixmap.Format.RGBA8888)
        try {
            val buf = pm.pixels
            val order = buf.order()
            buf.position(5)
            pm.writeRgba8888(IntArray(12) { 0x01020304 * (it + 1) })
            assertEquals(5, buf.position())
            assertEquals(order, buf.order())
            buf.position(0)
            assertEquals(ByteOrder.BIG_ENDIAN, buf.duplicate().order())
            assertEquals(0x01020304, pm.getPixel(0, 0))
        } finally {
            pm.dispose()
        }
    }

    @Test
    fun `bulk write rejects other formats and short arrays`() {
        val rgb = Pixmap(2, 2, Pixmap.Format.RGB888)
        val rgba = Pixmap(2, 2, Pixmap.Format.RGBA8888)
        try {
            assertThrows<IllegalArgumentException> { rgb.writeRgba8888(IntArray(4)) }
            assertThrows<IllegalArgumentException> { rgba.writeRgba8888(IntArray(3)) }
        } finally {
            rgb.dispose()
            rgba.dispose()
        }
    }
}
