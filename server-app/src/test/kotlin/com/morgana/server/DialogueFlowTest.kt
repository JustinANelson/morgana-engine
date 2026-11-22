package com.morgana.server

import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.EventPage
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ProtocolCapabilities
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** A dialogue line pauses an event for a client that can say it was closed; any other client gets the whole event at once. */
class DialogueFlowTest {
    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
        fun lines() = sent.filterIsInstance<ServerPacket.OpenUiWindow>().map { it.dialogueText }
    }

    private fun enter(dir: Path, capabilities: List<String>): Pair<MorganaWorldCoordinator, FakeSession> {
        val c = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null }, packetLimits = PacketLimitSettings(enabled = false))
        val s = FakeSession("s-1")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate("Hero", "pw", true, protocolVersion = PROTOCOL_VERSION, capabilities = capabilities))
        val me = c.simulation.players.getValue(s.characterId!!)
        // An event on the tile the player stands on: a line, a step, another line, a last step.
        c.simulation.events.keys.toList().forEach { id -> if (c.simulation.events.getValue(id).mapId == me.position.mapId) c.simulation.events.remove(id) }
        c.simulation.events[900] = EventDefinition(
            900, "Two lines", me.position.mapId, me.position.tileX, me.position.tileY,
            pages = listOf(EventPage(commands = listOf(
                EventCommand.ShowDialogue("Mira", "One"), EventCommand.SetVariable("x", 1),
                EventCommand.ShowDialogue("Mira", "Two"), EventCommand.SetVariable("x", 2)
            )))
        )
        s.sent.clear()
        return c to s
    }

    private fun MorganaWorldCoordinator.x(s: FakeSession) = simulation.players.getValue(s.characterId!!).variables["x"]

    @Test
    fun `a client with the capability reads one line at a time`(@TempDir dir: Path) {
        val (c, s) = enter(dir, ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES + ProtocolCapabilities.DIALOGUE_FLOW)
        c.handleClientPacket(s, ClientPacket.InteractFacing)
        assertEquals(listOf("One"), s.lines())
        assertNull(c.x(s))

        c.handleClientPacket(s, ClientPacket.SelectDialogueChoice(900, -1))
        assertEquals(listOf("One", "Two"), s.lines())
        assertEquals(1, c.x(s))

        c.handleClientPacket(s, ClientPacket.SelectDialogueChoice(900, -1))
        assertEquals(2, c.x(s))
        assertTrue(c.simulation.pendingDialogues.isEmpty())
    }

    @Test
    fun `a client without it gets the whole event at once, as before`(@TempDir dir: Path) {
        val (c, s) = enter(dir, ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES)
        assertFalse(ProtocolCapabilities.DIALOGUE_FLOW in ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES)
        c.handleClientPacket(s, ClientPacket.InteractFacing)
        assertEquals(listOf("One", "Two"), s.lines())
        assertEquals(2, c.x(s))
        assertTrue(c.simulation.pendingDialogues.isEmpty())
    }

    @Test
    fun `disconnecting mid-monologue finishes remaining steps and persists them for next login`(@TempDir dir: Path) {
        val (c, s) = enter(dir, ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES + ProtocolCapabilities.DIALOGUE_FLOW)
        c.handleClientPacket(s, ClientPacket.InteractFacing)
        assertEquals(listOf("One"), s.lines())
        assertNull(c.x(s))

        c.onSessionDisconnected(s)
        assertTrue(c.simulation.pendingDialogues.isEmpty())

        val s2 = FakeSession("s-2")
        c.onSessionConnected(s2)
        c.handleClientPacket(s2, ClientPacket.Authenticate("Hero", "pw", true, protocolVersion = PROTOCOL_VERSION))
        assertEquals(2, c.x(s2))
    }
}
