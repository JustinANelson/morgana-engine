package com.morgana.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class Phase18LimitsConfigTest {

    private fun write(dir: Path, text: String) = Files.writeString(ServerConfig.fileIn(dir), text)

    @Test
    fun `without a limits section the defaults apply`(@TempDir dir: Path) {
        assertNull(ServerConfig.load(dir).limits)
        write(dir, """{ "modules": {} }""")
        assertNull(ServerConfig.load(dir).limits)
    }

    @Test
    fun `enabled and scale are read, with defaults for what is omitted`(@TempDir dir: Path) {
        write(dir, """{ "limits": { "scale": 2.5 } }""")
        assertEquals(LimitsConfig(enabled = true, scale = 2.5), ServerConfig.load(dir).limits)
        write(dir, """{ "limits": { "enabled": false } }""")
        assertEquals(LimitsConfig(enabled = false, scale = 1.0), ServerConfig.load(dir).limits)
    }

    @Test
    fun `nonsense scales and unknown fields are rejected`(@TempDir dir: Path) {
        for (bad in listOf("0", "0.05", "-1", "101")) {
            write(dir, """{ "limits": { "scale": $bad } }""")
            val e = assertThrows(ServerConfigException::class.java, { ServerConfig.load(dir) }, "scale $bad")
            assertTrue(e.message!!.contains("limits.scale"), e.message)
        }
        write(dir, """{ "limits": { "scal": 2 } }""")
        assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }
    }

    @Test
    fun `limits survive saving other settings`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig(limits = LimitsConfig(scale = 3.0)))
        ServerConfig.save(dir, ServerConfig.load(dir).withPlayerData(PlayerDataBackend.SQLITE))
        assertEquals(3.0, ServerConfig.load(dir).limits?.scale)
    }
}
