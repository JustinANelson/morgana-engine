package com.morgana.client.desktop

import com.morgana.client.net.MorganaNetClient
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import com.morgana.server.MorganaServerRuntime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** A phone loses its connection mid-game: over real sockets the player gets the same character back. */
class ReconnectEndToEndTest {

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

    private fun login(client: MorganaNetClient, name: String): ServerPacket.AuthResult =
        client.run {
            send(ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
            waitFor(this) { it as? ServerPacket.AuthResult }!!
        }

    private fun resume(client: MorganaNetClient, token: String): ServerPacket.AuthResult =
        client.run {
            send(ClientPacket.ResumeSession(token, PROTOCOL_VERSION))
            waitFor(this) { it as? ServerPacket.AuthResult }!!
        }

    @Test
    fun `an abruptly dropped client resumes the same character with its token`(@TempDir dir: Path) {
        MorganaServerRuntime.start(dir, port = 0, watchContent = false).use { server ->
            val first = MorganaNetClient()
            assertTrue(first.connect("127.0.0.1", server.port))
            val auth = login(first, "Roamer")
            assertTrue(auth.success)
            val token = auth.resumeToken
            assertNotNull(token)
            val charId = auth.character!!.id

            first.close() // the connection vanishes, like a phone leaving the app

            MorganaNetClient().use { second ->
                assertTrue(second.connect("127.0.0.1", server.port))
                // Works whether or not the server has noticed the first socket die yet (takeover covers the race).
                val result = resume(second, token!!)
                assertTrue(result.success, result.message)
                assertEquals(charId, result.character?.id)
                assertNotNull(result.resumeToken)
                assertNotEquals(token, result.resumeToken)

                // The resumed session is fully live.
                second.send(ClientPacket.Ping(7))
                assertNotNull(waitFor(second) { it as? ServerPacket.Pong })
            }
        }
    }

    @Test
    fun `a second device with a live token evicts the first, which is told not to reconnect`(@TempDir dir: Path) {
        MorganaServerRuntime.start(dir, port = 0, watchContent = false).use { server ->
            MorganaNetClient().use { phone ->
                assertTrue(phone.connect("127.0.0.1", server.port))
                val token = login(phone, "TwoDevices").resumeToken!!

                MorganaNetClient().use { tablet ->
                    assertTrue(tablet.connect("127.0.0.1", server.port))
                    assertTrue(resume(tablet, token).success)
                }

                val ended = waitFor(phone) { it as? ServerPacket.SessionEnded }
                assertNotNull(ended, "the evicted device is told why")
                assertFalse(ended!!.resumable)
            }
        }
    }
}
