package com.morgana.server

import com.morgana.persistence.ServerConfig
import com.morgana.persistence.TlsConfig
import com.morgana.server.tools.tlsCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class Phase17TlsToolTest {

    private fun captureStdout(block: () -> Int): Pair<Int, String> {
        val old = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try { return block() to buffer.toString() } finally { System.setOut(old) }
    }

    @Test
    fun `the tls command creates the certificate and prints a usable invite code`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig(tls = TlsConfig(mode = "required")))
        val (code, out) = captureStdout { tlsCommand(dir, "play.example.com", 7001) }
        assertEquals(0, code)
        assertTrue(Files.exists(dir.resolve("tls/server.p12")))
        val invite = Regex("Invite code: (morgana://\\S+)").find(out)!!.groupValues[1]
        assertTrue(invite.startsWith("morgana://play.example.com:7001#fp="), invite)
        val fp = invite.substringAfter("#fp=")
        assertEquals(64, fp.length)
        assertTrue(out.contains(fp.chunked(2).joinToString(":").uppercase()), "the human-readable fingerprint is shown too")

        // Idempotent: running it again shows the same identity.
        val (_, again) = captureStdout { tlsCommand(dir, "play.example.com", 7001) }
        assertTrue(again.contains(invite))
    }

    @Test
    fun `with tls off it still previews the certificate but says it is not in use`(@TempDir dir: Path) {
        val (code, out) = captureStdout { tlsCommand(dir, "", 7001) }
        assertEquals(0, code)
        assertTrue(out.contains("TLS mode: off"))
        assertTrue(out.contains("still OFF"), out)
        assertFalse(out.contains("Invite code: morgana://null"))
    }

    @Test
    fun `a broken keystore setup is reported as an error`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig(tls = TlsConfig(mode = "required", keystore = "missing.p12", passwordEnv = "NOPE_NOT_SET")))
        assertEquals(1, tlsCommand(dir, "", 7001))
    }
}
