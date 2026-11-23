package com.morgana.tilegen.sprite

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Hand-pixelled form shading for sprites.
 *
 * The earlier sprite code sampled a ramp with a continuous gradient (`tone = base - x * 0.05 - y * 0.04`), which gives
 * diagonal streaks of one-pixel colour changes. Hand-drawn pixel art instead uses a few flat bands whose edges follow
 * the form: a lit side, a mid tone, a shaded side, and a darker underside. Everything here picks one of five fixed
 * tones from a [PaletteRamp], lit from the upper left like the rest of the Morgana art.
 */
internal object PixelForm {
    const val HIGHLIGHT = 0.94f
    const val LIT = 0.76f
    const val MID = 0.58f
    const val SHADE = 0.38f
    const val DEEP = 0.20f

    /** Maps a light amount (about -1..1) to one of the five band tones. */
    fun band(light: Float): Float = when {
        light > 0.78f -> HIGHLIGHT
        light > 0.50f -> LIT
        light > 0.20f -> MID
        light > -0.05f -> SHADE
        else -> DEEP
    }

    /**
     * A sphere-shaded ellipse (head, hair dome, helmet, pauldron, round shield). [bias] shifts every band lighter
     * (positive) or darker (negative), so a far-side part can reuse the same call.
     */
    fun dome(
        img: TileImage,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        pal: PaletteRamp,
        bias: Float = 0f,
        clip: (Int, Int) -> Boolean = { _, _ -> true }
    ) {
        val x0 = (cx - rx).toInt() - 1
        val x1 = (cx + rx).toInt() + 1
        val y0 = (cy - ry).toInt() - 1
        val y1 = (cy + ry).toInt() + 1
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (x < 0 || y < 0 || x >= img.width || y >= img.height) continue
                val nx = (x + 0.5f - cx) / rx
                val ny = (y + 0.5f - cy) / ry
                val r2 = nx * nx + ny * ny
                if (r2 > 1f || !clip(x, y)) continue
                val nz = sqrt(1f - r2)
                val light = -0.50f * nx - 0.58f * ny + 0.64f * nz + bias
                img.setPixel(x, y, pal.sample(band(light)))
            }
        }
    }

    /**
     * A rounded-rectangle column form (limb, torso, sleeve, boot): lit left column, shaded right column, darker bottom
     * row and a lighter top edge. [baseTone] picks the overall brightness (a far limb passes a lower one).
     * [round] trims the corner pixels so the silhouette is not a hard block.
     */
    fun column(
        img: TileImage,
        x0: Int,
        y0: Int,
        w: Int,
        h: Int,
        pal: PaletteRamp,
        baseTone: Float = MID,
        round: Boolean = true
    ) {
        if (w <= 0 || h <= 0) return
        for (j in 0 until h) {
            for (i in 0 until w) {
                val x = x0 + i
                val y = y0 + j
                if (x < 0 || y < 0 || x >= img.width || y >= img.height) continue
                if (round && w >= 3 && h >= 3 && (j == 0 || j == h - 1) && (i == 0 || i == w - 1)) continue
                var t = baseTone
                t += when {
                    w == 1 -> 0f
                    i == 0 -> 0.20f
                    i == w - 1 -> -0.22f
                    w >= 4 && i == w - 2 -> -0.08f
                    else -> 0f
                }
                if (h >= 3 && j == 0) t += 0.07f
                if (h >= 3 && j == h - 1) t -= 0.12f
                img.setPixel(x, y, pal.sample(t.coerceIn(0.05f, 0.98f)))
            }
        }
    }

    /**
     * Light amount for a point inside a [w] x [h] box, used by flat torso and cloth fills so they share the column look:
     * lit left edge, shaded right edge, dark hem.
     */
    fun boxLight(i: Int, j: Int, w: Int, h: Int, base: Float = 0.34f): Float {
        var l = base
        if (w > 1) {
            if (i == 0) l += 0.30f
            else if (i == w - 1) l -= 0.42f
            else if (i >= w - 2 && w >= 6) l -= 0.20f
            else if (i == 1 && w >= 6) l += 0.12f
        }
        if (h > 2) {
            if (j == 0) l += 0.14f
            if (j >= h - 1) l -= 0.20f
        }
        return l
    }

    /** One-pixel-wide line between two points (Bresenham), drawn with [colorAt] (0 at the start, 1 at the end). */
    fun line(img: TileImage, x0: Int, y0: Int, x1: Int, y1: Int, colorAt: (Float) -> Int) {
        val dx = abs(x1 - x0)
        val dy = abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx - dy
        var x = x0
        var y = y0
        val total = max(dx, dy).coerceAtLeast(1)
        var n = 0
        while (true) {
            if (x in 0 until img.width && y in 0 until img.height) img.setPixel(x, y, colorAt(n.toFloat() / total))
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 > -dy) { err -= dy; x += sx }
            if (e2 < dx) { err += dx; y += sy }
            n++
        }
    }

    /**
     * A two-pixel-wide blade from ([x0], [y0]) toward ([x1], [y1]): a bright edge, a darker spine pixel beside it and a
     * pointed tip, the way a hand-pixelled sword is drawn instead of a single 1 px line.
     */
    fun blade(img: TileImage, x0: Int, y0: Int, x1: Int, y1: Int, pal: PaletteRamp) {
        val steep = abs(y1 - y0) >= abs(x1 - x0)
        val total = max(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(1)
        val bright = pal.highlight
        val spine = pal.sample(0.42f)
        line(img, x0, y0, x1, y1) { t -> if (t > 0.9f) bright else pal.sample(0.80f) }
        // The spine runs along the right/back side of the bright edge, stopping short of the tip.
        val ox = if (steep) 1 else 0
        val oy = if (steep) 0 else 1
        line(img, x0 + ox, y0 + oy, x0 + ((x1 - x0) * (total - 1)) / total + ox, y0 + ((y1 - y0) * (total - 1)) / total + oy) { t ->
            if (t < 0.15f) spine else pal.sample(0.52f)
        }
    }

    /** Darker, cooler version of [rgba] for hand-picked accents (outline steps, creases). */
    fun darken(rgba: Int, amount: Float = 0.35f): Int {
        val r = ((rgba ushr 24) and 0xFF)
        val g = ((rgba ushr 16) and 0xFF)
        val b = ((rgba ushr 8) and 0xFF)
        val a = rgba and 0xFF
        val k = 1f - amount
        return PaletteRamp.rgba(
            (r * k * 0.96f).toInt().coerceIn(0, 255),
            (g * k).toInt().coerceIn(0, 255),
            min(255, (b * k + 255 * amount * 0.16f).toInt()),
            a
        )
    }
}
