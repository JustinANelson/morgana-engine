package com.morgana.client.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TouchChatSlotTest {
    private val screens = listOf(640f to 360f, 800f to 360f, 844f to 390f, 932f to 430f, 1024f to 600f)

    @Test
    fun `the compact chat sits between the movement pad and the action ring`() {
        for ((w, h) in screens) {
            val s = touchChatSlot(w, h)
            val ringLeft = w - ringReach(w, h)
            assertTrue(s.feedX + s.feedW <= ringLeft, "$w x $h: box clears the ring")
            assertTrue(s.feedX > 100f, "$w x $h: box is right of the pad's resting spot (${s.feedX})")
            assertTrue(s.feedW >= 120f, "$w x $h: box is wide enough to read (${s.feedW})")
            assertTrue(s.feedY >= 10f, "$w x $h: box is on screen")
            assertTrue(s.feedY + s.feedH < h / 2f, "$w x $h: box stays in the lower half")
        }
    }

    @Test
    fun `the chat button waits in the box's corner`() {
        for ((w, h) in screens) {
            val s = touchChatSlot(w, h)
            assertTrue(s.buttonX == s.feedX && s.buttonY == s.feedY, "$w x $h: button replaces the box where it was")
        }
    }
}
