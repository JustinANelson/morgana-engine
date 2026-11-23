package com.morgana.domain

import com.morgana.domain.content.NpcDropEntry
import com.morgana.domain.sim.SeededRandomSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Drops rarer than one basis point are written in parts per million. */
class RareDropChanceTest {

    @Test
    fun `a per-million chance is used when set and basis points otherwise`() {
        assertEquals(400L, NpcDropEntry(itemId = 1, chanceBasisPoints = 0, chancePerMillion = 400).chancePpm)
        assertEquals(250_000L, NpcDropEntry(itemId = 1, chanceBasisPoints = 2500).chancePpm)
    }

    @Test
    fun `1 in 50000 lands about as often as it says`() {
        val rng = SeededRandomSource(7L)
        val n = 2_000_000
        val hits = (0 until n).count { rng.rollPerMillion(20L) }
        // Expect 40 hits; a wide band keeps the test steady while still telling 1 in 50,000 from 1 in 5,000 or 1 in 500,000.
        assertTrue(hits in 15..80, "hits: $hits")
    }

    @Test
    fun `zero never drops and a million always drops`() {
        val rng = SeededRandomSource(1L)
        assertFalse((0 until 1000).any { rng.rollPerMillion(0L) })
        assertTrue((0 until 1000).all { rng.rollPerMillion(1_000_000L) })
    }
}
