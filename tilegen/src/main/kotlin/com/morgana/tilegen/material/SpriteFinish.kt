package com.morgana.tilegen.material

import com.morgana.tilegen.TileImage
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The Morgana house finish for characters, monsters and gear ([PixelStyle.MORGANA]).
 *
 * The map art (grass, water, the oak and pine trees) is soft: muted hue-shifted ramps, a dark outline that is only a
 * darker step of the colour inside it, a light from the upper left and translucent ground shadows. Sprites drawn as flat
 * fills with a near-black outline look pasted on top of it. This pass gives a flat-filled silhouette the same treatment:
 *
 * 1. **Rim shading** - pixels one step inside the lit (top and left) edge are lightened and warmed, pixels one step
 *    inside the shadow (bottom and right) edge are darkened and cooled, and the second step gets part of that, so a flat
 *    shape reads as a rounded volume instead of a sticker. Thin parts (a 2 px sword) are left alone.
 * 2. **Muted colour** - saturation is eased and capped so a pure red tunic sits next to a muted meadow.
 * 3. **Soft outline** - one pixel outside the silhouette in a darker, cooler version of the nearest fill (lighter on
 *    the lit side), never a flat black.
 *
 * Deterministic and per-layer, so worn gear keeps lining up with the body under it.
 */
object SpriteFinish {
    /** Dark, slightly blue-green anchor the shadow-side outline is pulled toward (the trees' outline is the same kind of colour). */
    private val OUTLINE_ANCHOR = PaletteRamp.rgba(26, 34, 44)

    /** Saturation is multiplied by this and then capped, so reds and golds sit with the muted ground. */
    private const val SATURATION_SCALE = 0.90f
    private const val SATURATION_CAP = 0.80f

    private const val OPAQUE = 160

    /** Shades and outlines one layer or finished frame. [outline] = false skips the outline. */
    fun finish(source: TileImage, outline: Boolean = true): TileImage {
        val shaded = shade(source)
        return if (outline) softOutline(shaded) else shaded
    }

    /** Rim shading, hue shift and saturation easing; transparent and translucent (shadow) pixels are untouched. */
    fun shade(source: TileImage): TileImage {
        val w = source.width
        val h = source.height
        val out = source.copy()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = source.getPixel(x, y)
                if ((px and 0xFF) < OPAQUE) continue
                val lit = min(run(source, x, y, -1, 0), run(source, x, y, 0, -1))
                val dark = min(run(source, x, y, 1, 0), run(source, x, y, 0, 1))
                var amount = 0f
                if (lit == 1) amount += 0.13f else if (lit == 2) amount += 0.05f
                if (dark == 1) amount -= 0.15f else if (dark == 2) amount -= 0.06f
                // A part that is both lit and shaded at the same step is thin: keep its own colour.
                if (lit <= 2 && dark <= 2 && lit == dark) amount = 0f
                out.setPixel(x, y, tone(px, amount))
            }
        }
        return out
    }

    /** Distance in pixels from ([x], [y]) to the first non-opaque pixel in direction ([dx], [dy]), capped at 3. */
    private fun run(img: TileImage, x: Int, y: Int, dx: Int, dy: Int): Int {
        var n = 1
        while (n <= 2) {
            val nx = x + dx * n
            val ny = y + dy * n
            if (nx < 0 || ny < 0 || nx >= img.width || ny >= img.height) return n
            if ((img.getPixel(nx, ny) and 0xFF) < OPAQUE) return n
            n++
        }
        return 3
    }

    /** Lightens (`amount` > 0, warmer) or darkens (`amount` < 0, cooler) a colour and eases its saturation. */
    private fun tone(rgba: Int, amount: Float): Int {
        val a = rgba and 0xFF
        val r = ((rgba ushr 24) and 0xFF) / 255f
        val g = ((rgba ushr 16) and 0xFF) / 255f
        val b = ((rgba ushr 8) and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val s = if (mx <= 0f) 0f else (mx - mn) / mx
        val s2 = min(s * SATURATION_SCALE, SATURATION_CAP)
        // Rebuild the colour with the eased saturation, keeping hue: scale the distance of each channel from the max.
        val k = if (s <= 0f) 0f else s2 / s
        var nr = mx - (mx - r) * k
        var ng = mx - (mx - g) * k
        var nb = mx - (mx - b) * k
        if (amount > 0f) {
            nr += amount * (1f - nr) * 1.10f
            ng += amount * (1f - ng) * 0.95f
            nb += amount * (1f - nb) * 0.70f
        } else if (amount < 0f) {
            val d = -amount
            nr *= 1f - d * 1.20f
            ng *= 1f - d * 0.95f
            nb = nb * (1f - d * 0.55f) + d * 0.10f
        }
        return PaletteRamp.rgba(c255(nr), c255(ng), c255(nb), a)
    }

    private fun c255(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    /** A one-pixel outline outside the opaque silhouette, coloured from the neighbouring fill. */
    fun softOutline(source: TileImage): TileImage {
        val w = source.width
        val h = source.height
        val out = source.copy()
        val ar = (OUTLINE_ANCHOR ushr 24) and 0xFF
        val ag = (OUTLINE_ANCHOR ushr 16) and 0xFF
        val ab = (OUTLINE_ANCHOR ushr 8) and 0xFF
        // Neighbour offsets: a fill pixel to the right or below means this pixel is on the lit (top / left) edge.
        val dx = intArrayOf(1, 0, -1, 0)
        val dy = intArrayOf(0, 1, 0, -1)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((source.getPixel(x, y) and 0xFF) >= OPAQUE) continue
                var sr = 0
                var sg = 0
                var sb = 0
                var n = 0
                var lit = 0
                for (i in 0 until 4) {
                    val nx = x + dx[i]
                    val ny = y + dy[i]
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val p = source.getPixel(nx, ny)
                    if ((p and 0xFF) < 200) continue
                    sr += (p ushr 24) and 0xFF
                    sg += (p ushr 16) and 0xFF
                    sb += (p ushr 8) and 0xFF
                    n++
                    lit += if (i < 2) 1 else -1
                }
                if (n == 0) continue
                val isLit = lit > 0
                val factor = if (isLit) 0.50f else 0.34f
                val mix = if (isLit) 0.18f else 0.40f
                val or = ((sr / n) * factor * (1f - mix) + ar * mix).toInt().coerceIn(0, 255)
                val og = ((sg / n) * factor * (1f - mix) + ag * mix).toInt().coerceIn(0, 255)
                val ob = ((sb / n) * factor * (1f - mix) + ab * mix).toInt().coerceIn(0, 255)
                out.setPixel(x, y, PaletteRamp.rgba(or, og, ob, 255))
            }
        }
        return out
    }

    /**
     * A soft two-ring ground shadow like the one under the trees: a translucent, grass-tinted core with a fainter rim.
     * Drawn with source-over so it can sit under a sprite already on [img].
     */
    fun softShadow(img: TileImage, cx: Float, cy: Float, rx: Float, ry: Float) {
        val core = PaletteRamp.rgba(10, 18, 12, 96)
        val rim = PaletteRamp.rgba(10, 18, 12, 48)
        for (y in (cy - ry - 1).toInt().coerceAtLeast(0)..(cy + ry + 1).toInt().coerceAtMost(img.height - 1)) {
            for (x in (cx - rx - 1).toInt().coerceAtLeast(0)..(cx + rx + 1).toInt().coerceAtMost(img.width - 1)) {
                val d = hypot((x + 0.5f - cx) / rx, (y + 0.5f - cy) / ry)
                if (d <= 0.72f) img.drawPixel(x, y, core) else if (d <= 1f) img.drawPixel(x, y, rim)
            }
        }
    }
}
