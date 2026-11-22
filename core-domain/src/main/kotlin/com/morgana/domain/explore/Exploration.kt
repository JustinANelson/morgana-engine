package com.morgana.domain.explore

import kotlinx.serialization.Serializable

/**
 * Which parts of one map a character has seen, as a bit per [Exploration.CHUNK] x [Exploration.CHUNK] block of tiles,
 * packed 64 to a word ([chunkBits]; bit `cy * chunksAcross + cx`). A 50 x 50 map is 169 chunks, three words.
 */
@Serializable
data class ExploredMap(val chunkBits: List<Long> = emptyList())

/**
 * Pure rules for exploration ("fog of war"): walking reveals the chunks within [VISION_RADIUS] tiles. The server stores one
 * [ExploredMap] per map per character and the client darkens what is not in it. No clock, no randomness.
 */
object Exploration {
    /** Side of a chunk, in tiles. */
    const val CHUNK = 4

    /** How far a character sees, in tiles (a chunk counts as seen when any of its tiles is this close). */
    const val VISION_RADIUS = 7

    fun chunksAcross(tiles: Int): Int = (tiles + CHUNK - 1) / CHUNK

    fun chunkCount(width: Int, height: Int): Int = chunksAcross(width) * chunksAcross(height)

    /** Whether the chunk at ([chunkX], [chunkY]) is seen. A null [map] (never visited) has seen nothing. */
    fun isChunkExplored(map: ExploredMap?, width: Int, chunkX: Int, chunkY: Int): Boolean {
        if (map == null || chunkX < 0 || chunkY < 0) return false
        val across = chunksAcross(width)
        if (chunkX >= across) return false
        val index = chunkY * across + chunkX
        val word = map.chunkBits.getOrNull(index / 64) ?: return false
        return (word ushr (index % 64)) and 1L == 1L
    }

    fun isTileExplored(map: ExploredMap?, width: Int, tileX: Int, tileY: Int): Boolean =
        isChunkExplored(map, width, Math.floorDiv(tileX, CHUNK), Math.floorDiv(tileY, CHUNK))

    /**
     * [existing] plus everything within [radius] tiles of ([tileX], [tileY]) on a map of [width] x [height] tiles, or null
     * when that adds nothing (so the caller can skip saving and sending).
     */
    fun reveal(existing: ExploredMap?, width: Int, height: Int, tileX: Int, tileY: Int, radius: Int = VISION_RADIUS): ExploredMap? {
        if (width <= 0 || height <= 0) return null
        val across = chunksAcross(width)
        val down = chunksAcross(height)
        val words = LongArray((across * down + 63) / 64)
        existing?.chunkBits?.forEachIndexed { i, w -> if (i < words.size) words[i] = w }
        var changed = false
        val firstX = Math.floorDiv(tileX - radius, CHUNK).coerceAtLeast(0)
        val lastX = Math.floorDiv(tileX + radius, CHUNK).coerceAtMost(across - 1)
        val firstY = Math.floorDiv(tileY - radius, CHUNK).coerceAtLeast(0)
        val lastY = Math.floorDiv(tileY + radius, CHUNK).coerceAtMost(down - 1)
        for (cy in firstY..lastY) for (cx in firstX..lastX) {
            val left = cx * CHUNK
            val top = cy * CHUNK
            val dx = maxOf(0, left - tileX, tileX - (left + CHUNK - 1))
            val dy = maxOf(0, top - tileY, tileY - (top + CHUNK - 1))
            if (dx * dx + dy * dy > radius * radius) continue
            val index = cy * across + cx
            val bit = 1L shl (index % 64)
            if (words[index / 64] and bit == 0L) {
                words[index / 64] = words[index / 64] or bit
                changed = true
            }
        }
        if (!changed) return null
        return ExploredMap(words.toList())
    }

    /** How many chunks are seen. */
    fun exploredChunks(map: ExploredMap?): Int = map?.chunkBits?.sumOf { java.lang.Long.bitCount(it) } ?: 0

    /** The seen share of a [width] x [height] map, 0..100. */
    fun percent(map: ExploredMap?, width: Int, height: Int): Int {
        val total = chunkCount(width, height)
        return if (total == 0) 0 else (exploredChunks(map) * 100 / total).coerceIn(0, 100)
    }
}
