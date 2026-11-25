package com.morgana.tilegen.structure

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.SpriteFinish
import com.morgana.tilegen.material.TileNoise
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The castle-church kit: tall, vertical stone architecture in the same calm, chunky, outlined style as [HouseProps]. A
 * cathedral with twin spired towers and a rose window, a spired bell tower, a crenellated keep tower, a grand stair and a
 * cloister arcade. Drawn at 32 px per tile; the caller scales when the tile size differs. Placement rule as for every prop:
 * only the bottom row is the footprint.
 */
object CitadelProps {
    private fun hex(s: String): Int {
        val v = s.removePrefix("#").toInt(16)
        return PaletteRamp.rgba((v shr 16) and 255, (v shr 8) and 255, v and 255)
    }

    private fun ramp(vararg h: String) = PaletteRamp(IntArray(h.size) { hex(h[it]) })

    private val stone = ramp("#3a3d48", "#5d6270", "#818999", "#a9b0bc", "#cdd3dc")
    private val slate = ramp("#1f2840", "#324270", "#4a62a0", "#6a86c6", "#98b4e6")
    private val gold = ramp("#6a4a14", "#b98a24", "#e8b83c", "#f8d868", "#fff0a8")
    private val wood = ramp("#2e1c10", "#4e3320", "#704c30", "#946840", "#b88a58")
    private val glassBlue = ramp("#1c2c52", "#2e4a86", "#4a72b8", "#7aa2dc", "#b4d0f2")
    private val glassWarm = ramp("#7a2c1c", "#c0502c", "#e8883c", "#ffc060", "#fff0b0")
    private val dark = ramp("#0e0f16", "#1a1c28", "#2a2d3c", "#3c4054", "#52586c")
    private val banner = ramp("#4a1c1c", "#7e2a26", "#a83c30", "#cc5a46", "#e88a68")

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
    }

    // ---- parts ---------------------------------------------------------------------------------------------------

    /** Coursed masonry lit from the left, shaded on the right, outlined at the sides. */
    private fun masonry(c: Cv, x0: Int, x1: Int, top: Int, bot: Int) {
        for (y in top..bot) for (x in x0..x1) {
            val t = (x - x0).toFloat() / (x1 - x0).coerceAtLeast(1)
            var i = if (t < 0.4f) 3 else 2
            if (x >= x1 - 3) i = 1
            val course = (y - top) / 7
            val off = if (course % 2 == 0) 0 else 6
            val joint = (x + off) % 12 == 0 || (y - top) % 7 == 0
            val h = c.hash((x + off) / 12, course, 11L)
            c.px(x, y, if (joint) tone(stone, 1) else tone(stone, (i + (if (h > 0.75f) 1 else 0)).coerceIn(1, 4)))
        }
        for (y in top..bot) { c.px(x0, y, tone(stone, 0)); c.px(x1, y, tone(stone, 0)) }
    }

    /** A pointed (lancet) window: frame, glass, mullion. */
    private fun lancet(c: Cv, cx: Int, yTop: Int, yBot: Int, halfW: Int, glass: PaletteRamp) {
        for (y in yTop..yBot) {
            val rel = (y - yTop).toFloat()
            val half = (halfW * minOf(1f, (rel + 1) / (halfW * 1.7f))).toInt()
            for (x in cx - half..cx + half) {
                val edge = abs(x - cx) == half || y == yBot
                c.px(x, y, if (edge) tone(stone, 0) else tone(glass, if (x < cx) 3 else 2))
            }
        }
        c.rect(cx, yTop + (halfW * 1.7f).toInt(), cx, yBot - 1, tone(stone, 1))
        c.rect(cx - halfW + 1, yBot + 1, cx + halfW - 1, yBot + 1, tone(stone, 4))
    }

    /** A pyramid spire in slate with a gold finial. */
    private fun spire(c: Cv, cx: Int, halfBase: Int, baseY: Int, topY: Int) {
        for (y in topY..baseY) {
            val half = ((y - topY).toFloat() / (baseY - topY) * halfBase).toInt()
            for (x in cx - half..cx + half) {
                val rel = if (half == 0) 0.5f else (x - (cx - half)).toFloat() / (2 * half)
                val band = (y - topY) % 6 == 0
                val edge = x == cx - half || x == cx + half
                c.px(x, y, if (edge) tone(slate, 0) else if (band) tone(slate, 1) else tone(slate, if (rel < 0.5f) 3 else 2))
            }
        }
        c.rect(cx - halfBase - 2, baseY, cx + halfBase + 2, baseY + 2, tone(stone, 3))
        c.rect(cx - halfBase - 2, baseY + 2, cx + halfBase + 2, baseY + 2, tone(stone, 1))
        // Finial: a small gold cross.
        c.rect(cx, topY - 8, cx, topY, tone(gold, 2))
        c.rect(cx - 2, topY - 6, cx + 2, topY - 6, tone(gold, 3))
    }

    private fun rose(c: Cv, cx: Int, cy: Int, r: Int) {
        for (y in cy - r - 3..cy + r + 3) for (x in cx - r - 3..cx + r + 3) {
            val d = hypot((x - cx).toFloat(), (y - cy).toFloat())
            if (d <= r + 3) {
                val ring = d > r
                val spoke = abs(x - cx) < 2 || abs(y - cy) < 2 || abs(abs(x - cx) - abs(y - cy)) < 2
                c.px(x, y, when {
                    d > r + 2 -> tone(stone, 0)
                    ring -> tone(stone, 4)
                    d < 4 -> tone(gold, 3)
                    spoke -> tone(stone, 1)
                    d < r * 0.55f -> tone(glassWarm, if (x < cx) 3 else 2)
                    else -> tone(glassBlue, if (x < cx) 3 else 2)
                })
            }
        }
    }

    private fun pointedDoor(c: Cv, cx: Int, yBase: Int, halfW: Int, h: Int) {
        // Recessed arch rings, then planks with iron bands.
        for (ring in 0..2) {
            val hw = halfW + 6 - ring * 3
            val top = yBase - h - 6 + ring * 3
            for (y in top..yBase) {
                val rel = (y - top).toFloat()
                val half = (hw * minOf(1f, (rel + 1) / (hw * 1.5f))).toInt()
                for (x in cx - half..cx + half) {
                    val edge = abs(x - cx) == half
                    c.px(x, y, if (edge) tone(stone, 0) else if (ring == 2) tone(wood, if ((x - cx) % 5 == 0) 2 else 3) else tone(stone, 3 - ring))
                }
            }
        }
        for (by in intArrayOf(yBase - h / 3, yBase - 2 * h / 3)) c.rect(cx - halfW, by, cx + halfW, by + 1, tone(dark, 3))
        c.rect(cx, yBase - h + 6, cx, yBase, tone(wood, 0))
        c.rect(cx + 3, yBase - h / 2, cx + 4, yBase - h / 2 + 1, tone(gold, 3))
    }

    private fun buttress(c: Cv, x0: Int, x1: Int, top: Int, bot: Int) {
        for (y in top..bot) for (x in x0..x1) c.px(x, y, if (x == x0 || x == x1) tone(stone, 0) else if (x < (x0 + x1) / 2) tone(stone, 4) else tone(stone, 2))
        for (k in 0..3) c.rect(x0 + k / 2, top - 4 + k, x1 - k / 2, top - 4 + k, if (k == 0) tone(stone, 0) else tone(stone, 3))
    }

    private fun merlons(c: Cv, x0: Int, x1: Int, y: Int) {
        var mx = x0
        while (mx + 6 <= x1) {
            c.rect(mx, y - 9, mx + 6, y, tone(stone, 3))
            c.rect(mx, y - 9, mx, y, tone(stone, 4)); c.rect(mx + 6, y - 9, mx + 6, y, tone(stone, 1))
            c.rect(mx, y - 9, mx + 6, y - 9, tone(stone, 0))
            mx += 10
        }
    }

    // ---- the props -----------------------------------------------------------------------------------------------

    private fun cathedral(c: Cv, w: Int, h: Int) {
        val base = h - 5
        c.shadow(w / 2, h - 3, w / 2 - 8, 5)
        // Side aisles with lean-to slate roofs, lancets and buttresses.
        for (side in intArrayOf(0, 1)) {
            val x0 = if (side == 0) 34 else w - 106
            val x1 = x0 + 72
            masonry(c, x0, x1, 176, base)
            for (y in 140..176) for (x in x0 - 3..x1 + 3) {
                val shing = (y - 140) % 6 == 0 || (x + (y - 140) / 6 * 4) % 10 == 0
                c.px(x, y, if (y == 140 || x == x0 - 3 || x == x1 + 3) tone(slate, 0) else if (shing) tone(slate, 1) else tone(slate, if (x < (x0 + x1) / 2) 3 else 2))
            }
            for (k in 0..1) lancet(c, x0 + 18 + k * 34, 192, 240, 6, glassBlue)
            buttress(c, x0 - 2, x0 + 4, 166, base)
            buttress(c, x1 - 4, x1 + 2, 166, base)
        }
        // Nave facade with gable.
        val nx0 = 140
        val nx1 = w - 140
        masonry(c, nx0, nx1, 104, base)
        val peak = 38
        for (y in peak..104) {
            val half = ((y - peak).toFloat() / (104 - peak) * ((nx1 - nx0) / 2 + 8)).toInt()
            for (x in w / 2 - half..w / 2 + half) {
                val edge = abs(x - w / 2) >= half - 3
                val rel = (x - (w / 2 - half)).toFloat() / (2 * half).coerceAtLeast(1)
                c.px(x, y, if (abs(x - w / 2) >= half - 1 || y == peak) tone(slate, 0) else if (edge) tone(slate, if (rel < 0.5f) 4 else 2) else {
                    val course = (y - peak) / 7
                    val joint = (y - peak) % 7 == 0 || (x + course * 6) % 12 == 0
                    if (joint) tone(stone, 1) else tone(stone, if (rel < 0.45f) 3 else 2)
                })
            }
        }
        rose(c, w / 2, 90, 26)
        pointedDoor(c, w / 2, base, 17, 62)
        for (sx in intArrayOf(nx0 + 22, nx1 - 22)) lancet(c, sx, 150, 214, 6, glassBlue)
        // Twin towers with belfries and spires.
        for (side in intArrayOf(0, 1)) {
            val x0 = if (side == 0) 96 else w - 96 - 58
            val x1 = x0 + 58
            masonry(c, x0, x1, 64, base)
            c.rect(x0 - 2, 62, x1 + 2, 66, tone(stone, 3)); c.rect(x0 - 2, 66, x1 + 2, 66, tone(stone, 1))
            lancet(c, x0 + 17, 78, 118, 6, glassWarm)
            lancet(c, x1 - 17, 78, 118, 6, glassWarm)
            lancet(c, (x0 + x1) / 2, 150, 200, 5, glassBlue)
            buttress(c, x0 - 3, x0 + 2, 176, base)
            buttress(c, x1 - 2, x1 + 3, 176, base)
            spire(c, (x0 + x1) / 2, 34, 62, 4)
        }
        // Crossing flèche behind the gable.
        spire(c, w / 2, 9, 40, 0)
        // Steps.
        for (k in 0..2) {
            c.rect(w / 2 - 34 - k * 4, base + k, w / 2 + 34 + k * 4, base + k, tone(stone, 4 - k))
        }
    }

    private fun bellTower(c: Cv, w: Int, h: Int) {
        val base = h - 5
        c.shadow(w / 2, h - 3, w / 2 - 6, 4)
        masonry(c, 18, w - 18, 76, base)
        c.rect(14, 74, w - 14, 78, tone(stone, 3)); c.rect(14, 78, w - 14, 78, tone(stone, 1))
        // Belfry opening with a bell.
        for (y in 82..112) for (x in w / 2 - 12..w / 2 + 12) {
            val edge = x == w / 2 - 12 || x == w / 2 + 12 || y == 82
            c.px(x, y, if (edge) tone(stone, 0) else tone(dark, 2))
        }
        c.rect(w / 2 - 5, 92, w / 2 + 5, 106, tone(gold, 2)); c.rect(w / 2 - 5, 92, w / 2 - 3, 106, tone(gold, 4)); c.rect(w / 2 - 7, 106, w / 2 + 7, 108, tone(gold, 1))
        lancet(c, w / 2, 128, 160, 5, glassBlue)
        buttress(c, 14, 20, 150, base); buttress(c, w - 20, w - 14, 150, base)
        spire(c, w / 2, 30, 74, 6)
    }

    private fun keepTower(c: Cv, w: Int, h: Int) {
        val base = h - 5
        c.shadow(w / 2, h - 3, w / 2 - 6, 4)
        masonry(c, 12, w - 12, 44, base)
        // Machicolated crown and crenellation.
        c.rect(8, 36, w - 8, 46, tone(stone, 3)); c.rect(8, 46, w - 8, 47, tone(stone, 1)); c.rect(8, 36, w - 8, 36, tone(stone, 4))
        for (x in 12..w - 14 step 8) c.rect(x, 40, x + 3, 46, tone(dark, 2))
        merlons(c, 8, w - 8, 36)
        for (sy in intArrayOf(64, 100)) c.rect(w / 2 - 1, sy, w / 2, sy + 14, tone(dark, 1))
        lancet(c, w / 2, 112, 128, 4, glassWarm)
        // Banner on a pole.
        c.rect(w / 2, 2, w / 2, 26, tone(wood, 1))
        for (k in 0..8) c.rect(w / 2 + 1, 3 + k, w / 2 + 14 - k / 2, 3 + k, tone(banner, if (k == 0) 4 else 3))
        c.rect(w / 2 + 1, 3, w / 2 + 14, 3, tone(banner, 0))
    }

    private fun grandStairs(c: Cv, w: Int, h: Int) {
        for (y in 0 until h) {
            val step = y / 12
            val inStep = y % 12
            val tread = inStep >= 4
            val col = if (!tread) tone(stone, 1) else tone(stone, if (step % 2 == 0) 4 else 3)
            for (x in 12 until w - 12) c.px(x, y, if (inStep == 0) tone(stone, 0) else col)
        }
        // Balustrade walls with finials.
        for (side in intArrayOf(0, 1)) {
            val x0 = if (side == 0) 0 else w - 12
            for (y in 0 until h) for (x in x0 until x0 + 12) {
                c.px(x, y, if (x == x0 || x == x0 + 11) tone(stone, 0) else if (x < x0 + 5) tone(stone, 4) else tone(stone, 2))
            }
            c.rect(x0 - 1, h - 12, x0 + 12, h - 3, tone(stone, 3)); c.rect(x0 - 1, h - 12, x0 + 12, h - 12, tone(stone, 0))
            c.rect(x0 + 3, h - 18, x0 + 8, h - 12, tone(gold, 3))
        }
    }

    private fun cloister(c: Cv, w: Int, h: Int) {
        val base = h - 4
        // Roof slab.
        for (y in 4..22) for (x in 0 until w) c.px(x, y, if (y == 4 || y == 22) tone(slate, 0) else if ((y - 4) % 6 == 0) tone(slate, 1) else tone(slate, if (x % 16 < 8) 3 else 2))
        // Back wall and arcade.
        masonry(c, 4, w - 5, 24, base)
        val bays = w / 64
        for (b in 0 until bays) {
            val cx = b * 64 + 32
            for (y in 34..base) {
                val half = (24 * minOf(1f, (y - 33) / 20f)).toInt()
                for (x in cx - half..cx + half) c.px(x, y, if (abs(x - cx) == half) tone(stone, 0) else tone(dark, if (y < 50) 3 else 2))
            }
        }
        for (b in 0..bays) {
            val px = b * 64
            c.rect((px - 4).coerceAtLeast(0), 24, (px + 4).coerceAtMost(w - 1), base, tone(stone, 3))
            c.rect((px - 4).coerceAtLeast(0), 24, (px - 3).coerceAtLeast(0), base, tone(stone, 4))
            c.rect((px + 3).coerceAtMost(w - 1), 24, (px + 4).coerceAtMost(w - 1), base, tone(stone, 1))
        }
        c.rect(0, base, w - 1, h - 1, tone(stone, 3)); c.rect(0, h - 1, w - 1, h - 1, tone(stone, 1))
    }

    /** Draws [kind] at 32 px per tile. */
    fun draw(kind: PropKind, seed: Long): TileImage {
        val w = kind.widthTiles * 32
        val h = kind.heightTiles * 32
        val canvas = TileImage(w, h)
        val c = Cv(canvas, seed)
        when (kind) {
            PropKind.CATHEDRAL -> cathedral(c, w, h)
            PropKind.BELL_TOWER -> bellTower(c, w, h)
            PropKind.KEEP_TOWER -> keepTower(c, w, h)
            PropKind.GRAND_STAIRS -> grandStairs(c, w, h)
            PropKind.CLOISTER -> cloister(c, w, h)
            else -> throw IllegalArgumentException("not a citadel prop: $kind")
        }
        return SpriteFinish.softOutline(canvas)
    }

    val kinds = setOf(PropKind.CATHEDRAL, PropKind.BELL_TOWER, PropKind.KEEP_TOWER, PropKind.GRAND_STAIRS, PropKind.CLOISTER)
}
