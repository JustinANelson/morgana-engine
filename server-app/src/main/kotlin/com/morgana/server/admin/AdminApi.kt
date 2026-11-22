package com.morgana.server.admin

import com.morgana.server.moderation.AuditEntry
import com.morgana.server.moderation.ModerationService
import com.morgana.server.moderation.Report
import com.morgana.server.moderation.Sanction
import com.morgana.server.moderation.SanctionKind
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The admin web panel's routing, authentication and validation, with no sockets: [AdminHttpServer] feeds it requests and
 * writes the answers. Everything under `/api/` needs `Authorization: Bearer <token>`; the panel's own files (`/`,
 * `/app.js`, `/admin.css`) do not, because they contain nothing secret and the page has to load before it can ask for the token.
 *
 * | Method and path | What it does |
 * | :--- | :--- |
 * | `GET /api/status`, `GET /api/metrics` | the server at a glance; the last two hours of population and tick time |
 * | `GET /api/players` | who is online |
 * | `POST /api/players/kick` `{name, reason}` | disconnect a player |
 * | `POST /api/players/mute` `{name, duration, reason}`, `.../unmute` `{name}` | mute (duration like `30m`, `12h`, `7d`, `perm`) |
 * | `POST /api/players/ban` `{name, duration?, reason}`, `.../unban` `{name}` | ban (no duration = permanent) |
 * | `GET /api/sanctions?kind=ban\|mute` | active bans or mutes |
 * | `GET /api/reports`, `POST /api/reports/close` `{id, note}` | the player report queue |
 * | `GET /api/audit?name=&limit=` | the moderation audit log, newest last |
 * | `POST /api/reload` | reload the world content from disk |
 * | `POST /api/broadcast` `{message}` | a server announcement to everyone online |
 *
 * A refused request answers with `{"error": "..."}` and a 4xx status. A rejected token is counted per client address;
 * after [MAX_AUTH_FAILURES] in a minute that address gets 429 until the minute passes.
 */
class AdminApi(
    private val backend: AdminBackend,
    token: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val resource: (String) -> ByteArray? = ::classpathResource
) {
    private val tokenBytes = token.toByteArray(StandardCharsets.UTF_8)
    private val failures = HashMap<String, ArrayDeque<Long>>()

    fun handle(method: String, uri: String, body: String, authorization: String?, remote: String): AdminResponse {
        val path = uri.substringBefore('?').substringBefore('#')
        val query = parseQuery(uri.substringAfter('?', ""))
        val verb = method.uppercase()
        PANEL_FILES[path]?.let { (resourceName, type) ->
            if (verb != "GET" && verb != "HEAD") return error(405, "Method not allowed")
            val bytes = resource(resourceName) ?: return error(404, "Not found")
            return AdminResponse(200, type, if (verb == "HEAD") ByteArray(0) else bytes, SECURITY_HEADERS + if (type.startsWith("text/html")) mapOf("Content-Security-Policy" to PANEL_CSP) else emptyMap())
        }
        if (!path.startsWith("/api/")) return error(404, "Not found")

        if (tooManyFailures(remote)) return error(429, "Too many failed attempts. Try again in a minute.")
        if (!tokenMatches(authorization)) {
            recordFailure(remote)
            return error(401, "A valid token is required.")
        }
        if (body.length > MAX_BODY) return error(413, "Request too large.")
        return try {
            route(verb, path, query, body)
        } catch (e: kotlinx.serialization.SerializationException) {
            error(400, "The request body is not valid JSON of the expected shape.")
        } catch (e: IllegalArgumentException) {
            error(400, e.message ?: "Bad request.")
        }
    }

    private fun route(verb: String, path: String, query: Map<String, String>, body: String): AdminResponse {
        fun needs(expected: String): AdminResponse? = if (verb != expected) error(405, "Use $expected for $path") else null
        return when (path) {
            "/api/status" -> needs("GET") ?: ok(AdminStatus.serializer(), backend.status())
            "/api/metrics" -> needs("GET") ?: ok(ListSerializer(AdminMetricSample.serializer()), backend.metrics())
            "/api/players" -> needs("GET") ?: ok(ListSerializer(AdminPlayer.serializer()), backend.players())
            "/api/players/kick" -> needs("POST") ?: run {
                val r = parse(NameRequest.serializer(), body)
                result(backend.kick(name(r.name), reason(r.reason), ACTOR))
            }
            "/api/players/mute" -> needs("POST") ?: run {
                val r = parse(SanctionRequest.serializer(), body)
                val duration = ModerationService.parseDuration(r.duration) ?: throw IllegalArgumentException("duration must look like 30m, 12h, 7d or perm")
                result(backend.sanction(SanctionKind.MUTE, name(r.name), duration, reason(r.reason), ACTOR))
            }
            "/api/players/unmute" -> needs("POST") ?: result(backend.lift(SanctionKind.MUTE, name(parse(NameRequest.serializer(), body).name), ACTOR))
            "/api/players/ban" -> needs("POST") ?: run {
                val r = parse(SanctionRequest.serializer(), body)
                val duration = if (r.duration.isBlank()) 0L else ModerationService.parseDuration(r.duration) ?: throw IllegalArgumentException("duration must look like 30m, 12h, 7d or perm")
                result(backend.sanction(SanctionKind.BAN, name(r.name), duration, reason(r.reason), ACTOR))
            }
            "/api/players/unban" -> needs("POST") ?: result(backend.lift(SanctionKind.BAN, name(parse(NameRequest.serializer(), body).name), ACTOR))
            "/api/sanctions" -> needs("GET") ?: run {
                val kind = when (query["kind"]?.lowercase()) {
                    "ban" -> SanctionKind.BAN
                    "mute" -> SanctionKind.MUTE
                    else -> throw IllegalArgumentException("kind must be ban or mute")
                }
                ok(ListSerializer(Sanction.serializer()), backend.sanctions(kind))
            }
            "/api/reports" -> needs("GET") ?: ok(ListSerializer(Report.serializer()), backend.openReports())
            "/api/reports/close" -> needs("POST") ?: run {
                val r = parse(CloseReportRequest.serializer(), body)
                if (r.id <= 0) throw IllegalArgumentException("a report id is required")
                result(backend.closeReport(r.id, reason(r.note, allowBlank = true), ACTOR))
            }
            "/api/audit" -> needs("GET") ?: run {
                val limit = query["limit"]?.toIntOrNull()?.coerceIn(1, MAX_AUDIT) ?: DEFAULT_AUDIT
                ok(ListSerializer(AuditEntry.serializer()), backend.audit(query["name"]?.takeIf { it.isNotBlank() }?.take(MAX_NAME), limit))
            }
            "/api/reload" -> needs("POST") ?: result(backend.reloadContent(ACTOR))
            "/api/broadcast" -> needs("POST") ?: run {
                val message = parse(BroadcastRequest.serializer(), body).message.trim()
                if (message.isEmpty()) throw IllegalArgumentException("a message is required")
                if (message.length > MAX_MESSAGE) throw IllegalArgumentException("the message is too long (max $MAX_MESSAGE characters)")
                result(backend.broadcast(message, ACTOR))
            }
            else -> error(404, "Not found")
        }
    }

    private fun name(raw: String): String {
        val n = raw.trim()
        if (n.isEmpty()) throw IllegalArgumentException("a player name is required")
        if (n.length > MAX_NAME) throw IllegalArgumentException("that player name is too long")
        return n
    }

    private fun reason(raw: String, allowBlank: Boolean = true): String {
        val r = raw.trim()
        if (!allowBlank && r.isEmpty()) throw IllegalArgumentException("a reason is required")
        return r.take(ModerationService.MAX_REASON)
    }

    private fun <T> parse(serializer: KSerializer<T>, body: String): T = JSON.decodeFromString(serializer, body.ifBlank { "{}" })

    private fun <T> ok(serializer: KSerializer<T>, value: T): AdminResponse =
        AdminResponse(200, "application/json; charset=utf-8", JSON.encodeToString(serializer, value).toByteArray(StandardCharsets.UTF_8), SECURITY_HEADERS)

    private fun result(r: AdminResult): AdminResponse =
        if (r.ok) ok(AdminResult.serializer(), r) else error(400, r.message)

    private fun error(status: Int, message: String): AdminResponse =
        AdminResponse(status, "application/json; charset=utf-8", JSON.encodeToString(AdminError.serializer(), AdminError(message)).toByteArray(StandardCharsets.UTF_8), SECURITY_HEADERS)

    /** Constant-time comparison, so response timing never reveals how much of a guess was right. */
    private fun tokenMatches(header: String?): Boolean {
        val given = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim() ?: return false
        return MessageDigest.isEqual(given.toByteArray(StandardCharsets.UTF_8), tokenBytes)
    }

    @Synchronized
    private fun tooManyFailures(remote: String): Boolean {
        val now = clock()
        val q = failures[remote] ?: return false
        while (q.isNotEmpty() && now - q.first() > WINDOW_MS) q.removeFirst()
        if (q.isEmpty()) failures.remove(remote)
        return q.size >= MAX_AUTH_FAILURES
    }

    @Synchronized
    private fun recordFailure(remote: String) {
        if (failures.size > MAX_TRACKED_ADDRESSES) failures.clear()
        failures.getOrPut(remote) { ArrayDeque() }.addLast(clock())
    }

    companion object {
        /** The name the audit log shows for actions taken through the panel or API. */
        const val ACTOR = "web-admin"
        const val MAX_BODY = 8 * 1024
        const val MAX_NAME = 32
        const val MAX_MESSAGE = 300
        const val DEFAULT_AUDIT = 50
        const val MAX_AUDIT = 500
        const val MAX_AUTH_FAILURES = 10
        private const val WINDOW_MS = 60_000L
        private const val MAX_TRACKED_ADDRESSES = 1000

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Nothing in the panel may load from, or send to, anywhere but this server. */
        private const val PANEL_CSP = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

        private val SECURITY_HEADERS = mapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "X-Frame-Options" to "DENY",
            "Referrer-Policy" to "no-referrer"
        )

        private val PANEL_FILES = mapOf(
            "/" to ("index.html" to "text/html; charset=utf-8"),
            "/index.html" to ("index.html" to "text/html; charset=utf-8"),
            "/app.js" to ("app.js" to "text/javascript; charset=utf-8"),
            "/admin.css" to ("admin.css" to "text/css; charset=utf-8")
        )

        fun classpathResource(name: String): ByteArray? =
            AdminApi::class.java.getResourceAsStream("/admin/$name")?.use { it.readBytes() }

        fun parseQuery(raw: String): Map<String, String> =
            raw.split('&').filter { it.contains('=') }.associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=')
                fun dec(s: String) = try { java.net.URLDecoder.decode(s, StandardCharsets.UTF_8) } catch (_: Exception) { s }
                dec(key) to dec(value)
            }
    }
}
