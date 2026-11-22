package com.morgana.domain

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spells restricted to certain classes (`SpellDefinition.classReqIds`): refused with a notice, and forgotten on login. */
class SpellClassRestrictionTest {
    private fun sim(): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(1L), game = MorganaClassic.COMPILED)
        sim.spells[7] = SpellDefinition(id = 7, name = "Fireball", targetType = SpellTargetType.SINGLE_TARGET, classReqIds = setOf(5))
        sim.registerMap(MapDefinition(id = 1, name = "Field", width = 10, height = 10))
        return sim
    }

    private fun character(sim: WorldSimulation, classId: Int, known: List<Int>): CharacterState = CharacterState(
        id = "hero", accountId = "a", name = "Hero", classId = classId, knownSpellIds = known,
        attributes = mapOf("str" to 10, "def" to 10, "int" to 10, "agi" to 10, "vit" to 10),
        position = WorldPosition.fromTile(1, 3, 3, Direction.DOWN)
    ).recalculateVitals(sim.game, sim.items, refill = true)

    @Test
    fun `casting another class's spell is refused with a notice`() {
        val sim = sim()
        sim.spawnOrUpdatePlayer(character(sim, classId = 1, known = emptyList()))
        // Known spells for the wrong class are dropped at login, so give the spell after spawning to test the cast guard itself.
        sim.players["hero"] = sim.players["hero"]!!.copy(knownSpellIds = listOf(7))
        val events = sim.playerCastSpell("hero", 7)
        assertTrue(events.any { it is WorldSimulationEvent.ChatEmitted && it.message.contains("Fireball") }, "the player is told why")
        assertFalse(events.any { it is WorldSimulationEvent.SpellCast })
    }

    @Test
    fun `the right class casts it`() {
        val sim = sim()
        sim.spawnOrUpdatePlayer(character(sim, classId = 5, known = listOf(7)))
        assertTrue(sim.playerCastSpell("hero", 7).any { it is WorldSimulationEvent.SpellCast })
    }

    @Test
    fun `a spell restricted after the character learned it is forgotten at login`() {
        val sim = sim()
        sim.spawnOrUpdatePlayer(character(sim, classId = 1, known = listOf(7)))
        assertEquals(emptyList<Int>(), sim.players["hero"]!!.knownSpellIds)
    }
}
