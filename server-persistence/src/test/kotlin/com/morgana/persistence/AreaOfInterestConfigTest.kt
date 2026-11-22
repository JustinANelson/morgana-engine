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

class AreaOfInterestConfigTest {
    private fun write(dir: Path, text: String) = Files.writeString(ServerConfig.fileIn(dir), text)

    @Test
    fun `no section means the buckets are off`(@TempDir dir: Path) {
        assertNull(ServerConfig.load(dir).areaOfInterest)
        assertFalse(AreaOfInterestConfig().enabled)
    }

    @Test
    fun `values are read and omitted ones keep their defaults`(@TempDir dir: Path) {
        write(dir, """{ "areaOfInterest": { "enabled": true, "cellTiles": 24 } }""")
        val a = ServerConfig.load(dir).areaOfInterest!!
        assertTrue(a.enabled)
        assertEquals(24, a.cellTiles)
        assertEquals(1, a.radiusCells)
    }

    @Test
    fun `out-of-range sizes refuse to load`(@TempDir dir: Path) {
        for ((body, mention) in listOf(
            """{ "cellTiles": 4 }""" to "areaOfInterest.cellTiles",
            """{ "cellTiles": 500 }""" to "areaOfInterest.cellTiles",
            """{ "radiusCells": 0 }""" to "areaOfInterest.radiusCells",
            """{ "radiusCells": 9 }""" to "areaOfInterest.radiusCells"
        )) {
            write(dir, """{ "areaOfInterest": $body }""")
            val e = assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }
            assertTrue(mention in e.message.orEmpty(), "${e.message} should mention $mention")
        }
    }
}
