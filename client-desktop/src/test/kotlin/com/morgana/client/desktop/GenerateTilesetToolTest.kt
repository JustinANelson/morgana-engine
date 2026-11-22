package com.morgana.client.desktop

import com.morgana.persistence.GameScaffolder
import com.morgana.server.assets.GameAssets
import com.morgana.tilegen.TilesetTheme
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GenerateTilesetToolTest {

    /** A throwaway scaffolded game with no art of its own. */
    private fun testGame(into: Path): Path = GameScaffolder.scaffold(into.resolve("game"), "Test Game", "test_game")

    @Test
    fun `generateAndExport produces a valid tileset that passes GameAssets scan`(@TempDir dir: Path) {
        val game = testGame(dir)
        val result = GenerateTilesetTool.generateAndExport(
            gameDir = game,
            tilesetId = 1,
            columns = 16,
            rows = 3,
            seed = 1337L
        )

        assertFalse(result.skipped)
        assertEquals(emptyList<String>(), result.errors)
        val written = result.writtenFile
        assertNotNull(written)
        assertTrue(Files.exists(written))

        val scan = GameAssets.scan(game, "test_game")
        assertTrue(scan.errors.isEmpty(), scan.errors.toString())
        assertTrue("tilesets/1.png" in scan.manifest.assets)
        assertEquals(512 to 96, GameAssets.pngSize(Files.readAllBytes(written)))
    }

    @Test
    fun `generateAndExport skips existing file unless forced`(@TempDir dir: Path) {
        val game = testGame(dir)
        val first = GenerateTilesetTool.generateAndExport(game, tilesetId = 1, force = false)
        assertFalse(first.skipped)

        val second = GenerateTilesetTool.generateAndExport(game, tilesetId = 1, force = false)
        assertTrue(second.skipped)

        val forced = GenerateTilesetTool.generateAndExport(game, tilesetId = 1, force = true)
        assertFalse(forced.skipped)
    }

    @Test
    fun `generateAndExport supports custom rows for expanded tilesets`(@TempDir dir: Path) {
        val game = testGame(dir)
        val result = GenerateTilesetTool.generateAndExport(
            gameDir = game,
            tilesetId = 2,
            columns = 16,
            rows = 6,
            seed = 42L
        )

        assertEquals(emptyList<String>(), result.errors)
        val scan = GameAssets.scan(game, "test_game")
        assertTrue(scan.errors.isEmpty(), scan.errors.toString())
        assertTrue("tilesets/2.png" in scan.manifest.assets)
        val written = result.writtenFile
        assertNotNull(written)
        assertEquals(512 to 192, GameAssets.pngSize(Files.readAllBytes(written)))
    }

    @Test
    fun `generateAndExport exports individual themed 32x32 tiles and bundles them`(@TempDir dir: Path) {
        val game = testGame(dir)
        val tilesOut = dir.resolve("farm-tiles")
        val farmResult = GenerateTilesetTool.generateAndExport(
            gameDir = game,
            tilesetId = 2,
            seed = 1337L,
            theme = TilesetTheme.FARM,
            exportTilesDir = tilesOut
        )

        assertEquals(emptyList<String>(), farmResult.errors)
        assertEquals(32, farmResult.exportedTiles.size)
        assertTrue(farmResult.exportedTiles.all { Files.exists(it) && GameAssets.pngSize(Files.readAllBytes(it)) == (32 to 32) })
        assertEquals(512 to 64, GameAssets.pngSize(Files.readAllBytes(farmResult.writtenFile!!)))

        val rebundled = GenerateTilesetTool.bundleDirectory(
            gameDir = game,
            tilesDir = tilesOut,
            tilesetId = 3,
            force = true
        )
        assertEquals(emptyList<String>(), rebundled.errors)
        assertEquals(512 to 64, GameAssets.pngSize(Files.readAllBytes(rebundled.writtenFile!!)))

        val scan = GameAssets.scan(game, "test_game")
        assertTrue(scan.errors.isEmpty(), scan.errors.toString())
        assertTrue("tilesets/2.png" in scan.manifest.assets)
        assertTrue("tilesets/3.png" in scan.manifest.assets)
    }

    @Test
    fun `generateAndExport supports dungeon theme`(@TempDir dir: Path) {
        val game = testGame(dir)
        val result = GenerateTilesetTool.generateAndExport(
            gameDir = game,
            tilesetId = 4,
            seed = 777L,
            theme = TilesetTheme.DUNGEON
        )
        assertEquals(emptyList<String>(), result.errors)
        assertEquals(512 to 64, GameAssets.pngSize(Files.readAllBytes(result.writtenFile!!)))
    }
}
