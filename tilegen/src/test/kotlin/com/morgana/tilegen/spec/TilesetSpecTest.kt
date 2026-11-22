package com.morgana.tilegen.spec

import com.morgana.tilegen.autotile.AutotileMode
import com.morgana.tilegen.autotile.TerrainPair
import com.morgana.tilegen.cli.TilesetCli
import com.morgana.tilegen.material.MaterialPattern
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class TilesetSpecTest {

    private val sampleJson = """
        {
          "name": "volcanic_cavern",
          "seed": 99,
          "tileSize": 16,
          "columns": 4,
          "palettes": {
            "basalt": {
              "colors": ["#141418", "#262830", "#3d414d", "#585e6e"]
            },
            "ember_ash": {
              "baseColor": "#8c3b2b",
              "steps": 5,
              "hueShift": 0.08,
              "valueSpread": 0.4
            }
          },
          "materials": [
            {
              "name": "basalt_cobble",
              "pattern": "COBBLESTONE",
              "palette": "basalt",
              "scale": 1.2,
              "detail": 0.7
            },
            {
              "name": "ash_ground",
              "pattern": "ORGANIC_FBM",
              "palette": "ember_ash",
              "accentColor": "#f98c1c"
            },
            {
              "name": "magma_flow",
              "pattern": "WATER_CAUSTICS",
              "palette": "lava_magma",
              "frames": 3
            },
            {
              "name": "obsidian_bricks",
              "pattern": "BRICK_MASONRY",
              "inlinePalette": {
                "colors": ["#0d0d12", "#1c1c28", "#2e2e42", "#464662"]
              }
            }
          ],
          "autotiles": [
            {
              "name": "ash_over_basalt",
              "mode": "CARDINAL_16",
              "foregroundMaterial": "ash_ground",
              "backgroundMaterial": "basalt_cobble",
              "style": {
                "margin": 3.0,
                "rimWidth": 1.5,
                "wobbleAmplitude": 2.0,
                "rimColor": "#141418"
              }
            },
            {
              "name": "preset_grass_dirt",
              "mode": "BLOB_47",
              "terrainPair": "GRASS_ON_DIRT"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `json spec parses, round-trips, and validates cleanly`() {
        val spec = TilesetSpecJson.parse(sampleJson)
        assertEquals("volcanic_cavern", spec.name)
        assertEquals(99L, spec.seed)
        assertEquals(16, spec.tileSize)
        assertEquals(4, spec.columns)
        assertEquals(2, spec.palettes.size)
        assertEquals(4, spec.materials.size)
        assertEquals(2, spec.autotiles.size)

        val reserialized = TilesetSpecJson.toJson(spec)
        val reparsed = TilesetSpecJson.parse(reserialized)
        assertEquals(spec, reparsed)
    }

    @Test
    fun `json parser rejects malformed json with line and column context`() {
        val ex = assertThrows(SpecException::class.java) {
            TilesetSpecJson.parse("{\n  \"name\": \"bad\",\n  \"tileSize\": }\n")
        }
        assertTrue(ex.message!!.contains("line 3"))
    }

    @Test
    fun `spec validation catches unknown palettes, unknown autotile materials, and invalid tileSize`() {
        // Unknown palette
        assertThrows(SpecException::class.java) {
            TilesetSpec(
                name = "bad_palette",
                materials = listOf(
                    MaterialTileSpec("x", MaterialPattern.ORGANIC_FBM, palette = "nonexistent")
                )
            )
        }

        // Unknown foregroundMaterial in autotile
        assertThrows(SpecException::class.java) {
            TilesetSpec(
                name = "bad_autotile",
                materials = listOf(
                    MaterialTileSpec("dirt", MaterialPattern.ORGANIC_FBM, palette = "earth_dirt")
                ),
                autotiles = listOf(
                    AutotileLayerSpec(
                        name = "broken",
                        foregroundMaterial = "missing",
                        backgroundMaterial = "dirt"
                    )
                )
            )
        }

        // Odd tileSize
        assertThrows(SpecException::class.java) {
            TilesetSpec(
                name = "odd_size",
                tileSize = 15,
                materials = listOf(
                    MaterialTileSpec("dirt", MaterialPattern.ORGANIC_FBM, palette = "earth_dirt")
                )
            )
        }

        // Empty spec
        assertThrows(SpecException::class.java) {
            TilesetSpec(name = "empty")
        }
    }

    @Test
    fun `renderer produces deterministic tiles, animated frames, and autotiles`() {
        val first = TilesetSpecRenderer.renderJson(sampleJson)
        val second = TilesetSpecRenderer.renderJson(sampleJson)

        // 3 static materials + 3 animated frames = 6 named tiles
        assertEquals(6, first.tiles.size)
        assertEquals(
            listOf("basalt_cobble", "ash_ground", "magma_flow_f0", "magma_flow_f1", "magma_flow_f2", "obsidian_bricks"),
            first.tiles.map { it.name }
        )

        val sheet = first.sheet
        assertNotNull(sheet)
        assertEquals(64, sheet!!.width)  // 4 columns * 16px
        assertEquals(32, sheet.height) // 2 rows * 16px
        assertArrayEquals(sheet.pixels, second.sheet!!.pixels)

        // Autotile layers
        assertEquals(2, first.autotiles.size)
        val ashAuto = first.autotiles["ash_over_basalt"]!!
        assertEquals(AutotileMode.CARDINAL_16, ashAuto.mode)
        assertEquals(16, ashAuto.entries.size)
        assertArrayEquals(ashAuto.image.pixels, second.autotiles["ash_over_basalt"]!!.image.pixels)

        val blobAuto = first.autotiles["preset_grass_dirt"]!!
        assertEquals(AutotileMode.BLOB_47, blobAuto.mode)
        assertEquals(47, blobAuto.entries.size)
    }

    @Test
    fun `jsonSchema includes all patterns, autotile modes, terrain pairs, and palette presets`() {
        val schema = TilesetSpecJson.jsonSchema()
        MaterialPattern.entries.forEach { assertTrue(schema.contains(it.name), "Missing pattern ${it.name}") }
        AutotileMode.entries.forEach { assertTrue(schema.contains(it.name), "Missing mode ${it.name}") }
        TerrainPair.entries.forEach { assertTrue(schema.contains(it.name), "Missing pair ${it.name}") }
        SpecPalettes.presets.keys.forEach { assertTrue(schema.contains(it), "Missing preset $it") }
        // Schema itself must be valid JSON
        JsonParser(schema).parse()
    }

    @Test
    fun `cli schema and spec commands execute and export png and json files`(@TempDir tempDir: Path) {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val out = PrintStream(outBuf)
        val err = PrintStream(errBuf)

        assertEquals(0, TilesetCli.run(arrayOf("schema"), out, err))
        assertTrue(outBuf.toString().contains("\"title\": \"TilesetSpec\""))

        val specFile = tempDir.resolve("volcanic.json")
        Files.writeString(specFile, sampleJson)
        val exportDir = tempDir.resolve("exported")

        assertEquals(0, TilesetCli.run(arrayOf("spec", specFile.toString(), "--out", exportDir.toString(), "--split"), out, err))
        assertTrue(Files.isRegularFile(exportDir.resolve("volcanic_cavern.png")))
        assertTrue(Files.isRegularFile(exportDir.resolve("volcanic_cavern.json")))
        assertTrue(Files.isRegularFile(exportDir.resolve("tiles/basalt_cobble.png")))
        assertTrue(Files.isRegularFile(exportDir.resolve("autotile_ash_over_basalt/volcanic_cavern_ash_over_basalt.png")))
        assertTrue(Files.isRegularFile(exportDir.resolve("autotile_preset_grass_dirt/volcanic_cavern_preset_grass_dirt.png")))
    }
}
