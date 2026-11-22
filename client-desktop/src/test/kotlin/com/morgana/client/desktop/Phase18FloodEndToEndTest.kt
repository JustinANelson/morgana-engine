package com.morgana.client.desktop

import com.morgana.client.net.MorganaNetClient
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import com.morgana.server.MorganaServerRuntime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Floods over real sockets: the offender is disconnected, everyone else keeps playing. */
class Phase18FloodEndToEndTest {

    private fun login(client: MorganaNetClient, name: String): ServerPacket.AuthResult? {
        client.send(ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
        return waitFor(client) { it as? ServerPacket.AuthResult }
    }

    private fun <T : Any> waitFor(client: MorganaNetClient, timeoutMs: Long = 5000, pick: (ServerPacket) -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var found: T? = null
            client.drainPackets { p -> pick(p)?.let { found = it } }
            if (found != null) return found
            Thread.sleep(20)
        }
        return null
    }

    private fun waitUntilDisconnected(client: MorganaNetClient, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            client.drainPackets { }
            if (!client.isConnected) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun `a message storm is cut off at the socket while another player is unaffected`(@TempDir dir: Path) {
        MorganaServerRuntime.start(dir, port = 0, watchContent = false).use { server ->
            MorganaNetClient().use { bystander ->
                assertTrue(bystander.connect("127.0.0.1", server.port))
                assertTrue(login(bystander, "Bystander")!!.success)

                MorganaNetClient().use { attacker ->
                    assertTrue(attacker.connect("127.0.0.1", server.port))
                    assertTrue(login(attacker, "Attacker")!!.success)
                    repeat(3000) { attacker.send(ClientPacket.Ping(it.toLong())) }
                    assertTrue(waitUntilDisconnected(attacker), "the flooding connection was closed by the server")
                }

                // The bystander still gets service: a ping is answered.
                bystander.send(ClientPacket.Ping(42))
                assertNotNull(waitFor(bystander) { it as? ServerPacket.Pong }, "server still healthy")
            }
        }
    }

    @Test
    fun `an oversized message from someone who has not logged in closes the connection`(@TempDir dir: Path) {
        MorganaServerRuntime.start(dir, port = 0, watchContent = false).use { server ->
            MorganaNetClient().use { stranger ->
                assertTrue(stranger.connect("127.0.0.1", server.port))
                stranger.send(ClientPacket.SendChat(ChatChannel.MAP, "A".repeat(20_000)))
                assertTrue(waitUntilDisconnected(stranger), "20 KB before login is refused")
            }
            assertEquals(0, server.coordinator.dataStore.accounts.count())
        }
    }

    @Test
    fun `chat spam over a real connection is limited to the burst and then ends in a disconnect`(@TempDir dir: Path) {
        MorganaServerRuntime.start(dir, port = 0, watchContent = false).use { server ->
            MorganaNetClient().use { listener ->
                assertTrue(listener.connect("127.0.0.1", server.port))
                assertTrue(login(listener, "Listener")!!.success)
                MorganaNetClient().use { spammer ->
                    assertTrue(spammer.connect("127.0.0.1", server.port))
                    assertTrue(login(spammer, "Spammer")!!.success)
                    repeat(250) { spammer.send(ClientPacket.SendChat(ChatChannel.MAP, "spam $it")) }
                    assertTrue(waitUntilDisconnected(spammer), "sustained spam ends the connection")
                }
                var heard = 0
                val deadline = System.currentTimeMillis() + 1500
                while (System.currentTimeMillis() < deadline) {
                    listener.drainPackets { if (it is ServerPacket.ChatBroadcast && it.message.startsWith("spam")) heard++ }
                    Thread.sleep(20)
                }
                assertTrue(heard in 1..8, "only a handful of the 250 messages were delivered, heard=$heard")
                assertFalse(heard > 12)
            }
            assertTrue(server.coordinator.limiter.kickCount() >= 1)
        }
    }
}
