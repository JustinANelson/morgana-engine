package com.morgana.server.assets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class ArtCheckTest {

    private fun png(w: Int, h: Int) = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()

    private fun art(dir: Path, rel: String, w: Int = 32, h: Int = 32) {
        val f = dir.resolve("assets").resolve(rel); Files.createDirectories(f.parent); Files.write(f, png(w, h))
    }

    private fun data(dir: Path, rel: String, text: String) {
        val f = dir.resolve("data").resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, text)
    }

    @Test
    fun `art that content uses is clean and unused art is a warning`(@TempDir dir: Path) {
        data(dir, "items/item_001.json", """{"id": 1, "name": "Coin", "iconId": 4, "projectileSpriteId": 0}""")
        data(dir, "npcs/npc_001.json", """{"id": 1, "name": "Slime", "spriteId": 7}""")
        data(dir, "animations/anim_001.json", """{"id": 2, "name": "Spark"}""")
        art(dir, "icons/4.png"); art(dir, "icons/9.png")
        art(dir, "sprites/7.png", 96, 128); art(dir, "sprites/8.png", 96, 128)
        art(dir, "animations/2.png")
        val report = ArtCheck.check(dir, "g")
        assertTrue(report.isClean, report.errors.toString())
        assertEquals(2, report.warnings.size, report.warnings.toString())
        assertTrue(report.warnings.any { "icons/9.png" in it }, report.warnings.toString())
        assertTrue(report.warnings.any { "sprites/8.png" in it }, report.warnings.toString())
        assertEquals(2, report.counts.getValue(com.morgana.protocol.realms.AssetKind.ICON))
    }

    @Test
    fun `an icon or projectile only a spell uses is not reported as unused`(@TempDir dir: Path) {
        data(dir, "items/item_001.json", """{"id": 1, "name": "Coin", "iconId": 4}""")
        data(dir, "spells/spell_001.json", """{"id": 1, "name": "Fireball", "iconId": 12, "projectileSpriteId": 3}""")
        art(dir, "icons/4.png"); art(dir, "icons/12.png"); art(dir, "icons/13.png")
        art(dir, "projectiles/3.png")
        val report = ArtCheck.check(dir, "g")
        assertTrue(report.isClean, report.errors.toString())
        assertEquals(1, report.warnings.size, report.warnings.toString())
        assertTrue("icons/13.png" in report.warnings.single(), "only the icon nothing uses: ${report.warnings}")
    }

    @Test
    fun `broken art is an error and a game without art is fine`(@TempDir dir: Path) {
        assertTrue(ArtCheck.check(dir, "g").isClean)
        art(dir, "icons/1.png", 32, 20)
        val report = ArtCheck.check(dir, "g")
        assertEquals(1, report.errors.size)
        assertTrue(!report.isClean)
    }
}
