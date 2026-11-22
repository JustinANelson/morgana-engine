package com.morgana.domain.explore

import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.TileAttribute

/**
 * A small picture of a real map for the world map window: one character per block of tiles saying what the ground is,
 * built from the map's own tiles and attributes (the same reading the minimap uses). Pure, so the server can build it and
 * the client can draw it without either holding the other's rules.
 */
object WorldMapTerrain {
    const val GRASS = 'g'
    const val WATER = 'w'
    const val BLOCKED = 'b'
    const val WARP = 'p'

    /** No thumbnail is wider or taller than this many blocks, so a 140 x 140 map costs about as much as a 64 x 64 one. */
    const val MAX_SIDE = 64

    /** [cells] holds [rows] rows of [cols] characters, top row first; each character stands for [scale] x [scale] tiles. */
    data class Thumbnail(val scale: Int, val cols: Int, val rows: Int, val cells: String) {
        fun at(col: Int, row: Int): Char = if (col in 0 until cols && row in 0 until rows) cells[row * cols + col] else GRASS
    }

    /** What one tile is: a warp, a wall or other block, water, or open ground. */
    fun classify(cell: MapCell?): Char = when {
        cell == null -> GRASS
        cell.attribute is TileAttribute.Warp -> WARP
        cell.attribute is TileAttribute.Blocked -> BLOCKED
        cell.layers[MapLayer.GROUND]?.srcTileX == 3 -> WATER
        else -> GRASS
    }

    fun thumbnail(map: MapDefinition): Thumbnail {
        val scale = ((maxOf(map.width, map.height) + MAX_SIDE - 1) / MAX_SIDE).coerceAtLeast(1)
        val cols = (map.width + scale - 1) / scale
        val rows = (map.height + scale - 1) / scale
        val out = StringBuilder(cols * rows)
        for (row in 0 until rows) for (col in 0 until cols) {
            var water = 0
            var blocked = 0
            var grass = 0
            var warp = false
            for (ty in row * scale until minOf((row + 1) * scale, map.height)) {
                for (tx in col * scale until minOf((col + 1) * scale, map.width)) {
                    when (classify(map.getCell(tx, ty))) {
                        WARP -> warp = true
                        BLOCKED -> blocked++
                        WATER -> water++
                        else -> grass++
                    }
                }
            }
            // A warp always shows (it is a place to go); otherwise the most common ground wins.
            out.append(
                when {
                    warp -> WARP
                    blocked >= water && blocked > grass -> BLOCKED
                    water > grass -> WATER
                    else -> GRASS
                }
            )
        }
        return Thumbnail(scale, cols, rows, out.toString())
    }
}
