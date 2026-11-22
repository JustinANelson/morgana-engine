package com.morgana.server.assets

import com.morgana.protocol.realms.AssetKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Icons, paperdolls, projectiles and animations as game-owned single images, and what an older client is sent. */
class GameAssetsArtV2Test {

    private fun png(w: Int, h: Int): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()

    private fun write(dir: Path, rel: String, w: Int, h: Int) {
        val f = dir.resolve("assets").resolve(rel)
        Files.createDirectories(f.parent)
        Files.write(f, png(w, h))
    }

    @Test
    fun `single image folders are scanned with their kinds`(@TempDir dir: Path) {
        write(dir, "icons/1.png", 32, 32)
        write(dir, "paperdolls/101.png", 32, 32)
        write(dir, "projectiles/2.png", 16, 16)
        write(dir, "animations/3.png", 64, 64)
        write(dir, "sprites/5.png", 96, 128)
        val scan = GameAssets.scan(dir, "g")
        assertEquals(emptyList<String>(), scan.errors)
        assertEquals(AssetKind.ICON, scan.manifest.assets.getValue("icons/1.png").kind)
        assertEquals(AssetKind.PAPERDOLL, scan.manifest.assets.getValue("paperdolls/101.png").kind)
        assertEquals(AssetKind.PROJECTILE, scan.manifest.assets.getValue("projectiles/2.png").kind)
        assertEquals(AssetKind.ANIMATION, scan.manifest.assets.getValue("animations/3.png").kind)
        assertNotNull(scan.fileFor("icons/1.png"))
    }

    @Test
    fun `single images must be square and a sensible size`(@TempDir dir: Path) {
        write(dir, "icons/1.png", 32, 16)
        write(dir, "icons/2.png", 4, 4)
        write(dir, "icons/3.png", 512, 512)
        write(dir, "icons/4.png", 64, 64)
        val scan = GameAssets.scan(dir, "g")
        assertEquals(3, scan.errors.size, scan.errors.toString())
        assertEquals(setOf("icons/4.png"), scan.manifest.assets.keys)
    }

    @Test
    fun `an older client is sent only tilesets and sprites`(@TempDir dir: Path) {
        write(dir, "tilesets/1.png", 64, 64)
        write(dir, "sprites/5.png", 96, 128)
        write(dir, "icons/1.png", 32, 32)
        val scan = GameAssets.scan(dir, "g")
        assertEquals(setOf("tilesets/1.png", "sprites/5.png", "icons/1.png"), scan.manifestFor(artV2 = true).assets.keys)
        assertEquals(setOf("tilesets/1.png", "sprites/5.png"), scan.manifestFor(artV2 = false).assets.keys)
        assertTrue(GameAssets.scan(Files.createTempDirectory("empty"), "g").manifestFor(false).assets.isEmpty())
        assertNull(scan.fileFor("icons/9.png"))
    }

    @Test
    fun `animations allow horizontal strips of square frames and reject malformed strips`(@TempDir dir: Path) {
        write(dir, "animations/1.png", 160, 32)   // valid 5-frame 32x32 strip
        write(dir, "animations/2.png", 64, 64)    // valid single square
        write(dir, "animations/3.png", 100, 32)   // width not divisible by frame height
        write(dir, "animations/4.png", 16, 4)     // frame height below MIN_SINGLE (8)
        write(dir, "animations/5.png", 520, 8)    // 65 frames exceeds MAX_ANIMATION_FRAMES (64)
        val scan = GameAssets.scan(dir, "g")
        assertEquals(3, scan.errors.size, scan.errors.toString())
        assertEquals(setOf("animations/1.png", "animations/2.png"), scan.manifest.assets.keys)
    }

    @Test
    fun `paperdolls allow directional sprite-profile sheets alongside legacy squares`(@TempDir dir: Path) {
        val profile = com.morgana.domain.game.SpriteProfile.MODERN_TALL
        write(dir, "paperdolls/101.png", profile.sheetWidth, profile.sheetHeight)
        write(dir, "paperdolls/102.png", 32, 32) // legacy square still allowed
        write(dir, "paperdolls/103.png", 96, 128) // neither square nor matching MODERN_TALL
        val scan = GameAssets.scan(dir, "g", sprites = profile)
        assertEquals(1, scan.errors.size, scan.errors.toString())
        assertEquals(setOf("paperdolls/101.png", "paperdolls/102.png"), scan.manifest.assets.keys)
    }
}
