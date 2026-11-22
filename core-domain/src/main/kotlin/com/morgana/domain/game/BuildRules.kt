package com.morgana.domain.game

import com.morgana.domain.entity.CharacterState
import kotlinx.serialization.Serializable

/**
 * One roguelike boon / relic upgrade in `game.json` (`"boons": [...]`).
 * Player stacks are stored in `CharacterState.variables["boon:<id>"]`, so event pages, quests, and Lua scripts can
 * inspect them directly without extra protocol or persistence changes.
 */
@Serializable
data class BoonDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    /** Maximum stacks a character may hold (1..20). */
    val maxStacks: Int = 1,
    /** When true, stacks are cleared automatically when an active dungeon run ends. */
    val runOnly: Boolean = true,
    /** Relative weight when offering random boon choices (1 or more). */
    val weight: Int = 100,
    val attributeBonuses: Map<String, Int> = emptyMap(),
    val attackDamageBonus: Int = 0,
    val armorDefenseBonus: Int = 0,
    val critChanceBpBonus: Int = 0,
    val lifeLeechPercentBonus: Int = 0
)

/**
 * One node in a game's skill / talent tree (`SkillTreeDefinition.nodes`).
 * Ranks are stored in `CharacterState.variables["skill:<id>"]`.
 */
@Serializable
data class SkillNodeDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    /** Class ids allowed to learn this node; empty = any class. */
    val classIds: Set<Int> = emptySet(),
    val maxRank: Int = 3,
    val minLevel: Int = 1,
    /** Prerequisite node ids that must have at least rank 1. */
    val requires: Set<String> = emptySet(),
    /** Optional spell id taught automatically when rank 1 is learned (0 = none). */
    val grantsSpellId: Int = 0,
    val attributeBonuses: Map<String, Int> = emptyMap(),
    val attackDamageBonus: Int = 0,
    val armorDefenseBonus: Int = 0,
    val critChanceBpBonus: Int = 0
)

/**
 * Opt-in skill / talent tree rules in `game.json` (`"skillTree": { ... }`).
 * Total earned points = `(character.level - 1) * pointsPerLevel`; spent points = sum of learned node ranks.
 */
@Serializable
data class SkillTreeDefinition(
    val enabled: Boolean = true,
    val pointsPerLevel: Int = 1,
    val nodes: List<SkillNodeDefinition> = emptyList()
)

/** Pure helpers that sum a character's active boon stacks and learned skill-tree ranks into combat & attribute bonuses. */
object BuildBonuses {
    const val BOON_VAR_PREFIX = "boon:"
    const val SKILL_VAR_PREFIX = "skill:"

    fun boonStacks(character: CharacterState, boonId: String): Int =
        (character.variables["$BOON_VAR_PREFIX$boonId"] ?: 0).coerceAtLeast(0)

    fun skillRank(character: CharacterState, nodeId: String): Int =
        (character.variables["$SKILL_VAR_PREFIX$nodeId"] ?: 0).coerceAtLeast(0)

    fun spentSkillPoints(character: CharacterState, tree: SkillTreeDefinition): Int =
        tree.nodes.sumOf { skillRank(character, it.id).coerceAtMost(it.maxRank) }

    fun availableSkillPoints(character: CharacterState, tree: SkillTreeDefinition): Int {
        val earned = ((character.level - 1).coerceAtLeast(0)) * tree.pointsPerLevel
        return (earned - spentSkillPoints(character, tree)).coerceAtLeast(0)
    }

    fun attributeBonuses(character: CharacterState, def: GameDefinition): Map<String, Int> {
        val hasBoons = def.boons.isNotEmpty()
        val tree = def.skillTree?.takeIf { it.enabled && it.nodes.isNotEmpty() }
        if (!hasBoons && tree == null) return emptyMap()
        val out = mutableMapOf<String, Int>()
        if (hasBoons) {
            for (b in def.boons) {
                val stacks = boonStacks(character, b.id).coerceAtMost(b.maxStacks)
                if (stacks <= 0) continue
                for ((k, v) in b.attributeBonuses) out[k] = (out[k] ?: 0) + v * stacks
            }
        }
        if (tree != null) {
            for (n in tree.nodes) {
                val rank = skillRank(character, n.id).coerceAtMost(n.maxRank)
                if (rank <= 0) continue
                for ((k, v) in n.attributeBonuses) out[k] = (out[k] ?: 0) + v * rank
            }
        }
        return out
    }

    fun attackDamageBonus(character: CharacterState, def: GameDefinition): Int {
        var total = 0
        for (b in def.boons) {
            val stacks = boonStacks(character, b.id).coerceAtMost(b.maxStacks)
            if (stacks > 0) total += b.attackDamageBonus * stacks
        }
        def.skillTree?.takeIf { it.enabled }?.nodes?.forEach { n ->
            val rank = skillRank(character, n.id).coerceAtMost(n.maxRank)
            if (rank > 0) total += n.attackDamageBonus * rank
        }
        return total
    }

    fun armorDefenseBonus(character: CharacterState, def: GameDefinition): Int {
        var total = 0
        for (b in def.boons) {
            val stacks = boonStacks(character, b.id).coerceAtMost(b.maxStacks)
            if (stacks > 0) total += b.armorDefenseBonus * stacks
        }
        def.skillTree?.takeIf { it.enabled }?.nodes?.forEach { n ->
            val rank = skillRank(character, n.id).coerceAtMost(n.maxRank)
            if (rank > 0) total += n.armorDefenseBonus * rank
        }
        return total
    }

    fun critChanceBpBonus(character: CharacterState, def: GameDefinition): Int {
        var total = 0
        for (b in def.boons) {
            val stacks = boonStacks(character, b.id).coerceAtMost(b.maxStacks)
            if (stacks > 0) total += b.critChanceBpBonus * stacks
        }
        def.skillTree?.takeIf { it.enabled }?.nodes?.forEach { n ->
            val rank = skillRank(character, n.id).coerceAtMost(n.maxRank)
            if (rank > 0) total += n.critChanceBpBonus * rank
        }
        return total
    }

    fun lifeLeechPercentBonus(character: CharacterState, def: GameDefinition): Int {
        var total = 0
        for (b in def.boons) {
            val stacks = boonStacks(character, b.id).coerceAtMost(b.maxStacks)
            if (stacks > 0) total += b.lifeLeechPercentBonus * stacks
        }
        return total
    }
}
