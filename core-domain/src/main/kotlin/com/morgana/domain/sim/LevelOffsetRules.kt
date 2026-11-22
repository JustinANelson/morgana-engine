package com.morgana.domain.sim

import kotlin.math.max

/**
 * Monster difficulty tier relative to the player's level.
 *
 * - [GREEN]: 3+ levels weaker than the player (`diff <= -3`) -> 20% XP.
 * - [WHITE]: near the player's level (`-2 <= diff <= +2`) -> 100% XP (or 200% on exact same level `diff == 0`).
 * - [RED]: 3 to 9 levels stronger (`+3 <= diff <= +9`) -> 150% XP.
 * - [BLACK]: 10+ levels stronger (`diff >= +10`) -> 200% XP.
 */
enum class EnemyLevelTier(val colorHex: String, val xpPercent: Int) {
    GREEN("86EFAC", 20),
    WHITE("FFFFFF", 100),
    RED("F87171", 150),
    BLACK("111827", 200)
}

object LevelOffsetRules {

    fun tier(playerLevel: Int, npcLevel: Int): EnemyLevelTier {
        val diff = npcLevel - playerLevel
        return when {
            diff <= -3 -> EnemyLevelTier.GREEN
            diff <= 2 -> EnemyLevelTier.WHITE
            diff <= 9 -> EnemyLevelTier.RED
            else -> EnemyLevelTier.BLACK
        }
    }

    fun xpPercent(playerLevel: Int, npcLevel: Int): Int {
        if (npcLevel == playerLevel) return 200
        return tier(playerLevel, npcLevel).xpPercent
    }

    fun scaleXp(baseXp: Long, playerLevel: Int, npcLevel: Int): Long {
        if (baseXp <= 0L) return 0L
        val pct = xpPercent(playerLevel, npcLevel)
        return max(1L, baseXp * pct / 100L)
    }
}
