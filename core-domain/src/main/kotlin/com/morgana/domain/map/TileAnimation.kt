package com.morgana.domain.map

/**
 * Animated map tiles, on any layer: a placement with [TilePlacement.animationFrames] above 1 cycles through that many
 * frames, showing each for [TilePlacement.animationSpeedMs]. The frames are tiles of the same tileset row, starting at the
 * placed tile and going right, [TilePlacement.animationFrameStep] tiles apart (1 for a single animated tile; the width of
 * the object for a multi-tile one, so a 2-wide fountain keeps its frames side by side). This is the common tile-editor
 * convention of drawing animation frames next to each other on the sheet.
 *
 * Pure and deterministic: the frame depends only on the placement and the elapsed time, so every client shows the same
 * frame at the same moment and the rule is unit-tested. The legacy [AutotileMode.ANIMATED_3_FRAME] water tile keeps its own
 * fixed frames and is not handled here.
 */
object TileAnimation {
    const val MAX_FRAMES = 32
    const val MIN_SPEED_MS = 16
    const val MAX_SPEED_MS = 60_000
    const val MAX_FRAME_STEP = 64

    /** Whether [p] animates by this rule. */
    fun isAnimated(p: TilePlacement): Boolean = p.animationFrames > 1 && p.autotileMode != AutotileMode.ANIMATED_3_FRAME

    /** The frame (0-based) of [p] shown [elapsedMs] milliseconds after the clock started. */
    fun frameAt(p: TilePlacement, elapsedMs: Long): Int {
        if (!isAnimated(p)) return 0
        val speed = p.animationSpeedMs.coerceIn(MIN_SPEED_MS, MAX_SPEED_MS).toLong()
        val frames = p.animationFrames.coerceAtMost(MAX_FRAMES)
        val step = (if (elapsedMs >= 0) elapsedMs else elapsedMs - speed + 1) / speed // floor division
        val frame = step % frames
        return (if (frame < 0) frame + frames else frame).toInt()
    }

    /** The source tile (column, row) on the sheet that [p] shows at [elapsedMs]. */
    fun sourceAt(p: TilePlacement, elapsedMs: Long): Pair<Int, Int> {
        val frame = frameAt(p, elapsedMs)
        if (frame == 0) return p.srcTileX to p.srcTileY
        val step = p.animationFrameStep.coerceIn(1, MAX_FRAME_STEP)
        return (p.srcTileX + frame * step) to p.srcTileY
    }

    /** The source column of the last frame of [p]: what must still be on the sheet for the animation to be complete. */
    fun lastFrameColumn(p: TilePlacement): Int =
        if (!isAnimated(p)) p.srcTileX else p.srcTileX + (p.animationFrames - 1) * p.animationFrameStep.coerceAtLeast(1)

    /** What is wrong with [p]'s animation settings, as (field, message) pairs; empty when they are fine. */
    fun problems(p: TilePlacement): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (p.animationFrames !in 1..MAX_FRAMES) out += "animationFrames" to "animationFrames must be 1..$MAX_FRAMES (got ${p.animationFrames})"
        if (p.animationFrames > 1 && p.animationSpeedMs !in MIN_SPEED_MS..MAX_SPEED_MS) {
            out += "animationSpeedMs" to "animationSpeedMs must be $MIN_SPEED_MS..$MAX_SPEED_MS for an animated tile (got ${p.animationSpeedMs})"
        }
        if (p.animationFrameStep !in 1..MAX_FRAME_STEP) out += "animationFrameStep" to "animationFrameStep must be 1..$MAX_FRAME_STEP (got ${p.animationFrameStep})"
        return out
    }
}
