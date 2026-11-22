package com.morgana.client.assets

import com.morgana.domain.game.SpriteProfile
import com.morgana.domain.game.SpriteState
import kotlin.math.floor

/** Pixel rectangle inside a sprite sheet PNG (top-down LibGDX split coordinates). */
data class SpriteCellRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Pure, LibGDX-free rules for slicing character/NPC sprite sheets and choosing animation columns from [SpriteProfile].
 */
object SpriteSheetLayout {

    /** Frames per second of a walk cycle longer than two frames (about the pace of the two-frame step toggle). */
    const val WALK_FRAMES_PER_SECOND = 7.5f

    /**
     * Returns `[directionRow][column]` rectangles for a sheet of `[sheetWidth] x [sheetHeight]`.
     * When [profile] matches the sheet dimensions (`sheetWidth == profile.sheetWidth && sheetHeight == profile.sheetHeight`),
     * slices into `[SpriteProfile.DIRECTIONS]` rows x `[profile.totalFrames]` columns of `[profile.cellWidth] x [profile.cellHeight]`.
     * Otherwise falls back to the classic `3 x 4` grid.
     */
    fun computeSheetGrid(
        sheetWidth: Int,
        sheetHeight: Int,
        profile: SpriteProfile?
    ): Array<Array<SpriteCellRect>> {
        if (profile != null && sheetWidth == profile.sheetWidth && sheetHeight == profile.sheetHeight) {
            val cols = profile.totalFrames
            val rows = SpriteProfile.DIRECTIONS
            val w = profile.cellWidth
            val h = profile.cellHeight
            return Array(rows) { r ->
                Array(cols) { c ->
                    SpriteCellRect(c * w, r * h, w, h)
                }
            }
        }
        val w = (sheetWidth / SpriteProfile.CLASSIC_WALK_FRAMES).coerceAtLeast(1)
        val h = (sheetHeight / SpriteProfile.DIRECTIONS).coerceAtLeast(1)
        return Array(SpriteProfile.DIRECTIONS) { r ->
            Array(SpriteProfile.CLASSIC_WALK_FRAMES) { c ->
                SpriteCellRect(c * w, r * h, w, h)
            }
        }
    }

    /**
     * Picks the frame column in `[0 until gridColumns]` for [state] and timing inputs.
     * For classic 3-column sheets (`gridColumns <= 3`), keeps frame `0..2` walk cadence cleanly.
     */
    fun resolveColumn(
        gridColumns: Int,
        profile: SpriteProfile?,
        state: SpriteState?,
        walkFrame: Int = 0,
        elapsedSeconds: Float = 0f,
        actionProgress: Float? = null
    ): Int {
        if (gridColumns <= 1) return 0
        if (profile == null || gridColumns != profile.totalFrames) {
            val classic = when (state) {
                SpriteState.IDLE -> 0
                SpriteState.ATTACK, SpriteState.CAST, SpriteState.HARVEST, SpriteState.JUMP -> {
                    val p = (actionProgress ?: ((elapsedSeconds * 4f) % 1f)).coerceIn(0f, 0.999f)
                    if (p < 0.5f) 1 else 2
                }
                SpriteState.HURT -> 0
                else -> walkFrame
            }
            return classic.coerceIn(0, gridColumns - 1)
        }
        val activeState = state ?: if (walkFrame > 0) SpriteState.WALK else SpriteState.IDLE
        val range = profile.columnRange(activeState)
        val count = (range.last - range.first + 1).coerceAtLeast(1)
        val offset = when (activeState) {
            SpriteState.IDLE -> if (count == 1) 0 else floor(elapsedSeconds * 2f).toInt().mod(count)
            SpriteState.WALK -> when {
                count <= 1 -> 0
                // The step toggle only carries 1 or 2, so a cycle of four or more frames steps through its frames on the clock instead.
                count > 3 && walkFrame > 0 -> floor(elapsedSeconds * WALK_FRAMES_PER_SECOND).toInt().mod(count)
                else -> (walkFrame - 1).coerceAtLeast(0).mod(count)
            }
            SpriteState.ATTACK, SpriteState.CAST, SpriteState.HARVEST, SpriteState.JUMP, SpriteState.HURT -> {
                val progress = actionProgress ?: ((elapsedSeconds * 3f) % 1f)
                floor(progress.coerceIn(0f, 0.9999f) * count).toInt().coerceIn(0, count - 1)
            }
        }
        return (range.first + offset).coerceIn(0, gridColumns - 1)
    }
}

