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

/** Health orbs dropped by slain monsters, and a skill that pulls a crowd in. */
class HealthOrbTest {
    private val prey = NpcDefinition(id = 9, name = "Prey", behavior = NpcBehavior.PASSIVE, level = 1, vitals = mapOf("hp" to 5), attackDamage = 0, aggroRangeTiles = 0, xpReward = 5)
    private val kill = ActionSkillDefinition(
        id = 1, name = "Kill", startupTicks = 1, activeTicks = 2, recoveryTicks = 2,
        hitboxes = listOf(SkillHitbox(1, 2, HitboxShape.CIRCLE, radiusPixels = 200, hit = SkillHit(damagePercent = 500)))
    )
    private val pull = ActionSkillDefinition(
        id = 2, name = "Pull", startupTicks = 1, activeTicks = 2, recoveryTicks = 2,
        hitboxes = listOf(SkillHitbox(1, 2, HitboxShape.CIRCLE, radiusPixels = 250, hit = SkillHit(damagePercent = 10, pullPixels = 160)))
    )
    private val seen = ArrayList<WorldSimulationEvent>()

    private fun world(orbChance: Int, hurt: Boolean, spawns: List<Pair<Int, Int>> = listOf(6 to 5), variant: Boolean = false): WorldSimulation {
        val def = MorganaClassic.definition
        val game = com.morgana.domain.game.CompiledGame.compile(def.copy(tuning = def.gameplayTuning.copy(healthOrbChancePercent = orbChance, healthOrbHealPercent = 20)))
        val sim = WorldSimulation(SeededRandomSource(3L), game = game)
        sim.modules = ModuleFlags.ALL_ENABLED
        sim.combat = ActionCombatModule()
        sim.npcs[9] = prey.copy(vitals = if (variant) mapOf("hp" to 5) else prey.vitals)
        sim.actionSkills[1] = kill
        sim.actionSkills[2] = pull
        sim.weaponSets[1] = WeaponSetDefinition(id = 1, name = "Set", skillIds = listOf(1, 2), entries = listOf(ComboEntry("LMB", 1), ComboEntry("RMB", 2)))
        sim.registerMap(MapDefinition(id = 1, name = "Arena", width = 24, height = 16, npcSpawns = spawns.mapIndexed { i, s -> MapNpcSpawnSlot(i, 9, s.first, s.second) }))
        var p = CharacterState(id = "ann", accountId = "a", name = "Ann", level = 10, position = WorldPosition.fromTile(1, 5, 5, Direction.RIGHT))
        sim.spawnOrUpdatePlayer(p)
        if (hurt) {
            val life = sim.players.getValue("ann").vitals.getValue("hp")
            sim.players["ann"] = sim.players.getValue("ann").copy(vitals = sim.players.getValue("ann").vitals + ("hp" to life.copy(cur = life.max / 2)))
        }
        seen.clear()
        return sim
    }

    private fun WorldSimulation.hp() = players.getValue("ann").vitals.getValue("hp").cur
    private fun WorldSimulation.run(n: Int) = repeat(n) { seen += stepTick() }

    @Test
    fun `a slain monster drops an orb that a hurt player walks into and is healed`() {
        val sim = world(orbChance = 100, hurt = true)
        val before = sim.hp()
        sim.handleAction("ann", "input", mapOf("token" to "LMB"))
        sim.run(40)
        assertTrue(seen.any { it is WorldSimulationEvent.OrbsChanged && it.orbs.isNotEmpty() }, "an orb dropped")
        assertTrue(sim.hp() > before, "the hurt player drew it in and healed")
        assertTrue(sim.healthOrbs.getValue(1).isEmpty(), "and it is gone")
    }

    @Test
    fun `a healthy player leaves the orb where it is`() {
        val sim = world(orbChance = 100, hurt = false)
        sim.handleAction("ann", "input", mapOf("token" to "LMB"))
        sim.run(40)
        assertEquals(1, sim.healthOrbs.getValue(1).size)
    }

    @Test
    fun `with orbs off, nothing drops`() {
        val sim = world(orbChance = 0, hurt = true)
        sim.handleAction("ann", "input", mapOf("token" to "LMB"))
        sim.run(30)
        assertFalse(seen.any { it is WorldSimulationEvent.OrbsChanged })
    }

    @Test
    fun `orbs fade away if nobody takes them`() {
        val sim = world(orbChance = 100, hurt = false)
        sim.handleAction("ann", "input", mapOf("token" to "LMB"))
        sim.run(700)
        assertTrue(sim.healthOrbs.getValue(1).isEmpty())
    }

    @Test
    fun `a pull drags a crowd in towards the attacker and stops them beside it`() {
        val sim = world(orbChance = 0, hurt = false, spawns = listOf(11 to 5, 10 to 8, 12 to 2))
        sim.npcs[9] = sim.npcs.getValue(9).copy(vitals = mapOf("hp" to 100_000))
        val before = sim.mapNpcs.getValue(1).associate { it.spawnSlotIndex to Math.hypot((it.tileX - 5).toDouble(), (it.tileY - 5).toDouble()) }
        sim.handleAction("ann", "input", mapOf("token" to "RMB"))
        sim.run(10)
        val after = sim.mapNpcs.getValue(1).associate { it.spawnSlotIndex to Math.hypot((it.tileX - 5).toDouble(), (it.tileY - 5).toDouble()) }
        for (slot in 0..2) assertTrue(after.getValue(slot) < before.getValue(slot), "monster $slot was pulled in")
        assertTrue(after.values.all { it >= 1.0 }, "none landed on the player")
    }
}
