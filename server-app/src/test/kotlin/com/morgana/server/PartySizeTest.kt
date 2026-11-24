package com.morgana.server

import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameplayTuning
import com.morgana.domain.sim.WorldSimulation
import com.morgana.persistence.GameDefinitionLoader
import com.morgana.persistence.GameScaffolder
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.PartyActionType
import com.morgana.protocol.ProtocolCapabilities
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** `tuning.maxPartySize`: the engine had no party limit, so a game sets one (0, the default, means none). */
class PartySizeTest {
    private class Fake(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
        fun said(text: String) = sent.filterIsInstance<ServerPacket.ChatBroadcast>().any { it.channel == ChatChannel.SYSTEM && text in it.message }
    }

    private fun coordinator(dir: Path, maxPartySize: Int): MorganaWorldCoordinator {
        GameScaffolder.scaffold(dir, "Party Test")
        val definition = GameDefinitionLoader.load(dir).game.definition.copy(tuning = GameplayTuning(maxPartySize = maxPartySize))
        return MorganaWorldCoordinator(
            MorganaDataStore(dir), simulation = WorldSimulation(game = CompiledGame.compile(definition)),
            ownerPolicy = OwnerPolicy(dir) { null }, packetLimits = PacketLimitSettings(enabled = false)
        )
    }

    private fun join(c: MorganaWorldCoordinator, name: String): Fake {
        val s = Fake("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION, capabilities = ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES))
        return s
    }

    private fun MorganaWorldCoordinator.invite(from: Fake, to: String) = handleClientPacket(from, ClientPacket.PartyAction(PartyActionType.INVITE, to))
    private fun MorganaWorldCoordinator.accept(who: Fake) = handleClientPacket(who, ClientPacket.PartyAction(PartyActionType.ACCEPT))

    @Test
    fun `a party stops growing at the game's limit`(@TempDir dir: Path) {
        val c = coordinator(dir, maxPartySize = 2)
        val lead = join(c, "lead")
        val second = join(c, "second")
        val third = join(c, "third")
        c.invite(lead, "second"); c.accept(second)
        val partyId = c.simulation.players.getValue(lead.characterId!!).partyId!!
        assertEquals(2, c.simulation.parties.getValue(partyId).memberCharacterIds.size)

        c.invite(lead, "third")
        assertTrue(lead.said("Your party is full (2 members)"), "the leader is told at once")
        assertEquals(null, c.simulation.pendingPartyInvites[third.characterId!!], "and no invitation is left waiting")
        assertEquals(2, c.simulation.parties.getValue(partyId).memberCharacterIds.size)
    }

    @Test
    fun `a stale invitation cannot push a party past the limit`(@TempDir dir: Path) {
        val c = coordinator(dir, maxPartySize = 2)
        val lead = join(c, "lead")
        val a = join(c, "alice")
        val b = join(c, "bob")
        c.invite(lead, "alice")
        c.invite(lead, "bob") // both invited while there was still room
        c.accept(a)
        c.accept(b) // by now the party is full
        assertTrue(b.said("That party is full (2 members)"))
        val partyId = c.simulation.players.getValue(lead.characterId!!).partyId!!
        assertEquals(2, c.simulation.parties.getValue(partyId).memberCharacterIds.size)
        assertEquals(null, c.simulation.players.getValue(b.characterId!!).partyId)
    }

    @Test
    fun `with no limit set a party grows as before`(@TempDir dir: Path) {
        val c = coordinator(dir, maxPartySize = 0)
        val lead = join(c, "lead")
        val names = listOf("anna", "bert", "cora", "dave")
        val others = names.map { join(c, it) }
        for ((name, o) in names.zip(others)) { c.invite(lead, name); c.accept(o) }
        val partyId = c.simulation.players.getValue(lead.characterId!!).partyId!!
        assertEquals(5, c.simulation.parties.getValue(partyId).memberCharacterIds.size)
    }
}
