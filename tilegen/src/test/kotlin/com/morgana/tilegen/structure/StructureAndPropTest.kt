package com.morgana.tilegen.structure

import com.morgana.tilegen.cli.TilesetCli
import com.morgana.tilegen.material.DecalKind
import com.morgana.tilegen.material.MaterialPattern
import com.morgana.tilegen.material.MaterialSpec
import com.morgana.tilegen.material.MaterialTileGenerator
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.material.TileDecals
import com.morgana.tilegen.spec.SpecException
import com.morgana.tilegen.spec.TilesetSpecJson
import com.morgana.tilegen.spec.TilesetSpecRenderer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class StructureAndPropTest {

    @Test
    fun `cliff generator creates 6 modular cap and face tiles deterministically`() {
        val matGen = MaterialTileGenerator(seed = 11L, tileSize = 16)
        val top = matGen.generateTile(MaterialSpec("grass", MaterialPattern.ORGANIC_FBM, PalettePresets.CLASSIC_RPG_GRASS))
        val face = matGen.generateTile(MaterialSpec("cliff", MaterialPattern.COBBLESTONE, PalettePresets.CLASSIC_RPG_STONE))

        val structGen = StructureGenerator(tileSize = 16)
        val first = structGen.generateCliffSet("earth_cliff", top, face, style = PixelStyle.CLASSIC_RPG)
        val second = structGen.generateCliffSet("earth_cliff", top, face, style = PixelStyle.CLASSIC_RPG)

        assertEquals(6, first.size)
        assertEquals(
            listOf(
                "earth_cliff_cap_left", "earth_cliff_cap_center", "earth_cliff_cap_right",
                "earth_cliff_face_left", "earth_cliff_face_center", "earth_cliff_face_right"
            ),
            first.map { it.name }
        )
        first.forEachIndexed { idx, tile ->
            assertEquals(16, tile.image.width)
            assertEquals(16, tile.image.height)
            assertArrayEquals(tile.image.pixels, second[idx].image.pixels)
        }
    }

    @Test
    fun `prop generator creates all prop kinds at 16px and 32px with expected dimensions and slices`() {
        for (tileSize in listOf(16, 32)) {
            val propGen = PropGenerator(seed = 42L, tileSize = tileSize)
            for (kind in PropKind.entries) {
                assertTrue(kind.widthTiles == 1 || kind.widthTiles == 3, "Prop $kind must be 1 or 3 tiles wide")
                val prop = propGen.generateProp("test_${kind.name.lowercase()}", kind, style = PixelStyle.CLASSIC_RPG)
                assertEquals(kind.widthTiles * tileSize, prop.fullImage.width)
                assertEquals(kind.heightTiles * tileSize, prop.fullImage.height)
                assertEquals(kind.widthTiles * kind.heightTiles, prop.slicedTiles.size)
                assertTrue(prop.fullImage.pixels.any { (it and 0xFF) != 0 }, "Prop $kind should have visible pixels")
            }
        }
    }

    @Test
    fun `retro 8bit prop respects 4-color cap and is deterministic`() {
        val gen = PropGenerator(seed = 88L, tileSize = 16)
        val a = gen.generateProp("oak", PropKind.OAK_TREE, style = PixelStyle.RETRO_8BIT, primaryPalette = PalettePresets.NES_OVERWORLD)
        val b = gen.generateProp("oak", PropKind.OAK_TREE, style = PixelStyle.RETRO_8BIT, primaryPalette = PalettePresets.NES_OVERWORLD)

        assertArrayEquals(a.fullImage.pixels, b.fullImage.pixels)
        val opaqueColors = a.fullImage.pixels.filter { (it and 0xFF) != 0 }.toSet()
        assertTrue(opaqueColors.size <= 4, "Expected <= 4 opaque colors in RETRO_8BIT prop, got ${opaqueColors.size}")
    }

    @Test
    fun `tileset spec parses, round-trips, and renders cliffs and props`(@TempDir tempDir: Path) {
        val json = """
            {
              "name": "classic_village",
              "seed": 2026,
              "tileSize": 16,
              "columns": 6,
              "style": "CLASSIC_RPG",
              "materials": [
                { "name": "classic_grass", "pattern": "ORGANIC_FBM", "palette": "classic_rpg_grass" },
                { "name": "classic_cliff_wall", "pattern": "COBBLESTONE", "palette": "classic_rpg_stone" }
              ],
              "cliffs": [
                { "name": "grass_cliff", "topMaterial": "classic_grass", "faceMaterial": "classic_cliff_wall" }
              ],
              "props": [
                { "name": "village_oak", "kind": "OAK_TREE", "primaryPalette": "classic_rpg_grass" },
                { "name": "village_house", "kind": "COTTAGE" }
              ]
            }
        """.trimIndent()

        val spec = TilesetSpecJson.parse(json)
        val reparsed = TilesetSpecJson.parse(TilesetSpecJson.toJson(spec))
        assertEquals(spec, reparsed)

        val rendered = TilesetSpecRenderer.render(spec)
        // 2 materials + 6 cliff tiles + 6 oak slices + 6 cottage slices = 20 tiles
        assertEquals(20, rendered.tiles.size)
        assertEquals(2, rendered.props.size)
        assertTrue(TilesetSpecJson.jsonSchema().contains("OAK_TREE"))

        // Verify CLI spec export writes both sheet and props/*.png
        val specPath = tempDir.resolve("village.json")
        Files.writeString(specPath, json)
        val outDir = tempDir.resolve("out")
        val out = PrintStream(ByteArrayOutputStream())
        val err = PrintStream(ByteArrayOutputStream())
        assertEquals(0, TilesetCli.run(arrayOf("spec", specPath.toString(), "--out", outDir.toString()), out, err))
        assertTrue(Files.isRegularFile(outDir.resolve("classic_village.png")))
        assertTrue(Files.isRegularFile(outDir.resolve("props/village_oak.png")))
        assertTrue(Files.isRegularFile(outDir.resolve("props/village_house.png")))
    }

    @Test
    fun `spec validation rejects cliff referencing missing material`() {
        val badJson = """
            {
              "name": "bad_cliff",
              "materials": [
                { "name": "grass", "pattern": "ORGANIC_FBM", "palette": "meadow_grass" }
              ],
              "cliffs": [
                { "name": "c1", "topMaterial": "grass", "faceMaterial": "missing_stone" }
              ]
            }
        """.trimIndent()
        assertThrows(SpecException::class.java) {
            TilesetSpecJson.parse(badJson)
        }
    }

    @Test
    fun `cli prop command exports png and json`(@TempDir tempDir: Path) {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val outDir = tempDir.resolve("cottage_out")
        val code = TilesetCli.run(
            arrayOf("prop", "cottage", "--size", "16", "--style", "classic_rpg", "--out", outDir.toString(), "--split"),
            PrintStream(outBuf),
            PrintStream(errBuf)
        )
        assertEquals(0, code)
        assertTrue(Files.isRegularFile(outDir.resolve("cottage.png")))
        assertTrue(Files.isRegularFile(outDir.resolve("cottage.json")))
        assertTrue(Files.isDirectory(outDir.resolve("tiles")))
    }

    @Test
    fun `a4 wall generator creates 9 void-cap and 2-tile-tall face tiles and round-trips in spec`() {
        val matGen = MaterialTileGenerator(seed = 7L, tileSize = 32)
        val top = matGen.generateTile(MaterialSpec("top", MaterialPattern.DIAGONAL_PAVER, PalettePresets.RUINS_MARBLE))
        val face = matGen.generateTile(MaterialSpec("face", MaterialPattern.BRICK_MASONRY, PalettePresets.RUINS_MARBLE, style = PixelStyle.CLASSIC_RPG))

        val structGen = StructureGenerator(tileSize = 32)
        val tiles = structGen.generateA4WallSet(
            name = "marble_wall",
            topMaterial = top,
            faceMaterial = face,
            style = PixelStyle.CLASSIC_RPG,
            trimColor = PalettePresets.RUINS_BLUE.mid
        )

        assertEquals(9, tiles.size)
        assertEquals(
            listOf(
                "marble_wall_void_left", "marble_wall_void_center", "marble_wall_void_right",
                "marble_wall_top_left", "marble_wall_top_center", "marble_wall_top_right",
                "marble_wall_bot_left", "marble_wall_bot_center", "marble_wall_bot_right"
            ),
            tiles.map { it.name }
        )
        val voidRgba = PaletteRamp.rgba(0x2B, 0x26, 0x32)
        assertEquals(voidRgba, tiles[1].image.getPixel(16, 4), "Void cap center should have #2b2632 void interior")

        val json = """
            {
              "name": "a4_test",
              "seed": 1,
              "tileSize": 32,
              "style": "CLASSIC_RPG",
              "materials": [
                { "name": "m_top", "pattern": "DIAGONAL_PAVER", "palette": "ruins_marble" },
                { "name": "m_face", "pattern": "BRICK_MASONRY", "palette": "ruins_marble" }
              ],
              "cliffs": [
                { "name": "w", "topMaterial": "m_top", "faceMaterial": "m_face", "trimColor": "#2d589f", "a4Layout": true }
              ]
            }
        """.trimIndent()
        val spec = TilesetSpecJson.parse(json)
        assertEquals(spec, TilesetSpecJson.parse(TilesetSpecJson.toJson(spec)))
        val rendered = TilesetSpecRenderer.render(spec)
        assertEquals(11, rendered.tiles.size)
    }

    @Test
    fun `vines decal adds crisp green pixels deterministically`() {
        val matGen = MaterialTileGenerator(seed = 5L, tileSize = 16)
        val base = matGen.generateTile(MaterialSpec("wall", MaterialPattern.BRICK_MASONRY, PalettePresets.RUINS_MARBLE))
        val a = TileDecals.applyDecal(base, DecalKind.VINES, seed = 99L, style = PixelStyle.CLASSIC_RPG, palette = PalettePresets.RUINS_MARBLE)
        val b = TileDecals.applyDecal(base, DecalKind.VINES, seed = 99L, style = PixelStyle.CLASSIC_RPG, palette = PalettePresets.RUINS_MARBLE)
        assertArrayEquals(a.pixels, b.pixels)

        val vineGreen = PaletteRamp.rgba(0x3E, 0xA8, 0x42)
        assertTrue(a.pixels.any { it == vineGreen }, "VINES decal should draw green leaf/tendril pixels")
    }

    @Test
    fun `classic ruins props use 16x16 pixel grid with 2x nearest-neighbor scaling`() {
        val ruinsKinds = listOf(
            PropKind.PILLAR, PropKind.SHRINE, PropKind.URN,
            PropKind.STAIRS_DOWN, PropKind.STAIRS_UP,
            PropKind.GARGOYLE_PLAQUE, PropKind.WALL_BANNER, PropKind.SKULL_PILE
        )
        val gen16 = PropGenerator(seed = 10L, tileSize = 16)
        val gen32 = PropGenerator(seed = 10L, tileSize = 32)
        val voidOutline = PaletteRamp.rgba(0x2B, 0x26, 0x32)

        for (kind in ruinsKinds) {
            val p16 = gen16.generateProp("classic_${kind.name}", kind, style = PixelStyle.CLASSIC_RPG, primaryPalette = PalettePresets.RUINS_MARBLE)
            val p32 = gen32.generateProp("classic_${kind.name}", kind, style = PixelStyle.CLASSIC_RPG, primaryPalette = PalettePresets.RUINS_MARBLE)

            assertTrue(p16.fullImage.pixels.any { it == voidOutline }, "$kind should use #2b2632 selective shadow outline")
            for (y in 0 until p16.fullImage.height) {
                for (x in 0 until p16.fullImage.width) {
                    val c = p16.fullImage.getPixel(x, y)
                    assertEquals(c, p32.fullImage.getPixel(x * 2, y * 2), "32px $kind should be 2x nearest-neighbor of 16px")
                    assertEquals(c, p32.fullImage.getPixel(x * 2 + 1, y * 2 + 1), "32px $kind should be 2x nearest-neighbor of 16px")
                }
            }
        }
    }
}
