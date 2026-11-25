package com.morgana.client.render

import com.morgana.domain.game.ClockDefinition
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the sky looks like at one moment of the game day: the colour the outdoor world is multiplied by, and where the sun
 * (or moon) throws shadows. Pure maths over the game's [ClockDefinition], so it is unit-tested and identical on desktop,
 * Android and the browser.
 */
data class SkyState(
    /** Ambient light the lit scene is multiplied by (the floor of the light map). */
    val ambientR: Float,
    val ambientG: Float,
    val ambientB: Float,
    /** 0 at full night, 1 in full day. */
    val daylight: Float,
    /** 0..1, peaks mid-dawn and mid-dusk: drives the warm tint and the low mist. */
    val warmth: Float,
    /** Horizontal lean of cast shadows per unit of shadow height. Negative leans west (sun in the east). */
    val shadowShear: Float,
    /** How long shadows are drawn, as a fraction of the caster's height (short at noon, long at dusk and dawn). */
    val shadowLength: Float,
    val shadowAlpha: Float,
    val isMoon: Boolean
)

object VisualSky {
    private val NIGHT = floatArrayOf(0.20f, 0.27f, 0.54f)
    private val DAY = floatArrayOf(1.00f, 1.00f, 0.98f)
    private val WARM = floatArrayOf(1.00f, 0.70f, 0.48f)

    fun at(clock: ClockDefinition, permille: Int): SkyState {
        val nightLight = clock.nightLight / 1000f
        val light = clock.lightAt(permille) / 1000f
        val d = if (nightLight >= 1f) 1f else ((light - nightLight) / (1f - nightLight)).coerceIn(0f, 1f)
        val warm = 4f * d * (1f - d)

        val ambient = FloatArray(3) { i ->
            val base = NIGHT[i] + (DAY[i] - NIGHT[i]) * d
            base + (WARM[i] - base) * warm * 0.55f
        }

        // The sun is up from the start of dawn to the start of night; the moon takes the rest of the day.
        val sunStart = clock.dawnStartPermille
        val sunEnd = clock.nightStartPermille
        val isSun = permille in sunStart until sunEnd
        val span = if (isSun) (sunEnd - sunStart) else (1000 - sunEnd + sunStart)
        val into = if (isSun) permille - sunStart else (permille - sunEnd + 1000) % 1000
        val angle = PI.toFloat() * (into.toFloat() / span.coerceAtLeast(1)).coerceIn(0f, 1f)
        val elevation = sin(angle)
        val shear = -cos(angle)
        val length = if (isSun) (0.28f + 0.95f * (1f - elevation)).coerceAtMost(1.25f) else (0.8f + 0.4f * (1f - elevation))
        val alpha = if (isSun) 0.14f + 0.20f * d else 0.12f

        return SkyState(ambient[0], ambient[1], ambient[2], d, warm, shear, length, alpha, !isSun)
    }
}
