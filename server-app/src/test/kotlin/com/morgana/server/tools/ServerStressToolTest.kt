package com.morgana.server.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ServerStressToolTest {

    @Test
    fun `parseStressArgs reads flags and defaults`() {
        val opts = parseStressArgs(arrayOf("--counts", "10,20", "--ticks", "50", "--layout", "clumped", "--aoi", "--sqlite"))
        assertEquals(listOf(10, 20), opts.counts)
        assertEquals(50, opts.ticksPerStage)
        assertEquals(StressLayout.CLUMPED, opts.layout)
        assertTrue(opts.aoi)
        assertTrue(opts.sqlite)
    }

    @Test
    fun `runStressStage executes a small stage cleanly in temp dir`(@TempDir dir: Path) {
        val opts = StressOptions(counts = listOf(5), ticksPerStage = 20, layout = StressLayout.SPREAD, aoi = true)
        val result = runStressStage(5, opts, dir)
        assertEquals(5, result.players)
        assertTrue(result.avgMs >= 0.0)
        assertTrue(result.p95Ms >= 0.0)
        assertTrue(result.maxMs >= result.p95Ms)
        assertTrue(result.outboundPerTick > 0)
    }
}
