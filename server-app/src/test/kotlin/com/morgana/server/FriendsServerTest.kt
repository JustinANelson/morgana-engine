package com.morgana.server

import com.morgana.domain.sim.OptionalModule
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerConfig
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.FriendActionType
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FriendsServerTest {
    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val outbox = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { outbox += packet }
        override fun close(reason: String) {}
        fun systemMessages() = outbox.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == ChatChannel.SYSTEM }.map { it.message }
        fun lastList() = outbox.filterIsInstance<ServerPacket.FriendList>().lastOrNull()?.friends
    }

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })

    private fun login(c: MorganaWorldCoordinator, name: String): FakeSession {
        val s = FakeSession("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    @Test
    fun `adding a friend lists them, and they are announced when they log in and out`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        val b = login(c, "Bravo")

        c.handleClientPacket(a, ClientPacket.FriendAction(FriendActionType.ADD, "bravo"))
        val entry = a.lastList()!!.single()
        assertEquals("Bravo", entry.name, "stored with the character's own spelling")
        assertTrue(entry.online)
        assertEquals(setOf("Bravo"), c.simulation.players.values.first { it.name == "Alpha" }.friendNames)

        c.onSessionDisconnected(b)
        assertTrue(a.systemMessages().any { it.contains("Bravo is now offline") }, a.systemMessages().toString())
        assertFalse(a.lastList()!!.single().online)

        login(c, "Bravo")
        assertTrue(a.systemMessages().any { it.contains("Bravo is now online") })
        assertTrue(a.lastList()!!.single().online)
    }

    @Test
    fun `the list is sent at login and bad requests are refused`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        login(c, "Bravo")
        c.handleClientPacket(a, ClientPacket.FriendAction(FriendActionType.ADD, "Nobody"))
        c.handleClientPacket(a, ClientPacket.FriendAction(FriendActionType.ADD, "Alpha"))
        c.handleClientPacket(a, ClientPacket.FriendAction(FriendActionType.REMOVE, "Bravo"))
        val said = a.systemMessages().joinToString(" | ")
        for (part in listOf("No character named", "cannot add yourself", "is not on your friends list")) assertTrue(said.contains(part), "missing $part in $said")

        c.handleClientPacket(a, ClientPacket.FriendAction(FriendActionType.ADD, "Bravo"))
        c.onSessionDisconnected(a)
        val a2 = login(c, "Alpha")
        assertEquals(listOf("Bravo"), a2.lastList()!!.map { it.name })
        assertTrue(a2.systemMessages().any { it.contains("Friends online: Bravo") })
    }

    @Test
    fun `with the friends module off the packets are refused`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig().withModules(disable = listOf(OptionalModule.FRIENDS)))
        val c = coordinator(dir)
        val a = login(c, "Alpha")
        login(c, "Bravo")
        c.handleClientPacket(a, ClientPacket.FriendAction(FriendActionType.ADD, "Bravo"))
        assertTrue(a.systemMessages().any { it.contains("Friends list is switched off") })
        assertTrue(c.simulation.players.values.first { it.name == "Alpha" }.friendNames.isEmpty())
    }
}
