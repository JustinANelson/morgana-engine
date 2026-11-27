package com.morgana.tilegen.sprite

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.export.PngEncoder
import com.morgana.tilegen.material.PixelStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

class LpcPaperDollPreviewTest {

    @Test
    fun `renders native 64x64 LPC preview sheet`() {
        val gen = PaperDollSpriteGenerator(seed = 1337L, tileSize = 64, tileHeight = 64)
        val bareTheme = PaperDollThemes.NEUTRAL_THEME.copy(
            style = PixelStyle.MORGANA,
            proportions = PaperDollProportions.LPC_64X64
        )
        val bare = PaperDollLoadout(
            job = PaperDollJob.HERO,
            hairStyle = HairStyle.NOBLE_PARTED,
            headgear = HeadgearKind.NONE,
            weapon = WeaponKind.NONE,
            shield = ShieldKind.NONE,
            torsoStyle = TorsoStyle.TRAVELER,
            legsStyle = LegsStyle.TROUSERS,
            feetStyle = FeetStyle.BOOTS,
            gloves = GloveStyle.NONE,
            includeCape = false,
            theme = bareTheme
        )
        val knight = bare.copy(
            job = PaperDollJob.KNIGHT,
            headgear = HeadgearKind.IRON_HELMET,
            weapon = WeaponKind.KNIGHT_SWORD,
            shield = ShieldKind.CRUSADER_CROSS_SHIELD,
            torsoStyle = TorsoStyle.BRIGANDINE,
            legsStyle = LegsStyle.GREAVES,
            feetStyle = FeetStyle.SABATONS,
            gloves = GloveStyle.GAUNTLET,
            includeCape = true,
            theme = PaperDollTheme.fromJob(PaperDollJob.KNIGHT).copy(
                style = PixelStyle.MORGANA,
                proportions = PaperDollProportions.LPC_64X64
            )
        )

        val dirs = listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N)
        val cols = 8 // 4 walk frames (bare) + 4 slash frames (knight)
        val cell = 64
        val sheet = TileImage(cols * cell, dirs.size * cell)

        for ((r, dir) in dirs.withIndex()) {
            for (f in 0 until 4) {
                val (layers, _) = gen.generateLayeredFrame(bare, dir, PaperDollAction.WALK, f, PixelStyle.MORGANA)
                val comp = gen.compositeLayers(layers, dir, PaperDollAction.WALK, includeShadow = true)
                assertEquals(64, comp.width)
                assertEquals(64, comp.height)
                sheet.drawImage(comp, 0, 0, cell, cell, f * cell, r * cell)
            }
            for (f in 0 until 4) {
                val (layers, _) = gen.generateLayeredFrame(knight, dir, PaperDollAction.SLASH, f, PixelStyle.MORGANA)
                val comp = gen.compositeLayers(layers, dir, PaperDollAction.SLASH, includeShadow = true)
                sheet.drawImage(comp, 0, 0, cell, cell, (4 + f) * cell, r * cell)
            }
        }

        // Scale 3x with a subtle checkerboard background so outlines & transparency are easy to inspect
        val scale = 3
        val preview = TileImage(sheet.width * scale, sheet.height * scale)
        for (y in 0 until preview.height) {
            for (x in 0 until preview.width) {
                val tx = (x / (16 * scale))
                val ty = (y / (16 * scale))
                val bg = if ((tx + ty) % 2 == 0) 0x3A414EFF else 0x2F3540FF
                preview.setPixel(x, y, bg)
            }
        }
        for (y in 0 until sheet.height) {
            for (x in 0 until sheet.width) {
                val c = sheet.getPixel(x, y)
                if ((c and 0xFF) != 0) {
                    for (sy in 0 until scale) {
                        for (sx in 0 until scale) {
                            preview.drawPixel(x * scale + sx, y * scale + sy, c)
                        }
                    }
                }
            }
        }

        val outDir = Paths.get("build", "lpc-preview")
        Files.createDirectories(outDir)
        val outFile = outDir.resolve("lpc_preview.png")
        PngEncoder.writeToFile(preview, outFile)
        assertTrue(Files.exists(outFile))
    }
}
