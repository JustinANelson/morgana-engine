package com.morgana.tilegen.material

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Ordered pixel-art color ramp from darkest shadow (`index 0`) to brightest highlight (`lastIndex`).
 * Colors are packed RGBA8888 integers (`0xRRGGBBAA`).
 */
class PaletteRamp(val colors: IntArray) {
    init {
        require(colors.size >= 2) { "PaletteRamp requires at least 2 colors, got ${colors.size}" }
    }

    val shadow: Int get() = colors.first()
    val mid: Int get() = colors[colors.size / 2]
    val highlight: Int get() = colors.last()

    /**
     * Quantized pixel-art lookup for [t] in `[0, 1]`. Preserves crisp palette bands.
     */
    fun sample(t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        val idx = (clamped * (colors.size - 1)).roundToInt().coerceIn(0, colors.lastIndex)
        return colors[idx]
    }

    /**
     * Returns a copy of this ramp with alpha overridden on every stop.
     */
    fun withAlpha(alpha: Int): PaletteRamp {
        val a = alpha.coerceIn(0, 255)
        return PaletteRamp(IntArray(colors.size) { i -> (colors[i] and 0xFFFFFF00.toInt()) or a })
    }

    /**
     * Finds the nearest color in this ramp to [rgba] (preserving [rgba]'s alpha channel).
     */
    fun quantize(rgba: Int): Int {
        val a = rgba and 0xFF
        if (a == 0) return 0
        val r = (rgba ushr 24) and 0xFF
        val g = (rgba ushr 16) and 0xFF
        val b = (rgba ushr 8) and 0xFF

        var bestColor = colors[0]
        var bestDist = Int.MAX_VALUE
        for (c in colors) {
            val cr = (c ushr 24) and 0xFF
            val cg = (c ushr 16) and 0xFF
            val cb = (c ushr 8) and 0xFF
            val dr = r - cr
            val dg = g - cg
            val db = b - cb
            // Weighted Euclidean distance reflecting human luminance sensitivity
            val dist = dr * dr * 3 + dg * dg * 4 + db * db * 2
            if (dist < bestDist) {
                bestDist = dist
                bestColor = c
            }
        }
        return (bestColor and 0xFFFFFF00.toInt()) or a
    }

    /**
     * Subsamples this ramp to at most [maxColors] evenly spaced stops (preserving endpoints).
     */
    fun limitColors(maxColors: Int): PaletteRamp {
        require(maxColors >= 2) { "maxColors must be >= 2, got $maxColors" }
        if (colors.size <= maxColors) return this
        val out = IntArray(maxColors) { idx ->
            val srcIdx = ((idx.toFloat() / (maxColors - 1)) * (colors.lastIndex)).roundToInt()
            colors[srcIdx.coerceIn(0, colors.lastIndex)]
        }
        return PaletteRamp(out)
    }

    override fun equals(other: Any?): Boolean =
        other is PaletteRamp && colors.contentEquals(other.colors)

    override fun hashCode(): Int = colors.contentHashCode()

    companion object {
        fun rgba(r: Int, g: Int, b: Int, a: Int = 255): Int =
            ((r and 0xFF) shl 24) or ((g and 0xFF) shl 16) or ((b and 0xFF) shl 8) or (a and 0xFF)

        /**
         * Generates a hue-shifted pixel-art ramp around [baseRgba].
         * Shadows shift cooler (toward blue/violet) and darker; highlights shift warmer and brighter.
         */
        fun fromBaseColor(
            baseRgba: Int,
            steps: Int = 5,
            hueShift: Float = 0.06f,
            valueSpread: Float = 0.35f
        ): PaletteRamp {
            require(steps >= 2) { "steps must be >= 2, got $steps" }
            val r = ((baseRgba ushr 24) and 0xFF) / 255f
            val g = ((baseRgba ushr 16) and 0xFF) / 255f
            val b = ((baseRgba ushr 8) and 0xFF) / 255f
            val a = baseRgba and 0xFF
            val (h, s, v) = rgbToHsv(r, g, b)

            val out = IntArray(steps) { idx ->
                val norm = if (steps == 1) 0f else (idx.toFloat() / (steps - 1)) * 2f - 1f // -1..+1
                // Cool shift in shadows (toward 0.65 blue-violet), warm shift in highlights (toward 0.14 yellow-gold)
                val targetHue = if (norm < 0f) 0.66f else 0.14f
                val shiftedHue = lerpHue(h, targetHue, abs(norm) * hueShift * 2f)
                val shiftedSat = (s + (if (norm < 0f) 0.10f else -0.08f) * abs(norm)).coerceIn(0f, 1f)
                val shiftedVal = (v + norm * valueSpread).coerceIn(0.06f, 0.98f)
                val (rr, gg, bb) = hsvToRgb(shiftedHue, shiftedSat, shiftedVal)
                rgba(
                    (rr * 255f).roundToInt(),
                    (gg * 255f).roundToInt(),
                    (bb * 255f).roundToInt(),
                    a
                )
            }
            return PaletteRamp(out)
        }

        private fun lerpHue(h1: Float, h2: Float, t: Float): Float {
            var diff = h2 - h1
            if (diff > 0.5f) diff -= 1f
            if (diff < -0.5f) diff += 1f
            val res = h1 + diff * t.coerceIn(0f, 1f)
            return ((res % 1f) + 1f) % 1f
        }

        private fun rgbToHsv(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val d = max - min
            val h = when {
                d == 0f -> 0f
                max == r -> (((g - b) / d) % 6f + 6f) % 6f / 6f
                max == g -> ((b - r) / d + 2f) / 6f
                else -> ((r - g) / d + 4f) / 6f
            }
            val s = if (max == 0f) 0f else d / max
            return Triple(h, s, max)
        }

        private fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Float, Float, Float> {
            val hh = ((h % 1f) + 1f) % 1f * 6f
            val i = hh.toInt()
            val f = hh - i
            val p = v * (1f - s)
            val q = v * (1f - f * s)
            val t = v * (1f - (1f - f) * s)
            return when (i % 6) {
                0 -> Triple(v, t, p)
                1 -> Triple(q, v, p)
                2 -> Triple(p, v, t)
                3 -> Triple(p, q, v)
                4 -> Triple(t, p, v)
                else -> Triple(v, p, q)
            }
        }
    }
}

/**
 * Curated pixel-art palette ramps for common 2D game biomes and materials.
 */
object PalettePresets {
    private fun c(r: Int, g: Int, b: Int) = PaletteRamp.rgba(r, g, b)

    val MEADOW_GRASS = PaletteRamp(intArrayOf(c(25, 64, 36), c(46, 111, 64), c(59, 133, 80), c(88, 175, 110), c(118, 204, 138)))
    val AUTUMN_GRASS = PaletteRamp(intArrayOf(c(92, 42, 24), c(148, 72, 32), c(191, 106, 40), c(224, 146, 58), c(245, 188, 92)))
    val EARTH_DIRT = PaletteRamp(intArrayOf(c(61, 38, 21), c(95, 62, 36), c(139, 109, 75), c(163, 131, 94), c(189, 156, 118)))
    val SLATE_STONE = PaletteRamp(intArrayOf(c(36, 32, 30), c(65, 68, 76), c(100, 106, 118), c(136, 143, 158), c(175, 182, 198)))
    val OCEAN_WATER = PaletteRamp(intArrayOf(c(20, 48, 120), c(29, 78, 216), c(59, 130, 246), c(96, 165, 250), c(191, 219, 254)))
    val CALM_WATER = PaletteRamp(intArrayOf(c(26, 62, 98), c(38, 92, 134), c(54, 122, 162), c(82, 154, 186), c(142, 204, 222)))
    val DESERT_SAND = PaletteRamp(intArrayOf(c(156, 122, 68), c(184, 153, 94), c(212, 184, 124), c(227, 204, 148), c(245, 230, 185)))
    val TIMBER_WOOD = PaletteRamp(intArrayOf(c(69, 26, 3), c(96, 42, 12), c(120, 53, 15), c(154, 107, 58), c(180, 130, 80)))
    val FROST_SNOW = PaletteRamp(intArrayOf(c(148, 174, 204), c(186, 205, 224), c(215, 228, 240), c(232, 241, 248), c(250, 253, 255)))
    val LAVA_MAGMA = PaletteRamp(intArrayOf(c(58, 16, 18), c(140, 28, 19), c(220, 68, 22), c(249, 140, 28), c(253, 224, 71)))
    val VOID_METAL = PaletteRamp(intArrayOf(c(18, 24, 38), c(38, 49, 71), c(64, 82, 112), c(102, 128, 164), c(152, 184, 216)))
    val TERRACOTTA_BRICK = PaletteRamp(intArrayOf(c(82, 30, 26), c(128, 48, 38), c(166, 68, 50), c(198, 94, 68), c(224, 130, 96)))

    // Retro 8-bit & Fantasy Console Palettes (4-stop hardware-faithful ramps)
    val GAMEBOY_DMG = PaletteRamp(intArrayOf(c(15, 56, 15), c(48, 98, 48), c(139, 172, 15), c(155, 188, 15)))
    val NES_OVERWORLD = PaletteRamp(intArrayOf(c(0, 0, 0), c(0, 88, 0), c(0, 168, 0), c(184, 248, 24)))
    val NES_DUNGEON = PaletteRamp(intArrayOf(c(0, 0, 0), c(0, 64, 88), c(116, 116, 116), c(188, 188, 188)))
    val PICO8_FOREST = PaletteRamp(intArrayOf(c(0, 0, 0), c(29, 43, 83), c(0, 135, 81), c(0, 228, 54)))
    val PICO8_WATER = PaletteRamp(intArrayOf(c(29, 43, 83), c(41, 173, 255), c(194, 195, 199), c(255, 241, 232)))

    // RMXP / Time Fantasy inspired earthy low-saturation 5-stop RPG ramps
    val TIME_FANTASY_GRASS = PaletteRamp(intArrayOf(c(38, 56, 45), c(58, 92, 62), c(82, 122, 74), c(114, 152, 92), c(150, 182, 118)))
    val TIME_FANTASY_EARTH = PaletteRamp(intArrayOf(c(56, 40, 34), c(88, 64, 52), c(120, 92, 74), c(150, 120, 96), c(178, 150, 124)))
    val TIME_FANTASY_STONE = PaletteRamp(intArrayOf(c(42, 44, 54), c(68, 74, 86), c(98, 106, 118), c(130, 138, 150), c(164, 172, 182)))

    // Time Fantasy Ruin Dungeon palettes (sampled from samples/tf_ruin_dungeon)
    val TF_RUINS_MARBLE = PaletteRamp(intArrayOf(c(43, 38, 50), c(123, 129, 122), c(166, 170, 155), c(199, 200, 184), c(232, 230, 216)))
    val TF_RUINS_SLATE = PaletteRamp(intArrayOf(c(43, 38, 50), c(49, 66, 78), c(77, 105, 120), c(115, 147, 163), c(157, 184, 194)))
    val TF_RUINS_GOLD = PaletteRamp(intArrayOf(c(43, 38, 50), c(125, 86, 32), c(181, 133, 48), c(224, 178, 72), c(242, 210, 114)))
    val TF_RUINS_TERRA = PaletteRamp(intArrayOf(c(43, 38, 50), c(79, 37, 36), c(124, 62, 54), c(168, 93, 76), c(204, 132, 110)))
    val TF_RUINS_BLUE = PaletteRamp(intArrayOf(c(31, 63, 143), c(47, 98, 212), c(86, 136, 242), c(150, 188, 248)))

    // Conquer Online (Wuxia Imperial Dynasty) signature 5-stop palettes
    val CO_IMPERIAL_JADE = PaletteRamp(intArrayOf(c(43, 38, 50), c(18, 78, 58), c(32, 128, 94), c(64, 184, 138), c(154, 242, 198)))
    val CO_DYNASTY_LACQUER = PaletteRamp(intArrayOf(c(43, 38, 50), c(98, 18, 24), c(158, 30, 36), c(212, 56, 48), c(248, 138, 96)))
    val CO_CELESTIAL_GOLD = PaletteRamp(intArrayOf(c(43, 38, 50), c(138, 88, 18), c(198, 142, 32), c(242, 196, 58), c(255, 242, 148)))
    val CO_MYSTIC_SILK = PaletteRamp(intArrayOf(c(43, 38, 50), c(36, 54, 118), c(62, 98, 184), c(108, 158, 232), c(188, 226, 255)))
    val CO_DRAGON_BRONZE = PaletteRamp(intArrayOf(c(43, 38, 50), c(76, 56, 38), c(118, 88, 56), c(164, 126, 78), c(214, 178, 118)))

    // White River / Black Desert Online (Dark Medieval High-Fantasy) signature 5-stop palettes
    val WR_ABYSSAL_OBSIDIAN = PaletteRamp(intArrayOf(c(22, 18, 30), c(38, 30, 52), c(62, 48, 84), c(96, 76, 126), c(148, 124, 184)))
    val WR_BLACK_SPIRIT = PaletteRamp(intArrayOf(c(18, 14, 24), c(56, 16, 28), c(118, 24, 38), c(196, 38, 52), c(252, 98, 92)))
    val WR_SERENDIA_IRON = PaletteRamp(intArrayOf(c(32, 34, 42), c(58, 64, 76), c(92, 100, 116), c(134, 144, 162), c(188, 198, 214)))
    val WR_KAMASYLVE_LEAF = PaletteRamp(intArrayOf(c(28, 48, 42), c(46, 86, 68), c(74, 134, 98), c(118, 186, 136), c(186, 238, 196)))
    val WR_VALENCIA_AMBER = PaletteRamp(intArrayOf(c(48, 34, 24), c(104, 68, 36), c(158, 112, 54), c(206, 158, 82), c(244, 208, 132)))

    // Suikoden II (PS1 32-bit JRPG) 6-stop warm-to-indigo cluster palettes
    val SK_HIGHLAND_GRASS = PaletteRamp(intArrayOf(c(27, 36, 50), c(40, 74, 60), c(62, 114, 74), c(100, 158, 88), c(152, 198, 110), c(200, 228, 142)))
    val SK_KYARO_TIMBER = PaletteRamp(intArrayOf(c(34, 24, 36), c(74, 42, 34), c(120, 70, 48), c(168, 106, 68), c(210, 150, 98), c(236, 192, 142)))
    val SK_MUSE_LIMESTONE = PaletteRamp(intArrayOf(c(36, 32, 48), c(76, 70, 88), c(122, 116, 130), c(174, 168, 174), c(216, 210, 206), c(244, 238, 230)))
    val SK_SCARLET_ROOF = PaletteRamp(intArrayOf(c(38, 22, 36), c(94, 34, 42), c(150, 56, 54), c(200, 88, 72), c(230, 132, 102), c(248, 182, 146)))
    val SK_TRUE_RUNE_GOLD = PaletteRamp(intArrayOf(c(40, 28, 40), c(110, 70, 30), c(173, 120, 44), c(223, 172, 68), c(248, 218, 120), c(255, 246, 184)))

    // Ragnarok Online (Midgard 2.5D Anime-Chibi & Painted Terrain) 6-stop luminous palettes
    val RO_PRONTERA_MARBLE = PaletteRamp(intArrayOf(c(40, 44, 68), c(82, 92, 130), c(136, 150, 184), c(194, 204, 226), c(232, 238, 248), c(255, 255, 255)))
    val RO_PAYON_JADE = PaletteRamp(intArrayOf(c(30, 50, 56), c(50, 94, 84), c(82, 146, 108), c(126, 196, 136), c(180, 230, 168), c(226, 250, 210)))
    val RO_GEFFEN_ARCANE = PaletteRamp(intArrayOf(c(30, 26, 54), c(60, 50, 104), c(100, 82, 162), c(148, 126, 210), c(196, 178, 242), c(236, 226, 255)))
    val RO_MORROC_SANDSTONE = PaletteRamp(intArrayOf(c(54, 34, 40), c(104, 66, 56), c(158, 108, 78), c(206, 158, 110), c(238, 214, 168), c(255, 242, 214)))
    val RO_PORING_PASTEL = PaletteRamp(intArrayOf(c(56, 28, 50), c(120, 48, 88), c(184, 82, 130), c(232, 130, 172), c(248, 182, 210), c(255, 232, 244)))

    // RoboCop / Old Detroit / OCP (1987 Cyber-Industrial RPG) 5-stop palettes
    val RC_OCP_STEEL = PaletteRamp(intArrayOf(c(24, 28, 38), c(56, 68, 88), c(102, 122, 148), c(166, 188, 214), c(228, 242, 255)))
    val RC_OLD_DETROIT = PaletteRamp(intArrayOf(c(22, 20, 26), c(44, 42, 50), c(72, 70, 80), c(108, 104, 114), c(150, 146, 156)))
    val RC_CRT_GREEN = PaletteRamp(intArrayOf(c(14, 30, 20), c(26, 78, 44), c(44, 148, 76), c(88, 224, 124), c(182, 255, 198)))
    val RC_SIREN_BLUE = PaletteRamp(intArrayOf(c(18, 28, 58), c(32, 62, 138), c(54, 118, 220), c(108, 184, 252), c(204, 238, 255)))
    val RC_HAZARD_AMBER = PaletteRamp(intArrayOf(c(42, 26, 16), c(116, 64, 18), c(188, 118, 26), c(244, 184, 44), c(255, 238, 132)))
    val RC_NUKE_CRIMSON = PaletteRamp(intArrayOf(c(36, 14, 22), c(102, 20, 34), c(178, 34, 48), c(236, 72, 76), c(255, 164, 152)))
    val RC_NEON_VIOLET = PaletteRamp(intArrayOf(c(28, 16, 46), c(68, 30, 112), c(122, 54, 188), c(186, 102, 244), c(236, 188, 255)))
    val RC_TOXIC_SLUDGE = PaletteRamp(intArrayOf(c(20, 34, 16), c(44, 88, 24), c(82, 154, 34), c(146, 218, 54), c(218, 252, 128)))
}
