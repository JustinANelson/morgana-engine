package com.morgana.client.render

/** Half-open ranges of the tile columns and rows (tile y, top row first) that overlap a view rectangle. */
data class VisibleTiles(val firstCol: Int, val lastCol: Int, val firstRow: Int, val lastRow: Int) {
    companion object {
        /**
         * The tiles of a [width] x [height] map (tile size [ts], drawn at [offsetX]/[offsetY] with render y running up from
         * the bottom row) that touch the world rectangle [left]..[right] x [bottom]..[top]. Callers add their own margin.
         */
        fun of(
            width: Int, height: Int, ts: Int, offsetX: Float, offsetY: Float,
            left: Float, right: Float, bottom: Float, top: Float
        ): VisibleTiles {
            val firstCol = Math.floorDiv((left - offsetX).toInt(), ts).coerceIn(0, width)
            val lastCol = (Math.floorDiv((right - offsetX).toInt(), ts) + 1).coerceIn(0, width)
            // Render y is (height - 1 - ty) * ts, so higher on screen means a smaller tile y.
            val firstRow = (height - 1 - Math.floorDiv((top - offsetY).toInt(), ts)).coerceIn(0, height)
            val lastRow = (height - 1 - Math.floorDiv((bottom - offsetY).toInt(), ts) + 1).coerceIn(0, height)
            return VisibleTiles(firstCol, lastCol, firstRow, lastRow)
        }
    }
}
