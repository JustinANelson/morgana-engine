package com.morgana.tilegen.autotile

/**
 * Supported autotiling bitmask layouts for 2D tilemaps.
 */
enum class AutotileMode {
    /** 47-tile minimal 3x3 blob autotile (8-way neighbor bitmask with gated corners). */
    BLOB_47,

    /** 16-tile 4-way cardinal autotile (N, E, S, W). */
    CARDINAL_16
}

/**
 * Bitmask constants and canonical lookup tables for 47-tile blob and 16-tile cardinal autotiling.
 *
 * Bit layout for [AutotileMode.BLOB_47]:
 * - `N = 1`, `NE = 2`, `E = 4`, `SE = 8`, `S = 16`, `SW = 32`, `W = 64`, `NW = 128`
 * - A diagonal bit is only active when both adjacent cardinal bits are active
 *   (e.g., `NE` requires both `N` and `E`). This reduces 256 raw states to 47 canonical tiles.
 *
 * Bit layout for [AutotileMode.CARDINAL_16]:
 * - `N = 1`, `E = 2`, `S = 4`, `W = 8`
 */
object AutotileBitmask {
    const val N: Int = 1
    const val NE: Int = 2
    const val E: Int = 4
    const val SE: Int = 8
    const val S: Int = 16
    const val SW: Int = 32
    const val W: Int = 64
    const val NW: Int = 128

    const val CARDINAL_N: Int = 1
    const val CARDINAL_E: Int = 2
    const val CARDINAL_S: Int = 4
    const val CARDINAL_W: Int = 8

    /** All 47 canonical 8-way blob bitmasks in deterministic ascending order. */
    val BLOB_47_MASKS: List<Int> = (0..255).map { canonicalBlobMask(it) }.distinct().sorted()

    /** All 16 cardinal bitmasks (`0..15`). */
    val CARDINAL_16_MASKS: List<Int> = (0..15).toList()

    private val blobMaskToIndex: Map<Int, Int> = BLOB_47_MASKS.withIndex().associate { (i, mask) -> mask to i }

    /**
     * Strips diagonal bits whose adjacent cardinal neighbors are not both set.
     */
    fun canonicalBlobMask(rawMask: Int): Int {
        val m = rawMask and 0xFF
        val cardinals = m and (N or E or S or W)
        var out = cardinals
        if ((cardinals and (N or E)) == (N or E) && (m and NE) != 0) out = out or NE
        if ((cardinals and (E or S)) == (E or S) && (m and SE) != 0) out = out or SE
        if ((cardinals and (S or W)) == (S or W) && (m and SW) != 0) out = out or SW
        if ((cardinals and (W or N)) == (W or N) && (m and NW) != 0) out = out or NW
        return out
    }

    /**
     * Builds a canonical 8-way blob mask from boolean neighbor flags.
     */
    fun blobMask(
        n: Boolean = false,
        ne: Boolean = false,
        e: Boolean = false,
        se: Boolean = false,
        s: Boolean = false,
        sw: Boolean = false,
        w: Boolean = false,
        nw: Boolean = false
    ): Int {
        var raw = 0
        if (n) raw = raw or N
        if (ne) raw = raw or NE
        if (e) raw = raw or E
        if (se) raw = raw or SE
        if (s) raw = raw or S
        if (sw) raw = raw or SW
        if (w) raw = raw or W
        if (nw) raw = raw or NW
        return canonicalBlobMask(raw)
    }

    /**
     * Builds a 4-way cardinal mask from boolean neighbor flags.
     */
    fun cardinalMask(
        n: Boolean = false,
        e: Boolean = false,
        s: Boolean = false,
        w: Boolean = false
    ): Int {
        var mask = 0
        if (n) mask = mask or CARDINAL_N
        if (e) mask = mask or CARDINAL_E
        if (s) mask = mask or CARDINAL_S
        if (w) mask = mask or CARDINAL_W
        return mask
    }

    /**
     * Returns the tile index (`0..46`) for any raw 8-way neighbor bitmask.
     */
    fun blobIndexFor(rawMask: Int): Int =
        blobMaskToIndex.getValue(canonicalBlobMask(rawMask))

    /**
     * Returns the canonical bitmask for a given [index] in `0..46`.
     */
    fun maskForBlobIndex(index: Int): Int {
        require(index in BLOB_47_MASKS.indices) { "Blob index out of range: $index" }
        return BLOB_47_MASKS[index]
    }

    /**
     * Returns a filesystem-safe name describing the active directions in [mask].
     */
    fun describeMask(mask: Int, mode: AutotileMode): String = when (mode) {
        AutotileMode.BLOB_47 -> {
            val m = canonicalBlobMask(mask)
            if (m == 0) "isolated"
            else buildList {
                if ((m and N) != 0) add("n")
                if ((m and NE) != 0) add("ne")
                if ((m and E) != 0) add("e")
                if ((m and SE) != 0) add("se")
                if ((m and S) != 0) add("s")
                if ((m and SW) != 0) add("sw")
                if ((m and W) != 0) add("w")
                if ((m and NW) != 0) add("nw")
            }.joinToString("_")
        }
        AutotileMode.CARDINAL_16 -> {
            val m = mask and 0xF
            if (m == 0) "isolated"
            else buildList {
                if ((m and CARDINAL_N) != 0) add("n")
                if ((m and CARDINAL_E) != 0) add("e")
                if ((m and CARDINAL_S) != 0) add("s")
                if ((m and CARDINAL_W) != 0) add("w")
            }.joinToString("_")
        }
    }
}
