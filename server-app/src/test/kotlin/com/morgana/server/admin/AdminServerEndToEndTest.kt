package com.morgana.server.admin

import com.morgana.persistence.AdminConfig
import com.morgana.persistence.ServerConfig
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import com.morgana.server.ClientSession
import com.morgana.server.MorganaServerRuntime
import com.morgana.server.moderation.SanctionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

class AdminServerEndToEndTest {
    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val outbox = mutableListOf<ServerPacket>()
        @Volatile var closedWith: String? = null
        override fun send(packet: ServerPacket) { synchronized(outbox) { outbox += packet } }
        override fun close(reason: String) { closedWith = reason }
        fun messages(channel: ChatChannel) = synchronized(outbox) { outbox.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == channel }.map { it.message } }
        fun lastAuth() = synchronized(outbox) { outbox.filterIsInstance<ServerPacket.AuthResult>().last() }
    }

    private val http = HttpClient.newHttpClient()

    private fun send(port: Int, method: String, path: String, token: String? = null, body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        if (token != null) builder.header("Authorization", "Bearer $token")
        val publisher = if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody()
        builder.method(method, publisher)
        if (body != null) builder.header("Content-Type", "application/json")
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun login(runtime: MorganaServerRuntime, name: String): FakeSession {
        val s = FakeSession("s-$name-${System.nanoTime()}")
        runtime.coordinator.onSessionConnected(s)
        runtime.coordinator.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    @Test
    fun `the panel and API work over a real socket and act on the running world`(@TempDir dir: Path) {
        ServerConfig.save(dir, ServerConfig(admin = AdminConfig(enabled = true, port = 0)))
        MorganaServerRuntime.start(dir, port = 0, watchContent = false, recordReplayTo = null, webSocketPort = null).use { runtime ->
            val port = runtime.adminPort!!
            val token = Files.readString(dir.resolve(AdminToken.FILE_NAME)).trim()
            assertEquals(64, token.length)

            val page = send(port, "GET", "/")
            assertEquals(200, page.statusCode())
            assertTrue(page.body().contains("Morgana Admin"))
            assertTrue(page.headers().firstValue("Content-Security-Policy").isPresent)
            assertTrue(send(port, "GET", "/app.js").body().contains("Bearer"))
            assertTrue(send(port, "GET", "/admin.css").statusCode() == 200)
            assertEquals(401, send(port, "GET", "/api/status").statusCode())
            assertEquals(401, send(port, "GET", "/api/players", token = "wrong").statusCode())

            val alpha = login(runtime, "Alpha")
            val bravo = login(runtime, "Bravo")
            val players = send(port, "GET", "/api/players", token).body()
            assertTrue(players.contains("\"name\":\"Alpha\"") && players.contains("\"name\":\"Bravo\""), players)
            assertTrue(send(port, "GET", "/api/status", token).body().contains("\"playersOnline\":2"))

            // kick
            val kick = send(port, "POST", "/api/players/kick", token, """{"name":"alpha","reason":"testing"}""")
            assertEquals(200, kick.statusCode(), kick.body())
            assertNotNull(alpha.closedWith)
            assertTrue(alpha.closedWith!!.contains("testing"))
            assertFalse(send(port, "GET", "/api/players", token).body().contains("Alpha"))
            assertEquals(400, send(port, "POST", "/api/players/kick", token, """{"name":"Nobody"}""").statusCode())

            // mute, then lift
            assertEquals(200, send(port, "POST", "/api/players/mute", token, """{"name":"Bravo","duration":"1h","reason":"spam"}""").statusCode())
            val accountId = runtime.coordinator.dataStore.characters.findByName("Bravo")!!.accountId
            assertNotNull(runtime.coordinator.moderation.active(SanctionKind.MUTE, accountId))
            assertTrue(bravo.messages(ChatChannel.SYSTEM).any { it.contains("muted") })
            assertTrue(send(port, "GET", "/api/sanctions?kind=mute", token).body().contains("Bravo"))
            assertEquals(200, send(port, "POST", "/api/players/unmute", token, """{"name":"Bravo"}""").statusCode())
            assertNull(runtime.coordinator.moderation.active(SanctionKind.MUTE, accountId))

            // ban stops a new login, unban allows it again
            assertEquals(200, send(port, "POST", "/api/players/ban", token, """{"name":"Bravo","reason":"cheating"}""").statusCode())
            assertNotNull(bravo.closedWith)
            val blocked = login(runtime, "Bravo")
            assertFalse(blocked.lastAuth().success)
            assertTrue(blocked.lastAuth().message.contains("banned"), blocked.lastAuth().message)
            assertEquals(200, send(port, "POST", "/api/players/unban", token, """{"name":"Bravo"}""").statusCode())
            assertTrue(login(runtime, "Bravo").lastAuth().success)

            // announcements reach everyone online
            val carol = login(runtime, "Carol")
            assertEquals(200, send(port, "POST", "/api/broadcast", token, """{"message":"Maintenance soon"}""").statusCode())
            assertTrue(carol.messages(ChatChannel.BROADCAST).contains("Maintenance soon"))

            // reports
            val filed = runtime.coordinator.moderation.report(accountId, "Bravo", "Carol", "rude")
            val id = (filed as com.morgana.server.moderation.ModerationService.ReportResult.Filed).report.id
            assertTrue(send(port, "GET", "/api/reports", token).body().contains("\"reason\":\"rude\""))
            assertEquals(200, send(port, "POST", "/api/reports/close", token, """{"id":$id,"note":"done"}""").statusCode())
            assertTrue(runtime.coordinator.moderation.openReports().isEmpty())

            // every action is in the audit log under the panel's name
            val audit = send(port, "GET", "/api/audit?limit=100", token).body()
            for (action in listOf("kick", "mute", "unmute", "ban", "unban", "broadcast", "report-closed")) assertTrue(audit.contains("\"action\":\"$action\""), "audit lacks $action: $audit")
            assertTrue(audit.contains("\"actor\":\"web-admin\""))

            // content reload through the same pipeline as /reload
            assertEquals(200, send(port, "POST", "/api/reload", token).statusCode())
        }
    }

    @Test
    fun `the panel is off unless the config turns it on`(@TempDir dir: Path) {
        MorganaServerRuntime.start(dir, port = 0, watchContent = false, recordReplayTo = null, webSocketPort = null).use { runtime ->
            assertNull(runtime.adminPort)
            assertFalse(Files.exists(dir.resolve(AdminToken.FILE_NAME)), "no token is made for a panel that is off")
        }
    }
}
