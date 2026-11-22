package com.morgana.server

import com.morgana.domain.access.AccessLevel
import com.morgana.domain.chat.ChatStyle
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerConfig
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ProtocolCapabilities
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RolePlayChatServerTest {

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
        fun chat() = sent.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel != ChatChannel.SYSTEM }
        fun system() = sent.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == ChatChannel.SYSTEM }.map { it.message }
    }

    private fun coordinator(dir: Path, serverJson: String? = null): MorganaWorldCoordinator {
        if (serverJson != null) Files.writeString(ServerConfig.fileIn(dir), serverJson)
        return MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })
    }

    private var counter = 0
    private fun login(
        c: MorganaWorldCoordinator,
        name: String,
        capabilities: List<String> = ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES
    ): FakeSession {
        val s = FakeSession("s-$name-${counter++}")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 3, protocolVersion = PROTOCOL_VERSION, capabilities = capabilities))
        s.sent.clear()
        return s
    }

    private fun loginAs(c: MorganaWorldCoordinator, name: String, level: AccessLevel): FakeSession {
        val first = login(c, name)
        c.dataStore.accounts.findByUsername(name)!!.let { c.dataStore.accounts.save(it.copy(accessLevel = level)) }
        c.onSessionDisconnected(first)
        return login(c, name)
    }

    /** Moves a character to a tile on its current map without going through movement rules. */
    private fun place(c: MorganaWorldCoordinator, name: String, tileX: Int, tileY: Int, mapId: Int? = null) {
        val p = c.simulation.players.values.first { it.name == name }
        c.simulation.players[p.id] = p.copy(position = p.position.copy(mapId = mapId ?: p.position.mapId, tileX = tileX, tileY = tileY))
    }

    private fun command(c: MorganaWorldCoordinator, s: FakeSession, line: String) = c.handleClientPacket(s, ClientPacket.AdminSlashCommand(line))

    @Test
    fun `me sends a styled action to clients that know styles and folded text to older ones`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val aria = login(c, "Aria")
        val modern = login(c, "Bram")
        val old = login(c, "Cole", capabilities = listOf(ProtocolCapabilities.MAP_CACHE))

        command(c, aria, "/me draws her sword")

        val styled = modern.chat().single()
        assertEquals(ChatStyle.ACTION, styled.style)
        assertEquals(ChatChannel.EMOTE, styled.channel)
        assertEquals("Aria", styled.senderName)
        assertEquals("draws her sword", styled.message)
        assertTrue(styled.showOverheadBubble)
        val plain = old.chat().single()
        assertEquals(ChatStyle.NORMAL, plain.style)
        assertEquals("* Aria draws her sword", plain.message)
        assertEquals(1, aria.chat().size, "the speaker sees their own action")
    }

    @Test
    fun `chat box text in double brackets is out of character and has no bubble`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val aria = login(c, "Aria")
        val bram = login(c, "Bram")
        c.handleClientPacket(aria, ClientPacket.SendChat(ChatChannel.MAP, "((brb, dinner))"))
        val line = bram.chat().single()
        assertEquals(ChatStyle.OOC, line.style)
        assertEquals("brb, dinner", line.message)
        assertFalse(line.showOverheadBubble)
    }

    @Test
    fun `plain map chat is unchanged by default and limited by a configured say radius`(@TempDir dir: Path) {
        val open = coordinator(dir.resolve("open").also { Files.createDirectories(it) })
        val a = login(open, "Aria")
        val b = login(open, "Bram")
        place(open, "Aria", 1, 1)
        place(open, "Bram", 20, 20)
        open.handleClientPacket(a, ClientPacket.SendChat(ChatChannel.MAP, "Hello"))
        assertEquals(listOf("Hello"), b.chat().map { it.message })
        assertEquals(ChatStyle.NORMAL, b.chat().single().style)

        val ranged = coordinator(dir.resolve("ranged").also { Files.createDirectories(it) }, """{ "roleplay": { "sayRadius": 4 } }""")
        val a2 = login(ranged, "Aria")
        val near = login(ranged, "Bram")
        val far = login(ranged, "Cole")
        place(ranged, "Aria", 5, 5)
        place(ranged, "Bram", 8, 7)
        place(ranged, "Cole", 20, 5)
        ranged.handleClientPacket(a2, ClientPacket.SendChat(ChatChannel.MAP, "Over here"))
        assertEquals(listOf("Over here"), near.chat().map { it.message })
        assertTrue(far.chat().isEmpty(), "out of earshot")
        command(ranged, a2, "/shout Over HERE")
        assertEquals(listOf("Over HERE"), far.chat().map { it.message })
        assertEquals(ChatStyle.SHOUT, far.chat().single().style)
    }

    @Test
    fun `quiet speech reaches only those beside the speaker`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Aria")
        val beside = login(c, "Bram")
        val across = login(c, "Cole")
        place(c, "Aria", 5, 5)
        place(c, "Bram", 6, 5)
        place(c, "Cole", 15, 5)
        command(c, a, "/quiet meet me at the docks")
        assertEquals(ChatStyle.QUIET, beside.chat().single().style)
        assertTrue(across.chat().isEmpty())
    }

    @Test
    fun `only staff narrate, and narration shows no speaker`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val pat = login(c, "Pat")
        val gm = loginAs(c, "Mona", AccessLevel.MONITOR)
        command(c, pat, "/narrate The sky turns red.")
        assertTrue(pat.system().any { "Only staff" in it })
        assertTrue(gm.chat().isEmpty())

        command(c, gm, "/narrate Thunder rolls over the hills.")
        val line = pat.chat().single()
        assertEquals(ChatStyle.NARRATION, line.style)
        assertEquals("Narrator", line.senderName)
        assertEquals("", line.senderEntityId)
        assertFalse(line.showOverheadBubble)
    }

    @Test
    fun `a command without words shows usage and muted players stay silent`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val boss = loginAs(c, "Boss", AccessLevel.OWNER)
        val pat = login(c, "Pat")
        command(c, pat, "/me")
        assertTrue(pat.system().any { it.startsWith("Usage: /me") })

        command(c, boss, "/mute Pat 5m spam")
        boss.sent.clear()
        command(c, pat, "/me sneaks a word in")
        c.handleClientPacket(pat, ClientPacket.SendChat(ChatChannel.EMOTE, "waves"))
        assertTrue(boss.chat().isEmpty(), "a mute covers role-play speech too")
        assertTrue(pat.system().count { "You are muted" in it } == 2)
    }

    @Test
    fun `with the module off the commands are not role-play and emotes behave as before`(@TempDir dir: Path) {
        val c = coordinator(dir, """{ "modules": { "rolePlayChat": false } }""")
        val a = login(c, "Aria")
        val b = login(c, "Bram")
        command(c, a, "/me waves")
        assertTrue(b.chat().isEmpty())
        c.handleClientPacket(a, ClientPacket.SendChat(ChatChannel.EMOTE, "waves"))
        val line = b.chat().single()
        assertEquals(ChatStyle.NORMAL, line.style)
        assertEquals("waves", line.message)
    }
}
