package com.morgana.tilegen.structure

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.SpriteFinish
import com.morgana.tilegen.material.TileNoise
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Town buildings in the calm, chunky style of commercial top-down sheets: a roof of rounded outlined shingles lit from
 * the upper left, a clear wall with timber, stone foundation, large windows with shutters and a plank door, a chimney
 * with smoke, and a soft outline around the whole silhouette. Every building is drawn at 32 px per tile; the caller
 * scales it when the tile size differs.
 *
 * Placement rule (same as the older kits): only the bottom row of tiles is the footprint; rows above are fringe.
 * Deterministic for a seed.
 */
object HouseProps {
    private fun hex(s: String): Int {
        val v = s.removePrefix("#").toInt(16)
        return PaletteRamp.rgba((v shr 16) and 255, (v shr 8) and 255, v and 255)
    }

    private fun ramp(vararg h: String) = PaletteRamp(IntArray(h.size) { hex(h[it]) })

    private val roofRed = ramp("#4f1a1a", "#7e2a26", "#a83c30", "#cc5a46", "#e88a68")
    private val roofBrown = ramp("#33231a", "#553a2a", "#7a5640", "#9c7656", "#c19a72")
    private val roofSlate = ramp("#232d46", "#374a72", "#4e6694", "#6c86b4", "#98b0d6")
    private val roofGreen = ramp("#1b3f34", "#2b5e4c", "#3d7e66", "#5a9e84", "#86c0a4")
    private val roofThatch = ramp("#5e3f18", "#85612a", "#b0863a", "#d4aa50", "#eecf78")

    private val plaster = ramp("#6a5842", "#a39076", "#cdbd9c", "#e4d6b8", "#f6edd2")
    private val sandstone = ramp("#6a5036", "#a68458", "#cfab76", "#e6c68e", "#f6deac")
    private val redstone = ramp("#5a3a34", "#8e625a", "#b98678", "#d4a496", "#ecc8b8")
    private val blueplaster = ramp("#46505c", "#74808e", "#a0adba", "#c4cfd9", "#e4ebf1")
    private val stone = ramp("#34373f", "#575c68", "#7a808c", "#9ea5b0", "#c2c9d2")
    private val timber = ramp("#2a1a10", "#46301e", "#664a30", "#866244", "#a47c58")
    private val doorWood = ramp("#2e1c10", "#4e3320", "#704c30", "#946840", "#b88a58")
    private val shutterRed = ramp("#4a1c1c", "#7a2c28", "#a4443a", "#c46250", "#de8468")
    private val shutterGreen = ramp("#1c3a2c", "#2e5a44", "#437a5e", "#5e9a7c", "#86bca0")
    private val glowWarm = ramp("#7a4a1c", "#c88a2c", "#f0b84a", "#ffd878", "#fff2b8")
    private val glowBlue = ramp("#2a3c5a", "#46648a", "#6a8cb6", "#9ab8d8", "#d0e4f4")

    private fun roofFor(r: BuildingRoof?, default: BuildingRoof): PaletteRamp = when (r ?: default) {
        BuildingRoof.RED -> roofRed
        BuildingRoof.BROWN -> roofBrown
        BuildingRoof.SLATE -> roofSlate
        BuildingRoof.GREEN -> roofGreen
        BuildingRoof.THATCH -> roofThatch
    }

    private fun wallFor(t: BuildingTheme): PaletteRamp = when (t) {
        BuildingTheme.DEFAULT -> plaster
        BuildingTheme.DESERT -> sandstone
        BuildingTheme.EMBER -> redstone
        BuildingTheme.PORT -> blueplaster
    }

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
    }

    private class Look(
        val roof: PaletteRamp,
        val wall: PaletteRamp,
        val storeys: Int = 1,
        val windows: Int = 2,
        val door: Boolean = true,
        val frame: Boolean = false,
        val chimney: Boolean = true,
        val flowers: Boolean = false,
        val awning: Boolean = false,
        val dormers: Int = 0,
        val forge: Boolean = false,
        val shutters: PaletteRamp = shutterRed,
        val sign: Boolean = false
    )

    // ---- parts ---------------------------------------------------------------------------------------------------

    private fun roofBlock(c: Cv, x0: Int, x1: Int, eaveY: Int, roofH: Int, rp: PaletteRamp) {
        val ridgeY = eaveY - roofH
        val over = 6
        val inset = ((x1 - x0) * 0.15f).toInt()
        fun left(y: Int): Float = (x0 - over) + (inset + over) * ((eaveY - y).toFloat() / roofH)
        fun right(y: Int): Float = (x1 + over) - (inset + over) * ((eaveY - y).toFloat() / roofH)
        for (y in ridgeY..eaveY) {
            val xl = left(y)
            val xr = right(y)
            val rel = eaveY - y
            for (x in xl.toInt()..xr.toInt()) {
                val sx = x - xl.toInt()
                val t = (x - xl) / (xr - xl)
                val row = rel / 6
                fun shingle(r: Int, ly: Int, lx: Int, sIdx: Int): Int {
                    var i = if (t < 0.55f) 3 else 2
                    val j = c.hash(r, sIdx, 3L)
                    if (t < 0.55f) { if (j > 0.88f) i++ else if (j < 0.10f) i-- }
                    if (ly >= 5 && t < 0.55f) i++
                    return tone(rp, i)
                }
                fun bot(lx: Int): Int = (1.6f * ((lx - 3.5f) / 4f) * ((lx - 3.5f) / 4f)).toInt() + 0
                val off = if (row % 2 == 0) 0 else 4
                val lx = (sx + off) % 8
                val ly = rel % 6
                val b = bot(lx)
                val colour = when {
                    y == ridgeY || x <= xl.toInt() + 1 && y < eaveY - 1 || x >= xr.toInt() - 1 && y < eaveY - 1 -> tone(rp, 0)
                    y >= eaveY - 1 -> if (y == eaveY) tone(rp, 0) else tone(rp, 1)
                    y <= ridgeY + 2 -> tone(rp, 4)
                    ly < b && row > 0 -> {
                        val poff = if ((row - 1) % 2 == 0) 0 else 4
                        shingle(row - 1, ly + 6, (sx + poff) % 8, (sx + poff) / 8)
                    }
                    ly == b -> tone(rp, 0)
                    else -> shingle(row, ly, lx, (sx + off) / 8)
                }
                c.px(x, y, colour)
            }
        }
    }

    private fun windowAt(c: Cv, cx: Int, cy: Int, look: Look, glass: PaletteRamp, flower: Boolean) {
        // Frame and glass.
        val x0 = cx - 5
        val y0 = cy - 6
        c.rect(x0, y0, x0 + 10, y0 + 12, tone(timber, 0))
        for (y in y0 + 1..y0 + 11) for (x in x0 + 1..x0 + 9) {
            val g = if (y < y0 + 6) 4 else 3
            c.px(x, y, tone(glass, if (x < cx) g else g - 1))
        }
        c.rect(cx, y0 + 1, cx, y0 + 11, tone(timber, 1))
        c.rect(x0 + 1, cy, x0 + 9, cy, tone(timber, 1))
        // Shutters either side.
        for (side in intArrayOf(-1, 1)) {
            val sx0 = if (side < 0) x0 - 4 else x0 + 11
            c.rect(sx0, y0, sx0 + 2, y0 + 12, tone(timber, 0))
            for (y in y0 + 1..y0 + 11) {
                for (x in sx0..sx0 + 2) if (x != sx0 || true) c.px(x, y, tone(look.shutters, if ((y - y0) % 3 == 0) 1 else 3))
            }
            c.rect(sx0, y0, sx0 + 2, y0, tone(timber, 0))
            c.rect(sx0, y0 + 12, sx0 + 2, y0 + 12, tone(timber, 0))
        }
        // Sill.
        c.rect(x0 - 1, y0 + 13, x0 + 11, y0 + 14, tone(stone, 3))
        c.rect(x0 - 1, y0 + 14, x0 + 11, y0 + 14, tone(stone, 1))
        if (flower) {
            c.rect(x0, y0 + 11, x0 + 10, y0 + 13, tone(timber, 2))
            for (i in 0..4) {
                val fx = x0 + 1 + i * 2
                c.px(fx, y0 + 10, if (i % 2 == 0) hex("#e0556a") else hex("#f2d24a"))
                c.px(fx, y0 + 9, tone(shutterGreen, 3))
            }
        }
    }

    private fun doorAt(c: Cv, cx: Int, yBase: Int, h: Int = 21) {
        val w = 14
        val x0 = cx - w / 2
        val top = yBase - h
        for (y in top..yBase - 1) {
            val inset = when (y - top) { 0 -> 4; 1 -> 2; 2 -> 1; else -> 0 }
            for (x in x0 + inset..x0 + w - 1 - inset) {
                val edge = x == x0 + inset || x == x0 + w - 1 - inset || y == top
                c.px(x, y, if (edge) tone(timber, 0) else tone(doorWood, if ((x - x0) % 4 == 0) 2 else 3))
            }
        }
        c.rect(x0 + 10, top + h / 2, x0 + 10, top + h / 2 + 1, tone(glowWarm, 3))
        c.rect(x0 + 2, top + 5, x0 + w - 3, top + 5, tone(doorWood, 1))
        c.rect(cx - 8, yBase, cx + 7, yBase + 1, tone(stone, 3))
        c.rect(cx - 8, yBase + 1, cx + 7, yBase + 1, tone(stone, 1))
    }

    private fun chimneyAt(c: Cv, x: Int, topY: Int, botY: Int, w: Int, smoke: Boolean) {
        for (y in topY..botY) for (xx in x..x + w) {
            val edge = xx == x || xx == x + w || y == topY
            c.px(xx, y, if (edge) tone(stone, 0) else tone(stone, if (xx < x + w / 2) 3 else 2))
        }
        c.rect(x - 1, topY - 2, x + w + 1, topY, tone(stone, 3))
        c.rect(x - 1, topY - 2, x + w + 1, topY - 2, tone(stone, 0))
        c.rect(x + 2, topY - 1, x + w - 2, topY - 1, tone(stone, 0))
        if (smoke) {
            val puffs = listOf(Triple(x + w / 2 + 1, topY - 6, 3), Triple(x + w / 2 + 3, topY - 12, 4), Triple(x + w / 2, topY - 19, 5))
            for ((i, p) in puffs.withIndex()) {
                val a = intArrayOf(170, 130, 90)[i]
                for (yy in p.second - p.third..p.second + p.third) for (xx in p.first - p.third..p.first + p.third) {
                    if (hypot((xx - p.first).toFloat(), (yy - p.second).toFloat()) <= p.third) c.blend(xx, yy, PaletteRamp.rgba(238, 238, 244, a))
                }
            }
        }
    }

    private fun foundation(c: Cv, x0: Int, x1: Int, yBase: Int, st: PaletteRamp) {
        for (y in yBase - 6..yBase) for (x in x0..x1) {
            val course = (y - (yBase - 6)) / 3
            val off = if (course % 2 == 0) 0 else 4
            val joint = (x + off) % 8 == 0 || y == yBase - 6 + course * 3
            val h = c.hash((x + off) / 8, course, 5L)
            c.px(x, y, if (y == yBase) tone(st, 0) else if (joint) tone(st, 1) else tone(st, if (h > 0.5f) 3 else 2))
        }
    }

    private fun wallBlock(c: Cv, x0: Int, x1: Int, yBase: Int, wallH: Int, look: Look, stoneWalls: Boolean) {
        val top = yBase - wallH
        val wr = if (stoneWalls) stone else look.wall
        for (y in top..yBase) for (x in x0..x1) {
            val t = (x - x0).toFloat() / (x1 - x0)
            var i = if (t < 0.42f) 3 else 2
            if (x >= x1 - 4) i = 1
            if (stoneWalls) {
                val course = (y - top) / 6
                val off = if (course % 2 == 0) 0 else 5
                val joint = (x + off) % 10 == 0 || (y - top) % 6 == 0
                val h = c.hash((x + off) / 10, course, 9L)
                c.px(x, y, if (joint) tone(wr, 1) else tone(wr, (i + (if (h > 0.7f) 1 else 0)).coerceIn(1, 4)))
            } else {
                val speck = c.hash(x, y, 2L) > 0.95f
                c.px(x, y, tone(wr, if (speck) i - 1 else i))
            }
        }
        // Timber frame.
        if (look.frame) {
            val beams = listOf(x0 + 1, (x0 + x1) / 2 - 1, x1 - 4)
            for (bx in beams) c.rect(bx, top, bx + 2, yBase - 7, tone(timber, 2))
            for (bx in beams) { c.rect(bx, top, bx, yBase - 7, tone(timber, 3)); c.rect(bx + 2, top, bx + 2, yBase - 7, tone(timber, 1)) }
            if (look.storeys > 1) c.rect(x0, top + wallH / 2 - 2, x1, top + wallH / 2, tone(timber, 2))
            c.rect(x0, top + 6, x1, top + 8, tone(timber, 2))
        }
        // Outline sides and the eave shadow under the roof.
        for (y in top..yBase) { c.px(x0, y, tone(timber, 0)); c.px(x1, y, tone(timber, 0)) }
        for (x in x0..x1) for (y in top + 5..top + 9) c.px(x, y, tone(wr, 1))
    }

    private fun house(c: Cv, x0: Int, x1: Int, yBase: Int, wallH: Int, roofH: Int, look: Look, stoneWalls: Boolean = false) {
        wallBlock(c, x0, x1, yBase, wallH, look, stoneWalls)
        foundation(c, x0 + 1, x1 - 1, yBase, stone)
        val eaveY = yBase - wallH + 5
        // Windows and door.
        val floors = look.storeys
        val floorH = (wallH - 14) / floors
        val width = x1 - x0
        for (f in 0 until floors) {
            val slots = look.windows + (if (look.door && f == 0) 1 else 0)
            val cy = yBase - 7 - floorH * f - floorH / 2 - 1
            for (s in 0 until slots) {
                val cx = x0 + (width * (2 * s + 1)) / (2 * slots)
                val isDoor = look.door && f == 0 && s == slots / 2
                if (isDoor) continue
                if (look.forge && f == 0 && s == 0) {
                    c.rect(cx - 7, cy - 6, cx + 7, cy + 8, tone(timber, 0))
                    for (y in cy - 5..cy + 7) for (x in cx - 6..cx + 6) {
                        val d = (y - (cy - 5)) / 12f
                        c.px(x, y, if (d < 0.35f) tone(stone, 0) else tone(glowWarm, if (d > 0.7f) 3 else 2))
                    }
                    c.rect(cx - 4, cy + 4, cx + 3, cy + 6, tone(stone, 0))
                    continue
                }
                windowAt(c, cx, cy, look, if (f == 0) glowWarm else glowBlue, look.flowers && f == 0)
            }
            if (look.door && f == 0) doorAt(c, x0 + (width * (2 * (slots / 2) + 1)) / (2 * slots), yBase)
        }
        if (look.awning) {
            val ay = yBase - wallH + 9
            for (x in x0 - 3..x1 + 3) {
                val stripe = ((x - x0) / 6) % 2 == 0
                val h = 7 + (if (((x - x0) % 6) in 1..4) 1 else 0)
                for (y in ay..ay + h) c.px(x, y, if (y == ay + h) tone(roofRed, 0) else if (stripe) tone(roofRed, 3) else tone(plaster, 4))
                c.px(x, ay, tone(roofRed, 0))
            }
            for (x in x0..x1) for (y in ay + 9..ay + 11) c.blend(x, y, PaletteRamp.rgba(20, 16, 10, 70))
        }
        roofBlock(c, x0, x1, eaveY, roofH, look.roof)
        // Dormers.
        for (d in 0 until look.dormers) {
            val dx = x0 + (width * (2 * d + 1)) / (2 * look.dormers)
            val dy = eaveY - roofH / 2
            c.rect(dx - 6, dy - 2, dx + 6, dy + 9, tone(look.wall, 3))
            c.rect(dx - 6, dy - 2, dx - 6, dy + 9, tone(timber, 0)); c.rect(dx + 6, dy - 2, dx + 6, dy + 9, tone(timber, 0))
            c.rect(dx - 3, dy + 1, dx + 3, dy + 7, tone(glowBlue, 3)); c.rect(dx - 3, dy + 1, dx + 3, dy + 1, tone(timber, 0))
            c.rect(dx, dy + 1, dx, dy + 7, tone(timber, 1))
            for (k in 0..6) c.rect(dx - 7 + k, dy - 3 - k, dx + 7 - k, dy - 3 - k, if (k == 6) tone(look.roof, 0) else tone(look.roof, if (k < 3) 3 else 2))
            c.rect(dx - 7, dy - 3, dx + 7, dy - 3, tone(look.roof, 0))
        }
        if (look.chimney) {
            val cxp = x1 - 28
            chimneyAt(c, cxp, eaveY - roofH - 2, eaveY - roofH * 5 / 12, 10, smoke = true)
        }
        if (look.sign) {
            val sx = x0 - 8
            val sy = yBase - wallH / 2 - 4
            c.rect(sx, sy - 4, sx + 9, sy - 3, tone(timber, 1))
            c.rect(sx + 2, sy - 2, sx + 8, sy + 4, tone(timber, 0))
            c.rect(sx + 3, sy - 1, sx + 7, sy + 3, tone(roofRed, 3))
            c.rect(sx + 4, sy, sx + 6, sy + 2, tone(glowWarm, 4))
        }
    }

    // ---- kinds ---------------------------------------------------------------------------------------------------

    /** Draws [kind] at 32 px per tile. */
    fun draw(kind: BuildingKind, roof: BuildingRoof?, theme: BuildingTheme, seed: Long): TileImage {
        val w = kind.widthTiles * 32
        val h = kind.heightTiles * 32
        val canvas = TileImage(w, h)
        val c = Cv(canvas, seed)
        val wall = wallFor(theme)
        // Soft ground shadow.
        fun shadow(cx: Int, cy: Int, rx: Int, ry: Int) {
            for (y in cy - ry..cy + ry) for (x in cx - rx..cx + rx) {
                val nx = (x - cx).toFloat() / rx
                val ny = (y - cy).toFloat() / ry
                if (nx * nx + ny * ny <= 1f) c.blend(x, y, PaletteRamp.rgba(14, 22, 12, 80))
            }
        }
        when (kind) {
            BuildingKind.COTTAGE_LARGE -> {
                shadow(w / 2, h - 3, w / 2 - 4, 4)
                house(c, 14, w - 14, h - 5, 40, 36, Look(roofFor(roof, BuildingRoof.RED), wall, windows = 2, flowers = true))
            }
            BuildingKind.SHOP -> {
                shadow(w / 2, h - 3, w / 2 - 4, 4)
                house(c, 12, w - 12, h - 5, 46, 30, Look(roofFor(roof, BuildingRoof.GREEN), wall, windows = 2, awning = true, shutters = shutterGreen, flowers = false, sign = true))
            }
            BuildingKind.SMITHY -> {
                shadow(w / 2, h - 3, w / 2 - 4, 4)
                house(c, 12, w - 12, h - 5, 42, 34, Look(roofFor(roof, BuildingRoof.SLATE), wall, windows = 2, forge = true, shutters = shutterGreen), stoneWalls = true)
            }
            BuildingKind.INN -> {
                shadow(w / 2, h - 3, w / 2 - 4, 5)
                house(c, 12, w - 12, h - 5, 68, 42, Look(roofFor(roof, BuildingRoof.BROWN), wall, storeys = 2, windows = 3, frame = true, dormers = 2, sign = true, flowers = true))
            }
            BuildingKind.GREAT_HALL -> {
                shadow(w / 2, h - 3, w / 2 - 4, 5)
                val rp = roofFor(roof, BuildingRoof.RED)
                house(c, 6, 74, h - 5, 40, 36, Look(rp, wall, windows = 2, chimney = false, door = false, frame = true))
                house(c, w - 74, w - 6, h - 5, 40, 36, Look(rp, wall, windows = 2, chimney = true, door = false, frame = true))
                house(c, 50, w - 50, h - 5, 66, 46, Look(rp, wall, storeys = 2, windows = 3, frame = true, chimney = false))
                // A flag on the ridge.
                c.rect(w / 2, 1, w / 2, 17, tone(timber, 1))
                for (k in 0..6) c.rect(w / 2 + 1, 2 + k, w / 2 + 11 - k, 2 + k, tone(roofRed, if (k == 0) 4 else 3))
                c.rect(w / 2 + 1, 2, w / 2 + 11, 2, tone(roofRed, 0))
            }
            BuildingKind.CHAPEL -> {
                shadow(w / 2, h - 3, w / 2 - 4, 4)
                val rp = roofFor(roof, BuildingRoof.SLATE)
                // Tower behind the nave.
                wallBlock(c, 30, 66, 96, 60, Look(rp, stone), stoneWalls = true)
                foundation(c, 31, 65, 96, stone)
                for (y in 8..38) {
                    val half = ((y - 8) * 24 / 30).coerceAtMost(24)
                    for (x in 48 - half..48 + half) {
                        val rel = (x - (48 - half)).toFloat() / (2 * half + 1)
                        val edge = x == 48 - half || x == 48 + half || y == 8
                        c.px(x, y, if (edge) tone(rp, 0) else tone(rp, if (rel < 0.45f) 3 else if (rel < 0.8f) 2 else 1))
                    }
                }
                c.rect(47, 0, 49, 9, tone(glowWarm, 3)); c.rect(44, 3, 52, 4, tone(glowWarm, 3))
                c.rect(42, 42, 54, 58, tone(timber, 0)); c.rect(43, 43, 53, 57, tone(glowBlue, 3)); c.rect(48, 43, 48, 57, tone(timber, 1)); c.rect(43, 50, 53, 50, tone(timber, 1))
                house(c, 8, w - 8, h - 5, 38, 30, Look(rp, stone, windows = 2, chimney = false, shutters = shutterGreen), stoneWalls = true)
            }
            BuildingKind.GUARDHOUSE -> {
                shadow(w / 2, h - 3, w / 2 - 4, 4)
                val x0 = 12
                val x1 = w - 12
                val top = 26
                val yb = h - 5
                wallBlock(c, x0, x1, yb, yb - top, Look(roofSlate, stone), stoneWalls = true)
                foundation(c, x0 + 1, x1 - 1, yb, stone)
                // Parapet and merlons.
                c.rect(x0 - 2, top - 6, x1 + 2, top + 1, tone(stone, 3))
                c.rect(x0 - 2, top + 1, x1 + 2, top + 2, tone(stone, 1))
                var mx = x0 - 2
                while (mx < x1 + 2) {
                    c.rect(mx, top - 12, mx + 7, top - 7, tone(stone, 3)); c.rect(mx, top - 12, mx, top - 7, tone(stone, 4))
                    c.rect(mx, top - 12, mx + 7, top - 12, tone(stone, 0)); c.rect(mx + 7, top - 12, mx + 7, top - 7, tone(stone, 1))
                    mx += 12
                }
                c.rect(x0 - 2, top - 7, x1 + 2, top - 7, tone(stone, 4))
                // Arrow slits, door and banner.
                for (sx in intArrayOf(x0 + 12, x1 - 12)) c.rect(sx, top + 12, sx + 1, top + 24, tone(stone, 0))
                doorAt(c, w / 2, yb)
                c.rect(w / 2 + 14, top + 6, w / 2 + 22, top + 28, tone(roofRed, 0)); c.rect(w / 2 + 15, top + 7, w / 2 + 21, top + 26, tone(roofRed, 3))
                c.rect(w / 2 + 17, top + 12, w / 2 + 19, top + 16, tone(glowWarm, 4))
            }
        }
        return SpriteFinish.softOutline(canvas)
    }
}
