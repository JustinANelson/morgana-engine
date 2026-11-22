package com.morgana.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class Phase19ConnectionsConfigTest {

    private fun write(dir: Path, text: String) = Files.writeString(ServerConfig.fileIn(dir), text)

    private fun rejected(dir: Path, body: String, mention: String) {
        write(dir, """{ "connections": $body }""")
        val e = assertThrows(ServerConfigException::class.java, { ServerConfig.load(dir) }, body)
        assertTrue(e.message!!.contains(mention), "expected '$mention' in: ${e.message}")
    }

    @Test
    fun `no connections section means the defaults`(@TempDir dir: Path) {
        assertNull(ServerConfig.load(dir).connections)
        val defaults = ConnectionsConfig()
        assertTrue(defaults.enabled)
        assertEquals(8, defaults.maxPerIp)
        assertEquals(30, defaults.preLoginSeconds)
    }

    @Test
    fun `values are read and omitted ones keep their defaults`(@TempDir dir: Path) {
        write(dir, """{ "connections": { "maxPerIp": 3, "banMinutes": 60, "allow": ["10.0.0.0/8", "203.0.113.7"] } }""")
        val c = ServerConfig.load(dir).connections!!
        assertEquals(3, c.maxPerIp)
        assertEquals(60, c.banMinutes)
        assertEquals(1000, c.maxTotal)
        assertEquals(listOf("10.0.0.0/8", "203.0.113.7"), c.allow)
    }

    @Test
    fun `out-of-range numbers, bad allow entries and typos are rejected with the field name`(@TempDir dir: Path) {
        rejected(dir, """{ "maxPerIp": 0 }""", "maxPerIp")
        rejected(dir, """{ "maxTotal": 100001 }""", "maxTotal")
        rejected(dir, """{ "burst": 0 }""", "burst")
        rejected(dir, """{ "newPerMinute": -5 }""", "newPerMinute")
        rejected(dir, """{ "strikesToBan": 0 }""", "strikesToBan")
        rejected(dir, """{ "banMinutes": 20000 }""", "banMinutes")
        rejected(dir, """{ "banMinutes": 60, "maxBanMinutes": 30 }""", "maxBanMinutes")
        rejected(dir, """{ "preLoginSeconds": 1 }""", "preLoginSeconds")
        rejected(dir, """{ "allow": ["10.0.0.0/8", "friend.example.com"] }""", "friend.example.com")
        rejected(dir, """{ "allow": ["10.0.0.0/40"] }""", "10.0.0.0/40")
        write(dir, """{ "connections": { "maxPerIP": 3 } }""")
        assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }
    }

    @Test
    fun `the connections section survives saving other settings`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig(connections = ConnectionsConfig(maxPerIp = 4, allow = listOf("192.168.0.0/16"))))
        ServerConfig.save(dir, ServerConfig.load(dir).withPlayerData(PlayerDataBackend.SQLITE))
        assertEquals(4, ServerConfig.load(dir).connections?.maxPerIp)
        assertEquals(listOf("192.168.0.0/16"), ServerConfig.load(dir).connections?.allow)
    }
}
