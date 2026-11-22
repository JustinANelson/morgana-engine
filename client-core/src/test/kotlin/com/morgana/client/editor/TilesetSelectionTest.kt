package com.morgana.client.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Picking tiles from a tileset sheet: cells, drag-selected blocks, and zoom. */
class TilesetSelectionTest {

    @Test
    fun `a pointer maps to the cell it is over`() {
        assertEquals(0 to 0, TilesetSelection.cellAt(0f, 0f, 32f, 16, 20))
        assertEquals(0 to 0, TilesetSelection.cellAt(31.9f, 31.9f, 32f, 16, 20))
        assertEquals(1 to 0, TilesetSelection.cellAt(32f, 5f, 32f, 16, 20))
        assertEquals(3 to 2, TilesetSelection.cellAt(100f, 70f, 32f, 16, 20))
    }

    @Test
    fun `cells scale with the zoom`() {
        // At 2x each cell is 64 px, at 0.5x each is 16 px.
        assertEquals(1 to 1, TilesetSelection.cellAt(70f, 70f, 64f, 16, 20))
        assertEquals(5 to 5, TilesetSelection.cellAt(90f, 90f, 16f, 16, 20))
    }

    @Test
    fun `a pointer outside the sheet clamps to the nearest edge cell`() {
        assertEquals(0 to 0, TilesetSelection.cellAt(-50f, -10f, 32f, 16, 20))
        assertEquals(15 to 19, TilesetSelection.cellAt(9999f, 9999f, 32f, 16, 20))
    }

    @Test
    fun `an empty sheet is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TilesetSelection.cellAt(0f, 0f, 32f, 0, 5) }
        assertThrows(IllegalArgumentException::class.java) { TilesetSelection.cellAt(0f, 0f, 0f, 5, 5) }
    }

    @Test
    fun `a click selects a single tile`() {
        assertEquals(TileRect(4, 2, 1, 1), TilesetSelection.rectBetween(4, 2, 4, 2, 16, 20))
    }

    @Test
    fun `a drag selects the block between the two cells in any direction`() {
        val expected = TileRect(2, 3, 4, 3)
        assertEquals(expected, TilesetSelection.rectBetween(2, 3, 5, 5, 16, 20))   // down-right
        assertEquals(expected, TilesetSelection.rectBetween(5, 5, 2, 3, 16, 20))   // up-left
        assertEquals(expected, TilesetSelection.rectBetween(5, 3, 2, 5, 16, 20))   // down-left
        assertEquals(expected, TilesetSelection.rectBetween(2, 5, 5, 3, 16, 20))   // up-right
    }

    @Test
    fun `a block never exceeds the stamp limit and keeps the starting cell`() {
        val r = TilesetSelection.rectBetween(2, 2, 60, 60, 100, 100)
        assertEquals(MapEditSession.MAX_STAMP, r.width)
        assertEquals(MapEditSession.MAX_STAMP, r.height)
        assertEquals(2, r.x)
        assertEquals(2, r.y)

        val back = TilesetSelection.rectBetween(40, 40, 0, 0, 100, 100)
        assertEquals(MapEditSession.MAX_STAMP, back.width)
        assertEquals(40 - (MapEditSession.MAX_STAMP - 1), back.x, "dragging up-left holds the far corner back")
    }

    @Test
    fun `a block never leaves the sheet`() {
        assertEquals(TileRect(0, 0, 3, 2), TilesetSelection.rectBetween(0, 0, 2, 1, 3, 2))
        val clipped = TilesetSelection.rectBetween(0, 0, 99, 99, 3, 2)
        assertEquals(TileRect(0, 0, 3, 2), clipped)
        val outsideStart = TilesetSelection.rectBetween(-5, -5, 1, 1, 3, 2)
        assertEquals(TileRect(0, 0, 2, 2), outsideStart)
    }

    @Test
    fun `zoom steps through the levels and stops at the ends`() {
        assertEquals(0.75f, TilesetSelection.stepZoom(0.5f, +1))
        assertEquals(1.25f, TilesetSelection.stepZoom(1f, +1))
        assertEquals(2f, TilesetSelection.stepZoom(2f, +1))
        assertEquals(1.5f, TilesetSelection.stepZoom(2f, -1))
        assertEquals(0.5f, TilesetSelection.stepZoom(0.5f, -1))
        assertEquals(1f, TilesetSelection.stepZoom(0.9f, 0), "an odd value snaps to the nearest level above")
    }

    @Test
    fun `sheet sizes convert to tile counts`() {
        assertEquals(16 to 3, TilesetSelection.gridOf(512, 96))
        assertEquals(32 to 48, TilesetSelection.gridOf(1024, 1536))
        assertEquals(1 to 1, TilesetSelection.gridOf(10, 10), "a tiny image still has one cell")
    }

    @Test
    fun `a sheet no wider than the palette is shown as it is`() {
        val p = PaletteStrips(8, 20)
        assertEquals(1, p.strips)
        assertEquals(8, p.displayCols)
        assertEquals(20, p.displayRows)
        assertEquals(3 to 4, p.toSheet(3, 4))
        val narrow = PaletteStrips(5, 3)
        assertEquals(5, narrow.displayCols)
        assertEquals(3, narrow.displayRows)
    }

    @Test
    fun `a wider sheet folds into eight-wide strips stacked downwards`() {
        val p = PaletteStrips(20, 4) // strips of 8, 8 and 4 columns
        assertEquals(3, p.strips)
        assertEquals(8, p.displayCols)
        assertEquals(12, p.displayRows)
        assertEquals(listOf(8, 8, 4), (0 until 3).map { p.stripWidth(it) })
        assertEquals(0 to 0, p.toSheet(0, 0))
        assertEquals(8 to 0, p.toSheet(0, 4), "the second strip starts at sheet column 8")
        assertEquals(19 to 3, p.toSheet(3, 11))
        for (sx in 0 until 20) for (sy in 0 until 4) {
            val (dx, dy) = p.toDisplay(sx, sy)
            assertEquals(sx to sy, p.toSheet(dx, dy))
        }
        assertEquals(true, p.hasTile(3, 11))
        assertEquals(false, p.hasTile(4, 11), "the narrow last strip leaves its right side empty")
    }

    @Test
    fun `a drag selection stays inside the strip it started in`() {
        val p = PaletteStrips(20, 4)
        assertEquals(TileRect(9, 1, 2, 2), p.selection(1 to 5, 2 to 6))
        // Dragging down into the next strip stops at the bottom of this one.
        assertEquals(TileRect(8, 2, 1, 2), p.selection(0 to 6, 0 to 10))
        // In the narrow last strip the drag cannot reach the empty columns.
        assertEquals(TileRect(17, 0, 3, 1), p.selection(1 to 8, 7 to 8))
    }

    @Test
    fun `a block crossing a strip edge is drawn in each strip it touches`() {
        val p = PaletteStrips(20, 4)
        assertEquals(listOf(TileRect(6, 1, 2, 2), TileRect(0, 5, 1, 2)), p.displayRects(TileRect(6, 1, 3, 2)))
        assertEquals(listOf(TileRect(2, 0, 1, 1)), p.displayRects(TileRect(2, 0, 1, 1)))
    }
}
