package com.morgana.tilegen.structure

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelDither
import com.morgana.tilegen.material.SpriteFinish
import com.morgana.tilegen.material.TileNoise
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Plaza furniture in the soft map style: a tiered fountain, a covered well, a lamp post, a bench and a planter.
 *
 * **Placement** is the same rule as the buildings: tiles that are the object's base go on `MASK_1` with a `blocked`
 * attribute, tiles that are only its top (the fountain's spout and finial, the well's roof, the lantern) go on `FRINGE_1`
 * so characters walk behind them. [PlazaKind.blockedFromRow] is the first tile row that blocks; rows above it are fringe.
 * Rows or columns with no pixels need no tile.
 */
enum class PlazaKind(val widthTiles: Int, val heightTiles: Int, val blockedFromRow: Int, val label: String) {
    FOUNTAIN(4, 4, 1, "Tiered fountain"),
    WELL(2, 2, 1, "Covered well"),
    LAMP_POST(1, 2, 1, "Lamp post"),
    BENCH(2, 1, 0, "Bench"),
    PLANTER(1, 1, 0, "Flower planter")
}

class GeneratedPlazaProp(val kind: PlazaKind, val image: TileImage, val tiles: List<NamedTile>)

class PlazaGenerator(val seed: Long = 1337L) {
    private fun natural(r: Int, g: Int, b: Int, spread: Float = 0.30f) =
        PaletteRamp.fromBaseColor(PaletteRamp.rgba(r, g, b), steps = 6, hueShift = 0.05f, valueSpread = spread)

    private val stone = natural(178, 172, 162, 0.28f)
    private val darkStone = natural(120, 118, 122, 0.28f)
    private val water = natural(70, 140, 186, 0.34f)
    private val iron = natural(84, 90, 104)
    private val wood = natural(126, 86, 56)
    private val crystal = natural(110, 190, 240, 0.34f)
    private val roofRamp = natural(140, 96, 66)
    private val soil = natural(96, 66, 46)

    fun generate(kind: PlazaKind): GeneratedPlazaProp {
        val w = kind.widthTiles * 32
        val h = kind.heightTiles * 32
        val c = Painter(TileImage(w, h), seed + kind.ordinal * 101L)
        when (kind) {
            PlazaKind.FOUNTAIN -> fountain(c)
            PlazaKind.WELL -> well(c)
            PlazaKind.LAMP_POST -> lamp(c)
            PlazaKind.BENCH -> bench(c)
            PlazaKind.PLANTER -> planter(c)
        }
        val out = TileImage(w, h)
        c.shadow(out, kind)
        out.drawImage(SpriteFinish.softOutline(c.img), 0, 0, w, h, 0, 0)
        val tiles = mutableListOf<NamedTile>()
        for (ty in 0 until kind.heightTiles) for (tx in 0 until kind.widthTiles) {
            tiles += NamedTile("${kind.name.lowercase()}_${tx}_$ty", out.crop(tx * 32, ty * 32, 32, 32))
        }
        return GeneratedPlazaProp(kind, out, tiles)
    }

    /** All kinds on one sheet (7 x 4 tiles): fountain at (0,0), well (4,0), lamp (6,0), bench (4,2), planter (6,2). */
    fun sheet(): Pair<TileImage, Map<PlazaKind, Pair<Int, Int>>> {
        val sheet = TileImage(7 * 32, 4 * 32)
        val at = mapOf(
            PlazaKind.FOUNTAIN to (0 to 0), PlazaKind.WELL to (4 to 0), PlazaKind.LAMP_POST to (6 to 0),
            PlazaKind.BENCH to (4 to 2), PlazaKind.PLANTER to (6 to 2)
        )
        for ((kind, pos) in at) {
            val g = generate(kind)
            sheet.drawImage(g.image, 0, 0, g.image.width, g.image.height, pos.first * 32, pos.second * 32)
        }
        return sheet to at
    }

    private inner class Painter(val img: TileImage, val seed: Long) {
        fun px(x: Int, y: Int, c: Int) {
            if (x in 1 until img.width - 1 && y in 1 until img.height - 1) img.setPixel(x, y, c)
        }

        fun tone(p: PaletteRamp, t: Float, x: Int, y: Int) = PixelDither.sampleDithered(p, t.coerceIn(0.05f, 0.98f), x, y, 4, 0.12f)
        fun rnd(x: Int, y: Int, salt: Long = 0L) = TileNoise.hash(x, y, seed + salt)

        /** A shaded ellipse; [shade] maps (dx, dy) in -1..1 to a tone. */
        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, p: PaletteRamp, base: Float, bias: (Float, Float) -> Float = { _, _ -> 0f }) {
            for (y in (cy - ry).toInt() - 1..(cy + ry).toInt() + 1) for (x in (cx - rx).toInt() - 1..(cx + rx).toInt() + 1) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                if (dx * dx + dy * dy <= 1f) px(x, y, tone(p, base - dx * 0.16f - dy * 0.12f + bias(dx, dy), x, y))
            }
        }

        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, p: PaletteRamp, base: Float) {
            for (y in y0..y1) for (x in x0..x1) {
                val u = (x - x0).toFloat() / (x1 - x0).coerceAtLeast(1)
                px(x, y, tone(p, base - u * 0.30f + 0.12f, x, y))
            }
        }

        fun shadow(into: TileImage, kind: PlazaKind) {
            val core = PaletteRamp.rgba(10, 18, 12, 84)
            val rim = PaletteRamp.rgba(10, 18, 12, 44)
            val h = img.height
            val w = img.width
            val ry = if (kind == PlazaKind.FOUNTAIN) 5f else 3f
            val rx = w * (if (kind == PlazaKind.FOUNTAIN) 0.47f else 0.42f)
            for (y in (h - ry - 1).toInt() until h) for (x in 0 until w) {
                val d = hypot((x + 0.5f - w / 2f) / rx, (y + 0.5f - (h - 2f)) / ry)
                if (d <= 0.72f) into.drawPixel(x, y, core) else if (d <= 1f) into.drawPixel(x, y, rim)
            }
        }
    }

    // ---- fountain ---------------------------------------------------------------------------------------------------

    private fun fountain(c: Painter) {
        val cx = 64f
        // basin: the outer side wall (stone courses), then the lit top rim, then the dark inner wall and the water
        for (y in 80..124) for (x in 6..122) {
            val dx = (x + 0.5f - cx) / 57f
            val dy = (y + 0.5f - 96f) / 28f
            if (dx * dx + dy * dy <= 1f && y >= 88) {
                val course = ((y - 88) / 7)
                val joint = (x + course * 9) % 18 == 0 || (y - 88) % 7 == 6
                c.px(x, y, c.tone(stone, 0.60f - dx * 0.22f - (y - 88) * 0.004f + (if (joint) -0.18f else 0f), x, y))
            }
        }
        c.ellipse(cx, 88f, 57f, 28f, stone, 0.82f)                          // lit rim top
        c.ellipse(cx, 89f, 49f, 23.5f, darkStone, 0.34f)                      // dark inner wall
        c.ellipse(cx, 92f, 47f, 21f, water, 0.62f) { dx, dy -> -dy * 0.06f + 0.04f * sin((dx * 9f)) } // water
        // ripples
        for (i in 1..3) {
            val rr = i * 11f
            for (a in 0 until 360 step 3) {
                val x = (cx + kotlin.math.cos(Math.toRadians(a.toDouble())) * rr * 1.7f).toInt()
                val y = (92f + kotlin.math.sin(Math.toRadians(a.toDouble())) * rr * 0.78f).toInt()
                if (hypot((x - cx) / 47f, (y - 92f) / 21f) < 0.95f && (a / 6 + i) % 2 == 0) c.px(x, y, c.tone(water, 0.86f, x, y))
            }
        }
        // pedestal: wide base, column, two bowls and a crystal finial
        c.ellipse(cx, 88f, 17f, 7f, stone, 0.7f)
        c.rect(53, 62, 75, 88, stone, 0.64f)
        c.ellipse(cx, 64f, 27f, 10f, stone, 0.8f)                             // lower bowl rim
        c.ellipse(cx, 65f, 23f, 7.5f, water, 0.66f)
        c.rect(58, 36, 70, 62, stone, 0.66f)
        c.ellipse(cx, 38f, 14f, 5.5f, stone, 0.84f)                           // upper bowl rim
        c.ellipse(cx, 38.6f, 11.5f, 3.8f, water, 0.7f)
        c.rect(61, 22, 67, 36, stone, 0.62f)
        c.ellipse(cx, 16f, 8.5f, 9f, crystal, 0.7f)                           // finial crystal
        for (dy in -3..0) c.px(60 + dy + 3, 12 + dy + 3, c.tone(crystal, 0.96f, 0, 0))
        // water streams: upper bowl -> lower bowl, lower bowl -> basin, with splash rings where they land
        val streamLight = PaletteRamp.rgba(206, 236, 252, 220)
        val streamMid = PaletteRamp.rgba(150, 204, 236, 200)
        for (sgn in listOf(-1, 1)) {
            val sx0 = (cx + sgn * 12f).toInt()
            for (y in 41..62) c.px(sx0 + sgn * ((y - 41) / 9), y, if ((y + sgn) % 3 == 0) streamLight else streamMid)
            val sx1 = (cx + sgn * 24f).toInt()
            for (y in 66..90) c.px(sx1 + sgn * ((y - 66) / 8), y, if ((y + sgn) % 3 == 0) streamLight else streamMid)
            val landX = sx1 + sgn * 3
            for (a in 0 until 360 step 20) {
                c.px((landX + kotlin.math.cos(Math.toRadians(a.toDouble())) * 5).toInt(), (92 + kotlin.math.sin(Math.toRadians(a.toDouble())) * 2).toInt(), streamLight)
            }
        }
        for (a in 0 until 360 step 24) c.px((cx + kotlin.math.cos(Math.toRadians(a.toDouble())) * 9).toInt(), (66 + kotlin.math.sin(Math.toRadians(a.toDouble())) * 3).toInt(), streamLight)
        // rim highlights and a spray of droplets at the top
        for (x in (cx - 50).toInt()..(cx + 50).toInt()) {
            val yy = (88f - 28f * kotlin.math.sqrt((1f - ((x + 0.5f - cx) / 57f) * ((x + 0.5f - cx) / 57f)).coerceAtLeast(0f))).toInt()
            if (x < cx - 8 && c.rnd(x, 0) < 0.7f) c.px(x, yy, c.tone(stone, 0.98f, x, yy))
        }
        for (i in 0 until 7) c.px(52 + i * 4 + (c.rnd(i, 3) * 2).toInt(), 24 + (c.rnd(i, 4) * 10).toInt(), streamLight)
    }

    // ---- well -------------------------------------------------------------------------------------------------------

    private fun well(c: Painter) {
        val cx = 32f
        for (y in 36..60) for (x in 8..56) {
            val dx = (x + 0.5f - cx) / 24f
            val dy = (y + 0.5f - 46f) / 14f
            if (dx * dx + dy * dy <= 1f && y >= 46) {
                val joint = (x + ((y - 46) / 6) * 7) % 14 == 0 || (y - 46) % 6 == 5
                c.px(x, y, c.tone(stone, 0.60f - dx * 0.2f + (if (joint) -0.18f else 0f), x, y))
            }
        }
        c.ellipse(cx, 46f, 24f, 11f, stone, 0.82f)
        c.ellipse(cx, 47f, 17f, 7.5f, darkStone, 0.24f)
        c.ellipse(cx, 49f, 14f, 5.5f, water, 0.5f)
        // posts, crossbeam, rope and bucket
        c.rect(11, 18, 14, 50, wood, 0.6f)
        c.rect(50, 18, 53, 50, wood, 0.5f)
        c.rect(11, 16, 53, 19, wood, 0.62f)
        for (y in 20..34) c.px(32, y, c.tone(natural(210, 190, 140), 0.7f, 0, y))
        c.rect(28, 34, 36, 41, wood, 0.54f)
        for (x in 28..36) c.px(x, 35, c.tone(iron, 0.5f, x, 0))
        // small shingled roof
        for (y in 3..17) {
            val half = 6 + (y - 3) * 2
            for (x in (cx - half).toInt()..(cx + half).toInt()) {
                val row = (y - 3) / 3
                val lx = Math.floorMod(x + (row % 2) * 3, 6)
                val edge = x - (cx - half).toInt() < 2 || (cx + half).toInt() - x < 2
                val t = 0.62f + (c.rnd(x / 6, row) - 0.5f) * 0.16f - (x - cx) * 0.004f
                c.px(x, y, c.tone(roofRamp, if (edge) 0.22f else if ((y - 3) % 3 == 2) t - 0.26f else if (lx == 0) t - 0.12f else t, x, y))
            }
        }
        for (x in (cx - 6).toInt()..(cx + 6).toInt()) c.px(x, 3, c.tone(roofRamp, 0.86f, x, 3))
    }

    // ---- lamp post, bench, planter ----------------------------------------------------------------------------------

    private fun lamp(c: Painter) {
        c.rect(10, 55, 21, 62, stone, 0.66f)
        c.rect(12, 51, 19, 55, stone, 0.74f)
        c.rect(15, 20, 16, 52, iron, 0.56f)
        for (x in 9..22) c.px(x, 19, c.tone(iron, 0.5f, x, 0))
        // lantern cage with a warm glow
        for (y in 6..18) for (x in 10..21) {
            val t = 0.96f - (y - 6) * 0.025f - abs(x - 15.5f) * 0.012f
            val frame = x == 10 || x == 21 || y == 6 || y == 18 || x == 15 || x == 16
            c.px(x, y, if (frame) c.tone(iron, 0.4f, x, y) else c.tone(natural(255, 214, 110), t, x, y))
        }
        for (i in 0..5) for (x in 15 - i..16 + i) c.px(x, 5 - i, c.tone(iron, 0.5f - i * 0.02f, x, 5 - i))
        c.px(15, 1, c.tone(iron, 0.7f, 0, 0))
        // curled bracket
        for (i in 0..5) c.px(16 + i, 38 + i / 2, c.tone(iron, 0.5f, i, 0))
    }

    private fun bench(c: Painter) {
        for (x in 5..58) for (y in 17..21) c.px(x, y, c.tone(wood, 0.68f - (y - 17) * 0.05f - (if ((x - 5) % 14 == 13) 0.2f else 0f), x, y))   // seat
        for (x in 5..58) for (y in 9..14) c.px(x, y, c.tone(wood, 0.62f - (y - 9) * 0.04f - (if ((x - 5) % 18 == 17) 0.2f else 0f), x, y))     // back
        for (lx in listOf(8, 54)) for (y in 9..28) { c.px(lx, y, c.tone(iron, 0.5f, 0, y)); c.px(lx + 1, y, c.tone(iron, 0.3f, 1, y)) }
        for (lx in listOf(7, 53)) for (x in lx..lx + 3) c.px(x, 28, c.tone(iron, 0.4f, x, 0))
        for (x in 6..57) c.px(x, 22, c.tone(wood, 0.28f, x, 22))
    }

    private fun planter(c: Painter) {
        c.rect(5, 17, 26, 28, stone, 0.66f)
        for (x in 5..26) { c.px(x, 17, c.tone(stone, 0.9f, x, 17)); c.px(x, 28, c.tone(stone, 0.3f, x, 28)) }
        for (x in 6..25) for (y in 12..16) c.px(x, y, c.tone(soil, 0.5f - (y - 12) * 0.03f, x, y))
        val leaf = natural(78, 138, 82)
        for (x in 5..26) for (y in 6..14) {
            val dy = (y - 11f) / 5.5f
            val dx = (x - 15.5f) / 11.5f
            if (dx * dx + dy * dy <= 1f) c.px(x, y, c.tone(leaf, 0.6f - dx * 0.12f - dy * 0.1f + (c.rnd(x, y) - 0.5f) * 0.18f, x, y))
        }
        val blooms = listOf(PaletteRamp.rgba(230, 76, 86), PaletteRamp.rgba(250, 214, 96), PaletteRamp.rgba(246, 240, 236), PaletteRamp.rgba(176, 120, 224))
        for (i in 0 until 9) {
            val x = 8 + (c.rnd(i, 11) * 17).toInt()
            val y = 6 + (c.rnd(i, 12) * 7).toInt()
            c.px(x, y, blooms[i % blooms.size]); c.px(x + 1, y, blooms[i % blooms.size])
        }
    }
}
