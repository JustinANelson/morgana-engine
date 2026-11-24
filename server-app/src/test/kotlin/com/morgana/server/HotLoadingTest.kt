package com.morgana.server

import com.morgana.persistence.GameScaffolder
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ProtocolCapabilities
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** Generated art, scripts and rules reaching a running game: art is pushed to clients, game.json asks for a restart. */
class HotLoadingTest {
    private class Fake(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
    }

    private fun coordinator(dir: Path): MorganaWorldCoordinator {
        GameScaffolder.scaffold(dir, "Hot Test")
        return MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null }, packetLimits = PacketLimitSettings(enabled = false))
    }

    private fun join(c: MorganaWorldCoordinator, name: String): Fake {
        val s = Fake("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION, capabilities = ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES))
        return s
    }

    private fun png(width: Int, height: Int, shade: Int = 0): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xFF000000.toInt() or shade)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun put(dir: Path, rel: String, bytes: ByteArray) {
        val file = dir.resolve("assets").resolve(rel)
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
    }

    @Test
    fun `new and changed art is sent to connected players, unchanged art is not, and a bad file is reported`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        val player = join(c, "viewer")
        player.sent.clear()

        put(tmp, "tilesets/7.png", png(64, 64))
        assertEquals(listOf("tilesets/7.png"), c.refreshAssets())
        val update = player.sent.filterIsInstance<ServerPacket.GameArtUpdated>().single()
        assertEquals("tilesets/7.png", update.entry.path)
        assertTrue(update.pngBase64.isNotEmpty())
        assertTrue("tilesets/7.png" in update.manifest.assets)

        assertTrue(c.refreshAssets().isEmpty(), "nothing changed, nothing sent")
        assertEquals(1, player.sent.filterIsInstance<ServerPacket.GameArtUpdated>().size)

        put(tmp, "tilesets/7.png", png(64, 64, shade = 0x123456))
        assertEquals(listOf("tilesets/7.png"), c.refreshAssets(), "a changed file is sent again")

        put(tmp, "tilesets/8.png", png(100, 64)) // not a multiple of 32
        assertTrue(c.refreshAssets().isEmpty())
        val status = Files.readString(tmp.resolve(ReloadStatusFile.NAME))
        assertTrue(status.contains("tilesets/8.png"), status)
    }

    @Test
    fun `a changed game file is noted as needing a restart`(@TempDir tmp: Path) {
        val c = coordinator(tmp)
        assertFalse(c.noteGameFileChanged(), "as running")
        val gameFile = tmp.resolve("game.json")
        Files.writeString(gameFile, Files.readString(gameFile).replaceFirst("\"name\": \"Hot Test\"", "\"name\": \"Hot Test 2\""))
        assertTrue(c.noteGameFileChanged())
        assertTrue(Files.readString(tmp.resolve(ReloadStatusFile.NAME)).contains("\"restartRequired\": true"))
    }

    @Test
    fun `the watcher reports which kinds changed, follows new art folders and ignores dot-files`(@TempDir tmp: Path) {
        val scripts = tmp.resolve("scripts").also { Files.createDirectories(it) }
        val assets = tmp.resolve("assets").also { Files.createDirectories(it) }
        val seen = CopyOnWriteArrayList<Set<String>>()
        val latch = CountDownLatch(2)
        val trees = listOf(
            WatchedTree(ChangeKind.SCRIPTS, scripts, recursive = true) { it.endsWith(".lua") },
            WatchedTree(ChangeKind.ASSETS, assets, recursive = true)
        )
        ContentWatcher(trees, debounceMs = 150) { kinds -> seen += kinds; latch.countDown() }.start().use {
            Files.writeString(scripts.resolve("main.lua"), "-- hi")
            Thread.sleep(500)
            Files.writeString(assets.resolve(".hidden"), "x")
            Files.createDirectories(assets.resolve("sprites"))
            Thread.sleep(300)
            Files.write(assets.resolve("sprites/1.png"), byteArrayOf(1, 2, 3))
            assertTrue(latch.await(10, TimeUnit.SECONDS), "both bursts should be reported: $seen")
        }
        assertEquals(setOf(ChangeKind.SCRIPTS), seen[0])
        assertEquals(setOf(ChangeKind.ASSETS), seen[1], "a file in a folder created after the start is seen; the dot-file is not")
    }
}
