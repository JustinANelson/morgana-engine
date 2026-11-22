package com.morgana.client.realms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ServerAddressTest {
    private fun p(text: String) = ServerAddress.parse(text)

    @Test
    fun `plain hosts and host-colon-port`() {
        assertEquals(ServerAddress.Parsed("play.example.com", null), p("play.example.com"))
        assertEquals(ServerAddress.Parsed("play.example.com", 7002), p("play.example.com:7002"))
        assertEquals(ServerAddress.Parsed("192.168.1.20", 7001), p("  192.168.1.20:7001 "))
        assertEquals(ServerAddress.Parsed("my-server_1", null), p("my-server_1"))
    }

    @Test
    fun `web-style addresses are reduced to host and port`() {
        assertEquals(ServerAddress.Parsed("play.example.com", 7002), p("https://play.example.com:7002/"))
        assertEquals(ServerAddress.Parsed("play.example.com", null), p("wss://play.example.com/game?x=1#top"))
        assertEquals(ServerAddress.Parsed("localhost", 7002), p("HTTP://localhost:7002"))
    }

    @Test
    fun `ipv6 in brackets or bare`() {
        assertEquals(ServerAddress.Parsed("::1", 7001), p("[::1]:7001"))
        assertEquals(ServerAddress.Parsed("fe80::1", null), p("[fe80::1]"))
        assertEquals(ServerAddress.Parsed("2001:db8::5", null), p("2001:db8::5"))
        assertNull(p("[::1"))
        assertNull(p("[::1]x"))
        assertNull(p("[::1]:99999"))
    }

    @Test
    fun `nonsense is refused rather than guessed`() {
        for (bad in listOf("", "   ", "two words", "host:", "host:abc", "host:0", "host:70000", ":7001", "http://", ".bad", "bad.", "bad!host")) {
            assertNull(p(bad), "'$bad'")
        }
        assertNotNull(p("a"))
    }

    @Test
    fun `the port box explains itself`() {
        assertNull(ServerAddress.portError("7001"))
        assertNull(ServerAddress.portError(" 65535 "))
        for (bad in listOf("", "0", "65536", "abc", "-1", "70.5")) assertNotNull(ServerAddress.portError(bad), "'$bad'")
    }
}
