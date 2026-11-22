package com.morgana.client.desktop

import com.morgana.client.net.MorganaNetClient
import com.morgana.persistence.ConnectionsConfig
import com.morgana.persistence.ServerConfig
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import com.morgana.server.MorganaServerRuntime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Path

/**
 * Real sockets. Connecting to this machine's LAN address makes the server see a non-loopback peer (loopback is exempt
 * from all connection limits), so the limits can be exercised without a second computer.
 */
class Phase19ConnectionLimitsEndToEndTest {

    private fun lanAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }?.hostAddress

    private fun start(dir: Path, connections: ConnectionsConfig): MorganaServerRuntime {
        ServerConfig.save(dir, ServerConfig(connections = connections))
        return MorganaServerRuntime.start(dir, port = 0, watchContent = false)
    }

    private fun login(client: MorganaNetClient, name: String): ServerPacket.AuthResult? {
        client.send(ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            var result: ServerPacket.AuthResult? = null
            client.drainPackets { if (it is ServerPacket.AuthResult) result = it }
            if (result != null) return result
            Thread.sleep(20)
        }
        return null
    }

    private fun waitUntilDisconnected(client: MorganaNetClient, timeoutMs: Long = 4000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            client.drainPackets { }
            if (!client.isConnected) return true
            Thread.sleep(20)
        }
        return false
    }

    /** True if a connection to [host] is accepted and stays usable (a login gets answered). */
    private fun canPlay(host: String, port: Int, name: String): Boolean = MorganaNetClient().use { c ->
        c.connect(host, port) && login(c, name) != null
    }

    @Test
    fun `an address is limited to its share of connections while this machine is not`(@TempDir dir: Path) {
        val lan = lanAddress()
        assumeTrue(lan != null, "no LAN address on this machine")
        start(dir, ConnectionsConfig(maxPerIp = 2)).use { server ->
            val kept = (1..2).map { n -> MorganaNetClient().also { assertTrue(it.connect(lan!!, server.port), "connection $n") } }
            MorganaNetClient().use { third ->
                third.connect(lan!!, server.port)
                assertTrue(waitUntilDisconnected(third), "the third connection from the same address is refused")
            }
            assertTrue(kept.all { it.isConnected }, "existing connections are unaffected")
            assertTrue(canPlay("127.0.0.1", server.port, "LocalPlayer"), "loopback has no limit")

            kept.first().close()
            Thread.sleep(100) // let the server notice the close
            assertTrue(canPlay(lan!!, server.port, "Freed"), "a freed slot can be used again")
            kept.forEach { it.close() }
        }
    }

    @Test
    fun `a flooding address is banned, other addresses keep playing, and the ban outlives a restart and an unban lifts it`(@TempDir dir: Path) {
        val lan = lanAddress()
        assumeTrue(lan != null, "no LAN address on this machine")
        val config = ConnectionsConfig(strikesToBan = 1, banMinutes = 30)
        var banKey: String
        start(dir, config).use { server ->
            MorganaNetClient().use { attacker ->
                assertTrue(attacker.connect(lan!!, server.port))
                assertNotNull(login(attacker, "Attacker"))
                repeat(3000) { attacker.send(ClientPacket.Ping(it.toLong())) }
                assertTrue(waitUntilDisconnected(attacker), "message storm closes the connection")
            }
            val bans = server.connectionGate!!.bans()
            assertEquals(1, bans.size, bans.toString())
            banKey = bans.single().key
            assertEquals(lan, banKey)

            MorganaNetClient().use { again ->
                again.connect(lan!!, server.port)
                assertTrue(waitUntilDisconnected(again), "a banned address cannot reconnect")
            }
            assertTrue(canPlay("127.0.0.1", server.port, "StillFine"), "this machine is unaffected")
        }

        // Restart: the ban is read back from ip-bans.json.
        start(dir, config).use { server ->
            assertEquals(listOf(banKey), server.connectionGate!!.bans().map { it.key })
            MorganaNetClient().use { c ->
                c.connect(lan!!, server.port)
                assertTrue(waitUntilDisconnected(c), "still banned after a restart")
            }
            assertTrue(server.connectionGate!!.unban(InetAddress.getByName(lan)))
            assertTrue(canPlay(lan!!, server.port, "Forgiven"), "allowed again once unbanned")
        }
    }

    @Test
    fun `a connection that never logs in is dropped after the pre-login timeout`(@TempDir dir: Path) {
        val lan = lanAddress()
        assumeTrue(lan != null, "no LAN address on this machine")
        start(dir, ConnectionsConfig(preLoginSeconds = 5)).use { server ->
            // Both connections live through the same timeout window: the silent one is dropped, the logged-in one is not.
            MorganaNetClient().use { idle ->
                MorganaNetClient().use { player ->
                    assertTrue(idle.connect(lan!!, server.port))
                    assertTrue(player.connect(lan, server.port))
                    assertNotNull(login(player, "Prompt"))
                    assertTrue(idle.isConnected, "still within the grace period")
                    assertTrue(waitUntilDisconnected(idle, 7000), "closed once the timeout passed")
                    assertTrue(player.isConnected, "logged-in players are never timed out for being idle")
                }
            }
        }
    }

    @Test
    fun `the allow-list exempts an address from every limit`(@TempDir dir: Path) {
        val lan = lanAddress()
        assumeTrue(lan != null, "no LAN address on this machine")
        start(dir, ConnectionsConfig(maxPerIp = 1, allow = listOf("$lan/32"))).use { server ->
            val clients = (1..4).map { MorganaNetClient().also { c -> assertTrue(c.connect(lan!!, server.port)) } }
            Thread.sleep(150)
            assertTrue(clients.all { it.isConnected }, "allow-listed address may exceed maxPerIp")
            assertFalse(server.connectionGate!!.refusedCounts().containsKey(com.morgana.server.GateVerdict.TOO_MANY_FROM_ADDRESS))
            clients.forEach { it.close() }
        }
    }
}
