package com.morgana.tilegen.autotile

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.MaterialPattern
import com.morgana.tilegen.material.MaterialSpec
import com.morgana.tilegen.material.MaterialTileGenerator
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.spec.TilesetSpecJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutotileEdgeFinishTest {
    private val grass = PaletteRamp.fromBaseColor(PaletteRamp.rgba(110, 150, 60))
    private val dirt = PaletteRamp.fromBaseColor(PaletteRamp.rgba(160, 100, 50))

    private fun tile(p: PaletteRamp, name: String, size: Int = 32): TileImage =
        MaterialTileGenerator(1L, size).generateTile(MaterialSpec(name, MaterialPattern.TUFTED_GROUND, p))

    private fun luma(rgba: Int): Int =
        (((rgba ushr 24) and 0xFF) * 3 + ((rgba ushr 16) and 0xFF) * 6 + ((rgba ushr 8) and 0xFF)) / 10

    @Test
    fun `tufted ground uses only palette colours and is deterministic`() {
        val a = tile(grass, "g")
        val b = tile(grass, "g")
        assertTrue(a.pixels.contentEquals(b.pixels))
        val allowed = grass.colors.toSet()
        for (y in 0 until 32) for (x in 0 until 32) assertTrue(a.getPixel(x, y) in allowed)
    }

    @Test
    fun `tufted ground is calm, not static`() {
        val t = tile(grass, "g")
        val noisy = MaterialTileGenerator(1L, 32).generateTile(MaterialSpec("g", MaterialPattern.ORGANIC_FBM, grass))
        fun changes(img: TileImage): Int {
            var n = 0
            for (y in 0 until 32) for (x in 0 until 31) if (img.getPixel(x, y) != img.getPixel(x + 1, y)) n++
            return n
        }
        assertTrue(changes(t) < changes(noisy), "tufted ${changes(t)} vs fbm ${changes(noisy)}")
    }

    @Test
    fun `finished edges outline the silhouette and darken it against the fill`() {
        val gen = AutotileGenerator(5L, 32)
        val fg = tile(grass, "fg")
        val bg = tile(dirt, "bg")
        val isolated = 0 // no neighbours: a single island of foreground
        val soft = gen.renderAutotileTile(fg, bg, isolated, AutotileMode.BLOB_47, AutotileStyle(margin = 6f))
        val out = gen.renderAutotileTile(fg, bg, isolated, AutotileMode.BLOB_47, AutotileStyle(margin = 6f, edge = AutotileEdge.OUTLINED))
        assertNotEquals(soft.pixels.toList(), out.pixels.toList())
        // Foreground interior is untouched; the outermost ring is darker than the fill it came from.
        assertEquals(fg.getPixel(16, 16), out.getPixel(16, 16))
        var darker = 0
        var ring = 0
        for (y in 0 until 32) for (x in 0 until 32) {
            val p = out.getPixel(x, y)
            if (p != bg.getPixel(x, y) && p != fg.getPixel(x, y) && luma(p) < luma(fg.getPixel(x, y))) darker++
            if (p != bg.getPixel(x, y)) ring++
        }
        assertTrue(ring > 0 && darker > 20, "expected an outline, got $darker dark pixels")
    }

    @Test
    fun `lush edges differ from outlined ones and a custom rim colour overrides the outline`() {
        val gen = AutotileGenerator(5L, 32)
        val fg = tile(grass, "fg")
        val bg = tile(dirt, "bg")
        fun render(edge: AutotileEdge, rim: Int? = null) =
            gen.renderAutotileTile(fg, bg, 0, AutotileMode.BLOB_47, AutotileStyle(margin = 6f, edge = edge, rimColor = rim)).pixels.toList()
        assertNotEquals(render(AutotileEdge.OUTLINED), render(AutotileEdge.LUSH))
        val pink = PaletteRamp.rgba(255, 0, 255)
        assertTrue(pink in render(AutotileEdge.OUTLINED, pink))
    }

    @Test
    fun `soft edges keep the original look when no finish is requested`() {
        val gen = AutotileGenerator(5L, 32)
        val sheet = gen.generateAutotile(tile(grass, "fg"), tile(dirt, "bg"), AutotileMode.BLOB_47, AutotileStyle())
        assertEquals(47, sheet.entries.size)
        assertEquals(AutotileEdge.SOFT, AutotileStyle().edge)
    }

    @Test
    fun `spec json round trips edge and shadow`() {
        val json = """
            {"name":"e","tileSize":32,"palettes":{"g":{"colors":["#223311","#446622","#668833","#88aa44","#bbdd66"]},"d":{"colors":["#331a0a","#552a12","#774020","#996030","#bb8040"]}},
             "materials":[{"name":"g","pattern":"TUFTED_GROUND","palette":"g"},{"name":"d","pattern":"TUFTED_GROUND","palette":"d"}],
             "autotiles":[{"name":"a","foregroundMaterial":"g","backgroundMaterial":"d","style":{"edge":"LUSH","shadow":0.4}}]}
        """.trimIndent()
        val spec = TilesetSpecJson.parse(json)
        val style = spec.autotiles.single().style!!
        assertEquals(AutotileEdge.LUSH, style.edge)
        assertEquals(0.4f, style.shadow)
        assertEquals(style, TilesetSpecJson.parse(TilesetSpecJson.toJson(spec)).autotiles.single().style)
        assertTrue(TilesetSpecJson.jsonSchema().contains("LUSH"))
    }
}
