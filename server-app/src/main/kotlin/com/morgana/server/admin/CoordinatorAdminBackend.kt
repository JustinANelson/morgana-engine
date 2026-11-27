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
    override fun accounts() = coordinator.adminAccounts()
    override fun createAccount(username: String, password: String, actor: String) = coordinator.adminCreateAccount(username, password, actor)
    override fun setAccess(username: String, access: String, actor: String) = coordinator.adminSetAccess(username, access, actor)
    override fun characters(account: String?) = coordinator.adminCharacters(account)
    override fun createCharacter(account: String, name: String, classId: Int, spriteId: Int, actor: String) =
        coordinator.adminCreateCharacter(account, name, classId, spriteId, actor)
    override fun mintTicket(username: String, ttlSeconds: Long, actor: String) = coordinator.adminMintTicket(username, ttlSeconds, actor)
    override fun warp(name: String, mapId: Int, x: Int, y: Int, actor: String) = coordinator.adminWarp(name, mapId, x, y, actor)
    override fun give(name: String, itemId: Int, amount: Int, actor: String) = coordinator.adminGive(name, itemId, amount, actor)
    override fun contentList(collection: String?) = coordinator.adminContentList(collection)
    override fun contentGet(collection: String, id: Int) = coordinator.adminContentGet(collection, id)
    override fun contentPut(collection: String, id: Int, json: String, actor: String) = coordinator.adminContentPut(collection, id, json, actor)
}
