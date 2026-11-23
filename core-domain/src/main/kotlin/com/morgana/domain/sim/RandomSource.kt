package com.morgana.domain.sim

/**
 * Deterministic pseudo-random source used by core simulation logic.
 * Avoids unseeded randomness so every simulation tick is 100% reproducible in tests.
 */
interface RandomSource {
    /** Opaque value summarising the generator's internal state; used by replay checkpoints. */
    val stateFingerprint: Long get() = 0L

    fun nextInt(bound: Int): Int

    fun nextIntInclusive(min: Int, max: Int): Int {
        if (max <= min) return min
        return min + nextInt((max - min) + 1)
    }

    /**
     * Rolls against a basis-point probability (10000 = 100%, 2500 = 25%).
     */
    fun rollBasisPoints(thresholdBasisPoints: Int): Boolean {
        if (thresholdBasisPoints <= 0) return false
        if (thresholdBasisPoints >= 10_000) return true
        return nextInt(10_000) < thresholdBasisPoints
    }

    /** Rolls against a parts-per-million probability (1,000,000 = 100%, 20 = 1 in 50,000). */
    fun rollPerMillion(thresholdPerMillion: Long): Boolean {
        if (thresholdPerMillion <= 0L) return false
        if (thresholdPerMillion >= 1_000_000L) return true
        return nextInt(1_000_000) < thresholdPerMillion
    }
}

/**
 * Fast, deterministic XorShift64* RNG implementation.
 */
class SeededRandomSource(seed: Long = 0x5DEECE66DL) : RandomSource {
    private var state: Long = if (seed == 0L) 0x5CAFEBABE1234567L else seed

    override val stateFingerprint: Long get() = state

    private fun nextLong(): Long {
        var x = state
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        state = x
        return x * 2685821657736338717L
    }

    override fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive (got $bound)" }
        val positive = (nextLong() ushr 1)
        return (positive % bound.toLong()).toInt()
    }
}
