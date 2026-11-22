package com.morgana.client.ui

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class UnreadCounterTest {
    private class Line(val text: String)

    @Test
    fun `counts lines that arrive while minimized`() {
        val counter = UnreadCounter<Line>()
        val lines = mutableListOf(Line("a"))
        assertEquals(0, counter.update(lines, watching = false))
        lines += Line("b"); lines += Line("c")
        assertEquals(2, counter.update(lines, watching = true))
        lines += Line("d")
        assertEquals(3, counter.update(lines, watching = true))
    }

    @Test
    fun `expanding the window clears the count`() {
        val counter = UnreadCounter<Line>()
        val lines = mutableListOf(Line("a"))
        counter.update(lines, true)
        lines += Line("b")
        assertEquals(1, counter.update(lines, true))
        assertEquals(0, counter.update(lines, false))
        assertEquals(0, counter.update(lines, true))
    }

    @Test
    fun `a capped list that dropped the old newest line counts every line`() {
        val counter = UnreadCounter<Line>()
        val first = mutableListOf(Line("a"), Line("b"))
        counter.update(first, true)
        val next = listOf(Line("x"), Line("y"), Line("z"))
        assertEquals(3, counter.update(next, true))
    }

    @Test
    fun `no change means no new count`() {
        val counter = UnreadCounter<Line>()
        val lines = listOf(Line("a"))
        assertEquals(0, counter.update(lines, true))
        assertEquals(0, counter.update(lines, true))
    }
}
