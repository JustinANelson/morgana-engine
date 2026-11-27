package com.morgana.tilegen.sprite

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Palette for a procedural 64x64 LPC-style mount paperdoll layer.
 */
data class MountPalette(
    val coat: PaletteRamp,
    val mane: PaletteRamp,
    val saddle: PaletteRamp,
    val blanket: PaletteRamp,
    val metal: PaletteRamp,
    val hoof: PaletteRamp
) {
    companion object {
        val BAY_HORSE = MountPalette(
            coat = PaletteRamp.fromBaseColor(PaletteRamp.rgba(148, 86, 48), steps = 6),
            mane = PaletteRamp.fromBaseColor(PaletteRamp.rgba(44, 30, 22), steps = 6),
            saddle = PaletteRamp.fromBaseColor(PaletteRamp.rgba(96, 56, 30), steps = 6),
            blanket = PaletteRamp.fromBaseColor(PaletteRamp.rgba(154, 46, 44), steps = 6),
            metal = PaletteRamp.fromBaseColor(PaletteRamp.rgba(182, 190, 202), steps = 6),
            hoof = PaletteRamp.fromBaseColor(PaletteRamp.rgba(52, 46, 44), steps = 6)
        )
    }
}

/**
 * Native 64x64 LPC-style horse mount paperdoll renderer.
 *
 * Renders the horse underneath a seated rider (`PaperDollAction.RIDE`), aligned with the rider's
 * saddle seat (`y = 35..36`) and stirrups (`y = 44..49`). Frame 0 is a calm standing stance (also
 * used for mounted idle and non-ride fallback), while frames 0..3 form a smooth 4-beat gallop cycle.
 */
object LpcMountRenderer {
    private const val W = 64
    private const val H = 64
    private const val CX = 32
    private const val FOOT_Y = 60

    fun renderHorseFrame(
        palette: MountPalette,
        dir: Direction8,
        frameIndex: Int,
        action: PaperDollAction
    ): TileImage {
        val beat = if (action == PaperDollAction.RIDE) frameIndex.mod(4) else 0
        if (dir == Direction8.E) {
            return mirrorHorizontal(renderHorseFrame(palette, Direction8.W, beat, PaperDollAction.RIDE))
        }
        val out = TileImage(W, H)
        drawShadow(out, dir)
        val raw = TileImage(W, H)
        when (dir) {
            Direction8.S -> drawHorseFront(raw, palette, beat)
            Direction8.N -> drawHorseBack(raw, palette, beat)
            else -> drawHorseSide(raw, palette, beat)
        }
        applyOutline(raw, out, outlineColorFor(palette.coat))
        return out
    }

    private fun drawShadow(img: TileImage, dir: Direction8) {
        val shadowColor = rgba(14, 12, 20, 96)
        val cy = FOOT_Y + 1
        val rx = if (dir == Direction8.S || dir == Direction8.N) 11 else 18
        val ry = 4
        for (y in (cy - ry)..(cy + ry)) {
            for (x in (CX - rx)..(CX + rx)) {
                val dx = (x - CX) / rx.toFloat()
                val dy = (y - cy) / ry.toFloat()
                if (dx * dx + dy * dy <= 1f) {
                    img.setPixel(x, y, shadowColor)
                }
            }
        }
    }

    private fun drawHorseSide(img: TileImage, p: MountPalette, beat: Int) {
        val bob = if (beat == 1 || beat == 3) -1 else 0
        // 4-beat gallop leg strides (negative = forward/left, positive = back/right)
        val flStride = when (beat) { 0 -> 0f; 1 -> -0.95f; 2 -> 0.2f; else -> 0.8f }
        val frStride = when (beat) { 0 -> 0f; 1 -> 0.65f; 2 -> -0.25f; else -> -0.9f }
        val hlStride = when (beat) { 0 -> 0f; 1 -> 0.9f; 2 -> -0.2f; else -> -0.75f }
        val hrStride = when (beat) { 0 -> 0f; 1 -> -0.65f; 2 -> 0.25f; else -> 0.85f }

        // Far legs (darker)
        drawSideHorseLeg(img, CX - 9, 44 + bob, frStride, p.coat, p.hoof, darkBias = 0.24f)
        drawSideHorseLeg(img, CX + 9, 44 + bob, hrStride, p.coat, p.hoof, darkBias = 0.24f)

        // Flowing tail (billows higher on beats 1 & 3)
        val tailLift = if (beat == 1 || beat == 3) -2 else 0
        for (i in 0..8) {
            val tx = CX + 13 + i
            val ty = 37 + bob + (i * i) / 14 + (tailLift * i) / 8
            fillShadedBox(img, tx, ty, 2, 3, p.mane)
        }

        // Barrel (chest + belly + hindquarters)
        fillShadedEllipse(img, CX - 8, 41 + bob, 6, 5, p.coat)
        fillShadedEllipse(img, CX + 8, 40 + bob, 6, 6, p.coat)
        fillShadedBox(img, CX - 9, 36 + bob, 18, 10, p.coat)

        // Arched neck (facing LEFT)
        for (ry in 0 until 14) {
            val frac = ry / 13f
            val nx = (CX - 17 + (frac * 5f)).roundToInt()
            val w = 5 + (frac * 3f).roundToInt()
            fillShadedBox(img, nx, 24 + bob + ry, w, 2, p.coat)
        }
        // Mane along crest of neck
        for (ry in 0 until 12) {
            val mx = CX - 13 + (ry * 4) / 12
            val wave = if ((beat == 1 || beat == 3) && ry % 3 == 0) 1 else 0
            fillShadedBox(img, mx + wave, 22 + bob + ry, 3, 2, p.mane)
        }

        // Sculpted horse head & muzzle (facing LEFT)
        fillShadedEllipse(img, CX - 19, 25 + bob, 5, 4, p.coat)
        fillShadedBox(img, CX - 26, 25 + bob, 7, 4, p.coat, darkBias = 0.06f)
        // Soft pale muzzle nose & nostril
        img.setPixel(CX - 26, 27 + bob, p.coat.shadow)
        img.setPixel(CX - 25, 26 + bob, p.coat.highlight)
        // Ears
        fillShadedBox(img, CX - 17, 19 + bob, 2, 4, p.coat)
        img.setPixel(CX - 15, 20 + bob, p.coat.shadow)
        // Eye
        img.setPixel(CX - 21, 24 + bob, rgba(24, 18, 16, 255))
        img.setPixel(CX - 20, 24 + bob, rgba(240, 244, 252, 255))

        // Bridle & reins
        fillShadedBox(img, CX - 24, 25 + bob, 1, 4, p.saddle)
        fillShadedBox(img, CX - 24, 27 + bob, 7, 1, p.saddle)
        img.setPixel(CX - 24, 28 + bob, p.metal.highlight)
        // Rein line back toward rider's hands
        for (i in 0..13) {
            val rx = CX - 23 + i
            val ry = 28 + bob + (i * 5) / 13
            img.setPixel(rx, ry, p.saddle.shadow)
        }

        // Saddle blanket + leather saddle + stirrup
        fillShadedBox(img, CX - 6, 35 + bob, 12, 6, p.blanket)
        fillShadedBox(img, CX - 5, 34 + bob, 10, 4, p.saddle)
        fillShadedBox(img, CX - 5, 33 + bob, 2, 2, p.saddle, darkBias = -0.1f) // pommel
        fillShadedBox(img, CX + 3, 33 + bob, 2, 2, p.saddle, darkBias = -0.1f) // cantle
        // Stirrup leather & iron stirrup
        fillShadedBox(img, CX - 3, 38 + bob, 1, 7, p.saddle)
        fillShadedBox(img, CX - 4, 45 + bob, 3, 2, p.metal)

        // Near legs (brighter)
        drawSideHorseLeg(img, CX - 10, 44 + bob, flStride, p.coat, p.hoof, darkBias = 0f)
        drawSideHorseLeg(img, CX + 8, 44 + bob, hlStride, p.coat, p.hoof, darkBias = 0f)
    }

    private fun drawSideHorseLeg(
        img: TileImage,
        hipX: Int,
        hipY: Int,
        stride: Float,
        coat: PaletteRamp,
        hoof: PaletteRamp,
        darkBias: Float
    ) {
        val totalH = (FOOT_Y - hipY).coerceIn(10, 17)
        val upperH = totalH / 2
        val lowerH = totalH - upperH
        val kneeDx = (stride * 4.5f).roundToInt()
        val hoofDx = (stride * 6.5f).roundToInt()
        val lift = if (abs(stride) > 0.35f) -2 else 0

        for (ry in 0 until upperH) {
            val frac = ry / upperH.coerceAtLeast(1).toFloat()
            val x = hipX + (kneeDx * frac).roundToInt()
            fillShadedBox(img, x - 1, hipY + ry, 3, 1, coat, darkBias)
        }
        val effLower = (lowerH + lift).coerceAtLeast(4)
        for (ry in 0 until effLower) {
            val frac = ry / effLower.coerceAtLeast(1).toFloat()
            val x = hipX + kneeDx + ((hoofDx - kneeDx) * frac).roundToInt()
            val ramp = if (ry >= effLower - 2) hoof else coat
            fillShadedBox(img, x - 1, hipY + upperH + ry, 2, 1, ramp, darkBias)
        }
    }

    private fun drawHorseFront(img: TileImage, p: MountPalette, beat: Int) {
        val bob = if (beat == 1 || beat == 3) -1 else 0
        val liftL = if (beat == 1) 3 else 0
        val liftR = if (beat == 3) 3 else 0

        // Hind legs peeking slightly wider behind chest
        fillShadedBox(img, CX - 9, 47 + bob, 3, (FOOT_Y - (47 + bob) - liftR).coerceAtLeast(6), p.coat, darkBias = 0.25f)
        fillShadedBox(img, CX + 6, 47 + bob, 3, (FOOT_Y - (47 + bob) - liftL).coerceAtLeast(6), p.coat, darkBias = 0.25f)

        // Chest & shoulders + saddle blanket flanks
        fillShadedEllipse(img, CX, 43 + bob, 10, 6, p.coat)
        fillShadedBox(img, CX - 9, 38 + bob, 18, 4, p.blanket)
        fillShadedBox(img, CX - 7, 37 + bob, 14, 3, p.saddle)

        // Front legs + hooves
        val flH = (FOOT_Y - (47 + bob) - liftL).coerceAtLeast(6)
        val frH = (FOOT_Y - (47 + bob) - liftR).coerceAtLeast(6)
        fillShadedBox(img, CX - 6, 47 + bob, 3, flH - 2, p.coat)
        fillShadedBox(img, CX - 6, 47 + bob + flH - 2, 3, 2, p.hoof)
        fillShadedBox(img, CX + 3, 47 + bob, 3, frH - 2, p.coat)
        fillShadedBox(img, CX + 3, 47 + bob + frH - 2, 3, 2, p.hoof)

        // Horse head & muzzle centered below rider's waist so face, blaze, eyes & bridle read clearly
        fillShadedBox(img, CX - 4, 41 + bob, 8, 7, p.coat)
        fillShadedBox(img, CX - 3, 47 + bob, 6, 6, p.coat, darkBias = 0.04f)
        // White blaze stripe on forehead/nose
        img.setPixel(CX, 43 + bob, rgba(242, 238, 228, 255))
        img.setPixel(CX, 44 + bob, rgba(242, 238, 228, 255))
        img.setPixel(CX, 45 + bob, rgba(242, 238, 228, 255))
        img.setPixel(CX, 46 + bob, rgba(242, 238, 228, 255))
        // Ears
        fillShadedBox(img, CX - 5, 38 + bob, 2, 3, p.coat)
        fillShadedBox(img, CX + 3, 38 + bob, 2, 3, p.coat)
        // Forelock mane
        fillShadedBox(img, CX - 2, 39 + bob, 4, 3, p.mane)
        // Eyes & nostrils
        img.setPixel(CX - 3, 44 + bob, rgba(24, 18, 16, 255))
        img.setPixel(CX + 2, 44 + bob, rgba(24, 18, 16, 255))
        img.setPixel(CX - 2, 51 + bob, p.coat.shadow)
        img.setPixel(CX + 1, 51 + bob, p.coat.shadow)
        // Bridle & bit
        fillShadedBox(img, CX - 3, 49 + bob, 6, 1, p.saddle)
        img.setPixel(CX - 3, 49 + bob, p.metal.highlight)
        img.setPixel(CX + 2, 49 + bob, p.metal.highlight)
    }

    private fun drawHorseBack(img: TileImage, p: MountPalette, beat: Int) {
        val bob = if (beat == 1 || beat == 3) -1 else 0
        val liftL = if (beat == 1) 3 else 0
        val liftR = if (beat == 3) 3 else 0
        val tailSway = when (beat) { 1 -> -1; 3 -> 1; else -> 0 }

        // Horse ears peeking beside rider's shoulders
        fillShadedBox(img, CX - 8, 26 + bob, 2, 4, p.coat, darkBias = 0.12f)
        fillShadedBox(img, CX + 6, 26 + bob, 2, 4, p.coat, darkBias = 0.12f)

        // Hindquarters + saddle blanket
        fillShadedEllipse(img, CX, 43 + bob, 10, 6, p.coat, darkBias = 0.1f)
        fillShadedBox(img, CX - 9, 38 + bob, 18, 4, p.blanket, darkBias = 0.08f)
        fillShadedBox(img, CX - 7, 37 + bob, 14, 3, p.saddle, darkBias = 0.08f)

        // Hind legs + hooves
        val hlH = (FOOT_Y - (47 + bob) - liftL).coerceAtLeast(6)
        val hrH = (FOOT_Y - (47 + bob) - liftR).coerceAtLeast(6)
        fillShadedBox(img, CX - 6, 47 + bob, 3, hlH - 2, p.coat, darkBias = 0.12f)
        fillShadedBox(img, CX - 6, 47 + bob + hlH - 2, 3, 2, p.hoof)
        fillShadedBox(img, CX + 3, 47 + bob, 3, hrH - 2, p.coat, darkBias = 0.12f)
        fillShadedBox(img, CX + 3, 47 + bob + hrH - 2, 3, 2, p.hoof)

        // Flowing dark tail over the croup
        fillShadedBox(img, CX - 2 + tailSway, 41 + bob, 4, 11, p.mane)
        fillShadedBox(img, CX - 1 + tailSway * 2, 51 + bob, 2, 4, p.mane)
    }

    private fun fillShadedBox(img: TileImage, x0: Int, y0: Int, w: Int, h: Int, ramp: PaletteRamp, darkBias: Float = 0f) {
        for (ry in 0 until h) {
            for (rx in 0 until w) {
                val nx = rx / (w - 1).coerceAtLeast(1).toFloat()
                val ny = ry / (h - 1).coerceAtLeast(1).toFloat()
                val t = (0.9f - (nx * 0.45f + ny * 0.45f) - darkBias).coerceIn(0.08f, 0.96f)
                img.setPixel(x0 + rx, y0 + ry, ramp.sample(t))
            }
        }
    }

    private fun fillShadedEllipse(img: TileImage, cx: Int, cy: Int, rx: Int, ry: Int, ramp: PaletteRamp, darkBias: Float = 0f) {
        for (dy in -ry..ry) {
            for (dx in -rx..rx) {
                val u = dx / rx.toFloat().coerceAtLeast(1f)
                val v = dy / ry.toFloat().coerceAtLeast(1f)
                if (u * u + v * v <= 1f) {
                    val nx = (u + 1f) * 0.5f
                    val ny = (v + 1f) * 0.5f
                    val t = (0.92f - (nx * 0.45f + ny * 0.45f) - darkBias).coerceIn(0.08f, 0.96f)
                    img.setPixel(cx + dx, cy + dy, ramp.sample(t))
                }
            }
        }
    }

    private fun applyOutline(src: TileImage, dst: TileImage, outlineRgba: Int) {
        for (y in 0 until H) {
            for (x in 0 until W) {
                val c = src.getPixel(x, y)
                if ((c and 0xFF) != 0) {
                    dst.setPixel(x, y, c)
                } else {
                    val hasNeighbor =
                        ((src.getPixel(x - 1, y) and 0xFF) != 0) ||
                            ((src.getPixel(x + 1, y) and 0xFF) != 0) ||
                            ((src.getPixel(x, y - 1) and 0xFF) != 0) ||
                            ((src.getPixel(x, y + 1) and 0xFF) != 0)
                    if (hasNeighbor) {
                        dst.setPixel(x, y, outlineRgba)
                    }
                }
            }
        }
    }

    private fun outlineColorFor(ramp: PaletteRamp): Int {
        val s = ramp.shadow
        val r = (((s ushr 24) and 0xFF) * 0.35f).roundToInt().coerceIn(14, 70)
        val g = (((s ushr 16) and 0xFF) * 0.32f).roundToInt().coerceIn(12, 65)
        val b = (((s ushr 8) and 0xFF) * 0.38f).roundToInt().coerceIn(18, 80)
        return rgba(r, g, b, 255)
    }

    private fun mirrorHorizontal(src: TileImage): TileImage {
        val dst = TileImage(W, H)
        for (y in 0 until H) for (x in 0 until W) {
            dst.setPixel(W - 1 - x, y, src.getPixel(x, y))
        }
        return dst
    }

    private fun rgba(r: Int, g: Int, b: Int, a: Int): Int =
        ((r and 0xFF) shl 24) or ((g and 0xFF) shl 16) or ((b and 0xFF) shl 8) or (a and 0xFF)
}
