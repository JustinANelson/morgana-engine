package com.morgana.domain

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.grantXpToCharacter
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoticeAndCooldownEventsTest {
    private fun world(): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(7L), game = MorganaClassic.COMPILED)
        sim.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        val hero = CharacterState(
            id = "hero", accountId = "a", name = "Hero",
            attributes = mapOf("str" to 10, "def" to 10, "int" to 20, "agi" to 10, "vit" to 10),
            position = WorldPosition.fromTile(1, 3, 3, Direction.DOWN),
            knownSpellIds = listOf(5)
        ).recalculateVitals(sim.game, sim.items, refill = true)
        sim.spawnOrUpdatePlayer(hero)
        sim.stepTick()
        return sim
    }

    @Test
    fun `an instant spell tells the client its exact cooldown`() {
        val sim = world()
        sim.spells[5] = SpellDefinition(id = 5, name = "Mend", targetType = SpellTargetType.SELF, isHealing = true, basePower = 5, cooldownTicks = 30, castTimeTicks = 0)
        val events = sim.playerCastSpell("hero", 5)
        assertTrue(events.contains(WorldSimulationEvent.SpellCooldownStarted("hero", 5, 30)), "$events")
        assertTrue(sim.playerCastSpell("hero", 5).isEmpty(), "still cooling down, so the second cast is refused and sends nothing")
    }

    @Test
    fun `gaining a level sends a typed notice to that player only`() {
        val sim = world()
        val out = mutableListOf<WorldSimulationEvent>()
        sim.grantXpToCharacter("hero", 100_000L, out)
        val notice = out.filterIsInstance<WorldSimulationEvent.ChatEmitted>().single { it.notice == "LEVEL" }
        assertEquals("hero", notice.targetCharacterId)
        assertTrue(notice.message.startsWith("Level up! You are now level "), notice.message)
        assertEquals("SYSTEM", notice.channel)
    }
}
