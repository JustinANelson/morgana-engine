package com.morgana.client.ui

import com.morgana.client.ui.RunSummaryModel.RunOutcome
import com.morgana.client.ui.RunSummaryModel.RunTracker
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunSummaryModelTest {

    @Test
    fun `tracker records dungeon extraction with floor, kills, boss, xp, and duration`() {
        val tracker = RunTracker()

        assertNull(tracker.onMapEntered("Brightwater", hasGenerator = false, 2, 100L, 1_000L))
        assertFalse(tracker.active)

        assertNull(tracker.onMapEntered("The Sunken Ruins (floor 1)", hasGenerator = true, 2, 100L, 10_000L))
        assertTrue(tracker.active)

        val mob = NpcDefinition(id = 1, name = "Skeleton", behavior = NpcBehavior.ATTACK_ON_SIGHT)
        val boss = NpcDefinition(id = 2, name = "Vorlash", title = "Floor Guardian", behavior = NpcBehavior.ATTACK_ON_SIGHT)
        val vendor = NpcDefinition(id = 3, name = "Mira", behavior = NpcBehavior.SHOPKEEPER)

        tracker.onNpcKilled(wasAlive = true, isNowAlive = false, npcDef = mob)
        tracker.onNpcKilled(wasAlive = true, isNowAlive = false, npcDef = vendor)
        tracker.onNpcKilled(wasAlive = true, isNowAlive = true, npcDef = mob)

        assertNull(tracker.onMapEntered("The Sunken Ruins (floor 3)", hasGenerator = true, 2, 220L, 55_000L))
        tracker.onNpcKilled(wasAlive = true, isNowAlive = false, npcDef = boss)

        val summary = tracker.onMapEntered("Brightwater", hasGenerator = false, 3, 450L, 95_000L)
        assertNotNull(summary)
        assertFalse(tracker.active)

        assertEquals(RunOutcome.EXTRACTED, summary!!.outcome)
        assertEquals(3, summary.deepestFloor)
        assertEquals(2, summary.floorsVisited)
        assertEquals(2, summary.kills)
        assertEquals(listOf("Vorlash"), summary.bossesDefeated)
        assertEquals(350L, summary.xpGained)
        assertEquals(1, summary.levelsGained)
        assertEquals(85, summary.durationSeconds)
        assertEquals("1m 25s", RunSummaryModel.formatDuration(summary.durationSeconds))
    }

    @Test
    fun `tracker marks outcome as defeated when player hits zero hp or receives death notice`() {
        val tracker = RunTracker()
        tracker.onMapEntered("The Sunken Ruins (floor 1)", hasGenerator = true, 1, 0L, 1_000L)
        tracker.onPlayerVitals(0)

        val summary = tracker.onMapEntered("Brightwater", hasGenerator = false, 1, 15L, 16_000L)
        assertNotNull(summary)
        assertEquals(RunOutcome.DEFEATED, summary!!.outcome)
        assertEquals("15s", RunSummaryModel.formatDuration(summary.durationSeconds))
    }
}
