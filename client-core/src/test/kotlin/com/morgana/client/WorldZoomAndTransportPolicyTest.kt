package com.morgana.client

import com.morgana.client.net.TransportPolicy
import com.morgana.client.render.WorldZoom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldZoomAndTransportPolicyTest {

    @Test
    fun `loopback and private hosts allow plaintext`() {
        listOf("localhost", "127.0.0.1", "10.1.2.3", "192.168.0.5", "172.16.0.1", "172.31.9.9", "mybox.local", "::1")
            .forEach { assertTrue(TransportPolicy.isLocalHost(it), it) }
    }

    @Test
    fun `tailscale addresses and machine names count as private`() {
        listOf("100.64.0.1", "100.101.102.103", "100.127.255.254", "gaming-pc", "gaming-pc.tail1234.ts.net", "gaming-pc.tail1234.ts.net.", "[fd7a:115c:a1e0::1]", "fd7a:115c:a1e0:ab12::5")
            .forEach { assertTrue(TransportPolicy.isLocalHost(it), it) }
        listOf("100.63.255.255", "100.128.0.1", "ts.net.example.com", "12345").forEach { assertFalse(TransportPolicy.isLocalHost(it), it) }
        assertNull(TransportPolicy.plaintextRefusal("gaming-pc.tail1234.ts.net", tls = false))
    }

    @Test
    fun `public hosts are not local`() {
        listOf("example.com", "8.8.8.8", "172.32.0.1", "192.169.0.1", "300.1.1.1", "").forEach {
            assertFalse(TransportPolicy.isLocalHost(it), it)
        }
    }

    @Test
    fun `plaintext to a public host is refused but tls and local are fine`() {
        assertNotNull(TransportPolicy.plaintextRefusal("play.example.com", tls = false))
        assertNull(TransportPolicy.plaintextRefusal("play.example.com", tls = true))
        assertNull(TransportPolicy.plaintextRefusal("localhost", tls = false))
    }

    @Test
    fun `auto zoom follows density but keeps about ten tiles on the short side`() {
        assertEquals(1, WorldZoom.auto(1280, 720, 1f))
        assertEquals(2, WorldZoom.auto(1920, 1080, 1f))
        assertEquals(2, WorldZoom.auto(1080, 2400, 2f))
        assertEquals(3, WorldZoom.auto(1440, 3200, 3f))
        assertEquals(1, WorldZoom.auto(400, 800, 3f)) // 400 px short side: too small to zoom
        assertEquals(3, WorldZoom.auto(4000, 3000, 9f)) // capped at 3x
    }

    @Test
    fun `fixed setting overrides auto and the setting cycles`() {
        assertEquals(2, WorldZoom.resolve(2, 1920, 1080, 1f))
        assertEquals(2, WorldZoom.resolve(0, 1920, 1080, 1f))
        assertEquals(1, WorldZoom.resolve(0, 1280, 720, 1f))
        assertEquals(listOf(1, 2, 3, 0), listOf(0, 1, 2, 3).map { WorldZoom.nextSetting(it) })
        assertEquals("Auto", WorldZoom.label(0))
        assertEquals("3x", WorldZoom.label(3))
    }

    @Test
    fun `the editor zooms in small steps between a quarter and four times`() {
        assertEquals(1.25f, WorldZoom.stepEditor(1f, +1))
        assertEquals(0.75f, WorldZoom.stepEditor(1f, -1))
        assertEquals(2.5f, WorldZoom.stepEditor(2f, +1))
        assertEquals(4f, WorldZoom.stepEditor(4f, +1), "stops at the top")
        assertEquals(0.25f, WorldZoom.stepEditor(0.25f, -1), "stops at the bottom")
        assertEquals(1.5f, WorldZoom.stepEditor(1.3f, +1), "between levels moves to the next one up")
        assertEquals(1.25f, WorldZoom.stepEditor(1.3f, -1), "between levels moves to the next one down")
        assertEquals(4f, WorldZoom.stepEditor(9f, +1))
        assertEquals(0.25f, WorldZoom.stepEditor(0.1f, -1))
        val levels = WorldZoom.EDITOR_LEVELS
        assertEquals(levels.sorted(), levels)
        for (i in 1 until levels.size) assertTrue(levels[i] / levels[i - 1] <= 2.01f, "no step more than doubles the size")
        assertTrue(listOf(1f, 2f, 3f).all { it in levels }, "the game's whole steps are editor levels too")
        assertEquals("125%", WorldZoom.percentLabel(1.25f))
    }

    @Test
    fun `snapping works at in-between scales`() {
        assertEquals(10.4f, WorldZoom.snap(10.3f, 2.5f), 0.0001f)
        assertEquals(10.3f, WorldZoom.snap(10.3f, 0f))
    }

    @Test
    fun `zoom steps from the toolbar stay within the range`() {
        assertEquals(2, WorldZoom.step(1, 1))
        assertEquals(3, WorldZoom.step(3, 1))
        assertEquals(1, WorldZoom.step(1, -1))
        assertEquals(2, WorldZoom.step(3, -1))
    }

    @Test
    fun `camera zoom and snapping are pixel exact`() {
        assertEquals(0.5f, WorldZoom.cameraZoom(2))
        assertEquals(10.5f, WorldZoom.snap(10.4f, 2))
        assertEquals(10.333333f, WorldZoom.snap(10.3f, 3), 0.0001f)
    }
}
