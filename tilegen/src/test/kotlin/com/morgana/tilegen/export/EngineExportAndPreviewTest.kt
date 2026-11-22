package com.morgana.tilegen.export

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.autotile.AutotileGenerator
import com.morgana.tilegen.autotile.AutotileMode
import com.morgana.tilegen.autotile.TerrainPair
import com.morgana.tilegen.material.DecalKind
import com.morgana.tilegen.material.MaterialPattern
import com.morgana.tilegen.material.MaterialSpec
import com.morgana.tilegen.material.MaterialTileGenerator
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.material.TileDecals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class EngineExportAndPreviewTest {

    @Test
    fun `exportNamedSheet writes tiled tsx, godot tres, and interactive preview html`(@TempDir tempDir: Path) {
        val tiles = listOf(
            NamedTile("grass", TileImage(16, 16)),
            NamedTile("earth", TileImage(16, 16))
        )
        val sheet = TileImage(32, 16)
        val res = TilesetExporter.exportNamedSheet(
            name = "starter",
            sheet = sheet,
            tiles = tiles,
            tileSize = 16,
            columns = 2,
            outDir = tempDir
        )

        val tsx = res.tiledTsx!!
        val tres = res.godotTres!!
        val html = res.previewHtml!!
        assertTrue(Files.isRegularFile(tsx))
        assertTrue(Files.isRegularFile(tres))
        assertTrue(Files.isRegularFile(html))

        val tsxText = Files.readString(tsx)
        assertTrue(tsxText.contains("<tileset"))
        assertTrue(tsxText.contains("tilewidth=\"16\""))
        assertTrue(tsxText.contains("<property name=\"name\" value=\"grass\"/>"))

        val tresText = Files.readString(tres)
        assertTrue(tresText.contains("[gd_resource type=\"TileSet\" format=3]"))
        assertTrue(tresText.contains("texture_region_size = Vector2i(16, 16)"))
        assertTrue(tresText.contains("1:0/0 = 0"))

        val htmlText = Files.readString(html)
        assertTrue(htmlText.contains("image-rendering: pixelated"))
        assertTrue(htmlText.contains("starter.png"))
    }

    @Test
    fun `exportAutotile writes wangset tsx, terrain tres, and live autotile painter html`(@TempDir tempDir: Path) {
        val autotile = AutotileGenerator(seed = 42L, tileSize = 16)
            .generateTerrainAutotile(TerrainPair.GRASS_ON_DIRT, AutotileMode.BLOB_47)

        val res = TilesetExporter.exportAutotile(
            name = "grass_dirt_blob47",
            autotile = autotile,
            outDir = tempDir
        )

        assertNotNull(res.tiledTsx)
        assertNotNull(res.godotTres)
        assertNotNull(res.previewHtml)

        val tsxText = Files.readString(res.tiledTsx!!)
        assertTrue(tsxText.contains("<wangsets>"))
        assertTrue(tsxText.contains("<wangtile tileid=\"0\""))

        val tresText = Files.readString(res.godotTres!!)
        assertTrue(tresText.contains("terrain_set_0/mode = 0"))
        assertTrue(tresText.contains("terrains_peering_bit/top_side = 0"))

        val htmlText = Files.readString(res.previewHtml!!)
        assertTrue(htmlText.contains("BLOB_47"))
        assertTrue(htmlText.contains("Interactive Autotile Painter"))
    }

    @Test
    fun `ground clutter decals flower tuft, pebbles, and grass blades modify tiles deterministically`() {
        val base = MaterialTileGenerator(seed = 99L, tileSize = 16).generateTile(
            MaterialSpec("grass", MaterialPattern.ORGANIC_FBM, PalettePresets.TIME_FANTASY_GRASS, style = PixelStyle.TIME_FANTASY)
        )
        for (decal in listOf(DecalKind.FLOWER_TUFT, DecalKind.PEBBLES, DecalKind.GRASS_BLADES)) {
            val d1 = TileDecals.applyDecal(base, decal, seed = 123L, style = PixelStyle.TIME_FANTASY)
            val d2 = TileDecals.applyDecal(base, decal, seed = 123L, style = PixelStyle.TIME_FANTASY)
            assertTrue(d1.pixels.contentEquals(d2.pixels), "$decal should be deterministic")
            assertFalse(base.pixels.contentEquals(d1.pixels), "$decal should add visible clutter")
        }
    }

    @Test
    fun `exportNamedSheet detects frame sequences and emits Tiled animation tags JSON animations and HTML animation player`(@TempDir tempDir: Path) {
        val tiles = listOf(
            NamedTile("co_trojan_down_walk_f0", TileImage(16, 16)),
            NamedTile("co_trojan_down_walk_f1", TileImage(16, 16)),
            NamedTile("co_trojan_down_walk_f2", TileImage(16, 16)),
            NamedTile("co_trojan_down_walk_f3", TileImage(16, 16))
        )
        val sheet = TileImage(64, 16)
        val res = TilesetExporter.exportNamedSheet(
            name = "trojan_anim",
            sheet = sheet,
            tiles = tiles,
            tileSize = 16,
            columns = 4,
            outDir = tempDir
        )

        val tsxText = Files.readString(res.tiledTsx!!)
        assertTrue(tsxText.contains("<animation>"), "TSX should contain <animation> block")
        assertTrue(tsxText.contains("<frame tileid=\"0\" duration=\"150\"/>"))
        assertTrue(tsxText.contains("<frame tileid=\"3\" duration=\"150\"/>"))

        val jsonText = Files.readString(res.metadataJson)
        assertTrue(jsonText.contains("\"animations\": ["), "JSON metadata should contain animations array")
        assertTrue(jsonText.contains("\"name\": \"co_trojan_down_walk\""))

        val htmlText = Files.readString(res.previewHtml!!)
        assertTrue(htmlText.contains("Animation Preview"), "Preview HTML should include live Animation Preview panel")
        assertTrue(htmlText.contains("animCanvas"))
    }
}
