package com.morgana.server.moderation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

enum class SanctionKind { BAN, MUTE }

/** A ban or mute on an account. [untilMillis] is wall-clock time; 0 means it never expires. */
@Serializable
data class Sanction(
    val accountId: String,
    val accountName: String,
    val kind: SanctionKind,
    val reason: String,
    val byName: String,
    val atMillis: Long,
    val untilMillis: Long = 0L
)

enum class ReportStatus { OPEN, CLOSED }

/** A player's report about another player, waiting for a moderator. */
@Serializable
data class Report(
    val id: Int,
    val reporterAccountId: String,
    val reporterName: String,
    val targetName: String,
    val reason: String,
    val atMillis: Long,
    val status: ReportStatus = ReportStatus.OPEN,
    val closedBy: String = "",
    val note: String = "",
    val closedAtMillis: Long = 0L
)

/** One line of the audit log: who did what to whom, when. */
@Serializable
data class AuditEntry(val atMillis: Long, val actor: String, val action: String, val target: String = "", val detail: String = "")

@Serializable
private data class ModerationState(val sanctions: List<Sanction> = emptyList(), val reports: List<Report> = emptyList(), val nextReportId: Int = 1)

/**
 * Bans, mutes, the report queue and the audit log of one server. State is kept in `<data dir>/moderation.json` (rewritten
 * atomically after every change) and the audit log in `<data dir>/moderation-log.jsonl` (append-only, rotated when large).
 * Times are wall-clock milliseconds from [clock], so sanctions survive restarts (game ticks do not).
 *
 * Every method is safe to call from any thread.
 */
class ModerationService(private val dir: Path, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val lineJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateFile = dir.resolve(STATE_FILE)
    private val logFile = dir.resolve(LOG_FILE)
    private var state: ModerationState = load()
    private val lastReportAt = HashMap<String, Long>()

    private fun load(): ModerationState {
        if (!Files.exists(stateFile)) return ModerationState()
        return try {
            json.decodeFromString(ModerationState.serializer(), Files.readString(stateFile))
        } catch (e: Exception) {
            // Keep the unreadable file for the operator rather than silently dropping everyone's bans.
            Files.move(stateFile, dir.resolve("moderation.broken.json"), StandardCopyOption.REPLACE_EXISTING)
            ModerationState()
        }
    }

    private fun save() {
        Files.createDirectories(dir)
        val tmp = stateFile.resolveSibling("$STATE_FILE.tmp")
        Files.writeString(tmp, json.encodeToString(ModerationState.serializer(), state))
        Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    // ----- bans and mutes

    /** Ban or mute [accountId] for [durationMillis] (0 = for good), replacing any earlier sanction of that kind. */
    @Synchronized
    fun sanction(kind: SanctionKind, accountId: String, accountName: String, reason: String, byName: String, durationMillis: Long): Sanction {
        val now = clock()
        val s = Sanction(accountId, accountName, kind, reason.take(MAX_REASON), byName, now, if (durationMillis > 0) now + durationMillis else 0L)
        state = state.copy(sanctions = state.sanctions.filterNot { it.accountId == accountId && it.kind == kind } + s)
        save()
        audit(byName, kind.name.lowercase(), accountName, "${describeDuration(s.untilMillis, now)}: ${s.reason}")
        return s
    }

    /** Lift a ban or mute. Returns false when there was none (expired ones count as none). */
    @Synchronized
    fun lift(kind: SanctionKind, accountId: String, accountName: String, byName: String): Boolean {
        val active = active(kind, accountId) ?: return false
        state = state.copy(sanctions = state.sanctions - active)
        save()
        audit(byName, "un" + kind.name.lowercase(), accountName)
        return true
    }

    /** The unexpired sanction of [kind] on this account, or null. Expired ones are dropped. */
    @Synchronized
    fun active(kind: SanctionKind, accountId: String): Sanction? {
        val now = clock()
        val found = state.sanctions.firstOrNull { it.accountId == accountId && it.kind == kind } ?: return null
        if (found.untilMillis != 0L && found.untilMillis <= now) {
            state = state.copy(sanctions = state.sanctions - found)
            save()
            return null
        }
        return found
    }

    /** Every unexpired sanction of [kind], newest first. */
    @Synchronized
    fun activeList(kind: SanctionKind): List<Sanction> {
        val now = clock()
        return state.sanctions.filter { it.kind == kind && (it.untilMillis == 0L || it.untilMillis > now) }.sortedByDescending { it.atMillis }
    }

    // ----- reports

    sealed interface ReportResult {
        data class Filed(val report: Report) : ReportResult
        data class Refused(val why: String) : ReportResult
    }

    /** File a player report. Refused when the reporter files too fast or the queue is full. */
    @Synchronized
    fun report(reporterAccountId: String, reporterName: String, targetName: String, reason: String): ReportResult {
        val now = clock()
        val last = lastReportAt[reporterAccountId]
        if (last != null && now - last < REPORT_COOLDOWN_MS) {
            return ReportResult.Refused("Please wait ${((REPORT_COOLDOWN_MS - (now - last)) / 1000) + 1} seconds before sending another report.")
        }
        if (state.reports.count { it.status == ReportStatus.OPEN } >= MAX_OPEN_REPORTS) {
            return ReportResult.Refused("The report queue is full. A moderator will clear it soon.")
        }
        val r = Report(state.nextReportId, reporterAccountId, reporterName, targetName, reason.take(MAX_REASON), now)
        state = state.copy(reports = state.reports + r, nextReportId = state.nextReportId + 1)
        lastReportAt[reporterAccountId] = now
        save()
        audit(reporterName, "report", targetName, "#${r.id}: ${r.reason}")
        return ReportResult.Filed(r)
    }

    @Synchronized
    fun openReports(): List<Report> = state.reports.filter { it.status == ReportStatus.OPEN }

    /** Close a report with an optional note; null if there is no open report with that id. */
    @Synchronized
    fun closeReport(id: Int, byName: String, note: String): Report? {
        val open = state.reports.firstOrNull { it.id == id && it.status == ReportStatus.OPEN } ?: return null
        val closed = open.copy(status = ReportStatus.CLOSED, closedBy = byName, note = note.take(MAX_REASON), closedAtMillis = clock())
        state = state.copy(reports = state.reports.map { if (it.id == id) closed else it }.takeLast(MAX_KEPT_REPORTS))
        save()
        audit(byName, "report-closed", open.targetName, "#$id${if (note.isBlank()) "" else ": $note"}")
        return closed
    }

    // ----- audit log

    /** Append a line to the audit log (every moderator action goes here, including ones that changed nothing durable). */
    @Synchronized
    fun audit(actor: String, action: String, target: String = "", detail: String = "") {
        Files.createDirectories(dir)
        if (Files.exists(logFile) && Files.size(logFile) > MAX_LOG_BYTES) {
            Files.move(logFile, dir.resolve("$LOG_FILE.1"), StandardCopyOption.REPLACE_EXISTING)
        }
        val entry = AuditEntry(clock(), actor.take(64), action, target.take(64), detail.take(400))
        Files.writeString(
            logFile, lineJson.encodeToString(AuditEntry.serializer(), entry) + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND
        )
    }

    /** The newest audit entries (optionally only those about, or by, [name]), oldest first. */
    @Synchronized
    fun recentAudit(name: String? = null, limit: Int = 10): List<AuditEntry> {
        if (!Files.exists(logFile)) return emptyList()
        return Files.readAllLines(logFile).mapNotNull { line ->
            runCatching { lineJson.decodeFromString(AuditEntry.serializer(), line) }.getOrNull()
        }.filter { name == null || it.target.equals(name, true) || it.actor.equals(name, true) }.takeLast(limit)
    }

    companion object {
        const val STATE_FILE = "moderation.json"
        const val LOG_FILE = "moderation-log.jsonl"
        const val MAX_REASON = 200
        const val REPORT_COOLDOWN_MS = 30_000L
        const val MAX_OPEN_REPORTS = 200
        const val MAX_KEPT_REPORTS = 500
        const val MAX_LOG_BYTES = 5_000_000L
        private const val MAX_DAYS = 3650L

        /**
         * Parse `30m`, `12h`, `7d` or `perm` into milliseconds (0 = permanent), or null when it is not a duration.
         * Longer than ten years is refused so a typo cannot overflow.
         */
        fun parseDuration(text: String): Long? {
            val t = text.trim().lowercase()
            if (t == "perm" || t == "permanent" || t == "forever") return 0L
            val match = Regex("(\\d{1,6})([mhd])").matchEntire(t) ?: return null
            val n = match.groupValues[1].toLong()
            val unit = when (match.groupValues[2]) { "m" -> 60_000L; "h" -> 3_600_000L; else -> 86_400_000L }
            if (n <= 0 || n * unit > MAX_DAYS * 86_400_000L) return null
            return n * unit
        }

        /** "for good", or how long is left ("2 d 3 h", "15 min"). */
        fun describeDuration(untilMillis: Long, nowMillis: Long): String {
            if (untilMillis == 0L) return "permanent"
            val minutes = ((untilMillis - nowMillis).coerceAtLeast(0L) + 59_999L) / 60_000L
            return when {
                minutes >= 1440 -> "${minutes / 1440} d ${(minutes % 1440) / 60} h"
                minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
                else -> "$minutes min"
            }
        }
    }
}
