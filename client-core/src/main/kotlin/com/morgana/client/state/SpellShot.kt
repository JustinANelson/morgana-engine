package com.morgana.client.state

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A purely visual shot (an arrow, a fireball) that flies from a caster to where a ranged spell landed. The server resolves
 * the hit instantly; this only shows it travelling, so the spell's impact animation waits for [durationSeconds].
 * Positions are world pixels with y growing downward (the same space as an entity's pixel position).
 * It homes on a fixed point and bows slightly off the straight line by [arc] pixels, so a volley does not look ruled.
 */
class SpellShot(
    val spriteId: Int,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val durationSeconds: Float,
    val arc: Float = 0f,
    var elapsedSeconds: Float = 0f
) {
    val finished: Boolean get() = elapsedSeconds >= durationSeconds

    private fun progress(): Float = (elapsedSeconds / durationSeconds).coerceIn(0f, 1f)

    private fun xAt(t: Float): Float = startX + (endX - startX) * t + normalX() * arc * sin(PI.toFloat() * t)
    private fun yAt(t: Float): Float = startY + (endY - startY) * t + normalY() * arc * sin(PI.toFloat() * t)

    private fun length(): Float = hypot(endX - startX, endY - startY).coerceAtLeast(0.001f)
    private fun normalX(): Float = -(endY - startY) / length()
    private fun normalY(): Float = (endX - startX) / length()

    val x: Float get() = xAt(progress())
    val y: Float get() = yAt(progress())

    /** Degrees counter-clockwise on screen (y up) for a sprite drawn pointing right, following the flight path. */
    val angleDegrees: Float get() {
        val t = progress()
        val a = (t - 0.02f).coerceAtLeast(0f)
        val b = (t + 0.02f).coerceAtMost(1f)
        val dx = xAt(b) - xAt(a)
        val dy = yAt(b) - yAt(a)
        if (dx == 0f && dy == 0f) return 0f
        return Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toFloat()
    }

    companion object {
        /** Pixels a shot covers each second. */
        const val SPEED_PIXELS_PER_SECOND = 560f

        /** Flight time for a shot of [distancePixels]: quick, but long enough to be seen at short range. */
        fun flightSeconds(distancePixels: Float): Float = (distancePixels / SPEED_PIXELS_PER_SECOND).coerceIn(0.14f, 0.5f)
    }
}
