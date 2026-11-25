package com.morgana.tilegen.structure

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.SpriteFinish
import com.morgana.tilegen.material.TileNoise
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Small props redrawn in the calm, chunky style of commercial top-down sheets: flat tones lit from the upper left, a
 * soft outline around the silhouette, and a translucent ground shadow. Drawn at 32 px per tile and scaled by the caller
 * when the tile size differs. Palettes run dark (index 0) to light.
 */
object SmoothProps {
    private fun tone(r: PaletteRamp, i: Int): Int = r.colors[i.coerceIn(0, r.colors.lastIndex)]

    private class Cv(val img: TileImage, val seed: Long) {
        fun px(x: Int, y: Int, c: Int) {
            if (x in 0 until img.width && y in 0 until img.height) img.setPixel(x, y, c)
        }

        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
            for (y in y0..y1) for (x in x0..x1) px(x, y, c)
        }

        fun blend(x: Int, y: Int, c: Int) {
            if (x in 0 until img.width && y in 0 until img.height) img.drawPixel(x, y, c)
        }

        fun hash(x: Int, y: Int, salt: Long = 0L): Float = TileNoise.hash(x, y, seed + salt)

        fun shadow(cx: Int, cy: Int, rx: Int, ry: Int) {
            for (y in cy - ry..cy + ry) for (x in cx - rx..cx + rx) {
                val nx = (x - cx).toFloat() / rx
                val ny = (y - cy).toFloat() / ry
                if (nx * nx + ny * ny <= 1f) blend(x, y, PaletteRamp.rgba(14, 22, 12, 80))
            }
        }

        /** Fills an ellipse; [f] gets the normalised position and returns a colour. */
        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, f: (Float, Float) -> Int) {
            for (y in (cy - ry).toInt()..(cy + ry).toInt() + 1) for (x in (cx - rx).toInt()..(cx + rx).toInt() + 1) {
                val nx = (x + 0.5f - cx) / rx
                val ny = (y + 0.5f - cy) / ry
                if (nx * nx + ny * ny <= 1f) px(x, y, f(nx, ny))
            }
        }
    }

    private fun lit(nx: Float, hi: Int = 4): Int = if (nx < -0.25f) hi else if (nx < 0.35f) hi - 1 else hi - 2

    // ---- the props -----------------------------------------------------------------------------------------------

    private fun barrel(c: Cv, wood: PaletteRamp, metal: PaletteRamp) {
        c.shadow(16, 28, 11, 3)
        fun bulge(y: Int) = 9 + (3f * sin(PI * (y - 7) / 21f)).roundToInt()
        for (y in 7..27) {
            val b = bulge(y)
            for (x in 16 - b until 16 + b) {
                val lx = (x - (16 - b)) / (2f * b)
                var i = if (lx < 0.33f) 3 else if (lx < 0.7f) 2 else 1
                if ((x - 16 + b) % 4 == 0) i -= 1
                c.px(x, y, tone(wood, i))
            }
        }
        for (hy in intArrayOf(11, 22)) for (y in hy..hy + 1) {
            val b = bulge(y)
            for (x in 16 - b until 16 + b) c.px(x, y, tone(metal, if (y == hy) 3 else 1))
        }
        c.ellipse(16f, 8f, 10f, 4f) { nx, ny -> if (hypot(nx, ny) > 0.78f) tone(wood, 1) else tone(wood, if (nx < 0) 4 else 3) }
    }

    private fun crate(c: Cv, wood: PaletteRamp, metal: PaletteRamp) {
        c.shadow(16, 28, 12, 3)
        for (y in 12..27) for (x in 6..25) {
            val edge = x < 9 || x > 22 || y < 15 || y > 24
            c.px(x, y, tone(wood, if (edge) (if (x < 16) 3 else 2) else 2))
        }
        for (k in 0..13) { c.px(9 + k, 15 + k * 9 / 13 + 0, tone(wood, 1)); c.px(22 - k, 15 + k * 9 / 13, tone(wood, 1)) }
        for (y in 7..11) for (x in 6..25) c.px(x, y, tone(wood, if (y == 7) 4 else 4))
        for (x in 6..25) c.px(x, 11, tone(wood, 2))
        for (cx in intArrayOf(7, 24)) for (cy in intArrayOf(13, 26)) c.px(cx, cy, tone(metal, 4))
        for (y in 12..27) { c.px(6, y, tone(wood, 1)); c.px(25, y, tone(wood, 0)) }
    }

    private fun chest(c: Cv, wood: PaletteRamp, metal: PaletteRamp) {
        c.shadow(16, 27, 12, 3)
        for (y in 16..26) for (x in 5..26) c.px(x, y, tone(wood, if (x < 13) 3 else if (x < 21) 2 else 1))
        c.ellipse(15.5f, 16f, 11f, 8f) { nx, ny -> if (ny > 0.15f) tone(wood, 2) else tone(wood, if (nx < -0.1f) 4 else 3) }
        for (bx in intArrayOf(9, 22)) for (y in 9..26) c.px(bx, y, tone(metal, if (bx < 16) 3 else 2))
        c.rect(5, 16, 26, 16, tone(metal, 2))
        c.rect(14, 15, 18, 20, tone(PalettePresets.RUNE_GOLD, 3)); c.rect(15, 17, 17, 18, tone(PalettePresets.RUNE_GOLD, 1))
    }

    private fun urn(c: Cv, stone: PaletteRamp, accent: PaletteRamp) {
        c.shadow(16, 28, 9, 3)
        fun radius(y: Int): Int = when {
            y < 9 -> 5
            y < 13 -> 3
            y < 20 -> (3 + 8 * sin(PI * (y - 12) / 16f)).roundToInt()
            else -> (5 + (27 - y) / 3)
        }
        for (y in 6..27) {
            val r = radius(y)
            for (x in 16 - r..16 + r) {
                val lx = (x - (16 - r)) / (2f * r)
                c.px(x, y, tone(stone, if (lx < 0.33f) 4 else if (lx < 0.68f) 3 else 2))
            }
        }
        for (x in 11..21) c.px(x, 14, tone(accent, 3))
        for (x in 12..20) c.px(x, 15, tone(accent, 2))
        for (x in 12..20) c.px(x, 21, tone(accent, 3))
        for (y in 6..7) for (x in 10..22) c.px(x, y, tone(stone, 4))
    }

    private fun tent(c: Cv, cloth: PaletteRamp, wood: PaletteRamp) {
        c.shadow(48, 58, 40, 4)
        val apexX = 48
        val apexY = 8
        val baseY = 56
        for (y in apexY..baseY) {
            val half = 4 + (y - apexY) * 38 / (baseY - apexY)
            for (x in apexX - half..apexX + half) {
                val t = (x - (apexX - half)).toFloat() / (2 * half + 1)
                c.px(x, y, tone(cloth, if (t < 0.5f) 3 else 2))
            }
        }
        // Centre seam, door opening and scalloped skirt.
        for (y in apexY + 2..baseY) c.px(apexX, y, tone(cloth, 1))
        for (y in 34..baseY) {
            val half = (y - 34) * 9 / (baseY - 34) + 2
            for (x in apexX - half..apexX + half) c.px(x, y, tone(wood, 0))
        }
        for (x in 8..88) if ((x / 5) % 2 == 0) for (y in baseY - 2..baseY) c.px(x, y, tone(cloth, 4)) else c.px(x, baseY, tone(cloth, 1))
        c.rect(apexX, 0, apexX, apexY, tone(wood, 3))
        for (k in 0..4) { c.px(apexX + 1 + k, 1 + k / 2, tone(cloth, 4)) }
        for (side in intArrayOf(-1, 1)) for (k in 0..8) c.px(apexX + side * (44 + k / 2), baseY - 2 - k * 2, tone(wood, 1))
    }

    private fun wagon(c: Cv, wood: PaletteRamp, canvasCloth: PaletteRamp) {
        c.shadow(48, 58, 38, 4)
        // Bed
        for (y in 34..48) for (x in 14..80) c.px(x, y, tone(wood, if ((x / 6) % 2 == 0) 3 else 2))
        for (x in 14..80) { c.px(x, 34, tone(wood, 4)); c.px(x, 48, tone(wood, 0)) }
        // Canopy
        c.ellipse(47f, 34f, 33f, 24f) { nx, ny ->
            if (ny > 0f) tone(canvasCloth, 0) else {
                val rib = (((nx + 1f) * 33f).toInt() % 11) == 0
                if (rib) tone(canvasCloth, 2) else tone(canvasCloth, if (nx < 0f) 4 else 3)
            }
        }
        for (y in 34..48) for (x in 14..80) if (y > 34 && c.img.getPixel(x, y) == 0) c.px(x, y, tone(wood, 2))
        c.rect(14, 33, 80, 34, tone(wood, 4))
        // Wheels
        for (wx in intArrayOf(26, 68)) {
            c.ellipse(wx.toFloat(), 51f, 10f, 10f) { nx, ny ->
                val r = hypot(nx, ny)
                if (r > 0.78f) tone(wood, 0) else if (r < 0.22f) tone(wood, 4) else {
                    val a = kotlin.math.atan2(ny, nx)
                    if (abs(sin(a * 4f)) > 0.93f) tone(wood, 3) else tone(wood, 1)
                }
            }
        }
        for (x in 2..14) c.px(x, 44 + (14 - x) / 5, tone(wood, 1))
    }

    private fun fence(c: Cv, wood: PaletteRamp) {
        c.shadow(48, 29, 42, 2)
        for (y in 10..13) for (x in 0..95) c.px(x, y, tone(wood, if (y == 10) 4 else if (y == 13) 1 else 3))
        for (y in 20..23) for (x in 0..95) c.px(x, y, tone(wood, if (y == 20) 4 else if (y == 23) 1 else 3))
        for (px in intArrayOf(8, 44, 80)) {
            for (y in 5..28) for (x in px..px + 6) c.px(x, y, tone(wood, if (x < px + 3) 3 else if (x < px + 6) 2 else 1))
            for (x in px..px + 6) c.px(x, 5, tone(wood, 4))
            c.px(px + 3, 4, tone(wood, 4)); c.px(px + 2, 4, tone(wood, 3)); c.px(px + 4, 4, tone(wood, 3))
        }
    }

    private fun bridge(c: Cv, wood: PaletteRamp) {
        for (y in 7..25) for (x in 0..95) {
            val seam = (x % 8) == 0
            c.px(x, y, tone(wood, if (seam) 1 else if ((x / 8) % 2 == 0) 3 else 2))
        }
        for (x in 0..95) { c.px(x, 7, tone(wood, 4)); c.px(x, 25, tone(wood, 0)) }
        for (ry in intArrayOf(2, 26)) for (y in ry..ry + 3) for (x in 0..95) c.px(x, y, tone(wood, if (y == ry) 4 else if (y == ry + 3) 1 else 3))
        for (px in intArrayOf(2, 46, 90)) for (y in 0..5) for (x in px..px + 4) c.px(x, y, tone(wood, if (x < px + 2) 4 else 2))
        for (px in intArrayOf(2, 46, 90)) for (y in 26..31) for (x in px..px + 4) c.px(x, y, tone(wood, if (x < px + 2) 3 else 1))
    }

    private fun archway(c: Cv, stone: PaletteRamp, moss: PaletteRamp) {
        c.shadow(48, 61, 44, 3)
        fun masonry(x: Int, y: Int): Int {
            val course = y / 7
            val off = if (course % 2 == 0) 0 else 6
            val joint = (x + off) % 12 == 0 || y % 7 == 0
            val h = c.hash((x + off) / 12, course, 7L)
            val i = (if (x < 48) 3 else 2) + (if (h > 0.75f) 1 else 0)
            return if (joint) tone(stone, 1) else tone(stone, i)
        }
        // Wall mass with a semicircular opening.
        val cx = 48f
        val cy = 36f
        val r = 17f
        for (y in 6..60) for (x in 4..92) {
            val inOpening = x in (cx - r).toInt()..(cx + r).toInt() && (y >= cy || hypot(x + 0.5f - cx, y + 0.5f - cy) <= r)
            if (inOpening) {
                c.px(x, y, tone(stone, 0))
            } else if (y > 12 || (x in 14..82 && y > 6)) {
                c.px(x, y, masonry(x, y))
            }
        }
        // Arch ring and keystone.
        for (a in 0..180) {
            val rad = a * PI.toFloat() / 180f
            for (k in 0..3) {
                val rr = r + k
                c.px((cx - cos(rad) * rr).toInt(), (cy - sin(rad) * rr).toInt(), tone(stone, if (k == 0) 1 else 4))
            }
        }
        c.rect(44, 14, 52, 20, tone(stone, 4)); c.rect(44, 14, 52, 14, tone(stone, 0))
        // Mossy top and a ragged crown.
        for (x in 14..82) if (c.hash(x, 1, 9L) > 0.45f) { c.px(x, 6, tone(moss, 3)); c.px(x, 7, tone(moss, 2)) }
        for (x in 4..92) for (y in 56..60) if (c.hash(x, y, 11L) > 0.8f) c.px(x, y, tone(moss, 2))
    }

    private fun sarcophagus(c: Cv, stone: PaletteRamp, accent: PaletteRamp) {
        c.shadow(16, 60, 12, 3)
        for (y in 8..58) {
            val half = if (y < 16) 7 + (y - 8) / 3 else 10
            for (x in 16 - half..16 + half) {
                val lx = (x - (16 - half)) / (2f * half + 1)
                c.px(x, y, tone(stone, if (lx < 0.3f) 4 else if (lx < 0.65f) 3 else 2))
            }
        }
        for (y in 8..58) { c.px(16 - (if (y < 16) 7 + (y - 8) / 3 else 10), y, tone(stone, 4)); c.px(16 + (if (y < 16) 7 + (y - 8) / 3 else 10), y, tone(stone, 1)) }
        for (y in 14..42) c.px(16, y, tone(accent, 1)); for (x in 11..21) c.px(x, 24, tone(accent, 1))
        for (y in 54..58) for (x in 5..27) c.px(x, y, tone(stone, 1))
    }

    private fun skulls(c: Cv, bone: PaletteRamp) {
        c.shadow(16, 28, 12, 3)
        fun skull(cx: Int, cy: Int, r: Int) {
            c.ellipse(cx.toFloat(), cy.toFloat(), r.toFloat(), r * 0.9f) { nx, _ -> tone(bone, if (nx < -0.2f) 4 else if (nx < 0.4f) 3 else 2) }
            c.rect(cx - r + 2, cy + r / 2, cx + r - 2, cy + r / 2 + 2, tone(bone, 2))
            c.rect(cx - r / 2 - 1, cy - 1, cx - r / 2 + 1, cy + 1, tone(bone, 0)); c.rect(cx + r / 2 - 1, cy - 1, cx + r / 2 + 1, cy + 1, tone(bone, 0))
        }
        skull(10, 21, 6); skull(22, 21, 6); skull(16, 13, 6); skull(16, 23, 5)
    }

    private fun weaponRack(c: Cv, wood: PaletteRamp, metal: PaletteRamp) {
        c.shadow(16, 28, 12, 3)
        for (y in 10..27) { c.rect(5, y, 7, y, tone(wood, 3)); c.rect(24, y, 26, y, tone(wood, 2)) }
        c.rect(5, 12, 26, 14, tone(wood, 3)); c.rect(5, 12, 26, 12, tone(wood, 4)); c.rect(5, 14, 26, 14, tone(wood, 1))
        for ((i, wx) in intArrayOf(10, 15, 20).withIndex()) {
            c.rect(wx, 3 + i * 2, wx + 1, 26, tone(metal, 3))
            c.rect(wx - 1, 17, wx + 2, 18, tone(wood, 2))
            c.px(wx, 2 + i * 2, tone(metal, 4)); c.px(wx + 1, 2 + i * 2, tone(metal, 4))
        }
    }

    private fun ballista(c: Cv, wood: PaletteRamp, metal: PaletteRamp) {
        c.shadow(16, 28, 12, 3)
        for (y in 17..25) for (x in 6..25) c.px(x, y, tone(wood, if (y < 19) 4 else if (x < 16) 3 else 2))
        c.rect(14, 12, 17, 22, tone(wood, 3))
        for (k in 0..9) { c.px(4 + k, 8 + k / 2 + 0, tone(wood, 3)); c.px(27 - k, 8 + k / 2, tone(wood, 2)) }
        for (k in 0..10) { c.px(5 + k, 9 + k / 2, tone(metal, 1)) }
        c.rect(15, 6, 16, 22, tone(metal, 4)); c.px(15, 5, tone(metal, 4)); c.px(16, 5, tone(metal, 3))
        for (wx in intArrayOf(9, 22)) c.ellipse(wx.toFloat(), 26f, 4f, 4f) { nx, ny -> if (hypot(nx, ny) > 0.6f) tone(wood, 0) else tone(wood, 3) }
    }

    private fun torch(c: Cv, wood: PaletteRamp, flame: PaletteRamp) {
        c.shadow(16, 28, 7, 2)
        for (y in 13..27) c.rect(14, y, 17, y, tone(wood, if (y % 5 == 0) 1 else 3))
        c.rect(13, 12, 18, 14, tone(wood, 1))
        c.ellipse(15.5f, 8f, 5f, 7f) { _, ny -> if (ny < -0.2f) tone(flame, 5) else if (ny < 0.3f) tone(flame, 4) else tone(flame, 3) }
        c.ellipse(15.5f, 10f, 2.5f, 4f) { _, _ -> tone(flame, 5) }
    }

    private fun pillar(c: Cv, stone: PaletteRamp, moss: PaletteRamp) {
        c.shadow(16, 60, 11, 3)
        for (y in 6..60) {
            val capital = y < 12
            val base = y > 52
            val half = if (capital) 9 else if (base) 9 else 6
            for (x in 16 - half..16 + half) {
                val lx = (x - (16 - half)) / (2f * half + 1)
                var i = if (lx < 0.3f) 4 else if (lx < 0.65f) 3 else 2
                if (!capital && !base && (x - 10) % 4 == 0) i -= 1
                c.px(x, y, tone(stone, i))
            }
        }
        for (x in 7..25) { c.px(x, 6, tone(stone, 4)); c.px(x, 12, tone(stone, 1)); c.px(x, 52, tone(stone, 4)) }
        for (y in 20..50) if (c.hash(y, 3, 1L) > 0.8f) c.px(10 + (y % 3), y, tone(moss, 3))
    }

    private fun waypoint(c: Cv, stone: PaletteRamp, glow: PaletteRamp) {
        c.shadow(16, 60, 11, 3)
        for (y in 22..60) {
            val half = if (y > 52) 9 else 6
            for (x in 16 - half..16 + half) {
                val lx = (x - (16 - half)) / (2f * half + 1)
                c.px(x, y, tone(stone, if (lx < 0.3f) 4 else if (lx < 0.65f) 3 else 2))
            }
        }
        for (x in 7..25) c.px(x, 52, tone(stone, 4))
        // Carved rune and a floating crystal with a halo.
        for (y in 32..44) c.px(16, y, tone(glow, 3)); c.rect(13, 36, 19, 36, tone(glow, 3))
        for (y in 0..27) for (x in 4..28) {
            val d = hypot((x - 16).toFloat(), (y - 12).toFloat())
            if (d < 13f) c.blend(x, y, (tone(glow, 2) and 0xFFFFFF00.toInt()) or ((1f - d / 13f) * 90f).toInt())
        }
        for (k in 0..8) {
            val w2 = if (k < 5) k else 8 - k
            for (x in 16 - w2..16 + w2) {
                c.px(x, 4 + k + (if (k >= 5) 3 else 0) + 0, tone(glow, if (x < 16) glow.colors.lastIndex else glow.colors.lastIndex - 1))
            }
        }
    }

    private fun shrine(c: Cv, stone: PaletteRamp, wood: PaletteRamp) {
        c.shadow(16, 60, 11, 3)
        for (y in 32..60) for (x in 7..25) c.px(x, y, tone(stone, if (x < 13) 4 else if (x < 20) 3 else 2))
        for (x in 5..27) { c.px(x, 58, tone(stone, 4)); c.px(x, 59, tone(stone, 1)); c.px(x, 60, tone(stone, 1)) }
        // Niche with a candle and a peaked roof.
        for (y in 38..52) for (x in 11..21) c.px(x, y, tone(stone, 0))
        for (y in 44..52) c.rect(15, y, 17, y, tone(PalettePresets.RUNE_GOLD, 4))
        c.rect(15, 41, 17, 43, tone(PalettePresets.RUNE_GOLD, 5))
        for (k in 0..12) for (x in 16 - (k + 4)..16 + (k + 4)) c.px(x, 19 + k, tone(wood, if (x < 16) 3 else 2))
        for (k in 0..12) { c.px(16 - (k + 4), 19 + k, tone(wood, 4)); c.px(16 + (k + 4), 19 + k, tone(wood, 1)) }
        c.rect(5, 32, 27, 33, tone(wood, 1))
    }

    private fun portal(c: Cv, stone: PaletteRamp, glow: PaletteRamp) {
        c.shadow(16, 61, 12, 3)
        c.ellipse(16f, 32f, 13f, 26f) { nx, ny ->
            val r = hypot(nx, ny)
            if (r > 0.74f) tone(stone, if (nx < -0.1f) 4 else if (nx < 0.4f) 3 else 2) else {
                val swirl = sin((nx * 5f) + (ny * 7f)) * 0.5f + 0.5f
                tone(glow, if (r < 0.3f) glow.colors.lastIndex else if (swirl > 0.6f) 4 else if (swirl > 0.3f) 3 else 2)
            }
        }
    }

    private fun altar(c: Cv, stone: PaletteRamp, glow: PaletteRamp) {
        c.shadow(16, 60, 12, 3)
        for (y in 38..58) for (x in 4..28) c.px(x, y, tone(stone, if (x < 12) 2 else if (x < 22) 1 else 1))
        for (y in 34..38) for (x in 2..30) c.px(x, y, tone(stone, if (y == 34) 3 else 2))
        for (x in 6..26 step 4) for (y in 42..54) if ((x + y) % 3 != 0) c.px(x, y, tone(glow, 3))
        c.ellipse(16f, 24f, 6f, 10f) { _, ny -> if (ny < -0.3f) tone(glow, glow.colors.lastIndex) else if (ny < 0.3f) tone(glow, 4) else tone(glow, 3) }
        for (y in 4..28) for (x in 6..26) {
            val d = hypot((x - 16).toFloat(), (y - 22).toFloat() * 0.7f)
            if (d < 12f) c.blend(x, y, (tone(glow, 2) and 0xFFFFFF00.toInt()) or ((1f - d / 12f) * 70f).toInt())
        }
    }

    // ---- dispatch ------------------------------------------------------------------------------------------------

    private fun finish(target: TileImage, canvas: TileImage, w: Int, h: Int) {
        val outlined = SpriteFinish.softOutline(canvas)
        if (outlined.width == w && outlined.height == h) {
            target.drawImage(outlined, 0, 0, w, h, 0, 0)
        } else {
            for (y in 0 until h) for (x in 0 until w) {
                val px = outlined.getPixel(x * outlined.width / w, y * outlined.height / h)
                if ((px and 0xFF) != 0) target.setPixel(x, y, px)
            }
        }
    }

    /** Draws [kind] when it has a smooth version; returns false when the caller should use the older drawing. */
    fun draw(kind: PropKind, target: TileImage, w: Int, h: Int, primary: PaletteRamp?, secondary: PaletteRamp?, seed: Long): Boolean {
        val wood = PalettePresets.TIMBER_WOOD
        val slate = PalettePresets.SLATE_STONE
        val canvas = TileImage(kind.widthTiles * 32, kind.heightTiles * 32)
        val c = Cv(canvas, seed)
        when (kind) {
            PropKind.BARREL -> barrel(c, secondary ?: wood, primary ?: slate)
            PropKind.CRATE -> crate(c, secondary ?: wood, primary ?: slate)
            PropKind.CHEST_CLOSED -> chest(c, secondary ?: wood, primary ?: slate)
            PropKind.URN -> urn(c, primary ?: slate, secondary ?: wood)
            PropKind.MEDIEVAL_TENT -> tent(c, primary ?: PalettePresets.TERRACOTTA_BRICK, secondary ?: wood)
            PropKind.TRADE_WAGON -> wagon(c, primary ?: wood, secondary ?: PalettePresets.CREAM_MARBLE)
            PropKind.WOOD_FENCE -> fence(c, secondary ?: wood)
            PropKind.WOOD_BRIDGE -> bridge(c, secondary ?: wood)
            PropKind.STONE_ARCHWAY -> archway(c, primary ?: slate, secondary ?: PalettePresets.ANCIENT_LEAF)
            PropKind.SARCOPHAGUS -> sarcophagus(c, primary ?: slate, secondary ?: slate)
            PropKind.SKULL_PILE -> skulls(c, primary ?: PalettePresets.DUNGEON_BONE)
            PropKind.WEAPON_RACK -> weaponRack(c, secondary ?: wood, primary ?: slate)
            PropKind.SIEGE_BALLISTA -> ballista(c, primary ?: wood, secondary ?: slate)
            PropKind.TORCH_SCONCE -> torch(c, wood, PalettePresets.RUNE_GOLD)
            PropKind.PILLAR -> pillar(c, primary ?: slate, secondary ?: PalettePresets.ANCIENT_LEAF)
            PropKind.WAYPOINT_PILLAR -> waypoint(c, primary ?: slate, secondary ?: PalettePresets.ARCANE_VIOLET)
            PropKind.SHRINE -> shrine(c, primary ?: slate, secondary ?: wood)
            PropKind.PORTAL -> portal(c, primary ?: slate, secondary ?: PalettePresets.ARCANE_VIOLET)
            PropKind.DARK_RIFT_ALTAR -> altar(c, primary ?: PalettePresets.ABYSSAL_OBSIDIAN, secondary ?: PalettePresets.RIFT_CRIMSON)
            else -> return false
        }
        finish(target, canvas, w, h)
        return true
    }
}
