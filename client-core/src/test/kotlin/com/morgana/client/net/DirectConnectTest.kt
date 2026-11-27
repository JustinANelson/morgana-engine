package com.morgana.client.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DirectConnectTest {
    @Test
    fun `a query with a server and a ticket gives a resume session for that server`() {
        val d = DirectConnect.fromQuery("?connect=example.com:7002&tls=1&ticket=dev_abc-DEF_1", "page", 80, false)!!
        assertEquals(DirectConnect("example.com", 7002, true, "dev_abc-DEF_1"), d)
        assertEquals(ResumeInfo("example.com", 7002, true, null, "dev_abc-DEF_1"), d.asResumeInfo())
    }

    @Test
    fun `a ticket alone uses the page server and scheme`() {
        val d = DirectConnect.fromQuery("?ticket=dev_x", "127.0.0.1", 7002, true)!!
        assertEquals(DirectConnect("127.0.0.1", 7002, true, "dev_x"), d)
    }

    @Test
    fun `a server without a ticket only names where to connect`() {
        val d = DirectConnect.fromQuery("?connect=10.0.0.5:7100", "page", 7002, false)!!
        assertEquals("10.0.0.5", d.host)
        assertEquals(7100, d.port)
        assertNull(d.ticket)
        assertNull(d.asResumeInfo())
    }

    @Test
    fun `nothing relevant or nonsense gives null`() {
        assertNull(DirectConnect.fromQuery("", "h", 1, false))
        assertNull(DirectConnect.fromQuery("?port=7002", "h", 1, false))
        assertNull(DirectConnect.fromQuery("?connect=host:99999", "h", 1, false))
        assertNull(DirectConnect.fromQuery("?ticket=" + "x".repeat(300), "h", 1, false))
    }

    @Test
    fun `desktop arguments and the environment variable`() {
        val d = DirectConnect.fromArgs(arrayOf("--connect", "host:7001", "--tls"), { if (it == "MORGANA_TICKET") "dev_env" else null }, "127.0.0.1", 7001)!!
        assertEquals(DirectConnect("host", 7001, true, "dev_env"), d)
        assertNull(DirectConnect.fromArgs(arrayOf("127.0.0.1", "7001"), { null }, "127.0.0.1", 7001))
    }
}
