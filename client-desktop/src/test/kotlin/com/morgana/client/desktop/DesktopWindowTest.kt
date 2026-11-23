package com.morgana.client.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DesktopWindowTest {
    @Test
    fun `the window opens at most of a large monitor`() {
        assertEquals(2457 to 1536, DesktopWindow.initialSize(3072, 1920))
        assertEquals(3072 to 1728, DesktopWindow.initialSize(3840, 2160))
    }

    @Test
    fun `the window keeps its old minimum when the monitor has room, and never outgrows the monitor`() {
        assertEquals(1536 to 864, DesktopWindow.initialSize(1920, 1080))
        assertEquals(1280 to 768, DesktopWindow.initialSize(1440, 900), "80% would be smaller than 1280 x 768")
        assertEquals(1280 to 720, DesktopWindow.initialSize(1280, 720), "a small monitor caps the window")
        assertEquals(1280 to 768, DesktopWindow.initialSize(0, 0), "no monitor information: the old default")
    }
}
