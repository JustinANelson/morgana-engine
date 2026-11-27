package com.morgana.domain.game

import kotlinx.serialization.Serializable

/**
 * Per-action-skill mastery progression (`skillMastery` in `game.json`).
 *
 * Landing hits with an action skill advances its mastery level and grants a flat damage multiplier
 * (`100 + (level - 1) * damagePercentPerLevel` percent). Kept flat and extensible for future per-level
 * modifiers (such as PvP scaling).
 */
@Serializable
data class SkillMasteryConfig(
    /** Easy kill-switch: set false in `game.json` to disable skill mastery tracking and bonuses. */
    val enabled: Boolean = true,
    /** Maximum mastery level per skill. */
    val maxLevel: Int = 20,
    /** Hits/uses required to advance from level `L` to `L + 1` (`usesPerLevel * L`). */
    val usesPerLevel: Int = 20,
    /** Flat bonus damage percent per mastery level above 1 (`level 1 = +0%`, `level 2 = +damagePercentPerLevel%`). */
    val damagePercentPerLevel: Int = 2
)

/**
 * Food-fueled offline mastery training and daily-capped online AFK training (`training` in `game.json`).
 *
 * - **Offline training** consumes food rations ONLY while the character is logged out. A ration bar locks to
 *   a single food tier (`ItemDefinition.trainingTier`) at a time so lower-tier food cannot be mixed to reach
 *   a higher tier's cap.
 * - **Online AFK mode** grants full mastery progress while online up to [onlineAfkDailyLimitMinutes] per UTC day
 *   without consuming offline food rations.
 */
@Serializable
data class TrainingConfig(
    /** Easy kill-switch: set false in `game.json` to disable offline & online AFK training. */
    val enabled: Boolean = true,
    /** Mastery hits/uses awarded per fueled offline minute. */
    val offlineHitsPerMinute: Int = 6,
    /** Daily budget (minutes per UTC day) for online AFK training without consuming food. */
    val onlineAfkDailyLimitMinutes: Int = 30,
    /** Mastery hits/uses awarded per minute while online AFK mode is active. */
    val onlineAfkHitsPerMinute: Int = 12,
    /** Simulation ticks between online AFK progress grants (default 100 ticks = 5 seconds at 20 TPS). */
    val onlineAfkIntervalTicks: Int = 100
)

/** Focus kind constants for [CharacterTrainingState.focusKind]. */
object TrainingFocusKind {
    const val WEAPON = "WEAPON"
    const val SKILL = "SKILL"
}

/**
 * Persisted offline & online AFK training state stored on each [com.morgana.domain.entity.CharacterState].
 */
@Serializable
data class CharacterTrainingState(
    /** [TrainingFocusKind.WEAPON], [TrainingFocusKind.SKILL], or empty when unset. */
    val focusKind: String = "",
    /** Weapon type id (e.g. `"sword"`) when [focusKind] is `WEAPON`, or action skill id string when `SKILL`. */
    val focusId: String = "",
    /** Locked food tier currently loaded in the offline ration bar (`0` = empty / unlocked). */
    val foodTier: Int = 0,
    /** Remaining offline training fuel in seconds (consumed ONLY while offline). */
    val fueledSeconds: Int = 0,
    /** Maximum fuel capacity in seconds for the currently locked [foodTier]. */
    val capSeconds: Int = 0,
    /** UTC epoch milliseconds when the character was last online. */
    val lastOnlineEpochMs: Long = 0L,
    /** Whether online AFK training mode is currently active. */
    val afkActive: Boolean = false,
    /** UTC day index (`epochMs / 86_400_000L`) for [afkUsedSecondsToday]. */
    val afkDayIndex: Long = 0L,
    /** Seconds of online AFK training consumed on [afkDayIndex]. */
    val afkUsedSecondsToday: Int = 0
) {
    fun remainingAfkSeconds(dailyLimitMinutes: Int, currentDayIndex: Long): Int {
        val cap = (dailyLimitMinutes.coerceAtLeast(0)) * 60
        val used = if (currentDayIndex > 0L && afkDayIndex == currentDayIndex) afkUsedSecondsToday else 0
        return (cap - used).coerceAtLeast(0)
    }
}
