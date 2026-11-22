package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.ActiveStatusEffect
import com.morgana.domain.stats.StatusEffectType
import com.morgana.domain.stats.VitalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A SLOW status stretches a monster's walking time by its magnitude in percent. */
class SlowStatusTest {
    private val wolf = NpcDefinition(
        id = 1, name = "Wolf", behavior = NpcBehavior.ATTACK_ON_SIGHT, vitals = mapOf("hp" to 1000), attributes = mapOf("str" to 1, "agi" to 1, "vit" to 1),
        moveSpeedTicks = 10, aggroRangeTiles = 40
    )

    /** How many tiles the wolf covers toward a distant hero in [ticks] ticks, with an optional SLOW of [slow] percent. */
    private fun distanceWalked(ticks: Int, slow: Int): Int {
        val s = WorldSimulation(SeededRandomSource(1L), game = CompiledGame.compile(MorganaClassic.definition))
        s.registerMap(MapDefinition(id = 1, name = "Field", width = 60, height = 5))
        s.npcs[1] = wolf
        s.spawnOrUpdatePlayer(CharacterState(id = "p1", accountId = "a", name = "P", position = WorldPosition.fromTile(1, 16, 2, Direction.LEFT)))
        val effects = if (slow > 0) listOf(ActiveStatusEffect("spell-1", StatusEffectType.SLOW, "p1", magnitude = slow, remainingTicks = 100_000)) else emptyList()
        s.mapNpcs[1] = mutableListOf(
            MapNpcInstance("wolf", 0, 1, 1, 2, 2, 2, 2, vitals = mapOf("hp" to VitalState(1000, 1000)), statusEffects = effects)
        )
        repeat(ticks) { s.stepTick() }
        return s.mapNpcs.getValue(1).first().tileX - 2
    }

    @Test
    fun `a slowed monster walks slower in proportion to the slow`() {
        val normal = distanceWalked(100, slow = 0)
        val halfSpeed = distanceWalked(100, slow = 100)
        assertTrue(normal >= 8, "the wolf walks a good way in 100 ticks (got $normal)")
        assertTrue(halfSpeed in (normal / 2 - 2)..(normal / 2 + 2), "a 100% slow halves the walk: $halfSpeed against $normal")
        assertEquals(normal, distanceWalked(100, slow = 0), "no slow is deterministic")
    }
}
