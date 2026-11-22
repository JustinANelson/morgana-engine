package com.morgana.tilegen.sprite

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage

/**
 * Standard RPG character / enemy animation clip states.
 *
 * Designed for 2D top-down & 3/4-perspective RPGs:
 * - [IDLE]: 2-frame or 4-frame subtle breathing / stance sway (`180–250ms` per frame, looping).
 * - [WALK]: 4-frame RMXP-style stride cycle (`LEFT_PASS -> NEUTRAL -> RIGHT_PASS -> NEUTRAL`, `120–150ms` per frame, looping).
 * - [ATTACK]: 3-to-4-frame combat swing (`WINDUP -> STRIKE ->SMEAR/IMPACT -> RECOVERY`, `80–110ms` per frame, non-looping).
 * - [CAST]: 4-frame skill/spell invocation (`CHANNEL -> SURGE -> RELEASE -> FADE`, `100–130ms` per frame, non-looping).
 * - [HURT]: 2-frame hit-recoil & flash (`90ms` per frame, non-looping).
 * - [KO]: 2-to-3-frame collapse / defeated state (`150ms` per frame, non-looping).
 */
enum class SpriteAnimationClip(
    val defaultFrames: Int,
    val defaultFrameDurationMs: Int,
    val looping: Boolean
) {
    IDLE(defaultFrames = 2, defaultFrameDurationMs = 220, looping = true),
    WALK(defaultFrames = 4, defaultFrameDurationMs = 135, looping = true),
    ATTACK(defaultFrames = 4, defaultFrameDurationMs = 95, looping = false),
    CAST(defaultFrames = 4, defaultFrameDurationMs = 115, looping = false),
    HURT(defaultFrames = 2, defaultFrameDurationMs = 90, looping = false),
    KO(defaultFrames = 2, defaultFrameDurationMs = 160, looping = false)
}

/**
 * Metadata for a single frame inside an [AnimationSequence], carrying per-frame timing,
 * pixel pivot (typically bottom-center foot anchor for 3/4 RPG sprites), and optional
 * weapon/projectile attachment sockets.
 */
data class AnimationFrameMeta(
    val frameIndex: Int,
    val tileName: String,
    val durationMs: Int,
    val pivotX: Int = 8,
    val pivotY: Int = 15,
    val handSocketX: Int? = null,
    val handSocketY: Int? = null
)

/**
 * Complete named animation sequence (e.g. `trojan_walk_down` or `kzarka_attack`) with
 * ordered [frames] and engine-ready [metadata].
 */
data class AnimationSequence(
    val name: String,
    val clip: SpriteAnimationClip,
    val direction: FacingDirection? = null,
    val frames: List<NamedTile>,
    val metadata: List<AnimationFrameMeta>
) {
    val looping: Boolean get() = clip.looping
    val totalDurationMs: Int get() = metadata.sumOf { it.durationMs }
    val images: List<TileImage> get() = frames.map { it.image }
}
