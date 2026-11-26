package com.morgana.client.state

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A built-in skill visual that needs no art, started by the server when a skill with an `effect` begins: "arrow_rain" (arrows rise
 * from the archer and fall on the aimed spot), "dodge" (a dust trail along the dash) or "spin" (a whirl at the landing point).
 * Positions are world pixels with y growing downward. The server resolves the hits; this only shows them.
 */
class SkillFx(
    val effect: String,
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val radius: Float,
    val delaySeconds: Float,
    val spanSeconds: Float,
    var elapsed: Float = 0f
) {
    val durationSeconds: Float
        get() = when (effect) {
            ARROW_RAIN -> delaySeconds + spanSeconds + 0.4f
            SPIN -> delaySeconds + spanSeconds + 0.4f
            EMPOWER_BURST -> 1.1f
            EMPOWER_PULSE -> 0.5f
            BOLT -> 0.3f
            SHOCK -> 0.55f
            BASH -> delaySeconds + 0.5f
            ELECTRIFY -> 0.8f
            SHOCKWAVE -> 0.45f
            else -> 0.6f
        }

    val finished: Boolean get() = elapsed >= durationSeconds

    companion object {
        const val ARROW_RAIN = "arrow_rain"
        const val DODGE = "dodge"
        const val SPIN = "spin"
        const val EMPOWER_BURST = "empower_burst"
        const val EMPOWER_PULSE = "empower_pulse"
        const val BOLT = "bolt"
        const val SHOCK = "shock"
        const val BASH = "bash"
        const val ELECTRIFY = "electrify"
        const val SHOCKWAVE = "shockwave"
        const val TICK_SECONDS = 0.05f
    }
}

/** Where one arrow of an arrow rain is at a moment: in the air (pointing up or down) or just landed, a ring spreading from the spot. */
data class RainArrow(val x: Float, val y: Float, val angleDegrees: Float, val landed: Boolean, val sinceLanding: Float)

/** Pure geometry of the built-in effects, so it is unit-tested apart from drawing. */
object SkillFxMath {
    const val ARROWS = 14
    const val FLIGHT_SECONDS = 0.55f
    const val IMPACT_SECONDS = 0.25f
    private const val GOLDEN = 2.39996323f

    /** Where arrow [k] of the rain lands: spread over the area's disc by the golden angle, so they never line up. */
    fun landing(fx: SkillFx, k: Int): Pair<Float, Float> {
        val angle = k * GOLDEN
        val r = fx.radius * 0.9f * sqrt((k + 0.5f) / ARROWS)
        return (fx.toX + cos(angle) * r) to (fx.toY + sin(angle) * r)
    }

    /** When arrow [k] lands, in seconds from the start: spread across the skill's hit window so each lands as a wave of damage does. */
    fun landTime(fx: SkillFx, k: Int): Float = fx.delaySeconds + fx.spanSeconds * k / (ARROWS - 1)

    /**
     * Arrow [k] at the effect's current time, or null before it is launched and after its impact has faded. It rises from the shooter
     * (45% of its flight, pointing up and drifting towards the spot), then drops from above onto its landing point (pointing down).
     */
    fun rainArrow(fx: SkillFx, k: Int): RainArrow? {
        val t = fx.elapsed
        val (lx, ly) = landing(fx, k)
        val landT = landTime(fx, k)
        val launch = maxOf(0f, landT - FLIGHT_SECONDS)
        val total = maxOf(0.15f, landT - launch)
        val rise = total * 0.45f
        if (t < launch) return null
        if (t < launch + rise) {
            val u = ((t - launch) / rise).coerceIn(0f, 1f)
            val eased = 1f - (1f - u) * (1f - u)
            val sx = fx.fromX + (k - ARROWS / 2) * 3f
            val ex = fx.fromX * 0.7f + lx * 0.3f
            return RainArrow(sx + (ex - sx) * eased, fx.fromY - 170f * eased, 90f, false, 0f)
        }
        if (t < landT) {
            val u = ((t - launch - rise) / (landT - launch - rise)).coerceIn(0f, 1f)
            return RainArrow(lx, ly - 210f * (1f - u) * (1f - u), 270f, false, 0f)
        }
        val since = t - landT
        return if (since > IMPACT_SECONDS) null else RainArrow(lx, ly, 0f, true, since)
    }

    /** The points of a spinning ring of [count] dots around ([cx], [cy]) at radius [r], turned by [turns] revolutions. */
    fun ring(cx: Float, cy: Float, r: Float, count: Int, turns: Float): List<Pair<Float, Float>> =
        List(count) { i ->
            val a = (i.toFloat() / count + turns) * 2f * PI.toFloat()
            (cx + cos(a) * r) to (cy + sin(a) * r)
        }

    /** Short crackling arcs radiating from ([cx], [cy]): [count] polylines of 4 points, [length] long, jumping each frame. */
    fun shockArcs(cx: Float, cy: Float, frame: Int, count: Int = 7, length: Float = 36f): List<List<Pair<Float, Float>>> =
        List(count) { a ->
            val base = (a.toFloat() / count + 0.07f * (frame % 5)) * 2f * PI.toFloat()
            List(4) { i ->
                val r = length * i / 3f
                val wobble = if (i == 0) 0f else (fract(sin(a * 7.13f + i * 3.7f + frame * 12.9898f) * 43758.547f) - 0.5f) * 0.9f
                (cx + cos(base + wobble) * r) to (cy + sin(base + wobble) * r)
            }
        }

    /** Arcs that leap across an area of [radius] around ([cx], [cy]): [count] jagged lines between points on its rim, new ones each frame. */
    fun electrifyArcs(cx: Float, cy: Float, radius: Float, frame: Int, count: Int = 5): List<List<Pair<Float, Float>>> =
        List(count) { a ->
            fun rim(seed: Float): Pair<Float, Float> {
                val ang = fract(sin(seed + frame * 78.233f) * 43758.547f) * 2f * PI.toFloat()
                return (cx + cos(ang) * radius * 0.95f) to (cy + sin(ang) * radius * 0.95f)
            }
            val from = rim(a * 5.31f)
            val to = rim(a * 9.77f + 1.3f)
            val dx = to.first - from.first
            val dy = to.second - from.second
            val len = hypot(dx, dy).coerceAtLeast(1f)
            List(6) { i ->
                val t = i / 5f
                val jump = if (i == 0 || i == 5) 0f else (fract(sin(a * 3.3f + i * 8.1f + frame * 12.9898f) * 43758.547f) - 0.5f) * 26f
                (from.first + dx * t - dy / len * jump) to (from.second + dy * t + dx / len * jump)
            }
        }

    /** A jagged lightning bolt from the effect's start to its end: [segments] + 1 points whose middle ones jump sideways each frame. */
    fun bolt(fx: SkillFx, segments: Int = 7): List<Pair<Float, Float>> {
        val dx = fx.toX - fx.fromX
        val dy = fx.toY - fx.fromY
        val len = hypot(dx, dy)
        if (len < 1f) return listOf(fx.fromX to fx.fromY, fx.toX to fx.toY)
        val nx = -dy / len
        val ny = dx / len
        val frame = (fx.elapsed * 30f).toInt()
        return List(segments + 1) { i ->
            val t = i.toFloat() / segments
            val jump = if (i == 0 || i == segments) 0f else (fract(sin(i * 12.9898f + frame * 78.233f) * 43758.547f) - 0.5f) * 30f
            (fx.fromX + dx * t + nx * jump) to (fx.fromY + dy * t + ny * jump)
        }
    }

    private fun fract(v: Float): Float = v - kotlin.math.floor(v)

    /** How far along its path a dodge's trail has drawn at time [t] (0..1), and how faded it is (1 = fresh, 0 = gone). */
    fun dodgeProgress(fx: SkillFx): Pair<Float, Float> {
        val length = hypot(fx.toX - fx.fromX, fx.toY - fx.fromY)
        val drawn = (fx.elapsed / 0.2f).coerceIn(0f, 1f)
        val fade = (1f - (fx.elapsed - 0.15f) / 0.45f).coerceIn(0f, 1f)
        return (if (length < 1f) 0f else drawn) to fade
    }
}
