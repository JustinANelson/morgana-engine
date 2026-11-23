package com.morgana.tilegen.sprite

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.SpriteFinish
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A two-wheeled cart hitched behind a [MountSprite] horse, with a load of sacks and a crate.
 *
 * The canvas is [WIDTH] x [HEIGHT]: the horse keeps its 48 x 64 cell and the cart takes the rest. In profile (W, with E a
 * mirror of the whole canvas) the horse is on the left and the cart trails to the right, joined by two shafts and a chest
 * strap; the wheel spokes turn through the walk frames. From the front (S) the cart sits behind the horse and rider with
 * its wheels edge-on at either side; from behind (N) the cart's tail is in front, with the horse and rider above it.
 */
object CartSprite {
    const val WIDTH = 96
    const val HEIGHT = MountSprite.HEIGHT

    private val WOOD = PalettePresets.WARM_TIMBER
    private val SACK = PaletteRamp(intArrayOf(PaletteRamp.rgba(110, 88, 54), PaletteRamp.rgba(152, 124, 80), PaletteRamp.rgba(192, 164, 112), PaletteRamp.rgba(224, 200, 148), PaletteRamp.rgba(244, 226, 180)))
    private val IRON = PaletteRamp(intArrayOf(PaletteRamp.rgba(28, 30, 38), PaletteRamp.rgba(58, 62, 76), PaletteRamp.rgba(96, 102, 118), PaletteRamp.rgba(140, 148, 164)))

    /** A horse, with a rider when [loadout] is given, pulling the cart. */
    fun hitched(
        gen: PaperDollSpriteGenerator?,
        loadout: PaperDollLoadout?,
        dir: Direction8,
        action: PaperDollAction,
        frame: Int,
        coat: HorseCoat = HorseCoat.BAY,
        style: PixelStyle = loadout?.effectiveTheme()?.style ?: PixelStyle.MORGANA
    ): TileImage {
        val base = dir.mirrorFrom ?: dir
        val horse = if (gen != null && loadout != null) {
            MountSprite.mounted(gen, loadout, base, action, frame, coat, style)
        } else {
            MountSprite.horse(base, action, frame, coat, saddle = false)
        }
        val out = TileImage(WIDTH, HEIGHT)
        val horseX = if (base == Direction8.W) 0 else (WIDTH - MountSprite.WIDTH) / 2
        val spin = if (action == PaperDollAction.WALK) frame % 4 else 0
        val bob = if (action == PaperDollAction.WALK && frame % 2 == 1) -1 else 0

        val cart = TileImage(WIDTH, HEIGHT)
        when (base) {
            Direction8.W -> drawSide(cart, spin, bob)
            Direction8.N -> drawBack(cart, bob)
            else -> drawFront(cart, bob)
        }
        val cartFin = SpriteFinish.finish(cart)
        SpriteFinish.softShadow(out, if (base == Direction8.W) 56f else 48f, MountSprite.GROUND_Y - 0.5f, if (base == Direction8.W) 30f else 15f, 3f)
        fun paste(src: TileImage, dx: Int) {
            for (y in 0 until src.height) for (x in 0 until src.width) {
                val p = src.getPixel(x, y)
                if ((p and 0xFF) != 0 && x + dx in 0 until WIDTH) out.drawPixel(x + dx, y, p)
            }
        }
        if (base == Direction8.N) {
            paste(horse, horseX)
            paste(cartFin, 0)
        } else {
            paste(cartFin, 0)
            paste(horse, horseX)
            if (base == Direction8.W) paste(SpriteFinish.finish(harness(bob)), 0)
        }
        if (dir.mirrorFrom != null) {
            val m = TileImage(WIDTH, HEIGHT)
            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) m.setPixel(WIDTH - 1 - x, y, out.getPixel(x, y))
            return m
        }
        return out
    }

    /** The chest strap and traces drawn over the horse's side, so the shafts look attached to it. */
    private fun harness(bob: Int): TileImage {
        val img = TileImage(WIDTH, HEIGHT)
        val leather = PaletteRamp(intArrayOf(PaletteRamp.rgba(40, 24, 18), PaletteRamp.rgba(70, 42, 28), PaletteRamp.rgba(104, 66, 42), PaletteRamp.rgba(140, 96, 62)))
        // Breast collar across the chest, a girth band under the saddle and a trace running back to the shaft.
        PixelForm.line(img, 15, 36 + bob, 17, 47 + bob) { leather.sample(0.7f) }
        PixelForm.line(img, 16, 36 + bob, 18, 47 + bob) { leather.sample(0.4f) }
        PixelForm.line(img, 26, 40 + bob, 26, 48 + bob) { leather.sample(0.55f) }
        PixelForm.line(img, 27, 40 + bob, 27, 48 + bob) { leather.sample(0.32f) }
        PixelForm.line(img, 18, 44 + bob, 40, 46 + bob) { t -> leather.sample(0.62f - 0.2f * t) }
        img.setPixel(17, 44 + bob, IRON.sample(0.9f))
        return img
    }

    private fun wheel(img: TileImage, cx: Int, cy: Int, r: Float, spin: Int, tone: Float) {
        val inner = r - 2.4f
        for (y in (cy - r.toInt() - 1)..(cy + r.toInt() + 1)) for (x in (cx - r.toInt() - 1)..(cx + r.toInt() + 1)) {
            val dx = x + 0.5f - cx
            val dy = y + 0.5f - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d <= r && d >= inner) {
                // The rim is lit on its upper left and shaded on its lower right.
                val lit = (-dx - dy) / (2f * r)
                img.setPixel(x, y, WOOD.sample((tone + 0.3f * lit).coerceIn(0.1f, 0.95f)))
            } else if (d < inner) {
                img.setPixel(x, y, 0)
            }
        }
        // Eight spokes, turning a little each walk frame.
        val a0 = spin * (PI.toFloat() / 16f)
        for (k in 0 until 8) {
            val a = a0 + k * (PI.toFloat() / 4f)
            val x1 = cx + (cos(a) * (inner + 0.4f)).let { if (it >= 0) it + 0.5f else it - 0.5f }
            val y1 = cy + (sin(a) * (inner + 0.4f)).let { if (it >= 0) it + 0.5f else it - 0.5f }
            PixelForm.line(img, cx, cy, x1.toInt(), y1.toInt()) { t -> WOOD.sample(tone * 0.8f + 0.15f * (1f - t)) }
        }
        PixelForm.dome(img, cx + 0.5f, cy + 0.5f, 2.2f, 2.2f, IRON)
    }

    private fun sack(img: TileImage, cx: Float, cy: Float, rx: Float, ry: Float) {
        PixelForm.dome(img, cx, cy, rx, ry, SACK)
        img.setPixel(cx.toInt(), (cy - ry).toInt(), SACK.sample(0.4f))
        img.setPixel(cx.toInt() + 1, (cy - ry).toInt(), SACK.sample(0.25f))
    }

    private fun crate(img: TileImage, x: Int, y: Int, w: Int, h: Int) {
        PixelForm.column(img, x, y, w, h, WOOD, 0.74f, round = false)
        for (i in 0 until w) {
            img.setPixel(x + i, y + h / 2, WOOD.sample(0.3f))
        }
        for (j in 0 until h) img.setPixel(x + w / 2, y + j, WOOD.sample(0.34f))
    }

    // ---------------------------------------------------------------- views

    private fun drawSide(img: TileImage, spin: Int, bob: Int) {
        // Far wheel, a little behind the near one and darker.
        wheel(img, 68, 52, 8f, spin, 0.3f)
        // Shafts from the horse's flank to the cart body, far one first.
        for (i in 0..1) PixelForm.line(img, 28, 43 + bob + i, 52, 47 + i) { t -> WOOD.sample(0.34f - 0.05f * t) }
        // The cart body: a plank box with a lit rim, a darker lower rail and a sloped front board.
        PixelForm.column(img, 50, 36, 30, 11, WOOD, 0.62f, round = false)
        for (x in 50..79) {
            img.setPixel(x, 36, WOOD.sample(0.92f))
            img.setPixel(x, 40, WOOD.sample(0.34f))
            img.setPixel(x, 44, WOOD.sample(0.34f))
            img.setPixel(x, 46, WOOD.sample(0.18f))
        }
        for (y in 36..46) { img.setPixel(50, y, WOOD.sample(0.84f)); img.setPixel(79, y, WOOD.sample(0.2f)) }
        // Iron straps and nails.
        for (x in listOf(56, 66, 75)) for (y in 37..45) img.setPixel(x, y, IRON.sample(if (y % 4 == 0) 0.9f else 0.5f))
        // The load: two sacks and a crate above the rim.
        sack(img, 57f, 34f, 4.2f, 3.2f)
        sack(img, 65f, 33f, 4.6f, 3.8f)
        crate(img, 71, 31, 8, 6)
        // Shafts again over the front board, then the near wheel.
        for (i in 0..1) PixelForm.line(img, 28, 45 + bob + i, 50, 49 + i) { t -> WOOD.sample(0.74f - 0.2f * t) }
        img.setPixel(51, 49, IRON.sample(0.9f)); img.setPixel(51, 50, IRON.sample(0.5f))
        wheel(img, 64, 52, 8f, spin, 0.66f)
        // Axle cap.
        PixelForm.dome(img, 64.5f, 52.5f, 1.4f, 1.4f, IRON)
    }

    private fun drawFront(img: TileImage, bob: Int) {
        // Seen head on, the cart is behind the horse: its box and load above the horse's back and the wheels edge-on.
        PixelForm.column(img, 34, 38, 28, 10, WOOD, 0.58f, round = false)
        for (x in 34..61) { img.setPixel(x, 38, WOOD.sample(0.9f)); img.setPixel(x, 42, WOOD.sample(0.32f)) }
        sack(img, 41f, 36f, 4.2f, 3.4f)
        sack(img, 50f, 35f, 4.8f, 4f)
        crate(img, 54, 32, 7, 6)
        for (side in listOf(31, 62)) {
            PixelForm.column(img, side, 45 + bob, 3, 15, WOOD, 0.5f, round = true)
            for (y in listOf(49, 53, 57)) img.setPixel(side + 1, y + bob, IRON.sample(0.7f))
        }
    }

    private fun drawBack(img: TileImage, bob: Int) {
        // Seen from behind the cart's tail fills the lower half and the load rises above it.
        PixelForm.column(img, 34, 42, 28, 13, WOOD, 0.6f, round = false)
        for (x in 34..61) { img.setPixel(x, 42, WOOD.sample(0.92f)); img.setPixel(x, 46, WOOD.sample(0.34f)); img.setPixel(x, 50, WOOD.sample(0.34f)) }
        for (x in listOf(40, 48, 55)) for (y in 43..53) img.setPixel(x, y, IRON.sample(if (y % 4 == 0) 0.9f else 0.5f))
        sack(img, 41f, 40f, 4.2f, 3.2f)
        sack(img, 49f, 39f, 4.6f, 3.6f)
        crate(img, 54, 36, 7, 6)
        for (side in listOf(31, 62)) {
            PixelForm.column(img, side, 46 + bob, 3, 14, WOOD, 0.48f, round = true)
            for (y in listOf(50, 54, 58)) img.setPixel(side + 1, y + bob, IRON.sample(0.7f))
        }
    }
}
