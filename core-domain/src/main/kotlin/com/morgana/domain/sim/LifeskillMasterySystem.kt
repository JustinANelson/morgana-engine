package com.morgana.domain.sim

import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.content.ResourceDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.LifeskillBranchDefinition
import com.morgana.domain.game.LifeskillDefinition
import com.morgana.domain.game.LifeskillMasteryConfig

/**
 * 3-tier Lifeskill Mastery progression, specialization funnel enforcement, and gathering/crafting perk hooks.
 *
 * Kept in its own file so `InventorySystem`, `EconomySystem`, and `WorldSimulation` stay clean.
 */
internal fun WorldSimulation.lifeskillConfig(): LifeskillMasteryConfig? =
    game.definition.lifeskillMastery?.takeIf { it.enabled && it.lifeskills.isNotEmpty() }

internal fun WorldSimulation.resolveLifeskillForResource(resDef: ResourceDefinition): LifeskillDefinition? {
    val cfg = lifeskillConfig() ?: return null
    val cat = resDef.category.name
    return cfg.lifeskills.firstOrNull { ls -> ls.resourceCategories.any { it.equals(cat, ignoreCase = true) } }
}

internal fun WorldSimulation.resolveLifeskillForRecipe(recipe: RecipeDefinition): Pair<LifeskillDefinition, LifeskillBranchDefinition?>? {
    val cfg = lifeskillConfig() ?: return null
    val ls = if (recipe.professionId.isNotBlank()) {
        cfg.findLifeskill(recipe.professionId)
    } else {
        val st = recipe.stationType.name
        cfg.lifeskills.firstOrNull { l -> l.stationTypes.any { it.equals(st, ignoreCase = true) } }
    } ?: return null
    val branch = if (recipe.branchId.isNotBlank()) ls.branches.firstOrNull { it.id == recipe.branchId } else null
    return ls to branch
}

/**
 * Returns a human-readable refusal message if [player] does not meet [recipe]'s lifeskill/branch requirement,
 * or null when crafting is permitted.
 */
internal fun WorldSimulation.checkLifeskillRecipeRequirement(player: CharacterState, recipe: RecipeDefinition): String? {
    val cfg = lifeskillConfig() ?: return null
    val reqLv = recipe.requiredProfessionLevel
    if (reqLv <= 0 && recipe.branchId.isBlank()) return null

    val resolved = resolveLifeskillForRecipe(recipe) ?: return null
    val (ls, branch) = resolved
    val state = player.lifeskillState

    if (branch != null) {
        if (branch.id !in state.journeymanBranches) {
            return "Requires the ${branch.name} specialization (${ls.name} Lv ${cfg.apprenticeMaxLevel} -> Journeyman)."
        }
        val bLv = state.branchLevelOf(branch.id, cfg.apprenticeMaxLevel)
        if (reqLv > 0 && bLv < reqLv) {
            return "Requires ${branch.name} Lv $reqLv (you are Lv $bLv)."
        }
        return null
    }

    val baseLv = state.levelOf(ls.id)
    val bestBranchLv = ls.branches.maxOfOrNull { state.branchLevelOf(it.id, cfg.apprenticeMaxLevel) } ?: 0
    val effectiveLv = maxOf(baseLv, bestBranchLv)
    if (reqLv > 0 && effectiveLv < reqLv) {
        return "Requires ${ls.name} Lv $reqLv (you are Lv $effectiveLv)."
    }
    return null
}

/** Harvest damage dealt per swing on [resDef]: 2 if the player has a qualifying branch perk, else 1. */
internal fun WorldSimulation.lifeskillHarvestPower(player: CharacterState, resDef: ResourceDefinition): Int {
    val cfg = lifeskillConfig() ?: return 1
    val ls = resolveLifeskillForResource(resDef) ?: return 1
    val state = player.lifeskillState
    val hasExtraHit = ls.branches.any { b ->
        b.extraHarvestHitAtLevel > 0 &&
            b.id in state.journeymanBranches &&
            state.branchLevelOf(b.id, cfg.apprenticeMaxLevel) >= b.extraHarvestHitAtLevel
    }
    return if (hasExtraHit) 2 else 1
}

/**
 * Awards one lifeskill action (from gathering or crafting) to [characterId], advances base Apprentice level
 * and any qualifying Journeyman/Grandmaster branch, and returns the player's effective bonus yield/refund chance percent.
 */
internal fun WorldSimulation.onLifeskillActionCompleted(
    characterId: String,
    lifeskill: LifeskillDefinition,
    preferredBranchId: String,
    out: MutableList<WorldSimulationEvent>
): Int {
    val cfg = lifeskillConfig() ?: return 0
    val player = players[characterId] ?: return 0
    val st = player.lifeskillState
    val levels = st.levels.toMutableMap()
    val progress = st.progress.toMutableMap()
    var changed = false

    fun advanceTrack(trackId: String, trackName: String, maxLevel: Int, minLevel: Int = 1) {
        val curLv = (levels[trackId] ?: minLevel).coerceAtLeast(minLevel)
        if (curLv >= maxLevel) return
        val need = (cfg.actionsPerLevel.coerceAtLeast(1) * curLv).coerceAtLeast(1)
        val nextProg = (progress[trackId] ?: 0) + 1
        changed = true
        if (nextProg >= need) {
            val newLv = (curLv + 1).coerceAtMost(maxLevel)
            levels[trackId] = newLv
            progress[trackId] = nextProg - need
            out.add(
                WorldSimulationEvent.FloatingTextEmitted(
                    mapId = player.position.mapId,
                    tileX = player.position.tileX,
                    tileY = player.position.tileY,
                    text = "$trackName Lv $newLv!",
                    category = "LEVEL_UP",
                    colorHex = "#FACC15"
                )
            )
            out.add(tellPlayer(player, "$trackName mastery reached level $newLv!", "INFO"))
        } else {
            progress[trackId] = nextProg
        }
    }

    // 1. Always advance base Apprentice lifeskill up to apprenticeMaxLevel.
    advanceTrack(lifeskill.id, lifeskill.name, cfg.apprenticeMaxLevel, minLevel = 1)

    // 2. Advance unlocked Journeyman / Grandmaster branch(es) under this lifeskill.
    val unlockedBranches = lifeskill.branches.filter { it.id in st.journeymanBranches }
    val targetBranches = if (preferredBranchId.isNotBlank() && unlockedBranches.any { it.id == preferredBranchId }) {
        unlockedBranches.filter { it.id == preferredBranchId }
    } else {
        unlockedBranches
    }
    for (b in targetBranches) {
        val cap = if (b.id in st.grandmasterBranches) cfg.grandmasterMaxLevel else cfg.journeymanMaxLevel
        advanceTrack(b.id, b.name, cap, minLevel = cfg.apprenticeMaxLevel + 1)
    }

    val updatedState = if (changed) st.copy(levels = levels, progress = progress) else st
    if (changed) {
        val updatedPlayer = player.copy(lifeskillState = updatedState)
        players[characterId] = updatedPlayer
        out.add(WorldSimulationEvent.CharacterUpdated(updatedPlayer))
    }

    // Compute effective bonus chance percent (Apprentice base + best matching unlocked branch).
    val baseLv = updatedState.levelOf(lifeskill.id)
    val baseBonus = (baseLv - 1).coerceAtLeast(0) * cfg.apprenticeBonusPercentPerLevel.coerceAtLeast(0)
    val branchBonus = targetBranches.maxOfOrNull { b ->
        val bLv = updatedState.branchLevelOf(b.id, cfg.apprenticeMaxLevel)
        (bLv - cfg.apprenticeMaxLevel).coerceAtLeast(0) * b.bonusPercentPerLevel.coerceAtLeast(0)
    } ?: 0
    return (baseBonus + branchBonus).coerceIn(0, 95)
}

/** Unlocks a Tier 2 (Journeyman) specialization branch once its parent lifeskill reaches [LifeskillMasteryConfig.apprenticeMaxLevel]. */
fun WorldSimulation.unlockLifeskillBranch(characterId: String, branchId: String): List<WorldSimulationEvent> {
    val cfg = lifeskillConfig() ?: return emptyList()
    val player = players[characterId] ?: return emptyList()
    val (ls, branch) = cfg.findBranchWithParent(branchId) ?: return emptyList()
    val st = player.lifeskillState

    if (branch.id in st.journeymanBranches) {
        return listOf(tellPlayer(player, "You have already specialized in ${branch.name}.", "INFO"))
    }
    val baseLv = st.levelOf(ls.id)
    if (baseLv < cfg.apprenticeMaxLevel) {
        return listOf(tellPlayer(player, "Reach ${ls.name} Lv ${cfg.apprenticeMaxLevel} before specializing in ${branch.name}.", "WARNING"))
    }
    if (st.journeymanBranches.size >= cfg.maxJourneymanBranches) {
        return listOf(
            tellPlayer(
                player,
                "All ${cfg.maxJourneymanBranches} Journeyman slots are full. Step down another specialization first.",
                "WARNING"
            )
        )
    }

    val startLv = cfg.apprenticeMaxLevel + 1
    val updatedState = st.copy(
        journeymanBranches = st.journeymanBranches + branch.id,
        levels = st.levels + (branch.id to startLv),
        progress = st.progress + (branch.id to 0)
    )
    val updated = player.copy(lifeskillState = updatedState)
    players[characterId] = updated
    return listOf(
        WorldSimulationEvent.CharacterUpdated(updated),
        WorldSimulationEvent.FloatingTextEmitted(
            mapId = updated.position.mapId,
            tileX = updated.position.tileX,
            tileY = updated.position.tileY,
            text = "Journeyman: ${branch.name}!",
            category = "LEVEL_UP",
            colorHex = "#38BDF8"
        ),
        tellPlayer(updated, "Unlocked Journeyman specialization: ${branch.name} (Lv $startLv)!", "INFO")
    )
}

/** Promotes an existing Tier 2 branch (at [LifeskillMasteryConfig.journeymanMaxLevel]) to Tier 3 (Grandmaster). */
fun WorldSimulation.promoteLifeskillGrandmaster(characterId: String, branchId: String): List<WorldSimulationEvent> {
    val cfg = lifeskillConfig() ?: return emptyList()
    val player = players[characterId] ?: return emptyList()
    val (_, branch) = cfg.findBranchWithParent(branchId) ?: return emptyList()
    val st = player.lifeskillState

    if (branch.id !in st.journeymanBranches) {
        return listOf(tellPlayer(player, "Unlock ${branch.name} as a Journeyman specialization first.", "WARNING"))
    }
    if (branch.id in st.grandmasterBranches) {
        return listOf(tellPlayer(player, "You are already a Grandmaster in ${branch.name}.", "INFO"))
    }
    val bLv = st.branchLevelOf(branch.id, cfg.apprenticeMaxLevel)
    if (bLv < cfg.journeymanMaxLevel) {
        return listOf(tellPlayer(player, "Reach ${branch.name} Lv ${cfg.journeymanMaxLevel} before promoting to Grandmaster.", "WARNING"))
    }
    if (st.grandmasterBranches.size >= cfg.maxGrandmasterBranches) {
        return listOf(
            tellPlayer(
                player,
                "All ${cfg.maxGrandmasterBranches} Grandmaster slot(s) are full. Step down your current Grandmaster first.",
                "WARNING"
            )
        )
    }

    val startLv = cfg.journeymanMaxLevel + 1
    val updatedState = st.copy(
        grandmasterBranches = st.grandmasterBranches + branch.id,
        levels = st.levels + (branch.id to startLv),
        progress = st.progress + (branch.id to 0)
    )
    val updated = player.copy(lifeskillState = updatedState)
    players[characterId] = updated
    return listOf(
        WorldSimulationEvent.CharacterUpdated(updated),
        WorldSimulationEvent.FloatingTextEmitted(
            mapId = updated.position.mapId,
            tileX = updated.position.tileX,
            tileY = updated.position.tileY,
            text = "Grandmaster: ${branch.name}!",
            category = "LEVEL_UP",
            colorHex = "#FACC15"
        ),
        tellPlayer(updated, "Promoted to Grandmaster in ${branch.name} (Lv $startLv)!", "INFO")
    )
}

/** Steps a branch down one tier: Grandmaster -> Journeyman (Lv 12), or Journeyman -> unspecialized (frees slot). */
fun WorldSimulation.stepDownLifeskillBranch(characterId: String, branchId: String): List<WorldSimulationEvent> {
    val cfg = lifeskillConfig() ?: return emptyList()
    val player = players[characterId] ?: return emptyList()
    val (_, branch) = cfg.findBranchWithParent(branchId) ?: return emptyList()
    val st = player.lifeskillState

    val updatedState = when {
        branch.id in st.grandmasterBranches -> st.copy(
            grandmasterBranches = st.grandmasterBranches - branch.id,
            levels = st.levels + (branch.id to cfg.journeymanMaxLevel),
            progress = st.progress + (branch.id to 0)
        )
        branch.id in st.journeymanBranches -> st.copy(
            journeymanBranches = st.journeymanBranches - branch.id,
            levels = st.levels - branch.id,
            progress = st.progress - branch.id
        )
        else -> return listOf(tellPlayer(player, "You do not hold the ${branch.name} specialization.", "INFO"))
    }
    val updated = player.copy(lifeskillState = updatedState)
    players[characterId] = updated
    val msg = if (branch.id in st.grandmasterBranches) {
        "Stepped down ${branch.name} from Grandmaster to Journeyman (Lv ${cfg.journeymanMaxLevel})."
    } else {
        "Released Journeyman specialization: ${branch.name}. Slot is now open."
    }
    return listOf(
        WorldSimulationEvent.CharacterUpdated(updated),
        tellPlayer(updated, msg, "INFO")
    )
}
