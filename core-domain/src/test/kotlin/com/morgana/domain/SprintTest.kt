package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.SprintDefinition
import com.morgana.domain.game.VitalDefinition
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.MovementRules
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.MovementMode
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SprintTest {
    private val stamina = VitalDefinition("stamina", "Stamina", "STM", max = "100")

    private fun sim(sprint: SprintDefinition?): WorldSimulation {
        val definition = MorganaClassic.definition.copy(vitals = MorganaClassic.definition.vitals + stamina, sprint = sprint)
        val s = WorldSimulation(SeededRandomSource(3L), game = CompiledGame.compile(definition))
        s.registerMap(MapDefinition(id = 1, name = "Field", width = 60, height = 20))
        s.spawnOrUpdatePlayer(CharacterState(id = "p", accountId = "a", name = "P", position = WorldPosition.fromTile(1, 3, 10, Direction.RIGHT)))
        s.stepTick()
        return s
    }

    private fun WorldSimulation.stamina() = players.getValue("p").vitals["stamina"]?.cur ?: -1
    private fun WorldSimulation.x() = players.getValue("p").position.pixelX

    private fun WorldSimulation.step(px: Float): Float {
        stepTick()
        val before = x()
        movePlayer("p", Direction.RIGHT, MovementMode.SUB_TILE, deltaPixelsX = px)
        return x() - before
    }

    @Test
    fun aLongStepIsKeptWhileStaminaLasts() {
        val s = sim(SprintDefinition(speedPercent = 150, stepsPerStamina = 1))
        assertEquals(12f, s.step(12f), 0.01f)
    }

    @Test
    fun aNormalStepIsNotASprintAndCostsNothing() {
        val s = sim(SprintDefinition(speedPercent = 150, stepsPerStamina = 1))
        val before = s.stamina()
        assertEquals(8f, s.step(8f), 0.01f)
        assertEquals(before, s.stamina())
    }

    @Test
    fun sprintingCannotExceedTheConfiguredLength() {
        val s = sim(SprintDefinition(speedPercent = 150, stepsPerStamina = 1))
        assertEquals(12f, s.step(16f), 0.01f)
    }

    @Test
    fun sprintingDrainsStaminaAndStopsWhenItIsGone() {
        val s = sim(SprintDefinition(speedPercent = 150, stepsPerStamina = 1))
        val start = s.stamina()
        assertTrue(start > 0)
        s.step(12f)
        assertEquals(start - 1, s.stamina())
        s.players["p"] = s.players.getValue("p").let { it.copy(vitals = it.vitals + ("stamina" to VitalState(0, 100))) }
        assertEquals(8f, s.step(12f), 0.01f)
        assertEquals(0, s.stamina())
    }

    @Test
    fun stepsPerStaminaSpreadsTheCost() {
        val s = sim(SprintDefinition(speedPercent = 150, stepsPerStamina = 2))
        val start = s.stamina()
        repeat(10) { s.step(12f) }
        val spent = start - s.stamina()
        assertTrue(spent in 4..6, "spent $spent over 10 sprint steps")
    }

    @Test
    fun withoutSprintRulesTheOldLimitStands() {
        val s = sim(null)
        assertEquals(MovementRules.SUB_TILE_MAX_STEP_PIXELS, s.step(100f), 0.01f)
    }

    private fun WorldSimulation.mount(percent: Int) {
        items[7] = ItemDefinition(id = 7, name = "Pony", type = ItemType.ARMOR, equipSlot = "MOUNT", mountSpeedPercent = percent)
        players["p"] = players.getValue("p").copy(equipment = EquipmentLoadout(mapOf(EquipmentSlot("MOUNT") to ItemStack(7))))
    }

    @Test
    fun aWornMountLengthensTheNormalStepWithoutCostingStamina() {
        val s = sim(SprintDefinition(speedPercent = 150, stepsPerStamina = 1))
        s.mount(125)
        val before = s.stamina()
        assertEquals(10f, s.step(10f), 0.01f)
        assertEquals(before, s.stamina(), "a mounted normal step is free")
    }

    @Test
    fun sprintingOnAMountStacksOnTheMountStepAndStillCostsStamina() {
        val s = sim(SprintDefinition(speedPercent = 150, stepsPerStamina = 1))
        s.mount(120)
        val before = s.stamina()
        assertEquals(14.4f, s.step(100f), 0.01f)
        assertEquals(before - 1, s.stamina())
    }

    @Test
    fun aMountCannotBeFakedByAnUnmountedClient() {
        val s = sim(SprintDefinition(speedPercent = 150, stepsPerStamina = 1))
        s.players["p"] = s.players.getValue("p").copy(vitals = s.players.getValue("p").vitals + ("stamina" to VitalState(0, 100)))
        assertEquals(8f, s.step(10f), 0.01f)
    }

    @Test
    fun validatorChecksTheRules() {
        val bad = MorganaClassic.definition.copy(sprint = SprintDefinition(speedPercent = 300, stepsPerStamina = 0, staminaVitalId = "nope"))
        val problems = GameDefinitionValidator.validate(bad).map { it.toString() }
        assertTrue(problems.any { "/sprint/speedPercent" in it }, problems.toString())
        assertTrue(problems.any { "/sprint/stepsPerStamina" in it }, problems.toString())
        assertTrue(problems.any { "/sprint/staminaVitalId" in it }, problems.toString())
    }
}
