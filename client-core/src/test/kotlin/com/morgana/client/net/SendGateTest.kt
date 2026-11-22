package com.morgana.client.net

import com.morgana.protocol.ClientPacket
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class SendGateTest {
    @Test
    fun `moves flow freely while few are un-acknowledged`() {
        val gate = SendGate()
        assertTrue(gate.movesAllowed(0, 1_000))
        assertTrue(gate.movesAllowed(9, 1_050))
    }

    @Test
    fun `moves stop at the cap and a probe step leaves every so often`() {
        val gate = SendGate(SendGatePolicy(maxUnackedMoves = 10, moveProbeMs = 400))
        assertTrue(gate.movesAllowed(10, 1_000), "first blocked step is a probe")
        assertFalse(gate.movesAllowed(10, 1_100))
        assertFalse(gate.movesAllowed(10, 1_300))
        assertTrue(gate.movesAllowed(10, 1_450), "a lost acknowledgement cannot freeze walking")
    }

    @Test
    fun `a ping unanswered for too long stalls the link and pauses actions`() {
        val gate = SendGate(SendGatePolicy(stallAfterMs = 1_500))
        assertTrue(gate.pingDue(10_000))
        assertTrue(gate.actionAllowed("attack", 10_500))
        assertFalse(gate.isStalled(11_000))
        assertTrue(gate.isStalled(11_600))
        assertFalse(gate.actionAllowed("attack", 11_600))
        assertFalse(gate.actionAllowed("spell:1", 11_700))
        assertEquals(2, gate.takeDroppedCount())
        assertEquals(0, gate.takeDroppedCount())
    }

    @Test
    fun `an answered ping clears the stall and records the round trip`() {
        val gate = SendGate()
        gate.pingDue(10_000)
        gate.pong(now = 12_000, clientTimestampMs = 10_000)
        assertFalse(gate.isStalled(12_000))
        assertEquals(2_000, gate.rttMs)
        assertTrue(gate.actionAllowed("attack", 12_100))
    }

    @Test
    fun `pings are spaced and an outstanding ping keeps its original start`() {
        val gate = SendGate(SendGatePolicy(pingIntervalMs = 2_000, stallAfterMs = 1_500))
        assertTrue(gate.pingDue(10_000))
        assertFalse(gate.pingDue(11_000))
        assertTrue(gate.pingDue(12_000))
        assertTrue(gate.isStalled(12_000), "the stall is measured from the first unanswered ping")
    }

    @Test
    fun `the same action cannot be sent faster than the minimum gap`() {
        val gate = SendGate(SendGatePolicy(minActionGapMs = 120))
        assertTrue(gate.actionAllowed("attack", 1_000))
        assertFalse(gate.actionAllowed("attack", 1_050))
        assertTrue(gate.actionAllowed("spell:2", 1_050), "other actions are independent")
        assertTrue(gate.actionAllowed("attack", 1_130))
    }

    @Test
    fun `only combat and interaction packets are gated`() {
        assertEquals("attack", SendGate.actionKind(ClientPacket.Attack))
        assertEquals("spell:7", SendGate.actionKind(ClientPacket.CastSpell(spellId = 7)))
        assertNull(SendGate.actionKind(ClientPacket.Ping(1L)))
    }

    @Test
    fun `the gated transport drops attacks while stalled and forwards everything else`() {
        val sent = mutableListOf<ClientPacket>()
        val inner = object : ClientTransport {
            override val isConnected = true
            override val isEncrypted = false
            override fun connectAsync(host: String, port: Int, security: ClientSecurity, onResult: (ConnectResult) -> Unit) {}
            override fun send(packet: ClientPacket) { sent += packet }
            override fun drainPackets(maxPerFrame: Int, consumer: (ServerPacket) -> Unit) { consumer(ServerPacket.Pong(10_000, 5)) }
            override fun close() {}
        }
        var now = 10_000L
        val gate = SendGate(SendGatePolicy(stallAfterMs = 1_500))
        val transport = GatedTransport(inner, gate) { now }

        gate.pingDue(now)
        now = 12_000
        transport.send(ClientPacket.Attack)
        transport.send(ClientPacket.Ping(now))
        assertEquals(listOf<ClientPacket>(ClientPacket.Ping(12_000)), sent, "the stalled link drops the attack but still pings")

        transport.drainPackets { }
        assertFalse(gate.isStalled(now), "a pong clears the stall")
        transport.send(ClientPacket.Attack)
        assertEquals(2, sent.size)
    }

    @Test
    fun `a backlog of unsent bytes stalls the link even when pings were answered`() {
        val gate = SendGate(SendGatePolicy(maxBufferedBytes = 1_000))
        assertTrue(gate.actionAllowed("attack", 1_000))
        gate.bufferedBytes = 5_000
        assertTrue(gate.isStalled(1_200))
        assertFalse(gate.actionAllowed("attack", 2_000))
        assertEquals(1, gate.takeDroppedCount())
        gate.bufferedBytes = 0
        assertFalse(gate.isStalled(2_100))
        assertTrue(gate.actionAllowed("attack", 2_200))
    }

    @Test
    fun `reset clears the buffered backlog`() {
        val gate = SendGate(SendGatePolicy(maxBufferedBytes = 10))
        gate.bufferedBytes = 100
        gate.reset()
        assertFalse(gate.isStalled(0))
    }
}
