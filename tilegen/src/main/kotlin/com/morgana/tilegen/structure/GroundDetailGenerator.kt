package com.morgana.tilegen.structure

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelDither
import com.morgana.tilegen.material.TileNoise
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Ground-detail decals: small, mostly transparent 32 x 32 overlays that break up flat ground the way the grass tufts and
 * flower patches do. They go on a map layer above the ground (`MASK_1`, no blocking attribute) on cells with nothing
 * else on them.
 *
 * The sheet is 4 x 4 tiles in [GroundDetail] order. The `SPILL_*` decals are for paving: dirt spilling over the edge of a
 * cobble tile on the named side, to place on a paved tile next to dirt so the road edge is not a straight cut.
 */
enum class GroundDetail(val label: String) {
    PEBBLES_A("Pebbles"), PEBBLES_B("Pebbles"), PEBBLES_C("Pebbles"), WEEDS("Weeds in a crack"),
    DIRT_PATCH_A("Worn dirt"), DIRT_PATCH_B("Worn dirt"), DIRT_PATCH_C("Worn dirt"), PUDDLE_A("Puddle"),
    PUDDLE_B("Puddle"), RUT_H("Cart rut (across)"), RUT_V("Cart rut (down)"), HAY("Scattered hay"),
    SPILL_N("Dirt over the north edge"), SPILL_E("Dirt over the east edge"), SPILL_S("Dirt over the south edge"), SPILL_W("Dirt over the west edge")
}

class GroundDetailGenerator(val seed: Long = 1337L) {
    private fun natural(r: Int, g: Int, b: Int) =
        PaletteRamp.fromBaseColor(PaletteRamp.rgba(r, g, b), steps = 6, hueShift = 0.05f, valueSpread = 0.30f)

    private val dirt = natural(124, 90, 62)
    private val pebble = natural(156, 150, 142)
    private val grass = natural(80, 138, 84)
    private val straw = natural(216, 188, 112)
    private val water = natural(92, 128, 160)

    fun generate(): List<NamedTile> = GroundDetail.entries.map { NamedTile(it.name.lowercase(), tile(it)) }

    fun sheet(): TileImage {
        val sheet = TileImage(4 * 32, 4 * 32)
        for ((i, t) in generate().withIndex()) sheet.drawImage(t.image, 0, 0, 32, 32, (i % 4) * 32, (i / 4) * 32)
        return sheet
    }

    private fun rnd(x: Int, y: Int, salt: Long) = TileNoise.hash(x, y, seed + salt)
    private fun tone(p: PaletteRamp, t: Float, x: Int, y: Int) = PixelDither.sampleDithered(p, t.coerceIn(0.05f, 0.98f), x, y, 4, 0.12f)

    private fun tile(d: GroundDetail): TileImage {
        val img = TileImage(32, 32)
        val s = d.ordinal * 7919L
        fun put(x: Int, y: Int, c: Int) { if (x in 0..31 && y in 0..31) img.setPixel(x, y, c) }
        when (d) {
            GroundDetail.PEBBLES_A, GroundDetail.PEBBLES_B, GroundDetail.PEBBLES_C -> {
                val n = 4 + d.ordinal
                for (i in 0 until n) {
                    val cx = 4 + (rnd(i, 1, s) * 24).toInt()
                    val cy = 4 + (rnd(i, 2, s) * 24).toInt()
                    val rx = 1.4f + rnd(i, 3, s) * 1.6f
                    val ry = 1.0f + rnd(i, 4, s) * 1.2f
                    for (y in cy - 3..cy + 3) for (x in cx - 4..cx + 4) {
                        val dx = (x + 0.5f - cx) / rx
                        val dy = (y + 0.5f - cy) / ry
                        if (dx * dx + dy * dy <= 1f) put(x, y, tone(pebble, 0.66f - dx * 0.2f - dy * 0.2f + (rnd(i, 5, s) - 0.5f) * 0.2f, x, y))
                        else if ((x - cx) in 0..(rx.toInt() + 2) && y == cy + ry.toInt() + 1 && abs(x + 0.5f - cx - 1f) < rx) put(x, y, PaletteRamp.rgba(20, 16, 12, 90))
                    }
                }
            }
            GroundDetail.WEEDS -> {
                for (i in 0 until 3) {
                    val bx = 6 + i * 9 + (rnd(i, 1, s) * 4).toInt()
                    val by = 14 + (rnd(i, 2, s) * 12).toInt()
                    for (b in 0 until 4) {
                        val lean = b - 1.5f
                        val len = 4 + (rnd(i * 4 + b, 3, s) * 4).toInt()
                        for (k in 0 until len) put(bx + (lean * k * 0.35f).toInt(), by - k, tone(grass, 0.5f + k * 0.07f + (b % 2) * 0.1f, bx, by - k))
                    }
                    put(bx, by + 1, PaletteRamp.rgba(40, 30, 24, 150))
                }
            }
            GroundDetail.DIRT_PATCH_A, GroundDetail.DIRT_PATCH_B, GroundDetail.DIRT_PATCH_C -> {
                val cx = 15.5f + (rnd(1, 1, s) - 0.5f) * 5f
                val cy = 15.5f + (rnd(2, 2, s) - 0.5f) * 5f
                val rx = 11f + rnd(3, 3, s) * 4f
                val ry = 8f + rnd(4, 4, s) * 4f
                for (y in 0 until 32) for (x in 0 until 32) {
                    val dx = (x + 0.5f - cx) / rx
                    val dy = (y + 0.5f - cy) / ry
                    val wob = (rnd(x / 3, y / 3, s + 9) - 0.5f) * 0.55f
                    val dist = hypot(dx, dy) + wob
                    // A dithered edge: pixels thin out toward the rim, like a worn track.
                    val keep = dist < 0.62f || (dist < 1.0f && rnd(x, y, s + 2) < (1.0f - dist) * 2.2f)
                    if (keep) put(x, y, tone(dirt, 0.34f + (rnd(x, y, s + 4) - 0.5f) * 0.16f - dx * 0.04f, x, y))
                }
            }
            GroundDetail.PUDDLE_A, GroundDetail.PUDDLE_B -> {
                val big = d == GroundDetail.PUDDLE_A
                val cx = 16f
                val cy = 17f
                val rx = if (big) 12f else 8.5f
                val ry = if (big) 6.5f else 4.5f
                for (y in 0 until 32) for (x in 0 until 32) {
                    val dx = (x + 0.5f - cx) / rx
                    val dy = (y + 0.5f - cy) / ry
                    val wob = (rnd(x / 2, y / 2, s) - 0.5f) * 0.34f
                    val dd = dx * dx + dy * dy + wob
                    if (dd <= 1f) {
                        val rim = dd > 0.78f
                        put(x, y, if (rim) tone(dirt, 0.22f, x, y) else tone(water, 0.58f - dy * 0.12f - dx * 0.06f, x, y))
                    }
                }
                // a sky glint
                for (i in 0 until (if (big) 5 else 3)) put((cx - rx * 0.5f).toInt() + i, (cy - ry * 0.35f).toInt() + (if (i > 2) 1 else 0), tone(water, 0.96f, i, 0))
            }
            GroundDetail.RUT_H, GroundDetail.RUT_V -> {
                val horizontal = d == GroundDetail.RUT_H
                for (lane in listOf(9, 21)) for (u in 0 until 32) {
                    val wob = ((TileNoise.hash(u / 4, lane, seed + 31L) - 0.5f) * 2f).toInt()
                    if (rnd(u, lane, s) < 0.12f) continue
                    for (v in 0..2) {
                        val a = lane + wob + v
                        val (x, y) = if (horizontal) u to a else a to u
                        put(x, y, tone(dirt, if (v == 1) 0.22f else if (v == 0) 0.34f else 0.46f, x, y))
                    }
                }
            }
            GroundDetail.HAY -> {
                for (i in 0 until 14) {
                    val x = 3 + (rnd(i, 1, s) * 25).toInt()
                    val y = 4 + (rnd(i, 2, s) * 24).toInt()
                    val len = 2 + (rnd(i, 3, s) * 3).toInt()
                    val dir = if (rnd(i, 4, s) < 0.5f) 1 else -1
                    for (k in 0 until len) put(x + k, y + dir * (k / 2), tone(straw, 0.5f + rnd(i, 5, s) * 0.4f, x, y))
                }
            }
            GroundDetail.SPILL_N, GroundDetail.SPILL_E, GroundDetail.SPILL_S, GroundDetail.SPILL_W -> {
                for (u in 0 until 32) {
                    val depth = 3 + (TileNoise.hash(u / 3, d.ordinal, seed + 77L) * 5f).toInt() + (rnd(u, 0, s) * 2f).toInt()
                    for (v in 0 until depth) {
                        // thin out toward the inner edge so the dirt dissolves into the stones
                        if (v >= depth - 2 && rnd(u, v, s + 1) < 0.55f) continue
                        val (x, y) = when (d) {
                            GroundDetail.SPILL_N -> u to v
                            GroundDetail.SPILL_S -> u to 31 - v
                            GroundDetail.SPILL_W -> v to u
                            else -> 31 - v to u
                        }
                        put(x, y, tone(dirt, 0.42f - v * 0.015f + (rnd(x, y, s + 3) - 0.5f) * 0.14f, x, y))
                    }
                }
            }
        }
        return img
    }
}
