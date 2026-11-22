package com.morgana.server

import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.PacketCodec
import com.morgana.protocol.ServerPacket
import com.morgana.server.web.WebClientHttpHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The server hosts the browser client on its WebSocket port, so a phone only needs `http://<server>:<port>/`. */
class WebClientServingTest {

    private fun webRoot(dir: Path): Path {
        val web = Files.createDirectories(dir.resolve("web"))
        Files.writeString(web.resolve("index.html"), "<html><body>Morgana</body></html>")
        Files.writeString(web.resolve("app.js"), "console.log('hi')")
        Files.createDirectories(web.resolve("assets"))
        Files.writeString(web.resolve("assets/skin.json"), "{}")
        Files.writeString(dir.resolve("secret.txt"), "do not serve")
        return web
    }

    @Test
    fun `requests resolve only to files inside the web folder`(@TempDir dir: Path) {
        val web = webRoot(dir)
        val h = WebClientHttpHandler(web)
        val root = web.toAbsolutePath().normalize()
        assertEquals(root.resolve("index.html"), h.resolve("/"))
        assertEquals(root.resolve("index.html"), h.resolve("/?port=7002"))
        assertEquals(root.resolve("app.js"), h.resolve("/app.js"))
        assertEquals(root.resolve("assets/skin.json"), h.resolve("/assets/skin.json"))
        assertNull(h.resolve("/missing.js"))
        assertNull(h.resolve("/../secret.txt"))
        assertNull(h.resolve("/assets/../../secret.txt"))
        assertNull(h.resolve("/%2e%2e/secret.txt"))
        assertNull(h.resolve("/..%5csecret.txt"))
        assertNull(h.resolve("/C:/Windows/win.ini"))
        assertEquals("text/javascript; charset=utf-8", WebClientHttpHandler.contentType("app.js"))
        assertEquals("text/html; charset=utf-8", WebClientHttpHandler.contentType("index.html"))
    }

    private class Collector : WebSocket.Listener {
        val messages = LinkedBlockingQueue<String>()
        private val partial = StringBuilder()
        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            partial.append(data)
            if (last) { messages.add(partial.toString()); partial.setLength(0) }
            ws.request(1)
            return null
        }
    }

    @Test
    fun `the page and the game share one port`(@TempDir dir: Path) {
        val web = webRoot(dir)
        MorganaServerRuntime.start(dir.resolve("data"), port = 0, watchContent = false, webSocketPort = 0, webClientDir = web).use { server ->
            val port = server.webSocketPort!!
            val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            fun get(path: String) = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

            val page = get("/")
            assertEquals(200, page.statusCode())
            assertTrue(page.body().contains("Morgana"))
            assertTrue(page.headers().firstValue("content-type").get().startsWith("text/html"))
            assertEquals("console.log('hi')", get("/app.js").body())
            // The client's own files sit under /assets/ too, next to the game's art served from the same prefix.
            assertEquals("{}", get("/assets/skin.json").body())
            assertEquals(404, get("/../secret.txt").statusCode())
            assertEquals(404, get("/missing.js").statusCode())

            // The WebSocket upgrade on the same port still reaches the game.
            val collector = Collector()
            val ws = http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:$port/"), collector).get(5, TimeUnit.SECONDS)
            ws.sendText(PacketCodec.encodeClientJson(ClientPacket.Authenticate("Phone", "pw", true, 1, protocolVersion = PROTOCOL_VERSION)), true).get(5, TimeUnit.SECONDS)
            var auth: ServerPacket.AuthResult? = null
            val deadline = System.currentTimeMillis() + 5000
            while (auth == null && System.currentTimeMillis() < deadline) {
                val msg = collector.messages.poll(100, TimeUnit.MILLISECONDS) ?: continue
                auth = PacketCodec.decodeServerJson(msg) as? ServerPacket.AuthResult
            }
            assertNotNull(auth)
            assertTrue(auth!!.success)
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `a server without a web folder does not serve pages`(@TempDir dir: Path) {
        MorganaServerRuntime.start(dir, port = 0, watchContent = false, webSocketPort = 0).use { server ->
            val response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.webSocketPort}/app.js")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(404, response.statusCode(), "answered, not left hanging")
        }
    }
}
