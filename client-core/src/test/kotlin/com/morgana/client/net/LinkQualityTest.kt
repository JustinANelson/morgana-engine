package com.morgana.client.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LinkQualityTest {
    @Test
    fun `no connection is the lowest level whatever else is reported`() {
        val q = LinkQuality.of(rttMs = 20, stalled = false, connected = false)
        assertEquals(LinkLevel.LOST, q.level)
        assertEquals(0, q.bars)
    }

    @Test
    fun `a stalled link is poor even with a good last round trip`() {
        val q = LinkQuality.of(rttMs = 20, stalled = true, connected = true)
        assertEquals(LinkLevel.POOR, q.level)
        assertEquals(1, q.bars)
    }

    @Test
    fun `round trips map to levels and bars`() {
        assertEquals(LinkLevel.GOOD, LinkQuality.of(40, false, true).level)
        assertEquals(4, LinkQuality.of(40, false, true).bars)
        assertEquals("40 ms", LinkQuality.of(40, false, true).text)
        assertEquals(LinkLevel.FAIR, LinkQuality.of(200, false, true).level)
        assertEquals(LinkLevel.POOR, LinkQuality.of(500, false, true).level)
        assertEquals(2, LinkQuality.of(500, false, true).bars)
        assertEquals(1, LinkQuality.of(900, false, true).bars)
    }

    @Test
    fun `before the first answer the link is assumed fine`() {
        val q = LinkQuality.of(0, false, true)
        assertEquals(LinkLevel.GOOD, q.level)
        assertEquals("...", q.text)
    }
}
