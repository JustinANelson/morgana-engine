package com.morgana.domain.game

import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.Inventory
import kotlinx.serialization.Serializable

/**
 * How a character's gear, level and XP behave across a dungeon run (`RunRulesDefinition.stateMode`).
 */
@Serializable
enum class RunStateMode {
    /** Keep everything gained during a run, win or lose (classic ARPG / Lanternfall default). */
    PERSISTENT,
    /** Extraction: items, level and XP gained during a run are kept only if you exit alive; wiping, timing out or abandoning restores your pre-run state. */
    EXTRACTION,
    /** Roguelite: snapshot character state on entry and restore it when the run ends (win or lose), keeping only [RunRulesDefinition.metaCurrencyItemIds] and switches/variables. */
    EPHEMERAL
}

/**
 * What happens to a character who dies when permadeath is active (`RunRulesDefinition.permadeath`).
 */
@Serializable
enum class PermadeathMode {
    /** No permadeath: normal respawn or ghost death applies. */
    OFF,
    /** Soft-lock: the character is marked `fallen = true` and cannot re-enter the world, but stays on the account for graveyard / hall-of-fame viewing. */
    LOCK,
    /** Hard delete: the character is permanently removed from the account on death. */
    DELETE
}

/**
 * Opt-in dungeon run lifecycle rules in `game.json` (`"runs": { ... }`).
 * Controls run tracking (seed, depth, timer, kills), extraction/ephemeral run state, custom seeds, wipe behaviour, and permadeath.
 */
@Serializable
data class RunRulesDefinition(
    val enabled: Boolean = true,
    /** How character progress behaves across runs: `PERSISTENT`, `EXTRACTION`, or `EPHEMERAL`. */
    val stateMode: RunStateMode = RunStateMode.PERSISTENT,
    /** Permadeath policy on death (outside safe arenas): `OFF`, `LOCK` (graveyard soft-lock), or `DELETE` (permanent erase). */
    val permadeath: PermadeathMode = PermadeathMode.OFF,
    /** Optional time limit per run in simulation ticks (20 TPS; 0 = unlimited). Expiring ends the run as a timeout. */
    val timeLimitTicks: Int = 0,
    /** When true, players can set a custom seed with `/seed <number>` before entering a generated floor. */
    val allowCustomSeed: Boolean = true,
    /** When true, every player on a generated floor being downed/dead ends the run immediately. */
    val wipeEndsRun: Boolean = true,
    /** Item ids preserved when [stateMode] is `EPHEMERAL` (for meta-currencies / permanent unlock tokens). */
    val metaCurrencyItemIds: Set<Int> = emptySet()
)

/** Snapshot of a character's progression before entering a dungeon run (used by `EXTRACTION` and `EPHEMERAL` modes). */
@Serializable
data class PreRunSnapshot(
    val level: Int,
    val experience: Long,
    val unspentStatPoints: Int,
    val attributes: Map<String, Int>,
    val inventory: Inventory,
    val equipment: EquipmentLoadout
)

/** Live state of an active dungeon run for a party or solo delver (kept on `WorldSimulation.activeRuns`, hashed for replay). */
@Serializable
data class DungeonRunState(
    val runId: String,
    val ownerKey: String,
    val seed: Long,
    val startTick: Long,
    val timeLimitTicks: Int = 0,
    val depth: Int = 1,
    val floorsVisited: Set<Int> = emptySet(),
    val kills: Int = 0,
    val deaths: Int = 0,
    val memberIds: Set<String> = emptySet(),
    val memberSnapshots: Map<String, PreRunSnapshot> = emptyMap()
)
