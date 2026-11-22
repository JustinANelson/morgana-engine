package com.morgana.server.admin

import com.morgana.server.MorganaWorldCoordinator
import com.morgana.server.moderation.AuditEntry
import com.morgana.server.moderation.Report
import com.morgana.server.moderation.Sanction
import com.morgana.server.moderation.SanctionKind

/** The running world as [AdminApi] sees it: every call is a thin hop to the coordinator's own admin operations. */
class CoordinatorAdminBackend(private val coordinator: MorganaWorldCoordinator) : AdminBackend {
    override fun status() = coordinator.adminStatus()
    override fun metrics() = coordinator.adminMetrics()
    override fun players() = coordinator.adminPlayers()
    override fun kick(name: String, reason: String, actor: String) = coordinator.adminKick(name, reason, actor)
    override fun sanction(kind: SanctionKind, name: String, durationMillis: Long, reason: String, actor: String) =
        coordinator.adminSanction(kind, name, durationMillis, reason, actor)
    override fun lift(kind: SanctionKind, name: String, actor: String) = coordinator.adminLift(kind, name, actor)
    override fun sanctions(kind: SanctionKind): List<Sanction> = coordinator.moderation.activeList(kind)
    override fun openReports(): List<Report> = coordinator.moderation.openReports()
    override fun closeReport(id: Int, note: String, actor: String): AdminResult {
        val closed = coordinator.moderation.closeReport(id, actor, note)
        return if (closed == null) AdminResult(false, "No open report #$id.") else AdminResult(true, "Closed report #$id.")
    }
    override fun audit(name: String?, limit: Int): List<AuditEntry> = coordinator.moderation.recentAudit(name, limit)
    override fun reloadContent(actor: String) = coordinator.adminReload(actor)
    override fun broadcast(message: String, actor: String) = coordinator.adminBroadcast(message, actor)
}
