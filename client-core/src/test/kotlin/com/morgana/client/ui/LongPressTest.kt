package com.morgana.client.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LongPressTest {
    @Test
    fun `holding still fires once at the threshold and the release reports it`() {
        val p = LongPress(thresholdSeconds = 0.5f)
        p.begin(10f, 10f)
        assertFalse(p.update(0.3f))
        assertTrue(p.update(0.3f))
        assertFalse(p.update(0.3f), "fires only once")
        assertTrue(p.isFired)
        assertTrue(p.end(), "the tap after a long press should be swallowed")
        assertFalse(p.isFired)
    }

    @Test
    fun `a short tap never fires and is not swallowed`() {
        val p = LongPress(thresholdSeconds = 0.5f)
        p.begin(0f, 0f)
        assertFalse(p.update(0.2f))
        assertFalse(p.end())
    }

    @Test
    fun `dragging past the slop cancels, small wobble does not`() {
        val p = LongPress(thresholdSeconds = 0.5f, slop = 10f)
        p.begin(0f, 0f)
        p.move(5f, 5f)
        assertTrue(p.update(0.6f), "a small wobble still counts as holding")

        val q = LongPress(thresholdSeconds = 0.5f, slop = 10f)
        q.begin(0f, 0f)
        q.move(20f, 0f)
        assertFalse(q.update(0.6f), "the finger moved away, so this is a scroll")
        assertFalse(q.end())
    }

    @Test
    fun `nothing fires without a press and a new press starts fresh`() {
        val p = LongPress(thresholdSeconds = 0.5f)
        assertFalse(p.update(1f))
        p.begin(0f, 0f)
        p.update(0.4f)
        p.end()
        p.begin(0f, 0f)
        assertFalse(p.update(0.4f), "time from the earlier press does not carry over")
    }
}
