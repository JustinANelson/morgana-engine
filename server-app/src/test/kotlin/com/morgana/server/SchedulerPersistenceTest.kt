package com.morgana.server

import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.ScheduleDefinition
import com.morgana.domain.game.ScheduleKind
import com.morgana.domain.sim.ScheduleHandler
import com.morgana.domain.sim.SchedulePhase
import com.morgana.domain.sim.ScheduleTrigger
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** The server feeds the simulation the wall clock and keeps the scheduler's state across restarts. */
class SchedulerPersistenceTest {
    /** A clock the test moves by hand. */
    private class SteppedClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private fun at(iso: String) = Instant.parse(iso)

    // A start every minute on the minute, a 10 second warning, and a generous grace.
    private val everyMinute = ScheduleDefinition(
        id = "minute", name = "Minute", kind = ScheduleKind.INTERVAL, intervalSeconds = 60, at = "2026-11-24T12:00:00Z",
        warningsSeconds = listOf(10), lateGraceSeconds = 300
    )
    private val game = CompiledGame.compile(MorganaClassic.definition.copy(schedules = listOf(everyMinute)))

    private class Counter : ScheduleHandler {
        val seen = mutableListOf<ScheduleTrigger>()
        override fun onSchedule(sim: WorldSimulation, trigger: ScheduleTrigger, out: MutableList<WorldSimulationEvent>) { seen += trigger }
        fun fires() = seen.count { it.phase == SchedulePhase.FIRE }
    }

    private fun boot(dir: Path, clock: Clock): Pair<MorganaWorldCoordinator, Counter> {
        val coordinator = MorganaWorldCoordinator(
            dataStore = MorganaDataStore(rootDir = dir), simulation = WorldSimulation(game = game), wallClock = clock
        )
        val counter = Counter()
        coordinator.simulation.install(counter)
        return coordinator to counter
    }

    /** One second of server time: 20 ticks, with the clock moved on by a second first. */
    private fun MorganaWorldCoordinator.second(clock: SteppedClock) {
        clock.now = clock.now.plusSeconds(1)
        repeat(20) { stepServerTick(50) }
    }

    @Test
    fun `the server reports the clock, the scheduler fires, and the state is saved at once`(@TempDir dir: Path) {
        val clock = SteppedClock(at("2026-11-24T12:00:30Z"))
        val (coordinator, counter) = boot(dir, clock)
        coordinator.second(clock)
        assertEquals(0, counter.fires(), "the first reading only sets the cursor")
        assertTrue(coordinator.simulation.scheduleCursorMillis > 0)

        repeat(40) { coordinator.second(clock) } // through 12:01:11
        assertEquals(1, counter.fires(), "the 12:01:00 start, once")
        assertEquals(listOf(10), counter.seen.filter { it.phase == SchedulePhase.WARNING }.map { it.secondsBefore }.take(1), "and its 10 second warning before it")

        // Saved when it fired, before any shutdown or autosave.
        val saved = ServerState.load(dir)
        assertEquals(at("2026-11-24T12:01:00Z").toEpochMilli(), saved.scheduleLastFired["minute"])
        assertTrue(saved.scheduleCursorMillis >= at("2026-11-24T12:01:00Z").toEpochMilli())
        coordinator.close()
    }

    @Test
    fun `a restart does not repeat a start even when the saved cursor is old`(@TempDir dir: Path) {
        val clock = SteppedClock(at("2026-11-24T12:00:30Z"))
        val (first, firstCounter) = boot(dir, clock)
        repeat(45) { first.second(clock) }
        assertEquals(1, firstCounter.fires())
        first.close()

        // A crash leaves an older cursor than the last-fired record. Boot again 20 seconds after the start.
        val state = ServerState.load(dir)
        ServerState.save(dir, state.copy(scheduleCursorMillis = at("2026-11-24T12:00:30Z").toEpochMilli()))
        val secondClock = SteppedClock(at("2026-11-24T12:01:20Z"))
        val (second, secondCounter) = boot(dir, secondClock)
        assertEquals(at("2026-11-24T12:00:30Z").toEpochMilli(), second.simulation.scheduleCursorMillis, "restored from the saved state")
        assertEquals(setOf("minute"), second.simulation.scheduleLastFired.keys)
        repeat(5) { second.second(secondClock) }
        assertEquals(0, secondCounter.fires(), "12:01:00 was already started before the crash")
        second.close()
    }

    @Test
    fun `one-off schedules survive a restart`(@TempDir dir: Path) {
        val clock = SteppedClock(at("2026-11-24T12:00:00Z"))
        val (first, _) = boot(dir, clock)
        first.second(clock)
        assertTrue(first.simulation.scheduleOnce("window", at("2026-11-26T12:00:00Z").toEpochMilli(), name = "Window", data = mapOf("guild" to "g1")))
        first.close()

        val restarted = ServerState.load(dir)
        assertEquals(setOf("window"), restarted.scheduleOneShots.keys)
        assertEquals(mapOf("guild" to "g1"), restarted.scheduleOneShots.getValue("window").data)
        val (second, _) = boot(dir, SteppedClock(at("2026-11-24T13:00:00Z")))
        assertEquals(setOf("window"), second.simulation.scheduleOneShots.keys, "loaded back into the simulation")
        second.close()
    }
}
