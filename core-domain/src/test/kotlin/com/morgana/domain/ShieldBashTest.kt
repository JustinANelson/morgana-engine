package com.morgana.domain

import com.morgana.domain.combat.ActionCombatModule
import com.morgana.domain.content.ActionSkillDefinition
import com.morgana.domain.content.ComboEntry
import com.morgana.domain.content.HitboxShape
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SkillHit
import com.morgana.domain.content.SkillHitbox
import com.morgana.domain.content.SkillMove
import com.morgana.domain.content.WeaponSetDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A rush that hits an enemy and everything near it and pushes them all away, and fast moves that cannot skip walls. */
class ShieldBashTest {
    private val dummy = NpcDefinition(id = 9, name = "Dummy", behavior = NpcBehavior.PASSIVE, level = 1, vitals = mapOf("hp" to 1_000_000), attackDamage = 0, aggroRangeTiles = 0)
    private val bash = ActionSkillDefinition(
        id = 1, name = "Bash", startupTicks = 3, activeTicks = 4, recoveryTicks = 4,
        moves = listOf(SkillMove(1, 3, forwardPixels = 140)),
        hitboxes = listOf(SkillHitbox(4, 5, HitboxShape.CIRCLE, forwardPixels = 24, radiusPixels = 72, hit = SkillHit(damagePercent = 150, knockbackPixels = 96, knockbackRadial = true)))
    )

    private fun world(spawns: List<Pair<Int, Int>>, blockTileX: Int? = null): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(8L), game = MorganaClassic.COMPILED)
        sim.modules = ModuleFlags.ALL_ENABLED
        sim.combat = ActionCombatModule()
        sim.npcs[9] = dummy
        sim.actionSkills[1] = bash
        sim.weaponSets[1] = WeaponSetDefinition(id = 1, name = "Set", skillIds = listOf(1), entries = listOf(ComboEntry("TAB", 1)))
        val map = MapDefinition(id = 1, name = "Arena", width = 24, height = 16, npcSpawns = spawns.mapIndexed { i, s -> MapNpcSpawnSlot(i, 9, s.first, s.second) })
        sim.registerMap(map)
        sim.spawnOrUpdatePlayer(CharacterState(id = "ann", accountId = "a", name = "Ann", level = 10, position = WorldPosition.fromTile(1, 4, 5, Direction.RIGHT)))
        return sim
    }

    private fun WorldSimulation.run() {
        handleAction("ann", "input", mapOf("token" to "TAB", "aimX" to (14 * 32 + 16).toString(), "aimY" to (5 * 32 + 16).toString()))
        repeat(16) { stepTick() }
    }

    @Test
    fun `the rush stops at the first enemy and pushes it and its neighbours away from the player`() {
        val sim = world(listOf(7 to 5, 7 to 6, 8 to 4))
        val before = sim.mapNpcs.getValue(1).associate { it.spawnSlotIndex to (it.tileX to it.tileY) }
        sim.run()
        val after = sim.mapNpcs.getValue(1).associate { it.spawnSlotIndex to (it.tileX to it.tileY) }
        val player = sim.players.getValue("ann")
        assertTrue(player.position.tileX in 5..6, "the rush closed in on the enemy and stopped beside it: ${player.position.tileX}")
        for (slot in 0..2) {
            val (bx, by) = before.getValue(slot)
            val (ax, ay) = after.getValue(slot)
            val away = (ax - 4) * (ax - 4) + (ay - 5) * (ay - 5) > (bx - 4) * (bx - 4) + (by - 5) * (by - 5)
            assertTrue(away, "enemy $slot was pushed back: $bx,$by -> $ax,$ay")
        }
        assertTrue(sim.mapNpcs.getValue(1).all { it.vitals.getValue("hp").cur < 1_000_000 }, "all three were hit")
    }

    @Test
    fun `a fast rush does not pass through a thin wall`() {
        val sim = world(emptyList())
        // A wall of blocked tiles at x = 6 (the rush covers 140 px, more than four tiles, from x = 4).
        val map = sim.maps.getValue(1)
        val walled = map.cells.toMutableList()
        for (y in 0 until map.height) walled[y * map.width + 6] = walled[y * map.width + 6].copy(attribute = com.morgana.domain.map.TileAttribute.Blocked)
        sim.registerMap(map.copy(cells = walled))
        sim.run()
        assertTrue(sim.players.getValue("ann").position.tileX <= 5, "stopped at the wall: ${sim.players.getValue("ann").position.tileX}")
    }

    @Test
    fun `a leap with a minimum range is still a jump when the aim is on top of the character`() {
        val leap = ActionSkillDefinition(
            id = 1, name = "Leap", startupTicks = 1, activeTicks = 8, recoveryTicks = 2,
            moves = listOf(SkillMove(1, 4, toAim = true, maxPixels = 150, minPixels = 80))
        )
        val sim = world(emptyList())
        sim.actionSkills[1] = leap
        val startX = sim.players.getValue("ann").position.pixelX
        val me = sim.players.getValue("ann").position
        sim.handleAction("ann", "input", mapOf("token" to "TAB", "aimX" to (me.pixelX + 16f).toString(), "aimY" to (me.pixelY + 16f).toString()))
        repeat(12) { sim.stepTick() }
        val moved = sim.players.getValue("ann").position.pixelX - startX
        assertTrue(moved in 70f..90f, "it still jumped about its minimum, facing right: $moved")
    }
}
