package com.morgana.client.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LaunchQueryTest {
    @Test
    fun `reads the port from the query string`() {
        assertEquals(7022, portFromQuery("?port=7022"))
        assertEquals(7022, portFromQuery("?a=1&port=7022&b=2"))
        assertEquals(7022, portFromQuery("port=7022"))
    }

    @Test
    fun `ignores missing or invalid ports`() {
        assertNull(portFromQuery(""))
        assertNull(portFromQuery("?port="))
        assertNull(portFromQuery("?port=abc"))
        assertNull(portFromQuery("?port=0"))
        assertNull(portFromQuery("?port=70000"))
        assertNull(portFromQuery("?host=7022"))
        assertEquals(7003, portFromQuery("?port=x&port=7003"))
    }

    @Test
    fun `a page served by the game server connects back to its own port`() {
        assertEquals(7002, webDefaultPort("7002", secure = false, fallback = 7002))
        assertEquals(7105, webDefaultPort("7105", secure = false, fallback = 7002))
        assertEquals(7002, webDefaultPort("8080", secure = false, fallback = 7002), "the TeaVM dev server is not a game server")
        assertEquals(7002, webDefaultPort("", secure = false, fallback = 7002))
        assertEquals(443, webDefaultPort("", secure = true, fallback = 7002), "https behind a proxy such as tailscale serve")
        assertEquals(8443, webDefaultPort("8443", secure = true, fallback = 7002))
    }
}
