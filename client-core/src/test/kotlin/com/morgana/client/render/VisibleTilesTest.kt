package com.morgana.client.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VisibleTilesTest {
    @Test
    fun `a view inside a big map covers only the tiles it touches`() {
        // 100x100 map of 32 px tiles; view x 320..640, render y 640..960 (tile rows counted from the top).
        val v = VisibleTiles.of(100, 100, 32, 0f, 0f, 320f, 640f, 640f, 960f)
        assertEquals(10, v.firstCol); assertEquals(21, v.lastCol)
        assertEquals(100 - 1 - 30, v.firstRow); assertEquals(100 - 1 - 20 + 1, v.lastRow)
    }

    @Test
    fun `a view larger than the map covers all of it`() {
        val v = VisibleTiles.of(20, 10, 32, 0f, 0f, -5000f, 5000f, -5000f, 5000f)
        assertEquals(VisibleTiles(0, 20, 0, 10), v)
    }

    @Test
    fun `a view far outside the map covers nothing`() {
        val v = VisibleTiles.of(20, 10, 32, 0f, 0f, 9000f, 9500f, 9000f, 9500f)
        assertEquals(v.firstCol, v.lastCol)
    }

    @Test
    fun `a neighbour map offset to the left is culled by its own offset`() {
        // West neighbour drawn at x -640..0; a view of x 0..300 sees none of it.
        val v = VisibleTiles.of(20, 10, 32, -640f, 0f, 0f, 300f, 0f, 320f)
        assertEquals(20, v.firstCol); assertEquals(20, v.lastCol)
    }
}
