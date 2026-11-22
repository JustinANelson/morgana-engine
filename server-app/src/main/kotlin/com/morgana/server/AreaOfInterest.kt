package com.morgana.server

import kotlin.math.abs

/**
 * Area-of-interest buckets: a map is cut into square cells of [cellTiles] tiles, and a player is sent the changes of
 * everything in their own cell and the [radiusCells] cells around it, instead of every change on the whole map. Pure
 * arithmetic (no state, no I/O), so the rules are unit-tested; the coordinator applies them to its broadcasts.
 */
class AreaOfInterest(val cellTiles: Int = DEFAULT_CELL_TILES, val radiusCells: Int = DEFAULT_RADIUS_CELLS) {
    init {
        require(cellTiles >= 1) { "cellTiles must be at least 1" }
        require(radiusCells >= 0) { "radiusCells must not be negative" }
    }

    /** The cell column or row that tile coordinate [tile] falls in. */
    fun cell(tile: Int): Int = Math.floorDiv(tile, cellTiles)

    /** Whether something on tile ([entityX], [entityY]) is inside the area of a viewer standing on ([viewerX], [viewerY]). */
    fun sees(viewerX: Int, viewerY: Int, entityX: Int, entityY: Int): Boolean =
        abs(cell(viewerX) - cell(entityX)) <= radiusCells && abs(cell(viewerY) - cell(entityY)) <= radiusCells

    /** Whether the viewer's own cell differs between the two tiles (the area has moved). */
    fun cellChanged(oldX: Int, oldY: Int, newX: Int, newY: Int): Boolean =
        cell(oldX) != cell(newX) || cell(oldY) != cell(newY)

    /**
     * Whether a tile is covered by the area of a viewer who has just moved from ([oldX], [oldY]) to ([newX], [newY])
     * but was not covered before: what the viewer must be told about when their area moves.
     */
    fun newlyCovered(oldX: Int, oldY: Int, newX: Int, newY: Int, entityX: Int, entityY: Int): Boolean =
        sees(newX, newY, entityX, entityY) && !sees(oldX, oldY, entityX, entityY)

    companion object {
        const val DEFAULT_CELL_TILES = 30
        const val DEFAULT_RADIUS_CELLS = 1
    }
}
