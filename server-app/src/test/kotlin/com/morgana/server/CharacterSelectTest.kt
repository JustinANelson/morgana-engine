package com.morgana.server

import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerConfig
import com.morgana.persistence.SessionConfig
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ProtocolCapabilities
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Several characters per account: the list after login, creating, choosing, deleting, resuming. */
class CharacterSelectTest {
    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        var closed = false
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) { closed = true }
        fun lastAuth() = sent.filterIsInstance<ServerPacket.AuthResult>().lastOrNull()
        fun lastList() = sent.filterIsInstance<ServerPacket.CharacterList>().lastOrNull()
    }

    private val withList = ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES + ProtocolCapabilities.CHARACTER_SELECT
    private var counter = 0

    private fun coordinator(dir: Path, maxCharacters: Int? = null): MorganaWorldCoordinator {
        if (maxCharacters != null) ServerConfig.save(dir, ServerConfig(session = SessionConfig(maxCharacters = maxCharacters)))
        return MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null }, packetLimits = PacketLimitSettings(enabled = false))
    }

    private fun connect(c: MorganaWorldCoordinator, caps: List<String> = withList, name: String = "Hero", register: Boolean = true): FakeSession {
        val s = FakeSession("s-${counter++}")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", register, protocolVersion = PROTOCOL_VERSION, capabilities = caps))
        return s
    }

    private fun MorganaWorldCoordinator.classId() = simulation.classes.keys.first()

    private fun connectAndCreate(c: MorganaWorldCoordinator, name: String = "Hero"): FakeSession {
        val s = connect(c, name = name)
        c.handleClientPacket(s, ClientPacket.CreateCharacter(name, c.classId()))
        return s
    }

    @Test
    fun `a new account with the capability gets an empty character list to pick name and class`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val first = connect(c)
        assertNull(first.lastAuth(), "not in the world yet until first character is created")
        val initialList = first.lastList()!!
        assertTrue(initialList.characters.isEmpty())
        assertEquals(3, initialList.maxCharacters)
        assertTrue(initialList.classes.isNotEmpty())
        assertNull(first.characterId)

        c.handleClientPacket(first, ClientPacket.CreateCharacter("Hero", c.classId()))
        assertTrue(first.lastAuth()!!.success, "creating the first character enters the world")
        assertEquals("Hero", first.lastAuth()!!.character!!.name)
        c.onSessionDisconnected(first)

        val again = connect(c)
        assertNull(again.lastAuth(), "not in the world yet")
        val list = again.lastList()!!
        assertEquals(listOf("Hero"), list.characters.map { it.name })
        assertEquals(3, list.maxCharacters)
        assertTrue(list.classes.isNotEmpty())
        assertNull(again.characterId)
    }

    @Test
    fun `a client without the capability is logged into the first character as before`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.onSessionDisconnected(connect(c, ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES))
        val again = connect(c, ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES)
        assertTrue(again.lastAuth()!!.success)
        assertNull(again.lastList())
    }

    @Test
    fun `a realm that allows one character per account never shows the list`(@TempDir dir: Path) {
        val c = coordinator(dir, maxCharacters = 1)
        c.onSessionDisconnected(connect(c))
        val again = connect(c)
        assertTrue(again.lastAuth()!!.success)
        assertNull(again.lastList())
    }

    @Test
    fun `creating a character checks the name, the limit and the class, then plays it`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.onSessionDisconnected(connect(c, name = "Rival"))
        c.onSessionDisconnected(connectAndCreate(c))
        val s = connect(c)
        val cls = c.classId()

        fun create(name: String, classId: Int = cls) = c.handleClientPacket(s, ClientPacket.CreateCharacter(name, classId))
        create("x")
        assertTrue(s.lastList()!!.message.contains("2 to 20"), s.lastList()!!.message)
        create("Bad!Name")
        assertTrue(s.lastList()!!.message.contains("letters"))
        create("hero")
        assertEquals("That name is taken.", s.lastList()!!.message, "another character may not share a name, even in another case")
        create("rival")
        assertEquals("That name is taken.", s.lastList()!!.message, "nor take another player's account name")
        create("Second", classId = 9999)
        assertEquals("Pick a class.", s.lastList()!!.message)
        assertNull(s.characterId)

        create("Second")
        assertTrue(s.lastAuth()!!.success)
        assertEquals("Second", s.lastAuth()!!.character!!.name)
        assertNotNull(s.characterId)
        val account = c.dataStore.accounts.findByUsername("Hero")!!
        assertEquals(2, account.characterIds.size)
        assertEquals(account.id, c.dataStore.characters.findByName("Second")!!.accountId)
        c.onSessionDisconnected(s)

        // The limit applies: the list now has two; a third is allowed, a fourth is not.
        val t = connect(c)
        c.handleClientPacket(t, ClientPacket.CreateCharacter("Third", cls))
        assertTrue(t.lastAuth()!!.success)
        c.onSessionDisconnected(t)
        val u = connect(c)
        c.handleClientPacket(u, ClientPacket.CreateCharacter("Fourth", cls))
        assertTrue(u.lastList()!!.message.contains("most characters"), u.lastList()!!.message)
        assertEquals(3, u.lastList()!!.characters.size)
    }

    @Test
    fun `choosing plays that character and the resume token remembers it`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.onSessionDisconnected(connectAndCreate(c))
        val s = connect(c)
        c.handleClientPacket(s, ClientPacket.CreateCharacter("Second", c.classId()))
        val secondId = s.characterId!!
        c.onSessionDisconnected(s)

        val t = connect(c)
        val firstId = t.lastList()!!.characters.first { it.name == "Hero" }.id
        c.handleClientPacket(t, ClientPacket.ChooseCharacter("not-mine"))
        assertEquals("That character was not found.", t.lastList()!!.message)
        c.handleClientPacket(t, ClientPacket.ChooseCharacter(firstId))
        assertEquals(firstId, t.characterId)
        c.handleClientPacket(t, ClientPacket.ChooseCharacter(secondId)) // already playing: refused, nothing changes
        assertEquals(firstId, t.characterId)

        // Drop the connection, then come back with the token: the same character, not the account's first.
        c.onSessionDisconnected(connect(c).also { c.handleClientPacket(it, ClientPacket.ChooseCharacter(secondId)) })
        val u = connect(c)
        c.handleClientPacket(u, ClientPacket.ChooseCharacter(secondId))
        val token = u.lastAuth()!!.resumeToken!!
        c.onSessionDisconnected(u)
        val r = FakeSession("resume")
        c.onSessionConnected(r)
        c.handleClientPacket(r, ClientPacket.ResumeSession(token, PROTOCOL_VERSION, withList))
        assertEquals("Second", r.lastAuth()!!.character!!.name)
    }

    @Test
    fun `choosing another character while one is in the world replaces it`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val first = connectAndCreate(c)
        val firstId = first.characterId!!
        val other = connect(c)
        c.handleClientPacket(other, ClientPacket.CreateCharacter("Second", c.classId()))
        assertTrue(other.lastAuth()!!.success)
        assertNull(first.characterId, "the first connection was ended")
        assertFalse(c.simulation.players.containsKey(firstId))
        assertTrue(first.sent.any { it is ServerPacket.SessionEnded })
    }

    @Test
    fun `deleting needs the name, an offline character and no guild`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.onSessionDisconnected(connectAndCreate(c))
        val s = connect(c)
        c.handleClientPacket(s, ClientPacket.CreateCharacter("Second", c.classId()))
        val secondId = s.characterId!!
        c.onSessionDisconnected(s)

        val t = connect(c)
        val firstId = t.lastList()!!.characters.first { it.name == "Hero" }.id
        c.handleClientPacket(t, ClientPacket.DeleteCharacter(firstId, "wrong"))
        assertTrue(t.lastList()!!.message.contains("Type the character's name"))
        assertEquals(2, t.lastList()!!.characters.size)

        val inGuild = c.dataStore.characters.findById(firstId)!!.copy(guildId = "guild-1")
        c.dataStore.characters.save(inGuild)
        c.handleClientPacket(t, ClientPacket.DeleteCharacter(firstId, "Hero"))
        assertTrue(t.lastList()!!.message.contains("guild"), t.lastList()!!.message)
        c.dataStore.characters.save(inGuild.copy(guildId = null))

        // Someone is playing the other character on another connection: it cannot be deleted from underneath them.
        val busy = connect(c)
        c.handleClientPacket(busy, ClientPacket.ChooseCharacter(secondId))
        assertNotNull(busy.characterId)
        c.handleClientPacket(t, ClientPacket.DeleteCharacter(secondId, "Second"))
        assertTrue(t.lastList()!!.message.contains("in the world"), t.lastList()?.message)
        c.onSessionDisconnected(busy)

        c.handleClientPacket(t, ClientPacket.DeleteCharacter(firstId, " hero "))
        assertEquals("Deleted Hero.", t.lastList()!!.message)
        assertEquals(listOf("Second"), t.lastList()!!.characters.map { it.name })
        assertNull(c.dataStore.characters.findById(firstId))
        assertEquals(listOf(secondId), c.dataStore.accounts.findByUsername("Hero")!!.characterIds)
        // The freed name can be used again.
        c.handleClientPacket(t, ClientPacket.CreateCharacter("Hero", c.classId()))
        assertEquals("Hero", t.lastAuth()!!.character!!.name)
    }

    @Test
    fun `an account with no characters can make one, and an old client is told why it cannot play`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.onSessionDisconnected(connectAndCreate(c))
        val s = connect(c)
        val id = s.lastList()!!.characters.single().id
        c.handleClientPacket(s, ClientPacket.DeleteCharacter(id, "Hero"))
        assertTrue(s.lastList()!!.characters.isEmpty())
        c.onSessionDisconnected(s)

        val old = connect(c, ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES)
        assertFalse(old.lastAuth()!!.success)
        assertTrue(old.lastAuth()!!.message.contains("no character"))

        val t = connect(c)
        assertTrue(t.lastList()!!.characters.isEmpty())
        c.handleClientPacket(t, ClientPacket.CreateCharacter("Fresh", c.classId()))
        assertEquals("Fresh", t.lastAuth()!!.character!!.name)
    }

    @Test
    fun `character requests before a successful sign-in are refused`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = FakeSession("anon")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.CreateCharacter("Sneaky", c.classId()))
        c.handleClientPacket(s, ClientPacket.ChooseCharacter("char-1"))
        c.handleClientPacket(s, ClientPacket.DeleteCharacter("char-1", "x"))
        assertEquals(3, s.sent.count { it is ServerPacket.AuthResult && !it.success })
        assertNull(c.dataStore.characters.findByName("Sneaky"))

        // A wrong password does not open the list either.
        c.onSessionDisconnected(connect(c))
        val bad = FakeSession("bad")
        c.onSessionConnected(bad)
        c.handleClientPacket(bad, ClientPacket.Authenticate("Hero", "wrong", false, protocolVersion = PROTOCOL_VERSION, capabilities = withList))
        assertNull(bad.lastList())
        c.handleClientPacket(bad, ClientPacket.DeleteCharacter("char-1", "Hero"))
        assertNull(bad.lastList())
    }
}
