package com.morgana.tilegen.material

import com.morgana.tilegen.TileImage

/**
 * Rendering style profile controlling palette quantization, dithering, and edge crispness.
 */
enum class PixelStyle {
    /** Default procedural pixel-art rendering. */
    STANDARD,

    /**
     * Retro 8-bit console style (NES / Game Boy / PICO-8):
     * strict 4-color palette cap, ordered 2x2 Bayer dithering, and crisp integer-stepped edges.
     */
    RETRO_8BIT,

    /**
     * Classic RMXP-style RPG:
     * subtle 4x4 ordered Bayer cluster dithering for hand-pixeled ground and foliage texture.
     */
    CLASSIC_RPG,

    /**
     * PS1 32-bit JRPG style:
     * warm cluster shading, deep indigo-shifted shadows, and light-aware selective outlining ("sel-out").
     */
    JRPG_32BIT,

    /**
     * 2.5D Anime-Chibi & Painted Environment style:
     * luminous pastel-to-deep-shade gradients, top-left inner rim highlights, and hue-tinted selective outlines.
     */
    PAINTED_25D,

    /**
     * Morgana house style for characters, monsters and gear: muted colour, rim-shaded volume and a soft cool outline
     * (see [SpriteFinish]), so sprites sit in the same soft look as the map art.
     */
    MORGANA
}

/**
 * Deterministic ordered Bayer dithering and palette-quantization utilities.
 */
object PixelDither {

    private val bayer2x2 = arrayOf(
        floatArrayOf(0f / 4f, 2f / 4f),
        floatArrayOf(3f / 4f, 1f / 4f)
    )

    private val bayer4x4 = arrayOf(
        floatArrayOf(0f / 16f, 8f / 16f, 2f / 16f, 10f / 16f),
        floatArrayOf(12f / 16f, 4f / 16f, 14f / 16f, 6f / 16f),
        floatArrayOf(3f / 16f, 11f / 16f, 1f / 16f, 9f / 16f),
        floatArrayOf(15f / 16f, 7f / 16f, 13f / 16f, 5f / 16f)
    )

    /**
     * Returns a centered Bayer threshold in `[-0.5, +0.5)` for pixel coordinates ([x], [y]).
     */
    fun threshold(x: Int, y: Int, matrixSize: Int = 4): Float {
        return when (matrixSize) {
            2 -> bayer2x2[Math.floorMod(y, 2)][Math.floorMod(x, 2)] - 0.375f
            4 -> bayer4x4[Math.floorMod(y, 4)][Math.floorMod(x, 4)] - 0.46875f
            else -> throw IllegalArgumentException("Supported Bayer matrix sizes are 2 or 4, got $matrixSize")
        }
    }

    /**
     * Samples [ramp] at normalized value [t] with ordered Bayer dithering at ([x], [y]).
     */
    fun sampleDithered(
        ramp: PaletteRamp,
        t: Float,
        x: Int,
        y: Int,
        matrixSize: Int = 4,
        strength: Float = 0.18f
    ): Int {
        val biased = (t + threshold(x, y, matrixSize) * strength).coerceIn(0f, 1f)
        return ramp.sample(biased)
    }

    /**
     * Applies [style] as a deterministic post-pass over [source], returning a styled copy
     * (or [source] unchanged when [style] is [PixelStyle.STANDARD]).
     */
    fun applyStyle(source: TileImage, style: PixelStyle, palette: PaletteRamp): TileImage {
        if (style == PixelStyle.STANDARD) return source

        val effectiveRamp = when (style) {
            PixelStyle.RETRO_8BIT -> palette.limitColors(4)
            PixelStyle.CLASSIC_RPG, PixelStyle.JRPG_32BIT, PixelStyle.PAINTED_25D -> palette
            PixelStyle.MORGANA -> return source // shading and outline are done per layer by SpriteFinish
            PixelStyle.STANDARD -> return source
        }
        val matrixSize = if (style == PixelStyle.RETRO_8BIT) 2 else 4
        val strength = when (style) {
            PixelStyle.RETRO_8BIT -> 0.22f
            PixelStyle.CLASSIC_RPG -> 0.14f
            PixelStyle.JRPG_32BIT -> 0.10f
            PixelStyle.PAINTED_25D -> 0.08f
            PixelStyle.MORGANA, PixelStyle.STANDARD -> 0f
        }

        val out = TileImage(source.width, source.height)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val rgba = source.getPixel(x, y)
                val a = rgba and 0xFF
                if (a == 0) {
                    out.setPixel(x, y, 0)
                    continue
                }
                val r = ((rgba ushr 24) and 0xFF) / 255f
                val g = ((rgba ushr 16) and 0xFF) / 255f
                val b = ((rgba ushr 8) and 0xFF) / 255f
                val luma = 0.299f * r + 0.587f * g + 0.114f * b

                // Preserve exact palette colors when already on-ramp; otherwise dither-quantize
                val quantized = when (style) {
                    PixelStyle.RETRO_8BIT -> {
                        val ditheredT = (luma + threshold(x, y, matrixSize) * strength).coerceIn(0f, 1f)
                        val candidate = effectiveRamp.sample(ditheredT)
                        // Blend toward nearest color if hue differs strongly from ramp luma
                        val nearest = effectiveRamp.quantize(rgba)
                        if (effectiveRamp.colors.any { (it and 0xFFFFFF00.toInt()) == (rgba and 0xFFFFFF00.toInt()) }) {
                            rgba
                        } else if (kotlin.math.abs(threshold(x, y, matrixSize)) > 0.15f) {
                            candidate
                        } else {
                            nearest
                        }
                    }
                    PixelStyle.CLASSIC_RPG -> {
                        // CLASSIC_RPG: subtle ordered cluster dithering toward the ramp
                        val nearest = effectiveRamp.quantize(rgba)
                        if (effectiveRamp.colors.any { (it and 0xFFFFFF00.toInt()) == (rgba and 0xFFFFFF00.toInt()) }) {
                            val ditheredT = (luma + threshold(x, y, matrixSize) * strength).coerceIn(0f, 1f)
                            val shifted = effectiveRamp.sample(ditheredT)
                            if (kotlin.math.abs(threshold(x, y, matrixSize)) > 0.35f) shifted else rgba
                        } else {
                            nearest
                        }
                    }
                    PixelStyle.JRPG_32BIT -> {
                        // Warm sunlit top-left bias + deep indigo-violet shadow enrichment
                        val dirBias = ((0.5f - x.toFloat() / source.width.coerceAtLeast(1)) * 0.04f +
                            (0.5f - y.toFloat() / source.height.coerceAtLeast(1)) * 0.05f)
                        if (effectiveRamp.colors.any { (it and 0xFFFFFF00.toInt()) == (rgba and 0xFFFFFF00.toInt()) }) {
                            val ditheredT = (luma + dirBias + threshold(x, y, 4) * strength).coerceIn(0f, 1f)
                            if (kotlin.math.abs(threshold(x, y, 4)) > 0.38f) effectiveRamp.sample(ditheredT) else rgba
                        } else {
                            tintJrpgTone(rgba, luma)
                        }
                    }
                    PixelStyle.PAINTED_25D -> {
                        // Luminous 2.5D directional gradient + pastel highlight bloom
                        val lightGrad = ((1f - (x + y).toFloat() / (source.width + source.height).coerceAtLeast(2)) - 0.5f) * 0.10f
                        tintPaintedTone(rgba, luma + lightGrad)
                    }
                    PixelStyle.MORGANA, PixelStyle.STANDARD -> rgba
                }
                val finalAlpha = if (style == PixelStyle.RETRO_8BIT) 255 else a
                out.setPixel(x, y, (quantized and 0xFFFFFF00.toInt()) or finalAlpha)
            }
        }
        return out
    }

    /**
     * Applies light-aware selective outlining ("sel-out") around non-transparent pixel clusters in [source].
     * Top-left lit silhouette edges receive a hue-matched medium-dark border, while bottom-right shadow
     * silhouette edges receive a deeper indigo-shifted border (`[shadowAnchor]`).
     */
    fun applySelectiveOutline(
        source: TileImage,
        shadowAnchor: Int = PaletteRamp.rgba(30, 24, 44)
    ): TileImage {
        val out = source.copy()
        val w = source.width
        val h = source.height
        val ar = (shadowAnchor ushr 24) and 0xFF
        val ag = (shadowAnchor ushr 16) and 0xFF
        val ab = (shadowAnchor ushr 8) and 0xFF

        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = source.getPixel(x, y)
                if ((px and 0xFF) >= 160) continue

                // Inspect 4-connected opaque neighbors (excluding semi-transparent ground shadows)
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var count = 0
                var litWeight = 0f

                val offsets = arrayOf(
                    intArrayOf(1, 0),   // neighbor to the right -> this pixel is on the LEFT (lit) edge
                    intArrayOf(0, 1),   // neighbor below -> this pixel is on the TOP (lit) edge
                    intArrayOf(-1, 0),  // neighbor to the left -> this pixel is on the RIGHT (shadow) edge
                    intArrayOf(0, -1)   // neighbor above -> this pixel is on the BOTTOM (shadow) edge
                )
                for (i in offsets.indices) {
                    val nx = x + offsets[i][0]
                    val ny = y + offsets[i][1]
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val nPx = source.getPixel(nx, ny)
                    val na = nPx and 0xFF
                    if (na >= 200) {
                        sumR += (nPx ushr 24) and 0xFF
                        sumG += (nPx ushr 16) and 0xFF
                        sumB += (nPx ushr 8) and 0xFF
                        count++
                        litWeight += if (i < 2) 1f else -1f
                    }
                }
                if (count > 0) {
                    val nr = sumR / count
                    val ng = sumG / count
                    val nb = sumB / count
                    val isLitEdge = litWeight > 0f
                    val shadeFactor = if (isLitEdge) 0.56f else 0.36f
                    val anchorMix = if (isLitEdge) 0.28f else 0.52f
                    val or = ((nr * shadeFactor) * (1f - anchorMix) + ar * anchorMix).toInt().coerceIn(0, 255)
                    val og = ((ng * shadeFactor) * (1f - anchorMix) + ag * anchorMix).toInt().coerceIn(0, 255)
                    val ob = ((nb * shadeFactor) * (1f - anchorMix) + ab * anchorMix).toInt().coerceIn(0, 255)
                    out.setPixel(x, y, PaletteRamp.rgba(or, og, ob, 255))
                }
            }
        }
        return out
    }

    private fun tintJrpgTone(rgba: Int, luma: Float): Int {
        val a = rgba and 0xFF
        val r = (rgba ushr 24) and 0xFF
        val g = (rgba ushr 16) and 0xFF
        val b = (rgba ushr 8) and 0xFF
        return if (luma < 0.42f) {
            // Shift shadows slightly toward deep indigo/plum
            val nr = (r * 0.92f + 36f * 0.08f).toInt().coerceIn(0, 255)
            val ng = (g * 0.90f + 28f * 0.10f).toInt().coerceIn(0, 255)
            val nb = (b * 0.94f + 58f * 0.12f).toInt().coerceIn(0, 255)
            PaletteRamp.rgba(nr, ng, nb, a)
        } else {
            // Warm sunlit midtones and highlights
            val nr = (r * 1.03f + 6f).toInt().coerceIn(0, 255)
            val ng = (g * 1.01f + 3f).toInt().coerceIn(0, 255)
            val nb = (b * 0.97f).toInt().coerceIn(0, 255)
            PaletteRamp.rgba(nr, ng, nb, a)
        }
    }

    private fun tintPaintedTone(rgba: Int, adjustedLuma: Float): Int {
        val a = rgba and 0xFF
        val r = (rgba ushr 24) and 0xFF
        val g = (rgba ushr 16) and 0xFF
        val b = (rgba ushr 8) and 0xFF
        return if (adjustedLuma > 0.55f) {
            val boost = ((adjustedLuma - 0.55f) * 28f).toInt()
            PaletteRamp.rgba(
                (r + boost).coerceIn(0, 255),
                (g + boost).coerceIn(0, 255),
                (b + (boost * 0.85f).toInt()).coerceIn(0, 255),
                a
            )
        } else {
            val cool = ((0.55f - adjustedLuma) * 18f).toInt()
            PaletteRamp.rgba(
                (r - cool).coerceIn(0, 255),
                (g - cool).coerceIn(0, 255),
                (b - (cool / 2)).coerceIn(0, 255),
                a
            )
        }
    }
}
