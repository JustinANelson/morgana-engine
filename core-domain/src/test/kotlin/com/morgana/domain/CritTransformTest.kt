package com.morgana.domain

import com.morgana.domain.combat.ActionCombatModule
import com.morgana.domain.content.ActionSkillDefinition
import com.morgana.domain.content.ComboEntry
import com.morgana.domain.content.HitboxShape
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SkillHit
import com.morgana.domain.content.SkillHitbox
import com.morgana.domain.content.WeaponSetDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A critical hit arms a transformation of an existing skill; the player casts it when they choose. */
class CritTransformTest {
    private val dummy = NpcDefinition(id = 9, name = "Dummy", behavior = NpcBehavior.PASSIVE, level = 1, vitals = mapOf("hp" to 1_000_000), attackDamage = 0, aggroRangeTiles = 0)

    private fun hitbox(hit: SkillHit) = SkillHitbox(1, 2, HitboxShape.CIRCLE, radiusPixels = 80, hit = hit)
    private fun skill(id: Int, name: String, hit: SkillHit) = ActionSkillDefinition(id = id, name = name, startupTicks = 1, activeTicks = 3, recoveryTicks = 2, hitboxes = listOf(hitbox(hit)))

    private val seen = ArrayList<WorldSimulationEvent>()

    private fun world(critBp: Int): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(6L), game = MorganaClassic.COMPILED)
        sim.modules = ModuleFlags.ALL_ENABLED
        sim.combat = ActionCombatModule()
        sim.npcs[9] = dummy
        sim.actionSkills[1] = skill(1, "Shot", SkillHit(damagePercent = 100, critBonusBp = critBp, critScalePercent = 0))
        sim.actionSkills[2] = skill(2, "Volley", SkillHit(damagePercent = 100, critScalePercent = 0))
        sim.actionSkills[3] = skill(3, "Thunder Volley", SkillHit(damagePercent = 300, critScalePercent = 0, critBonusBp = 10_000))
        sim.weaponSets[1] = WeaponSetDefinition(
            id = 1, name = "Set", skillIds = listOf(1, 2, 3), entries = listOf(ComboEntry("LMB", 1), ComboEntry("RMB", 2)),
            empowerSkillId = 2, empoweredSkillId = 3, empowerTicks = 100, empowerOnCrit = true
        )
        sim.registerMap(MapDefinition(id = 1, name = "Arena", width = 16, height = 12, npcSpawns = listOf(MapNpcSpawnSlot(0, 9, 6, 5))))
        sim.spawnOrUpdatePlayer(CharacterState(id = "ann", accountId = "a", name = "Ann", level = 10, position = WorldPosition.fromTile(1, 5, 5, Direction.RIGHT)))
        seen.clear()
        return sim
    }

    private fun WorldSimulation.press(token: String) { handleAction("ann", "input", mapOf("token" to token)) }
    private fun WorldSimulation.step(n: Int) = repeat(n) { seen += stepTick() }
    private fun WorldSimulation.armed() = (actionStates["ann"]?.empoweredUntil ?: 0L) > currentTick

    @Test
    fun `a critical hit arms the transformation but does not cast it`() {
        val sim = world(critBp = 10_000)
        sim.press("LMB"); sim.step(8)
        assertTrue(sim.armed())
        assertTrue(seen.filterIsInstance<WorldSimulationEvent.MomentumChanged>().any { it.empoweredTicks == 100 }, "the client is told")
        assertEquals(1, sim.actionStates["ann"]?.skillId ?: 1, "nothing else was started for the player")
    }

    @Test
    fun `without a crit nothing is armed`() {
        val sim = world(critBp = 0)
        sim.press("LMB"); sim.step(8)
        assertFalse(sim.armed())
    }

    @Test
    fun `the armed button casts the transformed skill when pressed, and the transformation is spent`() {
        val sim = world(critBp = 10_000)
        sim.press("LMB"); sim.step(10)
        assertTrue(sim.armed())
        sim.press("RMB")
        assertEquals(3, sim.actionStates["ann"]?.skillId, "the plain Volley button cast Thunder Volley")
        assertFalse(sim.armed(), "casting spent it")
    }

    @Test
    fun `the transformation wears off if it is not used`() {
        val sim = world(critBp = 10_000)
        sim.press("LMB"); sim.step(8)
        assertTrue(sim.armed())
        sim.step(120)
        assertFalse(sim.armed())
        sim.press("RMB")
        assertEquals(2, sim.actionStates["ann"]?.skillId, "back to the plain skill")
    }

    @Test
    fun `the transformed skill's own crits do not re-arm it`() {
        val sim = world(critBp = 10_000)
        sim.press("LMB"); sim.step(10)
        sim.press("RMB"); sim.step(10)
        assertFalse(sim.armed(), "Thunder Volley crits for certain but does not arm another")
    }
}
