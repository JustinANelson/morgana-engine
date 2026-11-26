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

    private fun WorldSimulation.fire(aimTileX: Int?, aimTileY: Int?, token: String = "RMB") {
        val params = buildMap {
            put("token", token)
            if (aimTileX != null && aimTileY != null) {
                put("aimX", (aimTileX * 32 + 16).toString())
                put("aimY", (aimTileY * 32 + 16).toString())
            }
        }
        handleAction("ann", "input", params)
        repeat(40) { stepTick() }
    }

    @Test
    fun `a non-finite or absurd aim is ignored so it cannot put NaN on a projectile`() {
        for (bad in listOf("NaN", "Infinity", "-Infinity", "1e30")) {
            val sim = world(shot(free = true), 11 to 8)
            sim.handleAction("ann", "input", mapOf("token" to "RMB", "aimX" to bad, "aimY" to bad))
            repeat(10) {
                sim.stepTick()
                sim.mapProjectiles.values.flatten().forEach { assertTrue(it.px.isFinite() && it.py.isFinite(), "projectile at ${it.px},${it.py} for aim $bad") }
            }
        }
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

    @Test
    fun `a press made while moving still fires the plain skill on that key`() {
        for (token in listOf("FORWARD+RMB", "BACK+RMB", "LEFT+RMB", "SHIFT+RIGHT+RMB")) {
            val sim = world(shot(free = true), 11 to 8)
            sim.fire(11, 8, token)
            assertTrue(sim.dummyHp() < 100_000, "$token fired the RMB shot")
        }
    }

    @Test
    fun `a directional move defined by the weapon set beats the plain fallback`() {
        assertEquals(listOf("BACK+SPACE", "SPACE"), com.morgana.domain.combat.ActionCombatRules.tokenFallbacks("BACK+SPACE"))
        assertEquals(listOf("SHIFT+BACK+LMB", "SHIFT+LMB", "LMB"), com.morgana.domain.combat.ActionCombatRules.tokenFallbacks("SHIFT+BACK+LMB"))
        assertEquals(listOf("LMB"), com.morgana.domain.combat.ActionCombatRules.tokenFallbacks("LMB"))
    }

    private fun cyclone(range: Int) = ActionSkillDefinition(
        id = 9, name = "Cyclone", startupTicks = 1, activeTicks = 12, recoveryTicks = 2,
        moves = listOf(SkillMove(1, 6, toAim = true, maxPixels = range)),
        hitboxes = listOf(SkillHitbox(8, 9, HitboxShape.CIRCLE, radiusPixels = 70, hit = SkillHit(damagePercent = 100)))
    )

    @Test
    fun `a leap carries the character to the aimed point and the spin hits what is there`() {
        val sim = world(cyclone(range = 200), 9 to 5) // 5 tiles away: inside the leap's range
        val startX = sim.players.getValue("ann").position.pixelX
        sim.fire(9, 5)
        val endX = sim.players.getValue("ann").position.pixelX
        assertTrue(endX - startX in 140f..170f, "leapt about 5 tiles, to ${endX - startX}")
        assertTrue(sim.dummyHp() < 100_000, "the spin hit the dummy it landed beside")
    }

    @Test
    fun `a leap is held within its range, so a far target is not reached`() {
        val sim = world(cyclone(range = 96), 15 to 5) // 11 tiles away, leap limited to 3 tiles
        val startX = sim.players.getValue("ann").position.pixelX
        sim.fire(15, 5)
        val moved = sim.players.getValue("ann").position.pixelX - startX
        assertTrue(moved in 80f..100f, "leapt only its range: $moved")
        assertEquals(100_000, sim.dummyHp())
    }

    @Test
    fun `a chain-lightning hit also strikes the monsters near the one it hit`() {
        val strike = ActionSkillDefinition(
            id = 10, name = "Strike", startupTicks = 1, activeTicks = 3, recoveryTicks = 2,
            hitboxes = listOf(SkillHitbox(1, 2, HitboxShape.ARC, radiusPixels = 70, arcDegrees = 90, hit = SkillHit(damagePercent = 100, chainTargets = 2, chainRadiusPixels = 120, chainDamagePercent = 50)))
        )
        val sim = WorldSimulation(SeededRandomSource(5L), game = MorganaClassic.COMPILED)
        sim.modules = ModuleFlags.ALL_ENABLED
        sim.combat = ActionCombatModule()
        sim.npcs[9] = dummy
        sim.actionSkills[10] = strike
        sim.weaponSets[1] = WeaponSetDefinition(id = 1, name = "Set", skillIds = listOf(10), entries = listOf(ComboEntry("RMB", 10)))
        sim.registerMap(
            MapDefinition(
                id = 1, name = "Arena", width = 24, height = 16,
                npcSpawns = listOf(MapNpcSpawnSlot(0, 9, 6, 5), MapNpcSpawnSlot(1, 9, 6, 8), MapNpcSpawnSlot(2, 9, 18, 12))
            )
        )
        sim.spawnOrUpdatePlayer(CharacterState(id = "ann", accountId = "a", name = "Ann", level = 10, position = WorldPosition.fromTile(1, 4, 5, Direction.RIGHT)))
        sim.handleAction("ann", "input", mapOf("token" to "RMB"))
        repeat(10) { sim.stepTick() }
        val hp = sim.mapNpcs.getValue(1).associate { it.spawnSlotIndex to it.vitals.getValue("hp").cur }
        assertTrue(hp.getValue(0) < 100_000, "the monster in the arc was hit")
        assertTrue(hp.getValue(1) < 100_000, "the one beside it was shocked")
        assertEquals(100_000, hp.getValue(2), "a far monster was not")
        val struck = 100_000 - hp.getValue(0)
        val shocked = 100_000 - hp.getValue(1)
        assertTrue(shocked in 1 until struck, "the chain hits for less than the strike: $shocked vs $struck")
    }
}
