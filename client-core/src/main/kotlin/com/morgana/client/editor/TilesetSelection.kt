package com.morgana.client.editor

/** A block of tiles on a tileset sheet: top-left cell and size, all in tiles. */
data class TileRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * How the tile palette shows a sheet of [sheetCols] x [sheetRows] tiles: always at most [columns] tiles wide (the RPG Maker
 * XP width of 8), with a wider sheet cut into strips of that width stacked one under another. Strip 0 holds sheet columns
 * 0..7 for every row, strip 1 columns 8..15 below it, and so on; the last strip may be narrower. Objects up to [columns]
 * tiles wide that sit inside one strip stay intact, and the palette never needs to scroll sideways.
 *
 * "Display" cells are where a tile appears in the palette; "sheet" cells are where it is in the image.
 */
data class PaletteStrips(val sheetCols: Int, val sheetRows: Int, val columns: Int = TilesetSelection.PALETTE_COLUMNS) {
    init {
        require(sheetCols > 0 && sheetRows > 0 && columns > 0) { "Sheet and palette must have a positive size" }
    }

    /** Number of strips the sheet is cut into. */
    val strips: Int get() = (sheetCols + columns - 1) / columns

    /** Width of the palette in tiles. */
    val displayCols: Int get() = minOf(sheetCols, columns)

    /** Height of the palette in tiles: every strip's rows. */
    val displayRows: Int get() = sheetRows * strips

    /** Tiles across strip [strip] (the last may be narrower than [columns]). */
    fun stripWidth(strip: Int): Int = minOf(columns, sheetCols - strip * columns).coerceAtLeast(0)

    /** The strip a display row belongs to. */
    fun stripOfRow(displayRow: Int): Int = (displayRow / sheetRows).coerceIn(0, strips - 1)

    /** Whether display cell ([dx], [dy]) shows a tile (the last strip may leave its right side empty). */
    fun hasTile(dx: Int, dy: Int): Boolean = dy in 0 until displayRows && dx in 0 until stripWidth(stripOfRow(dy))

    /** The sheet cell shown at display cell ([dx], [dy]). */
    fun toSheet(dx: Int, dy: Int): Pair<Int, Int> {
        val strip = stripOfRow(dy)
        return (strip * columns + dx) to (dy - strip * sheetRows)
    }

    /** Where sheet cell ([sx], [sy]) appears in the palette. */
    fun toDisplay(sx: Int, sy: Int): Pair<Int, Int> {
        val strip = sx / columns
        return (sx - strip * columns) to (strip * sheetRows + sy)
    }

    /**
     * The sheet block a drag from display cell [start] to display cell [end] selects. The block stays inside the strip the
     * drag started in (it cannot span two strips, which are not next to each other on the sheet) and within the stamp size
     * limit.
     */
    fun selection(start: Pair<Int, Int>, end: Pair<Int, Int>, maxSize: Int = MapEditSession.MAX_STAMP): TileRect {
        val strip = stripOfRow(start.second.coerceIn(0, displayRows - 1))
        val width = stripWidth(strip)
        val top = strip * sheetRows
        val local = TilesetSelection.rectBetween(
            start.first.coerceIn(0, width - 1), start.second - top,
            end.first.coerceIn(0, width - 1), end.second - top,
            width, sheetRows, maxSize
        )
        return local.copy(x = local.x + strip * columns)
    }

    /**
     * The parts of sheet block [r] as display rectangles: one per strip it touches (a block picked from the map with the
     * eyedropper, or chosen before, may cross a strip edge).
     */
    fun displayRects(r: TileRect): List<TileRect> {
        val out = ArrayList<TileRect>()
        var x = r.x
        val end = r.x + r.width
        while (x < end) {
            val strip = x / columns
            val stripEnd = minOf(end, (strip + 1) * columns)
            val (dx, dy) = toDisplay(x, r.y)
            out += TileRect(dx, dy, stripEnd - x, r.height)
            x = stripEnd
        }
        return out
    }
}

/**
 * The geometry of picking tiles from a tileset sheet in the palette: which cell a pointer is over and which block a drag
 * selects. Pure maths, so the rules (clamping to the sheet, the stamp size limit) are tested without any drawing.
 */
object TilesetSelection {
    /** Widest the palette shows a tileset, in tiles: RPG Maker XP's 8 (256 px). Wider sheets fold into strips ([PaletteStrips]). */
    const val PALETTE_COLUMNS = 8

    /** Zoom levels of the palette, as a multiplier of the tile's natural size. */
    val ZOOMS: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /** The next zoom level in [direction] (+1 larger, -1 smaller), staying within [ZOOMS]. */
    fun stepZoom(current: Float, direction: Int): Float {
        val index = ZOOMS.indexOfFirst { it >= current - 0.001f }.let { if (it < 0) ZOOMS.lastIndex else it }
        return ZOOMS[(index + direction).coerceIn(0, ZOOMS.lastIndex)]
    }

    /**
     * The cell under a pointer at ([px], [pyFromTop]) pixels from the sheet's top-left corner, for cells of [cellSize]
     * pixels on a sheet of [cols] x [rows] tiles. Positions outside the sheet clamp to its nearest edge cell, so a drag
     * that leaves the sheet keeps working.
     */
    fun cellAt(px: Float, pyFromTop: Float, cellSize: Float, cols: Int, rows: Int): Pair<Int, Int> {
        require(cellSize > 0f && cols > 0 && rows > 0) { "Sheet must have a positive size" }
        val cx = Math.floor((px / cellSize).toDouble()).toInt().coerceIn(0, cols - 1)
        val cy = Math.floor((pyFromTop / cellSize).toDouble()).toInt().coerceIn(0, rows - 1)
        return cx to cy
    }

    /**
     * The block selected by a drag from the cell ([ax], [ay]) to ([bx], [by]), in any direction. The block never exceeds
     * [maxSize] tiles on a side (the end of the drag is held back so the starting cell stays inside it) and never leaves
     * the [cols] x [rows] sheet.
     */
    fun rectBetween(ax: Int, ay: Int, bx: Int, by: Int, cols: Int, rows: Int, maxSize: Int = MapEditSession.MAX_STAMP): TileRect {
        val startX = ax.coerceIn(0, cols - 1)
        val startY = ay.coerceIn(0, rows - 1)
        val endX = (startX + (bx.coerceIn(0, cols - 1) - startX).coerceIn(-(maxSize - 1), maxSize - 1))
        val endY = (startY + (by.coerceIn(0, rows - 1) - startY).coerceIn(-(maxSize - 1), maxSize - 1))
        val x = minOf(startX, endX)
        val y = minOf(startY, endY)
        return TileRect(x, y, Math.abs(endX - startX) + 1, Math.abs(endY - startY) + 1)
    }

    /** Number of tile columns and rows of a sheet [pixelWidth] x [pixelHeight] pixels, with tiles of [tileSize] pixels. */
    fun gridOf(pixelWidth: Int, pixelHeight: Int, tileSize: Int = 32): Pair<Int, Int> =
        (pixelWidth / tileSize).coerceAtLeast(1) to (pixelHeight / tileSize).coerceAtLeast(1)
}
