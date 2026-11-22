package com.morgana.client.assets

import kotlin.math.floor

/** Pixel rectangle inside an animation strip PNG. */
data class AnimationFrameRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Pure, LibGDX-free rules for slicing horizontal animation strip sheets and stepping through frames by elapsed time.
 */
object AnimationStripLayout {
    const val MAX_FRAMES = 64
    const val DEFAULT_FRAME_COUNT = 5
    const val DEFAULT_FRAME_DURATION_MS = 80
    const val MIN_FRAME_DURATION_MS = 10
    const val MAX_FRAME_DURATION_MS = 2000

    /**
     * Slices `[sheetWidth] x [sheetHeight]` into square horizontal frames when [sheetWidth] is a positive multiple of
     * [sheetHeight] (1 to [MAX_FRAMES] frames), otherwise returns the whole image as a single frame.
     */
    fun computeStripFrames(sheetWidth: Int, sheetHeight: Int): Array<AnimationFrameRect> {
        val safeW = sheetWidth.coerceAtLeast(1)
        val safeH = sheetHeight.coerceAtLeast(1)
        if (safeW >= safeH && safeW % safeH == 0) {
            val count = (safeW / safeH).coerceIn(1, MAX_FRAMES)
            return Array(count) { i -> AnimationFrameRect(i * safeH, 0, safeH, safeH) }
        }
        return arrayOf(AnimationFrameRect(0, 0, safeW, safeH))
    }

    /** Total playback duration in seconds for [frameCount] frames at [frameDurationMs] milliseconds per frame. */
    fun totalDurationSeconds(frameCount: Int, frameDurationMs: Int): Float =
        frameCount.coerceIn(1, MAX_FRAMES) * (frameDurationMs.coerceIn(MIN_FRAME_DURATION_MS, MAX_FRAME_DURATION_MS) / 1000f)

    /**
     * Resolves the active frame index in `0 until availableFrames` at [elapsedSeconds].
     *
     * When [loop] is false, playback stops on the final step of [definitionFrames]; if [definitionFrames] exceeds
     * [availableFrames], frames wrap across the strip cleanly rather than clamping early.
     */
    fun resolveFrameIndex(
        elapsedSeconds: Float,
        frameDurationMs: Int,
        definitionFrames: Int,
        availableFrames: Int,
        loop: Boolean = false
    ): Int {
        if (availableFrames <= 1) return 0
        val stepSeconds = frameDurationMs.coerceIn(MIN_FRAME_DURATION_MS, MAX_FRAME_DURATION_MS) / 1000f
        val rawStep = floor(elapsedSeconds.coerceAtLeast(0f) / stepSeconds).toInt()
        val seqLen = definitionFrames.coerceIn(1, MAX_FRAMES)
        val step = if (loop) rawStep else rawStep.coerceIn(0, seqLen - 1)
        return step.mod(availableFrames)
    }
}

