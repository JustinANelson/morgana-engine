package com.morgana.domain.systems

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.BoonDefinition
import com.morgana.domain.game.BuildBonuses
import com.morgana.domain.sim.ChatCommandHandler
import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.tellPlayer
import com.morgana.domain.sim.warningNotice

/**
 * Roguelike Boons / Relics system (`OptionalModule.BOONS`):
 * - `/boon` lists active boons on the character.
 * - `/boon offer` rolls up to 3 eligible boons using the simulation's seeded RNG.
 * - `/boon pick <1..3>` acquires the chosen boon stack.
 * - Run-only boons (`runOnly = true`) are cleared automatically when a dungeon run finishes.
 */
object BoonSystem : GameSystem, ChatCommandHandler {
    override val key: String = OptionalModule.BOONS.configKey

    override fun handle(sim: WorldSimulation, player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>): Boolean {
        if (parts.firstOrNull()?.lowercase() != "boon") return false
        val boons = sim.game.definition.boons
        if (boons.isEmpty()) {
            out.add(sim.tellPlayer(player, "This realm has no boons defined.", "INFO"))
            return true
        }
        val sub = parts.getOrNull(1)?.lowercase().orEmpty()
        when (sub) {
            "", "list" -> {
                val active = boons.mapNotNull { b ->
                    val s = BuildBonuses.boonStacks(player, b.id)
                    if (s > 0) "${b.name} ($s/${b.maxStacks})" else null
                }
                val msg = if (active.isEmpty()) "No active boons. Use /boon offer to roll upgrades." else "Active boons: ${active.joinToString(", ")}"
                out.add(sim.tellPlayer(player, msg, "INFO"))
            }
            "offer" -> {
                val pool = boons.filter { BuildBonuses.boonStacks(player, it.id) < it.maxStacks && it.weight > 0 }.toMutableList()
                if (pool.isEmpty()) {
                    out.add(sim.tellPlayer(player, "No more boons available to offer.", "INFO"))
                    return true
                }
                val offered = ArrayList<BoonDefinition>(3)
                repeat(minOf(3, pool.size)) {
                    val total = pool.sumOf { it.weight }
                    var roll = sim.rng.nextInt(total)
                    var pickedIdx = pool.lastIndex
                    for (i in pool.indices) {
                        roll -= pool[i].weight
                        if (roll < 0) { pickedIdx = i; break }
                    }
                    offered += pool.removeAt(pickedIdx)
                }
                sim.pendingBoonOffers[player.id] = offered.map { it.id }
                val lines = offered.mapIndexed { i, b -> "${i + 1}) ${b.name}${if (b.description.isNotBlank()) " — ${b.description}" else ""}" }
                out.add(sim.tellPlayer(player, "Choose a boon with /boon pick <1-${offered.size}>: ${lines.joinToString(" | ")}", "INFO"))
            }
            "pick" -> {
                val offer = sim.pendingBoonOffers[player.id]
                if (offer.isNullOrEmpty()) {
                    out.add(warningNotice(player, "No active boon offer. Use /boon offer first."))
                    return true
                }
                val idx = (parts.getOrNull(2)?.toIntOrNull() ?: 0) - 1
                if (idx !in offer.indices) {
                    out.add(warningNotice(player, "Pick a number from 1 to ${offer.size}."))
                    return true
                }
                val boonId = offer[idx]
                val boon = boons.firstOrNull { it.id == boonId } ?: return true
                val cur = BuildBonuses.boonStacks(player, boon.id)
                if (cur >= boon.maxStacks) {
                    out.add(warningNotice(player, "${boon.name} is already at max stacks."))
                    return true
                }
                sim.pendingBoonOffers.remove(player.id)
                val updated = player.copy(variables = player.variables + ("${BuildBonuses.BOON_VAR_PREFIX}${boon.id}" to (cur + 1)))
                sim.players[player.id] = updated
                out.add(WorldSimulationEvent.CharacterUpdated(updated))
                out.add(sim.tellPlayer(updated, "Acquired boon: ${boon.name} (${cur + 1}/${boon.maxStacks}).", "INFO"))
            }
            else -> out.add(sim.tellPlayer(player, "Usage: /boon [list | offer | pick <1-3>]", "INFO"))
        }
        return true
    }

    /** Clears all `runOnly` boons and any pending offer from [characterId] when a dungeon run ends. */
    fun clearRunOnlyBoons(sim: WorldSimulation, characterId: String, out: MutableList<WorldSimulationEvent>) {
        sim.pendingBoonOffers.remove(characterId)
        val runOnlyKeys = sim.game.definition.boons.filter { it.runOnly }.map { "${BuildBonuses.BOON_VAR_PREFIX}${it.id}" }.toSet()
        if (runOnlyKeys.isEmpty()) return
        val player = sim.players[characterId] ?: return
        if (player.variables.keys.none { it in runOnlyKeys }) return
        val cleaned = player.copy(variables = player.variables.filterKeys { it !in runOnlyKeys })
        sim.players[characterId] = cleaned
        out.add(WorldSimulationEvent.CharacterUpdated(cleaned))
    }
}

/**
 * Data-driven Skill / Talent Tree system (`OptionalModule.SKILL_TREES`):
 * - `/skills` shows available points and nodes for the character's class.
 * - `/skills learn <nodeId>` invests 1 skill point, enforces prerequisites/level/class, and auto-teaches `grantsSpellId`.
 */
object SkillTreeSystem : GameSystem, ChatCommandHandler {
    override val key: String = OptionalModule.SKILL_TREES.configKey

    override fun handle(sim: WorldSimulation, player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>): Boolean {
        if (parts.firstOrNull()?.lowercase() != "skills") return false
        val tree = sim.game.definition.skillTree?.takeIf { it.enabled && it.nodes.isNotEmpty() }
        if (tree == null) {
            out.add(sim.tellPlayer(player, "This realm has no skill tree enabled.", "INFO"))
            return true
        }
        val sub = parts.getOrNull(1)?.lowercase().orEmpty()
        when (sub) {
            "", "list" -> {
                val points = BuildBonuses.availableSkillPoints(player, tree)
                val eligible = tree.nodes.filter { it.classIds.isEmpty() || player.classId in it.classIds }
                val summary = eligible.joinToString(", ") { n ->
                    "${n.id} (${BuildBonuses.skillRank(player, n.id)}/${n.maxRank})"
                }
                out.add(sim.tellPlayer(player, "Skill points: $points | Nodes: $summary | Use /skills learn <id>", "INFO"))
            }
            "learn" -> {
                val nodeId = parts.getOrNull(2)?.trim().orEmpty()
                val node = tree.nodes.firstOrNull { it.id == nodeId }
                if (node == null) {
                    out.add(warningNotice(player, "Unknown skill node '$nodeId'."))
                    return true
                }
                if (node.classIds.isNotEmpty() && player.classId !in node.classIds) {
                    out.add(warningNotice(player, "${node.name} is not available for your class."))
                    return true
                }
                if (player.level < node.minLevel) {
                    out.add(warningNotice(player, "${node.name} requires level ${node.minLevel}."))
                    return true
                }
                val curRank = BuildBonuses.skillRank(player, node.id)
                if (curRank >= node.maxRank) {
                    out.add(warningNotice(player, "${node.name} is already at max rank (${node.maxRank})."))
                    return true
                }
                val missing = node.requires.filter { BuildBonuses.skillRank(player, it) < 1 }
                if (missing.isNotEmpty()) {
                    out.add(warningNotice(player, "${node.name} requires: ${missing.joinToString(", ")}."))
                    return true
                }
                if (BuildBonuses.availableSkillPoints(player, tree) < 1) {
                    out.add(warningNotice(player, "You have no unspent skill points."))
                    return true
                }
                val newRank = curRank + 1
                val newSpells = if (node.grantsSpellId > 0 && node.grantsSpellId !in player.knownSpellIds && node.grantsSpellId in sim.spells) {
                    player.knownSpellIds + node.grantsSpellId
                } else {
                    player.knownSpellIds
                }
                val updated = player.copy(
                    variables = player.variables + ("${BuildBonuses.SKILL_VAR_PREFIX}${node.id}" to newRank),
                    knownSpellIds = newSpells
                )
                sim.players[player.id] = updated
                out.add(WorldSimulationEvent.CharacterUpdated(updated))
                out.add(sim.tellPlayer(updated, "Learned ${node.name} (rank $newRank/${node.maxRank}).", "INFO"))
            }
            else -> out.add(sim.tellPlayer(player, "Usage: /skills [list | learn <nodeId>]", "INFO"))
        }
        return true
    }
}
