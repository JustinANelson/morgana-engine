package com.morgana.domain.game

import kotlinx.serialization.Serializable

/**
 * A Tier 2 / Tier 3 specialization branch under a base [LifeskillDefinition]
 * (e.g., `weaponsmith` or `armorsmith` under `smithing`).
 */
@Serializable
data class LifeskillBranchDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    /** Bonus yield / extra craft output / material refund chance percent per branch level above [LifeskillMasteryConfig.apprenticeMaxLevel]. */
    val bonusPercentPerLevel: Int = 4,
    /** When > 0, reaching this branch level grants +1 harvest power per swing on matching resource nodes. */
    val extraHarvestHitAtLevel: Int = 0
)

/**
 * A base Tier 1 lifeskill (Apprentice) such as Mining, Woodcutting, Foraging, Smithing, Carpentry, Tailoring, or Cooking.
 */
@Serializable
data class LifeskillDefinition(
    val id: String,
    val name: String,
    /** `"GATHERING"` or `"CRAFTING"`. */
    val kind: String = "GATHERING",
    /** `ResourceCategory` names (e.g. `ORE_VEIN`, `TREE`, `HERB_PATCH`) that automatically train this lifeskill. */
    val resourceCategories: List<String> = emptyList(),
    /** `CraftingStationType` names (e.g. `ANVIL`, `COOKING_FIRE`) used as fallback when a recipe omits `professionId`. */
    val stationTypes: List<String> = emptyList(),
    /** Tier 2 specialization branches (typically 2 per base lifeskill). */
    val branches: List<LifeskillBranchDefinition> = emptyList()
)

/**
 * 3-tier Lifeskill Mastery configuration (`lifeskillMastery` in `game.json`):
 * - **Tier 1 (Apprentice, Lv 1..[apprenticeMaxLevel]):** All base lifeskills are open to every player.
 * - **Tier 2 (Journeyman, Lv [apprenticeMaxLevel]+1..[journeymanMaxLevel]):** Players pick up to [maxJourneymanBranches]
 *   specialization branches from across all lifeskills.
 * - **Tier 3 (Grandmaster, Lv [journeymanMaxLevel]+1..[grandmasterMaxLevel]):** Players promote up to
 *   [maxGrandmasterBranches] of their Journeyman branches to Grandmaster.
 */
@Serializable
data class LifeskillMasteryConfig(
    val enabled: Boolean = true,
    val apprenticeMaxLevel: Int = 5,
    val journeymanMaxLevel: Int = 12,
    val grandmasterMaxLevel: Int = 20,
    val maxJourneymanBranches: Int = 3,
    val maxGrandmasterBranches: Int = 1,
    /** Actions required to advance from level `L` to `L + 1` (`actionsPerLevel * L`). */
    val actionsPerLevel: Int = 10,
    /** Bonus yield / refund chance percent per Apprentice level above 1. */
    val apprenticeBonusPercentPerLevel: Int = 2,
    val lifeskills: List<LifeskillDefinition> = emptyList()
) {
    fun findLifeskill(id: String): LifeskillDefinition? = lifeskills.firstOrNull { it.id == id }

    fun findBranchWithParent(branchId: String): Pair<LifeskillDefinition, LifeskillBranchDefinition>? {
        for (ls in lifeskills) {
            val branch = ls.branches.firstOrNull { it.id == branchId }
            if (branch != null) return ls to branch
        }
        return null
    }
}

/**
 * Persisted 3-tier lifeskill mastery state stored on each [com.morgana.domain.entity.CharacterState].
 */
@Serializable
data class CharacterLifeskillState(
    /** Current levels for base lifeskills (`1..apprenticeMaxLevel`) and unlocked branches (`apprenticeMaxLevel+1..grandmasterMaxLevel`). */
    val levels: Map<String, Int> = emptyMap(),
    /** Accumulated actions toward the next level for each lifeskill or branch id. */
    val progress: Map<String, Int> = emptyMap(),
    /** Branch ids unlocked at Tier 2 (Journeyman). */
    val journeymanBranches: Set<String> = emptySet(),
    /** Branch ids promoted to Tier 3 (Grandmaster); always a subset of [journeymanBranches]. */
    val grandmasterBranches: Set<String> = emptySet()
) {
    fun levelOf(id: String): Int = (levels[id] ?: 1).coerceAtLeast(1)

    fun progressOf(id: String): Int = (progress[id] ?: 0).coerceAtLeast(0)

    fun branchLevelOf(branchId: String, apprenticeMaxLevel: Int = 5): Int =
        if (branchId in journeymanBranches) (levels[branchId] ?: (apprenticeMaxLevel + 1)).coerceAtLeast(apprenticeMaxLevel + 1)
        else 0
}
