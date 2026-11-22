package com.morgana.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class Phase17TlsConfigTest {

    private fun write(dir: Path, text: String) = Files.writeString(ServerConfig.fileIn(dir), text)

    @Test
    fun `no tls section means the caller picks the default mode`(@TempDir dir: Path) {
        assertNull(ServerConfig.load(dir).tls)
        write(dir, """{ "modules": { "trading": false } }""")
        assertNull(ServerConfig.load(dir).tls)
    }

    @Test
    fun `every documented mode parses`(@TempDir dir: Path) {
        for (mode in TlsMode.entries) {
            write(dir, """{ "tls": { "mode": "${mode.configKey}" } }""")
            assertEquals(mode, TlsMode.fromConfigKey(ServerConfig.load(dir).tls!!.mode))
        }
    }

    @Test
    fun `an operator keystore needs a password variable and typos are rejected`(@TempDir dir: Path) {
        write(dir, """{ "tls": { "mode": "required", "keystore": "certs/server.p12", "passwordEnv": "TLS_PW" } }""")
        val ok = ServerConfig.load(dir).tls!!
        assertEquals("certs/server.p12", ok.keystore)
        assertEquals("TLS_PW", ok.passwordEnv)

        write(dir, """{ "tls": { "mode": "required", "keystore": "certs/server.p12" } }""")
        assertTrue(assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }.message!!.contains("passwordEnv"))

        write(dir, """{ "tls": { "mode": "require" } }""")
        val unknown = assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }
        assertTrue(unknown.message!!.contains("require") && unknown.message!!.contains("required"), unknown.message)

        write(dir, """{ "tls": { "mode": "required", "keystorePassword": "plaintext!" } }""")
        assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) } // passwords never live in the file
    }

    @Test
    fun `saving other settings keeps the tls section`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig(tls = TlsConfig(mode = "remote")))
        ServerConfig.save(dir, ServerConfig.load(dir).withModules(disable = listOf(com.morgana.domain.sim.OptionalModule.GUILDS)))
        val back = ServerConfig.load(dir)
        assertEquals("remote", back.tls?.mode)
        assertEquals(listOf("guilds"), back.moduleFlags().disabledKeys())
    }
}
