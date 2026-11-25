package com.morgana.server

import com.morgana.domain.access.AccessLevel
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The admin operations behind the REST API, against a real coordinator: accounts, characters, tickets, live changes and content. */
class AdminOpsTest {
    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
        fun lastAuth() = sent.filterIsInstance<ServerPacket.AuthResult>().lastOrNull()
    }

    private var n = 0

    private fun coordinator(dir: Path, devLogin: Boolean = true): MorganaWorldCoordinator =
        MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null }, packetLimits = PacketLimitSettings(enabled = false))
            .also { if (devLogin) it.devTickets = DevTickets(dir) }

    private fun enter(c: MorganaWorldCoordinator, ticket: String): FakeSession {
        val s = FakeSession("s-${n++}")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.ResumeSession(ticket, PROTOCOL_VERSION, emptyList()))
        return s
    }

    @Test
    fun `an account made without a password can only be entered with a ticket`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val made = c.adminCreateAccount("Robot", "", "test")
        assertTrue(made.ok, made.message)
        assertFalse(c.adminCreateAccount("robot", "", "test").ok, "names are unique, ignoring case")
        assertFalse(c.adminCreateAccount("x", "", "test").ok, "too short")
        assertEquals(AccessLevel.PLAYER, c.dataStore.accounts.findByUsername("Robot")!!.accessLevel)

        // Nobody knows its password: a password login with a guess fails.
        val guess = FakeSession("g")
        c.onSessionConnected(guess)
        c.handleClientPacket(guess, ClientPacket.Authenticate("Robot", "guess", false, protocolVersion = PROTOCOL_VERSION, capabilities = emptyList()))
        assertFalse(guess.lastAuth()?.success == true)

        // A character, then a ticket, then the world.
        assertTrue(c.adminCreateCharacter("Robot", "Bolt", 0, 0, "test").ok)
        val ticket = c.adminMintTicket("Robot", 60, "test")
        assertNotNull(ticket)
        val s = enter(c, ticket!!.ticket)
        assertTrue(s.lastAuth()?.success == true, s.lastAuth()?.message)
        assertEquals("Bolt", s.lastAuth()!!.character!!.name)
    }

    @Test
    fun `character creation follows the character screen's rules`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.adminCreateAccount("Robot", "", "t")
        assertFalse(c.adminCreateCharacter("Nobody", "Bolt", 0, 0, "t").ok, "no such account")
        assertFalse(c.adminCreateCharacter("Robot", "Bolt", 9999, 0, "t").ok, "no such class")
        assertFalse(c.adminCreateCharacter("Robot", "x", 0, 0, "t").ok, "bad name")
        assertTrue(c.adminCreateCharacter("Robot", "Bolt", 0, 0, "t").ok)
        assertFalse(c.adminCreateCharacter("Robot", "Bolt", 0, 0, "t").ok, "name taken")
        c.adminCreateAccount("Second", "", "t")
        assertFalse(c.adminCreateCharacter("Second", "Bolt", 0, 0, "t").ok, "name taken on another account")
        assertEquals(listOf("Bolt"), c.adminCharacters("Robot").map { it.name })
        assertEquals(1, c.adminCharacters(null).size)
        assertTrue(c.adminCharacters("Ghost").isEmpty())
        assertEquals(1, c.adminAccounts().first { it.username == "Robot" }.characters)
    }

    @Test
    fun `access can be raised below owner and owner is never granted or changed here`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.adminCreateAccount("Robot", "", "t")
        c.adminCreateCharacter("Robot", "Bolt", 0, 0, "t")
        assertTrue(c.adminSetAccess("Robot", "mapper", "t").ok)
        assertEquals(AccessLevel.MAPPER, c.dataStore.accounts.findByUsername("Robot")!!.accessLevel)
        assertEquals(AccessLevel.MAPPER, c.adminCharacters("Robot").let { c.dataStore.characters.findByName("Bolt")!!.accessLevel })
        assertFalse(c.adminSetAccess("Robot", "owner", "t").ok)
        assertFalse(c.adminSetAccess("Robot", "banana", "t").ok)
        assertFalse(c.adminSetAccess("Nobody", "player", "t").ok)
        val boss = OwnerPolicy(dir) { "Boss" }
        val c2 = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = boss, packetLimits = PacketLimitSettings(enabled = false))
        assertTrue(c2.adminCreateAccount("Boss", "", "t").ok)
        assertEquals(AccessLevel.OWNER, c2.dataStore.accounts.findByUsername("Boss")!!.accessLevel, "an owner-listed name starts as Owner")
        assertFalse(c2.adminSetAccess("Boss", "player", "t").ok, "an Owner is not demoted through the API")
    }

    @Test
    fun `tickets need dev login and an existing account`(@TempDir dir: Path) {
        val off = coordinator(dir, devLogin = false)
        off.adminCreateAccount("Robot", "", "t")
        assertNull(off.adminMintTicket("Robot", 60, "t"))
        val on = coordinator(dir)
        assertNull(on.adminMintTicket("Ghost", 60, "t"))
        assertNotNull(on.adminMintTicket("Robot", 60, "t"))
    }

    @Test
    fun `warp and give act on an online player and refuse bad targets`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.adminCreateAccount("Robot", "", "t")
        c.adminCreateCharacter("Robot", "Bolt", 0, 0, "t")
        assertFalse(c.adminWarp("Bolt", 1, 1, 1, "t").ok, "not online yet")
        val s = enter(c, c.adminMintTicket("Robot", 60, "t")!!.ticket)
        assertTrue(s.lastAuth()?.success == true)
        val mapId = c.simulation.maps.keys.first()
        val map = c.simulation.maps[mapId]!!
        assertFalse(c.adminWarp("Bolt", 99999, 1, 1, "t").ok)
        assertFalse(c.adminWarp("Bolt", mapId, map.width, 0, "t").ok, "outside the map")
        assertTrue(c.adminWarp("Bolt", mapId, 2, 3, "t").ok)
        assertEquals(2, c.simulation.players.values.first { it.name == "Bolt" }.position.tileX)
        val item = c.simulation.items.keys.first()
        assertFalse(c.adminGive("Bolt", 987654, 1, "t").ok)
        assertTrue(c.adminGive("Bolt", item, 2, "t").ok)
    }

    @Test
    fun `a content edit is validated and a refused one leaves the file as it was`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val listed = c.adminContentList("items")
        assertTrue(listed.isNotEmpty(), "the blank game has items")
        val first = listed.first()
        val file = c.dataStore.contentDir.resolve(first.file)
        val original = Files.readString(file)
        assertEquals(original, c.adminContentGet("items", first.id))

        val renamed = original.replaceFirst(Regex("\"name\"\\s*:\\s*\"[^\"]*\""), "\"name\":\"Renamed By Admin\"")
        assertTrue(c.adminContentPut("items", first.id, renamed, "t").ok)
        assertEquals("Renamed By Admin", c.simulation.items[first.id]!!.name, "the live world has it")

        assertFalse(c.adminContentPut("items", first.id, "not json", "t").ok)
        assertFalse(c.adminContentPut("items", first.id, "[1]", "t").ok)
        assertFalse(c.adminContentPut("items", first.id + 1000, """{"id":${first.id}}""", "t").ok, "id must match the address")
        assertFalse(c.adminContentPut("nonsense", 1, "{}", "t").ok)

        // Valid JSON that is not a valid item: refused, and the previous good file is back on disk and live.
        val good = Files.readString(file)
        val bad = c.adminContentPut("items", first.id, """{"id":${first.id},"name":"","unknownField":true}""", "t")
        assertFalse(bad.ok, bad.message)
        assertEquals(good, Files.readString(file), "the refused edit did not stay on disk")
        assertEquals("Renamed By Admin", c.simulation.items[first.id]!!.name)

        // A brand new document that is refused is removed again.
        val newId = 777
        assertFalse(c.adminContentPut("items", newId, """{"id":$newId,"name":""}""", "t").ok)
        assertFalse(Files.exists(c.dataStore.contentDir.resolve("items/item_777.json")))
    }
}
