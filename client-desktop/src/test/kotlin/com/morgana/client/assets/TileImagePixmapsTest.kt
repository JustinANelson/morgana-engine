package com.morgana.client.assets

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.utils.GdxNativesLoader
import com.morgana.tilegen.ProceduralTilesetGenerator
import com.morgana.tilegen.TileImage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.math.abs

class TileImagePixmapsTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupNatives() {
            GdxNativesLoader.load()
        }
    }

    @Test
    fun `TileImage round-trips through Pixmap without re-blending translucent pixels`() {
        val image = ProceduralTilesetGenerator(seed = 3L).generateTileset(columns = 4, rows = 2)
        image.setPixel(0, 0, 0x11223380)
        image.setPixel(1, 0, 0)
        val pm = image.toPixmap()
        try {
            assertArrayEquals(image.pixels, pm.toTileImage().pixels)
        } finally {
            pm.dispose()
        }
    }

    @Test
    fun `TileImage source-over blending matches Pixmap blending within rounding`() {
        val colors = intArrayOf(0x000000FF, 0xFFFFFF80.toInt(), 0x3366CC40, 0xAA0000C0.toInt(), 0x00FF0001)
        val image = TileImage(colors.size, colors.size)
        val pm = Pixmap(colors.size, colors.size, Pixmap.Format.RGBA8888)
        try {
            for (y in colors.indices) for (x in colors.indices) {
                image.drawPixel(x, y, colors[y]); pm.drawPixel(x, y, colors[y])
                image.drawPixel(x, y, colors[x]); pm.drawPixel(x, y, colors[x])
            }
            val expected = pm.toTileImage().pixels
            for (i in expected.indices) {
                for (shift in intArrayOf(24, 16, 8, 0)) {
                    val e = (expected[i] ushr shift) and 0xFF
                    val a = (image.pixels[i] ushr shift) and 0xFF
                    assertTrue(abs(e - a) <= 2, "pixel $i channel $shift: Pixmap $e vs TileImage $a")
                }
            }
        } finally {
            pm.dispose()
        }
    }
}
