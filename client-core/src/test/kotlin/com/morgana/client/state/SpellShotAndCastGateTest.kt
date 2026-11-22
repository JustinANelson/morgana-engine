package com.morgana.client.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpellShotAndCastGateTest {
    @Test
    fun `a repeat of the same action inside the gap is refused`() {
        val s = ClientWorldState()
        assertTrue(s.actionReady("spell:7", 1_000))
        assertFalse(s.actionReady("spell:7", 1_200))
        assertTrue(s.actionReady("spell:8", 1_200))
        assertTrue(s.actionReady("spell:7", 1_500))
    }

    @Test
    fun `a spell still cooling down is refused`() {
        val s = ClientWorldState()
        s.spellCooldowns.set(7, 5f, com.morgana.client.ui.ToastClock.now())
        assertFalse(s.castReady(7, 1_000))
    }

    @Test
    fun `a shot flies from start to end and finishes`() {
        val shot = SpellShot(1, 0f, 0f, 100f, 0f, 1f, arc = 0f)
        assertEquals(0f, shot.x, 0.001f)
        shot.elapsedSeconds = 0.5f
        assertEquals(50f, shot.x, 0.001f)
        assertEquals(0f, shot.angleDegrees, 0.5f)
        shot.elapsedSeconds = 1f
        assertEquals(100f, shot.x, 0.001f)
        assertTrue(shot.finished)
    }

    @Test
    fun `a shot flying up the screen points up`() {
        val shot = SpellShot(1, 0f, 100f, 0f, 0f, 1f)
        shot.elapsedSeconds = 0.5f
        assertEquals(90f, shot.angleDegrees, 0.5f)
    }

    @Test
    fun `flight time is clamped`() {
        assertEquals(0.14f, SpellShot.flightSeconds(1f), 0.0001f)
        assertEquals(0.5f, SpellShot.flightSeconds(10_000f), 0.0001f)
    }
}
