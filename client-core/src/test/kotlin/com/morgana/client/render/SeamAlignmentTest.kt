package com.morgana.client.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SeamAlignmentTest {
    @Test
    fun `a taller east or west neighbour is shifted so tile rows meet`() {
        val ts = 32f
        val activeH = 70; val neighborH = 130
        val off = MorganaWorldRenderer.sideNeighborOffsetY(activeH, neighborH, ts)
        // Row 34 of each map must land at the same render height.
        fun renderY(h: Int, ty: Int) = (h - 1 - ty) * ts // MorganaWorldRenderer.toRenderY
        val activeY = renderY(activeH, 34)
        val neighborY = off + renderY(neighborH, 34)
        assertEquals(activeY, neighborY)
    }

    @Test
    fun `equal heights need no shift`() {
        assertEquals(0f, MorganaWorldRenderer.sideNeighborOffsetY(100, 100, 32f))
    }
}
