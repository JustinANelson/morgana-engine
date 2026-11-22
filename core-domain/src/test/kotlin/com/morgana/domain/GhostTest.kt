package com.morgana.domain

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinition
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.GhostDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GhostTest {
    private val revive = SpellDefinition(id = 1, name = "Revive", targetType = SpellTargetType.SELF, rangeTiles = 4, revivePercent = 50, cooldownTicks = 1)

    private fun sim(ghost: GhostDefinition?): WorldSimulation {
        val definition: GameDefinition = MorganaClassic.definition.copy(ghost = ghost)
        val s = WorldSimulation(SeededRandomSource(5L), game = CompiledGame.compile(definition))
        s.registerMap(MapDefinition(id = 1, name = "Yard", width = 20, height = 20))
        s.spells[1] = revive
        s.spawnOrUpdatePlayer(CharacterState(id = "dead", accountId = "a", name = "Dead", position = WorldPosition.fromTile(1, 10, 10, Direction.DOWN)))
        s.spawnOrUpdatePlayer(
            CharacterState(id = "healer", accountId = "b", name = "Healer", position = WorldPosition.fromTile(1, 12, 10, Direction.LEFT), knownSpellIds = listOf(1))
        )
        return s
    }

    private fun WorldSimulation.kill(id: String): List<WorldSimulationEvent> {
        val p = players.getValue(id)
        players[id] = p.copy(vitals = p.vitals + ("hp" to VitalState(0, p.vitals.getValue("hp").max)))
        return (1..2).flatMap { stepTick() }
    }

    private fun WorldSimulation.p(id: String) = players.getValue(id)

    @Test
    fun `a death with the ghost section leaves a ghost where they fell and keeps them dead`() {
        val s = sim(GhostDefinition())
        val events = s.kill("dead")
        val ghost = s.p("dead")
        assertTrue(ghost.isGhost)
        assertEquals(0, ghost.vitals.getValue("hp").cur)
        assertEquals(10, ghost.position.tileX, "the ghost stays where it fell")
        assertTrue(events.any { it is WorldSimulationEvent.PlayerDied })
        repeat(5) { s.stepTick() }
        assertTrue(s.p("dead").isGhost && s.p("dead").vitals.getValue("hp").cur == 0, "no regeneration and no second death")
    }

    @Test
    fun `without the section a death still respawns at once`() {
        val s = sim(null)
        s.kill("dead")
        assertFalse(s.p("dead").isGhost)
        assertTrue(s.p("dead").vitals.getValue("hp").cur > 0)
    }

    @Test
    fun `a ghost can walk but cannot fight or be fought`() {
        val s = sim(GhostDefinition())
        s.kill("dead")
        repeat(30) { s.stepTick() }
        val before = s.p("dead").position
        s.movePlayer("dead", Direction.LEFT)
        assertNotEquals(before.tileX, s.p("dead").position.tileX, "ghosts move")
        assertTrue(s.playerAttack("dead").isEmpty())
        assertTrue(s.playerCastSpell("dead", 1).isEmpty())
        assertTrue(s.initiateTrade("dead", "healer").isEmpty())
    }

    @Test
    fun `a revive spell brings the ghost back where it stands with the spell's share of life`() {
        val s = sim(GhostDefinition())
        s.kill("dead")
        assertTrue(s.playerCastSpell("healer", 1).isNotEmpty())
        val back = s.p("dead")
        assertFalse(back.isGhost)
        assertEquals(back.vitals.getValue("hp").max / 2, back.vitals.getValue("hp").cur)
        assertEquals(10, back.position.tileX)
    }

    @Test
    fun `revive needs a ghost in range and never heals the living`() {
        val s = sim(GhostDefinition())
        val events = s.playerCastSpell("healer", 1)
        assertTrue(events.any { it is WorldSimulationEvent.ChatEmitted }, "told there is nobody to revive")
        s.kill("dead")
        s.players["dead"] = s.p("dead").copy(position = WorldPosition.fromTile(1, 1, 1, Direction.DOWN))
        s.stepTick()
        s.playerCastSpell("healer", 1)
        assertTrue(s.p("dead").isGhost, "out of range")
    }

    @Test
    fun `release sends a ghost home with full life and the timer does it automatically`() {
        val s = sim(GhostDefinition(autoReleaseTicks = 40))
        s.kill("dead")
        s.executeAdminSlashCommand("dead", "/release")
        val home = s.p("dead")
        assertFalse(home.isGhost)
        assertEquals(home.vitals.getValue("hp").max, home.vitals.getValue("hp").cur)
        assertTrue(s.executeAdminSlashCommand("healer", "/release").isEmpty(), "the living cannot release")

        val t = sim(GhostDefinition(autoReleaseTicks = 40))
        t.kill("dead")
        repeat(30) { t.stepTick() }
        assertTrue(t.p("dead").isGhost)
        repeat(20) { t.stepTick() }
        assertFalse(t.p("dead").isGhost, "released after the wait")

        val never = sim(GhostDefinition(autoReleaseTicks = 0))
        never.kill("dead")
        repeat(300) { never.stepTick() }
        assertTrue(never.p("dead").isGhost, "0 means they must /release")
    }

    @Test
    fun `a character who logs in as a ghost wakes at home`() {
        val s = sim(GhostDefinition())
        s.kill("dead")
        val saved = s.p("dead")
        s.removePlayer("dead")
        s.spawnOrUpdatePlayer(saved)
        assertFalse(s.p("dead").isGhost)
        assertTrue(s.p("dead").vitals.getValue("hp").cur > 0)
    }

    @Test
    fun `the ghost section is validated`() {
        val classic = MorganaClassic.definition
        assertTrue(GameDefinitionValidator.validate(classic.copy(ghost = GhostDefinition())).isEmpty())
        assertFalse(GameDefinitionValidator.validate(classic.copy(ghost = GhostDefinition(autoReleaseTicks = -1))).isEmpty())
    }

    @Test
    fun `a wiped party on a generated floor is sent home, but a floor with a survivor is left alone`() {
        fun run(withSurvivor: Boolean): WorldSimulation {
            val s = sim(GhostDefinition(wipeEndsRun = true, autoReleaseTicks = 0))
            s.registerMap(MapDefinition(id = 2, name = "Floor", width = 12, height = 12, instanced = true, generator = com.morgana.domain.map.FloorGenerator(exitMapId = 1, exitX = 3, exitY = 3)))
            for (id in listOf("dead", "healer")) s.players[id] = s.p(id).copy(partyId = "p1") // one party, so one shared floor
            s.applyScriptEffect(com.morgana.domain.script.ScriptEffect.WarpPlayer("dead", 2, 0, 0))
            s.applyScriptEffect(com.morgana.domain.script.ScriptEffect.WarpPlayer("healer", 2, 0, 0))
            assertTrue(com.morgana.domain.sim.MapInstances.isInstanceId(s.p("dead").position.mapId))
            s.kill("dead")
            if (!withSurvivor) s.kill("healer")
            repeat(3) { s.stepTick() }
            return s
        }
        val wiped = run(withSurvivor = false)
        assertTrue(!wiped.p("dead").isGhost && wiped.p("dead").position.mapId == 1, "the fallen woke at home")
        assertTrue(!wiped.p("healer").isGhost && wiped.p("healer").position.mapId == 1)

        val survivor = run(withSurvivor = true)
        assertTrue(survivor.p("dead").isGhost, "a living party mate could still revive them")
        assertTrue(com.morgana.domain.sim.MapInstances.isInstanceId(survivor.p("dead").position.mapId))
    }

    @Test
    fun `without the wipe rule a fallen party waits on the floor`() {
        val s = sim(GhostDefinition(autoReleaseTicks = 0))
        s.registerMap(MapDefinition(id = 2, name = "Floor", width = 12, height = 12, instanced = true, generator = com.morgana.domain.map.FloorGenerator(exitMapId = 1, exitX = 3, exitY = 3)))
        s.applyScriptEffect(com.morgana.domain.script.ScriptEffect.WarpPlayer("dead", 2, 0, 0))
        s.kill("dead")
        repeat(3) { s.stepTick() }
        assertTrue(s.p("dead").isGhost)
    }
}
