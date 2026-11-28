package com.morgana.client

import com.morgana.client.ui.QuestTracker
import com.morgana.client.ui.QuestTracker.Sighting
import com.morgana.domain.event.PlayerQuestState
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.event.QuestObjective
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.domain.event.QuestStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuestTrackerTest {
    private val names = QuestTracker.Names(
        npcs = mapOf(1 to "Rat", 2 to "Elder"), items = mapOf(5 to "Herb"), resources = mapOf(7 to "Oak"),
        recipes = mapOf(3 to "Potion"), maps = mapOf(1 to "Town", 2 to "Cave")
    )
    private fun world(npcs: List<Sighting> = emptyList(), resources: List<Sighting> = emptyList(), px: Int = 0, py: Int = 0) =
        QuestTracker.World(mapId = 1, playerX = px, playerY = py, npcs = npcs, resources = resources, names = names)

    private fun quest(id: Int, vararg obj: QuestObjective) = QuestDefinition(id = id, name = "Quest $id", objectives = obj.toList())
    private fun state(id: Int, vararg counts: Int, status: QuestStatus = QuestStatus.IN_PROGRESS) = PlayerQuestState(id, status, counts.toList())
    private fun obj(type: QuestObjectiveType, target: Int, count: Int = 1, text: String = "") = QuestObjective(type, target, count, text)

    @Test
    fun `generic wording names the target and the author's description wins`() {
        assertEquals("Defeat Rat", QuestTracker.objectiveText(obj(QuestObjectiveType.KILL_NPC, 1), names))
        assertEquals("Collect Herb", QuestTracker.objectiveText(obj(QuestObjectiveType.COLLECT_ITEM, 5), names))
        assertEquals("Talk to Elder", QuestTracker.objectiveText(obj(QuestObjectiveType.TALK_TO_NPC, 2), names))
        assertEquals("Gather Oak", QuestTracker.objectiveText(obj(QuestObjectiveType.HARVEST_RESOURCE, 7), names))
        assertEquals("Craft Potion", QuestTracker.objectiveText(obj(QuestObjectiveType.CRAFT_RECIPE, 3), names))
        assertEquals("Visit Cave", QuestTracker.objectiveText(obj(QuestObjectiveType.VISIT_MAP, 2), names))
        assertEquals("Defeat #99", QuestTracker.objectiveText(obj(QuestObjectiveType.KILL_NPC, 99), names))
        assertEquals("Clear the cellar", QuestTracker.objectiveText(obj(QuestObjectiveType.KILL_NPC, 1, text = "  Clear the cellar "), names))
    }

    @Test
    fun `only quests in progress are tracked, and untracked ones are left out`() {
        val defs = mapOf(1 to quest(1, obj(QuestObjectiveType.KILL_NPC, 1)), 2 to quest(2, obj(QuestObjectiveType.KILL_NPC, 1)), 3 to quest(3, obj(QuestObjectiveType.KILL_NPC, 1)))
        val quests = mapOf(1 to state(1, 0), 2 to state(2, 1, status = QuestStatus.COMPLETED), 3 to state(3, 0), 9 to state(9, 0))
        assertEquals(listOf(1, 3), QuestTracker.entries(quests, defs, world()).map { it.questId }, "completed and unknown quests are skipped")
        assertEquals(listOf(3), QuestTracker.entries(quests, defs, world(), untracked = setOf(1)).map { it.questId })
    }

    @Test
    fun `the number of tracked quests is capped`() {
        val defs = (1..8).associateWith { quest(it, obj(QuestObjectiveType.KILL_NPC, 1)) }
        val quests = (1..8).associateWith { state(it, 0) }
        assertEquals(QuestTracker.MAX_QUESTS, QuestTracker.entries(quests, defs, world()).size)
    }

    @Test
    fun `the nearest matching NPC becomes the waypoint with compass direction`() {
        val npcs = listOf(Sighting(1, 10, 10), Sighting(1, 3, 2), Sighting(2, 1, 1))
        val e = QuestTracker.entries(mapOf(1 to state(1, 0)), mapOf(1 to quest(1, obj(QuestObjectiveType.KILL_NPC, 1, 3))), world(npcs, px = 2, py = 2)).single()
        val line = e.lines.single()
        assertEquals(QuestTracker.Waypoint(3, 2, "Rat"), line.waypoint)
        assertEquals("Defeat Rat (0/3) - Nearby", QuestTracker.lineText(line))
    }

    @Test
    fun `an objective whose target is elsewhere says so instead of a waypoint`() {
        val defs = mapOf(
            1 to quest(1, obj(QuestObjectiveType.TALK_TO_NPC, 2), obj(QuestObjectiveType.VISIT_MAP, 2), obj(QuestObjectiveType.VISIT_MAP, 1), obj(QuestObjectiveType.COLLECT_ITEM, 5, 2))
        )
        val lines = QuestTracker.entries(mapOf(1 to state(1, 0, 0, 0, 1)), defs, world()).single().lines
        assertEquals("Talk to Elder - not on this map", QuestTracker.lineText(lines[0]))
        assertEquals("Visit Cave - another map", QuestTracker.lineText(lines[1]))
        assertEquals("Visit Town", QuestTracker.lineText(lines[2]))
        assertEquals("Collect Herb (1/2)", QuestTracker.lineText(lines[3]))
        assertTrue(lines.all { it.waypoint == null })
    }

    @Test
    fun `finished objectives show done and need no waypoint, and a finished quest is ready`() {
        val defs = mapOf(1 to quest(1, obj(QuestObjectiveType.KILL_NPC, 1, 2), obj(QuestObjectiveType.COLLECT_ITEM, 5)))
        val e = QuestTracker.entries(mapOf(1 to state(1, 5, 1)), defs, world(listOf(Sighting(1, 1, 1)))).single()
        assertEquals(2, e.lines[0].current, "counts never exceed the requirement")
        assertNull(e.lines[0].waypoint)
        assertEquals("Defeat Rat (done)", QuestTracker.lineText(e.lines[0]))
        assertTrue(e.ready)
        assertFalse(QuestTracker.entries(mapOf(1 to state(1, 0, 1)), defs, world()).single().ready)
    }

    @Test
    fun `missing counts count as zero`() {
        val e = QuestTracker.entries(mapOf(1 to PlayerQuestState(1)), mapOf(1 to quest(1, obj(QuestObjectiveType.KILL_NPC, 1, 3))), world()).single()
        assertEquals(0, e.lines.single().current)
    }

    @Test
    fun `waypoints take the first open objective of each quest, nearest first, without duplicates`() {
        val defs = mapOf(
            1 to quest(1, obj(QuestObjectiveType.KILL_NPC, 1), obj(QuestObjectiveType.TALK_TO_NPC, 2)),
            2 to quest(2, obj(QuestObjectiveType.KILL_NPC, 1)),
            3 to quest(3, obj(QuestObjectiveType.TALK_TO_NPC, 2))
        )
        val w = world(listOf(Sighting(1, 8, 0), Sighting(2, 2, 0)))
        val entries = QuestTracker.entries(mapOf(1 to state(1, 1, 0), 2 to state(2, 0), 3 to state(3, 0)), defs, w)
        // quest 1's kill is done, so its waypoint is the Elder; quest 3 points at the same Elder; quest 2 at the Rat.
        assertEquals(listOf(QuestTracker.Waypoint(2, 0, "Elder"), QuestTracker.Waypoint(8, 0, "Rat")), QuestTracker.waypoints(entries, w))
    }

    @Test
    fun `a target on another map is marked by the server's guide tile with compass direction`() {
        val guide = com.morgana.domain.event.QuestGuide(questId = 1, objectiveIndex = 0, mapId = 1, tileX = 9, tileY = 5, targetMapName = "Cave", viaMapName = "Road")
        val w = world().copy(guides = listOf(guide))
        val e = QuestTracker.entries(mapOf(1 to state(1, 0)), mapOf(1 to quest(1, obj(QuestObjectiveType.KILL_NPC, 1))), w).single()
        val line = e.lines.single()
        assertEquals(QuestTracker.Waypoint(9, 5, "Rat"), line.waypoint)
        assertEquals("South-East", line.hint)
        assertEquals(listOf(line.waypoint), QuestTracker.waypoints(listOf(e), w))
        // a guide for another map, or another objective, is ignored
        val stale = world().copy(guides = listOf(guide.copy(mapId = 2)))
        assertNull(QuestTracker.entries(mapOf(1 to state(1, 0)), mapOf(1 to quest(1, obj(QuestObjectiveType.KILL_NPC, 1))), stale).single().lines.single().waypoint)
    }
}
