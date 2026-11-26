package com.morgana.client

import com.morgana.client.state.SkillFx
import com.morgana.client.state.SkillFxMath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class SkillFxTest {
    private fun rain(elapsed: Float = 0f) = SkillFx(SkillFx.ARROW_RAIN, fromX = 100f, fromY = 300f, toX = 400f, toY = 300f, radius = 64f, delaySeconds = 0.2f, spanSeconds = 0.3f, elapsed = elapsed)

    @Test
    fun `every arrow lands inside the area`() {
        val fx = rain()
        for (k in 0 until SkillFxMath.ARROWS) {
            val (x, y) = SkillFxMath.landing(fx, k)
            assertTrue(hypot(x - fx.toX, y - fx.toY) <= fx.radius, "arrow $k lands at $x,$y")
        }
    }

    @Test
    fun `an arrow rises from the shooter pointing up, then falls onto its spot pointing down, then bursts`() {
        val fx = rain()
        val k = 6
        val landT = SkillFxMath.landTime(fx, k)
        val (lx, ly) = SkillFxMath.landing(fx, k)
        fx.elapsed = maxOf(0f, landT - SkillFxMath.FLIGHT_SECONDS) + 0.05f
        val rising = SkillFxMath.rainArrow(fx, k)
        assertNotNull(rising)
        assertEquals(90f, rising!!.angleDegrees)
        assertTrue(rising.y < fx.fromY, "it has gone up from the shooter (map y is down)")

        fx.elapsed = landT - 0.05f
        val falling = SkillFxMath.rainArrow(fx, k)!!
        assertEquals(270f, falling.angleDegrees)
        assertEquals(lx, falling.x, 0.01f)
        assertTrue(falling.y < ly, "still above its spot")

        fx.elapsed = landT + 0.05f
        val landed = SkillFxMath.rainArrow(fx, k)!!
        assertTrue(landed.landed)
        assertEquals(ly, landed.y, 0.01f)

        fx.elapsed = landT + SkillFxMath.IMPACT_SECONDS + 0.05f
        assertNull(SkillFxMath.rainArrow(fx, k))
    }

    @Test
    fun `the rain lands over the skill's hit window, first arrow at its start and last at its end`() {
        val fx = rain()
        assertEquals(0.2f, SkillFxMath.landTime(fx, 0), 0.001f)
        assertEquals(0.5f, SkillFxMath.landTime(fx, SkillFxMath.ARROWS - 1), 0.001f)
        assertTrue(fx.durationSeconds > 0.5f)
    }

    @Test
    fun `a ring of dots stays on its circle and turns`() {
        val a = SkillFxMath.ring(0f, 0f, 50f, 8, 0f)
        val b = SkillFxMath.ring(0f, 0f, 50f, 8, 0.05f)
        for ((x, y) in a) assertEquals(50f, hypot(x, y), 0.01f)
        assertTrue(a[0] != b[0], "it has turned")
    }

    @Test
    fun `a bolt runs from its start to its end with a jagged middle`() {
        val fx = SkillFx(SkillFx.BOLT, 0f, 0f, 200f, 0f, 0f, 0f, 0.1f)
        val pts = SkillFxMath.bolt(fx, 7)
        assertEquals(8, pts.size)
        assertEquals(0f, pts.first().first)
        assertEquals(200f, pts.last().first)
        assertEquals(0f, pts.first().second)
        assertEquals(0f, pts.last().second)
        assertTrue(pts.drop(1).dropLast(1).any { kotlin.math.abs(it.second) > 1f }, "the middle jumps off the straight line")
        assertTrue(pts.all { kotlin.math.abs(it.second) <= 15.01f }, "but not far")
    }

    @Test
    fun `a dodge trail draws out along the dash and fades`() {
        val fx = SkillFx(SkillFx.DODGE, 0f, 0f, 100f, 0f, 0f, 0f, 0.4f)
        fx.elapsed = 0.05f
        val early = SkillFxMath.dodgeProgress(fx)
        fx.elapsed = 0.5f
        val late = SkillFxMath.dodgeProgress(fx)
        assertTrue(early.first < late.first && early.second > late.second)
        assertEquals(1f, late.first)
    }
}
