package com.morgana.server

import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.PacketCodec
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Path
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The browser transport: the same JSON packets as raw TCP, carried one per WebSocket text frame. */
class WebSocketTransportTest {

    private class Collector : WebSocket.Listener {
        val messages = LinkedBlockingQueue<String>()
        private val partial = StringBuilder()

        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            partial.append(data)
            if (last) {
                messages.add(partial.toString())
                partial.setLength(0)
            }
            ws.request(1)
            return null
        }
    }

    private fun connect(port: Int, collector: Collector): WebSocket =
        HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:$port/"), collector)
            .get(5, TimeUnit.SECONDS)

    @Test
    fun `a WebSocket client can log in and receive server packets`(@TempDir dir: Path) {
        MorganaServerRuntime.start(dir, port = 0, watchContent = false, webSocketPort = 0).use { server ->
            val wsPort = server.webSocketPort!!
            val collector = Collector()
            val ws = connect(wsPort, collector)
            ws.sendText(
                PacketCodec.encodeClientJson(ClientPacket.Authenticate("WebPlayer", "pw", true, 1, protocolVersion = PROTOCOL_VERSION)),
                true
            ).get(5, TimeUnit.SECONDS)

            var auth: ServerPacket.AuthResult? = null
            val deadline = System.currentTimeMillis() + 5000
            while (auth == null && System.currentTimeMillis() < deadline) {
                val msg = collector.messages.poll(100, TimeUnit.MILLISECONDS) ?: continue
                auth = PacketCodec.decodeServerJson(msg) as? ServerPacket.AuthResult
            }
            assertNotNull(auth, "login answered over WebSocket")
            assertTrue(auth!!.success)
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `WebSocket is off unless a port is given`(@TempDir dir: Path) {
        MorganaServerRuntime.start(dir, port = 0, watchContent = false, webSocketPort = null).use { server ->
            assertNull(server.webSocketPort)
            assertEquals(true, server.port > 0)
        }
    }
}
