package com.morgana.client

import com.morgana.client.net.ConnectionPlanner
import com.morgana.protocol.tls.InMemoryKnownHosts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Phase17ConnectionPlannerTest {

    private val known = InMemoryKnownHosts()
    private val fp = "cd".repeat(32)

    private fun plan(host: String, port: String = "7001", tls: Boolean = false) =
        ConnectionPlanner.plan(host, port, tls, known)

    @Test
    fun `an invite code fills in host, port and a pinned fingerprint and forces encryption`() {
        val p = plan("morgana://play.example.com:7123#fp=$fp", port = "9999", tls = false)
        assertEquals("play.example.com", p.host)
        assertEquals(7123, p.port, "the port inside the code wins over the port field")
        assertTrue(p.security.tls, "an invite always means TLS, even if the box was unticked")
        assertEquals(fp, p.security.pinnedFingerprint)
        assertSame(known, p.security.knownHosts)
        assertNull(p.error)
    }

    @Test
    fun `plain addresses use the port field unless they name a port, and the checkbox decides TLS`() {
        val withField = plan("example.com", port = "7005", tls = true)
        assertEquals("example.com", withField.host)
        assertEquals(7005, withField.port)
        assertTrue(withField.security.tls)
        assertNull(withField.security.pinnedFingerprint)

        assertEquals(8000, plan("example.com:8000", port = "7005").port)
        assertEquals(7005, plan("[::1]", port = "7005").port)
        assertEquals(8001, plan("[::1]:8001", port = "7005").port)
        assertFalse(plan("example.com", tls = false).security.tls)
    }

    @Test
    fun `blank host means this machine and a bad port falls back to the default`() {
        val p = plan("", port = "abc")
        assertEquals("127.0.0.1", p.host)
        assertEquals(7001, p.port)
        assertEquals(7001, plan("host", port = "0").port)
        assertEquals(7001, plan("host", port = "99999").port)
    }

    @Test
    fun `garbage is reported instead of connecting somewhere unintended`() {
        for (bad in listOf("two words", "morgana://host#fp=123", "a/b", "host:99999")) {
            val p = plan(bad)
            assertNotNull(p.error, "should reject '$bad'")
        }
    }

    @Test
    fun `loopback detection covers the usual spellings`() {
        for (local in listOf("localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3", "::1", "[::1]", "morgana://127.0.0.1:7001")) {
            assertTrue(ConnectionPlanner.isLoopbackHost(local), local)
        }
        for (remote in listOf("192.168.1.5", "example.com", "10.0.0.1", "morgana://203.0.113.5:7001#fp=$fp")) {
            assertFalse(ConnectionPlanner.isLoopbackHost(remote), remote)
        }
    }
}
