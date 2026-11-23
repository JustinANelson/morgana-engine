package com.morgana.tilegen.material

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.spec.TilesetSpecJson
import com.morgana.tilegen.spec.TilesetSpecRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HandPixelledMaterialsTest {
    private val ramp = PaletteRamp.fromBaseColor(PaletteRamp.rgba(70, 110, 190))

    private fun gen(pattern: MaterialPattern, frame: Int = 0, style: PixelStyle = PixelStyle.STANDARD, size: Int = 32): TileImage =
        MaterialTileGenerator(9L, size).generateTile(MaterialSpec("m", pattern, ramp, frameIndex = frame, style = style))

    @Test
    fun `new patterns use only palette colours, are deterministic and ignore dithering styles`() {
        for (pattern in listOf(MaterialPattern.ROUNDED_STONES, MaterialPattern.FLAT_WATER)) {
            for (size in listOf(16, 32)) {
                val a = gen(pattern, size = size)
                assertTrue(a.pixels.contentEquals(gen(pattern, size = size).pixels))
                val allowed = ramp.colors.toSet()
                assertTrue(a.pixels.all { it in allowed }, "$pattern has off-palette colours")
            }
            assertTrue(gen(pattern).pixels.contentEquals(gen(pattern, style = PixelStyle.CLASSIC_RPG).pixels), "$pattern was dithered")
        }
    }

    @Test
    fun `rounded stones show outlined stones with gaps and lit faces`() {
        val t = gen(MaterialPattern.ROUNDED_STONES)
        val used = t.pixels.toSet()
        assertTrue(ramp.colors[0] in used && ramp.colors.last() in used)
        assertTrue(used.size >= 4)
    }

    @Test
    fun `flat water frames differ and four frames loop exactly`() {
        val frames = (0..3).map { gen(MaterialPattern.FLAT_WATER, it) }
        assertNotEquals(frames[0].pixels.toList(), frames[1].pixels.toList())
        // Frame 4 would be frame 0 again: the streaks drift one quarter tile per frame.
        assertTrue(gen(MaterialPattern.FLAT_WATER, 4).pixels.contentEquals(frames[0].pixels))
    }

    @Test
    fun `lush cliff caps overhang the face and neighbouring caps share the same lip`() {
        val json = """
            {"name":"c","tileSize":32,"palettes":{"g":{"colors":["#223311","#446622","#668833","#88aa44","#bbdd66"]},"r":{"colors":["#2a1408","#4a2810","#7a4a20","#a86a30","#d08a40"]}},
             "materials":[{"name":"g","pattern":"TUFTED_GROUND","palette":"g"},{"name":"r","pattern":"ROUNDED_STONES","palette":"r"}],
             "cliffs":[{"name":"plain","topMaterial":"g","faceMaterial":"r"},{"name":"lush","topMaterial":"g","faceMaterial":"r","lush":true}]}
        """.trimIndent()
        val spec = TilesetSpecJson.parse(json)
        assertTrue(spec.cliffs[1].lush)
        assertTrue(TilesetSpecJson.parse(TilesetSpecJson.toJson(spec)).cliffs[1].lush)
        val tiles = TilesetSpecRenderer.render(spec).tiles.associateBy { it.name }
        val plain = tiles.getValue("plain_cap_center").image
        val lush = tiles.getValue("lush_cap_center").image
        assertNotEquals(plain.pixels.toList(), lush.pixels.toList())
        // The last row of a lush cap is face stone showing under the lip, not grass.
        assertTrue((0 until 32).count { lush.getPixel(it, 31) != plain.getPixel(it, 31) } > 24)
        // Left and right caps get the same lip as the centre one (it depends only on the column).
        val left = tiles.getValue("lush_cap_left").image
        for (x in 10 until 28) assertEquals(lush.getPixel(x, 31), left.getPixel(x, 31))
        // The outer corner curves up: the leftmost column of the left cap turns to stone higher than the centre cap does.
        fun firstStoneRow(img: TileImage, x: Int) = (0 until 32).first { y -> img.getPixel(x, y) != plain.getPixel(x, y) && y > 8 }
        assertTrue(firstStoneRow(left, 0) < firstStoneRow(lush, 0), "left corner not rounded")
    }
}
