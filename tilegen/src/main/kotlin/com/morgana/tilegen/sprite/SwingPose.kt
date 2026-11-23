package com.morgana.tilegen.sprite

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Hand and blade placement for a melee swing.
 *
 * The doll used to draw its sword as a short fixed line and "swing" it as a 5 px horizontal jab on one frame, with the
 * arms never moving. A swing is now four keyframes (wind-up, cut, follow-through, recovery) that give the weapon hand
 * a position and the blade an angle. The arm in the torso layer reaches that hand, the weapon layer draws a long blade
 * from it, and a fading arc shows where the blade has been, the way the reference sheets read a swing.
 *
 * Angles are in degrees from straight up, positive toward the side the character holds the weapon (the facing side in
 * profile). Hand offsets are relative to the doll's body centre and torso top.
 */
internal data class Swing(
    /** Sideways hand offset toward the weapon side, front and back views and profile views. */
    val dx: Int,
    val dxProfile: Int,
    val dy: Int,
    val angle: Float,
    val trailFrom: Float? = null,
    val trailTo: Float? = null
) {
    companion object {
        /** Weapon carried at the side, blade tilted a little outward. */
        val REST = Swing(dx = 8, dxProfile = 4, dy = 5, angle = 16f)

        /** The four SLASH keyframes. */
        fun slash(phase: Int): Swing = when (phase) {
            0 -> Swing(dx = 8, dxProfile = 3, dy = -1, angle = -10f)
            1 -> Swing(dx = 8, dxProfile = 4, dy = 3, angle = 58f, trailFrom = -10f, trailTo = 58f)
            2 -> Swing(dx = 6, dxProfile = 5, dy = 7, angle = 132f, trailFrom = 58f, trailTo = 132f)
            else -> Swing(dx = 7, dxProfile = 4, dy = 6, angle = 74f)
        }

        /** The keyframe for [action] at [phase], or [REST]. Only SLASH has its own keys so far. */
        fun forAction(action: PaperDollAction, phase: Int): Swing =
            if (action == PaperDollAction.SLASH) slash(phase) else REST

        /** The side of the screen (+1 right, -1 left) the weapon hand is on for [dir]. */
        fun side(dir: Direction8): Int = when (dir) {
            Direction8.W, Direction8.SW, Direction8.NW -> -1
            else -> 1
        }
    }

    /** Hand position in cell pixels. */
    fun hand(dir: Direction8, cx: Int, torsoTop: Int): Pair<Int, Int> {
        val d = if (dir == Direction8.W) dxProfile else dx
        return (cx + side(dir) * d) to (torsoTop + dy)
    }

    /** Horizontal squash for a blade pointing sideways: a front or back view sees it foreshortened. */
    fun xScale(dir: Direction8): Float = when (dir) {
        Direction8.W -> 1.0f
        Direction8.SW, Direction8.NW -> 0.8f
        else -> 0.55f
    }

    /** The point [r] pixels from ([hx], [hy]) along [deg]. */
    fun along(dir: Direction8, hx: Int, hy: Int, deg: Float, r: Float): Pair<Float, Float> {
        val a = Math.toRadians(deg.toDouble())
        return (hx + 0.5f + side(dir) * xScale(dir) * r * sin(a).toFloat()) to (hy + 0.5f - r * cos(a).toFloat())
    }
}

internal object SwingArt {
    /**
     * Draws a sword-like weapon held at the hand of [swing]: grip, crossguard, then a [length] px two-pixel blade at the
     * swing's angle. [trail] (optional, drawn first so the blade sits on it) receives the fading arc for a cut.
     */
    fun drawBlade(
        img: TileImage,
        trail: TileImage?,
        dir: Direction8,
        swing: Swing,
        hx: Int,
        hy: Int,
        length: Float,
        steel: PaletteRamp,
        gold: PaletteRamp,
        wood: PaletteRamp,
        broad: Boolean = false,
        curved: Boolean = false
    ) {
        val side = Swing.side(dir)
        fun px(p: Pair<Float, Float>): Pair<Int, Int> = p.first.toInt().coerceIn(1, img.width - 2) to p.second.toInt().coerceIn(1, img.height - 2)

        if (trail != null && swing.trailFrom != null && swing.trailTo != null) drawTrail(trail, dir, swing, hx, hy, length, steel)

        // Grip behind the hand, guard across it.
        val (gx, gy) = px(swing.along(dir, hx, hy, swing.angle + 180f, 2.2f))
        val (px0, py0) = px(swing.along(dir, hx, hy, swing.angle, 1.2f))
        PixelForm.line(img, gx, gy, px0, py0) { t -> wood.sample(0.62f - 0.3f * (1f - t)) }
        img.setPixel(gx, gy, gold.sample(0.7f))
        val perp = swing.angle + 90f
        val (g1x, g1y) = px(swing.along(dir, px0, py0, perp, 1.6f))
        val (g2x, g2y) = px(swing.along(dir, px0, py0, perp + 180f, 1.6f))
        PixelForm.line(img, g1x, g1y, g2x, g2y) { t -> gold.sample(0.88f - 0.4f * t) }

        // Blade: a slight curve for a katana or sabre bends the last third toward the cutting direction.
        val (bx, by) = px(swing.along(dir, px0, py0, swing.angle, 1.5f))
        val bendDeg = if (curved) 9f else 0f
        val mid = px(swing.along(dir, px0, py0, swing.angle, length * 0.6f))
        val tip = px(swing.along(dir, px0, py0, swing.angle + bendDeg, length))
        if (curved) {
            PixelForm.blade(img, bx, by, mid.first, mid.second, steel)
            PixelForm.blade(img, mid.first, mid.second, tip.first, tip.second, steel)
        } else {
            PixelForm.blade(img, bx, by, tip.first, tip.second, steel)
        }
        if (broad) {
            // A darker back edge along the blade for the heavy single-edged sword.
            val ox = if (kotlin.math.abs(tip.second - by) >= kotlin.math.abs(tip.first - bx)) side else 0
            val oy = if (ox == 0) 1 else 0
            PixelForm.line(img, bx + ox, by + oy, tip.first + ox, tip.second + oy + 1) { steel.sample(0.3f) }
        }
        img.setPixel(tip.first, tip.second, steel.highlight)
    }

    /**
     * A bow held vertically in the swing hand for a shot. [phase] 0 raises it with the string slack, 1 aims and draws the
     * string to the chest with an arrow nocked, 2 releases (string slack again, the arrow in flight, the bow recoiling) and
     * 3 lowers it. The limb bulges away from the archer.
     */
    fun drawBow(
        img: TileImage,
        dir: Direction8,
        phase: Int,
        hx: Int,
        hy: Int,
        cx: Int,
        torsoTop: Int,
        wood: PaletteRamp,
        steel: PaletteRamp,
        gold: PaletteRamp,
        half: Int
    ) {
        val side = Swing.side(dir)
        val profile = dir == Direction8.W
        val bulge = if (profile) 3 else 2
        val tipX = hx
        val topY = hy - half
        val botY = hy + half
        fun limbX(y: Int): Int {
            val d = (y - hy).toFloat() / half
            return hx + side * (bulge * (1f - d * d)).roundToInt()
        }
        // Limbs: a lit outer edge and a darker inner one, thicker in the middle.
        for (y in topY..botY) {
            val x = limbX(y)
            val grip = kotlin.math.abs(y - hy) <= 1
            img.setPixel(x.coerceIn(1, img.width - 2), y.coerceIn(1, img.height - 2), if (grip) wood.sample(0.3f) else wood.sample(if (y < hy) 0.78f else 0.6f))
            if (!grip && kotlin.math.abs(x - tipX) >= 1) img.setPixel((x - side).coerceIn(1, img.width - 2), y.coerceIn(1, img.height - 2), wood.sample(0.4f))
        }
        img.setPixel(tipX.coerceIn(1, img.width - 2), topY.coerceIn(1, img.height - 2), gold.sample(0.85f))
        img.setPixel(tipX.coerceIn(1, img.width - 2), botY.coerceIn(1, img.height - 2), gold.sample(0.6f))
        // String: straight when slack, pulled to the chest while drawn.
        val pullX = if (profile) cx - side * (-1) else cx + side * 2
        val pullY = hy
        val stringCol = steel.sample(0.82f)
        if (phase == 1) {
            PixelForm.line(img, tipX, topY, pullX, pullY) { stringCol }
            PixelForm.line(img, pullX, pullY, tipX, botY) { stringCol }
            // Arrow from the string to past the bow.
            val reach = if (profile) 11 else 5
            val ax0 = pullX
            val ax1 = (hx + side * reach).coerceIn(1, img.width - 2)
            PixelForm.line(img, ax0, pullY, ax1, pullY) { t -> if (t > 0.9f) steel.highlight else wood.sample(0.7f) }
            img.setPixel(ax0, pullY - 1, steel.sample(0.9f))
            img.setPixel(ax0, pullY + 1, steel.sample(0.9f))
        } else {
            PixelForm.line(img, tipX - side, topY, tipX - side, botY) { stringCol }
        }
        if (phase == 2) {
            // Arrow in flight, with a short streak behind it.
            val len = if (profile) 7 else 3
            val ax = (hx + side * (if (profile) 5 else 3)).coerceIn(1, img.width - 2)
            val tip = (ax + side * len).coerceIn(1, img.width - 2)
            PixelForm.line(img, ax, hy, tip, hy) { t -> if (t > 0.85f) steel.highlight else wood.sample(0.72f) }
            for (k in 1..3) img.drawPixel((ax - side * k).coerceIn(1, img.width - 2), hy, (steel.sample(0.7f) and 0xFFFFFF00.toInt()) or (150 - k * 40))
        }
    }

    /** A two-band crescent that fades toward its start, centred on the hand, over the angles the blade has covered. */
    private fun drawTrail(trail: TileImage, dir: Direction8, swing: Swing, hx: Int, hy: Int, length: Float, steel: PaletteRamp) {
        val from = swing.trailFrom!!
        val to = swing.trailTo!!
        val steps = max(6, (kotlin.math.abs(to - from) / 4f).roundToInt())
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val deg = from + (to - from) * t
            val alpha = (70 + 170 * t).toInt().coerceIn(0, 240)
            val bright = steel.sample(0.55f + 0.4f * t)
            for ((k, r) in listOf(length + 0.5f, length - 1.3f, length - 3.2f).withIndex()) {
                val (x, y) = swing.along(dir, hx, hy, deg, r)
                val a = (alpha * (if (k == 0) 1f else if (k == 1) 0.75f else 0.4f)).toInt()
                trail.drawPixel(x.toInt().coerceIn(0, trail.width - 1), y.toInt().coerceIn(0, trail.height - 1), (bright and 0xFFFFFF00.toInt()) or a)
            }
        }
    }

    /**
     * An arm from the shoulder to [hand]: a two-pixel sleeve in [sleeve] that is lit on its upper edge, an accent cuff and
     * a skin hand drawn on [handsImg].
     */
    fun drawArm(
        img: TileImage,
        handsImg: TileImage,
        shoulderX: Int,
        shoulderY: Int,
        handX: Int,
        handY: Int,
        sleeve: PaletteRamp,
        cuff: PaletteRamp,
        skin: PaletteRamp,
        tone: Float
    ) {
        val steep = kotlin.math.abs(handY - shoulderY) >= kotlin.math.abs(handX - shoulderX)
        val ox = if (steep) 1 else 0
        val oy = if (steep) 0 else 1
        val total = max(kotlin.math.abs(handX - shoulderX), kotlin.math.abs(handY - shoulderY)).coerceAtLeast(1)
        PixelForm.line(img, shoulderX, shoulderY, handX, handY) { t -> if (t > 0.78f) cuff.sample(tone) else sleeve.sample(tone + 0.14f) }
        PixelForm.line(img, shoulderX + ox, shoulderY + oy, handX + ox, handY + oy) { t -> if (t > 0.78f) cuff.sample(tone - 0.15f) else sleeve.sample(tone - 0.1f) }
        // Shoulder cap so the arm joins the torso.
        img.setPixel(shoulderX, shoulderY - 1, sleeve.sample(tone + 0.18f))
        if (total > 1) img.setPixel(shoulderX + ox, shoulderY - oy, sleeve.sample(tone))
        // Hand: a 2 x 2 fist on the body layer.
        for (dy in 0..1) for (dx in 0..1) {
            handsImg.setPixel((handX + dx - 0).coerceIn(1, handsImg.width - 2), (handY + dy - 0).coerceIn(1, handsImg.height - 2), skin.sample(if (dx == 0 && dy == 0) 0.74f else 0.5f))
        }
    }
}
