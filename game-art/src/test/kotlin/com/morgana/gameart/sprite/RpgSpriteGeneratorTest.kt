package com.morgana.gameart.sprite


import com.morgana.tilegen.*
import com.morgana.tilegen.material.*
import com.morgana.tilegen.spec.*
import com.morgana.tilegen.sprite.*
import com.morgana.tilegen.structure.*
import com.morgana.tilegen.export.*
import com.morgana.tilegen.autotile.*
import com.morgana.gameart.export.*
import com.morgana.gameart.whiteriver.*

import com.morgana.tilegen.sprite.Direction8
import com.morgana.tilegen.sprite.PaperDollAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RpgSpriteGeneratorTest {
    @Test
    fun `32x48 NPC and enemy sprites render`() {
        // 32x48 RMXP NPCs (12) and Enemies (22)
        val rpgGen = RpgSpriteGenerator(seed = 1337L, cellWidth = 32, cellHeight = 48)
        assertEquals(12, RpgNpcKind.entries.size)
        assertEquals(22, RpgEnemyKind.entries.size)
        val (npcSheet, npcTiles) = rpgGen.generateNpcSheet(RpgNpcKind.CAPTAIN)
        val expectedCols = PaperDollAction.EXTENDED.maxOf { it.defaultFrames }
        assertEquals(expectedCols * 32, npcSheet.width)
        assertEquals(36 * 48, npcSheet.height)
        assertEquals(36 * expectedCols, npcTiles.size)

        for (enemy in RpgEnemyKind.entries) {
            val frame = rpgGen.generateEnemyFrame(enemy, Direction8.S, PaperDollAction.IDLE, 0)
            assertEquals(32, frame.width)
            assertEquals(48, frame.height)
            assertTrue(frame.pixels.any { (it and 0xFF) != 0 }, "Expected visible pixels for ${enemy.key}")
        }
    }
}
