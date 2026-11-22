package com.morgana.tilegen.whiteriver

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.cli.TilesetCli
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.spec.TilesetSpecRenderer
import com.morgana.tilegen.sprite.FacingDirection
import com.morgana.tilegen.sprite.SpriteAnimationClip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class WhiteRiverAssetsTest {

    @Test
    fun `player generator produces deterministic 4-directional 2-frame Time Fantasy sprites`() {
        val gen1 = WhiteRiverPlayerGenerator(seed = 1337L, tileSize = 16)
        val gen2 = WhiteRiverPlayerGenerator(seed = 1337L, tileSize = 16)

        val (sheet1, tiles1) = gen1.generateAllPlayersSheet(includeDirections = true, frameCount = 2, style = PixelStyle.TIME_FANTASY)
        val (sheet2, tiles2) = gen2.generateAllPlayersSheet(includeDirections = true, frameCount = 2, style = PixelStyle.TIME_FANTASY)

        assertEquals(WhiteRiverPlayerClass.entries.size * FacingDirection.entries.size * 2, tiles1.size)
        assertEquals(tiles1.size, tiles2.size)
        assertTrue(sheet1.pixels.contentEquals(sheet2.pixels))
        tiles1.forEach { nt ->
            assertEquals(16, nt.image.width)
            assertEquals(16, nt.image.height)
            assertTrue(hasVisiblePixels(nt.image), "Player tile ${nt.name} should not be empty")
        }
    }

    @Test
    fun `White River player animation sequences produce expected frame counts`() {
        val wrPlayers = WhiteRiverPlayerGenerator(seed = 1337L, tileSize = 16)
        for (clip in SpriteAnimationClip.entries) {
            val seq = wrPlayers.generateAnimationSequence("dk", WhiteRiverPlayerClass.DARK_KNIGHT, clip, FacingDirection.RIGHT)
            assertEquals(clip.defaultFrames, seq.frames.size)
        }
    }

    @Test
    fun `enemy generator produces all 28 White River monsters and world bosses`() {
        val gen = WhiteRiverEnemyGenerator(seed = 1337L, tileSize = 16)
        val (sheet, tiles) = gen.generateAllEnemiesSheet(frameCount = 2, style = PixelStyle.TIME_FANTASY)

        assertEquals(WhiteRiverEnemyKind.entries.size * 2, tiles.size)
        assertEquals(8 * 16, sheet.width)
        tiles.forEach { nt ->
            assertTrue(hasVisiblePixels(nt.image), "Enemy tile ${nt.name} should not be empty")
        }

        val kzarkaF0 = gen.generateEnemyFrame(WhiteRiverEnemyKind.KZARKA, 0, PixelStyle.TIME_FANTASY)
        val kzarkaF1 = gen.generateEnemyFrame(WhiteRiverEnemyKind.KZARKA, 1, PixelStyle.TIME_FANTASY)
        assertFalse(kzarkaF0.pixels.contentEquals(kzarkaF1.pixels), "Animated frames should differ")
    }

    @Test
    fun `item generator produces all 36 White River items including black stones and boss gear`() {
        val gen = WhiteRiverItemGenerator(seed = 1337L, tileSize = 16)
        val (sheet, tiles) = gen.generateAllItemsSheet(style = PixelStyle.TIME_FANTASY)

        assertEquals(WhiteRiverItemKind.entries.size, tiles.size)
        assertEquals(8 * 16, sheet.width)
        tiles.forEach { nt ->
            assertTrue(hasVisiblePixels(nt.image), "Item tile ${nt.name} should not be empty")
        }
    }

    @Test
    fun `skill generator produces 4-frame animated VFX for all 24 White River skills`() {
        val gen = WhiteRiverSkillGenerator(seed = 1337L, tileSize = 16)
        val (sheet, tiles) = gen.generateAllSkillsSheet(frameCount = 4, style = PixelStyle.TIME_FANTASY)

        assertEquals(WhiteRiverSkillKind.entries.size * 4, tiles.size)
        assertEquals(4 * 16, sheet.width)
        tiles.forEach { nt ->
            assertTrue(hasVisiblePixels(nt.image), "Skill frame ${nt.name} should not be empty")
        }

        val f0 = gen.generateSkillFrame(WhiteRiverSkillKind.BLACK_SPIRIT_RAGE, 0, 4, PixelStyle.TIME_FANTASY)
        val f3 = gen.generateSkillFrame(WhiteRiverSkillKind.BLACK_SPIRIT_RAGE, 3, 4, PixelStyle.TIME_FANTASY)
        assertFalse(f0.pixels.contentEquals(f3.pixels), "Skill frames f0 and f3 should animate")
    }

    @Test
    fun `cli whiteriver all exports players enemies items and skills`(@TempDir tempDir: Path) {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val code = TilesetCli.run(
            arrayOf("whiteriver", "all", "--out", tempDir.toString()),
            PrintStream(outBuf),
            PrintStream(errBuf)
        )
        assertEquals(0, code, "CLI error: $errBuf")
        assertTrue(Files.isRegularFile(tempDir.resolve("players/whiteriver_players_all.png")))
        assertTrue(Files.isRegularFile(tempDir.resolve("enemies/whiteriver_enemies_all.png")))
        assertTrue(Files.isRegularFile(tempDir.resolve("items/whiteriver_items_all.png")))
        assertTrue(Files.isRegularFile(tempDir.resolve("skills/whiteriver_skills_all.png")))
    }

    @Test
    fun `all 8 White River Time Fantasy zone specs render cleanly`() {
        val specNames = listOf(
            "wr_white_river_town.json",
            "wr_velia_coast.json",
            "wr_serendia_swamp.json",
            "wr_calpheon_citadel.json",
            "wr_trent_forest.json",
            "wr_mediah_canyon.json",
            "wr_valencia_desert.json",
            "wr_hexe_sanctuary.json"
        )
        for (name in specNames) {
            val path = Paths.get("specs", name)
            assertTrue(Files.isRegularFile(path), "Missing spec $path")
            val rendered = TilesetSpecRenderer.renderJson(Files.readString(path))
            assertTrue(rendered.tiles.isNotEmpty(), "Spec $name should produce tiles")
            assertTrue(rendered.autotiles.isNotEmpty(), "Spec $name should produce autotiles")
            assertTrue(rendered.props.isNotEmpty(), "Spec $name should produce props")
        }
    }

    private fun hasVisiblePixels(img: TileImage): Boolean =
        img.pixels.any { (it and 0xFF) > 0 }
}
