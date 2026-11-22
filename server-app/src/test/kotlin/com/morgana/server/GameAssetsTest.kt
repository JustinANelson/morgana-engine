package com.morgana.server

import com.morgana.protocol.realms.AssetKind
import com.morgana.server.assets.GameAssets
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

/** Game-owned art: a game's `assets/` folder becomes a validated, content-addressed manifest. */
class GameAssetsTest {

    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out)
        return out.toByteArray()
    }

    private fun put(gameDir: Path, rel: String, bytes: ByteArray) {
        val file = gameDir.resolve("assets").resolve(rel)
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
    }

    @Test
    fun `a game without an assets folder is valid and empty`(@TempDir dir: Path) {
        val assets = GameAssets.scan(dir, "demo")
        assertTrue(assets.isEmpty)
        assertTrue(assets.errors.isEmpty())
        assertEquals("demo", assets.manifest.gameId)
    }

    @Test
    fun `valid tilesets and sprites produce a manifest with hashes kinds and sizes`(@TempDir dir: Path) {
        val tiles = png(512, 96)
        val sprite = png(96, 128)
        put(dir, "tilesets/1.png", tiles)
        put(dir, "sprites/7.png", sprite)

        val assets = GameAssets.scan(dir, "demo")
        assertTrue(assets.errors.isEmpty(), assets.errors.toString())
        val t = assets.manifest.assets.getValue("tilesets/1.png")
        assertEquals(AssetKind.TILESET, t.kind)
        assertEquals(tiles.size.toLong(), t.sizeBytes)
        assertEquals(GameAssets.sha256Hex(tiles), t.sha256)
        assertEquals(AssetKind.SPRITE, assets.manifest.assets.getValue("sprites/7.png").kind)
        assertNotNull(assets.fileFor("sprites/7.png"))
        assertNull(assets.fileFor("sprites/8.png"))
    }

    @Test
    fun `scanning is deterministic`(@TempDir dir: Path) {
        put(dir, "tilesets/2.png", png(64, 64))
        put(dir, "tilesets/1.png", png(32, 32))
        put(dir, "sprites/3.png", png(30, 40))
        assertEquals(GameAssets.scan(dir, "g").manifest, GameAssets.scan(dir, "g").manifest)
        assertEquals(listOf("sprites/3.png", "tilesets/1.png", "tilesets/2.png"), GameAssets.scan(dir, "g").manifest.assets.keys.toList())
    }

    @Test
    fun `a tileset whose sides are not multiples of 32 is rejected and left out`(@TempDir dir: Path) {
        put(dir, "tilesets/1.png", png(100, 64))
        val assets = GameAssets.scan(dir, "demo")
        assertTrue(assets.isEmpty)
        assertEquals(1, assets.errors.size)
        assertTrue("multiples of 32" in assets.errors.single() && "100x64" in assets.errors.single())
    }

    @Test
    fun `a sprite sheet must be 3 columns by 4 rows`(@TempDir dir: Path) {
        put(dir, "sprites/1.png", png(32, 32))
        put(dir, "sprites/2.png", png(96, 128))
        val assets = GameAssets.scan(dir, "demo")
        assertEquals(listOf("sprites/2.png"), assets.manifest.assets.keys.toList())
        assertTrue(assets.errors.single().startsWith("assets/sprites/1.png"))
    }

    @Test
    fun `bad names, non-PNG files and unknown folders are all reported together`(@TempDir dir: Path) {
        put(dir, "tilesets/grass.png", png(32, 32))
        put(dir, "tilesets/01.png", png(32, 32))
        put(dir, "tilesets/0.png", png(32, 32))
        put(dir, "tilesets/3.png", "not a png at all, definitely not".toByteArray())
        put(dir, "sounds/boom.ogg", ByteArray(4))
        val assets = GameAssets.scan(dir, "demo")
        assertTrue(assets.isEmpty)
        assertEquals(5, assets.errors.size, assets.errors.toString())
        assertTrue(assets.errors.any { "not a valid PNG" in it })
        assertTrue(assets.errors.any { "sounds" in it && "unknown" in it })
    }

    @Test
    fun `oversized sheets are rejected`(@TempDir dir: Path) {
        put(dir, "tilesets/1.png", png(GameAssets.MAX_DIMENSION + 32, 32))
        val assets = GameAssets.scan(dir, "demo")
        assertTrue(assets.isEmpty)
        assertTrue("larger than" in assets.errors.single())
    }

    @Test
    fun `pngSize reads the header only`() {
        assertEquals(64 to 32, GameAssets.pngSize(png(64, 32)))
        assertNull(GameAssets.pngSize(ByteArray(10)))
        assertNull(GameAssets.pngSize(ByteArray(40)))
    }

    @Test
    fun `pictures accept arbitrary PNG names as MISC assets`(@TempDir dir: Path) {
        val banner = png(128, 64)
        put(dir, "pictures/town_banner.png", banner)

        val assets = GameAssets.scan(dir, "demo")
        assertTrue(assets.errors.isEmpty(), assets.errors.toString())
        val entry = assets.manifest.assets.getValue("pictures/town_banner.png")
        assertEquals(AssetKind.MISC, entry.kind)
        assertEquals(banner.size.toLong(), entry.sizeBytes)
        assertNotNull(assets.fileFor("pictures/town_banner.png"))
    }

    @Test
    fun `declared SpriteProfile enforces cell x frames x directions and rescales legacy sheets`(@TempDir dir: Path) {
        val profile = com.morgana.domain.game.SpriteProfile.MODERN_TALL // 32x64 x 13 frames x 4 dirs = 416x256
        put(dir, "sprites/1.png", png(96, 128))
        val rejected = GameAssets.scan(dir, "demo", sprites = profile)
        assertTrue(rejected.isEmpty)
        assertTrue(rejected.errors.single().contains("416x256"))

        val rescaled = GameAssets.rescaleLegacySpriteSheet(png(96, 128), profile)
        put(dir, "sprites/1.png", rescaled)
        val accepted = GameAssets.scan(dir, "demo", sprites = profile)
        assertTrue(accepted.errors.isEmpty(), accepted.errors.toString())
        assertTrue(accepted.manifest.assets.containsKey("sprites/1.png"))
    }
}
