package com.morgana.server

import com.morgana.domain.game.ClockDefinition
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerState
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class WorldEpochPersistenceTest {

    private class RecordingSession(override val sessionId: String = "session-1") : ClientSession {
        override var characterId: String? = null
        val outbox = mutableListOf<ServerPacket>()

        override fun send(packet: ServerPacket) {
            outbox += packet
        }

        override fun close(reason: String) {}
    }

    @Test
    fun `server preserves world epoch and calendar progression across restarts`(@TempDir dir: Path) {
        // Configure a game with a 1200-tick day and 7 days per season
        val gameDef = MorganaClassic.definition.copy(
            clock = ClockDefinition(dayLengthTicks = 1200, daysPerSeason = 7, startTimePermille = 0)
        )
        val compiledGame = CompiledGame.compile(gameDef)

        // Run 1: Advance 3 days worth of ticks (3600 ticks) and shut down
        val store1 = MorganaDataStore(rootDir = dir)
        val coord1 = MorganaWorldCoordinator(dataStore = store1, simulation = com.morgana.domain.sim.WorldSimulation(game = compiledGame))
        
        repeat(3600) { coord1.stepServerTick(50) }
        val clockBeforeRestart = coord1.simulation.clockNow()!!
        assertEquals(3L, clockBeforeRestart.day)
        assertEquals(4, clockBeforeRestart.dayOfSeason) // Day 4 of season 0 (Spring)

        coord1.close()
        store1.close()

        // Verify server-state.json was written
        val savedState = ServerState.load(dir)
        assertEquals(3600L, savedState.worldEpochTicks)

        // Run 2: Boot server from same directory
        val store2 = MorganaDataStore(rootDir = dir)
        val coord2 = MorganaWorldCoordinator(dataStore = store2, simulation = com.morgana.domain.sim.WorldSimulation(game = compiledGame))

        assertEquals(3600L, coord2.simulation.worldEpochTicks)
        assertEquals(0L, coord2.simulation.currentTick)
        assertEquals(3600L, coord2.simulation.effectiveWorldTick())

        val clockAfterRestart = coord2.simulation.clockNow()!!
        assertEquals(clockBeforeRestart.day, clockAfterRestart.day)
        assertEquals(clockBeforeRestart.dayOfSeason, clockAfterRestart.dayOfSeason)
        assertEquals(clockBeforeRestart.seasonName, clockAfterRestart.seasonName)

        // Connect client and verify serverTick delivers effective world tick
        val session = RecordingSession()
        coord2.handleClientPacket(session, ClientPacket.Authenticate("Hero", "pw12345", registerIfNew = true, protocolVersion = PROTOCOL_VERSION))
        val authRes = session.outbox.filterIsInstance<ServerPacket.AuthResult>().first()
        assertTrue(authRes.success)
        assertEquals(3600L, authRes.serverTick)

        // Advance further and verify accumulation
        repeat(1200) { coord2.stepServerTick(50) }
        assertEquals(4800L, coord2.simulation.effectiveWorldTick())
        val clockDay5 = coord2.simulation.clockNow()!!
        assertEquals(4L, clockDay5.day)
        assertEquals(5, clockDay5.dayOfSeason)

        coord2.close()
        store2.close()

        val finalState = ServerState.load(dir)
        assertEquals(4800L, finalState.worldEpochTicks)
    }

    @Test
    fun `world switches and world variables survive server restarts`(@TempDir dir: Path) {
        val compiledGame = CompiledGame.compile(MorganaClassic.definition)
        val store1 = MorganaDataStore(rootDir = dir)
        val coord1 = MorganaWorldCoordinator(dataStore = store1, simulation = com.morgana.domain.sim.WorldSimulation(game = compiledGame))
        coord1.simulation.worldSwitches["bridge_repaired"] = true
        coord1.simulation.worldVariables["donations"] = 250
        coord1.simulation.worldStringVariables["patron"] = "Lyra"
        coord1.close()
        store1.close()

        val store2 = MorganaDataStore(rootDir = dir)
        val coord2 = MorganaWorldCoordinator(dataStore = store2, simulation = com.morgana.domain.sim.WorldSimulation(game = compiledGame))
        assertEquals(true, coord2.simulation.worldSwitches["bridge_repaired"])
        assertEquals(250, coord2.simulation.worldVariables["donations"])
        assertEquals("Lyra", coord2.simulation.worldStringVariables["patron"])
        coord2.close()
        store2.close()
    }
}

