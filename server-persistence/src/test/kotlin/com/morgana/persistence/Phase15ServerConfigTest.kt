package com.morgana.persistence

import com.morgana.domain.sim.OptionalModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class Phase15ServerConfigTest {

    private fun write(dir: Path, text: String) = Files.writeString(ServerConfig.fileIn(dir), text)

    @Test
    fun `a missing server json means every module is on`(@TempDir dir: Path) {
        val config = ServerConfig.load(dir)
        assertTrue(config.moduleFlags().disabled.isEmpty())
    }

    @Test
    fun `listed modules are switched and unlisted ones keep their default`(@TempDir dir: Path) {
        write(dir, """{ "schemaVersion": 1, "modules": { "trading": false, "guilds": false, "parties": true } }""")
        val flags = ServerConfig.load(dir).moduleFlags()
        assertEquals(listOf("guilds", "trading"), flags.disabledKeys())
        assertTrue(flags.isEnabled(OptionalModule.PARTIES))
        assertTrue(flags.isEnabled(OptionalModule.CRAFTING))
    }

    @Test
    fun `a plugin module key is accepted by shape and switches that module off`(@TempDir dir: Path) {
        write(dir, """{ "modules": { "stocks:market": false, "trading": false } }""")
        val flags = ServerConfig.load(dir).moduleFlags()
        assertEquals(listOf("stocks:market", "trading"), flags.disabledKeys())
        assertFalse(flags.isEnabled("stocks:market"))
    }

    @Test
    fun `typos and bad files are rejected with a message instead of silently ignored`(@TempDir dir: Path) {
        write(dir, """{ "modules": { "tradng": false } }""")
        val unknown = assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }
        assertTrue(unknown.message!!.contains("tradng") && unknown.message!!.contains("trading"), unknown.message)

        write(dir, """{ "modules": {}, "extra": 1 }""")
        assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }

        write(dir, "{ not json")
        assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }

        write(dir, """{ "schemaVersion": 99 }""")
        assertTrue(assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }.message!!.contains("schemaVersion"))
    }

    @Test
    fun `saving and editing round-trips`(@TempDir dir: Path) {
        val first = ServerConfig().withModules(disable = listOf(OptionalModule.TRADING, OptionalModule.PROJECTILES))
        ServerConfig.save(dir, first)
        assertEquals(first, ServerConfig.load(dir))

        val second = ServerConfig.load(dir).withModules(enable = listOf(OptionalModule.TRADING), disable = listOf(OptionalModule.GUILDS))
        ServerConfig.save(dir, second)
        val flags = ServerConfig.load(dir).moduleFlags()
        assertEquals(listOf("guilds", "projectiles"), flags.disabledKeys())
        assertFalse(flags.isEnabled(OptionalModule.GUILDS))
    }

    @Test
    fun `session config parses defaults and rejects out-of-range values`(@TempDir dir: Path) {
        write(dir, """{ "schemaVersion": 1, "session": { "resume": false, "graceMinutes": 15, "idleSeconds": 120 } }""")
        val loaded = ServerConfig.load(dir)
        assertEquals(SessionConfig(resume = false, graceMinutes = 15, idleSeconds = 120), loaded.session)

        write(dir, """{ "schemaVersion": 1, "session": { "graceMinutes": 0 } }""")
        assertTrue(assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }.message!!.contains("graceMinutes"))

        write(dir, """{ "schemaVersion": 1, "session": { "idleSeconds": 5 } }""")
        assertTrue(assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }.message!!.contains("idleSeconds"))
    }

    @Test
    fun `combatLogSeconds defaults to 15, accepts 0 to turn it off and rejects nonsense`(@TempDir dir: Path) {
        assertEquals(15, SessionConfig().combatLogSeconds)
        write(dir, """{ "schemaVersion": 1, "session": { "combatLogSeconds": 0 } }""")
        assertEquals(0, ServerConfig.load(dir).session!!.combatLogSeconds)
        write(dir, """{ "schemaVersion": 1, "session": { "combatLogSeconds": 301 } }""")
        assertTrue(assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }.message!!.contains("combatLogSeconds"))
        write(dir, """{ "schemaVersion": 1, "session": { "combatLogSeconds": -1 } }""")
        assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }
    }
}
