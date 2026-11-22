package com.morgana.tilegen.material

import com.morgana.tilegen.TileImage
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Deterministic pixel-art overlay decals for adding dungeon and terrain variation.
 */
enum class DecalKind {
    CRACKS,
    MOSS,
    BLOOD_SPLATTER,
    RUBBLE,
    BONES,
    RUNE_GLYPH,
    ASH_SCORCH,
    VINES,
    FLOWER_TUFT,
    PEBBLES,
    GRASS_BLADES
}

/**
 * Composites deterministic [DecalKind] overlays onto existing [TileImage] tiles.
 */
object TileDecals {

    fun applyDecal(
        base: TileImage,
        decal: DecalKind,
        seed: Long,
        style: PixelStyle = PixelStyle.STANDARD,
        palette: PaletteRamp? = null
    ): TileImage {
        val out = base.copy()
        val w = out.width
        val h = out.height

        when (decal) {
            DecalKind.CRACKS -> drawCracks(out, w, h, seed)
            DecalKind.MOSS -> drawMoss(out, w, h, seed)
            DecalKind.BLOOD_SPLATTER -> drawBlood(out, w, h, seed)
            DecalKind.RUBBLE -> drawRubble(out, w, h, seed)
            DecalKind.BONES -> drawBones(out, w, h, seed)
            DecalKind.RUNE_GLYPH -> drawRune(out, w, h, seed)
            DecalKind.ASH_SCORCH -> drawAsh(out, w, h, seed)
            DecalKind.VINES -> drawVines(out, w, h, seed)
            DecalKind.FLOWER_TUFT -> drawFlowerTuft(out, w, h, seed)
            DecalKind.PEBBLES -> drawPebbles(out, w, h, seed)
            DecalKind.GRASS_BLADES -> drawGrassBlades(out, w, h, seed)
        }

        return if (style == PixelStyle.RETRO_8BIT && palette != null) {
            PixelDither.applyStyle(out, style, palette)
        } else {
            out
        }
    }

    private fun drawCracks(img: TileImage, w: Int, h: Int, seed: Long) {
        val dark = PaletteRamp.rgba(16, 16, 24)
        var x = (w * (0.25f + 0.5f * TileNoise.hash(1, 2, seed))).toInt()
        var y = (h * 0.2f).toInt()
        val steps = (h * 0.6f).toInt().coerceAtLeast(4)
        for (i in 0 until steps) {
            if (x in 1 until w - 1 && y in 1 until h - 1) {
                img.setPixel(x, y, dark)
                if (i % 3 == 0 && x + 1 < w) img.setPixel(x + 1, y, dark)
            }
            val dir = TileNoise.hash(i, x, seed + 99L)
            x += when {
                dir < 0.33f -> -1
                dir > 0.66f -> 1
                else -> 0
            }
            y += 1
        }
    }

    private fun drawMoss(img: TileImage, w: Int, h: Int, seed: Long) {
        val mossDark = PaletteRamp.rgba(34, 72, 36)
        val mossLight = PaletteRamp.rgba(72, 136, 58)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val n = TileNoise.wrappedFbm(x.toFloat(), y.toFloat(), period = w, octaves = 2, seed = seed + 303L)
                if (n > 0.66f) {
                    img.setPixel(x, y, if (n > 0.74f) mossLight else mossDark)
                }
            }
        }
    }

    private fun drawBlood(img: TileImage, w: Int, h: Int, seed: Long) {
        val bloodDark = PaletteRamp.rgba(96, 12, 16)
        val bloodBright = PaletteRamp.rgba(164, 24, 28)
        val cx = w * (0.3f + 0.4f * TileNoise.hash(3, 7, seed))
        val cy = h * (0.3f + 0.4f * TileNoise.hash(7, 3, seed))
        val r = w * 0.22f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val d = hypot(x + 0.5f - cx, y + 0.5f - cy)
                val jitter = TileNoise.hash(x, y, seed + 511L) * (r * 0.65f)
                if (d < r * 0.5f + jitter * 0.4f) {
                    img.setPixel(x, y, if (d < r * 0.3f) bloodBright else bloodDark)
                }
            }
        }
    }

    private fun drawRubble(img: TileImage, w: Int, h: Int, seed: Long) {
        val stoneDark = PaletteRamp.rgba(38, 40, 48)
        val stoneLight = PaletteRamp.rgba(132, 138, 152)
        val count = (w / 4).coerceAtLeast(3)
        for (i in 0 until count) {
            val rx = (2 + TileNoise.hash(i, 1, seed) * (w - 5)).toInt()
            val ry = (2 + TileNoise.hash(i, 2, seed) * (h - 5)).toInt()
            img.setPixel(rx, ry, stoneLight)
            img.setPixel((rx + 1).coerceAtMost(w - 1), ry, stoneDark)
            img.setPixel(rx, (ry + 1).coerceAtMost(h - 1), stoneDark)
        }
    }

    private fun drawBones(img: TileImage, w: Int, h: Int, seed: Long) {
        val bone = PaletteRamp.rgba(218, 210, 190)
        val shadow = PaletteRamp.rgba(98, 92, 82)
        val bx = (w * (0.25f + 0.35f * TileNoise.hash(5, 8, seed))).toInt()
        val by = (h * (0.35f + 0.30f * TileNoise.hash(8, 5, seed))).toInt()
        val len = (w / 4).coerceAtLeast(3)
        for (i in 0 until len) {
            val px = (bx + i).coerceIn(0, w - 1)
            val py = (by + i / 2).coerceIn(0, h - 1)
            img.setPixel(px, py, bone)
            if (py + 1 < h) img.setPixel(px, py + 1, shadow)
        }
        // Skull dot
        val sx = (bx - 2).coerceIn(1, w - 2)
        val sy = (by - 1).coerceIn(1, h - 2)
        img.setPixel(sx, sy, bone)
        img.setPixel(sx + 1, sy, bone)
        img.setPixel(sx, sy + 1, shadow)
    }

    private fun drawRune(img: TileImage, w: Int, h: Int, seed: Long) {
        val glow = PaletteRamp.rgba(96, 178, 248)
        val cx = w / 2
        val cy = h / 2
        val r = (w * 0.25f).toInt().coerceAtLeast(2)
        for (i in -r..r) {
            if (cx + i in 0 until w) {
                img.setPixel(cx + i, cy, glow)
            }
            if (cy + i in 0 until h) {
                img.setPixel(cx, cy + i, glow)
            }
            if (abs(i) == r / 2 && cx + i in 0 until w && cy - i in 0 until h) {
                img.setPixel(cx + i, cy - i, glow)
            }
        }
    }

    private fun drawAsh(img: TileImage, w: Int, h: Int, seed: Long) {
        val ash = PaletteRamp.rgba(22, 18, 22)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val n = TileNoise.wrappedFbm(x.toFloat(), y.toFloat(), period = w, octaves = 2, seed = seed + 707L)
                if (n > 0.64f) {
                    img.setPixel(x, y, ash)
                }
            }
        }
    }

    private fun drawVines(img: TileImage, w: Int, h: Int, seed: Long) {
        val outline = PaletteRamp.rgba(43, 38, 50)
        val dark = PaletteRamp.rgba(35, 110, 44)
        val mid = PaletteRamp.rgba(62, 168, 66)
        val light = PaletteRamp.rgba(126, 224, 88)

        val vinePoints = mutableListOf<Pair<Int, Int>>()
        var vx = (w * (0.25f + 0.5f * TileNoise.hash(2, 9, seed))).toInt()
        for (vy in 1 until h - 1) {
            vinePoints += vx to vy
            if (vy % 4 == 1) {
                val leafDir = if ((vy / 4 + vx) % 2 == 0) 1 else -1
                vinePoints += (vx + leafDir).coerceIn(1, w - 2) to vy
                vinePoints += (vx + leafDir * 2).coerceIn(1, w - 2) to (vy + 1).coerceAtMost(h - 2)
            }
            val drift = TileNoise.hash(vy, vx, seed + 809L)
            vx = (vx + when {
                drift < 0.3f -> -1
                drift > 0.7f -> 1
                else -> 0
            }).coerceIn(2, w - 3)
        }

        // 1px dark outline around vine tendrils
        for ((px, py) in vinePoints) {
            for (dy in -1..1) for (dx in -1..1) {
                val ox = px + dx
                val oy = py + dy
                if (ox in 0 until w && oy in 0 until h) {
                    img.setPixel(ox, oy, outline)
                }
            }
        }
        // Vine fill & highlights
        vinePoints.forEachIndexed { idx, (px, py) ->
            val col = when {
                idx % 5 == 0 -> light
                idx % 2 == 0 -> mid
                else -> dark
            }
            img.setPixel(px, py, col)
        }
    }

    private fun drawFlowerTuft(img: TileImage, w: Int, h: Int, seed: Long) {
        val stem = PaletteRamp.rgba(38, 76, 44)
        val petalColors = intArrayOf(
            PaletteRamp.rgba(238, 212, 92),
            PaletteRamp.rgba(228, 128, 148),
            PaletteRamp.rgba(148, 198, 242)
        )
        val center = PaletteRamp.rgba(248, 240, 210)
        val count = (w / 6).coerceAtLeast(2)
        for (i in 0 until count) {
            val fx = (2 + TileNoise.hash(i, 11, seed) * (w - 5)).toInt()
            val fy = (2 + TileNoise.hash(i, 23, seed) * (h - 5)).toInt()
            val petal = petalColors[(TileNoise.hash(i, 37, seed) * petalColors.size).toInt().coerceIn(0, petalColors.lastIndex)]
            img.setPixel(fx, (fy + 1).coerceAtMost(h - 1), stem)
            img.setPixel(fx, fy, center)
            if (fx - 1 >= 0) img.setPixel(fx - 1, fy, petal)
            if (fx + 1 < w) img.setPixel(fx + 1, fy, petal)
            if (fy - 1 >= 0) img.setPixel(fx, fy - 1, petal)
        }
    }

    private fun drawPebbles(img: TileImage, w: Int, h: Int, seed: Long) {
        val dark = PaletteRamp.rgba(54, 52, 62)
        val mid = PaletteRamp.rgba(116, 118, 128)
        val light = PaletteRamp.rgba(174, 176, 184)
        val count = (w / 4).coerceAtLeast(3)
        for (i in 0 until count) {
            val px = (1 + TileNoise.hash(i, 41, seed) * (w - 4)).toInt()
            val py = (1 + TileNoise.hash(i, 53, seed) * (h - 4)).toInt()
            img.setPixel(px, py, light)
            img.setPixel((px + 1).coerceAtMost(w - 1), py, mid)
            img.setPixel(px, (py + 1).coerceAtMost(h - 1), dark)
            img.setPixel((px + 1).coerceAtMost(w - 1), (py + 1).coerceAtMost(h - 1), dark)
        }
    }

    private fun drawGrassBlades(img: TileImage, w: Int, h: Int, seed: Long) {
        val dark = PaletteRamp.rgba(38, 66, 42)
        val light = PaletteRamp.rgba(128, 174, 96)
        val count = (w / 3).coerceAtLeast(4)
        for (i in 0 until count) {
            val gx = (1 + TileNoise.hash(i, 61, seed) * (w - 3)).toInt()
            val gy = (2 + TileNoise.hash(i, 73, seed) * (h - 4)).toInt()
            img.setPixel(gx, gy, dark)
            img.setPixel(gx, (gy - 1).coerceAtLeast(0), light)
            if (gx + 1 < w) img.setPixel(gx + 1, (gy - 1).coerceAtLeast(0), dark)
        }
    }
}
