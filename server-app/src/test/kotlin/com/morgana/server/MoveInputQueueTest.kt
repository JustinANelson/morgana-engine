package com.morgana.server

import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.MovementMode
import com.morgana.domain.spatial.WorldPosition
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Movement steps that the network delivers in bursts are held for a later tick instead of dropped (dropping them snapped
 * players back, which looked like jitter on internet connections), and every [ServerPacket.CharacterSync] says which
 * steps it already includes.
 */
class MoveInputQueueTest {

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
        fun lastSync() = sent.filterIsInstance<ServerPacket.CharacterSync>().last()
    }

    private fun step(seq: Int) = ClientPacket.MoveInput(Direction.RIGHT, MovementMode.SUB_TILE, deltaPixelsX = 8f, seq = seq)

    /** A logged-in player standing in the middle of an empty 16 x 16 map, with the clock past tick 0. */
    private fun setup(dir: Path): Triple<MorganaWorldCoordinator, FakeSession, String> {
        val c = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })
        c.simulation.registerMap(com.morgana.domain.map.MapDefinition(id = 2, name = "Field", width = 16, height = 16))
        val s = FakeSession("s-walker")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate("Walker", "pw", true, 3, protocolVersion = PROTOCOL_VERSION))
        val id = c.simulation.players.values.single().id
        c.simulation.players[id] = c.simulation.players.getValue(id).copy(position = WorldPosition.fromTile(2, 4, 8, Direction.RIGHT))
        repeat(3) { c.stepServerTick() }
        return Triple(c, s, id)
    }

    @Test
    fun `two steps in the same tick are both applied, the second on the next tick`(@TempDir dir: Path) {
        val (c, s, id) = setup(dir)
        val startX = c.simulation.players.getValue(id).position.pixelX
        c.handleClientPacket(s, step(1))
        c.handleClientPacket(s, step(2))
        assertEquals(startX + 8f, c.simulation.players.getValue(id).position.pixelX, "the first step is applied at once")
        assertEquals(1, s.lastSync().ackMoveSeq)

        c.stepServerTick()
        assertEquals(startX + 16f, c.simulation.players.getValue(id).position.pixelX, "the second step waited one tick instead of being dropped")
        assertEquals(2, s.lastSync().ackMoveSeq)
        assertEquals(startX + 16f, s.lastSync().character.position.pixelX)
    }

    @Test
    fun `a burst never moves the player faster than one step per tick, and an overflow is dropped but acknowledged`(@TempDir dir: Path) {
        val (c, s, id) = setup(dir)
        val startX = c.simulation.players.getValue(id).position.pixelX
        for (seq in 1..10) c.handleClientPacket(s, step(seq))
        // One applied now, six waiting, three over the capacity: dropped, but acknowledged so the client stops replaying them.
        assertEquals(startX + 8f, c.simulation.players.getValue(id).position.pixelX)
        for (tick in 1..6) {
            c.stepServerTick()
            assertEquals(startX + 8f * (1 + tick), c.simulation.players.getValue(id).position.pixelX, "tick $tick")
        }
        repeat(5) { c.stepServerTick() }
        assertEquals(startX + 8f * 7, c.simulation.players.getValue(id).position.pixelX, "nothing more was queued")
        assertEquals(10, s.lastSync().ackMoveSeq)
    }

    @Test
    fun `the queue keeps order, waits while too soon and drops beyond capacity`() {
        val q = MoveInputQueue(capacity = 2)
        assertEquals(1, q.offer(step(1), allowedNow = true)?.seq, "nothing waits and it may move: apply at once")
        assertNull(q.offer(step(2), allowedNow = false))
        assertNull(q.offer(step(3), allowedNow = true), "a new step goes behind the waiting one even when it could move")
        assertNull(q.offer(step(4), allowedNow = true), "over capacity")
        assertEquals(4, q.lastHandledSeq, "the dropped step counts as handled")
        assertNull(q.pollReady(allowedNow = false))
        assertEquals(2, q.pollReady(allowedNow = true)?.seq)
        assertEquals(3, q.pollReady(allowedNow = true)?.seq)
        assertNull(q.pollReady(allowedNow = true))
    }
}
