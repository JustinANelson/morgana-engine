package com.morgana.server

import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** A one-time dev ticket logs an existing account in over the resume packet, only on a server started with dev login. */
class DevLoginTest {
    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
        fun lastAuth() = sent.filterIsInstance<ServerPacket.AuthResult>().lastOrNull()
    }

    private var n = 0

    private fun coordinator(dir: Path, devLogin: Boolean): MorganaWorldCoordinator =
        MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null }, packetLimits = PacketLimitSettings(enabled = false))
            .also { if (devLogin) it.devTickets = DevTickets(dir) }

    private fun registerAndLeave(c: MorganaWorldCoordinator, name: String) {
        val s = FakeSession("s-${n++}")
        c.onSessionConnected(s)
        // No CHARACTER_SELECT capability: registering enters the world straight away.
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw-not-known-to-the-tester", true, protocolVersion = PROTOCOL_VERSION, capabilities = emptyList()))
        assertTrue(s.lastAuth()!!.success)
        c.onSessionDisconnected(s)
    }

    private fun resumeWith(c: MorganaWorldCoordinator, token: String): FakeSession {
        val s = FakeSession("s-${n++}")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.ResumeSession(token, PROTOCOL_VERSION, emptyList()))
        return s
    }

    @Test
    fun `a ticket logs the account in once without a password`(@TempDir dir: Path) {
        val c = coordinator(dir, devLogin = true)
        registerAndLeave(c, "Tester")
        val ticket = DevTickets(dir).mint("Tester")

        val s = resumeWith(c, ticket)
        assertTrue(s.lastAuth()!!.success, s.lastAuth()?.message)
        assertEquals("Tester", s.lastAuth()!!.character!!.name)
        assertNotNull(s.lastAuth()!!.resumeToken, "the session then carries on with a normal resume token")
        c.onSessionDisconnected(s)

        val again = resumeWith(c, ticket)
        assertFalse(again.lastAuth()!!.success, "a ticket works only once")
    }

    @Test
    fun `a server without dev login ignores tickets`(@TempDir dir: Path) {
        val c = coordinator(dir, devLogin = false)
        registerAndLeave(c, "Tester")
        val ticket = DevTickets(dir).mint("Tester")
        val s = resumeWith(c, ticket)
        assertFalse(s.lastAuth()!!.success)
        assertTrue(DevTickets(dir).redeem(ticket) == "Tester", "and the ticket is left untouched")
    }

    @Test
    fun `a ticket for a missing account is refused`(@TempDir dir: Path) {
        val c = coordinator(dir, devLogin = true)
        val s = resumeWith(c, DevTickets(dir).mint("Ghost"))
        assertFalse(s.lastAuth()!!.success)
    }
}
