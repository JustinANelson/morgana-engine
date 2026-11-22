package com.morgana.domain.sim

import com.morgana.domain.spatial.MovementMode

/**
 * Server-enforced movement limits shared by the simulation and documentation.
 *
 * Clients send a grid step roughly every 2.6 ticks (130 ms) and a sub-tile step once per tick on average (50 ms).
 * Steps that arrive bunched by network jitter are held by the server (`MoveInputQueue`) until these limits allow them,
 * rather than dropped, so the limits cap speed without punishing a jittery connection.
 */
object MovementRules {
    /** Minimum ticks between accepted grid steps (max 10 tiles/s; a normal client does about 7.7). */
    const val GRID_MIN_STEP_TICKS: Int = 2

    /** Minimum ticks between accepted sub-tile steps (at most one step per tick). */
    const val SUB_TILE_MIN_STEP_TICKS: Int = 1

    /** Pixels a normal free-movement step covers (what the client sends when not sprinting). */
    const val NORMAL_STEP_PIXELS: Float = 8f

    /** A step longer than a normal one by more than this factor counts as a sprint step. */
    const val SPRINT_DETECT_FACTOR: Float = 1.05f

    /**
     * The step to accept for a requested step of [dx], [dy] pixels. Without sprint rules the old per-axis cap applies. With
     * them, a normal-length step passes, and a longer one is a sprint step: kept (up to the sprint length) while
     * [hasStamina], otherwise cut back to a normal step. [baseStepPercent] is the length of a normal step in percent (a worn mount raises it). Returns the step and whether it was a sprint step.
     */
    fun limitStep(dx: Float, dy: Float, sprintSpeedPercent: Int?, hasStamina: Boolean, baseStepPercent: Int = 100): Triple<Float, Float, Boolean> {
        if (sprintSpeedPercent == null) {
            return Triple(dx.coerceIn(-SUB_TILE_MAX_STEP_PIXELS, SUB_TILE_MAX_STEP_PIXELS), dy.coerceIn(-SUB_TILE_MAX_STEP_PIXELS, SUB_TILE_MAX_STEP_PIXELS), false)
        }
        val magnitude = kotlin.math.hypot(dx, dy)
        if (magnitude <= 0f) return Triple(dx, dy, false)
        val normal = NORMAL_STEP_PIXELS * baseStepPercent / 100f
        val wantsSprint = magnitude > normal * SPRINT_DETECT_FACTOR
        val sprinting = wantsSprint && hasStamina
        val allowed = if (sprinting) normal * sprintSpeedPercent / 100f else normal
        val scale = if (magnitude > allowed) allowed / magnitude else 1f
        return Triple(dx * scale, dy * scale, sprinting)
    }

    /** Maximum pixels moved per accepted sub-tile step on each axis. */
    const val SUB_TILE_MAX_STEP_PIXELS: Float = 16f

    /** Minimum ticks between accepted steps in [mode]. */
    fun minStepTicks(mode: MovementMode): Int =
        if (mode == MovementMode.SUB_TILE) SUB_TILE_MIN_STEP_TICKS else GRID_MIN_STEP_TICKS

    /** True when a step at [currentTick] would come too soon after the last accepted one ([lastMoveTick], 0 = never). */
    fun isTooSoon(lastMoveTick: Long, currentTick: Long, mode: MovementMode): Boolean =
        lastMoveTick > 0L && currentTick - lastMoveTick < minStepTicks(mode)
}
