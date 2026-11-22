package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.DurabilityDefinition
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.GhostDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.RestedDefinition
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemStack
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.withRestedXp
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeathPenaltyAndRestedTest {
    private fun sim(
        ghost: GhostDefinition? = GhostDefinition(deathWearPoints = 5, selfReviveAfterTicks = 100, selfRevivePercent = 30),
        rested: RestedDefinition? = null,
        character: CharacterState = CharacterState(id = "p", accountId = "a", name = "P", position = WorldPosition.fromTile(1, 10, 10, Direction.DOWN))
    ): WorldSimulation {
        val definition = MorganaClassic.definition.copy(ghost = ghost, rested = rested, durability = DurabilityDefinition())
        val s = WorldSimulation(SeededRandomSource(3L), game = CompiledGame.compile(definition))
        s.registerMap(MapDefinition(id = 1, name = "Yard", width = 20, height = 20))
        s.spawnOrUpdatePlayer(character)
        return s
    }

    private fun WorldSimulation.kill(id: String = "p") {
        val p = players.getValue(id)
        players[id] = p.copy(vitals = p.vitals + ("hp" to VitalState(0, p.vitals.getValue("hp").max)))
        repeat(2) { stepTick() }
    }

    @Test
    fun `dying wears worn gear by the configured amount and says so`() {
        val geared = CharacterState(
            id = "p", accountId = "a", name = "P", position = WorldPosition.fromTile(1, 10, 10, Direction.DOWN),
            equipment = EquipmentLoadout(mapOf(EquipmentSlot.ARMOR to ItemStack(itemId = 1, amount = 1, durability = 80)))
        )
        val s = sim(character = geared)
        s.players["p"] = s.players.getValue("p").copy(vitals = mapOf("hp" to VitalState(0, 50)))
        val events = (1..2).flatMap { s.stepTick() }
        assertEquals(75, s.players.getValue("p").equipment.getEquipped(EquipmentSlot.ARMOR)!!.durability)
        val notice = events.filterIsInstance<WorldSimulationEvent.ChatEmitted>().first { "have died" in it.message }.message
        assertTrue("lost 5 durability" in notice && "/revive" in notice, notice)
    }

    @Test
    fun `gear never goes below zero and no cost means no wear`() {
        val worn = CharacterState(
            id = "p", accountId = "a", name = "P", position = WorldPosition.fromTile(1, 10, 10, Direction.DOWN),
            equipment = EquipmentLoadout(mapOf(EquipmentSlot.ARMOR to ItemStack(itemId = 1, amount = 1, durability = 3)))
        )
        val s = sim(character = worn)
        s.kill()
        assertEquals(0, s.players.getValue("p").equipment.getEquipped(EquipmentSlot.ARMOR)!!.durability)
        val free = sim(ghost = GhostDefinition(), character = worn)
        free.kill()
        assertEquals(3, free.players.getValue("p").equipment.getEquipped(EquipmentSlot.ARMOR)!!.durability)
    }

    @Test
    fun `a ghost can get up where it fell only after the wait`() {
        val s = sim()
        s.kill()
        s.executeAdminSlashCommand("p", "/revive")
        assertTrue(s.players.getValue("p").isGhost, "too early")
        repeat(100) { s.stepTick() }
        s.executeAdminSlashCommand("p", "/revive")
        val back = s.players.getValue("p")
        assertFalse(back.isGhost)
        assertEquals(back.vitals.getValue("hp").max * 30 / 100, back.vitals.getValue("hp").cur)
        assertEquals(10, back.position.tileX)
    }

    @Test
    fun `revive is off unless the game sets a wait`() {
        val s = sim(ghost = GhostDefinition())
        s.kill()
        repeat(200) { s.stepTick() }
        s.executeAdminSlashCommand("p", "/revive")
        assertTrue(s.players.getValue("p").isGhost)
    }

    @Test
    fun `time away fills the rested pool up to the cap`() {
        val cfg = RestedDefinition(poolPercentPerOfflineHour = 10, poolCapPercent = 150, bonusPercent = 100)
        val away = CharacterState(id = "p", accountId = "a", name = "P", level = 10, lastSeenWorldTick = 1L, position = WorldPosition.fromTile(1, 5, 5, Direction.DOWN))
        val s = sim(rested = cfg, character = away.copy())
        // sim() spawned at epoch 0 so nothing accrued yet; respawn after five hours away
        s.removePlayer("p")
        s.worldEpochTicks = 5 * 72_000L + 1
        s.spawnOrUpdatePlayer(away)
        val needed = s.game.xpToNext(away)!!
        assertEquals(needed / 2, s.players.getValue("p").restedXp)
        s.removePlayer("p")
        s.worldEpochTicks = 500 * 72_000L
        s.spawnOrUpdatePlayer(away)
        assertEquals(needed * 150 / 100, s.players.getValue("p").restedXp, "capped")
    }

    @Test
    fun `rested pool pays a bonus on kills and is spent by it`() {
        val s = sim(rested = RestedDefinition(bonusPercent = 100), character = CharacterState(id = "p", accountId = "a", name = "P", level = 10, restedXp = 150L, position = WorldPosition.fromTile(1, 5, 5, Direction.DOWN)))
        val out = mutableListOf<WorldSimulationEvent>()
        assertEquals(200L, s.withRestedXp("p", 100L, out))
        assertEquals(50L, s.players.getValue("p").restedXp)
        assertEquals(150L, s.withRestedXp("p", 100L, out), "only what is left")
        assertEquals(0L, s.players.getValue("p").restedXp)
        assertEquals(100L, s.withRestedXp("p", 100L, out))
    }

    @Test
    fun `catch-up pays extra below the threshold only and rested needs the section`() {
        val low = CharacterState(id = "p", accountId = "a", name = "P", level = 10, position = WorldPosition.fromTile(1, 5, 5, Direction.DOWN))
        val s = sim(rested = RestedDefinition(catchUpBelowLevel = 20, catchUpPercent = 25), character = low)
        val out = mutableListOf<WorldSimulationEvent>()
        assertEquals(125L, s.withRestedXp("p", 100L, out))
        s.players["p"] = s.players.getValue("p").copy(level = 20)
        assertEquals(100L, s.withRestedXp("p", 100L, out))
        val none = sim(rested = null, character = low.copy(restedXp = 999L))
        assertEquals(100L, none.withRestedXp("p", 100L, out))
    }

    @Test
    fun `the validator rejects out of range settings`() {
        val bad = MorganaClassic.definition.copy(ghost = GhostDefinition(selfRevivePercent = 0), rested = RestedDefinition(catchUpBelowLevel = 9999))
        val errors = GameDefinitionValidator.validate(bad).map { it.toString() }
        assertTrue(errors.any { "selfRevivePercent" in it } && errors.any { "catchUpBelowLevel" in it }, errors.toString())
    }
}
