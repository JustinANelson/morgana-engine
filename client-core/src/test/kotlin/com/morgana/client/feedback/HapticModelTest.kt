package com.morgana.client.feedback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HapticModelTest {
    @Test
    fun `the first sighting of a character never buzzes`() {
        val h = HapticModel()
        assertEquals(0, h.update("c1", 50, 3, 0))
    }

    @Test
    fun `losing health gives a short tick and a burst of hits is spaced out`() {
        val h = HapticModel()
        h.update("c1", 50, 3, 0)
        assertEquals(HapticModel.HIT_MS, h.update("c1", 40, 3, 1_000))
        assertEquals(0, h.update("c1", 30, 3, 1_050), "too soon after the last buzz")
        assertEquals(HapticModel.HIT_MS, h.update("c1", 20, 3, 1_300))
    }

    @Test
    fun `healing and steady health are silent`() {
        val h = HapticModel()
        h.update("c1", 20, 1, 0)
        assertEquals(0, h.update("c1", 30, 1, 100))
        assertEquals(0, h.update("c1", 30, 1, 200))
    }

    @Test
    fun `dying buzzes long even right after another buzz`() {
        val h = HapticModel()
        h.update("c1", 10, 1, 0)
        h.update("c1", 5, 1, 1_000)
        assertEquals(HapticModel.DEATH_MS, h.update("c1", 0, 1, 1_010))
        assertEquals(0, h.update("c1", 0, 1, 1_020), "already dead: no repeat")
    }

    @Test
    fun `a level up buzzes`() {
        val h = HapticModel()
        h.update("c1", 10, 1, 0)
        assertEquals(HapticModel.LEVEL_UP_MS, h.update("c1", 10, 2, 100))
    }

    @Test
    fun `switching character or resetting only re-learns the values`() {
        val h = HapticModel()
        h.update("c1", 10, 5, 0)
        assertEquals(0, h.update("c2", 1, 1, 100), "a different character with lower values is not damage")
        h.reset()
        assertEquals(0, h.update("c2", 100, 9, 200))
    }
}
