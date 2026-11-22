package com.morgana.domain.systems

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.DungeonRunState
import com.morgana.domain.game.PermadeathMode
import com.morgana.domain.game.PreRunSnapshot
import com.morgana.domain.game.RunRulesDefinition
import com.morgana.domain.game.RunStateMode
import com.morgana.domain.item.Inventory
import com.morgana.domain.sim.ChatCommandHandler
import com.morgana.domain.sim.CombatArbiter
import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.KillObserver
import com.morgana.domain.sim.MapInstances
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.TickParticipant
import com.morgana.domain.sim.TickStage
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.releaseGhostImpl
import com.morgana.domain.sim.tellPlayer
import com.morgana.domain.sim.warningNotice
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition

/**
 * Dungeon run lifecycle system (ADR 0016 / ADR 0018):
 * - Tracks active runs on generated floors (`WorldSimulation.activeRuns`: seed, depth, timer, kills, deaths).
 * - Enforces optional time limits, party wipes, and extraction/ephemeral run state snapshots.
 * - Handles configurable permadeath (`PermadeathMode.LOCK` or `DELETE`) outside safe arenas.
 * - Owns `/run` (status), `/seed <number|clear>` (custom run seed), and `/abandon` (leave active run).
 */
object RunLifecycleSystem : GameSystem, CombatArbiter, TickParticipant, KillObserver, ChatCommandHandler {
    override val key: String = OptionalModule.RUN_LIFECYCLE.configKey
    override val stage: TickStage = TickStage.AFTER_EVENTS

    private fun rules(sim: WorldSimulation): RunRulesDefinition? =
        sim.game.definition.runs?.takeIf { it.enabled }

    override fun deathOutcome(sim: WorldSimulation, player: CharacterState, out: MutableList<WorldSimulationEvent>): CharacterState? {
        val cfg = rules(sim) ?: return null
        val ownerKey = MapInstances.ownerKey(player)
        sim.activeRuns[ownerKey]?.let { run ->
            sim.activeRuns[ownerKey] = run.copy(deaths = run.deaths + 1)
        }
        if (cfg.permadeath == PermadeathMode.OFF) return null
        val map = sim.maps[player.position.mapId]
        if (map?.moral?.safeRespawnInPlace == true) return null

        val lifeId = sim.game.lifeVitalId
        val zeroedVitals = player.vitals.mapValues { (id, v) -> if (id == lifeId) v.copy(cur = 0) else v }
        val fallen = player.copy(
            vitals = zeroedVitals,
            isGhost = false,
            fallen = true,
            statusEffects = emptyList()
        )
        sim.players[player.id] = fallen
        out.add(WorldSimulationEvent.CharacterUpdated(fallen))
        val msg = when (cfg.permadeath) {
            PermadeathMode.LOCK -> "${fallen.name} has fallen permanently and entered the Hall of the Fallen."
            PermadeathMode.DELETE -> "${fallen.name} has fallen permanently."
            PermadeathMode.OFF -> ""
        }
        if (msg.isNotEmpty()) out.add(warningNotice(fallen, msg))
        out.add(WorldSimulationEvent.CharacterPermadeath(fallen.id, fallen.name, cfg.permadeath))
        return fallen
    }

    override fun onKillReward(sim: WorldSimulation, killer: CharacterState, xpReward: Long, out: MutableList<WorldSimulationEvent>) {
        if (rules(sim) == null) return
        val inst = sim.instances[killer.position.mapId] ?: return
        if (sim.maps[inst.templateMapId]?.generator == null) return
        val run = sim.activeRuns[inst.ownerKey] ?: return
        sim.activeRuns[inst.ownerKey] = run.copy(kills = run.kills + 1)
    }

    override fun onTick(sim: WorldSimulation, out: MutableList<WorldSimulationEvent>) {
        val cfg = rules(sim) ?: return

        // 1. Register or advance runs for players currently on generated dungeon floors.
        for (player in sim.players.values.sortedBy { it.id }) {
            val inst = sim.instances[player.position.mapId] ?: continue
            if (sim.maps[inst.templateMapId]?.generator == null) continue
            val existing = sim.activeRuns[inst.ownerKey]
            if (existing == null) {
                val snapshots = if (cfg.stateMode != RunStateMode.PERSISTENT) mapOf(player.id to snapshotOf(player)) else emptyMap()
                val run = DungeonRunState(
                    runId = sim.nextId("run"),
                    ownerKey = inst.ownerKey,
                    seed = inst.seed,
                    startTick = sim.currentTick,
                    timeLimitTicks = cfg.timeLimitTicks,
                    depth = 1,
                    floorsVisited = setOf(inst.templateMapId),
                    memberIds = setOf(player.id),
                    memberSnapshots = snapshots
                )
                sim.activeRuns[inst.ownerKey] = run
                val timerNote = if (cfg.timeLimitTicks > 0) " Time limit: ${cfg.timeLimitTicks / 20}s." else ""
                out.add(sim.tellPlayer(player, "Run started (seed ${run.seed}).$timerNote", "INFO"))
            } else {
                var updated = existing
                if (player.id !in updated.memberIds) {
                    updated = updated.copy(memberIds = updated.memberIds + player.id)
                }
                if (cfg.stateMode != RunStateMode.PERSISTENT && player.id !in updated.memberSnapshots) {
                    updated = updated.copy(memberSnapshots = updated.memberSnapshots + (player.id to snapshotOf(player)))
                }
                if (inst.templateMapId !in updated.floorsVisited) {
                    val floors = updated.floorsVisited + inst.templateMapId
                    updated = updated.copy(floorsVisited = floors, depth = maxOf(updated.depth, floors.size))
                }
                if (updated != existing) sim.activeRuns[inst.ownerKey] = updated
            }
        }

        // 2. Check active runs for timeout, wipe, or completion (everyone returned to a non-generated map).
        for (ownerKey in sim.activeRuns.keys.toList()) {
            val run = sim.activeRuns[ownerKey] ?: continue
            val membersOnline = run.memberIds.mapNotNull { sim.players[it] }.sortedBy { it.id }
            val inDungeon = membersOnline.filter { p ->
                val inst = sim.instances[p.position.mapId]
                inst != null && sim.maps[inst.templateMapId]?.generator != null
            }

            // Timeout check
            if (run.timeLimitTicks > 0 && sim.currentTick - run.startTick >= run.timeLimitTicks) {
                finishRun(sim, run, cfg, outcome = "TIMEOUT", extractSuccess = false, returnMembers = inDungeon, out = out)
                continue
            }

            // Wipe check (everyone currently in the dungeon is downed/dead)
            if (cfg.wipeEndsRun && inDungeon.isNotEmpty() && inDungeon.none { it.isAlive(sim.game.lifeVitalId) && !it.isGhost }) {
                finishRun(sim, run, cfg, outcome = "WIPED", extractSuccess = false, returnMembers = inDungeon, out = out)
                continue
            }

            // Completion check: nobody in the run is still on a generated floor (they exited to hub, or disconnected)
            if (inDungeon.isEmpty()) {
                val extracted = membersOnline.any { !it.fallen && it.isAlive(sim.game.lifeVitalId) }
                finishRun(sim, run, cfg, outcome = if (extracted) "EXTRACTED" else "ABANDONED", extractSuccess = extracted, returnMembers = emptyList(), out = out)
            }
        }
    }

    override fun handle(sim: WorldSimulation, player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>): Boolean {
        val cmd = parts.firstOrNull()?.lowercase() ?: return false
        val cfg = rules(sim)
        return when (cmd) {
            "run" -> {
                if (cfg == null) {
                    out.add(sim.tellPlayer(player, "Dungeon run tracking is not enabled in this realm.", "INFO"))
                    return true
                }
                val run = sim.activeRuns[MapInstances.ownerKey(player)]
                if (run == null) {
                    val seedHint = sim.customRunSeeds[player.id]?.let { " Next run seed: $it." }.orEmpty()
                    out.add(sim.tellPlayer(player, "No active dungeon run.$seedHint", "INFO"))
                } else {
                    val elapsedSec = (sim.currentTick - run.startTick) / 20L
                    val timerPart = if (run.timeLimitTicks > 0) {
                        val left = ((run.timeLimitTicks - (sim.currentTick - run.startTick)).coerceAtLeast(0L)) / 20L
                        " | Time left: ${left}s"
                    } else " | Elapsed: ${elapsedSec}s"
                    out.add(sim.tellPlayer(player, "Run ${run.runId} | Depth ${run.depth} | Kills ${run.kills} | Seed ${run.seed}$timerPart", "INFO"))
                }
                true
            }
            "seed" -> {
                if (cfg == null || !cfg.allowCustomSeed) {
                    out.add(warningNotice(player, "Custom run seeds are not enabled in this realm."))
                    return true
                }
                val arg = parts.getOrNull(1)?.trim().orEmpty()
                if (arg.isEmpty()) {
                    val cur = sim.customRunSeeds[player.id]
                    out.add(sim.tellPlayer(player, if (cur != null) "Current custom run seed: $cur. Use /seed clear to reset." else "No custom run seed set. Usage: /seed <number>", "INFO"))
                    return true
                }
                if (arg.equals("clear", ignoreCase = true) || arg == "0") {
                    sim.customRunSeeds.remove(player.id)
                    out.add(sim.tellPlayer(player, "Cleared custom run seed; future runs will use random seeds.", "INFO"))
                    return true
                }
                val parsed = arg.toLongOrNull()
                if (parsed == null) {
                    out.add(warningNotice(player, "Usage: /seed <whole number> or /seed clear"))
                } else {
                    sim.customRunSeeds[player.id] = parsed
                    out.add(sim.tellPlayer(player, "Next dungeon run seed set to $parsed.", "INFO"))
                }
                true
            }
            "abandon" -> {
                if (cfg == null) {
                    out.add(sim.tellPlayer(player, "Dungeon run tracking is not enabled in this realm.", "INFO"))
                    return true
                }
                val run = sim.activeRuns[MapInstances.ownerKey(player)]
                if (run == null) {
                    out.add(sim.tellPlayer(player, "You are not in an active dungeon run.", "INFO"))
                    return true
                }
                val membersInDungeon = run.memberIds.mapNotNull { sim.players[it] }.filter { p ->
                    val inst = sim.instances[p.position.mapId]
                    inst != null && sim.maps[inst.templateMapId]?.generator != null
                }.sortedBy { it.id }
                finishRun(sim, run, cfg, outcome = "ABANDONED", extractSuccess = false, returnMembers = membersInDungeon, out = out)
                true
            }
            "return" -> {
                val ownerKey = MapInstances.ownerKey(player)
                val portal = sim.returnPortals[ownerKey]
                if (portal == null) {
                    out.add(sim.tellPlayer(player, "You have no open return portal.", "INFO"))
                    return true
                }
                val destMap = sim.maps[portal.targetMapId]
                val openInst = sim.instances[portal.targetMapId]
                if (destMap == null || openInst == null) {
                    sim.returnPortals.remove(ownerKey)
                    out.add(warningNotice(player, "Your return portal has collapsed."))
                    return true
                }
                val oldMap = player.position.mapId
                val destPos = WorldPosition.fromTile(
                    destMap.id,
                    portal.targetX.coerceIn(0, destMap.width - 1),
                    portal.targetY.coerceIn(0, destMap.height - 1),
                    player.position.direction,
                    destMap.tileSize
                )
                val updated = player.copy(
                    position = destPos,
                    instanceReturn = if (MapInstances.isInstanceId(oldMap)) player.instanceReturn else player.position
                )
                sim.instances[openInst.mapId] = openInst.copy(memberIds = openInst.memberIds + player.id)
                sim.players[player.id] = updated
                out.add(WorldSimulationEvent.PlayerMoved(updated, oldMap, destMap.id))
                out.add(WorldSimulationEvent.CharacterUpdated(updated))
                out.add(sim.tellPlayer(updated, "You step back through the return portal.", "INFO"))
                true
            }
            else -> false
        }
    }

    private fun finishRun(
        sim: WorldSimulation,
        run: DungeonRunState,
        cfg: RunRulesDefinition,
        outcome: String,
        extractSuccess: Boolean,
        returnMembers: List<CharacterState>,
        out: MutableList<WorldSimulationEvent>
    ) {
        sim.activeRuns.remove(run.ownerKey)
        val durationTicks = (sim.currentTick - run.startTick).coerceAtLeast(0L)

        // Send anyone still inside back to hub/return tile.
        for (member in returnMembers) {
            val live = sim.players[member.id] ?: continue
            if (live.isGhost) {
                out.addAll(sim.releaseGhostImpl(live.id))
            } else if (!live.fallen) {
                val oldMap = live.position.mapId
                val home = hubPosition(sim, live)
                val fullVitals = live.vitals.mapValues { (_, v) -> v.copy(cur = v.max) }
                val returned = live.copy(position = home, instanceReturn = null, vitals = fullVitals)
                sim.players[live.id] = returned
                out.add(WorldSimulationEvent.PlayerMoved(returned, oldMap, home.mapId))
                out.add(WorldSimulationEvent.CharacterUpdated(returned))
            }
        }

        // Restore pre-run snapshots when required by stateMode.
        val shouldRestore = when (cfg.stateMode) {
            RunStateMode.PERSISTENT -> false
            RunStateMode.EXTRACTION -> !extractSuccess
            RunStateMode.EPHEMERAL -> true
        }
        if (shouldRestore) {
            for (memberId in run.memberIds.sorted()) {
                val snap = run.memberSnapshots[memberId] ?: continue
                val live = sim.players[memberId] ?: continue
                if (live.fallen) continue
                val restoredInv = if (cfg.stateMode == RunStateMode.EPHEMERAL && cfg.metaCurrencyItemIds.isNotEmpty()) {
                    mergeMetaCurrencies(sim, snap.inventory, live.inventory, cfg.metaCurrencyItemIds)
                } else {
                    snap.inventory
                }
                val restored = live.copy(
                    level = snap.level,
                    xp = snap.experience,
                    unspentStatPoints = snap.unspentStatPoints,
                    attributes = snap.attributes,
                    inventory = restoredInv,
                    equipment = snap.equipment
                )
                sim.players[memberId] = restored
                out.add(WorldSimulationEvent.CharacterUpdated(restored))
            }
        }

        val summaryLine = "Run $outcome (Depth ${run.depth}, Kills ${run.kills}, Time ${durationTicks / 20}s, Seed ${run.seed})."
        for (memberId in run.memberIds.sorted()) {
            BoonSystem.clearRunOnlyBoons(sim, memberId, out)
            sim.players[memberId]?.let { p ->
                out.add(sim.tellPlayer(p, summaryLine, if (extractSuccess) "INFO" else "WARN"))
            }
        }
        out.add(
            WorldSimulationEvent.RunEnded(
                runId = run.runId,
                ownerKey = run.ownerKey,
                memberIds = run.memberIds,
                outcome = outcome,
                seed = run.seed,
                depth = run.depth,
                kills = run.kills,
                deaths = run.deaths,
                durationTicks = durationTicks
            )
        )
    }

    private fun snapshotOf(player: CharacterState): PreRunSnapshot = PreRunSnapshot(
        level = player.level,
        experience = player.xp,
        unspentStatPoints = player.unspentStatPoints,
        attributes = player.attributes,
        inventory = player.inventory,
        equipment = player.equipment
    )

    private fun mergeMetaCurrencies(
        sim: WorldSimulation,
        baseInv: Inventory,
        endInv: Inventory,
        metaItemIds: Set<Int>
    ): Inventory {
        var inv = baseInv
        for (itemId in metaItemIds.sorted()) {
            val def = sim.items[itemId] ?: continue
            val before = baseInv.slots.filterNotNull().filter { it.itemId == itemId }.sumOf { it.amount }
            val after = endInv.slots.filterNotNull().filter { it.itemId == itemId }.sumOf { it.amount }
            val gained = (after - before).coerceAtLeast(0)
            if (gained > 0) {
                inv = inv.addItem(def, gained).updatedInventory
            }
        }
        return inv
    }

    private fun hubPosition(sim: WorldSimulation, character: CharacterState): WorldPosition =
        character.instanceReturn?.takeIf { it.mapId in sim.maps && !MapInstances.isInstanceId(it.mapId) }
            ?: sim.classes[character.classId].let { cls ->
                val startMap = sim.maps[cls?.startMapId ?: 1] ?: sim.maps.values.firstOrNull { !MapInstances.isInstanceId(it.id) }
                WorldPosition.fromTile(startMap?.id ?: 1, cls?.startX ?: 12, cls?.startY ?: 9, Direction.DOWN, startMap?.tileSize ?: WorldPosition.DEFAULT_TILE_SIZE)
            }
}
