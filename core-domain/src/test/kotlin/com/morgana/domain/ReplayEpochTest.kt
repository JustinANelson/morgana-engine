package com.morgana.domain

import com.morgana.domain.game.ClockDefinition
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.replay.InputRecorder
import com.morgana.domain.replay.ReplayPlayer
import com.morgana.domain.replay.SimInput
import com.morgana.domain.sim.WorldSimulation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplayEpochTest {

    @Test
    fun `replays capture starting epoch ticks and restore them on playback`() {
        val gameDef = MorganaClassic.definition.copy(
            clock = ClockDefinition(dayLengthTicks = 1000, daysPerSeason = 5)
        )
        val compiled = CompiledGame.compile(gameDef)

        val sim = WorldSimulation(game = compiled, worldEpochTicks = 5000L)
        val recorder = InputRecorder(sim = sim, seed = 1337L)

        // Advance 200 ticks
        repeat(200) {
            recorder.apply(SimInput.Tick(50))
        }

        val log = recorder.finish()
        assertEquals(5000L, log.startingEpochTicks)
        assertEquals(200L, log.finalTick)

        // Replay on fresh sim
        val replaySim = WorldSimulation(game = compiled)
        val result = ReplayPlayer.replay(replaySim, log)

        assertTrue(result.ok)
        assertEquals(5000L, replaySim.worldEpochTicks)
        assertEquals(200L, replaySim.currentTick)
        assertEquals(5200L, replaySim.effectiveWorldTick())
    }
}

