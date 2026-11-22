package com.morgana.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AdminConfigTest {
    private fun write(dir: Path, json: String) = Files.writeString(ServerConfig.fileIn(dir), json)

    @Test
    fun `the panel is off when the section is missing`(@TempDir dir: Path) {
        assertNull(ServerConfig.load(dir).admin)
        write(dir, """{"schemaVersion":1}""")
        assertNull(ServerConfig.load(dir).admin)
    }

    @Test
    fun `a valid section loads with its defaults`(@TempDir dir: Path) {
        write(dir, """{"schemaVersion":1,"admin":{"enabled":true}}""")
        val admin = ServerConfig.load(dir).admin!!
        assertTrue(admin.enabled)
        assertEquals(AdminConfig.DEFAULT_PORT, admin.port)
        assertEquals("127.0.0.1", admin.bindAddress)
        assertFalse(admin.allowRemote)
    }

    @Test
    fun `an address that is not this machine needs allowRemote`(@TempDir dir: Path) {
        write(dir, """{"schemaVersion":1,"admin":{"enabled":true,"bindAddress":"0.0.0.0"}}""")
        val e = assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }
        assertTrue(e.message!!.contains("allowRemote"), e.message)
        write(dir, """{"schemaVersion":1,"admin":{"enabled":true,"bindAddress":"0.0.0.0","allowRemote":true}}""")
        assertEquals("0.0.0.0", ServerConfig.load(dir).admin!!.bindAddress)
        write(dir, """{"schemaVersion":1,"admin":{"enabled":false,"bindAddress":"0.0.0.0"}}""")
        assertFalse(ServerConfig.load(dir).admin!!.enabled, "a panel that is off may keep any address")
        write(dir, """{"schemaVersion":1,"admin":{"enabled":true,"bindAddress":"localhost"}}""")
        assertEquals("localhost", ServerConfig.load(dir).admin!!.bindAddress)
    }

    @Test
    fun `bad ports, blank addresses and unknown fields are refused`(@TempDir dir: Path) {
        write(dir, """{"schemaVersion":1,"admin":{"enabled":true,"port":70000}}""")
        assertTrue(assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }.message!!.contains("admin.port"))
        write(dir, """{"schemaVersion":1,"admin":{"enabled":true,"bindAddress":" "}}""")
        assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }
        write(dir, """{"schemaVersion":1,"admin":{"enabeld":true}}""")
        assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }
    }

    @Test
    fun `loopback detection`() {
        for (a in listOf("127.0.0.1", "127.1.2.3", "localhost", "::1", "[::1]", "LOCALHOST")) assertTrue(AdminConfig.isLoopback(a), a)
        for (a in listOf("0.0.0.0", "192.168.1.5", "::", "example.com")) assertFalse(AdminConfig.isLoopback(a), a)
    }
}
