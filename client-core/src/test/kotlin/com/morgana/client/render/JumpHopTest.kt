package com.morgana.client.render

import com.morgana.client.state.EntityVisualSmoothing
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class JumpHopTest {
    private fun leaping(timer: Float, duration: Float = 0.4f, peak: Float = 20f) =
        EntityVisualSmoothing(0f, 0f, 200f, 0f, leapTimer = timer, leapDuration = duration, leapPeakPixels = peak)

    @Test
    fun hopIsZeroOnTheGroundAndPeaksMidLeap() {
        assertEquals(0f, EntityVisualSmoothing(0f, 0f, 0f, 0f).hopHeight)
        assertEquals(0f, leaping(0f).hopHeight, 0.001f)
        assertEquals(20f, leaping(0.2f).hopHeight, 0.001f)
        assertEquals(0f, leaping(0.4f).hopHeight, 0.01f)
        assertTrue(leaping(0.1f).hopHeight in 1f..19f)
    }

    @Test
    fun liftRaisesSpriteAndUiButKeepsTheGroundPoint() {
        val ground = SpritePlacement.place(64f, 32f, 10, 32)
        val hop = SpritePlacement.place(64f, 32f, 10, 32, lift = 12f)
        assertEquals(ground.drawY + 12f, hop.drawY, 0.001f)
        assertEquals(ground.uiTopY + 12f, hop.uiTopY, 0.001f)
        assertEquals(ground.drawY, hop.drawY - hop.lift, 0.001f)
        assertEquals(ground.centerX, hop.centerX, 0.001f)
    }
}
