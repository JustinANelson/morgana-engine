package com.morgana.persistence

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.replay.InputRecorder
import com.morgana.domain.replay.ReplayLog
import com.morgana.domain.replay.ReplayPlayer
import com.morgana.domain.replay.SimInput
import com.morgana.domain.sim.MovementRules
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Golden-master replay of a scripted session in the seeded blank world.
 *
 * If a deliberate gameplay or seed-content change makes this fail, re-record with
 * `MORGANA_REGEN_GOLDEN=1 .\gradlew.bat :server-persistence:test --tests "*Phase12GoldenReplayTest*"`,
 * review the diff of the golden file, and commit it together with the change.
 */
class Phase12GoldenReplayTest {

    private val seed = 0xFADE2026L
    private val goldenPath: Path = Paths.get("src/test/resources/golden/blank_world_session.replay.json")

    private fun seededSim(dir: Path): WorldSimulation {
        val store = MorganaDataStore(dir)
        GameScaffolder.seedIfEmpty(store.world)
        val sim = WorldSimulation(SeededRandomSource(seed), game = MorganaClassic.COMPILED)
        store.world.loadIntoSimulation(sim)
        return sim
    }

    private fun knight(sim: WorldSimulation): CharacterState {
        val cls = sim.classes.getValue(1)
        return CharacterState(
            id = "char-golden", accountId = "acc-golden", name = "Golden", classId = cls.id,
            spriteId = cls.spriteIds.firstOrNull() ?: 1, attributes = cls.baseAttributes,
            position = WorldPosition.fromTile(cls.startMapId, cls.startX, cls.startY, Direction.DOWN),
            knownSpellIds = cls.startingSpellIds
        ).recalculateVitals(sim.game, sim.items, refill = true)
    }

    private fun scriptedSession(sim: WorldSimulation): ReplayLog {
        val rec = InputRecorder(sim, seed, checkpointEveryTicks = 25)
        val id = "char-golden"
        rec.apply(SimInput.SpawnPlayer(knight(sim)))
        rec.apply(SimInput.Tick())
        val route = listOf(Direction.LEFT, Direction.LEFT, Direction.UP, Direction.UP, Direction.RIGHT, Direction.DOWN, Direction.RIGHT, Direction.RIGHT)
        for (dir in route) {
            rec.apply(SimInput.Move(id, dir))
            repeat(MovementRules.GRID_MIN_STEP_TICKS) { rec.apply(SimInput.Tick()) }
            rec.apply(SimInput.InteractFacing(id))
            rec.apply(SimInput.Attack(id))
        }
        repeat(200) { rec.apply(SimInput.Tick()) }
        return rec.finish()
    }

    @Test
    fun `blank world session replays identically to the committed golden master`(@TempDir dir: Path) {
        val recorded = scriptedSession(seededSim(dir.resolve("record")))

        if (System.getenv("MORGANA_REGEN_GOLDEN") == "1" || System.getProperty("MORGANA_REGEN_GOLDEN") == "1") {
            ReplayLogCodec.write(goldenPath, recorded)
        }
        assertTrue(Files.exists(goldenPath), "Golden file missing; run with MORGANA_REGEN_GOLDEN=1 to create it")

        val golden = ReplayLogCodec.read(goldenPath)
        assertEquals(golden.finalHash, recorded.finalHash, "fresh recording no longer matches the golden master (see class docs to re-record)")

        val replayed = ReplayPlayer.replay(seededSim(dir.resolve("replay")), golden)
        assertTrue(replayed.ok, replayed.toString())
    }

    @Test
    fun `replay log JSON round-trips losslessly`(@TempDir dir: Path) {
        val log = scriptedSession(seededSim(dir))
        val decoded = ReplayLogCodec.decode(ReplayLogCodec.encode(log))
        assertEquals(log, decoded)
        assertFalse(log.inputs.isEmpty())
    }
}
