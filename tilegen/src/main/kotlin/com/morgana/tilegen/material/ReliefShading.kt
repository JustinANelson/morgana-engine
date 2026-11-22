package com.morgana.tilegen.material

import com.morgana.tilegen.TileImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-craft pixel-art drawing, heightfield relief shading, outlining, and lighting toolkit.
 *
 * Designed to bridge the gap between raw procedural noise and hand-pixeled 2.5D RPG art:
 * - [Heightfield] lets generators sculpt rounded cobblestones, beveled flagstones, carved stone
 *   sarcophagi, statues, and coin mounds in a float relief buffer and shade them with directional
 *   top-left lighting, crevice ambient occlusion, and quantized [PaletteRamp] sampling.
 * - [PixelCanvas] provides crisp pixel-art geometry, grounded contact shadows, directional wall AO,
 *   additive/screen radial glow halos, and layered animated flames.
 * - [PixelOutline] applies hue-shifted selective dark outlines and 1 px inner bevels.
 */
class Heightfield(val width: Int, val height: Int) {
    val heights = FloatArray(width * height)
    val mask = BooleanArray(width * height)

    fun get(x: Int, y: Int): Float =
        if (x in 0 until width && y in 0 until height) heights[y * width + x] else 0f

    fun isMasked(x: Int, y: Int): Boolean =
        if (x in 0 until width && y in 0 until height) mask[y * width + x] else false

    fun set(x: Int, y: Int, h: Float) {
        if (x in 0 until width && y in 0 until height) {
            val i = y * width + x
            heights[i] = h
            mask[i] = true
        }
    }

    fun raiseMax(x: Int, y: Int, h: Float) {
        if (x in 0 until width && y in 0 until height) {
            val i = y * width + x
            if (!mask[i] || h > heights[i]) {
                heights[i] = h
                mask[i] = true
            }
        }
    }

    fun add(x: Int, y: Int, delta: Float) {
        if (x in 0 until width && y in 0 until height) {
            val i = y * width + x
            heights[i] = (heights[i] + delta).coerceAtLeast(0f)
            mask[i] = true
        }
    }

    /** Sculpts a smooth rounded stone/dome into the heightfield using max-blending. */
    fun addDome(cx: Float, cy: Float, rx: Float, ry: Float, peak: Float = 1f, power: Float = 0.65f) {
        val x0 = (cx - rx - 1).toInt().coerceAtLeast(0)
        val x1 = (cx + rx + 1).toInt().coerceAtMost(width - 1)
        val y0 = (cy - ry - 1).toInt().coerceAtLeast(0)
        val y1 = (cy + ry + 1).toInt().coerceAtMost(height - 1)
        for (y in y0..y1) {
            val dy = (y + 0.5f - cy) / ry
            for (x in x0..x1) {
                val dx = (x + 0.5f - cx) / rx
                val d2 = dx * dx + dy * dy
                if (d2 < 1f) {
                    val h = peak * powApprox(1f - d2, power)
                    raiseMax(x, y, h)
                }
            }
        }
    }

    /** Sculpts a beveled rectangular stone block into the heightfield. */
    fun addBeveledBox(x0: Int, y0: Int, x1: Int, y1: Int, peak: Float = 1f, bevel: Float = 2f) {
        val bx0 = x0.coerceAtLeast(0)
        val by0 = y0.coerceAtLeast(0)
        val bx1 = x1.coerceAtMost(width - 1)
        val by1 = y1.coerceAtMost(height - 1)
        val b = bevel.coerceAtLeast(0.5f)
        for (y in by0..by1) {
            val ey = min(y - y0 + 0.5f, y1 - y + 0.5f)
            for (x in bx0..bx1) {
                val ex = min(x - x0 + 0.5f, x1 - x + 0.5f)
                val edge = min(ex, ey)
                val t = (edge / b).coerceIn(0f, 1f)
                // Smoothstep bevel profile
                val s = t * t * (3f - 2f * t)
                raiseMax(x, y, peak * s)
            }
        }
    }

    /** Carves a recessed groove/crack line into masked pixels. */
    fun carveLine(x0: Float, y0: Float, x1: Float, y1: Float, depth: Float = 0.35f, radius: Float = 0.85f) {
        val steps = max(abs(x1 - x0), abs(y1 - y0)).toInt().coerceAtLeast(1) * 2
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val px = x0 + (x1 - x0) * t
            val py = y0 + (y1 - y0) * t
            val minX = (px - radius - 1).toInt().coerceAtLeast(0)
            val maxX = (px + radius + 1).toInt().coerceAtMost(width - 1)
            val minY = (py - radius - 1).toInt().coerceAtLeast(0)
            val maxY = (py + radius + 1).toInt().coerceAtMost(height - 1)
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val d = hypot(x + 0.5f - px, y + 0.5f - py)
                    if (d <= radius && isMasked(x, y)) {
                        val idx = y * width + x
                        val falloff = 1f - d / radius
                        heights[idx] = (heights[idx] - depth * falloff).coerceAtLeast(0f)
                    }
                }
            }
        }
    }

    /**
     * Renders the heightfield into [target] using directional top-left shading, crevice AO,
     * and quantized [PaletteRamp] lookup with optional Bayer dithering.
     *
     * When [wrap] is true, neighbor lookups wrap toroidally for seamless repeating tiles.
     */
    fun shadeInto(
        target: TileImage,
        ramp: PaletteRamp,
        dstX: Int = 0,
        dstY: Int = 0,
        lightX: Float = -0.68f,
        lightY: Float = -0.78f,
        lightZ: Float = 0.55f,
        ambient: Float = 0.42f,
        contrast: Float = 0.62f,
        aoStrength: Float = 0.32f,
        normalScale: Float = 2.4f,
        ditherStrength: Float = 0.08f,
        mortarThreshold: Float = 0.06f,
        mortarColor: Int = ramp.shadow,
        wrap: Boolean = false
    ) {
        val lLen = sqrt(lightX * lightX + lightY * lightY + lightZ * lightZ).coerceAtLeast(0.001f)
        val lx = lightX / lLen
        val ly = lightY / lLen
        val lz = lightZ / lLen

        fun sampleH(x: Int, y: Int): Float {
            return if (wrap) {
                heights[Math.floorMod(y, height) * width + Math.floorMod(x, width)]
            } else {
                get(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
            }
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (!mask[idx]) continue
                val h = heights[idx]
                if (h <= mortarThreshold) {
                    target.drawPixel(dstX + x, dstY + y, mortarColor)
                    continue
                }
                val hL = sampleH(x - 1, y)
                val hR = sampleH(x + 1, y)
                val hU = sampleH(x, y - 1)
                val hD = sampleH(x, y + 1)

                val dx = (hR - hL) * normalScale
                val dy = (hD - hU) * normalScale
                val invLen = 1f / sqrt(dx * dx + dy * dy + 1f)
                val nx = -dx * invLen
                val ny = -dy * invLen
                val nz = invLen

                val ndotl = (nx * lx + ny * ly + nz * lz).coerceIn(0f, 1f)
                val avgNeighbor = (hL + hR + hU + hD) * 0.25f
                val crevice = (avgNeighbor - h).coerceAtLeast(0f)
                val heightLift = (h - 0.5f) * 0.22f
                val tone = (ambient + (ndotl - 0.45f) * contrast + heightLift - crevice * aoStrength)
                    .coerceIn(0.02f, 0.99f)
                val color = if (ditherStrength > 0f) {
                    PixelDither.sampleDithered(ramp, tone, x, y, matrixSize = 4, strength = ditherStrength)
                } else {
                    ramp.sample(tone)
                }
                target.drawPixel(dstX + x, dstY + y, color)
            }
        }
    }

    companion object {
        private fun powApprox(base: Float, exp: Float): Float =
            Math.pow(base.coerceIn(0f, 1f).toDouble(), exp.toDouble()).toFloat()
    }
}

/**
 * Crisp pixel-art drawing, shadowing, and emissive lighting helper operating on a [TileImage].
 */
class PixelCanvas(val image: TileImage) {
    val width: Int get() = image.width
    val height: Int get() = image.height

    fun px(x: Int, y: Int, rgba: Int) = image.drawPixel(x, y, rgba)

    fun set(x: Int, y: Int, rgba: Int) = image.setPixel(x, y, rgba)

    fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int, rgba: Int) {
        val bx0 = x0.coerceAtLeast(0)
        val by0 = y0.coerceAtLeast(0)
        val bx1 = x1.coerceAtMost(width - 1)
        val by1 = y1.coerceAtMost(height - 1)
        for (y in by0..by1) for (x in bx0..bx1) image.drawPixel(x, y, rgba)
    }

    /** Draws a beveled pixel-art box with top-left highlight lip and bottom-right shadow rim. */
    fun fillBeveledRect(
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        ramp: PaletteRamp,
        baseTone: Float = 0.52f,
        topBoost: Float = 0.24f,
        bottomDrop: Float = 0.22f,
        dither: Float = 0.06f
    ) {
        for (y in y0..y1) {
            for (x in x0..x1) {
                var t = baseTone
                val isTop = y == y0
                val isBottom = y == y1
                val isLeft = x == x0
                val isRight = x == x1
                if (isTop) t += topBoost
                else if (isBottom) t -= bottomDrop
                if (isLeft && !isBottom) t += topBoost * 0.65f
                else if (isRight && !isTop) t -= bottomDrop * 0.65f
                val col = if (dither > 0f) {
                    PixelDither.sampleDithered(ramp, t.coerceIn(0.05f, 0.98f), x, y, 4, dither)
                } else {
                    ramp.sample(t.coerceIn(0.05f, 0.98f))
                }
                px(x, y, col)
            }
        }
    }

    /** Draws a shaded ellipse lit from the upper-left. */
    fun fillShadedEllipse(
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        ramp: PaletteRamp,
        baseTone: Float = 0.55f,
        dither: Float = 0.08f
    ) {
        val x0 = (cx - rx - 1).toInt().coerceAtLeast(0)
        val x1 = (cx + rx + 1).toInt().coerceAtMost(width - 1)
        val y0 = (cy - ry - 1).toInt().coerceAtLeast(0)
        val y1 = (cy + ry + 1).toInt().coerceAtMost(height - 1)
        for (y in y0..y1) {
            val dy = (y + 0.5f - cy) / ry
            for (x in x0..x1) {
                val dx = (x + 0.5f - cx) / rx
                val d2 = dx * dx + dy * dy
                if (d2 <= 1f) {
                    val nz = sqrt((1f - d2).coerceAtLeast(0f))
                    val t = (baseTone - dx * 0.22f - dy * 0.22f + (nz - 0.5f) * 0.20f).coerceIn(0.06f, 0.98f)
                    px(x, y, PixelDither.sampleDithered(ramp, t, x, y, 4, dither))
                }
            }
        }
    }

    /** Draws a crisp Bresenham line. */
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, rgba: Int) {
        var cx = x0
        var cy = y0
        val dx = abs(x1 - x0)
        val sx = if (x0 < x1) 1 else -1
        val dy = -abs(y1 - y0)
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            px(cx, cy, rgba)
            if (cx == x1 && cy == y1) break
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                cx += sx
            }
            if (e2 <= dx) {
                err += dx
                cy += sy
            }
        }
    }

    /** Draws a grounded two-ring contact shadow ellipse (hue-shifted dark plum/charcoal). */
    fun drawContactShadow(
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        shadowRgb: Int = PaletteRamp.rgba(16, 11, 20),
        coreAlpha: Int = 128,
        rimAlpha: Int = 62
    ) {
        val core = (shadowRgb and 0xFFFFFF00.toInt()) or coreAlpha.coerceIn(0, 255)
        val rim = (shadowRgb and 0xFFFFFF00.toInt()) or rimAlpha.coerceIn(0, 255)
        val x0 = (cx - rx - 1).toInt().coerceAtLeast(0)
        val x1 = (cx + rx + 1).toInt().coerceAtMost(width - 1)
        val y0 = (cy - ry - 1).toInt().coerceAtLeast(0)
        val y1 = (cy + ry + 1).toInt().coerceAtMost(height - 1)
        for (y in y0..y1) {
            val dy = (y + 0.5f - cy) / ry
            for (x in x0..x1) {
                val dx = (x + 0.5f - cx) / rx
                val d = hypot(dx, dy)
                if (d <= 0.72f) px(x, y, core) else if (d <= 1f) px(x, y, rim)
            }
        }
    }

    /**
     * Applies a soft directional ambient-occlusion shadow gradient across a rectangle,
     * e.g. a wall casting shadow south or east onto adjacent floor/water tiles.
     */
    fun drawDirectionalAO(
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        fadeFrom: EdgeSide,
        maxAlpha: Int = 115,
        shadowRgb: Int = PaletteRamp.rgba(16, 11, 20)
    ) {
        val bx0 = x0.coerceAtLeast(0)
        val by0 = y0.coerceAtLeast(0)
        val bx1 = x1.coerceAtMost(width - 1)
        val by1 = y1.coerceAtMost(height - 1)
        val spanX = (x1 - x0 + 1).coerceAtLeast(1).toFloat()
        val spanY = (y1 - y0 + 1).coerceAtLeast(1).toFloat()
        for (y in by0..by1) {
            for (x in bx0..bx1) {
                val t = when (fadeFrom) {
                    EdgeSide.TOP -> 1f - (y - y0) / spanY
                    EdgeSide.BOTTOM -> 1f - (y1 - y) / spanY
                    EdgeSide.LEFT -> 1f - (x - x0) / spanX
                    EdgeSide.RIGHT -> 1f - (x1 - x) / spanX
                }.coerceIn(0f, 1f)
                val alpha = (t * t * maxAlpha).roundToInt().coerceIn(0, 255)
                if (alpha > 0) {
                    px(x, y, (shadowRgb and 0xFFFFFF00.toInt()) or alpha)
                }
            }
        }
    }

    /**
     * Blends a luminous radial glow halo onto the image using screen-like additive illumination.
     * Preserves transparency on empty pixels by writing translucent light when the background is clear.
     */
    fun drawGlow(
        cx: Float,
        cy: Float,
        radius: Float,
        glowRgb: Int,
        intensity: Float = 0.65f,
        coreBoost: Float = 0.35f
    ) {
        val gr = ((glowRgb ushr 24) and 0xFF) / 255f
        val gg = ((glowRgb ushr 16) and 0xFF) / 255f
        val gb = ((glowRgb ushr 8) and 0xFF) / 255f
        // Clamp effective radius slightly inside canvas bounds to prevent visible rectangular edge clipping
        val edgeLimit = min(min(cx, width - cx), min(cy, height - cy)).coerceAtLeast(2f)
        val effRadius = min(radius, edgeLimit)
        val x0 = (cx - effRadius).toInt().coerceAtLeast(0)
        val x1 = (cx + effRadius).toInt().coerceAtMost(width - 1)
        val y0 = (cy - effRadius).toInt().coerceAtLeast(0)
        val y1 = (cy + effRadius).toInt().coerceAtMost(height - 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dist = hypot(x + 0.5f - cx, y + 0.5f - cy)
                if (dist >= effRadius) continue
                val falloff = 1f - dist / effRadius
                val smooth = falloff * falloff * (3f - 2f * falloff)
                val strength = (smooth * falloff * intensity + falloff * falloff * falloff * coreBoost).coerceIn(0f, 1f)
                if (strength <= 0.01f) continue

                val dst = image.getPixel(x, y)
                val da = dst and 0xFF
                if (da == 0) {
                    val outA = (strength * 185f).roundToInt().coerceIn(0, 255)
                    image.setPixel(
                        x,
                        y,
                        PaletteRamp.rgba((gr * 255f).roundToInt(), (gg * 255f).roundToInt(), (gb * 255f).roundToInt(), outA)
                    )
                } else {
                    val dr = ((dst ushr 24) and 0xFF) / 255f
                    val dg = ((dst ushr 16) and 0xFF) / 255f
                    val db = ((dst ushr 8) and 0xFF) / 255f
                    // Soft screen-additive blend so highlights glow without washing out dark stone
                    val nr = (dr + gr * strength * (1f - dr * 0.45f)).coerceIn(0f, 1f)
                    val ng = (dg + gg * strength * (1f - dg * 0.45f)).coerceIn(0f, 1f)
                    val nb = (db + gb * strength * (1f - db * 0.45f)).coerceIn(0f, 1f)
                    val outA = max(da, (strength * 200f).roundToInt()).coerceIn(0, 255)
                    image.setPixel(
                        x,
                        y,
                        PaletteRamp.rgba((nr * 255f).roundToInt(), (ng * 255f).roundToInt(), (nb * 255f).roundToInt(), outA)
                    )
                }
            }
        }
    }

    /**
     * Draws a stylized multi-layered pixel-art flame (white-hot core, bright mid-flame, wispy tip)
     * plus an optional soft radial glow halo around it.
     */
    fun drawFlame(
        cx: Float,
        baseY: Float,
        flameWidth: Float,
        flameHeight: Float,
        ramp: PaletteRamp,
        seed: Long = 0L,
        phase: Float = 0f,
        withGlow: Boolean = true
    ) {
        if (withGlow) {
            drawGlow(
                cx = cx,
                cy = baseY - flameHeight * 0.45f,
                radius = max(flameWidth, flameHeight) * 1.9f,
                glowRgb = ramp.sample(0.75f),
                intensity = 0.55f,
                coreBoost = 0.30f
            )
        }
        val yStart = (baseY - flameHeight - 1).toInt().coerceAtLeast(0)
        val yEnd = (baseY + 1).toInt().coerceAtMost(height - 1)
        val xStart = (cx - flameWidth - 2).toInt().coerceAtLeast(0)
        val xEnd = (cx + flameWidth + 2).toInt().coerceAtMost(width - 1)

        for (y in yStart..yEnd) {
            val v = ((baseY - y) / flameHeight).coerceIn(0f, 1.2f)
            if (v > 1f) continue
            // Sinuous sway toward the tip
            val sway = sin((v * 3.8f + phase + (seed % 17) * 0.37f).toDouble()).toFloat() * flameWidth * 0.28f * v
            val halfW = flameWidth * 0.55f * (1f - v * 0.78f) * (0.65f + 0.55f * sin(v * PI).toFloat())
            for (x in xStart..xEnd) {
                val dx = abs(x + 0.5f - (cx + sway))
                if (dx <= halfW) {
                    val radial = 1f - (dx / halfW.coerceAtLeast(0.5f))
                    // Brightest near lower-center core, tapering to saturated wisps at top/edges
                    val heat = (radial * 0.72f + (1f - v) * 0.38f).coerceIn(0.15f, 1f)
                    px(x, y, ramp.sample(heat))
                }
            }
        }
        // 1-2 detached ember sparks rising above the tip
        val sparkY = (baseY - flameHeight - 1.5f).roundToInt()
        val sparkX = (cx + sin((phase + seed * 0.2f).toDouble()).toFloat() * 1.8f).roundToInt()
        if (sparkX in 0 until width && sparkY in 0 until height) {
            px(sparkX, sparkY, ramp.sample(0.85f))
        }
    }
}

enum class EdgeSide { TOP, BOTTOM, LEFT, RIGHT }

/**
 * Crisp hue-shifted outlining and 1 px inner-bevel utilities for dungeon props and architectural tiles.
 */
object PixelOutline {
    /** Default dark plum-charcoal outline matching cohesive dungeon pixel art (`#1B141A`). */
    val DUNGEON_INK: Int = PaletteRamp.rgba(27, 20, 26, 255)

    /**
     * Adds a crisp 1 px 4-connected outline around opaque pixels (`alpha >= 200`).
     * Top/left lit edges blend slightly toward the neighboring fill; bottom/right edges stay deep ink.
     */
    fun applyCrispOutline(
        source: TileImage,
        inkColor: Int = DUNGEON_INK,
        litEdgeBlend: Float = 0.28f
    ): TileImage {
        val w = source.width
        val h = source.height
        val out = source.copy()
        val ir = (inkColor ushr 24) and 0xFF
        val ig = (inkColor ushr 16) and 0xFF
        val ib = (inkColor ushr 8) and 0xFF
        val dx = intArrayOf(1, 0, -1, 0)
        val dy = intArrayOf(0, 1, 0, -1)

        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((source.getPixel(x, y) and 0xFF) >= 200) continue
                var sr = 0
                var sg = 0
                var sb = 0
                var count = 0
                var litBias = 0
                for (i in 0 until 4) {
                    val nx = x + dx[i]
                    val ny = y + dy[i]
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val p = source.getPixel(nx, ny)
                    if ((p and 0xFF) >= 200) {
                        sr += (p ushr 24) and 0xFF
                        sg += (p ushr 16) and 0xFF
                        sb += (p ushr 8) and 0xFF
                        count++
                        litBias += if (i < 2) 1 else -1
                    }
                }
                if (count > 0) {
                    val blend = if (litBias > 0) litEdgeBlend else litEdgeBlend * 0.25f
                    val nr = ((sr / count) * blend * 0.55f + ir * (1f - blend)).roundToInt().coerceIn(0, 255)
                    val ng = ((sg / count) * blend * 0.55f + ig * (1f - blend)).roundToInt().coerceIn(0, 255)
                    val nb = ((sb / count) * blend * 0.55f + ib * (1f - blend)).roundToInt().coerceIn(0, 255)
                    out.setPixel(x, y, PaletteRamp.rgba(nr, ng, nb, 255))
                }
            }
        }
        return out
    }
}
