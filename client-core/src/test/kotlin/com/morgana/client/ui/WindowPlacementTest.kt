package com.morgana.client.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WindowPlacementTest {
    private val stageW = 1000f
    private val stageH = 600f

    @Test
    fun `clamp pulls a window back on screen and pins an oversized one to the top left`() {
        assertEquals(WindowRect(0f, 0f, 200f, 100f), WindowPlacement.clamp(WindowRect(-50f, -20f, 200f, 100f), stageW, stageH))
        assertEquals(WindowRect(800f, 500f, 200f, 100f), WindowPlacement.clamp(WindowRect(900f, 580f, 200f, 100f), stageW, stageH))
        assertEquals(WindowRect(0f, -100f, 1200f, 700f), WindowPlacement.clamp(WindowRect(40f, 30f, 1200f, 700f), stageW, stageH))
    }

    @Test
    fun `a window near a screen edge snaps to it, one far away stays put`() {
        val near = WindowPlacement.snap(WindowRect(6f, 300f, 200f, 100f), stageW, stageH, emptyList())
        assertEquals(0f, near.x)
        assertEquals(300f, near.y)
        val corner = WindowPlacement.snap(WindowRect(795f, 496f, 200f, 100f), stageW, stageH, emptyList())
        assertEquals(800f, corner.x)
        assertEquals(500f, corner.y)
        val far = WindowPlacement.snap(WindowRect(100f, 200f, 200f, 100f), stageW, stageH, emptyList())
        assertEquals(WindowRect(100f, 200f, 200f, 100f), far)
    }

    @Test
    fun `a window snaps against the side of another one it overlaps`() {
        val other = WindowRect(100f, 200f, 300f, 200f) // right edge 400, top edge 400
        val beside = WindowPlacement.snap(WindowRect(408f, 250f, 150f, 100f), stageW, stageH, listOf(other))
        assertEquals(400f, beside.x, "left edge meets their right edge")
        val below = WindowPlacement.snap(WindowRect(150f, 93f, 100f, 100f), stageW, stageH, listOf(other))
        assertEquals(100f, below.y, "top edge (193) meets their bottom edge (200)")
    }

    @Test
    fun `a window on the far side of the screen is not snapped to`() {
        val other = WindowRect(100f, 500f, 100f, 80f)
        val result = WindowPlacement.snap(WindowRect(205f, 100f, 100f, 100f), stageW, stageH, listOf(other))
        assertEquals(205f, result.x, "they do not overlap vertically, so no snapping between them")
    }

    @Test
    fun `a saved position comes back with its top edge in place and stays on screen`() {
        val saved = WindowPlacement.format(WindowRect(120f, 300f, 200f, 150f)) // top edge 450
        assertEquals("120,450", saved)
        assertEquals(WindowRect(120f, 300f, 200f, 150f), WindowPlacement.restore(saved, 200f, 150f, stageW, stageH))
        assertEquals(WindowRect(120f, 420f, 200f, 30f), WindowPlacement.restore(saved, 200f, 30f, stageW, stageH), "collapsed: same top edge")
        assertEquals(WindowRect(800f, 0f, 200f, 100f), WindowPlacement.restore("5000,-40", 200f, 100f, stageW, stageH), "off-screen values are pulled back")
    }

    @Test
    fun `junk saved text is ignored`() {
        assertNull(WindowPlacement.restore(null, 10f, 10f, stageW, stageH))
        assertNull(WindowPlacement.restore("abc", 10f, 10f, stageW, stageH))
        assertNull(WindowPlacement.restore("1,two", 10f, 10f, stageW, stageH))
        assertNull(WindowPlacement.restore("1,2,3", 10f, 10f, stageW, stageH))
    }
}
