package com.morgana.tilegen.structure

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.SpriteFinish
import com.morgana.tilegen.structure.CitadelProps.Cv
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The castle-town kit: buildings and towers that share the curtain wall's grey masonry and slate roofs, after the
 * "gritty castle" reference. Stone and half-timber houses, a long hall, roofed round and square towers (no open-topped
 * cans), a great gatehouse, a keep with four conical corner towers, and a hunters' lodge, gate and palisade. Drawn at 32 px
 * per tile like [CitadelProps]; placement rule as for every prop: only the bottom row is the footprint.
 */
object CastleKit {
    private val stone get() = CitadelProps.stone
    private val wood get() = CitadelProps.wood
    private val gold get() = CitadelProps.gold
    private val dark get() = CitadelProps.dark
    private val banner get() = CitadelProps.banner
    private val glassBlue get() = CitadelProps.glassBlue
    private val glassWarm get() = CitadelProps.glassWarm
    private fun tone(r: PaletteRamp, i: Int) = CitadelProps.tone(r, i)

    private val roof = CitadelProps.ramp("#232733", "#3a4152", "#566077", "#78839b", "#9ea9c0")
    private val shake = CitadelProps.ramp("#2a1a10", "#46301c", "#654526", "#86603a", "#a8805a")
    private val plaster = CitadelProps.ramp("#6b6554", "#9a9278", "#c2b99c", "#ddd4b8", "#f0e8cc")
    private val bone = CitadelProps.ramp("#6a604c", "#a89c80", "#d8ccae", "#efe6cc", "#fff8e4")

    // ---- parts ---------------------------------------------------------------------------------------------------

    /** A hip roof (or a cone when [halfTop] is 0) in shingle courses, lit from the left. */
    private fun hipRoof(c: Cv, cx: Int, topY: Int, botY: Int, halfTop: Int, halfBot: Int, ramp: PaletteRamp) {
        val span = (botY - topY).coerceAtLeast(1)
        for (y in topY..botY) {
            val half = halfTop + ((halfBot - halfTop) * (y - topY).toFloat() / span).toInt()
            val row = (y - topY) / 6
            for (x in cx - half..cx + half) {
                val rel = (x - (cx - half)).toFloat() / (2 * half).coerceAtLeast(1)
                var i = if (rel < 0.3f) 4 else if (rel < 0.62f) 3 else 2
                if (c.hash((x + row * 5) / 6, row, 31L) > 0.86f && i < 4) i++
                val joint = (y - topY) % 6 == 5 || (x + row * 5) % 12 == 0
                c.px(x, y, when {
                    x == cx - half || x == cx + half || y == topY || y >= botY - 1 -> tone(ramp, 0)
                    y == topY + 1 -> tone(ramp, 4)
                    joint -> tone(ramp, maxOf(0, i - 2))
                    else -> tone(ramp, i)
                })
            }
        }
    }

    private fun finial(c: Cv, cx: Int, y: Int) {
        c.rect(cx, y - 8, cx, y, tone(wood, 1))
        c.rect(cx - 1, y - 2, cx + 1, y, tone(gold, 2))
        for (k in 0..3) c.rect(cx + 1, y - 8 + k, cx + 7 - k, y - 8 + k, tone(banner, if (k == 0) 4 else 3))
    }

    private fun window(c: Cv, cx: Int, top: Int, hw: Int, hh: Int, lit: Boolean, shutters: Boolean) {
        val glass = if (lit) glassWarm else glassBlue
        c.rect(cx - hw - 1, top - 1, cx + hw + 1, top + hh + 1, tone(wood, 1))
        for (y in top..top + hh) for (x in cx - hw..cx + hw) c.px(x, y, tone(glass, if (x < cx) 3 else 2))
        c.rect(cx, top, cx, top + hh, tone(wood, 1))
        c.rect(cx - hw, top + hh / 2, cx + hw, top + hh / 2, tone(wood, 1))
        c.rect(cx - hw - 2, top + hh + 2, cx + hw + 2, top + hh + 3, tone(stone, 4))
        if (shutters) {
            c.rect(cx - hw - 5, top, cx - hw - 2, top + hh, tone(wood, 3))
            c.rect(cx + hw + 2, top, cx + hw + 5, top + hh, tone(wood, 2))
        }
    }

    private fun door(c: Cv, cx: Int, base: Int, hw: Int, dh: Int) {
        for (y in base - dh..base) {
            val r = y - (base - dh)
            val inset = if (r < 3) 3 - r else 0
            for (x in cx - hw + inset..cx + hw - inset) {
                val edge = x == cx - hw + inset || x == cx + hw - inset || r == 0
                c.px(x, y, if (edge) tone(stone, 0) else tone(wood, if ((x - cx) % 5 == 0) 2 else 3))
            }
        }
        c.rect(cx - hw - 2, base - dh - 2, cx - hw - 1, base, tone(stone, 4))
        c.rect(cx + hw + 1, base - dh - 2, cx + hw + 2, base, tone(stone, 2))
        c.rect(cx + hw - 4, base - dh / 2, cx + hw - 3, base - dh / 2 + 1, tone(gold, 3))
        c.rect(cx - hw, base - dh / 3, cx + hw, base - dh / 3, tone(dark, 3))
    }

    private fun chimney(c: Cv, x: Int, y0: Int, y1: Int) {
        for (y in y0..y1) for (xx in x..x + 11) c.px(xx, y, if (xx == x || xx == x + 11) tone(stone, 0) else if (xx < x + 5) tone(stone, 4) else tone(stone, 2))
        c.rect(x - 2, y0 - 3, x + 13, y0, tone(stone, 3)); c.rect(x - 2, y0 - 3, x + 13, y0 - 3, tone(stone, 0))
        c.rect(x + 3, y0 - 2, x + 8, y0, tone(dark, 1))
    }

    /** One house wing: walls (stone, or plaster in a timber frame on a stone course), a hip roof, windows, a door. */
    private fun block(c: Cv, x0: Int, x1: Int, roofTop: Int, wallTop: Int, base: Int, stoneWalls: Boolean, hasDoor: Boolean, ramp: PaletteRamp) {
        val wx0 = x0 + 6
        val wx1 = x1 - 6
        val mid = (wx0 + wx1) / 2
        if (stoneWalls) {
            CitadelProps.masonry(c, wx0, wx1, wallTop, base)
        } else {
            val footTop = base - 11
            for (y in wallTop..base) for (x in wx0..wx1) {
                val t = (x - wx0).toFloat() / (wx1 - wx0)
                if (y >= footTop) {
                    val course = (y - footTop) / 6
                    val joint = (y - footTop) % 6 == 0 || (x + course * 5) % 11 == 0
                    c.px(x, y, if (joint) tone(stone, 1) else tone(stone, if (t < 0.4f) 3 else 2))
                } else {
                    c.px(x, y, tone(plaster, if (t < 0.4f) 3 else if (t > 0.85f) 1 else 2))
                }
            }
            val n = maxOf(2, (wx1 - wx0) / 36)
            for (i in 0..n) {
                val px = wx0 + i * (wx1 - wx0 - 2) / n
                c.rect(px, wallTop, px + 2, footTop - 1, tone(wood, 2))
            }
            c.rect(wx0, wallTop, wx1, wallTop + 2, tone(wood, 1))
            c.rect(wx0, footTop - 3, wx1, footTop - 1, tone(wood, 1))
            c.rect(wx0, wallTop + (footTop - wallTop) / 2, wx1, wallTop + (footTop - wallTop) / 2 + 1, tone(wood, 2))
            for (y in wallTop..base) { c.px(wx0, y, tone(stone, 0)); c.px(wx1, y, tone(stone, 0)) }
        }
        hipRoof(c, (x0 + x1) / 2, roofTop, wallTop + 8, (x1 - x0) / 4, (x1 - x0) / 2 + 2, ramp)
        for (y in wallTop + 9..wallTop + 12) for (x in wx0..wx1) c.blend(x, y, PaletteRamp.rgba(0, 0, 0, 70))
        val n = maxOf(1, (wx1 - wx0) / 46)
        for (i in 0 until n) {
            val cx = wx0 + ((i + 0.5f) * (wx1 - wx0) / n).toInt()
            if (hasDoor && abs(cx - mid) < 24) continue
            window(c, cx, wallTop + 15, 6, 15, c.hash(cx, wallTop, 5L) > 0.6f, !stoneWalls)
        }
        if (hasDoor) door(c, mid, base, 10, minOf(32, base - wallTop - 6))
    }

    private fun house(c: Cv, w: Int, h: Int, wings: Int, stoneWalls: Boolean) {
        val base = h - 7
        c.shadow(w / 2, h - 5, w / 2 - 4, 4)
        when (wings) {
            2 -> {
                block(c, 4, w * 55 / 100, h * 26 / 100, h * 58 / 100, base, false, false, roof)
                block(c, w * 40 / 100, w - 4, h * 8 / 100, h * 44 / 100, base, true, true, roof)
                chimney(c, w * 78 / 100, h * 8 / 100 - 2, h * 8 / 100 + 26)
            }
            else -> {
                val roofTop = h * 12 / 100
                block(c, 4, w - 4, roofTop, h * 55 / 100, base, stoneWalls, true, roof)
                chimney(c, w * 70 / 100, roofTop - 2, roofTop + 22)
            }
        }
    }

    private fun longHall(c: Cv, w: Int, h: Int) {
        val base = h - 7
        c.shadow(w / 2, h - 5, w / 2 - 4, 4)
        block(c, 4, w - 4, 20, 78, base, true, true, roof)
        chimney(c, 54, 12, 40)
        chimney(c, w - 70, 12, 40)
        // Dormers.
        for (dx in intArrayOf(w / 2 - 64, w / 2 + 64)) {
            hipRoof(c, dx, 44, 66, 6, 17, roof)
            c.rect(dx - 5, 54, dx + 5, 66, tone(dark, 2)); c.rect(dx - 5, 54, dx - 5, 66, tone(wood, 1)); c.rect(dx + 5, 54, dx + 5, 66, tone(wood, 1))
        }
    }

    private fun slateTower(c: Cv, w: Int, h: Int) {
        val base = h - 6
        c.shadow(w / 2, h - 4, w / 2 - 8, 4)
        CitadelProps.cylinder(c, 16, w - 16, 90, base)
        for (x in 16..w - 16) c.px(x, base + 1, tone(stone, 0))
        c.rect(12, 84, w - 12, 91, tone(stone, 3)); c.rect(12, 91, w - 12, 92, tone(stone, 1)); c.rect(12, 84, w - 12, 84, tone(stone, 0))
        for (x in 14..w - 14 step 8) c.rect(x, 92, x + 3, 95, tone(dark, 2))
        hipRoof(c, w / 2, 12, 88, 0, w / 2 - 4, roof)
        c.rect(w / 2 - 1, 5, w / 2, 14, tone(gold, 2))
        finial(c, w / 2, 8)
        c.rect(w / 2 - 1, 108, w / 2, 124, tone(dark, 1))
        CitadelProps.lancet(c, w / 2, 132, 154, 4, glassWarm)
        door(c, w / 2, base, 8, 22)
    }

    private fun squareTower(c: Cv, w: Int, h: Int) {
        val base = h - 6
        c.shadow(w / 2, h - 4, w / 2 - 6, 4)
        CitadelProps.masonry(c, 14, w - 14, 44, base)
        c.rect(8, 34, w - 8, 46, tone(stone, 3)); c.rect(8, 46, w - 8, 47, tone(stone, 1)); c.rect(8, 34, w - 8, 34, tone(stone, 4))
        for (x in 12..w - 14 step 8) c.rect(x, 40, x + 3, 46, tone(dark, 2))
        CitadelProps.merlons(c, 8, w - 8, 34)
        for (sy in intArrayOf(60, 96)) c.rect(w / 2 - 1, sy, w / 2, sy + 14, tone(dark, 1))
        CitadelProps.lancet(c, w / 2, 104, 120, 4, glassWarm)
        c.rect(14, 118, 22, base, tone(stone, 4)); c.rect(w - 22, 118, w - 14, base, tone(stone, 1))
        finial(c, w - 14, 28)
    }

    private fun greatGate(c: Cv, w: Int, h: Int) {
        val base = h - 5
        val cx = w / 2
        c.shadow(cx, h - 3, w / 2 - 8, 4)
        // Curtain between the towers with a crenellated walk.
        CitadelProps.masonry(c, 70, w - 70, 62, base)
        c.rect(66, 52, w - 66, 64, tone(stone, 3)); c.rect(66, 64, w - 66, 65, tone(stone, 1)); c.rect(66, 52, w - 66, 52, tone(stone, 4))
        CitadelProps.merlons(c, 66, w - 66, 52)
        // Arch: stone rim, then the dark passage with a raised portcullis; the lower part is open so the ground shows.
        val spring = 104
        for (y in 70..base) for (x in cx - 38..cx + 38) {
            val inside = if (y < spring) hypot((x - cx).toFloat(), (y - spring).toFloat()) <= 38f else abs(x - cx) <= 38
            if (inside) c.px(x, y, if (abs(x - cx) >= 36 || y == 70) tone(stone, 0) else tone(stone, if (x < cx) 4 else 3))
        }
        for (y in 74..base) for (x in cx - 31..cx + 31) {
            val inside = if (y < spring) hypot((x - cx).toFloat(), (y - spring).toFloat()) <= 31f else true
            if (!inside) continue
            when {
                y >= base - 30 -> c.img.setPixel(x, y, 0)
                else -> c.px(x, y, if ((x - cx) % 8 == 0 || (y - 74) % 18 == 0) tone(dark, 4) else tone(dark, 1))
            }
        }
        for (y in base - 30..base) { c.px(cx - 32, y, tone(stone, 0)); c.px(cx + 32, y, tone(stone, 0)) }
        // Flanking towers with slate cones.
        for (side in intArrayOf(0, 1)) {
            val x0 = if (side == 0) 2 else w - 76
            val x1 = x0 + 74
            val mid = (x0 + x1) / 2
            CitadelProps.cylinder(c, x0 + 4, x1 - 4, 74, base)
            c.rect(x0, 70, x1, 76, tone(stone, 3)); c.rect(x0, 76, x1, 77, tone(stone, 1))
            hipRoof(c, mid, 8, 72, 0, 38, roof)
            finial(c, mid, 6)
            c.rect(mid - 1, 92, mid, 108, tone(dark, 1))
            c.rect(mid - 1, 122, mid, 134, tone(dark, 1))
        }
        // Banners down either side of the arch.
        for (bx in intArrayOf(cx - 52, cx + 44)) {
            c.rect(bx, 70, bx + 8, 110, tone(banner, 2)); c.rect(bx, 70, bx, 110, tone(banner, 3)); c.rect(bx, 70, bx + 8, 71, tone(gold, 2))
            for (k in 0..3) c.rect(bx + k, 111 + k, bx + 8 - k, 111 + k, tone(banner, 2))
            c.rect(bx + 3, 88, bx + 5, 94, tone(gold, 3))
        }
    }

    private fun greatKeep(c: Cv, w: Int, h: Int) {
        val base = h - 6
        c.shadow(w / 2, h - 4, w / 2 - 10, 5)
        // Donjon above the main block.
        CitadelProps.masonry(c, 112, 208, 38, 110)
        c.rect(106, 28, 214, 40, tone(stone, 3)); c.rect(106, 40, 214, 41, tone(stone, 1)); c.rect(106, 28, 214, 28, tone(stone, 4))
        for (x in 110..206 step 8) c.rect(x, 34, x + 3, 40, tone(dark, 2))
        CitadelProps.merlons(c, 106, 214, 28)
        CitadelProps.lancet(c, 160, 54, 86, 5, glassBlue)
        // Main block and its terrace.
        CitadelProps.masonry(c, 48, 272, 104, base)
        for (side in intArrayOf(0, 1)) {
            val x0 = if (side == 0) 70 else 208
            c.rect(x0, 96, x0 + 42, 106, tone(stone, 3)); c.rect(x0, 106, x0 + 42, 107, tone(stone, 1)); c.rect(x0, 96, x0 + 42, 96, tone(stone, 4))
            CitadelProps.merlons(c, x0, x0 + 42, 96)
        }
        for (wx in intArrayOf(96, 126, 194, 224)) CitadelProps.lancet(c, wx, 126, 168, 5, glassWarm)
        for (wx in intArrayOf(96, 224)) c.rect(wx - 1, 190, wx, 206, tone(dark, 1))
        CitadelProps.pointedDoor(c, 160, base, 14, 50)
        for (k in 0..2) c.rect(160 - 26 - k * 4, base + k - 1, 160 + 26 + k * 4, base + k - 1, tone(stone, 4 - k))
        // Four corner towers in slate cones (the outer two here, the inner pair flank the door).
        for (cxT in intArrayOf(40, 280)) {
            CitadelProps.cylinder(c, cxT - 34, cxT + 34, 96, base)
            c.rect(cxT - 38, 90, cxT + 38, 97, tone(stone, 3)); c.rect(cxT - 38, 97, cxT + 38, 98, tone(stone, 1))
            hipRoof(c, cxT, 18, 94, 0, 40, roof)
            finial(c, cxT, 16)
            c.rect(cxT - 1, 120, cxT, 138, tone(dark, 1))
            c.rect(cxT - 1, 170, cxT, 188, tone(dark, 1))
        }
        c.rect(159, 4, 160, 30, tone(wood, 1))
        for (k in 0..9) c.rect(161, 5 + k, 178 - k, 5 + k, tone(banner, if (k == 0) 4 else 3))
    }

    private fun hunterLodge(c: Cv, w: Int, h: Int) {
        val base = h - 6
        c.shadow(w / 2, h - 4, w / 2 - 4, 4)
        // Log walls: courses of round logs with end caps at the corners.
        for (y in 68..base) for (x in 10..w - 10) {
            val row = (y - 68) / 7
            val inRow = (y - 68) % 7
            val t = (x - 10).toFloat() / (w - 20)
            val shade = if (inRow == 0) 1 else if (inRow < 3) (if (t < 0.5f) 4 else 3) else if (inRow < 6) (if (t < 0.5f) 3 else 2) else 1
            c.px(x, y, tone(wood, (shade + (if (c.hash(x / 9, row, 41L) > 0.8f) 1 else 0)).coerceIn(0, 4)))
        }
        for (y in 68..base) for (x in intArrayOf(10, 11, w - 11, w - 10)) c.px(x, y, tone(wood, 0))
        for (row in 0..7) for (x in intArrayOf(7, w - 12)) c.rect(x, 69 + row * 7, x + 4, 74 + row * 7, tone(wood, 4))
        hipRoof(c, w / 2, 16, 74, 34, w / 2 - 2, shake)
        chimney(c, w - 48, 12, 40)
        for (y in 75..79) for (x in 10..w - 10) c.blend(x, y, PaletteRamp.rgba(0, 0, 0, 70))
        window(c, 36, 84, 6, 14, true, false)
        door(c, w / 2 + 6, base, 10, 28)
        // Antlers over the door and a hide on a frame.
        val ax = w / 2 + 6
        for (s in intArrayOf(-1, 1)) {
            for (k in 0..9) c.px(ax + s * (3 + k), 63 - k, tone(bone, 3))
            for (k in 0..4) c.px(ax + s * (6 + k), 60 - k, tone(bone, 3))
            for (k in 0..4) c.px(ax + s * (9 + k), 57 - k, tone(bone, 3))
        }
        c.rect(ax - 3, 62, ax + 3, 67, tone(bone, 2))
        c.rect(w - 36, 86, w - 18, 112, tone(shake, 3)); c.rect(w - 36, 86, w - 18, 86, tone(wood, 1))
        c.rect(w - 38, 84, w - 38, base, tone(wood, 1)); c.rect(w - 16, 84, w - 16, base, tone(wood, 1))
    }

    private fun hunterGate(c: Cv, w: Int, h: Int) {
        // Two tall log posts with a crossbar and a hanging skull; the passage between them is open.
        for (px in intArrayOf(6, w - 32)) {
            for (y in 10..h - 4) for (x in px..px + 25) {
                val t = (x - px) / 25f
                val ring = (y - 10) % 12 == 0
                c.px(x, y, if (x == px || x == px + 25) tone(wood, 0) else if (ring) tone(wood, 1) else tone(wood, if (t < 0.4f) 4 else if (t < 0.7f) 3 else 2))
            }
            for (k in 0..8) c.rect(px + k + 1, 2 + (8 - k), px + 24 - k, 2 + (8 - k), tone(wood, if (k == 0) 0 else 3))
        }
        c.rect(6, 22, w - 6, 36, tone(wood, 3)); c.rect(6, 22, w - 6, 23, tone(wood, 4)); c.rect(6, 36, w - 6, 36, tone(wood, 0)); c.rect(6, 35, w - 6, 35, tone(wood, 1))
        val cx = w / 2
        for (y in 40..58) for (x in cx - 11..cx + 11) {
            val d = hypot((x - cx) / 11f, (y - 48) / 9f)
            if (d <= 1f) c.px(x, y, if (d > 0.9f) tone(bone, 0) else tone(bone, if (x < cx) 4 else 3))
        }
        c.rect(cx - 6, 46, cx - 3, 50, tone(dark, 1)); c.rect(cx + 3, 46, cx + 6, 50, tone(dark, 1))
        for (s in intArrayOf(-1, 1)) for (k in 0..8) c.px(cx + s * (10 + k), 42 - k, tone(bone, 3))
        c.rect(cx, 36, cx, 40, tone(dark, 3))
        for (bx in intArrayOf(44, w - 56)) {
            c.rect(bx, 36, bx + 10, 62, tone(banner, 2)); c.rect(bx, 36, bx, 62, tone(banner, 3))
            for (k in 0..4) c.rect(bx + k, 63 + k, bx + 10 - k, 63 + k, tone(banner, 2))
        }
    }

    private fun palisade(c: Cv, w: Int, h: Int) {
        val tops = intArrayOf(6, 12, 4)
        for ((i, top) in tops.withIndex()) {
            val x0 = 1 + i * 10
            for (y in top..h - 4) for (x in x0..x0 + 8) {
                val t = (x - x0) / 8f
                c.px(x, y, if (x == x0 || x == x0 + 8) tone(wood, 0) else tone(wood, if (t < 0.4f) 4 else if (t < 0.7f) 3 else 2))
            }
            for (k in 0..4) c.rect(x0 + k, top - 4 + (4 - k) - 0, x0 + 8 - k, top - 4 + (4 - k), tone(wood, if (k == 0) 0 else 3))
        }
        c.rect(0, 28, w - 1, 31, tone(wood, 1)); c.rect(0, 28, w - 1, 28, tone(wood, 3))
        c.rect(0, h - 4, w - 1, h - 3, tone(wood, 0))
    }

    /**
     * The curtain-wall corner tower: a battered (flared) plinth, a banded drum with a lit left flank, arrow slits and a
     * lancet, a corbelled machicolation ring with dark openings, and a deep-eaved slate cone with a flag.
     */
    private fun cornerTower(c: Cv, w: Int, h: Int) {
        val base = h - 6
        val cx = w / 2
        c.shadow(cx, h - 4, w / 2 - 6, 5)
        val x0 = 16
        val x1 = w - 16
        // Drum.
        CitadelProps.cylinder(c, x0, x1, 92, base - 14)
        // Stone banding every 36 px.
        for (by in intArrayOf(120, 154)) {
            for (x in x0..x1) {
                val t = (x - (x0 + x1) / 2f) / ((x1 - x0) / 2f)
                c.px(x, by, tone(stone, if (t < -0.2f) 4 else 2)); c.px(x, by + 1, tone(stone, 1))
            }
        }
        // Battered plinth: flares out and is darker, with a coursed top edge.
        for (y in base - 14..base) {
            val flare = (y - (base - 14)) / 4
            for (x in x0 - flare..x1 + flare) {
                val t = (x - cx).toFloat() / (w / 2)
                val course = (y - (base - 14)) / 5
                val joint = (y - (base - 14)) % 5 == 0 || (x + course * 7) % 14 == 0
                val i = if (t < -0.25f) 3 else if (t < 0.3f) 2 else 1
                c.px(x, y, if (x == x0 - flare || x == x1 + flare || y == base) tone(stone, 0) else if (joint) tone(stone, maxOf(0, i - 2)) else tone(stone, i))
            }
        }
        c.rect(x0 - 3, base - 16, x1 + 3, base - 15, tone(stone, 4)); c.rect(x0 - 3, base - 14, x1 + 3, base - 14, tone(stone, 1))
        // Corbelled ring with machicolation openings.
        c.rect(x0 - 6, 82, x1 + 6, 93, tone(stone, 3)); c.rect(x0 - 6, 82, x1 + 6, 82, tone(stone, 4)); c.rect(x0 - 6, 93, x1 + 6, 94, tone(stone, 1))
        for (x in x0 - 2..x1 + 2 step 10) { c.rect(x, 86, x + 4, 92, tone(dark, 1)); c.rect(x, 95, x + 4, 98, tone(stone, 1)) }
        // Openings: slits above, a lancet in the middle, a slit and a barred loop below.
        c.rect(cx - 15, 106, cx - 14, 118, tone(dark, 1)); c.rect(cx + 14, 106, cx + 15, 118, tone(dark, 1))
        CitadelProps.lancet(c, cx, 126, 150, 5, glassWarm)
        c.rect(cx - 1, 168, cx, 182, tone(dark, 1))
        // Cone roof with a deep eave, scalloped courses and a finial.
        hipRoof(c, cx, 14, 84, 0, w / 2 - 2, roof)
        c.rect(cx - (w / 2 - 2), 83, cx + (w / 2 - 2), 84, tone(roof, 0))
        for (x in cx - (w / 2 - 4)..cx + (w / 2 - 4) step 6) c.rect(x, 85, x + 2, 87, tone(dark, 2))
        c.rect(cx - 1, 7, cx, 16, tone(gold, 2))
        finial(c, cx, 9)
        // A hanging pennant on the lit flank.
        c.rect(x0 + 8, 100, x0 + 14, 104, tone(banner, 3))
    }

    /** Draws [kind] at 32 px per tile. */
    fun draw(kind: PropKind, seed: Long): TileImage {
        val w = kind.widthTiles * 32
        val h = kind.heightTiles * 32
        val canvas = TileImage(w, h)
        val c = Cv(canvas, seed)
        when (kind) {
            PropKind.STONE_HOUSE_S -> house(c, w, h, 1, false)
            PropKind.STONE_HOUSE_M -> house(c, w, h, 1, true)
            PropKind.STONE_HOUSE_L -> house(c, w, h, 2, false)
            PropKind.LONG_HALL -> longHall(c, w, h)
            PropKind.SLATE_TOWER -> slateTower(c, w, h)
            PropKind.SQUARE_TOWER -> squareTower(c, w, h)
            PropKind.GREAT_GATE -> greatGate(c, w, h)
            PropKind.GREAT_KEEP -> greatKeep(c, w, h)
            PropKind.HUNTER_LODGE -> hunterLodge(c, w, h)
            PropKind.HUNTER_GATE -> hunterGate(c, w, h)
            PropKind.PALISADE -> palisade(c, w, h)
            PropKind.CORNER_TOWER -> cornerTower(c, w, h)
            else -> throw IllegalArgumentException("not a castle-kit prop: $kind")
        }
        return SpriteFinish.softOutline(canvas)
    }

    val kinds = setOf(
        PropKind.STONE_HOUSE_S, PropKind.STONE_HOUSE_M, PropKind.STONE_HOUSE_L, PropKind.LONG_HALL, PropKind.SLATE_TOWER,
        PropKind.SQUARE_TOWER, PropKind.GREAT_GATE, PropKind.GREAT_KEEP, PropKind.HUNTER_LODGE, PropKind.HUNTER_GATE, PropKind.PALISADE, PropKind.CORNER_TOWER
    )
}
