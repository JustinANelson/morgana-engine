package com.morgana.server.plugins

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.sim.WorldSimulation
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.PluginWindowButton
import com.morgana.protocol.PluginWindowDto
import com.morgana.protocol.PluginWindowInput
import com.morgana.protocol.ProtocolCapabilities
import com.morgana.protocol.ServerPacket
import com.morgana.server.ClientSession
import com.morgana.server.EventHookRegistry
import com.morgana.server.MorganaServerHook
import com.morgana.server.MorganaWorldCoordinator
import com.morgana.server.OwnerPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PluginUiTest {

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
    }

    private class Recorder : MorganaServerHook {
        override val pluginId = "stocks"
        val actions = mutableListOf<Pair<String, String>>()
        val closed = mutableListOf<String>()
        val messages = mutableListOf<Pair<String, Map<String, String>>>()
        override fun onPluginAction(character: CharacterState, windowId: String, buttonId: String, values: Map<String, String>, sim: WorldSimulation) {
            actions += windowId to buttonId
            lastValues = values
        }
        var lastValues: Map<String, String> = emptyMap()
        override fun onPluginWindowClosed(character: CharacterState, windowId: String, sim: WorldSimulation) { closed += windowId }
        override fun onPluginMessage(character: CharacterState, channel: String, payload: Map<String, String>, sim: WorldSimulation) { messages += channel to payload }
    }

    private val window = PluginWindowDto(
        "stocks:board", "Board",
        buttons = listOf(PluginWindowButton("buy", "Buy"), PluginWindowButton("off", "Off", enabled = false)),
        inputs = listOf(PluginWindowInput("qty", "How many", maxLength = 5))
    )

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })

    private fun login(c: MorganaWorldCoordinator, name: String, capabilities: List<String>): FakeSession {
        val s = FakeSession("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 3, protocolVersion = PROTOCOL_VERSION, capabilities = capabilities))
        return s
    }

    @Test
    fun `windows reach clients that advertise plugin ui and are tracked per player`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val modern = login(c, "Modern", ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES)
        val old = login(c, "Old", ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES - ProtocolCapabilities.PLUGIN_UI)
        val modernId = c.simulation.players.values.first { it.name == "Modern" }.id
        val oldId = c.simulation.players.values.first { it.name == "Old" }.id

        assertTrue(c.openPluginWindow(modernId, window))
        assertFalse(c.openPluginWindow(oldId, window), "a client without plugin_ui is not sent the window")
        assertTrue(modern.sent.any { it is ServerPacket.PluginWindow && it.window.id == "stocks:board" })
        assertTrue(old.sent.none { it is ServerPacket.PluginWindow })
        assertEquals(setOf("stocks:board"), c.openPluginWindowIds(modernId))
        assertTrue(c.sendPluginMessage(modernId, "stocks:tick", mapOf("a" to "1")))
        assertFalse(c.sendPluginMessage(oldId, "stocks:tick", emptyMap()))
        assertTrue(c.closePluginWindow(modernId, "stocks:board"))
        assertTrue(modern.sent.any { it is ServerPacket.PluginWindowClose })
        assertTrue(c.openPluginWindowIds(modernId).isEmpty())
    }

    @Test
    fun `button presses only count for open windows and enabled buttons`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val rec = Recorder()
        c.hookRegistry.register(rec)
        val s = login(c, "Modern", ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES)
        val id = c.simulation.players.values.single().id

        c.handleClientPacket(s, ClientPacket.PluginAction("stocks:board", "buy"))
        assertTrue(rec.actions.isEmpty(), "no window is open yet")

        c.openPluginWindow(id, window)
        c.handleClientPacket(s, ClientPacket.PluginAction("stocks:board", "buy"))
        c.handleClientPacket(s, ClientPacket.PluginAction("stocks:board", "off"))
        c.handleClientPacket(s, ClientPacket.PluginAction("stocks:board", "invented"))
        c.handleClientPacket(s, ClientPacket.PluginAction("other:window", "buy"))
        assertEquals(listOf("stocks:board" to "buy"), rec.actions)

        c.handleClientPacket(s, ClientPacket.PluginWindowClosed("stocks:board"))
        c.handleClientPacket(s, ClientPacket.PluginWindowClosed("stocks:board"))
        assertEquals(listOf("stocks:board"), rec.closed, "closing reports once")
        c.handleClientPacket(s, ClientPacket.PluginAction("stocks:board", "buy"))
        assertEquals(1, rec.actions.size, "a closed window takes no more presses")
    }

    @Test
    fun `input text reaches the hook cut to its limit and only for the window's own inputs`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val rec = Recorder()
        c.hookRegistry.register(rec)
        val s = login(c, "Modern", ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES)
        c.openPluginWindow(c.simulation.players.values.single().id, window)
        c.handleClientPacket(s, ClientPacket.PluginAction("stocks:board", "buy", mapOf("qty" to "1234567", "evil" to "x")))
        assertEquals(mapOf("qty" to "12345"), rec.lastValues)
        c.handleClientPacket(s, ClientPacket.PluginAction("stocks:board", "buy"))
        assertEquals(emptyMap<String, String>(), rec.lastValues, "a missing input is simply absent")
    }

    @Test
    fun `disabling a plugin closes the windows it opened`() {
        val closed = mutableListOf<String>()
        val bridge = object : PluginUiBridge {
            override fun sendMessage(characterId: String, channel: String, payload: Map<String, String>) = true
            override fun openWindow(characterId: String, window: PluginWindowDto) = true
            override fun closeWindow(characterId: String, windowId: String) = closed.add("$characterId/$windowId")
        }
        val dir = java.nio.file.Files.createTempDirectory("plugin-ui2")
        java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(dir.resolve("s.jar"))).use { out ->
            out.putNextEntry(java.util.jar.JarEntry(PluginManifest.FILE_NAME))
            out.write("""{"id":"stocks","apiVersion":$PLUGIN_API_VERSION,"main":"${RecordingPlugin::class.java.name}"}""".toByteArray())
            out.closeEntry()
        }
        val manager = PluginManager(dir, EventHookRegistry(), ui = bridge)
        manager.loadAll()
        val ctx = RecordingPlugin.lastContext!!
        ctx.openWindow("c1", window)
        ctx.openWindow("c2", window)
        ctx.closeWindow("c2", "stocks:board")
        closed.clear()
        manager.disable("stocks")
        assertEquals(listOf("c1/stocks:board"), closed, "only windows still open are closed")
    }

    @Test
    fun `plugin messages from a client reach hooks and oversize ones do not`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val rec = Recorder()
        c.hookRegistry.register(rec)
        val s = login(c, "Modern", ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES)
        c.handleClientPacket(s, ClientPacket.PluginMessage("stocks:order", mapOf("qty" to "3")))
        c.handleClientPacket(s, ClientPacket.PluginMessage("stocks:order", mapOf("qty" to "x".repeat(5000))))
        assertEquals(listOf("stocks:order" to mapOf("qty" to "3")), rec.messages)
    }

    @Test
    fun `a plugin can only use its own namespace and sane window sizes`() {
        val sent = mutableListOf<String>()
        val bridge = object : PluginUiBridge {
            override fun sendMessage(characterId: String, channel: String, payload: Map<String, String>) = sent.add("msg:$channel")
            override fun openWindow(characterId: String, window: PluginWindowDto) = sent.add("win:${window.id}")
            override fun closeWindow(characterId: String, windowId: String) = sent.add("close:$windowId")
        }
        val dir = java.nio.file.Files.createTempDirectory("plugin-ui")
        java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(dir.resolve("s.jar"))).use { out ->
            out.putNextEntry(java.util.jar.JarEntry(PluginManifest.FILE_NAME))
            out.write("""{"id":"stocks","apiVersion":$PLUGIN_API_VERSION,"main":"${RecordingPlugin::class.java.name}"}""".toByteArray())
            out.closeEntry()
        }
        val manager = PluginManager(dir, EventHookRegistry(), ui = bridge)
        manager.loadAll()
        val ctx = RecordingPlugin.lastContext!!
        assertTrue(ctx.openWindow("c1", window))
        assertTrue(ctx.sendMessage("c1", "stocks:tick"))
        assertTrue(ctx.closeWindow("c1", "stocks:board"))
        assertEquals(listOf("win:stocks:board", "msg:stocks:tick", "close:stocks:board"), sent)
        assertThrows(IllegalArgumentException::class.java) { ctx.openWindow("c1", window.copy(id = "other:board")) }
        assertThrows(IllegalArgumentException::class.java) { ctx.sendMessage("c1", "other:tick") }
        assertThrows(IllegalArgumentException::class.java) { ctx.openWindow("c1", window.copy(title = "t".repeat(500))) }
        manager.close()
    }
}
