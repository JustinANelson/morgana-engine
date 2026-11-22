package com.morgana.server

import com.morgana.persistence.AssetsConfig
import com.morgana.persistence.ServerConfig
import com.morgana.persistence.ServerConfigException
import com.morgana.persistence.TlsMode
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.PacketCodec
import com.morgana.protocol.ServerPacket
import com.morgana.server.assets.GameAssets
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
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
import javax.imageio.ImageIO

/** Game-owned art end to end: the server advertises a game's `assets/` in the login reply and serves the files over HTTP. */
class GameAssetsServingTest {

    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out)
        return out.toByteArray()
    }

    private fun gameWithArt(dir: Path, tiles: ByteArray): Path {
        // A full playable game (so login works) with art added to the copy.
        val game = TestGames.blank(dir)
        Files.createDirectories(game.resolve("assets/tilesets"))
        Files.write(game.resolve("assets/tilesets/1.png"), tiles)
        return game
    }

    private fun get(url: String): HttpResponse<ByteArray> =
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())

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

    private fun login(wsPort: Int): ServerPacket.AuthResult {
        val collector = Collector()
        val ws = HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:$wsPort/"), collector).get(5, TimeUnit.SECONDS)
        ws.sendText(PacketCodec.encodeClientJson(ClientPacket.Authenticate("Painter", "pw", true, 1, protocolVersion = PROTOCOL_VERSION)), true)
            .get(5, TimeUnit.SECONDS)
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val msg = collector.messages.poll(100, TimeUnit.MILLISECONDS) ?: continue
            (PacketCodec.decodeServerJson(msg) as? ServerPacket.AuthResult)?.let {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done")
                return it
            }
        }
        error("no login reply")
    }

    @Test
    fun `login advertises the game's art and the files download byte for byte`(@TempDir dir: Path) {
        val tiles = png(64, 64)
        val game = gameWithArt(dir, tiles)
        MorganaServerRuntime.start(dir.resolve("data"), port = 0, watchContent = false, webSocketPort = 0, gameDir = game).use { server ->
            val wsPort = server.webSocketPort!!
            val auth = login(wsPort)
            assertTrue(auth.success)
            val entry = auth.assetManifest!!.assets.getValue("tilesets/1.png")
            assertEquals(GameAssets.sha256Hex(tiles), entry.sha256)
            assertEquals("http://{host}:$wsPort/assets", auth.assetBaseUrl)

            val res = get("http://127.0.0.1:$wsPort/assets/tilesets/1.png")
            assertEquals(200, res.statusCode())
            assertEquals("image/png", res.headers().firstValue("content-type").orElse(""))
            assertArrayEquals(tiles, res.body())
        }
    }

    @Test
    fun `art is revalidated, never cached blindly, so edited art reaches returning players`(@TempDir dir: Path) {
        val tiles = png(64, 64)
        val game = gameWithArt(dir, tiles)
        MorganaServerRuntime.start(dir.resolve("data"), port = 0, watchContent = false, webSocketPort = 0, gameDir = game).use { server ->
            val url = "http://127.0.0.1:${server.webSocketPort}/assets/tilesets/1.png"
            val etag = "\"${GameAssets.sha256Hex(tiles)}\""

            val first = get(url)
            assertEquals(etag, first.headers().firstValue("etag").orElse(""))
            val cacheControl = first.headers().firstValue("cache-control").orElse("")
            assertTrue("no-cache" in cacheControl && "immutable" !in cacheControl, cacheControl)

            val conditional = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).header("If-None-Match", etag).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            )
            assertEquals(304, conditional.statusCode())
            assertEquals(0, conditional.body().size)
            assertEquals(etag, conditional.headers().firstValue("etag").orElse(""))

            val stale = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).header("If-None-Match", "\"0000\"").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            )
            assertEquals(200, stale.statusCode())
            assertArrayEquals(tiles, stale.body())
        }
    }

    @Test
    fun `only manifest files are served, never arbitrary paths`(@TempDir dir: Path) {
        val game = gameWithArt(dir, png(32, 32))
        Files.writeString(game.resolve("game-secret.txt"), "secret")
        MorganaServerRuntime.start(dir.resolve("data"), port = 0, watchContent = false, webSocketPort = 0, gameDir = game).use { server ->
            val base = "http://127.0.0.1:${server.webSocketPort}"
            assertEquals(404, get("$base/assets/tilesets/2.png").statusCode())
            assertEquals(404, get("$base/assets/../game-secret.txt").statusCode())
            assertEquals(404, get("$base/assets/tilesets/../../game-secret.txt").statusCode())
            assertEquals(404, get("$base/assets/%2e%2e/game-secret.txt").statusCode())
            assertEquals(404, get("$base/assets/").statusCode())
        }
    }

    @Test
    fun `a game without art sends no manifest`(@TempDir dir: Path) {
        MorganaServerRuntime.start(dir.resolve("data"), port = 0, watchContent = false, webSocketPort = 0).use { server ->
            val auth = login(server.webSocketPort!!)
            assertTrue(auth.success)
            assertNull(auth.assetManifest)
            assertNull(auth.assetBaseUrl)
        }
    }

    @Test
    fun `without a WebSocket port the art cannot be served so none is advertised`(@TempDir dir: Path) {
        val game = gameWithArt(dir, png(32, 32))
        assertNull(MorganaServerRuntime.resolveAssetBaseUrl(null, null, null))
        MorganaServerRuntime.start(dir.resolve("data"), port = 0, watchContent = false, webSocketPort = null, gameDir = game).use { server ->
            assertNull(server.coordinator.assetBaseUrl)
            assertNotNull(server.coordinator.gameAssets.manifest.assets["tilesets/1.png"])
        }
    }

    @Test
    fun `an operator base URL overrides the built-in one`() {
        assertEquals("https://cdn.example.com/hearthvale", MorganaServerRuntime.resolveAssetBaseUrl(AssetsConfig("https://cdn.example.com/hearthvale/"), 8081, null))
        assertEquals("http://{host}:8081/assets", MorganaServerRuntime.resolveAssetBaseUrl(AssetsConfig(""), 8081, null))
    }

    @Test
    fun `server json validates assets baseUrl`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("server.json"), """{"schemaVersion":1,"assets":{"baseUrl":"ftp://x"}}""")
        assertThrows(ServerConfigException::class.java) { ServerConfig.load(dir) }
        Files.writeString(dir.resolve("server.json"), """{"schemaVersion":1,"assets":{"baseUrl":"https://cdn.example.com/a"}}""")
        assertEquals("https://cdn.example.com/a", ServerConfig.load(dir).assets?.baseUrl)
    }
}
