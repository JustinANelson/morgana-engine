package com.morgana.tilegen.structure

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelDither
import com.morgana.tilegen.material.SpriteFinish
import com.morgana.tilegen.material.TileNoise
import kotlin.math.abs

/**
 * Buildings for towns, drawn as one multi-tile picture in the same soft style as the trees: hue-shifted ramps, a light
 * from the upper left, dithered shading, a darker-step outline and a translucent ground shadow.
 *
 * **How a building is placed on a map.** The picture is [BuildingKind.widthTiles] x [BuildingKind.heightTiles] tiles.
 * Only the bottom row of tiles is the footprint (walls, door and windows at ground level): put those on a blocking layer.
 * Every row above it (roof, upper storey, chimney) goes on a fringe layer so characters walk behind it. That is the same
 * rule the oak trees use (trunk row blocked, canopy rows fringe). The door is on the bottom row, so
 * [GeneratedBuilding.doorColumn] says which column of that row holds it.
 *
 * Deterministic for a seed.
 */
/** Roof colours a building can be drawn with; each kind has its own default. */
enum class BuildingRoof { RED, BROWN, SLATE, GREEN, THATCH }

/** Wall colours: the default cream plaster and grey stone, sandstone for desert towns, red stone for the fortress, weathered blue-grey for the port. */
enum class BuildingTheme { DEFAULT, DESERT, EMBER, PORT }

enum class BuildingKind(val widthTiles: Int, val heightTiles: Int, val label: String) {
    COTTAGE_LARGE(4, 3, "Large cottage"),
    INN(5, 4, "Inn"),
    SMITHY(4, 3, "Smithy"),
    SHOP(4, 3, "Shop"),
    CHAPEL(3, 4, "Chapel"),
    GUARDHOUSE(3, 3, "Guardhouse"),
    GREAT_HALL(6, 4, "Great hall")
}

data class GeneratedBuilding(
    val name: String,
    val kind: BuildingKind,
    val image: TileImage,
    val tiles: List<NamedTile>,
    /** Column (0-based) of the bottom row that holds the front door. */
    val doorColumn: Int
)

class BuildingGenerator(val seed: Long = 1337L, val theme: BuildingTheme = BuildingTheme.DEFAULT) {
    private val tile = 32

    private fun natural(r: Int, g: Int, b: Int, spread: Float = 0.30f) =
        PaletteRamp.fromBaseColor(PaletteRamp.rgba(r, g, b), steps = 6, hueShift = 0.05f, valueSpread = spread)

    private val plaster = when (theme) {
        BuildingTheme.DEFAULT -> natural(232, 218, 190, 0.24f)
        BuildingTheme.DESERT -> natural(236, 208, 160, 0.24f)
        BuildingTheme.EMBER -> natural(210, 176, 154, 0.24f)
        BuildingTheme.PORT -> natural(206, 214, 214, 0.24f)
    }
    private val stoneWall = when (theme) {
        BuildingTheme.DEFAULT -> natural(158, 154, 150, 0.28f)
        BuildingTheme.DESERT -> natural(190, 166, 128, 0.28f)
        BuildingTheme.EMBER -> natural(150, 116, 112, 0.28f)
        BuildingTheme.PORT -> natural(138, 148, 156, 0.28f)
    }
    private val timber = natural(112, 76, 50)
    private val doorWood = natural(128, 84, 52)
    private val glassLit = natural(250, 212, 118)
    private val glassBlue = natural(120, 160, 206)
    private val ironRamp = natural(96, 100, 112)

    private val roofRed = natural(184, 72, 56)
    private val roofBrown = natural(140, 96, 66)
    private val roofSlate = natural(92, 106, 134)
    private val roofGreen = natural(88, 138, 116)
    private val roofThatch = natural(208, 172, 94)

    private fun roofRamp(r: BuildingRoof) = when (r) {
        BuildingRoof.RED -> roofRed
        BuildingRoof.BROWN -> roofBrown
        BuildingRoof.SLATE -> roofSlate
        BuildingRoof.GREEN -> roofGreen
        BuildingRoof.THATCH -> roofThatch
    }

    /** Draws [kind]; [roof] overrides the default roof colour. */
    fun generate(kind: BuildingKind, name: String = kind.name.lowercase(), roof: BuildingRoof? = null): GeneratedBuilding {
        val w = kind.widthTiles * tile
        val h = kind.heightTiles * tile
        val c = Canvas(TileImage(w, h), seed + name.hashCode().toLong() * 31L)
        val rr = roof?.let { roofRamp(it) }
        val door = when (kind) {
            BuildingKind.COTTAGE_LARGE -> cottage(c, w, h, rr ?: roofRed)
            BuildingKind.INN -> inn(c, w, h, rr ?: roofBrown)
            BuildingKind.SMITHY -> smithy(c, w, h, rr ?: roofSlate)
            BuildingKind.SHOP -> shop(c, w, h, rr ?: roofGreen)
            BuildingKind.CHAPEL -> chapel(c, w, h, rr ?: roofSlate)
            BuildingKind.GUARDHOUSE -> guardhouse(c, w, h)
            BuildingKind.GREAT_HALL -> greatHall(c, w, h, rr ?: roofRed)
        }
        val shadowed = TileImage(w, h)
        c.groundShadow(shadowed)
        shadowed.drawImage(SpriteFinish.softOutline(c.img), 0, 0, w, h, 0, 0)
        val tiles = mutableListOf<NamedTile>()
        for (ty in 0 until kind.heightTiles) for (tx in 0 until kind.widthTiles) {
            tiles += NamedTile("${name}_${tx}_$ty", shadowed.crop(tx * tile, ty * tile, tile, tile))
        }
        return GeneratedBuilding(name, kind, shadowed, tiles, door / tile)
    }

    // ---- the drawing toolkit ----------------------------------------------------------------------------------------

    private inner class Canvas(val img: TileImage, val seed: Long) {
        fun px(x: Int, y: Int, c: Int) {
            if (x in 1 until img.width - 1 && y in 1 until img.height - 1) img.setPixel(x, y, c)
        }

        fun rnd(x: Int, y: Int, salt: Long = 0L) = TileNoise.hash(x, y, seed + salt)

        fun tone(pal: PaletteRamp, t: Float, x: Int, y: Int) =
            PixelDither.sampleDithered(pal, t.coerceIn(0.05f, 0.98f), x, y, 4, 0.12f)

        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, pal: PaletteRamp, t: Float) {
            for (y in y0..y1) for (x in x0..x1) px(x, y, tone(pal, t - (x - x0) * 0.004f - (y - y0) * 0.004f, x, y))
        }

        /** A soft translucent shadow along the foot of the building, like the one under the trees. */
        fun groundShadow(into: TileImage) {
            val shadow = PaletteRamp.rgba(10, 18, 12, 84)
            val rim = PaletteRamp.rgba(10, 18, 12, 44)
            for (y in img.height - 4 until img.height) for (x in 3 until img.width - 1) {
                val edge = x < 6 || x > img.width - 5 || y == img.height - 1
                into.drawPixel(x, y, if (edge) rim else shadow)
            }
        }

        // -- walls

        fun plasterWall(x0: Int, y0: Int, x1: Int, y1: Int, frame: Boolean, storeys: Int, base: PaletteRamp = plaster) {
            val wallH = y1 - y0
            for (y in y0..y1) for (x in x0..x1) {
                val ao = ((5 - (y - y0)).coerceAtLeast(0)) * 0.07f
                val side = if (x - x0 < 2) 0.06f else if (x1 - x < 3) -0.12f else 0f
                val grain = (rnd(x / 2, y / 2) - 0.5f) * 0.05f
                px(x, y, tone(base, 0.78f - ao + side + grain - (y - y0) * 0.0015f, x, y))
            }
            if (!frame) return
            val postGap = ((x1 - x0) / (if (x1 - x0 > 110) 8 else 5)).coerceAtLeast(14)
            // horizontal beams: under the eaves, between storeys and on the sill
            val beams = mutableListOf(y0 + 5, y1 - 6)
            for (s in 1 until storeys) beams += y0 + (wallH * s) / storeys
            for (by in beams) for (x in x0..x1) {
                px(x, by, tone(timber, 0.62f, x, by)); px(x, by + 1, tone(timber, 0.5f, x, by + 1)); px(x, by + 2, tone(timber, 0.34f, x, by + 2))
            }
            var x = x0
            while (x <= x1) {
                for (y in y0..y1) {
                    px(x, y, tone(timber, 0.66f, x, y)); px(x + 1, y, tone(timber, 0.5f, x + 1, y)); px(x + 2, y, tone(timber, 0.34f, x + 2, y))
                }
                // a diagonal brace in every other panel of the upper storey
                if (storeys > 1 && ((x - x0) / postGap) % 2 == 0 && x + postGap <= x1) {
                    val top = y0 + 8
                    val bot = y0 + wallH / storeys - 2
                    for (i in 0..(bot - top)) px(x + 3 + (i * (postGap - 4)) / (bot - top).coerceAtLeast(1), bot - i, tone(timber, 0.5f, x, i))
                }
                x += postGap
            }
            for (y in y0..y1) { px(x1 - 1, y, tone(timber, 0.5f, x1, y)); px(x1, y, tone(timber, 0.34f, x1, y)) }
        }

        fun stoneBlocks(x0: Int, y0: Int, x1: Int, y1: Int, pal: PaletteRamp = stoneWall) {
            val bh = 6
            val bw = 11
            for (y in y0..y1) {
                val row = (y - y0) / bh
                val off = if (row % 2 == 0) 0 else bw / 2
                for (x in x0..x1) {
                    val col = (x - x0 + off) / bw
                    val lx = (x - x0 + off) % bw
                    val ly = (y - y0) % bh
                    val ao = ((5 - (y - y0)).coerceAtLeast(0)) * 0.06f
                    var t = 0.62f + (rnd(col, row) - 0.5f) * 0.20f - ao
                    t += if (x - x0 < 3) 0.06f else if (x1 - x < 3) -0.12f else 0f
                    val c = when {
                        ly == bh - 1 || lx == bw - 1 -> tone(pal, t - 0.20f, x, y) // mortar
                        ly == 0 || lx == 0 -> tone(pal, t + 0.10f, x, y)            // lit top-left edge of a block
                        else -> tone(pal, t, x, y)
                    }
                    px(x, y, c)
                }
            }
        }

        fun foundation(x0: Int, x1: Int, y1: Int) {
            for (y in y1 - 4..y1) for (x in x0..x1) {
                val lx = (x - x0) % 9
                val t = if (y == y1 - 4) 0.62f else if (lx == 0 || y == y1) 0.30f else 0.44f + (rnd(x / 9, y) - 0.5f) * 0.12f
                px(x, y, tone(stoneWall, t, x, y))
            }
        }

        // -- roofs

        /** A hip (trapezoid) or gable (triangle) roof with scalloped shingle rows, lit from the upper left. */
        fun roof(cx: Int, top: Int, eave: Int, topHalf: Int, bottomHalf: Int, pal: PaletteRamp, trimBottom: Boolean = true) {
            val h = eave - top
            for (y in top..eave) {
                val t = (y - top).toFloat() / h
                val half = topHalf + ((bottomHalf - topHalf) * t).toInt()
                val row = (y - top) / 4
                val ly = (y - top) % 4
                val off = (row % 2) * 4
                for (x in cx - half..cx + half) {
                    val col = (x + off) / 8
                    val lx = Math.floorMod(x + off, 8)
                    var tt = 0.62f + (rnd(col, row, 7) - 0.5f) * 0.16f - t * 0.08f - (x - cx) * 0.0035f
                    val edge = x - (cx - half) < 2 || (cx + half) - x < 2
                    val color = when {
                        edge -> tone(pal, 0.22f, x, y)
                        trimBottom && y >= eave - 1 -> tone(pal, 0.14f, x, y)
                        ly == 3 -> tone(pal, tt - 0.26f, x, y)
                        ly == 0 && lx in 1..6 -> tone(pal, tt + 0.10f, x, y)
                        lx == 0 -> tone(pal, tt - 0.14f, x, y)
                        else -> tone(pal, tt, x, y)
                    }
                    px(x, y, color)
                }
            }
            // ridge cap
            for (x in cx - topHalf..cx + topHalf) { px(x, top, tone(pal, 0.86f, x, top)); px(x, top + 1, tone(pal, 0.34f, x, top + 1)) }
        }

        fun chimney(x: Int, roofY: Int, h: Int) {
            for (y in roofY - h..roofY + 6) for (xx in x until x + 9) {
                val lit = if (xx - x < 2) 0.10f else if (xx - x > 6) -0.14f else 0f
                val brick = if ((y + (xx - x) / 4 * 2) % 4 == 0) -0.14f else 0f
                px(xx, y, tone(natural(168, 96, 80), 0.58f + lit + brick, xx, y))
            }
            for (xx in x - 1..x + 9) { px(xx, roofY - h, tone(stoneWall, 0.72f, xx, 0)); px(xx, roofY - h + 1, tone(stoneWall, 0.34f, xx, 1)) }
        }

        // -- openings and fittings

        fun window(cx: Int, top: Int, lit: Boolean, shutter: PaletteRamp?, blue: Boolean = false, w: Int = 10, h: Int = 12) {
            val x0 = cx - w / 2
            val glass = if (blue) glassBlue else glassLit
            for (y in top until top + h) for (x in x0 until x0 + w) {
                val t = if (lit) 0.86f - (y - top) * 0.035f + (x - x0) * 0.004f else 0.40f
                px(x, y, tone(glass, t, x, y))
            }
            val frame = timber
            for (x in x0 - 1..x0 + w) { px(x, top - 1, tone(frame, 0.62f, x, 0)); px(x, top + h, tone(frame, 0.5f, x, 1)); px(x, top + h + 1, tone(frame, 0.30f, x, 2)) }
            for (y in top - 1..top + h) { px(x0 - 1, y, tone(frame, 0.62f, 0, y)); px(x0 + w, y, tone(frame, 0.34f, 1, y)) }
            for (y in top until top + h) px(cx, y, tone(frame, 0.5f, cx, y))
            for (x in x0 until x0 + w) px(x, top + h / 2, tone(frame, 0.5f, x, top))
            if (shutter != null) for (sgn in listOf(-1, 1)) {
                val sx = if (sgn < 0) x0 - 5 else x0 + w + 1
                for (y in top - 1..top + h) for (x in sx until sx + 4) {
                    val slat = if ((y - top) % 3 == 0) -0.14f else 0f
                    px(x, y, tone(shutter, 0.58f + slat - (if (sgn > 0) 0.10f else 0f), x, y))
                }
            }
        }

        fun flowerBox(cx: Int, y: Int, w: Int = 12) {
            val x0 = cx - w / 2
            for (x in x0 until x0 + w) { px(x, y, tone(timber, 0.5f, x, y)); px(x, y + 1, tone(timber, 0.32f, x, y + 1)) }
            val cols = listOf(PaletteRamp.rgba(220, 70, 80), PaletteRamp.rgba(246, 210, 90), PaletteRamp.rgba(240, 240, 240))
            for (x in x0 until x0 + w) {
                px(x, y - 1, tone(natural(70, 130, 70), 0.6f, x, y))
                if ((x - x0) % 3 == 1) px(x, y - 2, cols[((x - x0) / 3 + seed.toInt()).mod(3)])
            }
        }

        fun door(cx: Int, bottomY: Int, w: Int = 14, h: Int = 22, double: Boolean = false, arch: Boolean = true) {
            val x0 = cx - w / 2
            val top = bottomY - h
            for (y in top..bottomY) for (x in x0 until x0 + w) {
                val cut = arch && y < top + 4 && (x - x0 < 4 - (y - top) || x0 + w - 1 - x < 4 - (y - top))
                if (cut) continue
                val plank = if ((x - x0) % 4 == 0) -0.12f else 0f
                px(x, y, tone(doorWood, 0.56f + plank - (y - top) * 0.004f, x, y))
            }
            // frame
            for (x in x0 - 1..x0 + w) px(x, top - 1, tone(stoneWall, 0.7f, x, 0))
            for (y in top..bottomY) { px(x0 - 1, y, tone(stoneWall, 0.66f, 0, y)); px(x0 + w, y, tone(stoneWall, 0.34f, 1, y)) }
            for (y in top + 2..bottomY) if (double) px(cx, y, tone(doorWood, 0.28f, cx, y))
            // iron strap and knob
            for (x in x0 + 1 until x0 + w - 1) px(x, top + 6, tone(ironRamp, 0.5f, x, 0))
            px(cx + (if (double) -2 else w / 2 - 3), bottomY - h / 2, tone(natural(240, 200, 90), 0.8f, 0, 0))
            // step
            for (x in x0 - 2..x0 + w + 1) { px(x, bottomY + 1, tone(stoneWall, 0.74f, x, 1)); px(x, bottomY + 2, tone(stoneWall, 0.42f, x, 2)) }
        }

        fun lantern(x: Int, y: Int) {
            for (dy in 0..2) px(x, y - 4 + dy, tone(ironRamp, 0.5f, x, y))
            for (dy in 0..3) for (dx in -1..1) px(x + dx, y + dy, tone(natural(255, 214, 110), 0.9f - dy * 0.1f, x, y))
        }

        fun awning(x0: Int, x1: Int, y: Int, a: PaletteRamp, b: PaletteRamp) {
            for (yy in 0 until 9) for (x in x0..x1) {
                val stripe = ((x - x0) / 6) % 2 == 0
                val scallop = yy >= 7 && (x - x0) % 6 in 0..0
                if (scallop) continue
                px(x, y + yy, tone(if (stripe) a else b, 0.7f - yy * 0.03f - (x - x0) * 0.001f, x, y + yy))
            }
            for (x in x0..x1) px(x, y + 9, tone(natural(40, 30, 30), 0.3f, x, y))
        }

        fun banner(cx: Int, top: Int, h: Int, pal: PaletteRamp) {
            for (yy in 0 until h) for (x in cx - 3..cx + 3) {
                if (yy > h - 4 && abs(x - cx) < (yy - (h - 4)) ) continue
                px(x, top + yy, tone(pal, 0.64f - yy * 0.01f + (cx - x) * 0.02f, x, top + yy))
            }
            for (x in cx - 4..cx + 4) px(x, top - 1, tone(timber, 0.5f, x, 0))
            for (yy in 4..9) px(cx, top + yy, tone(natural(246, 214, 96), 0.84f, cx, yy))
            for (xx in cx - 1..cx + 1) px(xx, top + 7, tone(natural(246, 214, 96), 0.84f, xx, 7))
        }
    }

    // ---- the buildings ----------------------------------------------------------------------------------------------

    private fun cottage(c: Canvas, w: Int, h: Int, roofPal: PaletteRamp): Int {
        val wallTop = h - 32 + 0
        val x0 = 6
        val x1 = w - 7
        c.plasterWall(x0, wallTop, x1, h - 6, frame = true, storeys = 1)
        c.foundation(x0, x1, h - 2)
        val cx = w / 2
        c.door(cx, h - 7, 14, 22)
        for (dx in listOf(-38, 38)) {
            c.window(cx + dx, wallTop + 9, lit = true, shutter = natural(88, 140, 96))
            c.flowerBox(cx + dx, wallTop + 24)
        }
        c.roof(cx, 12, wallTop + 4, (w * 0.17f).toInt(), w / 2 - 2, roofPal)
        c.chimney(cx + 30, 22, 16)
        c.lantern(cx + 14, wallTop + 14)
        return cx
    }

    private fun inn(c: Canvas, w: Int, h: Int, roofPal: PaletteRamp): Int {
        val wallTop = h - 64
        val x0 = 6
        val x1 = w - 7
        c.plasterWall(x0, wallTop, x1, h - 6, frame = true, storeys = 2)
        c.foundation(x0, x1, h - 2)
        val cx = w / 2
        c.door(cx, h - 7, 16, 24, double = true)
        for (dx in listOf(-56, -30, 30, 56)) c.window(cx + dx, h - 27, lit = true, shutter = natural(150, 70, 60))
        for (dx in listOf(-56, -30, 0, 30, 56)) c.window(cx + dx, wallTop + 14, lit = true, shutter = natural(150, 70, 60))
        c.flowerBox(cx - 43, h - 13, 24)
        c.roof(cx, 12, wallTop + 4, (w * 0.20f).toInt(), w / 2 - 2, roofPal)
        c.chimney(cx - 54, 24, 16)
        c.chimney(cx + 44, 24, 16)
        // hanging sign on a bracket to the right of the door
        val sx = cx + 22
        for (x in sx..sx + 12) c.px(x, h - 40, c.tone(timber, 0.5f, x, 0))
        for (y in h - 40..h - 37) { c.px(sx + 4, y, c.tone(ironRamp, 0.5f, 0, y)); c.px(sx + 10, y, c.tone(ironRamp, 0.5f, 1, y)) }
        for (y in h - 37..h - 26) for (x in sx + 1..sx + 13) c.px(x, y, c.tone(natural(206, 150, 70), 0.66f - (y - h + 37) * 0.012f, x, y))
        for (y in h - 34..h - 29) for (x in sx + 4..sx + 9) c.px(x, y, c.tone(natural(250, 240, 220), 0.86f, x, y)) // a mug
        c.lantern(cx - 16, h - 36)
        return cx
    }

    private fun smithy(c: Canvas, w: Int, h: Int, roofPal: PaletteRamp): Int {
        val wallTop = h - 32
        val x0 = 6
        val x1 = w - 7
        c.stoneBlocks(x0, wallTop, x1, h - 6)
        c.foundation(x0, x1, h - 2)
        c.door(34, h - 7, 14, 22)
        c.window(66, wallTop + 7, lit = true, shutter = null, w = 8, h = 10)
        // open forge on the right: a dark frame around a glowing hearth
        val fx0 = w - 46
        for (y in wallTop + 6..h - 7) for (x in fx0..fx0 + 30) {
            val inner = x in fx0 + 3..fx0 + 27 && y in wallTop + 9..h - 7
            val t = if (inner) 0.92f - (y - wallTop - 9) * 0.02f - abs(x - fx0 - 15) * 0.012f else 0.26f
            c.px(x, y, c.tone(if (inner) natural(250, 150, 52) else ironRamp, t, x, y))
        }
        for (x in fx0 + 6..fx0 + 24) for (y in h - 12..h - 8) c.px(x, y, c.tone(ironRamp, 0.38f, x, y)) // an anvil block
        c.roof(w / 2, 12, wallTop + 4, (w * 0.20f).toInt(), w / 2 - 2, roofPal)
        c.chimney(w - 36, 20, 22)
        c.lantern(54, wallTop + 12)
        return 34
    }

    private fun shop(c: Canvas, w: Int, h: Int, roofPal: PaletteRamp): Int {
        val wallTop = h - 32
        val x0 = 6
        val x1 = w - 7
        c.plasterWall(x0, wallTop, x1, h - 6, frame = true, storeys = 1, base = natural(214, 226, 232, 0.24f))
        c.foundation(x0, x1, h - 2)
        val cx = w / 2
        c.door(cx + 26, h - 7, 14, 22)
        c.window(cx - 24, wallTop + 11, lit = true, shutter = null, w = 22, h = 12)
        c.awning(x0 + 6, x1 - 6, wallTop + 3, natural(206, 70, 64), natural(240, 232, 214))
        for (bx in listOf(x0 + 3, x1 - 9)) for (y in h - 17..h - 7) for (x in bx..bx + 6) {
            val hoop = (y - h) % 4 == 0
            c.px(x, y, c.tone(timber, if (hoop) 0.30f else 0.55f - (x - bx) * 0.03f, x, y))
        }
        c.roof(cx, 12, wallTop + 4, (w * 0.18f).toInt(), w / 2 - 2, roofPal)
        c.chimney(cx - 40, 22, 14)
        return cx + 26
    }

    private fun chapel(c: Canvas, w: Int, h: Int, roofPal: PaletteRamp): Int {
        val wallTop = h - 40
        val x0 = 6
        val x1 = w - 7
        c.stoneBlocks(x0, wallTop, x1, h - 6)
        c.foundation(x0, x1, h - 2)
        val cx = w / 2
        c.door(cx, h - 7, 18, 26, double = true)
        // stained-glass window above the door
        for (y in wallTop + 4..wallTop + 14) for (x in cx - 5..cx + 5) {
            if (y < wallTop + 7 && abs(x - cx) > 3 - (wallTop + 7 - y)) continue
            val pane = ((x - cx + 5) / 4 + (y - wallTop) / 5) % 2 == 0
            c.px(x, y, c.tone(if (pane) glassBlue else natural(214, 90, 80), 0.74f, x, y))
        }
        c.roof(cx, 30, wallTop + 4, 2, w / 2 - 2, roofPal)
        // bell cupola on the apex, with a cross
        for (y in 12..30) for (x in cx - 6..cx + 6) {
            val inRoof = y < 22
            val half = if (inRoof) 1 + (y - 12) / 2 else 6
            if (abs(x - cx) > half) continue
            c.px(x, y, c.tone(if (y in 22..28 && abs(x - cx) < 4) ironRamp else stoneWall, 0.6f - (x - cx) * 0.01f, x, y))
        }
        for (y in 4..12) c.px(cx, y, c.tone(natural(246, 214, 96), 0.84f, cx, y))
        for (x in cx - 2..cx + 2) c.px(x, 7, c.tone(natural(246, 214, 96), 0.84f, x, 7))
        c.lantern(cx - 20, h - 28)
        c.lantern(cx + 20, h - 28)
        return cx
    }

    private fun guardhouse(c: Canvas, w: Int, h: Int): Int {
        val x0 = 10
        val x1 = w - 11
        val top = 26
        c.stoneBlocks(x0, top, x1, h - 6)
        c.foundation(x0, x1, h - 2)
        // battlements: merlons across the top, with a darker gap between
        for (x in x0 - 3..x1 + 3) {
            val merlon = (x - x0 + 3) % 12 < 8
            val yTop = if (merlon) top - 9 else top - 3
            for (y in yTop..top + 2) c.px(x, y, c.tone(stoneWall, if (y == yTop) 0.8f else if (y >= top) 0.34f else 0.62f - (x - x0) * 0.002f, x, y))
        }
        val cx = w / 2
        c.door(cx, h - 7, 14, 22)
        for (y in top + 8..top + 20) for (x in cx - 1..cx + 1) c.px(x, y, c.tone(ironRamp, 0.2f, x, y)) // arrow slit
        for (y in top + 6..top + 22) { c.px(cx - 2, y, c.tone(stoneWall, 0.7f, 0, y)); c.px(cx + 2, y, c.tone(stoneWall, 0.3f, 1, y)) }
        // pennant on a pole
        for (y in 4..top - 8) c.px(cx + 20, y, c.tone(timber, 0.5f, 0, y))
        c.banner(cx + 25, 6, 12, natural(200, 64, 60))
        c.lantern(cx - 20, h - 36)
        return cx
    }

    private fun greatHall(c: Canvas, w: Int, h: Int, roofPal: PaletteRamp): Int {
        val wallTop = h - 64
        val x0 = 6
        val x1 = w - 7
        c.stoneBlocks(x0, h - 36, x1, h - 6)
        c.plasterWall(x0, wallTop, x1, h - 37, frame = true, storeys = 1)
        c.foundation(x0, x1, h - 2)
        val cx = w / 2
        c.door(cx, h - 7, 20, 26, double = true)
        c.banner(cx - 24, h - 40, 24, natural(176, 52, 56))
        c.banner(cx + 24, h - 40, 24, natural(176, 52, 56))
        for (dx in listOf(-70, -50, 50, 70)) c.window(cx + dx, h - 29, lit = true, shutter = null, blue = false, w = 8, h = 12)
        for (dx in listOf(-70, -40, -14, 14, 40, 70)) c.window(cx + dx, wallTop + 14, lit = true, shutter = natural(120, 60, 54))
        c.roof(cx, 12, wallTop + 4, (w * 0.24f).toInt(), w / 2 - 2, roofPal)
        c.chimney(cx - 64, 22, 18)
        c.chimney(cx + 54, 22, 18)
        c.lantern(cx - 14, h - 34)
        c.lantern(cx + 14, h - 34)
        return cx
    }
}
