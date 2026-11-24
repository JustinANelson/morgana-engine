package com.morgana.domain.systems

import com.morgana.domain.access.PermissionFlag
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.WorldObject
import com.morgana.domain.game.ClaimKindDefinition
import com.morgana.domain.game.ScheduleRules
import com.morgana.domain.game.WorldObjectKind
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.sim.ChatCommandHandler
import com.morgana.domain.sim.CombatArbiter
import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.SchedulePhase
import com.morgana.domain.sim.ScheduleHandler
import com.morgana.domain.sim.ScheduleTrigger
import com.morgana.domain.sim.WorldObjectGate
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.tellPlayer
import com.morgana.domain.spatial.TileCoord

/**
 * Territory: claiming land. A claim is a world object of a claim kind ([com.morgana.domain.game.ClaimKindDefinition]); the ground within its
 * radius is its owner's. Built on `worldState` (the claim and everything built are world objects, so they are saved and replayed) and the
 * scheduler (timed attack windows), with no state of its own.
 *
 * Rules, all from `game.json` `territory`:
 * - **Who may build**: inside a claim, only its owner, for a guild claim the guild's members, and anyone the owner allows (`/claim allow`).
 * - **Claims keep apart**: a new claim needs `radius + radius + minClaimSpacingTiles` between its centre and every other claim's.
 * - **How much**: a claim holds at most `maxPieces` objects at its level; a character or guild holds at most `maxPerOwner` claims of a kind.
 * - **Keep clear**: nothing is placed within `noBuildRadiusTiles` of a resource node or a monster spawn tile.
 * - **Safe zone**: nobody can attack anybody inside a claim whose kind is a safe zone, unless an attack window is open on it.
 * - **Attack windows**: [bookWindow] opens a claim to attack for a while at a wall-clock time, with advance warnings in chat.
 *
 * What the GDD of a particular game adds (upkeep, decay, weekly wipes, raids, siege damage) is built from the same parts: schedules, Lua
 * hooks (`register.schedule`, `register.object_gate`) and the claim data (`data` on the claim object).
 */
object TerritorySystem : WorldObjectGate, com.morgana.domain.sim.StanceForcer, com.morgana.domain.sim.WorldObjectDamageGate, CombatArbiter, ScheduleHandler, ChatCommandHandler, GameSystem {
    override val key: String = OptionalModule.TERRITORY.configKey

    private const val KEY_LEVEL = "level"
    private const val KEY_ALLOW = "allow"
    private const val KEY_WINDOW = "window"
    private const val OPEN = "open"
    // Schedule ids allow letters, digits, _ and - only.
    private const val OPEN_PREFIX = "territory-open-"
    private const val CLOSE_PREFIX = "territory-close-"

    // ---- reading claims ---------------------------------------------------------------------------------------------------------

    /** Every claim on [mapId], oldest first. */
    fun claimsOn(sim: WorldSimulation, mapId: Int): List<WorldObject> {
        val kinds = sim.game.definition.territory?.claimKinds?.map { it.kind } ?: return emptyList()
        return kinds.flatMap { sim.worldObjectsOfKind(mapId, it) }.sortedBy { it.createdAtTick }
    }

    /** The claim's level, 1 or more. */
    fun levelOf(claim: WorldObject): Int = claim.data[KEY_LEVEL]?.toIntOrNull()?.coerceAtLeast(1) ?: 1

    private fun kindOf(sim: WorldSimulation, claim: WorldObject): ClaimKindDefinition? = sim.game.definition.territory?.claimKind(claim.kind)

    /** How far the claim reaches, in tiles from its centre. */
    fun radiusOf(sim: WorldSimulation, claim: WorldObject): Int = kindOf(sim, claim)?.level(levelOf(claim))?.radiusTiles ?: 0

    private fun covers(sim: WorldSimulation, claim: WorldObject, x: Int, y: Int): Boolean =
        maxOf(kotlin.math.abs(claim.tileX - x), kotlin.math.abs(claim.tileY - y)) <= radiusOf(sim, claim)

    /** The claim that covers a tile (the nearest centre if several do, which spacing rules make rare), or null. */
    fun claimAt(sim: WorldSimulation, mapId: Int, x: Int, y: Int): WorldObject? =
        claimsOn(sim, mapId).filter { covers(sim, it, x, y) }
            .minWithOrNull(compareBy<WorldObject> { maxOf(kotlin.math.abs(it.tileX - x), kotlin.math.abs(it.tileY - y)) }.thenBy { it.id })

    /** How many pieces (world objects that are not claims) stand inside [claim]'s reach. */
    fun piecesIn(sim: WorldSimulation, claim: WorldObject): Int {
        val claimIds = claimsOn(sim, claim.mapId).map { it.id }.toSet()
        return sim.worldObjectsOn(claim.mapId).count { it.id !in claimIds && covers(sim, claim, it.tileX, it.tileY) }
    }

    /** Whether [characterId] may build inside [claim]: its owner, the owner's guild for a guild claim, or anyone the owner allowed. */
    fun mayBuildIn(sim: WorldSimulation, claim: WorldObject, characterId: String): Boolean {
        if (claim.ownerCharacterId == characterId) return true
        val kind = kindOf(sim, claim)
        if (kind?.guild == true && claim.ownerGuildId != null && sim.players[characterId]?.guildId == claim.ownerGuildId) return true
        return characterId in allowed(claim)
    }

    private fun allowed(claim: WorldObject): Set<String> = claim.data[KEY_ALLOW]?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    /** True while an attack window is open on [claim]. */
    fun isWindowOpen(claim: WorldObject): Boolean = claim.data[KEY_WINDOW] == OPEN

    /** Raises a claim one level (up to its kind's last). False if it is not a claim or is already at the top. Costs are a game's to charge. */
    fun upgradeClaim(sim: WorldSimulation, claimId: String, out: MutableList<WorldSimulationEvent> = mutableListOf()): Boolean {
        val claim = sim.worldObject(claimId) ?: return false
        val kind = kindOf(sim, claim) ?: return false
        if (levelOf(claim) >= kind.levels.size) return false
        return sim.setWorldObjectData(claimId, claim.data + (KEY_LEVEL to (levelOf(claim) + 1).toString()), out)
    }

    // ---- building rules (WorldObjectGate) ---------------------------------------------------------------------------------------

    override fun refusal(sim: WorldSimulation, kind: WorldObjectKind, mapId: Int, tileX: Int, tileY: Int, ownerCharacterId: String?): String? {
        val territory = sim.game.definition.territory ?: return null
        val map = sim.maps[mapId] ?: return null
        val claimKind = territory.claimKind(kind.id)

        // Keep clear of resources and spawns, for everything the game does not place itself.
        if (ownerCharacterId != null && territory.noBuildRadiusTiles > 0 && nearResourceOrSpawn(sim, mapId, tileX, tileY, territory.noBuildRadiusTiles)) {
            return "You cannot build this close to a resource or a monster's lair."
        }

        if (claimKind != null) {
            if (ownerCharacterId == null) return null
            val level = claimKind.level(1)
            val guildId = sim.players[ownerCharacterId]?.guildId
            if (claimKind.guild && guildId == null) return "You must belong to a guild to claim land for it."
            val held = claimsOn(sim, mapId).count {
                it.kind == kind.id && (if (claimKind.guild) it.ownerGuildId == guildId else it.ownerCharacterId == ownerCharacterId)
            }
            if (held >= claimKind.maxPerOwner) return "You already hold as many claims as you may here."
            for (other in claimsOn(sim, mapId)) {
                val needed = level.radiusTiles + radiusOf(sim, other) + territory.minClaimSpacingTiles
                if (maxOf(kotlin.math.abs(other.tileX - tileX), kotlin.math.abs(other.tileY - tileY)) <= needed) return "That is too close to another claim."
            }
            // A new claim may not swallow another's pieces either: nothing of anyone else's may stand within its reach.
            val foreign = sim.worldObjectsOn(mapId).any {
                it.ownerCharacterId != ownerCharacterId && maxOf(kotlin.math.abs(it.tileX - tileX), kotlin.math.abs(it.tileY - tileY)) <= level.radiusTiles
            }
            if (foreign) return "Someone else has built too close to claim here."
            return null
        }

        // Anything else placed by a character inside a claim must be by someone who may build there.
        if (ownerCharacterId == null) return null
        val claim = claimAt(sim, mapId, tileX, tileY) ?: return null
        if (!mayBuildIn(sim, claim, ownerCharacterId)) {
            val owner = claim.ownerCharacterId?.let { sim.players[it]?.name }
            return if (owner != null) "That land belongs to $owner." else "That land is claimed."
        }
        val limit = kindOf(sim, claim)?.level(levelOf(claim))?.maxPieces ?: return null
        if (piecesIn(sim, claim) >= limit) return "This claim cannot hold anything more."
        return null
    }

    private fun nearResourceOrSpawn(sim: WorldSimulation, mapId: Int, x: Int, y: Int, radius: Int): Boolean {
        val map = sim.maps[mapId] ?: return false
        if (map.npcSpawns.any { maxOf(kotlin.math.abs(it.spawnX - x), kotlin.math.abs(it.spawnY - y)) <= radius }) return true
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (map.getCell(TileCoord(x + dx, y + dy))?.attribute is TileAttribute.ResourceNode) return true
        }
        return false
    }

    // ---- safe zones (CombatArbiter) ---------------------------------------------------------------------------------------------

    private fun protectedAt(sim: WorldSimulation, c: CharacterState): Boolean {
        val claim = claimAt(sim, c.position.mapId, c.position.tileX, c.position.tileY) ?: return false
        return kindOf(sim, claim)?.safeZone == true && !isWindowOpen(claim)
    }

    /** Nobody may attack anybody while either stands in a protected claim; no opinion otherwise. */
    override fun mayFight(sim: WorldSimulation, attacker: CharacterState, defender: CharacterState): Boolean? =
        if (sim.game.definition.territory == null) null else if (protectedAt(sim, attacker) || protectedAt(sim, defender)) false else null

    /**
     * Structures inside a protected (safe zone) claim cannot be hurt unless an attack window is open on it, except by those who may build there.
     * The claim itself counts as inside its own reach.
     */
    override fun damageRefusal(sim: WorldSimulation, attacker: CharacterState, obj: WorldObject, kind: WorldObjectKind): String? {
        if (sim.game.definition.territory == null) return null
        val claim = claimAt(sim, obj.mapId, obj.tileX, obj.tileY) ?: return null
        if (kindOf(sim, claim)?.safeZone != true || isWindowOpen(claim)) return null
        if (mayBuildIn(sim, claim, attacker.id)) return null
        return "This land is protected right now."
    }

    /** Inside a claim whose attack window is open everyone is in PvP stance: a siege cannot be sat out. */
    override fun forcesPvp(sim: WorldSimulation, player: CharacterState): Boolean {
        if (sim.game.definition.territory == null) return false
        val claim = claimAt(sim, player.position.mapId, player.position.tileX, player.position.tileY) ?: return false
        return isWindowOpen(claim)
    }

    // ---- attack windows ---------------------------------------------------------------------------------------------------------

    /**
     * Opens [claimId] to attack from [startMillis] (epoch milliseconds, UTC) for [durationSeconds], with chat warnings [warningsSeconds]
     * before it opens. Built on two scheduler one-offs, so it survives a restart. False when the claim does not exist, the scheduler is off or
     * a time is not in the future. Booking again replaces the earlier booking.
     */
    fun bookWindow(
        sim: WorldSimulation, claimId: String, startMillis: Long, durationSeconds: Int, warningsSeconds: List<Int> = listOf(3_600, 900, 60),
        label: String = "Attack window"
    ): Boolean {
        val claim = sim.worldObject(claimId) ?: return false
        if (kindOf(sim, claim) == null || durationSeconds <= 0) return false
        val data = mapOf("claim" to claimId)
        val opened = sim.scheduleOnce(OPEN_PREFIX + claimId, startMillis, label, warningsSeconds, data)
        if (!opened) return false
        return sim.scheduleOnce(CLOSE_PREFIX + claimId, startMillis + durationSeconds * 1000L, "$label closes", emptyList(), data, fireMessage = "{name}.")
            .also { if (!it) sim.cancelSchedule(OPEN_PREFIX + claimId) }
    }

    override fun onSchedule(sim: WorldSimulation, trigger: ScheduleTrigger, out: MutableList<WorldSimulationEvent>) {
        if (trigger.phase != SchedulePhase.FIRE) return
        val opening = trigger.scheduleId.startsWith(OPEN_PREFIX)
        if (!opening && !trigger.scheduleId.startsWith(CLOSE_PREFIX)) return
        val claim = sim.worldObject(trigger.data["claim"] ?: return) ?: return
        val data = if (opening) claim.data + (KEY_WINDOW to OPEN) else claim.data - KEY_WINDOW
        sim.setWorldObjectData(claim.id, data, out)
    }

    // ---- /claim -----------------------------------------------------------------------------------------------------------------

    override fun handle(sim: WorldSimulation, player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>): Boolean {
        if (parts.firstOrNull()?.lowercase() != "claim") return false
        val claim = claimAt(sim, player.position.mapId, player.position.tileX, player.position.tileY)
        fun say(text: String, kind: String = "INFO") = out.add(sim.tellPlayer(player, text, kind))
        when (parts.getOrNull(1)?.lowercase()) {
            null, "", "info" -> {
                if (claim == null) { say("You are not standing on claimed land."); return true }
                val kind = sim.game.definition.worldObjects?.kind(claim.kind)?.displayName ?: claim.kind
                val definition = kindOf(sim, claim)
                val owner = claim.ownerCharacterId?.let { sim.players[it]?.name ?: "someone offline" } ?: "no one"
                val window = if (isWindowOpen(claim)) ", OPEN to attack" else ""
                say("$kind (level ${levelOf(claim)}) of $owner: ${piecesIn(sim, claim)}/${definition?.level(levelOf(claim))?.maxPieces ?: 0} pieces, reach ${radiusOf(sim, claim)} tiles$window")
            }
            "allow", "deny" -> {
                if (claim == null || claim.ownerCharacterId != player.id) { say("Stand on your own claim to do that.", "WARNING"); return true }
                val target = sim.players.values.firstOrNull { it.name.equals(parts.getOrNull(2) ?: "", ignoreCase = true) }
                if (target == null) { say("Name a player who is online: /claim ${parts[1].lowercase()} <name>", "WARNING"); return true }
                val now = allowed(claim)
                val next = if (parts[1].lowercase() == "allow") now + target.id else now - target.id
                sim.setWorldObjectData(claim.id, claim.data + (KEY_ALLOW to next.sorted().joinToString(",")), out)
                say(if (parts[1].lowercase() == "allow") "${target.name} may build on your claim." else "${target.name} may no longer build on your claim.")
            }
            "abandon" -> {
                val mayAbandon = claim != null && (claim.ownerCharacterId == player.id || player.accessLevel.hasPermission(PermissionFlag.SERVER_MAINTENANCE))
                if (claim == null || !mayAbandon) { say("Stand on your own claim to do that.", "WARNING"); return true }
                sim.removeWorldObject(claim.id, "abandoned", out)
                say("You give up the claim. What you built stays where it is.")
            }
            else -> say("Usage: /claim [info] | allow <name> | deny <name> | abandon", "WARNING")
        }
        return true
    }
}
