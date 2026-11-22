package com.morgana.domain.sim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LevelOffsetRulesTest {

    @Test
    fun `tier and xp scaling follow the level offset rules`() {
        // 3+ levels weaker -> Green, 20% XP
        assertEquals(EnemyLevelTier.GREEN, LevelOffsetRules.tier(playerLevel = 10, npcLevel = 7))
        assertEquals(20L, LevelOffsetRules.scaleXp(100L, playerLevel = 10, npcLevel = 7))

        // Near player level (-2..+2) -> White; exact same level = 200% XP, other white = 100% XP
        assertEquals(EnemyLevelTier.WHITE, LevelOffsetRules.tier(playerLevel = 10, npcLevel = 8))
        assertEquals(100L, LevelOffsetRules.scaleXp(100L, playerLevel = 10, npcLevel = 8))
        assertEquals(EnemyLevelTier.WHITE, LevelOffsetRules.tier(playerLevel = 10, npcLevel = 10))
        assertEquals(200L, LevelOffsetRules.scaleXp(100L, playerLevel = 10, npcLevel = 10))
        assertEquals(EnemyLevelTier.WHITE, LevelOffsetRules.tier(playerLevel = 10, npcLevel = 12))
        assertEquals(100L, LevelOffsetRules.scaleXp(100L, playerLevel = 10, npcLevel = 12))

        // 3..9 levels harder -> Red, 150% XP
        assertEquals(EnemyLevelTier.RED, LevelOffsetRules.tier(playerLevel = 10, npcLevel = 13))
        assertEquals(150L, LevelOffsetRules.scaleXp(100L, playerLevel = 10, npcLevel = 13))
        assertEquals(EnemyLevelTier.RED, LevelOffsetRules.tier(playerLevel = 10, npcLevel = 19))
        assertEquals(150L, LevelOffsetRules.scaleXp(100L, playerLevel = 10, npcLevel = 19))

        // 10+ levels harder -> Black, 200% XP
        assertEquals(EnemyLevelTier.BLACK, LevelOffsetRules.tier(playerLevel = 10, npcLevel = 20))
        assertEquals(200L, LevelOffsetRules.scaleXp(100L, playerLevel = 10, npcLevel = 20))
    }
}
