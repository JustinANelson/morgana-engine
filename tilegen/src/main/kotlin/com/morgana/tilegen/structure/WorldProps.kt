package com.morgana.tilegen.structure

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pixel drawers for the overworld props of the [PropKind] "world" group: signs, monoliths, reeds, wheat, rope bridges,
 * piers, ships, towers, gatehouses, mine carts and dead trees. They are generic scenery for any game; palettes pick the
 * biome (red rock tower, grey keep, mossy obelisk). Each takes the primary palette (stone, cloth or foliage) and the
 * secondary palette (wood), like the other prop drawers, and draws into a transparent canvas lit from the upper left.
 */
internal object WorldProps {

    private val outline = PaletteRamp.rgba(30, 24, 34)
    private val rope = PaletteRamp.rgba(176, 142, 92)
    private val ropeShadow = PaletteRamp.rgba(112, 86, 54)
    private val iron = PaletteRamp.rgba(78, 82, 92)
    private val ironLight = PaletteRamp.rgba(138, 144, 156)

    private fun rect(img: TileImage, x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        for (y in y0..y1) for (x in x0..x1) if (x in 0 until img.width && y in 0 until img.height) img.setPixel(x, y, c)
    }

    private fun box(img: TileImage, x0: Int, y0: Int, x1: Int, y1: Int, pal: PaletteRamp) {
        for (y in y0..y1) for (x in x0..x1) {
            val edge = x == x0 || x == x1 || y == y0 || y == y1
            val t = 0.75f - (x - x0).toFloat() / (x1 - x0).coerceAtLeast(1) * 0.4f - (y - y0).toFloat() / (y1 - y0).coerceAtLeast(1) * 0.1f
            if (x in 0 until img.width && y in 0 until img.height) img.setPixel(x, y, if (edge) pal.shadow else pal.sample(t))
        }
    }

    private fun groundShadow(img: TileImage, cx: Int, cy: Int, rx: Int, ry: Int) {
        val shade = PaletteRamp.rgba(20, 16, 24, 70)
        for (y in cy - ry..cy + ry) for (x in cx - rx..cx + rx) {
            val nx = (x - cx).toFloat() / rx
            val ny = (y - cy).toFloat() / ry
            if (nx * nx + ny * ny <= 1f && x in 0 until img.width && y in 0 until img.height) img.drawPixel(x, y, shade)
        }
    }

    /** 1x2 wooden signpost with two plank boards, one pointing each way, on a single post. */
    fun signpost(img: TileImage, w: Int, h: Int, wood: PaletteRamp) {
        groundShadow(img, w / 2, (h * 0.93f).toInt(), w / 3, h / 24)
        val px = w / 2
        rect(img, px - 2, (h * 0.20f).toInt(), px + 1, (h * 0.93f).toInt(), wood.shadow)
        rect(img, px - 1, (h * 0.20f).toInt() + 1, px, (h * 0.93f).toInt(), wood.sample(0.55f))
        fun board(y0: Int, y1: Int, x0: Int, x1: Int, pointRight: Boolean) {
            val tip = (y1 - y0) / 2
            for (y in y0..y1) {
                val dy = abs(y - (y0 + y1) / 2)
                for (x in x0..x1) {
                    val taper = if (pointRight) (x - (x1 - tip)).coerceAtLeast(0) else ((x0 + tip) - x).coerceAtLeast(0)
                    if (dy > (y1 - y0) / 2 - taper) continue
                    val edge = y == y0 || y == y1 || dy == (y1 - y0) / 2 - taper
                    img.setPixel(x, y, if (edge) wood.shadow else wood.sample(0.80f - (y - y0).toFloat() / (y1 - y0).coerceAtLeast(1) * 0.3f))
                }
            }
            rect(img, if (pointRight) x0 + 3 else x1 - 4, (y0 + y1) / 2 - 1, if (pointRight) x0 + 4 else x1 - 3, (y0 + y1) / 2, wood.shadow)
        }
        board((h * 0.16f).toInt(), (h * 0.34f).toInt(), 3, w - 5, pointRight = true)
        board((h * 0.40f).toInt(), (h * 0.56f).toInt(), 5, w - 3, pointRight = false)
    }

    /** 1x3 tall tapering stone monolith with a glowing rune band and a pointed cap. */
    fun obelisk(img: TileImage, w: Int, h: Int, stone: PaletteRamp, glow: PaletteRamp) {
        groundShadow(img, w / 2, (h * 0.95f).toInt(), (w * 0.42f).toInt(), h / 40)
        val cx = w / 2
        rect(img, cx - (w * 0.40f).toInt(), (h * 0.90f).toInt(), cx + (w * 0.40f).toInt(), (h * 0.95f).toInt(), stone.shadow)
        box(img, cx - (w * 0.36f).toInt(), (h * 0.86f).toInt(), cx + (w * 0.36f).toInt(), (h * 0.91f).toInt(), stone)
        val top = (h * 0.08f).toInt()
        val bottom = (h * 0.86f).toInt()
        for (y in top..bottom) {
            val t = (y - top).toFloat() / (bottom - top)
            val half = if (y < h * 0.16f) ((y - top) * 0.9f).toInt() + 1 else (w * (0.14f + 0.12f * t)).toInt()
            for (x in cx - half..cx + half) {
                val edge = x == cx - half || x == cx + half
                val lit = (x - (cx - half)).toFloat() / (2 * half).coerceAtLeast(1)
                img.setPixel(x, y, if (edge) stone.shadow else stone.sample(0.85f - lit * 0.55f))
            }
        }
        // Rune bands: three glowing dashes down the face.
        for (i in 0..2) {
            val y = (h * (0.28f + i * 0.16f)).toInt()
            rect(img, cx - 2, y, cx + 2, y + 1, glow.highlight)
            rect(img, cx - 1, y - 2, cx, y + 3, glow.sample(0.7f))
            img.drawPixel(cx - 4, y, PaletteRamp.rgba(glow.highlight shr 24 and 0xFF, glow.highlight shr 16 and 0xFF, glow.highlight shr 8 and 0xFF, 90))
            img.drawPixel(cx + 4, y, PaletteRamp.rgba(glow.highlight shr 24 and 0xFF, glow.highlight shr 16 and 0xFF, glow.highlight shr 8 and 0xFF, 90))
        }
    }

    /** 1x1 clump of reeds with brown cattail heads, for swamps and shores. */
    fun reeds(img: TileImage, w: Int, h: Int, leaf: PaletteRamp, wood: PaletteRamp, seed: Long) {
        val rnd = Random(seed)
        groundShadow(img, w / 2, (h * 0.90f).toInt(), (w * 0.38f).toInt(), h / 16)
        val stalks = 7
        for (i in 0 until stalks) {
            val bx = (w * 0.18f).toInt() + i * (w * 0.64f).toInt() / (stalks - 1)
            val top = (h * (0.10f + rnd.nextFloat() * 0.30f)).toInt()
            val lean = rnd.nextInt(-1, 2)
            for (y in top..(h * 0.90f).toInt()) {
                val x = bx + lean * (y - top) / ((h * 0.9f).toInt() - top).coerceAtLeast(1) * -1
                img.setPixel(x, y, if (i % 2 == 0) leaf.sample(0.45f) else leaf.sample(0.75f))
                img.setPixel(x + 1, y, leaf.shadow)
            }
            if (i % 3 == 1) {
                val x = bx
                rect(img, x - 1, top - 1, x + 1, top + 4, wood.sample(0.55f))
                rect(img, x, top - 2, x, top - 2, wood.shadow)
            }
        }
    }

    /** 1x1 bound sheaf of golden wheat (use a golden foliage palette). */
    fun wheatSheaf(img: TileImage, w: Int, h: Int, grain: PaletteRamp, wood: PaletteRamp) {
        groundShadow(img, w / 2, (h * 0.90f).toInt(), (w * 0.34f).toInt(), h / 16)
        val cx = w / 2
        val base = (h * 0.88f).toInt()
        for (i in -4..4) {
            val topY = (h * 0.12f).toInt() + abs(i) * 2
            val topX = cx + i * 3
            val steps = base - topY
            for (s in 0..steps) {
                val x = cx + (topX - cx) * (steps - s) / steps.coerceAtLeast(1)
                val y = base - s
                img.setPixel(x, y, if (i % 2 == 0) grain.sample(0.55f) else grain.sample(0.40f))
            }
            // The ear: a small grain head at the top of each stalk.
            rect(img, topX - 1, topY - 2, topX, topY + 3, grain.highlight)
            img.setPixel(topX + 1, topY, grain.sample(0.85f))
            img.setPixel(topX - 2, topY + 1, grain.sample(0.7f))
        }
        rect(img, cx - 6, (h * 0.56f).toInt(), cx + 6, (h * 0.56f).toInt() + 2, wood.sample(0.6f))
        rect(img, cx - 6, (h * 0.56f).toInt() + 2, cx + 6, (h * 0.56f).toInt() + 2, wood.shadow)
    }

    /** 3x1 rope bridge seen from above: planks between two sagging rope rails, with posts at both ends. */
    fun ropeBridge(img: TileImage, w: Int, h: Int, wood: PaletteRamp) {
        val top = (h * 0.20f).toInt()
        val bottom = (h * 0.80f).toInt()
        var x = 0
        var n = 0
        while (x < w - 5) {
            val shade = if (n % 2 == 0) 0.62f else 0.50f
            rect(img, x + 1, top + 2, x + 4, bottom - 2, wood.sample(shade))
            rect(img, x + 1, top + 2, x + 1, bottom - 2, wood.sample(0.82f))
            rect(img, x + 4, top + 2, x + 4, bottom - 2, wood.shadow)
            x += 6
            n++
        }
        for (xx in 0 until w) {
            val sag = (sin01(xx.toFloat() / w) * 2f).toInt()
            img.setPixel(xx, top - 1 + sag, rope)
            img.setPixel(xx, top + sag, ropeShadow)
            img.setPixel(xx, bottom + sag, rope)
            img.setPixel(xx, bottom + 1 + sag, ropeShadow)
        }
        for (px in listOf(2, w - 4)) {
            rect(img, px - 1, top - 4, px + 2, top + 1, wood.shadow)
            rect(img, px, top - 3, px + 1, top, wood.sample(0.7f))
            rect(img, px - 1, bottom - 1, px + 2, bottom + 4, wood.shadow)
            rect(img, px, bottom, px + 1, bottom + 3, wood.sample(0.7f))
        }
    }

    private fun sin01(t: Float): Float = kotlin.math.sin(t * Math.PI.toFloat())

    /** 3x1 wooden pier: a plank deck with post tops along the open edge, for quays and docks. */
    fun pier(img: TileImage, w: Int, h: Int, wood: PaletteRamp) {
        val top = (h * 0.12f).toInt()
        val bottom = (h * 0.78f).toInt()
        rect(img, 0, top, w - 1, bottom, wood.sample(0.55f))
        var x = 0
        while (x < w) {
            rect(img, x, top, x, bottom, wood.shadow)
            for (y in top until bottom step 5) img.setPixel(x + 3, y + 2, wood.sample(0.30f))
            x += 8
        }
        rect(img, 0, top, w - 1, top, wood.sample(0.85f))
        rect(img, 0, bottom, w - 1, bottom, wood.shadow)
        rect(img, 0, bottom + 1, w - 1, bottom + 4, PaletteRamp.rgba(20, 16, 24, 80))
        for (px in 2 until w step 24) {
            rect(img, px, bottom - 3, px + 3, bottom + 2, wood.shadow)
            rect(img, px + 1, bottom - 3, px + 2, bottom, wood.sample(0.7f))
        }
    }

    /** 4x3 sailing ship in side view: curved hull, deck rail, mast, one square sail and a pennant. */
    fun sailingShip(img: TileImage, w: Int, h: Int, sail: PaletteRamp, wood: PaletteRamp) {
        val water = PaletteRamp.rgba(24, 40, 70, 90)
        for (x in (w * 0.08f).toInt()..(w * 0.92f).toInt()) img.drawPixel(x, (h * 0.88f).toInt(), water)
        val deckY = (h * 0.62f).toInt()
        val keelY = (h * 0.86f).toInt()
        val x0 = (w * 0.10f).toInt()
        val x1 = (w * 0.90f).toInt()
        for (y in deckY..keelY) {
            val t = (y - deckY).toFloat() / (keelY - deckY)
            val inset = (t * t * (w * 0.14f)).toInt()
            for (x in x0 + inset..x1 - inset) {
                val bow = if (x > x1 - 14 && y < deckY + 4) 1 else 0
                val edge = y == deckY || y == keelY || x == x0 + inset || x == x1 - inset
                val plank = (y - deckY) % 5 == 4
                img.setPixel(x, y - bow * 0, if (edge) wood.shadow else if (plank) wood.sample(0.35f) else wood.sample(0.62f - t * 0.2f))
            }
        }
        rect(img, x0 - 4, deckY - 8, x0 + 8, deckY, wood.sample(0.55f))
        rect(img, x0 - 4, deckY - 8, x0 + 8, deckY - 8, wood.shadow)
        rect(img, x1 - 12, deckY - 4, x1 + 5, deckY, wood.sample(0.55f))
        rect(img, x1 - 12, deckY - 4, x1 + 5, deckY - 4, wood.shadow)
        for (x in x0 + 6..x1 - 14 step 6) rect(img, x, deckY - 5, x, deckY, wood.shadow)
        rect(img, x0 + 6, deckY - 5, x1 - 14, deckY - 5, wood.sample(0.8f))
        val mastX = w / 2
        rect(img, mastX - 1, (h * 0.08f).toInt(), mastX + 1, deckY, wood.shadow)
        rect(img, mastX, (h * 0.08f).toInt(), mastX, deckY, wood.sample(0.7f))
        val sy0 = (h * 0.16f).toInt()
        val sy1 = (h * 0.52f).toInt()
        rect(img, mastX - (w * 0.28f).toInt(), sy0 - 2, mastX + (w * 0.28f).toInt(), sy0 - 1, wood.shadow)
        for (y in sy0..sy1) {
            val belly = (sqrt(1f - ((y - (sy0 + sy1) / 2f) / ((sy1 - sy0) / 2f)).let { it * it }.coerceAtMost(1f)) * 3f).toInt()
            for (x in mastX - (w * 0.26f).toInt() - belly..mastX + (w * 0.26f).toInt() + belly) {
                val edge = x == mastX - (w * 0.26f).toInt() - belly || x == mastX + (w * 0.26f).toInt() + belly || y == sy0 || y == sy1
                val lit = (x - (mastX - (w * 0.26f))).toFloat() / (w * 0.52f)
                img.setPixel(x, y, if (edge) sail.shadow else sail.sample(0.90f - lit * 0.45f))
            }
        }
        for (i in 1..2) rect(img, mastX - (w * 0.26f).toInt(), sy0 + i * (sy1 - sy0) / 3, mastX + (w * 0.26f).toInt(), sy0 + i * (sy1 - sy0) / 3, sail.sample(0.45f))
        rect(img, mastX + 1, (h * 0.05f).toInt(), mastX + 12, (h * 0.05f).toInt() + 3, PaletteRamp.rgba(196, 52, 48))
    }

    /** 2x3 square stone watchtower with a crenellated top, an arrow slit, a door and brick courses. */
    fun watchTower(img: TileImage, w: Int, h: Int, stone: PaletteRamp, wood: PaletteRamp) {
        groundShadow(img, w / 2, (h * 0.96f).toInt(), (w * 0.40f).toInt(), h / 40)
        val x0 = (w * 0.16f).toInt()
        val x1 = (w * 0.84f).toInt()
        val top = (h * 0.14f).toInt()
        val bottom = (h * 0.95f).toInt()
        stoneWall(img, x0, top, x1, bottom, stone)
        // Overhanging crenellated parapet.
        rect(img, x0 - 2, top - 8, x1 + 2, top, stone.shadow)
        rect(img, x0 - 1, top - 7, x1 + 1, top - 1, stone.sample(0.62f))
        var mx = x0 - 2
        while (mx < x1 + 2) {
            rect(img, mx, top - 14, mx + 5, top - 8, stone.shadow)
            rect(img, mx + 1, top - 13, mx + 4, top - 9, stone.sample(0.72f))
            mx += 10
        }
        // Arrow slits and a door.
        for (sy in listOf(0.34f, 0.54f)) rect(img, w / 2 - 1, (h * sy).toInt(), w / 2, (h * sy).toInt() + 7, outline)
        val dw = (w * 0.14f).toInt()
        rect(img, w / 2 - dw, (h * 0.78f).toInt(), w / 2 + dw, bottom, wood.shadow)
        rect(img, w / 2 - dw + 1, (h * 0.78f).toInt() + 1, w / 2 + dw - 1, bottom, wood.sample(0.5f))
        rect(img, w / 2, (h * 0.78f).toInt() + 1, w / 2, bottom, wood.shadow)
    }

    /** 4x3 gatehouse in front elevation: two crenellated towers, an arched gate with a portcullis, and a banner. */
    fun gatehouse(img: TileImage, w: Int, h: Int, stone: PaletteRamp, wood: PaletteRamp, banner: PaletteRamp) {
        groundShadow(img, w / 2, (h * 0.97f).toInt(), (w * 0.46f).toInt(), h / 40)
        val towerW = (w * 0.26f).toInt()
        val top = (h * 0.22f).toInt()
        val bottom = (h * 0.96f).toInt()
        // Wall between the towers is a little lower than the towers.
        stoneWall(img, towerW - 2, (h * 0.40f).toInt(), w - towerW + 2, bottom, stone)
        for (x in listOf(0, w - towerW - 1)) {
            stoneWall(img, x + 1, top, x + towerW, bottom, stone)
            rect(img, x, top - 8, x + towerW + 1, top, stone.shadow)
            rect(img, x + 1, top - 7, x + towerW, top - 1, stone.sample(0.62f))
            var mx = x
            while (mx < x + towerW) {
                rect(img, mx, top - 15, mx + 5, top - 8, stone.shadow)
                rect(img, mx + 1, top - 14, mx + 4, top - 9, stone.sample(0.72f))
                mx += 10
            }
            rect(img, x + towerW / 2 - 1, (h * 0.40f).toInt(), x + towerW / 2, (h * 0.40f).toInt() + 8, outline)
        }
        // Battlement along the lower wall.
        var bx = towerW - 2
        while (bx < w - towerW + 2) {
            rect(img, bx, (h * 0.40f).toInt() - 7, bx + 5, (h * 0.40f).toInt(), stone.shadow)
            rect(img, bx + 1, (h * 0.40f).toInt() - 6, bx + 4, (h * 0.40f).toInt() - 1, stone.sample(0.70f))
            bx += 10
        }
        // Arched gateway with a portcullis.
        val gw = (w * 0.17f).toInt()
        val cx = w / 2
        val archTop = (h * 0.56f).toInt()
        for (y in archTop..bottom) {
            val half = if (y < archTop + gw) sqrt((gw * gw - (archTop + gw - y) * (archTop + gw - y)).toFloat().coerceAtLeast(0f)).toInt() else gw
            for (x in cx - half..cx + half) img.setPixel(x, y, outline)
        }
        for (x in cx - gw + 2..cx + gw - 2 step 5) rect(img, x, archTop + 6, x + 1, bottom - 6, iron)
        for (y in archTop + 8..bottom - 8 step 8) rect(img, cx - gw + 2, y, cx + gw - 2, y, ironLight)
        rect(img, cx - gw - 2, archTop - 2, cx + gw + 2, archTop, wood.shadow)
        // Banner hanging above the arch.
        rect(img, cx - 6, (h * 0.42f).toInt(), cx + 6, (h * 0.52f).toInt(), banner.sample(0.5f))
        for (i in 0..6) rect(img, cx - 6 + i, (h * 0.52f).toInt() + i / 2, cx - 6 + i, (h * 0.52f).toInt() + 2 + i / 2, banner.sample(0.4f))
        rect(img, cx - 6, (h * 0.42f).toInt(), cx + 6, (h * 0.42f).toInt(), banner.shadow)
    }

    private fun stoneWall(img: TileImage, x0: Int, y0: Int, x1: Int, y1: Int, stone: PaletteRamp) {
        for (y in y0..y1) {
            val row = (y - y0) / 8
            for (x in x0..x1) {
                val edge = x == x0 || x == x1
                val offset = if (row % 2 == 0) 0 else 8
                val mortar = (y - y0) % 8 == 7 || (x - x0 + offset) % 16 == 15
                val lit = (x - x0).toFloat() / (x1 - x0).coerceAtLeast(1)
                img.setPixel(x, y, when {
                    edge -> stone.shadow
                    mortar -> stone.sample(0.28f)
                    else -> stone.sample(0.78f - lit * 0.38f + (((x * 7 + y * 13) % 5) - 2) * 0.02f)
                })
            }
        }
    }

    /** 1x1 ore cart: wooden box on iron wheels, iron bands and a heap of ore on top (use stone for the ore). */
    fun mineCart(img: TileImage, w: Int, h: Int, ore: PaletteRamp, wood: PaletteRamp) {
        groundShadow(img, w / 2, (h * 0.88f).toInt(), (w * 0.40f).toInt(), h / 16)
        val x0 = (w * 0.14f).toInt()
        val x1 = (w * 0.86f).toInt()
        for (x in x0 + 3..x1 - 3) {
            val hump = (sqrt(1f - ((x - w / 2f) / ((x1 - x0) / 2f)).let { it * it }.coerceAtMost(1f)) * h * 0.14f).toInt()
            for (y in (h * 0.36f).toInt() - hump..(h * 0.42f).toInt()) img.setPixel(x, y, ore.sample(0.35f + ((x + y) % 4) * 0.12f))
        }
        for (y in (h * 0.42f).toInt()..(h * 0.72f).toInt()) {
            val inset = ((y - (h * 0.42f)) / (h * 0.30f) * 3f).toInt()
            for (x in x0 + inset..x1 - inset) {
                val edge = x == x0 + inset || x == x1 - inset || y == (h * 0.42f).toInt() || y == (h * 0.72f).toInt()
                img.setPixel(x, y, if (edge) wood.shadow else wood.sample(0.70f - (x - x0).toFloat() / (x1 - x0) * 0.35f))
            }
        }
        rect(img, x0, (h * 0.50f).toInt(), x1, (h * 0.52f).toInt(), iron)
        rect(img, x0, (h * 0.62f).toInt(), x1, (h * 0.64f).toInt(), iron)
        for (wx in listOf((w * 0.32f).toInt(), (w * 0.68f).toInt())) {
            for (dy in -3..3) for (dx in -3..3) if (dx * dx + dy * dy <= 10) img.setPixel(wx + dx, (h * 0.80f).toInt() + dy, if (dx * dx + dy * dy <= 3) ironLight else iron)
        }
    }

    /** 2x2 leafless dead tree: a gnarled trunk with forked bare branches and a few hanging twigs. */
    fun deadTree(img: TileImage, w: Int, h: Int, wood: PaletteRamp, seed: Long) {
        val rnd = Random(seed)
        groundShadow(img, w / 2, (h * 0.93f).toInt(), (w * 0.30f).toInt(), h / 20)
        val cx = w / 2
        val base = (h * 0.92f).toInt()
        val fork = (h * 0.50f).toInt()
        for (y in fork..base) {
            val half = 3 + (y - fork) * 3 / (base - fork).coerceAtLeast(1)
            for (x in cx - half..cx + half) {
                val edge = x == cx - half || x == cx + half
                img.setPixel(x, y, if (edge) wood.shadow else wood.sample(0.72f - (x - (cx - half)).toFloat() / (2 * half) * 0.45f))
            }
        }
        fun branch(x: Int, y: Int, dx: Float, dy: Float, len: Int, thick: Int, depth: Int) {
            var cxf = x.toFloat(); var cyf = y.toFloat()
            for (i in 0 until len) {
                cxf += dx + (rnd.nextFloat() - 0.5f) * 0.5f
                cyf += dy
                val t = (thick - i * thick / len.coerceAtLeast(1)).coerceAtLeast(1)
                rect(img, cxf.toInt() - t / 2, cyf.toInt(), cxf.toInt() + t / 2, cyf.toInt() + 1, if (i % 3 == 0) wood.shadow else wood.sample(0.55f))
            }
            if (depth > 0) {
                branch(cxf.toInt(), cyf.toInt(), dx - 0.7f, dy - 0.1f, len * 2 / 3, thick - 1, depth - 1)
                branch(cxf.toInt(), cyf.toInt(), dx + 0.7f, dy - 0.1f, len * 2 / 3, thick - 1, depth - 1)
            }
        }
        branch(cx - 2, fork + 2, -0.6f, -0.9f, (h * 0.20f).toInt(), 4, 2)
        branch(cx + 2, fork + 2, 0.6f, -0.9f, (h * 0.20f).toInt(), 4, 2)
        branch(cx, fork, 0.0f, -1.0f, (h * 0.22f).toInt(), 3, 2)
    }
}
