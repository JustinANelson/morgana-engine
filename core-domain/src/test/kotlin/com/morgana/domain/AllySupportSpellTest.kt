package com.morgana.domain

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AllySupportSpellTest {
    private fun hero(id: String, x: Int, party: String?, sim: WorldSimulation) = CharacterState(
        id = id, accountId = "a-$id", name = id,
        attributes = mapOf("str" to 10, "def" to 10, "int" to 20, "agi" to 10, "vit" to 10),
        position = WorldPosition.fromTile(1, x, 3, Direction.DOWN),
        knownSpellIds = listOf(5),
        partyId = party
    ).recalculateVitals(sim.game, sim.items, refill = true)

    private fun world(allyParty: String?, allyX: Int = 4, allyTargetable: Boolean = true): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(7L), game = MorganaClassic.COMPILED)
        sim.registerMap(MapDefinition(id = 1, name = "Room", width = 12, height = 8))
        sim.spells[5] = SpellDefinition(
            id = 5, name = "Cure", targetType = SpellTargetType.SELF, isHealing = true,
            basePower = 30, rangeTiles = 3, allyTargetable = allyTargetable
        )
        sim.spawnOrUpdatePlayer(hero("healer", 3, "p1", sim))
        sim.spawnOrUpdatePlayer(hero("ally", allyX, allyParty, sim))
        sim.stepTick()
        return sim
    }

    private fun wound(sim: WorldSimulation, id: String): Int {
        val p = sim.players.getValue(id)
        val life = sim.game.lifeVitalId
        val v = p.vitals.getValue(life)
        sim.players[id] = p.copy(vitals = p.vitals + (life to v.copy(cur = 1)))
        return v.max
    }

    private fun life(sim: WorldSimulation, id: String) = sim.players.getValue(id).vitals.getValue(sim.game.lifeVitalId).cur

    @Test
    fun `cure heals the most wounded party ally in range instead of the caster`() {
        val sim = world(allyParty = "p1")
        wound(sim, "ally")
        sim.playerCastSpell("healer", 5)
        assertTrue(life(sim, "ally") > 1, "ally was healed")
    }

    @Test
    fun `an explicit ally target wins over a more wounded one`() {
        val sim = world(allyParty = "p1")
        wound(sim, "ally")
        wound(sim, "healer")
        sim.playerCastSpell("healer", 5, targetEntityId = "healer")
        assertEquals(1, life(sim, "ally"))
        assertTrue(life(sim, "healer") > 1)
    }

    @Test
    fun `strangers and out of range allies are not healed`() {
        val stranger = world(allyParty = null)
        wound(stranger, "ally"); wound(stranger, "healer")
        stranger.playerCastSpell("healer", 5)
        assertEquals(1, life(stranger, "ally"))
        assertTrue(life(stranger, "healer") > 1)

        val far = world(allyParty = "p1", allyX = 10)
        wound(far, "ally"); wound(far, "healer")
        far.playerCastSpell("healer", 5)
        assertEquals(1, life(far, "ally"))
    }

    @Test
    fun `spells without allyTargetable stay self only`() {
        val sim = world(allyParty = "p1", allyTargetable = false)
        wound(sim, "ally"); wound(sim, "healer")
        sim.playerCastSpell("healer", 5)
        assertEquals(1, life(sim, "ally"))
        assertTrue(life(sim, "healer") > 1)
    }
}
