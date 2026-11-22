package com.morgana.tilegen.structure

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelDither
import com.morgana.tilegen.material.TileNoise
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Town paving drawn in the soft map style: rounded stones with dark mortar, light from the upper left, muted hue-shifted
 * colour and a few mossy gaps. Every tile is seamless (the stone layout wraps at 32 px), and each kind comes in several
 * variants so a big road does not visibly repeat; a map picks a variant per cell with a position hash.
 */
enum class PavingKind(val cells: Int, val jitter: Float, val label: String) {
    /** Pale grey cobble for plazas and courtyards. */
    COBBLE_PALE(5, 0.55f, "Pale cobble"),

    /** Large warm flagstones, nearly square. */
    FLAGSTONE_WARM(3, 0.12f, "Warm flagstone"),

    /** Dark slate cobble for roads. */
    COBBLE_ROAD(5, 0.50f, "Road cobble"),

    /** Dark red-brown cobble for the fortress town. */
    COBBLE_EMBER(5, 0.50f, "Ember cobble")
}

class PavingGenerator(val seed: Long = 1337L, val size: Int = 32) {
    private fun natural(r: Int, g: Int, b: Int) =
        PaletteRamp.fromBaseColor(PaletteRamp.rgba(r, g, b), steps = 6, hueShift = 0.05f, valueSpread = 0.30f)

    private fun ramp(kind: PavingKind) = when (kind) {
        PavingKind.COBBLE_PALE -> natural(190, 186, 170)
        PavingKind.FLAGSTONE_WARM -> natural(196, 176, 146)
        PavingKind.COBBLE_ROAD -> natural(104, 112, 128)
        PavingKind.COBBLE_EMBER -> natural(134, 84, 74)
    }

    private val moss = natural(88, 128, 76)

    /** [variants] seamless tiles of [kind]. */
    fun generate(kind: PavingKind, variants: Int = 4): List<NamedTile> =
        (0 until variants).map { v -> NamedTile("${kind.name.lowercase()}_$v", tile(kind, v)) }

    private fun tile(kind: PavingKind, variant: Int): TileImage {
        val n = kind.cells
        val s = seed + kind.ordinal * 977L + variant * 131L
        val pal = ramp(kind)
        val cell = size.toFloat() / n
        // One jittered point per cell; the grid wraps so the tile repeats without a seam.
        val px = FloatArray(n * n)
        val py = FloatArray(n * n)
        for (cy in 0 until n) for (cx in 0 until n) {
            px[cy * n + cx] = (cx + 0.5f + (TileNoise.hash(cx, cy, s) - 0.5f) * kind.jitter * 2f) * cell
            py[cy * n + cx] = (cy + 0.5f + (TileNoise.hash(cx, cy, s + 1L) - 0.5f) * kind.jitter * 2f) * cell
        }
        val img = TileImage(size, size)
        val mossy = variant >= 2
        for (y in 0 until size) for (x in 0 until size) {
            var d1 = Float.MAX_VALUE
            var d2 = Float.MAX_VALUE
            var best = 0
            var bestDx = 0f
            var bestDy = 0f
            val gx = floor((x + 0.5f) / cell).toInt()
            val gy = floor((y + 0.5f) / cell).toInt()
            for (oy in -1..1) for (ox in -1..1) {
                val ux = gx + ox
                val uy = gy + oy
                val wi = Math.floorMod(uy, n) * n + Math.floorMod(ux, n)
                // the neighbour's point, shifted by whole tiles when the neighbour lies outside it
                val ptx = px[wi] + floor(ux.toFloat() / n) * size
                val pty = py[wi] + floor(uy.toFloat() / n) * size
                val dx = x + 0.5f - ptx
                val dy = y + 0.5f - pty
                val d = if (kind == PavingKind.FLAGSTONE_WARM) maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) else hypot(dx, dy)
                if (d < d1) { d2 = d1; d1 = d; best = wi; bestDx = dx; bestDy = dy } else if (d < d2) d2 = d
            }
            val edge = d2 - d1
            val gap = if (kind == PavingKind.FLAGSTONE_WARM) 1.3f else 1.1f
            val col: Int
            if (edge < gap) {
                val deep = edge < gap * 0.55f
                col = if (mossy && TileNoise.hash(x, y, s + 9L) < 0.30f && !deep) PixelDither.sampleDithered(moss, 0.45f, x, y, 4, 0.1f)
                else PixelDither.sampleDithered(pal, if (deep) 0.10f else 0.22f, x, y, 4, 0.1f)
            } else {
                // stone: its own base tone, lit from the upper left, darker toward its lower-right rim
                val base = 0.50f + (TileNoise.hash(best % n, best / n, s + 3L) - 0.5f) * 0.26f
                val rim = (d1 / (cell * 0.6f)).coerceIn(0f, 1f)
                val light = -bestDx * 0.012f - bestDy * 0.014f
                val grain = (TileNoise.hash(x, y, s + 5L) - 0.5f) * 0.07f
                val edgeLight = if (edge < gap + 1.0f) (if (bestDx + bestDy < 0f) 0.14f else -0.10f) else 0f
                col = PixelDither.sampleDithered(pal, (base + light + grain + edgeLight - rim * 0.06f).coerceIn(0.1f, 0.95f), x, y, 4, 0.12f)
            }
            img.setPixel(x, y, col)
        }
        return img
    }
}
