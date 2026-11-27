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

    fun accounts(): List<AdminAccount>
    fun createAccount(username: String, password: String, actor: String): AdminResult
    fun setAccess(username: String, access: String, actor: String): AdminResult
    fun characters(account: String?): List<AdminCharacter>
    fun createCharacter(account: String, name: String, classId: Int, spriteId: Int, actor: String): AdminResult

    /** A one-time login ticket for [username] (null when dev login is off), see [com.morgana.server.DevTickets]. */
    fun mintTicket(username: String, ttlSeconds: Long, actor: String): AdminTicket?
    fun warp(name: String, mapId: Int, x: Int, y: Int, actor: String): AdminResult
    fun give(name: String, itemId: Int, amount: Int, actor: String): AdminResult

    fun contentList(collection: String?): List<AdminContentEntry>

    /** The raw JSON of one document, or null when there is none. */
    fun contentGet(collection: String, id: Int): String?

    /** Validates [json] as a document, writes it, hot-reloads it, and puts the old file back if the reload refuses it. */
    fun contentPut(collection: String, id: Int, json: String, actor: String): AdminResult
}
