package com.morgana.domain

import com.morgana.domain.combat.ActionCombatModule
import com.morgana.domain.content.ActionSkillDefinition
import com.morgana.domain.content.ComboEntry
import com.morgana.domain.content.HitboxShape
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SkillArmorType
import com.morgana.domain.content.SkillArmorWindow
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Successful dodges fill a momentum meter, and a full meter empowers one skill for a while. */
class MomentumTest {
    private val biter = NpcDefinition(id = 9, name = "Biter", behavior = NpcBehavior.ATTACK_ON_SIGHT, level = 1, vitals = mapOf("hp" to 100_000), attackDamage = 5, attackSpeedTicks = 6, aggroRangeTiles = 12, leashRangeTiles = 40)
    private val strike = ActionSkillDefinition(
        id = 1, name = "Strike", startupTicks = 1, activeTicks = 2, recoveryTicks = 2,
        hitboxes = listOf(SkillHitbox(1, 2, HitboxShape.CIRCLE, radiusPixels = 80, hit = SkillHit(damagePercent = 100)))
    )
    private val bigStrike = strike.copy(id = 2, name = "Big Strike", hitboxes = strike.hitboxes.map { it.copy(hit = SkillHit(damagePercent = 500)) })
    private val dodge = ActionSkillDefinition(id = 3, name = "Dodge", startupTicks = 0, activeTicks = 40, recoveryTicks = 2, armor = listOf(SkillArmorWindow(0, 40, SkillArmorType.INVULNERABLE)))

    private fun world(perDodge: Int): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(2L), game = MorganaClassic.COMPILED)
        sim.modules = ModuleFlags.ALL_ENABLED
        sim.combat = ActionCombatModule()
        sim.npcs[9] = biter
        listOf(strike, bigStrike, dodge).forEach { sim.actionSkills[it.id] = it }
        sim.weaponSets[1] = WeaponSetDefinition(
            id = 1, name = "Set", skillIds = listOf(1, 2, 3),
            entries = listOf(ComboEntry("LMB", 1), ComboEntry("SPACE", 3)),
            empowerSkillId = 1, empoweredSkillId = 2, momentumPerDodge = perDodge, empowerTicks = 100
        )
        sim.registerMap(MapDefinition(id = 1, name = "Arena", width = 16, height = 12, npcSpawns = listOf(MapNpcSpawnSlot(0, 9, 6, 5))))
        sim.spawnOrUpdatePlayer(CharacterState(id = "ann", accountId = "a", name = "Ann", level = 10, position = WorldPosition.fromTile(1, 5, 5, Direction.RIGHT)))
        return sim
    }

    private val seen = ArrayList<WorldSimulationEvent>()
    private fun WorldSimulation.step(n: Int) = repeat(n) { seen += stepTick() }
    private fun WorldSimulation.press(token: String) { handleAction("ann", "input", mapOf("token" to token)) }
    private fun WorldSimulation.meter() = actionStates["ann"]?.momentum ?: 0
    private fun WorldSimulation.empowered() = (actionStates["ann"]?.empoweredUntil ?: 0L) > currentTick

    @Test
    fun `a dodge that takes a blow fills the meter once, however many blows it takes`() {
        val sim = world(perDodge = 30)
        sim.press("SPACE")
        sim.step(36)
        assertEquals(30, sim.meter(), "one dodge, one fill")
        assertTrue(seen.filterIsInstance<WorldSimulationEvent.MomentumChanged>().isNotEmpty(), "the client is told")
    }

    @Test
    fun `a dodge nothing attacked into does not fill the meter`() {
        val sim = world(perDodge = 30)
        sim.mapNpcs.getValue(1).clear() // nobody to hit it
        sim.press("SPACE")
        sim.step(30)
        assertEquals(0, sim.meter())
    }

    @Test
    fun `a full meter empowers the character, and the upgraded skill replaces the plain one`() {
        val sim = world(perDodge = 60)
        sim.press("SPACE"); sim.step(36)
        assertEquals(60, sim.meter())
        sim.step(10) // let the first dodge end
        sim.press("SPACE"); sim.step(36)
        assertTrue(sim.empowered(), "two dodges of 60 fill the meter")
        assertEquals(0, sim.meter(), "the meter empties as the empowerment starts")
        sim.step(10)
        sim.press("LMB")
        assertEquals(2, sim.actionStates["ann"]?.skillId, "the empowered press started the upgraded skill")
    }

    @Test
    fun `empowerment wears off`() {
        val sim = world(perDodge = 100)
        sim.press("SPACE"); sim.step(36)
        assertTrue(sim.empowered())
        sim.step(120)
        assertEquals(false, sim.empowered())
        sim.press("LMB")
        assertEquals(1, sim.actionStates["ann"]?.skillId, "back to the plain skill")
    }

    @Test
    fun `unfed momentum fades away`() {
        val sim = world(perDodge = 30)
        sim.press("SPACE"); sim.step(36)
        assertEquals(30, sim.meter())
        sim.mapNpcs.getValue(1).clear()
        sim.step(300)
        assertEquals(0, sim.meter())
    }
}
