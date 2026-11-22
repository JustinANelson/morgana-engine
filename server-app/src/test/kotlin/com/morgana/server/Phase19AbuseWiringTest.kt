package com.morgana.server

import com.morgana.persistence.AddressRange
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.nio.file.Path

class Phase19AbuseWiringTest {

    private class FakeSession(
        override val sessionId: String,
        address: String,
        override val isLoopback: Boolean = false
    ) : ClientSession {
        override val remoteInetAddress: InetAddress? = AddressRange.literal(address)
        override var characterId: String? = null
        val outbox = mutableListOf<ServerPacket>()
        var closed = false
        override fun send(packet: ServerPacket) { outbox += packet }
        override fun close(reason: String) { closed = true }
        fun system() = outbox.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == ChatChannel.SYSTEM }.map { it.message }
    }

    private fun setup(dir: Path, strikesToBan: Int = 2): Pair<MorganaWorldCoordinator, ConnectionGate> {
        val c = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })
        val gate = ConnectionGate(ConnectionGateSettings(strikesToBan = strikesToBan, banMinutes = 30), banFile = dir.resolve("ip-bans.json"))
        c.connectionGate = gate
        gate.onBan = { c.disconnectBanned(it) }
        return c to gate
    }

    private fun connect(c: MorganaWorldCoordinator, id: String, address: String, loopback: Boolean = false): FakeSession =
        FakeSession(id, address, loopback).also { c.onSessionConnected(it) }

    private fun login(c: MorganaWorldCoordinator, s: FakeSession, name: String) =
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION))

    private fun failLogins(c: MorganaWorldCoordinator, s: FakeSession) =
        repeat(5) { c.handleClientPacket(s, ClientPacket.Authenticate("NoSuchUser", "bad", false, 1, protocolVersion = PROTOCOL_VERSION)) }

    @Test
    fun `repeated failed-login rounds ban the address and close its other live connections`(@TempDir dir: Path) {
        val (c, gate) = setup(dir)
        val bystander = connect(c, "same-ip-idle", "203.0.113.20")
        val elsewhere = connect(c, "other-ip", "203.0.113.21")

        val first = connect(c, "guess-1", "203.0.113.20")
        failLogins(c, first)
        assertTrue(first.closed, "five failures close the connection as before")
        assertTrue(gate.bans().isEmpty(), "one round is only a strike")
        assertFalse(bystander.closed)

        val second = connect(c, "guess-2", "203.0.113.20")
        failLogins(c, second)
        assertEquals(listOf("203.0.113.20"), gate.bans().map { it.key })
        assertTrue(bystander.closed, "banning closes every connection from that address")
        assertTrue(bystander.system().any { it.contains("temporarily banned") })
        assertFalse(elsewhere.closed, "other addresses are untouched")
        assertEquals(GateVerdict.BANNED, gate.admit(AddressRange.literal("203.0.113.20")!!))
    }

    @Test
    fun `a packet flood is a strike against the sender's address`(@TempDir dir: Path) {
        val (c, gate) = setup(dir, strikesToBan = 1)
        val flooder = connect(c, "flood", "203.0.113.30")
        login(c, flooder, "Flooder")
        repeat(300) { c.handleClientPacket(flooder, ClientPacket.SendChat(ChatChannel.MAP, "x")) }
        assertTrue(flooder.closed)
        assertEquals(listOf("203.0.113.30"), gate.bans().map { it.key })
        assertEquals("packet flood", gate.bans().single().reason)
    }

    @Test
    fun `loopback players are never struck however they behave`(@TempDir dir: Path) {
        val (c, gate) = setup(dir, strikesToBan = 1)
        val local = connect(c, "local", "127.0.0.1", loopback = true)
        failLogins(c, local)
        assertTrue(local.closed, "the per-connection rules still apply")
        assertTrue(gate.bans().isEmpty(), "but the machine's own address is never banned")
    }

    @Test
    fun `operators manage bans with slash commands and ordinary players cannot`(@TempDir dir: Path) {
        val (c, gate) = setup(dir)
        val owner = connect(c, "owner", "127.0.0.1", loopback = true)   // first loopback account becomes Owner
        login(c, owner, "Boss")
        val player = connect(c, "player", "203.0.113.40")
        login(c, player, "Pleb")
        val victim = connect(c, "victim", "203.0.113.41")

        c.handleClientPacket(player, ClientPacket.AdminSlashCommand("/ipban 203.0.113.41"))
        assertTrue(player.system().any { it.contains("permission") })
        assertTrue(gate.bans().isEmpty())

        c.handleClientPacket(owner, ClientPacket.AdminSlashCommand("/ipban 203.0.113.41 45 spamming chat"))
        assertEquals(listOf("203.0.113.41"), gate.bans().map { it.key })
        assertEquals("spamming chat", gate.bans().single().reason)
        assertTrue(owner.system().any { it.contains("Banned 203.0.113.41 for 45 minutes") }, owner.system().toString())
        assertTrue(victim.closed, "the banned address is disconnected immediately")

        c.handleClientPacket(owner, ClientPacket.AdminSlashCommand("/ipbans"))
        assertTrue(owner.system().any { it.startsWith("203.0.113.41:") && it.contains("spamming chat") }, owner.system().toString())

        c.handleClientPacket(owner, ClientPacket.AdminSlashCommand("/ipunban 203.0.113.41"))
        assertTrue(gate.bans().isEmpty())
        c.handleClientPacket(owner, ClientPacket.AdminSlashCommand("/ipunban 203.0.113.41"))
        assertTrue(owner.system().any { it.contains("is not banned") })

        // Typed into normal chat it works the same way.
        c.handleClientPacket(owner, ClientPacket.SendChat(ChatChannel.MAP, "/ipban 203.0.113.42 5"))
        assertEquals(listOf("203.0.113.42"), gate.bans().map { it.key })
    }

    @Test
    fun `bad ip commands explain themselves and never ban this machine`(@TempDir dir: Path) {
        val (c, gate) = setup(dir)
        val owner = connect(c, "owner", "127.0.0.1", loopback = true)
        login(c, owner, "Boss")
        for (bad in listOf("/ipban", "/ipban not-an-ip", "/ipban example.com 10", "/ipunban", "/ipunban nope")) {
            c.handleClientPacket(owner, ClientPacket.AdminSlashCommand(bad))
        }
        assertEquals(5, owner.system().count { it.startsWith("Usage:") }, owner.system().toString())
        c.handleClientPacket(owner, ClientPacket.AdminSlashCommand("/ipban 127.0.0.1 10"))
        assertTrue(owner.system().any { it.contains("exempt") })
        assertTrue(gate.bans().isEmpty())
    }

    @Test
    fun `without a connection gate the commands say so`(@TempDir dir: Path) {
        val c = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })
        val owner = connect(c, "owner", "127.0.0.1", loopback = true)
        login(c, owner, "Boss")
        c.handleClientPacket(owner, ClientPacket.AdminSlashCommand("/ipbans"))
        assertTrue(owner.system().any { it.contains("not active") })
    }
}
