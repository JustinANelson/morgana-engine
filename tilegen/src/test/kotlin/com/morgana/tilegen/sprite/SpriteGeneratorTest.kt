package com.morgana.tilegen.sprite

import com.morgana.tilegen.cli.TilesetCli
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelStyle
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class SpriteGeneratorTest {

    @Test
    fun `all 16 enemy kinds generate single frames with retro 8bit 4-color cap`() {
        val gen = SpriteGenerator(seed = 42L, tileSize = 16)
        assertEquals(16, EnemyKind.entries.size)
        for (kind in EnemyKind.entries) {
            val frames = gen.generateEnemyFrames(kind.name.lowercase(), kind, frameCount = 1, style = PixelStyle.RETRO_8BIT)
            assertEquals(1, frames.size)
            assertEquals(kind.name.lowercase(), frames[0].name)
            val f0 = frames[0].image
            assertEquals(16, f0.width)
            assertEquals(16, f0.height)

            val opaqueColors = f0.pixels.filter { (it and 0xFF) != 0 }.toSet()
            assertTrue(opaqueColors.size in 2..4, "Expected 2..4 colors for $kind, got ${opaqueColors.size}")
        }
    }

    @Test
    fun `enemy generator scales to 32x32 deterministically`() {
        val gen = SpriteGenerator(seed = 7L, tileSize = 32)
        val a = gen.generateEnemyFrame(EnemyKind.DEMON_LORD, frameIndex = 0, style = PixelStyle.RETRO_8BIT)
        val b = gen.generateEnemyFrame(EnemyKind.DEMON_LORD, frameIndex = 0, style = PixelStyle.RETRO_8BIT)
        assertEquals(32, a.width)
        assertEquals(32, a.height)
        assertArrayEquals(a.pixels, b.pixels)
    }

    @Test
    fun `cli enemy all command exports 8-wide sprite sheet and metadata`(@TempDir tempDir: Path) {
        val outDir = tempDir.resolve("enemies")
        val code = TilesetCli.run(
            arrayOf("enemy", "all", "--size", "16", "--frames", "2", "--style", "retro_8bit", "--out", outDir.toString(), "--split"),
            PrintStream(ByteArrayOutputStream()),
            PrintStream(ByteArrayOutputStream())
        )
        assertEquals(0, code)
        assertTrue(Files.isRegularFile(outDir.resolve("enemies_all.png")))
        assertTrue(Files.isRegularFile(outDir.resolve("enemies_all.json")))
        assertTrue(Files.isRegularFile(outDir.resolve("tiles/skeleton_f0.png")))
        assertTrue(Files.isRegularFile(outDir.resolve("tiles/lich_f1.png")))
    }

    @Test
    fun `all 8 player classes generate 4 directions in time fantasy style with 2x scaling`() {
        val gen16 = PlayerSpriteGenerator(seed = 11L, tileSize = 16)
        val gen32 = PlayerSpriteGenerator(seed = 11L, tileSize = 32)
        val voidOutline = PaletteRamp.rgba(0x2B, 0x26, 0x32)

        val (sheet, tiles) = gen16.generateAllPlayersSheet(includeDirections = true, frameCount = 1, style = PixelStyle.TIME_FANTASY)
        assertEquals(32, tiles.size)
        assertEquals(8 * 16, sheet.width)
        assertEquals(4 * 16, sheet.height)

        for (cls in PlayerClass.entries) {
            val p16 = gen16.generatePlayerFrame(cls, FacingDirection.DOWN, 0, PixelStyle.TIME_FANTASY)
            val p32 = gen32.generatePlayerFrame(cls, FacingDirection.DOWN, 0, PixelStyle.TIME_FANTASY)
            assertTrue(p16.pixels.any { it == voidOutline }, "$cls should use #2b2632 selective outline")
            assertEquals(p16.getPixel(8, 8), p32.getPixel(16, 16))
        }
    }

    @Test
    fun `cli player all command exports 8-wide 4-direction sheet`(@TempDir tempDir: Path) {
        val outDir = tempDir.resolve("players")
        val code = TilesetCli.run(
            arrayOf("player", "all", "--size", "16", "--frames", "1", "--directions", "--style", "time_fantasy", "--out", outDir.toString(), "--split"),
            PrintStream(ByteArrayOutputStream()),
            PrintStream(ByteArrayOutputStream())
        )
        assertEquals(0, code)
        assertTrue(Files.isRegularFile(outDir.resolve("players_all.png")))
        assertTrue(Files.isRegularFile(outDir.resolve("players_all.json")))
        assertTrue(Files.isRegularFile(outDir.resolve("tiles/knight_down.png")))
        assertTrue(Files.isRegularFile(outDir.resolve("tiles/necromancer_up.png")))
    }
}
