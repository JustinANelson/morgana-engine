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

class RolePlayConfigTest {

    private fun write(dir: Path, text: String) = Files.writeString(ServerConfig.fileIn(dir), text)

    @Test
    fun `no roleplay section means whole-map speech`(@TempDir dir: Path) {
        assertNull(ServerConfig.load(dir).roleplay)
        val settings = RolePlayConfig().toSettings()
        assertEquals(0, settings.sayRadius)
        assertEquals(2, settings.quietRadius)
        assertTrue(settings.shoutReachesNeighbors)
    }

    @Test
    fun `values are read and omitted ones keep their defaults`(@TempDir dir: Path) {
        write(dir, """{ "roleplay": { "sayRadius": 8, "shoutReachesNeighbors": false } }""")
        val r = ServerConfig.load(dir).roleplay!!
        assertEquals(8, r.sayRadius)
        assertEquals(2, r.quietRadius)
        assertFalse(r.shoutReachesNeighbors)
    }

    @Test
    fun `out-of-range radii and unknown fields refuse to load`(@TempDir dir: Path) {
        for ((body, mention) in listOf(
            """{ "sayRadius": -1 }""" to "roleplay.sayRadius",
            """{ "sayRadius": 65 }""" to "roleplay.sayRadius",
            """{ "quietRadius": 0 }""" to "roleplay.quietRadius",
            """{ "sayRange": 4 }""" to "sayRange"
        )) {
            write(dir, """{ "roleplay": $body }""")
            val e = assertThrows(ServerConfigException::class.java, { ServerConfig.load(dir) }, body)
            assertTrue(e.message!!.contains(mention), "expected '$mention' in: ${e.message}")
        }
    }

    @Test
    fun `the module can be switched off like any other`(@TempDir dir: Path) {
        write(dir, """{ "modules": { "rolePlayChat": false } }""")
        assertTrue("rolePlayChat" in ServerConfig.load(dir).moduleFlags().disabledKeys())
    }
}
