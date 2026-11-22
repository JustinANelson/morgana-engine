package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.BestiaryDefinition
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.applyDamageToNpc
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Every kill is counted per monster kind when the game has a bestiary. */
class BestiaryTest {
    private fun world(bestiary: BestiaryDefinition?): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(1L), game = CompiledGame.compile(MorganaClassic.definition.copy(bestiary = bestiary)))
        sim.npcs[10] = NpcDefinition(id = 10, name = "Rat", behavior = NpcBehavior.PASSIVE, vitals = mapOf("hp" to 5), xpReward = 1L)
        sim.registerMap(MapDefinition(id = 1, name = "Cellar", width = 20, height = 20, npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 10, spawnX = 8, spawnY = 8, respawnTicks = 1))))
        sim.spawnOrUpdatePlayer(
            CharacterState(id = "p", accountId = "a", name = "p", attributes = mapOf("str" to 10, "def" to 10, "agi" to 10, "vit" to 10), position = WorldPosition.fromTile(1, 8, 9, Direction.UP))
                .recalculateVitals(sim.game, sim.items, refill = true)
        )
        return sim
    }

    private fun WorldSimulation.kill() {
        val rat = mapNpcs.getValue(1).single()
        applyDamageToNpc(players.getValue("p"), rat, 1000, isCrit = false, out = mutableListOf())
    }

    @Test
    fun `each kill adds one to that kind's count`() {
        val sim = world(BestiaryDefinition())
        sim.kill()
        assertEquals(mapOf(10 to 1), sim.players.getValue("p").bestiary)
        repeat(3) { sim.stepTick() } // the rat comes back
        sim.kill()
        assertEquals(2, sim.players.getValue("p").bestiary.getValue(10))
    }

    @Test
    fun `a game without a bestiary keeps no count`() {
        val sim = world(null)
        sim.kill()
        assertTrue(sim.players.getValue("p").bestiary.isEmpty())
    }
}
