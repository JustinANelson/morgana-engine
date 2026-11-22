package com.morgana.tilegen.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GameTilesetExporterTest {

    private val spec = """
        {
          "name": "mini", "seed": 5, "tileSize": 16, "columns": 4, "style": "CLASSIC_RPG",
          "palettes": { "gold": { "colors": ["#5a4a22", "#8a7234", "#b89a48", "#d8bc62", "#ecd88a"] } },
          "materials": [
            { "name": "grass", "pattern": "ORGANIC_FBM", "palette": "gold", "variants": 2 },
            { "name": "dirt", "pattern": "ORGANIC_FBM", "palette": "earth_dirt" }
          ],
          "props": [
            { "name": "sign", "kind": "SIGNPOST", "secondaryPalette": "timber_wood" },
            { "name": "wheat", "kind": "WHEAT_SHEAF", "primaryPalette": "gold" }
          ],
          "autotiles": [
            { "name": "dirt_on_grass", "mode": "BLOB_47", "columns": 8, "foregroundMaterial": "dirt", "backgroundMaterial": "grass" }
          ]
        }
    """.trimIndent()

    private fun game(dir: Path): Path {
        val specs = dir.resolve("specs")
        Files.createDirectories(specs)
        Files.writeString(specs.resolve("mini.json"), spec)
        return specs.resolve("mini.json")
    }

    @Test
    fun `writes owned sheets from the first id and an index that locates every tile prop and autotile cell`(@TempDir dir: Path) {
        val specFile = game(dir)
        val gameDir = dir.resolve("game")
        Files.createDirectories(gameDir.resolve("assets/tilesets"))
        Files.writeString(gameDir.resolve("assets/tilesets/2.png"), "not touched")

        val r = GameTilesetExporter.export(gameDir, listOf(specFile), firstId = 100)

        assertEquals(listOf(100, 101), r.written.map { it.fileName.toString().removeSuffix(".png").toInt() })
        assertEquals(102, r.nextFreeId)
        assertEquals("not touched", Files.readString(gameDir.resolve("assets/tilesets/2.png")), "ids below firstId are never touched")
        assertEquals(GameTilesetExporter.Placement(100, 0, 0), r.tiles["grass"])
        assertEquals(GameTilesetExporter.Placement(100, 1, 0), r.tiles["grass_v1"])
        assertEquals(GameTilesetExporter.Placement(100, 2, 0), r.tiles["dirt"])
        val sign = r.props.getValue("sign")
        assertEquals(1, sign.w)
        assertEquals(2, sign.h)
        assertTrue(sign.y >= 1, "multi-tile props are packed below the single tiles")
        assertEquals(47, r.autotiles.getValue("dirt_on_grass").cells.size)
        val index = Files.readString(gameDir.resolve("tilesets.index.json"))
        assertTrue(index.contains("\"ownedIds\": [100, 101]"))
        assertTrue(index.contains("\"dirt_on_grass\""))
    }

    @Test
    fun `is deterministic and removes owned sheets that no spec produces any more`(@TempDir dir: Path) {
        val specFile = game(dir)
        val gameDir = dir.resolve("game")
        GameTilesetExporter.export(gameDir, listOf(specFile), firstId = 100)
        val first = Files.readAllBytes(gameDir.resolve("assets/tilesets/100.png"))
        val index1 = Files.readString(gameDir.resolve("tilesets.index.json"))
        GameTilesetExporter.export(gameDir, listOf(specFile), firstId = 100)
        assertTrue(first.contentEquals(Files.readAllBytes(gameDir.resolve("assets/tilesets/100.png"))))
        assertEquals(index1, Files.readString(gameDir.resolve("tilesets.index.json")))

        Files.writeString(specFile, spec.substringBefore("\"autotiles\"").trimEnd().trimEnd(',') + "}")
        val r = GameTilesetExporter.export(gameDir, listOf(specFile), firstId = 100)
        assertEquals(1, r.removed.size)
        assertFalse(Files.exists(gameDir.resolve("assets/tilesets/101.png")))
    }

    @Test
    fun `animated frames sit side by side and never wrap onto the next row`(@TempDir dir: Path) {
        val specs = dir.resolve("specs")
        Files.createDirectories(specs)
        val file = specs.resolve("anim.json")
        Files.writeString(file, """
            { "name": "anim", "seed": 1, "tileSize": 16, "columns": 4, "style": "CLASSIC_RPG",
              "materials": [
                { "name": "a", "pattern": "ORGANIC_FBM", "palette": "earth_dirt" },
                { "name": "b", "pattern": "ORGANIC_FBM", "palette": "earth_dirt" },
                { "name": "sea", "pattern": "WATER_CAUSTICS", "palette": "ocean_water", "frames": 4 }
              ] }
        """.trimIndent())
        val r = GameTilesetExporter.export(dir.resolve("game"), listOf(file), firstId = 100)
        val frames = (0..3).map { r.tiles.getValue("sea_f$it") }
        assertEquals(listOf(0, 1, 2, 3), frames.map { it.x })
        assertTrue(frames.all { it.y == frames.first().y }, "all four frames on one row")
        assertEquals(1, frames.first().y, "the group moved to the next row instead of wrapping")
    }
}
