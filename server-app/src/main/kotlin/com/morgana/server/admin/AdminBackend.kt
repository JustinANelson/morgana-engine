package com.morgana.server.admin

import com.morgana.server.moderation.AuditEntry
import com.morgana.server.moderation.Report
import com.morgana.server.moderation.Sanction
import com.morgana.server.moderation.SanctionKind

/**
 * What the admin API needs from the running server. [com.morgana.server.MorganaWorldCoordinator] implements it; tests use a
 * fake, so the API's routing, validation and authentication are tested without a game world or a socket.
 * Every method may be called from any thread.
 */
interface AdminBackend {
    fun status(): AdminStatus
    fun metrics(): List<AdminMetricSample>
    fun players(): List<AdminPlayer>

    /** [actor] is who the audit log names (always the web panel's own name). */
    fun kick(name: String, reason: String, actor: String): AdminResult

    /** [durationMillis] 0 means permanent. */
    fun sanction(kind: SanctionKind, name: String, durationMillis: Long, reason: String, actor: String): AdminResult
    fun lift(kind: SanctionKind, name: String, actor: String): AdminResult
    fun sanctions(kind: SanctionKind): List<Sanction>
    fun openReports(): List<Report>
    fun closeReport(id: Int, note: String, actor: String): AdminResult
    fun audit(name: String?, limit: Int): List<AuditEntry>
    fun reloadContent(actor: String): AdminResult
    fun broadcast(message: String, actor: String): AdminResult
}
