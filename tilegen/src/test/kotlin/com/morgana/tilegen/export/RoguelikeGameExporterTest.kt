package com.morgana.tilegen.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class RoguelikeGameExporterTest {

    @Test
    fun `roguelike export writes tilesets, sprites, paperdolls, icons, projectiles and animations deterministically`(@TempDir dir: Path) {
        val game = dir.resolve("roguelike")
        val spritesDir = game.resolve("assets/sprites")
        Files.createDirectories(spritesDir)
        val untouched = spritesDir.resolve("99.png")
        Files.write(untouched, byteArrayOf(7, 8, 9))

        val res = RoguelikeGameExporter.export(game)
        // 7 tilesets + 3 class bodies + 8 NPCs + 6 enemies + 16 paperdolls + 41 icons + 4 projectiles + 6 animations
        assertEquals(7 + 3 + 8 + 6 + 16 + 41 + 4 + 6, res.written.size)
        assertTrue(Files.readAllBytes(untouched).contentEquals(byteArrayOf(7, 8, 9)))

        val body = ImageIO.read(spritesDir.resolve("1.png").toFile())
        assertEquals(736, body.width)
        assertEquals(192, body.height)

        val cryptSheet = ImageIO.read(game.resolve("assets/tilesets/7.png").toFile())
        assertEquals(256, cryptSheet.width)
        assertEquals(64, cryptSheet.height)

        val anim = ImageIO.read(game.resolve("assets/animations/1.png").toFile())
        assertEquals(160, anim.width)
        assertEquals(32, anim.height)

        val icon = ImageIO.read(game.resolve("assets/icons/1.png").toFile())
        assertEquals(32, icon.width)
        assertEquals(32, icon.height)

        assertTrue(res.uiHeights.getValue(1) in 36..48, "class body height: ${res.uiHeights[1]}")
        assertTrue(res.uiHeights.getValue(106) in 26..48, "boss height: ${res.uiHeights[106]}")
    }

    @Test
    fun `parts can be exported selectively`(@TempDir dir: Path) {
        val res = RoguelikeGameExporter.export(dir, setOf(RoguelikeGameExporter.Part.TILESETS))
        assertEquals(7, res.written.size)
        assertFalse(Files.exists(dir.resolve("assets/sprites/1.png")))
    }
}
