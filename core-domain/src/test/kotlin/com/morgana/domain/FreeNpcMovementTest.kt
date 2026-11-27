package com.morgana.domain

import com.morgana.domain.combat.ActionCombatModule
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Monsters walk in free pixel steps when the game has free movement, and tile by tile when it does not. */
class FreeNpcMovementTest {
    private val wolf = NpcDefinition(id = 9, name = "Wolf", behavior = NpcBehavior.ATTACK_ON_SIGHT, level = 1, vitals = mapOf("hp" to 100), attackDamage = 1, aggroRangeTiles = 30, leashRangeTiles = 80, moveSpeedTicks = 10)

    private fun world(free: Boolean): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(3L), game = MorganaClassic.COMPILED)
        sim.modules = if (free) ModuleFlags.ALL_ENABLED else ModuleFlags(disabled = setOf(OptionalModule.SUB_TILE_MOVEMENT))
        sim.combat = ActionCombatModule()
        sim.npcs[9] = wolf
        sim.registerMap(MapDefinition(id = 1, name = "Field", width = 24, height = 16, npcSpawns = listOf(MapNpcSpawnSlot(0, 9, 3, 3))))
        sim.spawnOrUpdatePlayer(CharacterState(id = "ann", accountId = "a", name = "Ann", level = 10, position = WorldPosition.fromTile(1, 12, 9, Direction.LEFT)))
        return sim
    }

    private fun WorldSimulation.wolf() = mapNpcs.getValue(1).single()

    @Test
    fun `a chasing monster moves in pixel steps along a straight line, not a staircase`() {
        val sim = world(free = true)
        repeat(30) { sim.stepTick() }
        val w = sim.wolf()
        assertTrue(w.subPixel)
        val ts = 32f
        assertTrue(w.pixelX % ts != 0f || w.pixelY % ts != 0f, "it is between tiles")
        assertTrue(w.pixelX > 3 * ts && w.pixelY > 3 * ts, "it has closed in on both axes at once, a diagonal path")
        // On the straight line to the player the offsets stay in proportion (9 tiles across, 6 down).
        val across = (w.pixelX - 3 * ts)
        val down = (w.pixelY - 3 * ts)
        assertTrue(kotlin.math.abs(across / down - 9f / 6f) < 0.3f, "across $across down $down")
    }

    @Test
    fun `without free movement a monster still steps tile by tile`() {
        val sim = world(free = false)
        repeat(30) { sim.stepTick() }
        val w = sim.wolf()
        assertEquals(false, w.subPixel)
        assertTrue(w.tileX > 3 || w.tileY > 3)
    }

    @Test
    fun `a monster reaches its target and stops beside it instead of walking through`() {
        val sim = world(free = true)
        repeat(400) { sim.stepTick() }
        val w = sim.wolf()
        val dist = kotlin.math.hypot((w.tileX - 12).toDouble(), (w.tileY - 9).toDouble())
        assertTrue(dist >= 1.0 && dist < 3.0, "beside the player: $dist")
    }
}
