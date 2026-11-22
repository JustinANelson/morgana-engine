package com.morgana.server.plugins

import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import com.morgana.server.ClientSession
import com.morgana.server.MorganaWorldCoordinator
import com.morgana.server.OwnerPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class PluginCommandAndStorageTest {

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
        fun messages() = sent.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == ChatChannel.SYSTEM }.map { it.message }
    }

    @Test
    fun `storage persists, is private to its file and refuses oversize values`(@TempDir dir: Path) {
        val file = dir.resolve("p/storage.json")
        val s = PluginStorage(file)
        assertNull(s.get("a"))
        s.set("a", "1"); s.set("gold:x", "50"); s.set("gold:y", "7")
        assertEquals(listOf("gold:x", "gold:y"), s.keys("gold:"))
        val again = PluginStorage(file)
        assertEquals("50", again.get("gold:x"))
        assertTrue(again.remove("a"))
        assertFalse(again.remove("a"))
        assertThrows(IllegalArgumentException::class.java) { again.set("", "x") }
        assertThrows(IllegalArgumentException::class.java) { again.set("k", "x".repeat(PluginStorage.MAX_VALUE + 1)) }
        assertEquals(listOf("gold:x", "gold:y"), PluginStorage(file).keys())
    }

    @Test
    fun `an unreadable storage file is kept aside and the plugin starts empty`(@TempDir dir: Path) {
        val file = dir.resolve("storage.json")
        Files.writeString(file, "{ not json")
        val s = PluginStorage(file)
        assertNull(s.get("a"))
        assertTrue(Files.exists(dir.resolve("storage.broken.json")))
    }

    @Test
    fun `the plugin command is for maintainers and manages plugins`(@TempDir dir: Path) {
        val plugins = dir.resolve("plugins")
        Files.createDirectories(plugins)
        JarOutputStream(Files.newOutputStream(plugins.resolve("rec.jar"))).use { out ->
            out.putNextEntry(JarEntry(PluginManifest.FILE_NAME))
            out.write("""{"id":"rec","version":"1.0","apiVersion":$PLUGIN_API_VERSION,"main":"${RecordingPlugin::class.java.name}"}""".toByteArray())
            out.closeEntry()
        }
        val c = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { "Boss" })
        val manager = PluginManager(plugins, c.hookRegistry, flags = { c.simulation.modules }, messenger = c::messagePlayer)
        c.plugins = manager
        manager.loadAll()

        fun login(name: String): FakeSession {
            val s = FakeSession("s-$name")
            c.onSessionConnected(s)
            c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 3, protocolVersion = PROTOCOL_VERSION))
            return s
        }
        fun say(s: FakeSession, text: String) = c.handleClientPacket(s, ClientPacket.SendChat(ChatChannel.MAP, text))

        val boss = login("Boss")
        val other = login("Other")

        say(other, "/plugins")
        assertTrue(other.messages().any { "permission" in it })

        say(boss, "/plugins")
        assertTrue(boss.messages().any { it.startsWith("rec 1.0: enabled") }, boss.messages().toString())
        say(boss, "/plugin disable rec")
        assertTrue("Disabled rec." in boss.messages())
        assertTrue(c.hookRegistry.pluginIds().isEmpty())
        say(boss, "/plugin enable rec")
        assertTrue(boss.messages().any { it.startsWith("rec is enabled") })
        say(boss, "/plugin reload rec")
        assertTrue(boss.messages().any { it.startsWith("rec reloaded: enabled") })
        say(boss, "/plugin enable nope")
        assertTrue(boss.messages().any { "No plugin 'nope'" in it })
        manager.close()
    }
}
