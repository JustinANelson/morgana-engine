package com.morgana.mapbuilder

import kotlin.math.floor

/**
 * Seeded gradient noise for terrain: smooth, isotropic hills rather than the blocky look of value noise. Everything here is a pure
 * function of its arguments, so a recipe always compiles to the same map.
 */
internal object NoiseField {

    private val GRADIENTS = arrayOf(
        1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f,
        0.7071f to 0.7071f, -0.7071f to 0.7071f, 0.7071f to -0.7071f, -0.7071f to -0.7071f
    )

    private fun fade(t: Float) = t * t * t * (t * (t * 6f - 15f) + 10f)

    /** One octave of gradient noise at ([x], [y]), roughly -0.7..0.7. */
    fun gradient(x: Float, y: Float, seed: Long): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        fun dot(ix: Int, iy: Int, dx: Float, dy: Float): Float {
            val g = GRADIENTS[MapCompiler.hash(ix, iy, seed) % GRADIENTS.size]
            return g.first * dx + g.second * dy
        }
        val u = fade(fx)
        val v = fade(fy)
        val a = dot(x0, y0, fx, fy) + (dot(x0 + 1, y0, fx - 1, fy) - dot(x0, y0, fx, fy)) * u
        val b = dot(x0, y0 + 1, fx, fy - 1) + (dot(x0 + 1, y0 + 1, fx - 1, fy - 1) - dot(x0, y0 + 1, fx, fy - 1)) * u
        return a + (b - a) * v
    }

    /** Fractal noise: [octaves] layers of [gradient], each twice as fine and [persistence] as strong as the last. */
    fun fbm(x: Float, y: Float, seed: Long, octaves: Int, persistence: Float): Float {
        var sum = 0f
        var amp = 1f
        var freq = 1f
        var norm = 0f
        for (o in 0 until octaves.coerceIn(1, 8)) {
            sum += gradient(x * freq, y * freq, seed + o * 7919L) * amp
            norm += amp
            amp *= persistence
            freq *= 2f
        }
        return sum / norm
    }

    /**
     * A field over a [w] x [h] map: [scale] tiles per feature, bent by [warp] tiles of domain warping (which turns straight ridges
     * and round blobs into winding ones), stretched to span exactly 0..1, with [edge] tiles of falloff at the map edge that pushes
     * the value by [edgeBias] (negative sinks the edge, for a coast; positive raises it, for a ring of mountains).
     */
    fun field(w: Int, h: Int, seed: Long, scale: Float, octaves: Int, persistence: Float, warp: Float): FloatArray {
        val out = FloatArray(w * h)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (y in 0 until h) for (x in 0 until w) {
            var sx = x / scale
            var sy = y / scale
            if (warp > 0f) {
                val wx = gradient(x / (scale * 1.7f) + 31.7f, y / (scale * 1.7f) + 11.3f, seed + 101L)
                val wy = gradient(x / (scale * 1.7f) - 17.9f, y / (scale * 1.7f) + 47.1f, seed + 202L)
                sx += wx * warp / scale
                sy += wy * warp / scale
            }
            val v = fbm(sx, sy, seed, octaves, persistence)
            out[y * w + x] = v
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val span = (hi - lo).coerceAtLeast(1e-6f)
        for (i in out.indices) out[i] = (out[i] - lo) / span
        return out
    }

    /** [passes] rounds of a 3 x 3 box blur (edges clamp), then stretched back to span 0..1 so the thresholds keep their meaning. */
    fun smooth(f: FloatArray, w: Int, h: Int, passes: Int): FloatArray {
        var cur = f
        repeat(passes) {
            val next = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0f
                for (dy in -1..1) for (dx in -1..1) sum += cur[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                next[y * w + x] = sum / 9f
            }
            cur = next
        }
        if (passes > 0) {
            val lo = cur.min()
            val span = (cur.max() - lo).coerceAtLeast(1e-6f)
            cur = FloatArray(cur.size) { (cur[it] - lo) / span }
        }
        return cur
    }

    /** The edge falloff of [field], applied after smoothing so the border stays crisp. */
    fun applyEdge(out: FloatArray, w: Int, h: Int, edge: Float, edgeBias: Float) {
        if (edge > 0f && edgeBias != 0f) {
            for (y in 0 until h) for (x in 0 until w) {
                val d = minOf(minOf(x, w - 1 - x), minOf(y, h - 1 - y)).toFloat()
                if (d < edge) {
                    val k = 1f - d / edge
                    out[y * w + x] = (out[y * w + x] + edgeBias * k * k).coerceIn(0f, 1f)
                }
            }
        }
    }
}

/** The fields of a [WorldGenOp] at one world tile: a pure function of the op and the tile, so every map agrees along its borders. */
internal object WorldNoise {
    fun sample(op: WorldGenOp, wx: Int, wy: Int): Map<String, Float> {
        val out = LinkedHashMap<String, Float>()
        for ((name, f) in op.fields) {
            var sx = wx.toFloat() / f.scale
            var sy = wy.toFloat() / f.scale
            val seed = op.seed + f.salt * 7919L
            if (f.warp > 0f) {
                val k = f.scale * 1.7f
                sx += NoiseField.gradient(wx / k + 31.7f, wy / k + 11.3f, seed + 101L) * f.warp / f.scale
                sy += NoiseField.gradient(wx / k - 17.9f, wy / k + 47.1f, seed + 202L) * f.warp / f.scale
            }
            val raw = NoiseField.fbm(sx, sy, seed, f.octaves, f.persistence)
            var v = if (f.ridged) 1f - kotlin.math.abs(raw * f.ridgeWidth) else 0.5f + raw * f.gain
            v += f.tiltX * (wx.toFloat() / op.worldWidth.coerceAtLeast(1) - 0.5f) + f.tiltY * (wy.toFloat() / op.worldHeight.coerceAtLeast(1) - 0.5f)
            for ((other, weight) in f.mix) v += weight * (out[other] ?: 0f)
            for (b in f.bumps) {
                val dx = (wx - b.x).toFloat()
                val dy = (wy - b.y).toFloat()
                val d2 = dx * dx + dy * dy
                val r2 = b.radius.toFloat() * b.radius
                if (d2 < r2) { val k = 1f - d2 / r2; v += b.amount * k * k }
            }
            if (f.edge > 0f && f.edgeBias != 0f) {
                val d = minOf(minOf(wx, op.worldWidth - 1 - wx), minOf(wy, op.worldHeight - 1 - wy)).toFloat()
                if (d < f.edge) { val k = 1f - d / f.edge; v += f.edgeBias * k * k }
            }
            out[name] = v.coerceIn(0f, 1f)
        }
        return out
    }
}
