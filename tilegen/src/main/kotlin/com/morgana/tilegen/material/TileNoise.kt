package com.morgana.tilegen.material

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Pure-Kotlin periodic (toroidal) noise primitives for seamless pixel-art tile generation
 * at any tile size.
 */
object TileNoise {

    /**
     * Deterministic integer-coordinate hash returning a value in `[0, 1]`.
     */
    fun hash(x: Int, y: Int, seed: Long): Float {
        var h = seed xor (x.toLong() * 374761393L) xor (y.toLong() * 668265263L)
        h = (h xor (h ushr 16)) * 0x85ebca6bL
        h = (h xor (h ushr 13)) * 0xc2b2ae35L
        return ((h xor (h ushr 16)) and 0x7FFFFFFFL).toFloat() / 0x7FFFFFFFL.toFloat()
    }

    /**
     * Smoothly interpolated 2D value noise that wraps seamlessly at [period].
     */
    fun wrappedNoise2D(x: Float, y: Float, period: Int, seed: Long): Float {
        require(period > 0) { "period must be positive, got $period" }
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val xf = x - xi
        val yf = y - yi

        val u = xf * xf * (3f - 2f * xf)
        val v = yf * yf * (3f - 2f * yf)

        val x0 = Math.floorMod(xi, period)
        val y0 = Math.floorMod(yi, period)
        val x1 = Math.floorMod(xi + 1, period)
        val y1 = Math.floorMod(yi + 1, period)

        val c00 = hash(x0, y0, seed)
        val c10 = hash(x1, y0, seed)
        val c01 = hash(x0, y1, seed)
        val c11 = hash(x1, y1, seed)

        val nx0 = c00 + u * (c10 - c00)
        val nx1 = c01 + u * (c11 - c01)
        return nx0 + v * (nx1 - nx0)
    }

    /**
     * Multi-octave periodic fractional Brownian motion wrapping seamlessly at [period].
     */
    fun wrappedFbm(
        x: Float,
        y: Float,
        period: Int,
        octaves: Int = 3,
        persistence: Float = 0.5f,
        seed: Long
    ): Float {
        var total = 0f
        var frequency = 1f
        var amplitude = 1f
        var maxVal = 0f
        for (i in 0 until octaves) {
            val octavePeriod = (period * frequency).roundToInt().coerceAtLeast(1)
            total += wrappedNoise2D(
                x * frequency,
                y * frequency,
                octavePeriod,
                seed + i * 7919L
            ) * amplitude
            maxVal += amplitude
            amplitude *= persistence
            frequency *= 2f
        }
        return if (maxVal > 0f) total / maxVal else 0f
    }

    /**
     * Periodic Voronoi cellular distance over `[0, period) x [0, period)`.
     * Returns `(dist1, dist2, cellHash)`.
     */
    fun periodicVoronoi(
        x: Float,
        y: Float,
        period: Int,
        cells: Int = 4,
        seed: Long
    ): Triple<Float, Float, Float> {
        require(period > 0) { "period must be positive, got $period" }
        require(cells > 0) { "cells must be positive, got $cells" }
        val cellW = period.toFloat() / cells
        val gx = floor(x / cellW).toInt()
        val gy = floor(y / cellW).toInt()

        var minDist1 = Float.MAX_VALUE
        var minDist2 = Float.MAX_VALUE
        var closestHash = 0f

        for (dy in -1..1) {
            for (dx in -1..1) {
                val cx = Math.floorMod(gx + dx, cells)
                val cy = Math.floorMod(gy + dy, cells)
                val jx = hash(cx, cy, seed) * 0.7f + 0.15f
                val jy = hash(cx, cy, seed + 997L) * 0.7f + 0.15f

                val fx = (gx + dx + jx) * cellW
                val fy = (gy + dy + jy) * cellW
                val d = hypot(x - fx, y - fy) / cellW

                if (d < minDist1) {
                    minDist2 = minDist1
                    minDist1 = d
                    closestHash = hash(cx, cy, seed + 3331L)
                } else if (d < minDist2) {
                    minDist2 = d
                }
            }
        }
        return Triple(minDist1, minDist2, closestHash)
    }
}
