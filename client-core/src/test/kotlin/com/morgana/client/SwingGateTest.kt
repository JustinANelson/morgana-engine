package com.morgana.client

import com.morgana.client.state.ClientWorldState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class SwingGateTest {
    @Test
    fun `a second swing inside the weapon delay is refused until the delay passes`() {
        val state = ClientWorldState()
        assertTrue(state.swingReady(1_000))
        assertFalse(state.swingReady(1_100))
        assertTrue(state.swingReady(1_000 + 12 * 50L))
    }
}
