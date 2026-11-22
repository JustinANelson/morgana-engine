package com.morgana.server

import com.morgana.domain.entity.HotbarBindingType
import com.morgana.domain.game.BlankGame
import com.morgana.domain.replay.ReplayPlayer
import com.morgana.domain.replay.SimInput
import com.morgana.domain.sim.MovementRules
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ReplayLogCodec
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.PartyActionType
import com.morgana.protocol.ServerPacket
import com.morgana.server.tools.replayLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Records a live coordinator session and replays it from the log against the same data directory. */
class Phase12ServerRecordingTest {

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        override fun send(packet: ServerPacket) {}
        override fun close(reason: String) {}
    }

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(MorganaDataStore(dir), ownerPolicy = OwnerPolicy(dir) { null })

    private fun login(c: MorganaWorldCoordinator, name: String): FakeSession {
        val s = FakeSession("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    private fun session(c: MorganaWorldCoordinator) {
        val a = login(c, "Alpha")
        val b = login(c, "Bravo")
        repeat(3) { c.stepServerTick() }
        for (dir in listOf(Direction.LEFT, Direction.UP, Direction.RIGHT, Direction.DOWN)) {
            c.handleClientPacket(a, ClientPacket.MoveInput(dir))
            repeat(MovementRules.GRID_MIN_STEP_TICKS) { c.stepServerTick() }
            c.handleClientPacket(a, ClientPacket.Attack)
        }
        c.handleClientPacket(a, ClientPacket.SetHotbarSlot(2, HotbarBindingType.ITEM, 1))
        c.handleClientPacket(a, ClientPacket.SwapInventorySlots(0, 1))
        c.handleClientPacket(b, ClientPacket.MoveInput(Direction.UP))
        repeat(40) { c.stepServerTick() }
        c.onSessionDisconnected(b)
        repeat(10) { c.stepServerTick() }
    }

    private fun freshReplaySim(dir: Path, seed: Long): WorldSimulation {
        val store = MorganaDataStore(dir)
        val sim = WorldSimulation(SeededRandomSource(seed), game = BlankGame.COMPILED)
        store.world.loadIntoSimulation(sim)
        store.social.guilds.findAll().forEach { sim.guilds[it.id] = it }
        return sim
    }

    @Test
    fun `a recorded server session replays to the identical final state`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.startRecording()
        session(c)
        val log = c.stopRecording()
        assertNotNull(log)
        log!!
        assertTrue(log.gaps.isEmpty(), "plain gameplay must be fully recorded, got ${log.gaps}")
        assertTrue(log.inputs.isNotEmpty())
        assertTrue(log.inputs.any { it is SimInput.SetPlayerState }, "hotbar/swap edits must be recorded")
        assertTrue(log.inputs.any { it is SimInput.Move } && log.inputs.any { it is SimInput.RemovePlayer })

        val result = ReplayPlayer.replay(freshReplaySim(dir, log.seed), log)
        assertTrue(result.ok, result.toString())

        // Same through the CLI entry point, via a file on disk.
        val file = dir.resolve("session.replay.json")
        ReplayLogCodec.write(file, log)
        assertEquals(0, replayLog(dir, file.toString()))
    }

    @Test
    fun `unrecorded coordinator actions flag the log as incomplete`(@TempDir dir: Path) {
        val c = coordinator(dir)
        c.startRecording()
        val a = login(c, "Alpha")
        c.handleClientPacket(a, ClientPacket.PartyAction(PartyActionType.INVITE, "Nobody"))
        c.reloadContent()
        val log = c.stopRecording()!!
        assertTrue("party action" in log.gaps, log.gaps.toString())
        assertTrue("content hot reload" in log.gaps, log.gaps.toString())
    }

    @Test
    fun `recording refuses to start once the world has activity and cannot start twice`(@TempDir dir: Path) {
        val c = coordinator(dir)
        login(c, "Early")
        assertThrows(IllegalStateException::class.java) { c.startRecording() }

        val fresh = coordinator(dir.resolve("other"))
        fresh.startRecording()
        assertThrows(IllegalStateException::class.java) { fresh.startRecording() }
        assertFalse(fresh.stopRecording() == null)
        assertEquals(null, fresh.stopRecording())
    }
}
