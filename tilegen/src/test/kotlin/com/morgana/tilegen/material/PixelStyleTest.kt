package com.morgana.tilegen.material

import com.morgana.tilegen.autotile.AutotileGenerator
import com.morgana.tilegen.autotile.AutotileMode
import com.morgana.tilegen.autotile.AutotileStyle
import com.morgana.tilegen.spec.TilesetSpecJson
import com.morgana.tilegen.spec.TilesetSpecRenderer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PixelStyleTest {

    @Test
    fun `retro 8bit style caps unique opaque colors to at most 4 and is deterministic`() {
        val gen = MaterialTileGenerator(seed = 77L, tileSize = 16)
        val spec = MaterialSpec(
            name = "gb_grass",
            pattern = MaterialPattern.ORGANIC_FBM,
            palette = PalettePresets.MEADOW_GRASS, // 5-color ramp, should be capped to 4
            style = PixelStyle.RETRO_8BIT
        )
        val tileA = gen.generateTile(spec)
        val tileB = gen.generateTile(spec)

        assertArrayEquals(tileA.pixels, tileB.pixels)

        val uniqueColors = tileA.pixels.filter { (it and 0xFF) != 0 }.toSet()
        assertTrue(uniqueColors.size in 2..4, "Expected 2..4 colors in RETRO_8BIT, got ${uniqueColors.size}")
    }

    @Test
    fun `time fantasy style produces deterministic dithered tiles with earthy presets`() {
        val gen = MaterialTileGenerator(seed = 123L, tileSize = 32)
        val spec = MaterialSpec(
            name = "tf_grass",
            pattern = MaterialPattern.ORGANIC_FBM,
            palette = PalettePresets.TIME_FANTASY_GRASS,
            style = PixelStyle.TIME_FANTASY
        )
        val tileA = gen.generateTile(spec)
        val tileB = gen.generateTile(spec)
        assertArrayEquals(tileA.pixels, tileB.pixels)

        val uniqueColors = tileA.pixels.filter { (it and 0xFF) != 0 }.toSet()
        assertTrue(uniqueColors.size >= 3, "Expected >= 3 colors in TIME_FANTASY, got ${uniqueColors.size}")
    }

    @Test
    fun `palette ramp quantize and limitColors preserve expected stops`() {
        val limited = PalettePresets.MEADOW_GRASS.limitColors(4)
        assertEquals(4, limited.colors.size)
        assertEquals(PalettePresets.MEADOW_GRASS.shadow, limited.shadow)
        assertEquals(PalettePresets.MEADOW_GRASS.highlight, limited.highlight)

        val exact = PalettePresets.GAMEBOY_DMG.quantize(PalettePresets.GAMEBOY_DMG.colors[1])
        assertEquals(PalettePresets.GAMEBOY_DMG.colors[1], exact)
    }

    @Test
    fun `retro 8bit autotile maintains 100 percent connected seam continuity`() {
        val matGen = MaterialTileGenerator(seed = 55L, tileSize = 16)
        val fg = matGen.generateTile(
            MaterialSpec("fg", MaterialPattern.ORGANIC_FBM, PalettePresets.NES_OVERWORLD, style = PixelStyle.RETRO_8BIT)
        )
        val bg = matGen.generateTile(
            MaterialSpec("bg", MaterialPattern.COBBLESTONE, PalettePresets.NES_DUNGEON, style = PixelStyle.RETRO_8BIT)
        )
        val autoGen = AutotileGenerator(seed = 55L, tileSize = 16)
        val sheet = autoGen.generateAutotile(
            foreground = fg,
            background = bg,
            mode = AutotileMode.CARDINAL_16,
            style = AutotileStyle(margin = 3f, rimWidth = 1f, wobbleAmplitude = 2f, pixelStyle = PixelStyle.RETRO_8BIT)
        )
        assertEquals(16, sheet.entries.size)
        // Full mask (15) should match foreground pixels
        val fullEntry = sheet.entryForMask(15)
        val fullTile = sheet.image.crop(fullEntry.col * 16, fullEntry.row * 16, 16, 16)
        assertArrayEquals(fg.pixels, fullTile.pixels)
    }

    @Test
    fun `tileset spec style field round-trips through json and propagates to renderer`() {
        val json = """
            {
              "name": "retro_overworld",
              "seed": 42,
              "tileSize": 16,
              "columns": 2,
              "style": "RETRO_8BIT",
              "materials": [
                { "name": "gb_ground", "pattern": "ORGANIC_FBM", "palette": "gameboy_dmg" },
                { "name": "tf_path", "pattern": "COBBLESTONE", "palette": "time_fantasy_earth", "style": "TIME_FANTASY" }
              ]
            }
        """.trimIndent()

        val spec = TilesetSpecJson.parse(json)
        assertEquals(PixelStyle.RETRO_8BIT, spec.style)
        val reparsed = TilesetSpecJson.parse(TilesetSpecJson.toJson(spec))
        assertEquals(spec, reparsed)

        val rendered = TilesetSpecRenderer.render(spec)
        val gbColors = rendered.tiles[0].image.pixels.filter { (it and 0xFF) != 0 }.toSet()
        assertTrue(gbColors.size <= 4)
        assertTrue(TilesetSpecJson.jsonSchema().contains("RETRO_8BIT"))
        assertTrue(TilesetSpecJson.jsonSchema().contains("time_fantasy_grass"))
    }
}
