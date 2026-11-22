package com.morgana.client.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CooldownAndTextMotionTest {
    @Test
    fun `a cooldown counts down and a recast while cooling does not restart it`() {
        val c = SpellCooldowns()
        assertTrue(c.start(1, 4f, now = 10f))
        assertEquals(3f, c.remaining(1, 11f), 0.001f)
        assertEquals(0.75f, c.fractionLeft(1, 11f), 0.001f)
        assertFalse(c.start(1, 4f, now = 11f), "the server would refuse this cast, so the sweep keeps running")
        assertEquals(3f, c.remaining(1, 11f), 0.001f)
        assertEquals(0f, c.remaining(1, 14.5f))
        assertTrue(c.start(1, 4f, now = 14.5f), "ready again, so a new cast starts a new cooldown")
    }

    @Test
    fun `spells are tracked independently and a zero cooldown never shows`() {
        val c = SpellCooldowns()
        assertFalse(c.start(2, 0f, 0f))
        c.start(3, 2f, 0f)
        assertEquals(0f, c.remaining(4, 1f))
        assertTrue(c.remaining(3, 1f) > 0f)
        c.clear()
        assertEquals(0f, c.remaining(3, 1f))
    }

    @Test
    fun `combat text pops in large, settles and fades at the end`() {
        assertEquals(1.7f, FloatingTextMotion.scale(0f, crit = false), 0.001f)
        assertEquals(1f, FloatingTextMotion.scale(FloatingTextMotion.POP_SECONDS, crit = false), 0.001f)
        assertEquals(1f, FloatingTextMotion.scale(1f, crit = false), 0.001f)
        assertTrue(FloatingTextMotion.scale(1f, crit = true) > 1.3f, "crits stay bigger")
        assertEquals(1f, FloatingTextMotion.alpha(1f))
        assertEquals(0.5f, FloatingTextMotion.alpha(0.2f), 0.001f)
        assertEquals(0f, FloatingTextMotion.alpha(0f))
    }
}
