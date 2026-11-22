package com.morgana.gameart.sprite

import com.morgana.tilegen.*
import com.morgana.tilegen.material.*
import com.morgana.tilegen.spec.*
import com.morgana.tilegen.sprite.*
import com.morgana.tilegen.structure.*
import com.morgana.tilegen.export.*
import com.morgana.tilegen.autotile.*
import com.morgana.gameart.export.*
import com.morgana.gameart.whiteriver.*

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelDither
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Silhouettes for the non-humanoid Trevana Realms monsters, each with a front, a side (facing left; the exporter mirrors it
 * for right) and a back view.
 *
 * Everything is built from a few shaded primitives drawn in painter's order (back to front): [capsule] (a tapered limb or
 * body, shaded like a lit cylinder), [blob] (a shaded ellipse) and [spike] (a triangle). All shading comes from the upper
 * left and is Bayer-dithered like the oak canopy, so a monster reads as a rounded volume. [SpriteFinish] adds the rim light
 * and the soft outline afterwards.
 *
 * Layout of the 32 x 48 cell: feet on y = 44, the ground shadow centred on y = 45.5, x centred on 16.
 */
internal class MonsterArt(private val img: TileImage) {
    enum class View { FRONT, SIDE, BACK }

    /** Animation state of one frame. [walk] is -2, 0 or 2 (the stride), [flap] lifts wings, [bob] moves the whole body. */
    class Pose(val bob: Int, val phase: Int, val walk: Int, val flap: Int, val eye: Int)

    private val dark = PaletteRamp.rgba(24, 20, 34)

    // ---- primitives -------------------------------------------------------------------------------------------------

    private fun put(x: Int, y: Int, c: Int) {
        if (x in 1..30 && y in 1..46) img.setPixel(x, y, c)
    }

    private fun shade(pal: PaletteRamp, dx: Float, dy: Float, d2: Float, bias: Float, x: Int, y: Int): Int {
        val z = sqrt(max(0f, 1f - d2))
        val light = (-dx * 0.42f - dy * 0.52f + z * 0.74f).coerceIn(0f, 1f)
        return PixelDither.sampleDithered(pal, (0.16f + light * 0.80f + bias).coerceIn(0.06f, 0.99f), x, y, 4, 0.16f)
    }

    /** A tapered limb from ([ax], [ay]) radius [ar] to ([bx], [by]) radius [br]. */
    fun capsule(ax: Float, ay: Float, ar: Float, bx: Float, by: Float, br: Float, pal: PaletteRamp, bias: Float = 0f) {
        val minX = (min(ax - ar, bx - br) - 1).toInt()
        val maxX = (max(ax + ar, bx + br) + 1).toInt()
        val minY = (min(ay - ar, by - br) - 1).toInt()
        val maxY = (max(ay + ar, by + br) + 1).toInt()
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val px = x + 0.5f
                val py = y + 0.5f
                val t = if (len2 <= 0f) 0f else (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
                val cx = ax + vx * t
                val cy = ay + vy * t
                val r = ar + (br - ar) * t
                val dx = (px - cx) / r
                val dy = (py - cy) / r
                val d2 = dx * dx + dy * dy
                if (d2 <= 1f) put(x, y, shade(pal, dx, dy, d2, bias, x, y))
            }
        }
    }

    /** A shaded ellipse. */
    fun blob(cx: Float, cy: Float, rx: Float, ry: Float, pal: PaletteRamp, bias: Float = 0f) {
        for (y in (cy - ry).toInt() - 1..(cy + ry).toInt() + 1) {
            for (x in (cx - rx).toInt() - 1..(cx + rx).toInt() + 1) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                val d2 = dx * dx + dy * dy
                if (d2 <= 1f) put(x, y, shade(pal, dx, dy, d2, bias, x, y))
            }
        }
    }

    /** A filled triangle (a horn, a spike, a feather, a wing panel), lighter toward the edge nearest the first vertex. */
    fun spike(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, pal: PaletteRamp, tone: Float = 0.6f) {
        val minX = floorI(min(x0, min(x1, x2)))
        val maxX = ceilI(max(x0, max(x1, x2)))
        val minY = floorI(min(y0, min(y1, y2)))
        val maxY = ceilI(max(y0, max(y1, y2)))
        val den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if (abs(den) < 1e-4f) return
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val px = x + 0.5f
                val py = y + 0.5f
                val a = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / den
                val b = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / den
                val c = 1f - a - b
                if (a >= 0f && b >= 0f && c >= 0f) {
                    // Barycentric weight of the base vertex drives a soft gradient toward the tip.
                    put(x, y, PixelDither.sampleDithered(pal, (tone + (a - 0.33f) * 0.30f).coerceIn(0.06f, 0.99f), x, y, 4, 0.16f))
                }
            }
        }
    }

    private fun floorI(v: Float) = kotlin.math.floor(v).toInt()
    private fun ceilI(v: Float) = kotlin.math.ceil(v).toInt()

    /** Two-pixel eye with a dark brow when [big]. */
    private fun eye(x: Int, y: Int, col: Int, big: Boolean = false) {
        put(x, y, col)
        put(x + 1, y, col)
        if (big) {
            put(x, y + 1, col)
            put(x + 1, y + 1, col)
        }
        put(x, y - 1, dark)
        put(x + 1, y - 1, dark)
    }

    private fun dot(x: Int, y: Int, col: Int) = put(x, y, col)

    // ---- palettes ---------------------------------------------------------------------------------------------------

    private fun natural(r: Int, g: Int, b: Int) =
        PaletteRamp.fromBaseColor(PaletteRamp.rgba(r, g, b), steps = 6, hueShift = 0.05f, valueSpread = 0.30f)

    private val beak = natural(214, 164, 62)
    private val bone = natural(214, 206, 188)
    private val pink = natural(222, 150, 150)
    private val skin = natural(196, 152, 118)
    private val ember = natural(250, 150, 52)
    private val glass = natural(150, 214, 232)
    private val ice = natural(190, 232, 250)
    private val stone = natural(120, 114, 112)
    private val voidRamp = natural(40, 40, 70)
    private val membrane = natural(92, 54, 84)

    // ---- dispatch ---------------------------------------------------------------------------------------------------

    /** Draws [kind]; returns false for the humanoid monsters, which are drawn from the paper-doll generator instead. */
    fun draw(kind: TrevanaEnemyKind, pal: PaletteRamp, view: View, cx: Int, pose: Pose, eyeCol: Int): Boolean {
        when (kind) {
            TrevanaEnemyKind.PHEASANT -> bird(pal, view, cx, pose, eyeCol, tail = 5, crest = true, breast = null, size = 1.1f)
            TrevanaEnemyKind.TURTLEDOVE -> bird(pal, view, cx, pose, eyeCol, tail = 2, crest = false, breast = bone, size = 1.0f)
            TrevanaEnemyKind.ROBIN -> bird(pal, view, cx, pose, eyeCol, tail = 1, crest = false, breast = natural(232, 122, 70), size = 0.9f)
            TrevanaEnemyKind.APPARITION -> ghost(pal, view, cx, pose, eyeCol)
            TrevanaEnemyKind.POLTERGEIST -> imp(pal, view, cx, pose, eyeCol)
            TrevanaEnemyKind.FIRE_SPIRIT -> flame(pal, view, cx, pose)
            TrevanaEnemyKind.WINGED_SNAKE -> snake(pal, view, cx, pose, eyeCol)
            TrevanaEnemyKind.RATLING -> rat(pal, view, cx, pose)
            TrevanaEnemyKind.MACAQUE -> ape(pal, view, cx, pose, eyeCol, 0.78f, Ape.MACAQUE)
            TrevanaEnemyKind.GIANT_APE -> ape(pal, view, cx, pose, eyeCol, 1f, Ape.GIANT)
            TrevanaEnemyKind.THUNDER_APE -> ape(pal, view, cx, pose, eyeCol, 1f, Ape.THUNDER)
            TrevanaEnemyKind.ELF_APE -> ape(pal, view, cx, pose, eyeCol, 0.92f, Ape.ELF)
            TrevanaEnemyKind.SAND_MONSTER -> beetle(pal, view, cx, pose, eyeCol)
            TrevanaEnemyKind.HILL_MONSTER -> golem(pal, view, cx, pose, eyeCol)
            TrevanaEnemyKind.SCORPION -> scorpion(pal, view, cx, pose, eyeCol)
            TrevanaEnemyKind.BLADE_GHOST -> reaper(pal, view, cx, pose)
            TrevanaEnemyKind.BIRDMAN -> harpy(pal, view, cx, pose, eyeCol, raptor = false)
            TrevanaEnemyKind.HAWKING -> harpy(pal, view, cx, pose, eyeCol, raptor = true)
            TrevanaEnemyKind.TERATO_DRAGON -> dragon(pal, view, cx, pose, eyeCol)
            TrevanaEnemyKind.SNOW_BANSHEE -> banshee(pal, view, cx, pose)
            TrevanaEnemyKind.BANDIT, TrevanaEnemyKind.SWORD_MASTER -> return false
        }
        return true
    }

    // ---- birds ------------------------------------------------------------------------------------------------------

    private fun bird(pal: PaletteRamp, v: View, cx: Int, p: Pose, eyeCol: Int, tail: Int, crest: Boolean, breast: PaletteRamp?, size: Float) {
        val x = cx.toFloat()
        val by = 36f + p.bob
        val s = size
        val sw = p.walk / 2f
        when (v) {
            View.FRONT -> {
                capsule(x - 2.2f, by + 5f, 0.9f, x - 2.2f + sw, 44f, 0.8f, beak)
                capsule(x + 2.2f, by + 5f, 0.9f, x + 2.2f - sw, 44f, 0.8f, beak)
                capsule(x - 6f * s, by - 3f, 2.2f, x - 6.5f * s, by + 3f - p.flap, 1.5f, pal, -0.12f)
                capsule(x + 6f * s, by - 3f, 2.2f, x + 6.5f * s, by + 3f - p.flap, 1.5f, pal, -0.18f)
                blob(x, by, 6f * s, 6f * s, pal)
                if (breast != null) blob(x, by + 1.5f, 3.8f * s, 4.2f * s, breast)
                blob(x, by - 7.5f * s, 4.2f * s, 3.9f * s, pal, 0.04f)
                if (crest) {
                    spike(x - 1.5f, by - 10.5f, x + 1.5f, by - 10.5f, x - 2f, by - 15f, pal, 0.8f)
                    spike(x, by - 10.5f, x + 3f, by - 10.5f, x + 3.5f, by - 14f, pal, 0.7f)
                    blob(x, by - 6.2f, 3.2f, 2.2f, natural(206, 60, 52))
                }
                spike(x - 1.6f, by - 6.4f, x + 1.6f, by - 6.4f, x, by - 3.8f, beak, 0.7f)
                dot(cx - 3, (by - 8.6f).toInt(), if (p.eye == 1) eyeCol else dark)
                dot(cx + 2, (by - 8.6f).toInt(), if (p.eye == 1) eyeCol else dark)
            }
            View.SIDE -> {
                if (tail > 0) {
                    capsule(x + 4f, by - 1f, 2.4f, x + 9f + tail, by - 3f - tail / 2f, 1.2f, pal, -0.10f)
                    if (tail > 3) capsule(x + 4f, by, 1.8f, x + 10f + tail, by - 1f, 0.9f, pal, -0.20f)
                }
                capsule(x - 1f, by + 5f, 0.9f, x - 1f + sw, 44f, 0.8f, beak)
                capsule(x + 2.4f, by + 5f, 0.9f, x + 2.4f - sw, 44f, 0.8f, beak)
                blob(x + 1f, by, 7f * s, 5.2f * s, pal)
                if (breast != null) blob(x - 3f, by + 1.5f, 3.2f * s, 3.4f * s, breast)
                capsule(x, by - 2.5f, 2.5f, x + 4.5f, by + 2f - p.flap, 1.5f, pal, -0.14f)
                blob(x - 5.2f * s, by - 5.2f * s, 3.8f * s, 3.6f * s, pal, 0.04f)
                if (crest) spike(x - 6f, by - 8f, x - 3.5f, by - 8f, x - 1f, by - 13f, pal, 0.8f)
                spike(x - 8.2f * s, by - 6.4f, x - 8.2f * s, by - 4.2f, x - 11.4f * s, by - 5.2f, beak, 0.7f)
                dot((x - 6.6f * s).toInt(), (by - 6.4f).toInt(), if (p.eye == 1) eyeCol else dark)
            }
            View.BACK -> {
                if (tail > 0) spike(x - 3f, by + 3f, x + 3f, by + 3f, x, by + 7f + tail, pal, 0.35f)
                capsule(x - 2.2f, by + 5f, 0.9f, x - 2.2f + sw, 44f, 0.8f, beak)
                capsule(x + 2.2f, by + 5f, 0.9f, x + 2.2f - sw, 44f, 0.8f, beak)
                blob(x, by, 6f * s, 6f * s, pal, -0.05f)
                capsule(x - 6f * s, by - 3f, 2.2f, x - 6.5f * s, by + 3f - p.flap, 1.5f, pal, -0.12f)
                capsule(x + 6f * s, by - 3f, 2.2f, x + 6.5f * s, by + 3f - p.flap, 1.5f, pal, -0.18f)
                blob(x, by - 7.5f * s, 4.2f * s, 3.9f * s, pal, 0f)
                if (crest) spike(x - 1.5f, by - 10.5f, x + 1.5f, by - 10.5f, x, by - 15f, pal, 0.8f)
            }
        }
    }

    // ---- floating spirits -------------------------------------------------------------------------------------------

    private fun hem(x: Float, y: Float, half: Int, pal: PaletteRamp, p: Pose, spread: Float = 2f, tone: Float = 0.38f) {
        for (i in -half..half) {
            val tipDrop = 4f + ((p.phase + abs(i)) % 3)
            spike(x + i * spread - 1.2f, y, x + i * spread + 1.2f, y, x + i * spread, y + tipDrop, pal, tone)
        }
    }

    private fun ghost(pal: PaletteRamp, v: View, cx: Int, p: Pose, eyeCol: Int) {
        val x = cx.toFloat()
        val fy = 27f + p.bob
        val armUp = if (p.phase % 2 == 1) -2f else 0f
        hem(x, fy + 10f, 3, pal, p)
        capsule(x - 6.5f, fy + 1f, 2f, x - 11f, fy + 8f + armUp, 1.5f, pal, -0.16f)
        capsule(x + 6.5f, fy + 1f, 2f, x + 11f, fy + 8f + armUp, 1.5f, pal, -0.22f)
        capsule(x, fy - 3f, 6.8f, x, fy + 8f, 6f, pal)
        when (v) {
            View.FRONT -> {
                blob(x - 2.5f, fy - 4f, 1.9f, 2.4f, voidRamp)
                blob(x + 2.5f, fy - 4f, 1.9f, 2.4f, voidRamp)
                dot(cx - 3, (fy - 4f).toInt(), eyeCol)
                dot(cx + 2, (fy - 4f).toInt(), eyeCol)
                blob(x, fy + 1.5f, 1.4f, 2f, voidRamp)
            }
            View.SIDE -> {
                blob(x - 4f, fy - 4f, 1.9f, 2.4f, voidRamp)
                dot(cx - 5, (fy - 4f).toInt(), eyeCol)
                blob(x - 4.5f, fy + 1.5f, 1.2f, 1.8f, voidRamp)
            }
            View.BACK -> Unit
        }
    }

    private fun imp(pal: PaletteRamp, v: View, cx: Int, p: Pose, eyeCol: Int) {
        val x = cx.toFloat()
        val fy = 30f + p.bob
        val lantern = natural(255, 214, 110)
        // lantern on a hook held out to the right (left on the side view's far hand)
        val lx = if (v == View.SIDE) x - 10f else x + 10f
        capsule(lx, fy - 6f, 0.7f, lx, fy - 2f, 0.7f, stone)
        blob(lx, fy + 1.5f, 3.4f, 4.2f, lantern)
        blob(lx, fy + 1.5f, 1.6f, 2.2f, natural(255, 250, 200))
        hem(x, fy + 7f, 2, pal, p, 2.2f)
        capsule(x - 5f, fy, 2f, x - 8f, fy + 6f, 1.5f, pal, -0.16f)
        capsule(x + 5f, fy, 2f, x + 8f, fy + 6f, 1.5f, pal, -0.22f)
        capsule(x, fy, 5.2f, x, fy + 6f, 4.6f, pal)
        blob(x, fy - 6.5f, 5.4f, 4.8f, pal, 0.05f)
        spike(x - 4.5f, fy - 8f, x - 3f, fy - 5f, x - 9f, fy - 11f, pal, 0.75f)
        spike(x + 4.5f, fy - 8f, x + 3f, fy - 5f, x + 9f, fy - 11f, pal, 0.6f)
        if (v != View.BACK) {
            val ex = if (v == View.SIDE) cx - 4 else cx - 3
            eye(ex, (fy - 7f).toInt(), eyeCol, true)
            if (v == View.FRONT) eye(cx + 2, (fy - 7f).toInt(), eyeCol, true)
            blob(if (v == View.SIDE) x - 4f else x, fy - 3.2f, 2.2f, 1.1f, voidRamp)
        }
    }

    private fun flame(pal: PaletteRamp, v: View, cx: Int, p: Pose) {
        val x = cx.toFloat()
        val fy = 29f + p.bob
        val f = if (p.phase % 2 == 0) 1f else -1f
        // flickering tongues, back to front
        spike(x - 6f, fy - 4f, x - 1f, fy - 4f, x - 5f + f, fy - 15f, pal, 0.7f)
        spike(x + 6f, fy - 4f, x + 1f, fy - 4f, x + 5f - f, fy - 14f, pal, 0.62f)
        spike(x - 4f, fy - 6f, x + 4f, fy - 6f, x + f, fy - 18f, pal, 0.8f)
        capsule(x - 6.5f, fy + 2f, 2f, x - 10f, fy + 8f, 1.4f, pal, -0.1f)
        capsule(x + 6.5f, fy + 2f, 2f, x + 10f, fy + 8f, 1.4f, pal, -0.2f)
        blob(x, fy + 2f, 7.4f, 9f, pal)
        spike(x - 6f, fy + 8f, x - 2f, fy + 9f, x - 4f - f, fy + 14f, pal, 0.5f)
        spike(x + 6f, fy + 8f, x + 2f, fy + 9f, x + 4f + f, fy + 14f, pal, 0.45f)
        blob(x, fy + 3.5f, 4f, 5.4f, ember, 0.12f)
        blob(x, fy + 4.5f, 2.2f, 3.2f, natural(255, 240, 180), 0.2f)
        if (v != View.BACK) {
            val ex = if (v == View.SIDE) cx - 4 else cx - 3
            eye(ex, (fy - 1f).toInt(), dark, true)
            if (v == View.FRONT) eye(cx + 2, (fy - 1f).toInt(), dark, true)
        }
    }

    // ---- snake ------------------------------------------------------------------------------------------------------

    private fun snake(pal: PaletteRamp, v: View, cx: Int, p: Pose, eyeCol: Int) {
        val x = cx.toFloat()
        val n = 11
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val rs = FloatArray(n)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            ys[i] = 43f - i * 2.3f + p.bob
            xs[i] = x + sin(i * 0.85f + p.phase * 0.9f) * 4.2f * (1f - t * 0.35f)
            rs[i] = 3.9f - t * 1.2f
        }
        val wingY = ys[6]
        val wx = xs[6]
        val fl = p.flap * 1f
        // wings behind the body
        for ((i, tone) in listOf(0.55f, 0.66f, 0.78f).withIndex()) {
            spike(wx - 2f, wingY, wx - 15f + i, wingY - 11f + i * 5f - fl, wx - 7f, wingY + 4f + i * 2f, bone, tone)
            spike(wx + 2f, wingY, wx + 15f - i, wingY - 11f + i * 5f - fl, wx + 7f, wingY + 4f + i * 2f, bone, tone - 0.12f)
        }
        for (i in 0 until n - 1) capsule(xs[i], ys[i], rs[i], xs[i + 1], ys[i + 1], rs[i + 1], pal, if (i % 2 == 0) 0f else -0.08f)
        val hx = xs[n - 1]
        val hy = ys[n - 1]
        val face = if (v == View.SIDE) -2.5f else 0f
        blob(hx + face, hy - 1f, 4.2f, 3.6f, pal, 0.05f)
        if (v != View.BACK) {
            val sx = if (v == View.SIDE) hx - 6f else hx
            blob(sx, hy + 0.5f, 2.6f, 2f, pal, 0.05f)
            dot(hx.toInt() - 2 + face.toInt(), (hy - 2f).toInt(), if (p.eye == 1) eyeCol else dark)
            if (v == View.FRONT) dot(hx.toInt() + 2, (hy - 2f).toInt(), if (p.eye == 1) eyeCol else dark)
            val red = PaletteRamp.rgba(210, 60, 70)
            dot(sx.toInt(), (hy + 3f).toInt(), red)
            dot(sx.toInt() - 1, (hy + 4f).toInt(), red)
            dot(sx.toInt() + 1, (hy + 4f).toInt(), red)
        }
    }

    // ---- rat --------------------------------------------------------------------------------------------------------

    private fun rat(pal: PaletteRamp, v: View, cx: Int, p: Pose) {
        val x = cx.toFloat()
        val by = 36f + p.bob
        val sw = p.walk / 2f
        val red = PaletteRamp.rgba(230, 70, 70)
        when (v) {
            View.FRONT -> {
                capsule(x + 5f, by + 5f, 1.1f, x + 11f, by + 2f, 0.9f, pink)
                capsule(x + 11f, by + 2f, 0.9f, x + 13f, by - 4f, 0.7f, pink)
                capsule(x - 3f, by + 4f, 2.2f, x - 3f + sw, 44f, 1.8f, pal, -0.08f)
                capsule(x + 3f, by + 4f, 2.2f, x + 3f - sw, 44f, 1.8f, pal, -0.14f)
                blob(x, by, 5.8f, 6.4f, pal)
                blob(x, by + 1.5f, 3.2f, 4f, pal, 0.14f)
                capsule(x - 5f, by - 2f, 1.7f, x - 6f, by + 3f, 1.4f, pal, -0.1f)
                capsule(x + 5f, by - 2f, 1.7f, x + 6f, by + 3f, 1.4f, pal, -0.16f)
                blob(x - 3.8f, by - 11f, 2.5f, 2.7f, pal, -0.05f)
                blob(x + 3.8f, by - 11f, 2.5f, 2.7f, pal, -0.1f)
                blob(x - 3.8f, by - 11f, 1.4f, 1.6f, pink)
                blob(x + 3.8f, by - 11f, 1.4f, 1.6f, pink)
                blob(x, by - 7.5f, 4.6f, 4f, pal, 0.05f)
                blob(x, by - 5.5f, 2.2f, 1.8f, pink)
                dot(cx - 3, (by - 8.5f).toInt(), red)
                dot(cx + 2, (by - 8.5f).toInt(), red)
                dot(cx, (by - 4.2f).toInt(), PaletteRamp.rgba(250, 244, 230))
            }
            View.SIDE -> {
                capsule(x + 7f, by + 3f, 1.1f, x + 13f, by, 0.9f, pink)
                capsule(x + 13f, by, 0.9f, x + 14f, by - 6f, 0.7f, pink)
                capsule(x + 4f, by + 3f, 2.2f, x + 4f - sw, 44f, 1.8f, pal, -0.14f)
                capsule(x - 2f, by + 3f, 2.2f, x - 2f + sw, 44f, 1.8f, pal, -0.08f)
                blob(x + 2f, by + 0.5f, 7.2f, 5.4f, pal)
                capsule(x - 4f, by, 1.5f, x - 6f, by + 4f, 1.3f, pal, -0.1f)
                blob(x - 5f, by - 5.5f, 3.9f, 3.6f, pal, 0.05f)
                capsule(x - 7.5f, by - 5f, 1.8f, x - 11f, by - 4.2f, 0.9f, pal, 0.05f)
                dot(cx - 12, (by - 4f).toInt(), pink.colors[0])
                blob(x - 3.2f, by - 10f, 2.3f, 2.6f, pal, -0.05f)
                blob(x - 3.2f, by - 10f, 1.2f, 1.5f, pink)
                dot(cx - 7, (by - 6.5f).toInt(), red)
            }
            View.BACK -> {
                capsule(x, by + 5f, 1.1f, x + 2f, by + 8f, 0.9f, pink)
                capsule(x - 3f, by + 4f, 2.2f, x - 3f + sw, 44f, 1.8f, pal, -0.08f)
                capsule(x + 3f, by + 4f, 2.2f, x + 3f - sw, 44f, 1.8f, pal, -0.14f)
                blob(x, by, 5.8f, 6.4f, pal, -0.05f)
                blob(x - 3.8f, by - 11f, 2.5f, 2.7f, pal, -0.05f)
                blob(x + 3.8f, by - 11f, 2.5f, 2.7f, pal, -0.1f)
                blob(x, by - 7.5f, 4.6f, 4f, pal)
            }
        }
    }

    // ---- apes -------------------------------------------------------------------------------------------------------

    enum class Ape { MACAQUE, GIANT, THUNDER, ELF }

    private fun ape(pal: PaletteRamp, v: View, cx: Int, p: Pose, eyeCol: Int, s: Float, kind: Ape) {
        val x = cx.toFloat()
        val top = 44f - 33f * s // top of the head region scales with the animal
        val swing = p.walk * 0.9f
        val belly = pal
        val mane = when (kind) {
            Ape.THUNDER -> natural(250, 232, 120)
            Ape.ELF -> natural(70, 130, 84)
            else -> pal
        }
        when (v) {
            View.FRONT -> {
                if (kind == Ape.MACAQUE) {
                    capsule(x + 6f, 40f, 1.1f, x + 12f, 38f, 1f, pal, -0.12f)
                    capsule(x + 12f, 38f, 1f, x + 13f, 31f, 0.9f, pal, -0.12f)
                }
                capsule(x - 4f * s, 38f, 3.2f * s, x - 4.4f * s + swing / 2f, 44f, 3.4f * s, pal, -0.1f)
                capsule(x + 4f * s, 38f, 3.2f * s, x + 4.4f * s - swing / 2f, 44f, 3.4f * s, pal, -0.16f)
                capsule(x - 9.2f * s, top + 11f, 3.6f * s, x - 11f * s, 40f - swing, 4f * s, pal, -0.08f)
                capsule(x + 9.2f * s, top + 11f, 3.6f * s, x + 11f * s, 40f + swing, 4f * s, pal, -0.16f)
                blob(x, 32f * s + (1f - s) * 14f, 8.6f * s, 9f * s, pal)
                blob(x, 34f * s + (1f - s) * 14f, 5f * s, 5.6f * s, belly, 0.16f)
                blob(x, top + 9f, 10.4f * s, 5.4f * s, pal, 0.04f)
                // head sunk between the shoulders
                if (kind == Ape.THUNDER) {
                    for (i in -2..2) spike(x + i * 1.8f - 1.2f, top + 3f, x + i * 1.8f + 1.2f, top + 3f, x + i * 2.4f, top - 5f + abs(i), mane, 0.85f)
                }
                if (kind == Ape.ELF) {
                    spike(x - 4f * s, top + 3f, x - 3f * s, top + 6f, x - 10f * s, top - 2f, pal, 0.75f)
                    spike(x + 4f * s, top + 3f, x + 3f * s, top + 6f, x + 10f * s, top - 2f, pal, 0.6f)
                    for (i in -2..2) spike(x + i * 2f - 1.5f, top + 2f, x + i * 2f + 1.5f, top + 2f, x + i * 2f, top - 4f, mane, 0.7f)
                }
                blob(x, top + 3f, 5.4f * s, 4.8f * s, pal, 0.05f)
                blob(x, top + 4.4f, 3.4f * s, 2.8f * s, skin)
                eye(cx - 3, (top + 3f).toInt(), if (p.eye == 1) eyeCol else dark)
                eye(cx + 2, (top + 3f).toInt(), if (p.eye == 1) eyeCol else dark)
                dot(cx, (top + 6f).toInt(), dark)
            }
            View.SIDE -> {
                if (kind == Ape.MACAQUE) {
                    capsule(x + 6f, 38f, 1.1f, x + 12f, 36f, 1f, pal, -0.12f)
                    capsule(x + 12f, 36f, 1f, x + 13f, 30f, 0.9f, pal, -0.12f)
                }
                capsule(x + 3f * s, 38f, 3.2f * s, x + 3f * s - swing / 2f, 44f, 3.4f * s, pal, -0.16f)
                capsule(x - 3f * s, 38f, 3.2f * s, x - 3f * s + swing / 2f, 44f, 3.4f * s, pal, -0.08f)
                capsule(x + 2f * s, top + 11f, 3.4f * s, x - 7f * s - swing, 40f, 3.8f * s, pal, -0.16f)
                blob(x + 1.5f * s, 32f * s + (1f - s) * 14f, 8f * s, 9f * s, pal)
                blob(x - 1f * s, top + 9.5f, 8f * s, 5f * s, pal, 0.04f)
                if (kind == Ape.THUNDER) for (i in 0..3) spike(x - 3f + i * 1.8f, top + 2f, x - 1.5f + i * 1.8f, top + 3f, x - 2f + i * 2.4f, top - 5f + i, mane, 0.85f)
                if (kind == Ape.ELF) {
                    spike(x - 1f, top + 3f, x, top + 6f, x + 7f * s, top - 1f, pal, 0.65f)
                    for (i in 0..2) spike(x - 3f + i * 2f, top + 2f, x + i * 2f, top + 2f, x - 1f + i * 2f, top - 4f, mane, 0.7f)
                }
                blob(x - 6f * s, top + 4f, 5f * s, 4.6f * s, pal, 0.05f)
                blob(x - 8.5f * s, top + 5.2f, 2.6f * s, 2.8f * s, skin)
                eye(cx - 9 + (if (s < 1f) 1 else 0), (top + 3.6f).toInt(), if (p.eye == 1) eyeCol else dark)
                capsule(x - 2f * s, top + 11f, 3.2f * s, x - 9f * s + swing, 39f, 3.6f * s, pal, -0.06f)
            }
            View.BACK -> {
                capsule(x - 4f * s, 38f, 3.2f * s, x - 4.4f * s + swing / 2f, 44f, 3.4f * s, pal, -0.1f)
                capsule(x + 4f * s, 38f, 3.2f * s, x + 4.4f * s - swing / 2f, 44f, 3.4f * s, pal, -0.16f)
                if (kind == Ape.MACAQUE) capsule(x, 40f, 1.1f, x + 4f, 44f, 0.9f, pal, -0.12f)
                capsule(x - 9.2f * s, top + 11f, 3.6f * s, x - 11f * s, 40f - swing, 4f * s, pal, -0.08f)
                capsule(x + 9.2f * s, top + 11f, 3.6f * s, x + 11f * s, 40f + swing, 4f * s, pal, -0.16f)
                blob(x, 32f * s + (1f - s) * 14f, 8.6f * s, 9f * s, pal, -0.04f)
                blob(x, top + 9f, 10.4f * s, 5.4f * s, pal, 0.04f)
                if (kind == Ape.THUNDER) for (i in -2..2) spike(x + i * 1.8f - 1.2f, top + 3f, x + i * 1.8f + 1.2f, top + 3f, x + i * 2.4f, top - 5f + abs(i), mane, 0.85f)
                if (kind == Ape.ELF) for (i in -2..2) spike(x + i * 2f - 1.5f, top + 2f, x + i * 2f + 1.5f, top + 2f, x + i * 2f, top - 4f, mane, 0.7f)
                blob(x, top + 3f, 5.4f * s, 4.8f * s, pal, 0f)
            }
        }
    }

    // ---- stone and glass --------------------------------------------------------------------------------------------

    private fun golem(pal: PaletteRamp, v: View, cx: Int, p: Pose, eyeCol: Int) {
        val x = cx.toFloat()
        val sw = p.walk * 0.8f
        val slag = ember
        capsule(x - 5f, 37f, 3.8f, x - 5.4f + sw / 2f, 44f, 4.2f, pal, -0.12f)
        capsule(x + 5f, 37f, 3.8f, x + 5.4f - sw / 2f, 44f, 4.2f, pal, -0.18f)
        if (v == View.SIDE) {
            capsule(x, 27f, 4f, x - 8f - sw, 39f, 4.6f, pal, -0.1f)
        } else {
            capsule(x - 10.5f, 26f, 4f, x - 12f, 38f - sw, 4.6f, pal, -0.08f)
            capsule(x + 10.5f, 26f, 4f, x + 12f, 38f + sw, 4.6f, pal, -0.16f)
        }
        blob(x, 31f + p.bob, if (v == View.SIDE) 7.6f else 9.4f, 10f, pal)
        if (v == View.SIDE) blob(x - 1f, 24f + p.bob, 6.2f, 4.6f, pal, 0.06f) else {
            blob(x - 9.5f, 24f + p.bob, 4.8f, 4.4f, pal, 0.06f)
            blob(x + 9.5f, 24f + p.bob, 4.8f, 4.4f, pal, 0.02f)
        }
        // rock plates and crystals on the shoulders
        spike(x - 11f, 22f + p.bob, x - 7f, 22f + p.bob, x - 10f, 14f + p.bob, stone, 0.6f)
        spike(x + 7f, 22f + p.bob, x + 11f, 22f + p.bob, x + 10f, 15f + p.bob, stone, 0.5f)
        blob(x, 18.5f + p.bob, 4.8f, 4.2f, pal, 0.1f)
        when (v) {
            View.FRONT -> {
                eye(cx - 3, 18 + p.bob, eyeCol, true)
                eye(cx + 2, 18 + p.bob, eyeCol, true)
                // glowing slag cracks across the chest
                for ((dx, dy) in listOf(0 to 0, 1 to 1, 2 to 1, 3 to 2, -1 to 2, -2 to 3, -3 to 3, 0 to 4, 0 to 5)) dot(cx + dx, 28 + dy + p.bob, slag.colors[4])
            }
            View.SIDE -> {
                eye(cx - 6, 18 + p.bob, eyeCol, true)
                for ((dx, dy) in listOf(-3 to 0, -2 to 1, -1 to 2, 0 to 3, 1 to 4)) dot(cx + dx, 28 + dy + p.bob, slag.colors[4])
            }
            View.BACK -> {
                for (i in -2..2) spike(x + i * 3f - 1.6f, 29f + p.bob, x + i * 3f + 1.6f, 29f + p.bob, x + i * 3f, 22f + p.bob - abs(i), stone, 0.55f)
            }
        }
    }

    private fun beetle(pal: PaletteRamp, v: View, cx: Int, p: Pose, eyeCol: Int) {
        val x = cx.toFloat()
        val sw = p.walk * 0.7f
        val by = 37f + p.bob
        val darkShell = pal
        if (v == View.SIDE) {
            for (i in 0..3) capsule(x - 6f + i * 4f, by + 3f, 1f, x - 8f + i * 4f + (if (i % 2 == 0) sw else -sw) / 2f, 44f, 0.8f, darkShell, -0.15f)
            blob(x + 2f, by, 10f, 6.4f, pal)
            for (i in 0..2) spike(x - 2f + i * 4f, by - 4f, x + 2f + i * 4f, by - 4f, x + i * 4f, by - 15f + i, glass, 0.7f)
            blob(x - 8.5f, by - 1.5f, 4.6f, 4.2f, pal, 0.06f)
            capsule(x - 11f, by + 1f, 1.2f, x - 13f, by + 5f, 0.9f, bone, 0.05f)
            capsule(x - 7f, by + 2f, 1.2f, x - 9f, by + 7f, 0.9f, bone, 0.05f)
            eye(cx - 11, (by - 3f).toInt(), eyeCol)
            return
        }
        for (i in 0..2) {
            val oy = by + 1f + i * 1.6f
            capsule(x - 8f, oy, 1f, x - 12f - i, 44f, 0.8f, darkShell, -0.15f)
            capsule(x + 8f, oy, 1f, x + 12f + i, 44f, 0.8f, darkShell, -0.2f)
        }
        if (v == View.FRONT) {
            capsule(x - 8f, by - 2f, 2f, x - 12f, by + 2f + sw / 2f, 2.4f, pal, -0.05f)
            capsule(x + 8f, by - 2f, 2f, x + 12f, by + 2f - sw / 2f, 2.4f, pal, -0.12f)
        }
        blob(x, by, 11f, 6.8f, pal, if (v == View.BACK) -0.05f else 0f)
        for (i in -2..2) spike(x + i * 3.2f - 1.8f, by - 4f, x + i * 3.2f + 1.8f, by - 4f, x + i * 3.6f, by - 15f + abs(i) * 2f, glass, 0.7f)
        if (v == View.FRONT) {
            blob(x, by - 6f, 5.6f, 4.6f, pal, 0.06f)
            capsule(x - 3f, by - 3f, 1.2f, x - 5f, by + 3f, 0.9f, bone, 0.05f)
            capsule(x + 3f, by - 3f, 1.2f, x + 5f, by + 3f, 0.9f, bone, 0.05f)
            eye(cx - 3, (by - 7f).toInt(), eyeCol)
            eye(cx + 2, (by - 7f).toInt(), eyeCol)
        } else {
            capsule(x, by - 5f, 0.8f, x, by + 5f, 0.8f, darkShell, -0.3f)
        }
    }

    // ---- scorpion ---------------------------------------------------------------------------------------------------

    private fun scorpion(pal: PaletteRamp, v: View, cx: Int, p: Pose, eyeCol: Int) {
        val x = cx.toFloat()
        val by = 38f + p.bob
        val sw = p.walk * 0.6f
        val sting = bone
        val sway = if (p.phase % 2 == 1) 1f else -1f
        fun tail(pts: List<Pair<Float, Float>>) {
            for (i in 0 until pts.size - 1) {
                val r0 = 3.2f - i * 0.2f
                capsule(pts[i].first, pts[i].second, r0, pts[i + 1].first, pts[i + 1].second, r0 - 0.2f, pal, if (i % 2 == 0) 0.04f else -0.04f)
            }
            val last = pts.last()
            spike(last.first - 1.4f, last.second - 1f, last.first + 1.4f, last.second - 1f, last.first, last.second + 4f, sting, 0.8f)
        }
        when (v) {
            View.FRONT -> {
                for (i in 0..2) {
                    capsule(x - 5f + i * 0.5f, by + 1f, 0.8f, x - 9f - i * 1.3f + (if (i % 2 == 0) sw else -sw) / 2f, 44f, 0.7f, pal, -0.15f)
                    capsule(x + 5f - i * 0.5f, by + 1f, 0.8f, x + 9f + i * 1.3f + (if (i % 2 == 0) -sw else sw) / 2f, 44f, 0.7f, pal, -0.2f)
                }
                tail(listOf(x to by - 1f, x + 5f + sway to by - 8f, x + 7f + sway to by - 16f, x + 4f to by - 23f, x - 1f to by - 24f))
                capsule(x - 6f, by - 1f, 1.5f, x - 10f, by - 5f, 1.6f, pal, -0.05f)
                capsule(x + 6f, by - 1f, 1.5f, x + 10f, by - 5f, 1.6f, pal, -0.12f)
                blob(x - 10.5f, by - 8f, 2.4f, 2.8f, pal, 0.05f)
                blob(x + 10.5f, by - 8f, 2.4f, 2.8f, pal, 0f)
                spike(x - 11.8f, by - 10.8f, x - 9.2f, by - 10.8f, x - 10.5f, by - 7.8f, voidRamp, 0.3f)
                spike(x + 11.8f, by - 10.8f, x + 9.2f, by - 10.8f, x + 10.5f, by - 7.8f, voidRamp, 0.3f)
                blob(x, by, 8.4f, 5.2f, pal)
                blob(x, by - 3f, 4.6f, 3.2f, pal, 0.1f)
                eye(cx - 3, (by - 4f).toInt(), eyeCol)
                eye(cx + 2, (by - 4f).toInt(), eyeCol)
            }
            View.SIDE -> {
                for (i in 0..3) capsule(x - 4f + i * 3f, by + 1f, 0.8f, x - 6f + i * 3f + (if (i % 2 == 0) sw else -sw) / 2f, 44f, 0.7f, pal, -0.15f)
                tail(listOf(x + 7f to by - 1f, x + 12f + sway to by - 6f, x + 10f to by - 13f, x + 4f to by - 17f, x - 2f to by - 14f))
                capsule(x - 6f, by - 1f, 2f, x - 11f, by - 3f, 2.2f, pal, -0.05f)
                blob(x - 13f, by - 4f, 3.2f, 3.6f, pal, 0.05f)
                blob(x + 1f, by, 9.4f, 4.8f, pal)
                blob(x - 6f, by - 2.2f, 3.6f, 3f, pal, 0.08f)
                eye(cx - 8, (by - 4f).toInt(), eyeCol)
            }
            View.BACK -> {
                for (i in 0..2) {
                    capsule(x - 5f + i * 0.5f, by + 1f, 0.8f, x - 9f - i * 1.3f, 44f, 0.7f, pal, -0.15f)
                    capsule(x + 5f - i * 0.5f, by + 1f, 0.8f, x + 9f + i * 1.3f, 44f, 0.7f, pal, -0.2f)
                }
                capsule(x - 6f, by - 1f, 2f, x - 10f, by - 6f, 2.2f, pal, -0.05f)
                capsule(x + 6f, by - 1f, 2f, x + 10f, by - 6f, 2.2f, pal, -0.12f)
                blob(x - 11f, by - 9f, 3.2f, 3.6f, pal, 0.05f)
                blob(x + 11f, by - 9f, 3.2f, 3.6f, pal, 0f)
                blob(x, by, 8.4f, 5.2f, pal, -0.04f)
                tail(listOf(x to by - 2f, x + 1f + sway to by - 8f, x - 1f to by - 14f, x to by - 19f, x - 1f to by - 22f))
            }
        }
    }

    // ---- reaper -----------------------------------------------------------------------------------------------------

    private fun reaper(pal: PaletteRamp, v: View, cx: Int, p: Pose) {
        val x = cx.toFloat()
        val fy = 28f + p.bob
        val cyan = PaletteRamp.rgba(140, 240, 255)
        fun blade(bx: Float, tone: Float) {
            capsule(bx, fy - 10f, 0.8f, bx, fy + 8f, 0.8f, stone)
            spike(bx - 3.2f, fy - 11f, bx + 0.8f, fy - 10f, bx - 0.5f, fy - 3f, bone, tone)
            spike(bx - 3.2f, fy - 11f, bx - 1f, fy - 14.5f, bx + 0.8f, fy - 10f, bone, tone + 0.1f)
        }
        if (v == View.SIDE) blade(x + 2f, 0.7f) else {
            blade(x - 11f, 0.85f)
            blade(x + 11f, 0.7f)
        }
        hem(x, fy + 10f, 3, pal, p, 2.2f, 0.34f)
        capsule(x, fy - 1f, 7.4f, x, fy + 10f, 5.6f, pal)
        if (v != View.SIDE) {
            capsule(x - 7f, fy - 1f, 2f, x - 10.5f, fy + 3f, 1.7f, pal, -0.12f)
            capsule(x + 7f, fy - 1f, 2f, x + 10.5f, fy + 3f, 1.7f, pal, -0.2f)
        }
        blob(x, fy - 8f, 5.6f, 5.6f, pal, 0.06f)
        spike(x - 3f, fy - 12f, x + 3f, fy - 12f, x + 1f, fy - 17f, pal, 0.7f)
        when (v) {
            View.FRONT -> {
                blob(x, fy - 7f, 3.5f, 3.8f, voidRamp, -0.1f)
                dot(cx - 2, (fy - 7f).toInt(), cyan)
                dot(cx + 1, (fy - 7f).toInt(), cyan)
            }
            View.SIDE -> {
                blob(x - 2.5f, fy - 7f, 3f, 3.6f, voidRamp, -0.1f)
                dot(cx - 4, (fy - 7f).toInt(), cyan)
            }
            View.BACK -> Unit
        }
    }

    // ---- harpy and raptor -------------------------------------------------------------------------------------------

    private fun harpy(pal: PaletteRamp, v: View, cx: Int, p: Pose, eyeCol: Int, raptor: Boolean) {
        val x = cx.toFloat()
        val fl = p.flap * 1.4f
        val sw = p.walk / 2f
        val wing = if (raptor) bone else pal
        val shoulderY = if (raptor) 24f else 26f
        // talons and legs
        val legTop = if (raptor) 38f else 36f
        capsule(x - 2.6f, legTop, 1.5f, x - 2.6f + sw, 43f, 1.2f, beak)
        capsule(x + 2.6f, legTop, 1.5f, x + 2.6f - sw, 43f, 1.2f, beak)
        for (sgn in listOf(-1f, 1f)) {
            val fx = x + sgn * 2.6f - sgn * sw
            spike(fx - 2f, 43f, fx + 2f, 43f, fx, 46f, beak, 0.8f)
        }
        // wings
        if (v == View.SIDE) {
            for ((i, tone) in listOf(0.62f, 0.52f, 0.42f).withIndex()) {
                spike(x + 2f, shoulderY, x + 8f + i * 4f, shoulderY - 12f + i * 4f - fl, x + 5f, shoulderY + 8f, wing, tone)
            }
        } else if (raptor) {
            for (sgn in listOf(-1f, 1f)) {
                val tone = if (sgn < 0f) 0.68f else 0.54f
                spike(x + sgn * 4f, shoulderY, x + sgn * 16f, shoulderY + 1f - fl, x + sgn * 13f, shoulderY + 8f, wing, tone)
                spike(x + sgn * 4f, shoulderY + 2f, x + sgn * 15f, shoulderY + 9f - fl / 2f, x + sgn * 8f, shoulderY + 15f, wing, tone - 0.1f)
            }
        } else {
            for (sgn in listOf(-1f, 1f)) {
                val base = if (sgn < 0f) 0.76f else 0.6f
                spike(x + sgn * 3f, shoulderY, x + sgn * 16f, shoulderY - 5f - fl, x + sgn * 14f, shoulderY + 3f - fl / 2f, wing, base)
                spike(x + sgn * 3f, shoulderY + 1f, x + sgn * 15f, shoulderY + 3f - fl / 2f, x + sgn * 12f, shoulderY + 9f, wing, base - 0.1f)
                spike(x + sgn * 3f, shoulderY + 3f, x + sgn * 11f, shoulderY + 11f, x + sgn * 6f, shoulderY + 12f, wing, base - 0.2f)
            }
        }
        if (raptor) {
            if (v != View.FRONT) spike(x - 4f, 36f, x + 4f, 36f, x, 44f, wing, 0.4f)
            blob(x, 31f, 6.2f, 9f, pal)
            blob(x, 33f, 3.8f, 6f, bone, 0.1f)
            blob(x, 21f, 4.6f, 4.4f, pal, 0.05f)
        } else {
            capsule(x, shoulderY, 4.6f, x, 34f, 3.8f, pal)
            blob(x, 37f, 4.6f, 3.6f, pal, -0.05f)
            blob(x, 31f, 2.8f, 3.4f, bone, 0.1f)
            blob(x, 19.5f, 4.3f, 4.1f, pal, 0.05f)
            for (i in -1..1) spike(x + i * 2f - 1.2f, 16.5f, x + i * 2f + 1.2f, 16.5f, x + i * 2.4f, 11f + abs(i) * 1.5f, bone, 0.7f)
        }
        val hy = if (raptor) 21 else 19
        when (v) {
            View.FRONT -> {
                spike(x - 1.8f, hy + 1f, x + 1.8f, hy + 1f, x, hy + 5.5f, beak, 0.75f)
                eye(cx - 3, hy - 1, if (p.eye == 1) eyeCol else dark, true)
                eye(cx + 2, hy - 1, if (p.eye == 1) eyeCol else dark, true)
            }
            View.SIDE -> {
                spike(x - 4f, hy.toFloat(), x - 2f, hy + 3f, x - 8f, hy + 3f, beak, 0.75f)
                eye(cx - 4, hy - 1, if (p.eye == 1) eyeCol else dark, true)
            }
            View.BACK -> Unit
        }
    }

    // ---- bosses -----------------------------------------------------------------------------------------------------

    private fun dragon(pal: PaletteRamp, v: View, cx: Int, p: Pose, eyeCol: Int) {
        val x = cx.toFloat()
        val fl = p.flap * 1.5f
        val sw = p.walk * 0.7f
        val horn = natural(226, 206, 160)
        val belly = natural(222, 186, 112)
        // tail
        if (v == View.SIDE) {
            capsule(x + 6f, 38f, 3f, x + 12f, 41f, 2.2f, pal, -0.1f)
            capsule(x + 12f, 41f, 2.2f, x + 15f, 36f, 1.2f, pal, -0.1f)
        } else {
            capsule(x + 5f, 40f, 2.8f, x + 11f, 43f, 1.8f, pal, -0.12f)
            capsule(x + 11f, 43f, 1.8f, x + 14f, 40f, 1f, pal, -0.12f)
        }
        // wings (membrane panels with a bone arm), behind the body
        if (v == View.SIDE) {
            spike(x + 2f, 20f, x + 12f, 7f - fl, x + 8f, 32f, membrane, 0.55f)
            capsule(x + 2f, 21f, 1.2f, x + 12f, 8f - fl, 0.9f, horn, -0.05f)
        } else {
            for (sgn in listOf(-1f, 1f)) {
                val tone = if (sgn < 0f) 0.58f else 0.46f
                spike(x + sgn * 4f, 21f, x + sgn * 15.5f, 8f - fl, x + sgn * 14f, 30f, membrane, tone)
                spike(x + sgn * 4f, 25f, x + sgn * 15f, 18f - fl, x + sgn * 9f, 33f, membrane, tone - 0.1f)
                capsule(x + sgn * 4f, 22f, 1.3f, x + sgn * 15.5f, 9f - fl, 0.9f, horn, -0.05f)
            }
        }
        // legs
        capsule(x - 5f, 38f, 3.6f, x - 5.4f + sw / 2f, 44f, 3.6f, pal, -0.1f)
        capsule(x + 5f, 38f, 3.6f, x + 5.4f - sw / 2f, 44f, 3.6f, pal, -0.16f)
        for (sgn in listOf(-1f, 1f)) {
            val fx = x + sgn * 5.4f
            for (k in -1..1) spike(fx + k * 1.8f - 0.8f, 44f, fx + k * 1.8f + 0.8f, 44f, fx + k * 2.2f, 46.5f, horn, 0.8f)
        }
        // body
        blob(x, 30f + p.bob, if (v == View.SIDE) 8f else 9f, 11f, pal)
        if (v != View.BACK) blob(x - (if (v == View.SIDE) 2f else 0f), 33f + p.bob, if (v == View.SIDE) 4f else 5.6f, 8.4f, belly, 0.1f)
        if (v == View.BACK) {
            for (i in 0..4) spike(x - 1.6f, 22f + i * 4f + p.bob, x + 1.6f, 22f + i * 4f + p.bob, x, 18f + i * 4f + p.bob, horn, 0.75f)
        }
        // arms
        if (v == View.FRONT) {
            capsule(x - 8.5f, 27f, 2.4f, x - 10f, 35f + p.flap, 2.2f, pal, -0.05f)
            capsule(x + 8.5f, 27f, 2.4f, x + 10f, 35f + p.flap, 2.2f, pal, -0.14f)
        }
        // head
        val hx = if (v == View.SIDE) x - 4f else x
        blob(hx, 17f + p.bob, 5.8f, 5.2f, pal, 0.06f)
        if (v == View.SIDE) {
            capsule(hx - 3f, 19f + p.bob, 2.6f, hx - 8f, 20f + p.bob, 2f, pal, 0.04f)
            spike(hx - 1f, 14f + p.bob, hx + 3f, 14f + p.bob, hx + 7f, 8f + p.bob, horn, 0.85f)
            eye(cx - 8, 16 + p.bob, if (p.eye == 1) eyeCol else dark, true)
        } else {
            spike(hx - 5.5f, 14f + p.bob, hx - 2.5f, 14f + p.bob, hx - 8f, 5f + p.bob, horn, 0.88f)
            spike(hx + 5.5f, 14f + p.bob, hx + 2.5f, 14f + p.bob, hx + 8f, 6f + p.bob, horn, 0.74f)
            if (v == View.FRONT) {
                blob(hx, 20.6f + p.bob, 3.4f, 2.6f, pal, 0.12f)
                dot(cx - 1, 20 + p.bob, dark)
                dot(cx + 1, 20 + p.bob, dark)
                eye(cx - 4, 15 + p.bob, if (p.eye == 1) eyeCol else dark, true)
                eye(cx + 3, 15 + p.bob, if (p.eye == 1) eyeCol else dark, true)
            }
        }
    }

    private fun banshee(pal: PaletteRamp, v: View, cx: Int, p: Pose) {
        val x = cx.toFloat()
        val fy = 27f + p.bob
        val glow = PaletteRamp.rgba(120, 232, 255)
        val hair = natural(150, 188, 226)
        val sway = if (p.phase % 2 == 1) 1f else -1f
        // streaming hair behind
        capsule(x - 5f, fy - 8f, 3.4f, x - 9f - sway, fy + 14f, 2f, hair, -0.12f)
        capsule(x + 5f, fy - 8f, 3.4f, x + 9f + sway, fy + 14f, 2f, hair, -0.2f)
        hem(x, fy + 11f, 3, pal, p, 2.4f, 0.4f)
        // arms raised in a wail
        if (v != View.SIDE) {
            capsule(x - 5.5f, fy + 1f, 2f, x - 11f, fy - 5f, 1.5f, pal, -0.1f)
            capsule(x + 5.5f, fy + 1f, 2f, x + 11f, fy - 5f, 1.5f, pal, -0.18f)
            for (sgn in listOf(-1f, 1f)) for (k in -1..1) {
                spike(x + sgn * 11f - 0.6f, fy - 5f, x + sgn * 11f + 0.6f, fy - 5f, x + sgn * (11f + k * 1.6f), fy - 10f, ice, 0.8f)
            }
        } else {
            capsule(x - 2f, fy + 1f, 2f, x - 9f, fy - 4f, 1.5f, pal, -0.1f)
        }
        capsule(x, fy, 6.6f, x, fy + 12f, 4f, pal)
        blob(x, fy - 8f, 4.8f, 5.2f, pal, 0.1f)
        if (v == View.BACK) {
            capsule(x, fy - 9f, 5.2f, x, fy + 8f, 4.8f, hair, -0.05f)
        }
        // ice crown
        for (i in -2..2) spike(x + i * 2.2f - 1.2f, fy - 11.5f, x + i * 2.2f + 1.2f, fy - 11.5f, x + i * 2.4f, fy - 18f + abs(i) * 1.5f, ice, 0.9f)
        when (v) {
            View.FRONT -> {
                dot(cx - 3, (fy - 9f).toInt(), glow)
                dot(cx - 2, (fy - 9f).toInt(), glow)
                dot(cx + 2, (fy - 9f).toInt(), glow)
                dot(cx + 3, (fy - 9f).toInt(), glow)
                blob(x, fy - 4.2f, 1.7f, 2.3f, voidRamp, -0.1f)
            }
            View.SIDE -> {
                dot(cx - 5, (fy - 9f).toInt(), glow)
                dot(cx - 4, (fy - 9f).toInt(), glow)
                blob(x - 3.5f, fy - 4.2f, 1.2f, 2f, voidRamp, -0.1f)
            }
            View.BACK -> Unit
        }
    }
}
