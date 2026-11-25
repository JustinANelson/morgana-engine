package com.morgana.tilegen.structure

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.SpriteFinish
import com.morgana.tilegen.material.TileNoise
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Overworld barrier and graveyard props in the same flat-shaded, upper-left-lit style as [NatureProps]: mountain
 * peaks and rock spires (optionally snow-capped), tombstones, and a rune-sealed gate for a blocked passage.
 *
 * Every prop is drawn relative to its canvas, so it works at any tile size. Palettes run dark (index 0) to light.
 * Deterministic for a seed.
 */
object MountainProps {
    private fun h(x: Int, y: Int, s: Long): Float = TileNoise.hash(x, y, s)

    private fun tone(r: PaletteRamp, i: Int): Int = r.colors[i.coerceIn(0, r.colors.lastIndex)]

    private fun put(img: TileImage, x: Int, y: Int, rgba: Int) {
        if (x in 0 until img.width && y in 0 until img.height) img.setPixel(x, y, rgba)
    }

    /** Soft translucent ground shadow. */
    private fun groundShadow(img: TileImage, cx: Float, cy: Float, rx: Float, ry: Float) {
        val shadow = PaletteRamp.rgba(10, 16, 18, 96)
        for (y in (cy - ry - 1).toInt()..(cy + ry + 1).toInt()) {
            for (x in (cx - rx - 1).toInt()..(cx + rx + 1).toInt()) {
                if (x !in 0 until img.width || y !in 0 until img.height) continue
                val nx = (x + 0.5f - cx) / rx
                val ny = (y + 0.5f - cy) / ry
                if (hypot(nx, ny) < 1f) img.drawPixel(x, y, shadow)
            }
        }
    }

    private class Peak(val apexX: Float, val apexY: Float, val baseY: Float, val halfBase: Float)

    /** The left and right edge of a peak on row [y], with a ragged slope, or null above the apex or below the base. */
    private fun span(p: Peak, y: Int, s: Long): Pair<Float, Float>? {
        if (y < p.apexY || y > p.baseY) return null
        val t = (y - p.apexY) / (p.baseY - p.apexY)
        val width = p.halfBase * t.pow(0.8f)
        val jagL = (h(y / 3, 7, s) - 0.5f) * p.halfBase * 0.20f * t
        val jagR = (h(y / 3, 11, s) - 0.5f) * p.halfBase * 0.20f * t
        return Pair(p.apexX - width + jagL, p.apexX + width + jagR)
    }

    private fun paintPeak(img: TileImage, p: Peak, rock: PaletteRamp, snow: PaletteRamp?, s: Long) {
        val rows = (p.baseY - p.apexY).toInt() + 1
        val covered = Array(rows) { BooleanArray(img.width) }
        for (row in 0 until rows) {
            val y = p.apexY.toInt() + row
            val sp = span(p, y, s) ?: continue
            for (x in sp.first.toInt()..sp.second.toInt()) if (x in 0 until img.width) covered[row][x] = true
        }
        fun isIn(x: Int, row: Int): Boolean = row in 0 until rows && x in 0 until img.width && covered[row][x]
        for (row in 0 until rows) {
            val y = p.apexY.toInt() + row
            val t = row.toFloat() / rows
            for (x in 0 until img.width) {
                if (!covered[row][x]) continue
                val edge = !isIn(x, row - 1) || !isIn(x, row + 1) || !isIn(x - 1, row) || !isIn(x + 1, row)
                val side = x + 0.5f - p.apexX
                val ridge = 1.5f + t * 2f
                val snowLine = 0.28f + 0.10f * h(x / 4, 3, s)
                val colour = when {
                    edge -> tone(rock, 0)
                    snow != null && t < snowLine -> when {
                        abs(side) < ridge -> tone(snow, snow.colors.lastIndex)
                        side < 0 -> tone(snow, 3)
                        else -> tone(snow, 1)
                    }
                    abs(side) < ridge -> tone(rock, 4)
                    side < 0 -> {
                        val crease = ((x * 3 + y * 5 + (h(x / 6, y / 6, s) * 8).toInt()) % 23) == 0
                        tone(rock, if (crease) 2 else 3)
                    }
                    else -> {
                        val band = (y / 3 + (h(0, y / 3, s) * 3f).toInt()) % 5 == 0
                        tone(rock, if (band) 0 else 1)
                    }
                }
                put(img, x, y, colour)
            }
        }
    }

    private fun scree(img: TileImage, from: Float, to: Float, y: Float, rock: PaletteRamp, s: Long) {
        for (x in from.toInt()..to.toInt()) {
            for (dy in 0..2) {
                if (h(x, dy + 40, s) > 0.55f) put(img, x, y.toInt() + dy, tone(rock, 1 + (h(x, dy, s) * 2f).toInt()))
            }
        }
    }

    /** A mountain peak (4 x 4 tiles): a tall centre peak and two lower shoulders; [snow] adds snow caps. */
    fun mountainPeak(img: TileImage, w: Int, hh: Int, rock: PaletteRamp, snow: PaletteRamp?, s: Long) {
        val fw = w.toFloat()
        val fh = hh.toFloat()
        val base = fh * 0.88f
        groundShadow(img, fw * 0.5f, base, fw * 0.47f, fh * 0.05f)
        val jit = (h(1, 1, s) - 0.5f) * fw * 0.06f
        paintPeak(img, Peak(fw * 0.50f + jit, fh * 0.05f, base, fw * 0.44f), rock, snow, s + 1L)
        paintPeak(img, Peak(fw * 0.24f - jit, fh * 0.36f, base + fh * 0.02f, fw * 0.28f), rock, snow, s + 2L)
        paintPeak(img, Peak(fw * 0.77f + jit, fh * 0.30f, base + fh * 0.02f, fw * 0.30f), rock, snow, s + 3L)
        scree(img, fw * 0.06f, fw * 0.94f, base + fh * 0.01f, rock, s + 4L)
    }

    /** A cluster of three rock spires (2 x 3 tiles); [snow] adds snow caps. */
    fun rockSpire(img: TileImage, w: Int, hh: Int, rock: PaletteRamp, snow: PaletteRamp?, s: Long) {
        val fw = w.toFloat()
        val fh = hh.toFloat()
        val base = fh * 0.90f
        groundShadow(img, fw * 0.5f, base, fw * 0.46f, fh * 0.04f)
        paintPeak(img, Peak(fw * 0.62f, fh * 0.03f, base, fw * 0.20f), rock, snow, s + 1L)
        paintPeak(img, Peak(fw * 0.30f, fh * 0.14f, base + fh * 0.01f, fw * 0.22f), rock, snow, s + 2L)
        paintPeak(img, Peak(fw * 0.82f, fh * 0.34f, base + fh * 0.01f, fw * 0.15f), rock, snow, s + 3L)
        scree(img, fw * 0.08f, fw * 0.92f, base + fh * 0.01f, rock, s + 4L)
    }

    /** A tombstone (1 x 1 tile): a headstone, a cross or a low slab picked by the seed, with moss at the foot. */
    fun tombstone(img: TileImage, w: Int, hh: Int, stone: PaletteRamp, moss: PaletteRamp, s: Long) {
        val fw = w.toFloat()
        val fh = hh.toFloat()
        val canvas = TileImage(w, hh)
        val cx = fw * 0.5f
        val foot = fh * 0.82f
        groundShadow(canvas, cx, foot + fh * 0.02f, fw * 0.36f, fh * 0.07f)
        val variant = (h(1, 2, s) * 3f).toInt().coerceIn(0, 2)
        fun inside(x: Int, y: Int): Boolean {
            val fx = x + 0.5f
            val fy = y + 0.5f
            return when (variant) {
                0 -> {
                    val half = fw * 0.20f
                    val topY = fh * 0.24f
                    val arcCy = topY + half
                    fy in topY..foot && abs(fx - cx) <= half && (fy >= arcCy || hypot(fx - cx, fy - arcCy) <= half)
                }
                1 -> {
                    val stem = abs(fx - cx) <= fw * 0.07f && fy in fh * 0.14f..foot
                    val arm = abs(fx - cx) <= fw * 0.22f && fy in fh * 0.28f..fh * 0.40f
                    stem || arm
                }
                else -> abs(fx - cx) <= fw * 0.32f && fy in fh * 0.46f..foot
            }
        }
        for (y in 0 until hh) {
            for (x in 0 until w) {
                if (!inside(x, y)) continue
                val edge = !inside(x, y - 1) || !inside(x, y + 1) || !inside(x - 1, y) || !inside(x + 1, y)
                val lit = x + 0.5f < cx - fw * 0.02f
                val idx = when {
                    edge -> 0
                    y < fh * 0.34f && lit -> 4
                    lit -> 3
                    else -> 1
                }
                put(canvas, x, y, tone(stone, idx))
            }
        }
        // Carved marks.
        if (variant == 0) {
            for (dy in 0..(fh * 0.16f).toInt()) put(canvas, cx.toInt(), (fh * 0.38f).toInt() + dy, tone(stone, 1))
            for (dx in -(fw * 0.07f).toInt()..(fw * 0.07f).toInt()) put(canvas, cx.toInt() + dx, (fh * 0.43f).toInt(), tone(stone, 1))
        } else if (variant == 2) {
            for (dx in -(fw * 0.18f).toInt()..(fw * 0.18f).toInt()) put(canvas, cx.toInt() + dx, (fh * 0.56f).toInt(), tone(stone, 1))
            for (dx in -(fw * 0.12f).toInt()..(fw * 0.12f).toInt()) put(canvas, cx.toInt() + dx, (fh * 0.64f).toInt(), tone(stone, 1))
        }
        // Moss and grass at the foot.
        for (x in (cx - fw * 0.30f).toInt()..(cx + fw * 0.30f).toInt()) {
            for (dy in 0..2) {
                if (h(x, dy + 9, s) > 0.5f) put(canvas, x, foot.toInt() - dy + 1, tone(moss, 2 + (h(x, dy, s) * 2f).toInt()))
            }
        }
        img.drawImage(SpriteFinish.softOutline(canvas), 0, 0, w, hh, 0, 0)
    }

    /**
     * A rune-sealed gate (4 x 3 tiles): two crenellated towers, a wall, and an arch filled by a banded door with a
     * glowing seal and two chains. [stone] is the masonry, [glow] the seal (the palette's light end is the bright core).
     */
    fun sealedGate(img: TileImage, w: Int, hh: Int, stone: PaletteRamp, glow: PaletteRamp, s: Long) {
        val fw = w.toFloat()
        val fh = hh.toFloat()
        val canvas = TileImage(w, hh)
        val base = fh * 0.92f
        groundShadow(canvas, fw * 0.5f, base + fh * 0.01f, fw * 0.48f, fh * 0.05f)

        val towerW = fw * 0.20f
        val wallTop = fh * 0.34f
        val towerTop = fh * 0.08f
        val archL = fw * 0.34f
        val archR = fw * 0.66f
        val archRad = (archR - archL) / 2f
        val archCx = fw * 0.5f
        val archTop = fh * 0.30f + archRad

        fun isTower(x: Float) = x < towerW || x > fw - towerW
        fun masonry(x: Int, y: Int, lit: Boolean): Int {
            val course = y / 8
            val offset = if (course % 2 == 0) 0 else 6
            val seam = y % 8 == 0 || (x + offset) % 12 == 0
            val level = if (lit) 3 else 2
            return tone(stone, if (seam) level - 1 else level + (if (h(x / 3, y / 3, s) > 0.8f) 1 else 0))
        }
        fun inArch(x: Float, y: Float): Boolean {
            if (y > base) return false
            if (x < archL || x > archR) return false
            return y >= archTop || hypot(x - archCx, y - archTop) <= archRad
        }

        for (y in 0 until hh) {
            for (x in 0 until w) {
                val fx = x + 0.5f
                val fy = y + 0.5f
                if (fy > base) continue
                val tower = isTower(fx) && fy >= towerTop
                val wall = !isTower(fx) && fy >= wallTop
                if (!tower && !wall) continue
                // Crenellation notches on the towers.
                if (tower && fy < towerTop + fh * 0.07f && ((fx / (fw * 0.05f)).toInt() % 2 == 1)) continue
                if (inArch(fx, fy)) continue
                val lit = fx < fw * 0.5f
                put(canvas, x, y, masonry(x, y, lit))
            }
        }

        // The door filling the arch: dark planks, two iron bands.
        for (y in 0 until hh) {
            for (x in 0 until w) {
                val fx = x + 0.5f
                val fy = y + 0.5f
                if (!inArch(fx, fy)) continue
                val plank = ((x - archL.toInt()) / 6) % 2 == 0
                val band = abs(fy - fh * 0.52f) < 2f || abs(fy - fh * 0.78f) < 2f
                val c = when {
                    band -> tone(stone, 0)
                    plank -> tone(stone, 1)
                    else -> tone(stone, 0)
                }
                put(canvas, x, y, c)
            }
        }

        // Glowing seal: a halo, two rings and eight rune ticks.
        val sx = archCx
        val sy = fh * 0.62f
        val radius = archRad * 0.70f
        for (y in (sy - radius * 2f).toInt()..(sy + radius * 2f).toInt()) {
            for (x in (sx - radius * 2f).toInt()..(sx + radius * 2f).toInt()) {
                if (x !in 0 until w || y !in 0 until hh) continue
                val d = hypot(x + 0.5f - sx, y + 0.5f - sy)
                if (d < radius * 2f && inArch(x + 0.5f, y + 0.5f)) {
                    val a = ((1f - d / (radius * 2f)) * 120f).toInt().coerceIn(0, 120)
                    canvas.drawPixel(x, y, (tone(glow, 2) and 0xFFFFFF00.toInt()) or a)
                }
            }
        }
        for (step in 0 until 96) {
            val a = step / 96f * 2f * PI.toFloat()
            put(canvas, (sx + cos(a) * radius).toInt(), (sy + sin(a) * radius).toInt(), tone(glow, glow.colors.lastIndex))
            put(canvas, (sx + cos(a) * radius * 0.5f).toInt(), (sy + sin(a) * radius * 0.5f).toInt(), tone(glow, glow.colors.lastIndex - 1))
        }
        for (k in 0 until 8) {
            val a = k / 8f * 2f * PI.toFloat() + PI.toFloat() / 8f
            for (r in 0..2) {
                put(canvas, (sx + cos(a) * (radius * 0.62f + r)).toInt(), (sy + sin(a) * (radius * 0.62f + r)).toInt(), tone(glow, glow.colors.lastIndex - 2))
            }
        }
        // Two chains from the top of the arch to the seal.
        for (side in intArrayOf(-1, 1)) {
            val x0 = archCx + side * archRad * 0.8f
            val y0 = fh * 0.38f
            val steps = 28
            for (i in 0..steps) {
                if (i % 3 == 2) continue
                val t = i / steps.toFloat()
                put(canvas, (x0 + (sx - x0) * t).toInt(), (y0 + (sy - y0) * t).toInt(), tone(stone, 4))
            }
        }

        img.drawImage(SpriteFinish.softOutline(canvas), 0, 0, w, hh, 0, 0)
    }
}
