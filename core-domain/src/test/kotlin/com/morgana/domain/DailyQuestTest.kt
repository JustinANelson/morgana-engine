package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.EventCondition
import com.morgana.domain.event.EventConditionType
import com.morgana.domain.event.QuestCheck
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.event.QuestObjective
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.domain.event.QuestStatus
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Repeatable quests with a cooldown: the basis of daily and weekly quests. */
class DailyQuestTest {
    private val daily = QuestDefinition(
        id = 9, name = "Daily patrol", repeatable = true, cooldownTicks = 1000L,
        objectives = listOf(QuestObjective(QuestObjectiveType.TALK_TO_NPC, 1, 1, ""))
    )

    private fun sim(def: QuestDefinition = daily): WorldSimulation {
        val s = WorldSimulation(game = MorganaClassic.COMPILED)
        s.registerMap(MapDefinition(id = 1, name = "Town", width = 10, height = 10))
        s.quests[def.id] = def
        s.spawnOrUpdatePlayer(CharacterState(id = "p1", accountId = "a1", name = "T", position = WorldPosition.fromTile(1, 3, 3, Direction.DOWN)))
        return s
    }

    private fun WorldSimulation.p() = players.getValue("p1")
    private fun WorldSimulation.run(vararg steps: EventCommand) =
        mutableListOf<WorldSimulationEvent>().also { executeEventCommands("p1", 7, steps.toList(), it) }
    private fun WorldSimulation.notStarted(): Boolean {
        run(EventCommand.Conditional(EventCondition(EventConditionType.QUEST, targetId = 9, quest = QuestCheck.NOT_STARTED), listOf(EventCommand.SetVariable("r", 1)), listOf(EventCommand.SetVariable("r", 0))))
        return p().variables["r"] == 1
    }

    private fun WorldSimulation.doIt() {
        run(EventCommand.StartQuest(9))
        spawnOrUpdatePlayer(p().copy(quests = p().quests + (9 to p().quests.getValue(9).copy(objectiveCounts = listOf(1)))))
        run(EventCommand.CompleteQuest(9))
    }

    @Test
    fun handingInStampsTheWorldTick() {
        val s = sim()
        s.worldEpochTicks = 500L
        s.doIt()
        assertEquals(QuestStatus.COMPLETED, s.p().quests.getValue(9).status)
        assertEquals(500L, s.p().quests.getValue(9).completedAtTick)
    }

    @Test
    fun theQuestIsNotAvailableAgainUntilTheCooldownPasses() {
        val s = sim()
        s.doIt()
        assertFalse(s.notStarted(), "still cooling down")
        s.run(EventCommand.StartQuest(9))
        assertEquals(QuestStatus.COMPLETED, s.p().quests.getValue(9).status, "an early restart is refused")
        s.worldEpochTicks += 999L
        assertFalse(s.notStarted())
        s.worldEpochTicks += 1L
        assertTrue(s.notStarted(), "a full cooldown later it is offered again")
        s.run(EventCommand.StartQuest(9))
        assertEquals(QuestStatus.IN_PROGRESS, s.p().quests.getValue(9).status)
        assertEquals(listOf(0), s.p().quests.getValue(9).objectiveCounts)
    }

    @Test
    fun aRepeatableQuestWithoutACooldownKeepsTheOldBehaviour() {
        val s = sim(daily.copy(cooldownTicks = 0L))
        s.doIt()
        assertFalse(s.notStarted(), "completed stays completed for checks")
        s.run(EventCommand.StartQuest(9))
        assertEquals(QuestStatus.IN_PROGRESS, s.p().quests.getValue(9).status, "but it can still be restarted")
    }

    @Test
    fun aCooldownNeedsRepeatable() {
        val cat = com.morgana.domain.validation.ContentCatalog(quests = mapOf(1 to daily.copy(id = 1, repeatable = false), 2 to daily.copy(id = 2, cooldownTicks = -1L)))
        val issues = com.morgana.domain.validation.ContentValidator.validate(cat, MorganaClassic.definition).issues
        assertTrue(issues.any { it.pointer.contains("/cooldownTicks") && it.id == 1 }, issues.toString())
        assertTrue(issues.any { it.pointer.contains("/cooldownTicks") && it.id == 2 }, issues.toString())
    }
}
