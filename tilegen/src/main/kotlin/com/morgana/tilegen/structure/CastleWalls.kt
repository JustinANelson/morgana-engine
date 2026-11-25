package com.morgana.tilegen.structure

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.TileNoise

/**
 * A castle wall set in the `a4Layout` naming (`<name>_void|top|bot_left|center|right`) drawn as a walkable-looking rampart:
 * `void` is light flagstone with a crenellated rim on its open sides, `top` is flagstone running into a row of merlons, and
 * `bot` is the brick face with a plinth. A block of this set (see the map-builder `wall` op) reads as a stone wall you could
 * walk along, in the calm outlined style of the rest of the Citadel kit. Drawn at 32 px and scaled for other tile sizes.
 */
object CastleWalls {
    private fun hex(s: String): Int {
        val v = s.removePrefix("#").toInt(16)
        return PaletteRamp.rgba((v shr 16) and 255, (v shr 8) and 255, v and 255)
    }

    private val stone = intArrayOf(hex("#3a3d48"), hex("#6a7080"), hex("#8e96a6"), hex("#b4bbc8"), hex("#d6dbe4"))
    private val brick = intArrayOf(hex("#34373f"), hex("#585d6a"), hex("#7a808e"), hex("#9aa1b0"), hex("#bcc3d0"))

    private enum class Side { LEFT, CENTER, RIGHT }

    private fun floor(img: TileImage, seed: Long, ox: Int, oy: Int, y0: Int, y1: Int) {
        for (y in y0..y1) for (x in 0 until 32) {
            val fy = (y + oy) / 16
            val off = if (fy % 2 == 0) 0 else 8
            val fx = (x + off + ox) / 16
            val joint = (y + oy) % 16 == 0 || (x + off + ox) % 16 == 0
            val h = TileNoise.hash(fx, fy, seed)
            img.setPixel(x, y, if (joint) stone[2] else if (h > 0.7f) stone[4] else stone[3])
        }
    }

    private fun rim(img: TileImage, side: Side, y0: Int, y1: Int) {
        if (side == Side.CENTER) return
        for (y in y0..y1) {
            val merlon = (y / 8) % 2 == 0
            for (k in 0..6) {
                val x = if (side == Side.LEFT) k else 31 - k
                val outer = k == 0
                val inner = k == 6
                img.setPixel(x, y, when {
                    !merlon -> if (outer) stone[0] else stone[1]
                    outer -> stone[0]
                    inner -> stone[2]
                    k < 3 -> stone[4]
                    else -> stone[3]
                })
            }
            if (merlon && (y % 8 == 0 || y % 8 == 7)) for (k in 0..6) img.setPixel(if (side == Side.LEFT) k else 31 - k, y, stone[0])
        }
    }

    private fun voidTile(side: Side, seed: Long): TileImage {
        val t = TileImage(32, 32)
        floor(t, seed, 0, 0, 0, 31)
        rim(t, side, 0, 31)
        return t
    }

    private fun topTile(side: Side, seed: Long): TileImage {
        val t = TileImage(32, 32)
        floor(t, seed, 0, 0, 0, 15)
        rim(t, side, 0, 15)
        // Merlon row along the front edge.
        for (y in 16..31) for (x in 0 until 32) {
            val m = x % 16
            val isMerlon = m in 1..10
            if (isMerlon) {
                val edge = m == 1 || m == 10 || y == 16 || y == 31
                t.setPixel(x, y, when {
                    edge -> stone[0]
                    y < 21 -> stone[4]
                    m < 5 -> stone[3]
                    else -> stone[2]
                })
            } else {
                t.setPixel(x, y, if (y < 20) stone[1] else stone[2])
            }
        }
        if (side == Side.LEFT) for (y in 0..31) t.setPixel(0, y, stone[0])
        if (side == Side.RIGHT) for (y in 0..31) t.setPixel(31, y, stone[0])
        return t
    }

    private fun botTile(side: Side, seed: Long): TileImage {
        val t = TileImage(32, 32)
        for (y in 0 until 32) for (x in 0 until 32) {
            val course = y / 8
            val off = if (course % 2 == 0) 0 else 8
            val bx = (x + off) / 16
            val joint = y % 8 == 0 || (x + off) % 16 == 0
            val h = TileNoise.hash(bx, course, seed + 5)
            var c = if (joint) brick[1] else if (h > 0.75f) brick[3] else if (x % 16 < 5 && false) brick[4] else brick[2]
            if (y < 3) c = brick[1]                                   // shade under the merlons
            if (y >= 27) c = if (y == 31) brick[0] else brick[1]      // plinth
            t.setPixel(x, y, c)
        }
        // Arrow slit.
        for (y in 9..20) { t.setPixel(15, y, brick[0]); t.setPixel(16, y, brick[0]) }
        if (side == Side.LEFT) for (y in 0..31) t.setPixel(0, y, brick[0])
        if (side == Side.RIGHT) for (y in 0..31) t.setPixel(31, y, brick[0])
        return t
    }

    private fun scale(t: TileImage, size: Int): TileImage {
        if (size == 32) return t
        val out = TileImage(size, size)
        for (y in 0 until size) for (x in 0 until size) out.setPixel(x, y, t.getPixel(x * 32 / size, y * 32 / size))
        return out
    }

    fun generate(name: String, tileSize: Int, seed: Long = 11L): List<NamedTile> {
        val sides = listOf(Side.LEFT to "left", Side.CENTER to "center", Side.RIGHT to "right")
        val out = ArrayList<NamedTile>()
        for ((part, f) in listOf<Pair<String, (Side, Long) -> TileImage>>("void" to ::voidTile, "top" to ::topTile, "bot" to ::botTile)) {
            for ((side, label) in sides) out += NamedTile("${name}_${part}_$label", scale(f(side, seed), tileSize))
        }
        return out
    }
}
