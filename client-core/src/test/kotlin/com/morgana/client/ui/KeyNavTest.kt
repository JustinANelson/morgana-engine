package com.morgana.client.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KeyNavTest {
    @Test
    fun `cycling wraps in both directions`() {
        assertEquals(1, KeyNav.cycle(0, 3, backwards = false))
        assertEquals(0, KeyNav.cycle(2, 3, backwards = false))
        assertEquals(2, KeyNav.cycle(0, 3, backwards = true))
        assertEquals(0, KeyNav.cycle(1, 2, backwards = true))
    }

    @Test
    fun `an empty ring has nowhere to go`() {
        assertEquals(-1, KeyNav.cycle(0, 0, backwards = false))
    }

    @Test
    fun `stepping through a list stops at the ends instead of wrapping`() {
        assertEquals(1, KeyNav.step(0, 3, 1))
        assertEquals(2, KeyNav.step(2, 3, 1), "already at the end")
        assertEquals(0, KeyNav.step(0, 3, -1), "already at the start")
        assertEquals(0, KeyNav.step(-1, 3, 1), "nothing selected: down picks the first")
        assertEquals(2, KeyNav.step(-1, 3, -1), "nothing selected: up picks the last")
        assertEquals(-1, KeyNav.step(0, 0, 1))
    }
}
