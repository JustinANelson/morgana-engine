package com.morgana.server.admin

import com.morgana.server.moderation.AuditEntry
import com.morgana.server.moderation.Report
import com.morgana.server.moderation.Sanction
import com.morgana.server.moderation.SanctionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdminApiTest {
    private class FakeBackend : AdminBackend {
        val calls = mutableListOf<String>()
        override fun status() = AdminStatus(5L, 100L, 2, 3, 4, 1, 0.5, 1.5, 10, 100, 4, listOf("mail"), 1, 0, 0)
        override fun metrics() = listOf(AdminMetricSample(1L, 2, 0.4))
        override fun players() = listOf(AdminPlayer("c1", "Alpha", "a1", 3, 1, 4, 5, "Player"))
        override fun kick(name: String, reason: String, actor: String): AdminResult { calls += "kick $name|$reason|$actor"; return AdminResult(true, "Kicked $name.") }
        override fun sanction(kind: SanctionKind, name: String, durationMillis: Long, reason: String, actor: String): AdminResult {
            calls += "sanction $kind $name|$durationMillis|$reason|$actor"
            return if (name == "Ghost") AdminResult(false, "No player named Ghost.") else AdminResult(true, "done")
        }
        override fun lift(kind: SanctionKind, name: String, actor: String): AdminResult { calls += "lift $kind $name|$actor"; return AdminResult(true, "lifted") }
        override fun sanctions(kind: SanctionKind) = listOf(Sanction("a1", "Alpha", kind, "spam", "web-admin", 10L))
        override fun openReports() = listOf(Report(7, "a2", "Bravo", "Alpha", "rude", 5L))
        override fun closeReport(id: Int, note: String, actor: String): AdminResult { calls += "close $id|$note|$actor"; return AdminResult(true, "Closed report #$id.") }
        override fun audit(name: String?, limit: Int): List<AuditEntry> { calls += "audit $name|$limit"; return listOf(AuditEntry(1L, "x", "kick", "y")) }
        override fun reloadContent(actor: String): AdminResult { calls += "reload $actor"; return AdminResult(true, "Reloaded") }
        override fun broadcast(message: String, actor: String): AdminResult { calls += "broadcast $message|$actor"; return AdminResult(true, "sent") }
    }

    private val token = "secret-token-1234567890"
    private val backend = FakeBackend()
    private var now = 1_000L
    private val api = AdminApi(backend, token, clock = { now }, resource = { name -> if (name == "missing.js") null else "content of $name".toByteArray() })

    private fun call(method: String, uri: String, body: String = "", auth: String? = "Bearer $token", remote: String = "127.0.0.1") =
        api.handle(method, uri, body, auth, remote)

    private fun AdminResponse.text() = String(body, Charsets.UTF_8)

    @Test
    fun `the panel files are served without a token, with a locked-down policy`() {
        val page = call("GET", "/", auth = null)
        assertEquals(200, page.status)
        assertTrue(page.contentType.startsWith("text/html"))
        assertTrue(page.headers.getValue("Content-Security-Policy").contains("default-src 'none'"))
        assertFalse(page.headers.getValue("Content-Security-Policy").contains("unsafe-inline"))
        assertEquals("nosniff", page.headers["X-Content-Type-Options"])
        assertEquals("no-store", page.headers["Cache-Control"])
        assertEquals("content of app.js", call("GET", "/app.js", auth = null).text())
        assertTrue(call("GET", "/admin.css", auth = null).contentType.startsWith("text/css"))
        assertEquals(405, call("POST", "/", auth = null).status)
        assertEquals(404, call("GET", "/secrets.txt", auth = null).status)
        assertEquals(404, call("GET", "/../server.json", auth = null).status)
    }

    @Test
    fun `the API needs the exact token`() {
        assertEquals(401, call("GET", "/api/status", auth = null).status)
        assertEquals(401, call("GET", "/api/status", auth = "Bearer wrong").status)
        assertEquals(401, call("GET", "/api/status", auth = token).status, "the Bearer word is required")
        assertEquals(401, call("GET", "/api/status", auth = "Bearer ${token}x").status)
        assertEquals(200, call("GET", "/api/status").status)
        assertEquals(200, call("GET", "/api/status", auth = "bearer $token").status)
    }

    @Test
    fun `too many wrong tokens from one address are refused for a minute, other addresses are not affected`() {
        repeat(AdminApi.MAX_AUTH_FAILURES) { assertEquals(401, call("GET", "/api/status", auth = "Bearer no", remote = "9.9.9.9").status) }
        assertEquals(429, call("GET", "/api/status", auth = "Bearer no", remote = "9.9.9.9").status)
        assertEquals(429, call("GET", "/api/status", remote = "9.9.9.9").status, "even the right token waits")
        assertEquals(200, call("GET", "/api/status", remote = "8.8.8.8").status)
        now += 61_000
        assertEquals(200, call("GET", "/api/status", remote = "9.9.9.9").status)
    }

    @Test
    fun `read endpoints return JSON`() {
        assertTrue(call("GET", "/api/status").text().contains("\"playersOnline\":2"))
        assertTrue(call("GET", "/api/metrics").text().contains("\"tickAvgMs\":0.4"))
        assertTrue(call("GET", "/api/players").text().contains("\"name\":\"Alpha\""))
        assertTrue(call("GET", "/api/reports").text().contains("\"reporterName\":\"Bravo\""))
        assertTrue(call("GET", "/api/sanctions?kind=ban").text().contains("\"kind\":\"BAN\""))
        assertTrue(call("GET", "/api/sanctions?kind=mute").text().contains("\"kind\":\"MUTE\""))
        assertEquals(400, call("GET", "/api/sanctions").status)
        assertEquals(400, call("GET", "/api/sanctions?kind=other").status)
        assertTrue(call("GET", "/api/audit").status == 200)
        assertEquals("application/json; charset=utf-8", call("GET", "/api/status").contentType)
    }

    @Test
    fun `audit takes a name and a clamped limit`() {
        call("GET", "/api/audit?name=Al%20pha&limit=9999")
        call("GET", "/api/audit?limit=abc")
        call("GET", "/api/audit?name=")
        assertEquals(listOf("audit Al pha|${AdminApi.MAX_AUDIT}", "audit null|${AdminApi.DEFAULT_AUDIT}", "audit null|${AdminApi.DEFAULT_AUDIT}"), backend.calls)
    }

    @Test
    fun `actions reach the backend named as the web panel`() {
        assertEquals(200, call("POST", "/api/players/kick", """{"name":"Alpha","reason":"afk"}""").status)
        assertEquals(200, call("POST", "/api/players/mute", """{"name":"Alpha","duration":"30m","reason":"spam"}""").status)
        assertEquals(200, call("POST", "/api/players/unmute", """{"name":"Alpha"}""").status)
        assertEquals(200, call("POST", "/api/players/ban", """{"name":"Alpha","duration":"2h","reason":"cheat"}""").status)
        assertEquals(200, call("POST", "/api/players/ban", """{"name":"Alpha","reason":"forever"}""").status)
        assertEquals(200, call("POST", "/api/players/unban", """{"name":"Alpha"}""").status)
        assertEquals(200, call("POST", "/api/reports/close", """{"id":7,"note":"handled"}""").status)
        assertEquals(200, call("POST", "/api/reload").status)
        assertEquals(200, call("POST", "/api/broadcast", """{"message":" Hello all "}""").status)
        assertEquals(
            listOf(
                "kick Alpha|afk|web-admin",
                "sanction MUTE Alpha|1800000|spam|web-admin",
                "lift MUTE Alpha|web-admin",
                "sanction BAN Alpha|7200000|cheat|web-admin",
                "sanction BAN Alpha|0|forever|web-admin",
                "lift BAN Alpha|web-admin",
                "close 7|handled|web-admin",
                "reload web-admin",
                "broadcast Hello all|web-admin"
            ),
            backend.calls
        )
    }

    @Test
    fun `a refused action is a 400 with the reason`() {
        val r = call("POST", "/api/players/ban", """{"name":"Ghost","reason":"x"}""")
        assertEquals(400, r.status)
        assertEquals("""{"error":"No player named Ghost."}""", r.text())
    }

    @Test
    fun `bad requests are refused before they reach the backend`() {
        assertEquals(400, call("POST", "/api/players/kick", """{"reason":"no name"}""").status)
        assertEquals(400, call("POST", "/api/players/kick", """{"name":"${"x".repeat(AdminApi.MAX_NAME + 1)}"}""").status)
        assertEquals(400, call("POST", "/api/players/mute", """{"name":"A","duration":"soon"}""").status)
        assertEquals(400, call("POST", "/api/players/mute", """{"name":"A"}""").status, "a mute needs a duration")
        assertEquals(400, call("POST", "/api/players/ban", """{"name":"A","duration":"99999d"}""").status)
        assertEquals(400, call("POST", "/api/reports/close", """{"note":"no id"}""").status)
        assertEquals(400, call("POST", "/api/broadcast", """{"message":"   "}""").status)
        assertEquals(400, call("POST", "/api/broadcast", """{"message":"${"m".repeat(AdminApi.MAX_MESSAGE + 1)}"}""").status)
        assertEquals(400, call("POST", "/api/players/kick", "not json").status)
        assertEquals(413, call("POST", "/api/broadcast", "x".repeat(AdminApi.MAX_BODY + 1)).status)
        assertTrue(backend.calls.isEmpty(), backend.calls.toString())
    }

    @Test
    fun `wrong methods and unknown paths are refused`() {
        assertEquals(405, call("GET", "/api/players/kick").status)
        assertEquals(405, call("POST", "/api/status").status)
        assertEquals(404, call("GET", "/api/nothing").status)
        assertEquals(404, call("GET", "/other").status)
    }

    @Test
    fun `long reasons are trimmed and unknown JSON fields are ignored`() {
        call("POST", "/api/players/kick", """{"name":"Alpha","reason":"${"r".repeat(500)}","extra":1}""")
        assertEquals(200, backend.calls.single().substringAfter('|').substringBefore('|').length)
    }

    @Test
    fun `tokens are generated long and different, and short ones are rejected`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val a = AdminToken.generate()
        val b = AdminToken.generate()
        assertEquals(64, a.length)
        assertTrue(a != b)
        val first = AdminToken.loadOrCreate(dir, env = null)
        assertEquals(first, AdminToken.loadOrCreate(dir, env = null), "kept between runs")
        assertEquals("an-environment-secret-123", AdminToken.loadOrCreate(dir, env = "an-environment-secret-123"))
        assertNotNull(runCatching { AdminToken.loadOrCreate(dir, env = "short") }.exceptionOrNull())
    }
}
