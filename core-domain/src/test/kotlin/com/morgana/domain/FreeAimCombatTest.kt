package com.morgana.domain

import com.morgana.domain.combat.ActionCombatModule
import com.morgana.domain.content.ActionSkillDefinition
import com.morgana.domain.content.ComboEntry
import com.morgana.domain.content.HitboxShape
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SkillHit
import com.morgana.domain.content.SkillHitbox
import com.morgana.domain.content.SkillProjectile
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Projectiles that fly at any angle and area hitboxes that land where they were aimed, in action combat. */
class FreeAimCombatTest {
    private val dummy = NpcDefinition(id = 9, name = "Dummy", behavior = NpcBehavior.PASSIVE, level = 1, vitals = mapOf("hp" to 100_000), attackDamage = 0, aggroRangeTiles = 0)

    private fun shot(free: Boolean) = ActionSkillDefinition(
        id = 7, name = "Shot", startupTicks = 1, activeTicks = 2, recoveryTicks = 2,
        hitboxes = listOf(SkillHitbox(1, 1, HitboxShape.CIRCLE, radiusPixels = 8, hit = SkillHit(damagePercent = 100), projectile = SkillProjectile(speedPixelsPerTick = 10, rangePixels = 320, freeAngle = free)))
    )

    private fun burst(maxRange: Int) = ActionSkillDefinition(
        id = 8, name = "Burst", startupTicks = 1, activeTicks = 2, recoveryTicks = 2,
        hitboxes = listOf(SkillHitbox(1, 2, HitboxShape.CIRCLE, radiusPixels = 40, hit = SkillHit(damagePercent = 100), groundTarget = true, maxRangePixels = maxRange))
    )

    private fun world(skill: ActionSkillDefinition, dummyAt: Pair<Int, Int>): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(5L), game = MorganaClassic.COMPILED)
        sim.modules = ModuleFlags.ALL_ENABLED
        sim.combat = ActionCombatModule()
        sim.npcs[9] = dummy
        sim.actionSkills[skill.id] = skill
        sim.weaponSets[1] = WeaponSetDefinition(id = 1, name = "Set", skillIds = listOf(skill.id), entries = listOf(ComboEntry("RMB", skill.id)))
        sim.registerMap(MapDefinition(id = 1, name = "Arena", width = 24, height = 16, npcSpawns = listOf(MapNpcSpawnSlot(0, 9, dummyAt.first, dummyAt.second))))
        sim.spawnOrUpdatePlayer(CharacterState(id = "ann", accountId = "a", name = "Ann", level = 10, position = WorldPosition.fromTile(1, 4, 5, Direction.RIGHT)))
        return sim
    }

    private fun WorldSimulation.dummyHp() = mapNpcs.getValue(1).single().vitals.getValue("hp").cur

    private fun WorldSimulation.fire(aimTileX: Int?, aimTileY: Int?) {
        val params = buildMap {
            put("token", "RMB")
            if (aimTileX != null && aimTileY != null) {
                put("aimX", (aimTileX * 32 + 16).toString())
                put("aimY", (aimTileY * 32 + 16).toString())
            }
        }
        handleAction("ann", "input", params)
        repeat(40) { stepTick() }
    }

    @Test
    fun `a free-angle shot hits a target that is nowhere near one of the eight directions`() {
        // 7 tiles across and 3 down from the player: about 23 degrees, between the right and down-right lines.
        val sim = world(shot(free = true), 11 to 8)
        sim.fire(11, 8)
        assertTrue(sim.dummyHp() < 100_000, "the shot flew along the aim and hit")
    }

    @Test
    fun `the same shot along the eight tile directions misses that target`() {
        val sim = world(shot(free = false), 11 to 8)
        sim.fire(11, 8)
        assertEquals(100_000, sim.dummyHp())
    }

    @Test
    fun `a free-angle shot stops at its range`() {
        val sim = world(shot(free = true), 20 to 5)
        sim.fire(20, 5) // 16 tiles away, past the 320 px range
        assertEquals(100_000, sim.dummyHp())
    }

    @Test
    fun `a ground-targeted burst lands on the aimed point, far from the character`() {
        val sim = world(burst(maxRange = 300), 12 to 5)
        sim.fire(12, 5)
        assertTrue(sim.dummyHp() < 100_000)
    }

    @Test
    fun `a ground-targeted burst with no aim point sits in front of the character and misses a far target`() {
        val sim = world(burst(maxRange = 300), 12 to 5)
        sim.fire(null, null)
        assertEquals(100_000, sim.dummyHp())
    }

    @Test
    fun `a ground-targeted burst is held within its range of the character`() {
        val sim = world(burst(maxRange = 96), 12 to 5) // the aimed point is 8 tiles away; the burst lands 3 tiles out
        sim.fire(12, 5)
        assertEquals(100_000, sim.dummyHp())
    }
}
