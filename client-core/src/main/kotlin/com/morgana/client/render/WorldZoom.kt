package com.morgana.client.render

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Integer world zoom (1x, 2x, 3x). One world pixel becomes an exact whole number of screen pixels, so tiles stay crisp
 * and are not tiny on high-density phone screens. The libGDX camera uses `zoom = 1 / scale`.
 */
object WorldZoom {
    const val MIN = 1
    const val MAX = 3

    /** Fewest pixels (of world space) the short screen side should still show: about 10 tiles of 32 px. */
    private const val MIN_VISIBLE_SHORT_SIDE = 320

    /** The user's choice: 0 means "auto", otherwise a fixed 1..3. */
    fun resolve(setting: Int, screenWidth: Int, screenHeight: Int, density: Float): Int =
        if (setting in MIN..MAX) setting else auto(screenWidth, screenHeight, density)

    /** Picks a scale from screen density and resolution (2x on 900p+ screens), never showing fewer than ~10 tiles. */
    fun auto(screenWidth: Int, screenHeight: Int, density: Float): Int {
        val densityScale = floor(density.coerceAtLeast(1f) + 0.25f).toInt()
        val shortSide = max(1, min(screenWidth, screenHeight))
        val wanted = max(densityScale, if (shortSide >= 900) 2 else 1).coerceIn(MIN, MAX)
        val fits = max(MIN, shortSide / MIN_VISIBLE_SHORT_SIDE)
        return min(wanted, fits).coerceIn(MIN, MAX)
    }

    /** Camera zoom for a scale (smaller camera zoom = bigger picture). */
    fun cameraZoom(scale: Int): Float = 1f / scale.coerceIn(MIN, MAX)

    /** Snaps a camera coordinate to a whole screen pixel so scaled sprites do not shimmer. */
    fun snap(value: Float, scale: Int): Float = Math.round(value * scale) / scale.toFloat()

    /** Snaps a camera coordinate to a whole screen pixel at any scale, including the editor's in-between ones. */
    fun snap(value: Float, scale: Float): Float = if (scale <= 0f) value else Math.round(value * scale) / scale

    /**
     * The map editor's zoom levels, as screen pixels per world pixel. The game itself keeps whole steps (1x..3x) so pixel
     * art stays crisp; the editor adds in-between steps and a wider range, because 1x to 2x is too big a jump when
     * looking over a map, and zooming out past 1x shows a whole map at once.
     */
    val EDITOR_LEVELS: List<Float> = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

    /**
     * The editor zoom level [delta] steps from [current] (zoom in = +1). A value between levels first moves to the
     * neighbouring level in that direction; the ends stop.
     */
    fun stepEditor(current: Float, delta: Int): Float {
        if (delta == 0) return EDITOR_LEVELS.minByOrNull { Math.abs(it - current) } ?: 1f
        val eps = 0.001f
        var index = if (delta > 0) EDITOR_LEVELS.indexOfLast { it <= current + eps } else EDITOR_LEVELS.indexOfFirst { it >= current - eps }
        if (index < 0) index = if (delta > 0) -1 else EDITOR_LEVELS.size
        val onLevel = EDITOR_LEVELS.getOrNull(index)?.let { Math.abs(it - current) < eps } == true
        val target = if (onLevel) index + delta else if (delta > 0) index + 1 else index - 1
        return EDITOR_LEVELS[target.coerceIn(0, EDITOR_LEVELS.lastIndex)]
    }

    /** "125%" for a scale of 1.25. */
    fun percentLabel(scale: Float): String = "${Math.round(scale * 100)}%"

    /** Moves the resolved scale [current] by [delta] steps (zoom in = +1), staying within 1x..3x; returns the new fixed setting. */
    fun step(current: Int, delta: Int): Int = (current + delta).coerceIn(MIN, MAX)

    /** Cycles Auto -> 1x -> 2x -> 3x -> Auto. */
    fun nextSetting(setting: Int): Int = if (setting >= MAX || setting < 0) 0 else setting + 1

    fun label(setting: Int): String = if (setting in MIN..MAX) "${setting}x" else "Auto"
}
