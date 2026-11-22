package com.morgana.client.desktop

import com.morgana.client.net.ClientSecurity
import com.morgana.client.net.ConnectResult
import com.morgana.client.net.MorganaNetClient
import com.morgana.persistence.ServerConfig
import com.morgana.persistence.TlsConfig
import com.morgana.persistence.TlsMode
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.tls.Fingerprint
import com.morgana.protocol.tls.InMemoryKnownHosts
import com.morgana.server.MorganaServerRuntime
import com.morgana.server.tls.SelfSignedCertificates
import com.morgana.server.tls.TlsSetupException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path

/** Real server + real client over loopback (and, where available, this machine's LAN address). */
class Phase17TlsEndToEndTest {

    private fun start(dir: Path, mode: TlsMode?, config: TlsConfig? = null): MorganaServerRuntime {
        if (config != null) ServerConfig.save(dir, ServerConfig(tls = config))
        else if (mode != null) ServerConfig.save(dir, ServerConfig(tls = TlsConfig(mode = mode.configKey)))
        return MorganaServerRuntime.start(dir, port = 0, watchContent = false)
    }

    /** Sends a login and waits for the answer; null if the server never answers or drops the connection. */
    private fun login(client: MorganaNetClient, name: String): ServerPacket.AuthResult? {
        client.send(ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            var result: ServerPacket.AuthResult? = null
            client.drainPackets { if (it is ServerPacket.AuthResult) result = it }
            if (result != null) return result
            if (!client.isConnected) return null // the server dropped us; no answer is coming
            Thread.sleep(10)
        }
        return null
    }

    private fun lanAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }?.hostAddress

    @Test
    fun `an invite code fingerprint lets a client log in over an encrypted connection with no setup`(@TempDir dir: Path) {
        start(dir, TlsMode.REQUIRED).use { server ->
            val fp = server.tlsFingerprint
            assertNotNull(fp)
            MorganaNetClient().use { client ->
                val result = client.connectSecure("127.0.0.1", server.port, ClientSecurity(tls = true, pinnedFingerprint = fp))
                assertEquals(ConnectResult.Connected(encrypted = true, fingerprint = fp), result)
                assertTrue(client.isEncrypted)
                val auth = login(client, "Secure")
                assertNotNull(auth)
                assertTrue(auth!!.success, auth.message)
                assertTrue(auth.message.contains("[encrypted]"), auth.message)
            }
        }
    }

    @Test
    fun `first contact asks for trust, then the remembered fingerprint connects silently`(@TempDir dir: Path) {
        start(dir, TlsMode.REQUIRED).use { server ->
            val known = InMemoryKnownHosts()
            val security = ClientSecurity(tls = true, knownHosts = known)
            MorganaNetClient().use { client ->
                val first = client.connectSecure("127.0.0.1", server.port, security)
                assertTrue(first is ConnectResult.NeedsTrust, first.toString())
                first as ConnectResult.NeedsTrust
                assertEquals(server.tlsFingerprint, first.fingerprint)
                assertFalse(client.isConnected, "nothing is sent before the player agrees")

                known.remember(first.host, first.port, first.fingerprint) // what the UI does when the player accepts
                assertTrue(client.connectSecure("127.0.0.1", server.port, security) is ConnectResult.Connected)
                assertTrue(login(client, "Trusting")!!.success)
            }
        }
    }

    @Test
    fun `a wrong invite fingerprint and a changed identity are refused`(@TempDir dir: Path) {
        start(dir, TlsMode.REQUIRED).use { server ->
            val real = server.tlsFingerprint!!
            val fake = "00".repeat(32)
            MorganaNetClient().use { client ->
                val mismatch = client.connectSecure("127.0.0.1", server.port, ClientSecurity(tls = true, pinnedFingerprint = fake))
                assertEquals(ConnectResult.InviteMismatch("127.0.0.1", server.port, fake, real), mismatch)

                val known = InMemoryKnownHosts().also { it.remember("127.0.0.1", server.port, fake) }
                val changed = client.connectSecure("127.0.0.1", server.port, ClientSecurity(tls = true, knownHosts = known))
                assertEquals(ConnectResult.IdentityChanged("127.0.0.1", server.port, fake, real), changed)
                assertFalse(client.isConnected)
            }
        }
    }

    @Test
    fun `required mode refuses plain-text clients`(@TempDir dir: Path) {
        start(dir, TlsMode.REQUIRED).use { server ->
            MorganaNetClient().use { plain ->
                plain.connect("127.0.0.1", server.port) // the TCP connect itself may succeed
                assertNull(login(plain, "Sneaky"), "a plain client must never get an answer")
                assertFalse(plain.isConnected, "and the server drops it")
            }
            assertEquals(0, server.coordinator.dataStore.accounts.count(), "no account was created")
        }
    }

    @Test
    fun `optional mode serves TLS and plain clients on the same port`(@TempDir dir: Path) {
        start(dir, TlsMode.OPTIONAL).use { server ->
            MorganaNetClient().use { plain ->
                assertTrue(plain.connect("127.0.0.1", server.port))
                val auth = login(plain, "PlainOne")!!
                assertTrue(auth.success)
                assertFalse(auth.message.contains("[encrypted]"))
            }
            MorganaNetClient().use { secure ->
                assertTrue(secure.connectSecure("127.0.0.1", server.port, ClientSecurity(true, server.tlsFingerprint)) is ConnectResult.Connected)
                assertTrue(login(secure, "SecureOne")!!.message.contains("[encrypted]"))
            }
        }
    }

    @Test
    fun `remote mode lets this machine in without TLS but makes everyone else use it`(@TempDir dir: Path) {
        start(dir, TlsMode.REMOTE).use { server ->
            MorganaNetClient().use { local ->
                assertTrue(local.connect("127.0.0.1", server.port))
                assertTrue(login(local, "Local")!!.success, "loopback plain play needs no setup")
            }
            // Connecting to this machine's LAN address makes the server see a non-loopback peer.
            val lan = lanAddress()
            assumeTrue(lan != null, "no LAN address on this machine; remote branch not exercised")
            MorganaNetClient().use { remotePlain ->
                remotePlain.connect(lan!!, server.port)
                assertNull(login(remotePlain, "RemotePlain"), "non-loopback plain is refused")
            }
            MorganaNetClient().use { remoteTls ->
                val result = remoteTls.connectSecure(lan!!, server.port, ClientSecurity(true, server.tlsFingerprint))
                assertTrue(result is ConnectResult.Connected, result.toString())
                assertTrue(login(remoteTls, "RemoteSecure")!!.message.contains("[encrypted]"))
            }
        }
    }

    @Test
    fun `a TLS client reports a clear failure against a server without TLS`(@TempDir dir: Path) {
        start(dir, null).use { server ->
            assertNull(server.tlsFingerprint)
            MorganaNetClient().use { client ->
                val result = client.connectSecure("127.0.0.1", server.port, ClientSecurity(tls = true))
                assertTrue(result is ConnectResult.Failed, result.toString())
                assertTrue((result as ConnectResult.Failed).message.contains("encryption"), result.message)
                assertFalse(client.isConnected, "never silently downgraded to plain text")
            }
        }
    }

    @Test
    fun `the certificate is created once and reused so invite codes keep working across restarts`(@TempDir dir: Path) {
        val first = start(dir, TlsMode.REQUIRED).use { it.tlsFingerprint }
        assertTrue(Files.exists(dir.resolve("tls/server.p12")))
        val second = start(dir, TlsMode.REQUIRED).use { it.tlsFingerprint }
        assertEquals(first, second)
        // Deleting it creates a new identity (clients will be warned).
        Files.delete(dir.resolve("tls/server.p12"))
        assertTrue(start(dir, TlsMode.REQUIRED).use { it.tlsFingerprint } != first)
    }

    @Test
    fun `an operator keystore is used and bad TLS setups stop the server instead of going unencrypted`(@TempDir dir: Path) {
        // An operator-supplied PKCS12 file whose password differs from the auto-generated one.
        val operator = SelfSignedCertificates.generate("Operator Cert")
        val ks = java.security.KeyStore.getInstance("PKCS12").also { it.load(null, null) }
        ks.setKeyEntry("k", operator.keyStore.getKey("morgana", operator.password) as java.security.PrivateKey, "s3cret".toCharArray(), arrayOf(operator.certificate))
        Files.newOutputStream(dir.resolve("op.p12")).use { ks.store(it, "s3cret".toCharArray()) }

        val config = TlsConfig(mode = "required", keystore = "op.p12", passwordEnv = "MORGANA_TEST_TLS_PW")
        // The password variable is not set in this process: startup must fail loudly.
        val missing = assertThrows(TlsSetupException::class.java) { start(dir, null, config) }
        assertTrue(missing.message!!.contains("MORGANA_TEST_TLS_PW"), missing.message)

        val missingFile = assertThrows(TlsSetupException::class.java) {
            start(dir, null, config.copy(keystore = "nope.p12"))
        }
        assertTrue(missingFile.message!!.contains("nope.p12"))

        // With the password supplied (via the loader's injectable environment) the operator certificate is served.
        val tls = com.morgana.server.tls.ServerTls.load(dir, config, env = { if (it == "MORGANA_TEST_TLS_PW") "s3cret" else null })
        assertEquals(Fingerprint.of(operator.certificate), tls!!.fingerprint)
        val wrong = assertThrows(TlsSetupException::class.java) {
            com.morgana.server.tls.ServerTls.load(dir, config, env = { "wrong-password" })
        }
        assertTrue(wrong.message!!.contains("wrong password"), wrong.message)
        assertFalse(Files.exists(dir.resolve("tls")), "operator keystore means no auto certificate is created")
    }
}
