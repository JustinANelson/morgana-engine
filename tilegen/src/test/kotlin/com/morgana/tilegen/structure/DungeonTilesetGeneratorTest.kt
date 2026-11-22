package com.morgana.tilegen.structure

import com.morgana.tilegen.cli.TilesetCli
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class DungeonTilesetGeneratorTest {

    @Test
    fun `generates deterministic 32x32 tileset bundle, multi-tile props, and showcase for all themes`() {
        for (theme in DungeonTheme.entries) {
            val bundle1 = DungeonTilesetGenerator(seed = 1337L, theme = theme).generateBundle()
            val bundle2 = DungeonTilesetGenerator(seed = 1337L, theme = theme).generateBundle()

            assertEquals(32, bundle1.tileSize)
            assertEquals(8, bundle1.columns)
            assertEquals(40, bundle1.tiles.size, "Expected 40 named 32x32 tiles for $theme")
            assertEquals(bundle1.tiles.size, bundle1.tiles.map { it.name }.toSet().size, "Tile names must be unique")
            assertTrue(bundle1.sheet.pixels.contentEquals(bundle2.sheet.pixels), "Sheet must be deterministic for $theme")
            assertTrue(bundle1.showcase.pixels.contentEquals(bundle2.showcase.pixels), "Showcase must be deterministic for $theme")
            assertEquals(16 * 32, bundle1.showcase.width)
            assertEquals(11 * 32, bundle1.showcase.height)

            assertTrue("lich_statue" in bundle1.props)
            assertTrue("sarcophagus_effigy" in bundle1.props)
            assertTrue("knight_statue" in bundle1.props)
            assertTrue("spectral_brazier" in bundle1.props)
            assertTrue("open_tomb_skeleton" in bundle1.props)
            assertTrue("hanging_skeleton" in bundle1.props)
            for ((name, img) in bundle1.props) {
                assertTrue(img.pixels.any { (it and 0xFF) >= 200 }, "Prop $name should have opaque pixels")
            }
        }
    }

    @Test
    fun `crypt cobble wall reads darker than warm flagstone floor`() {
        val gen = DungeonTilesetGenerator(seed = 77L, theme = DungeonTheme.CRYPT)
        val floor = gen.generateFloorTile(variant = 0)
        val wall = gen.generateCobbleWallFace(isLower = true)
        assertTrue(avgLuma(floor) > avgLuma(wall), "Flagstone floor should be lighter than cobble wall for readability")
    }

    @Test
    fun `cli dungeon command exports sheet, props, and showcase into temp dir`(@TempDir tempDir: Path) {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = TilesetCli.run(
            arrayOf("dungeon", "crypt", "--seed", "99", "--out", tempDir.toString()),
            PrintStream(out),
            PrintStream(err)
        )
        assertEquals(0, code, "CLI failed: ${err.toString(Charsets.UTF_8)}")
        assertTrue(Files.isRegularFile(tempDir.resolve("dungeon_crypt.png")))
        assertTrue(Files.isRegularFile(tempDir.resolve("dungeon_crypt.json")))
        assertTrue(Files.isRegularFile(tempDir.resolve("dungeon_crypt_showcase.png")))
        assertTrue(Files.isRegularFile(tempDir.resolve("props/lich_statue.png")))
        assertTrue(Files.isRegularFile(tempDir.resolve("props/sarcophagus_effigy.png")))
    }

    private fun avgLuma(img: com.morgana.tilegen.TileImage): Float {
        var sum = 0.0
        for (px in img.pixels) {
            val r = (px ushr 24) and 0xFF
            val g = (px ushr 16) and 0xFF
            val b = (px ushr 8) and 0xFF
            sum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        return (sum / img.pixels.size).toFloat()
    }
}
