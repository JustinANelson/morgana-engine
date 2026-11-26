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
import com.morgana.domain.stats.StatusEffectType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Per-skill critical chances, electrical hits that stun, and a critical hit electrifying a skill. */
class ElectricHitTest {
    private val dummy = NpcDefinition(id = 9, name = "Dummy", behavior = NpcBehavior.PASSIVE, level = 1, vitals = mapOf("hp" to 1_000_000), attackDamage = 0, aggroRangeTiles = 0)

    private fun skill(hit: SkillHit) = ActionSkillDefinition(
        id = 1, name = "Burst", startupTicks = 1, activeTicks = 8, recoveryTicks = 2,
        hitboxes = listOf(1, 4, 7).mapIndexed { g, t -> SkillHitbox(t, t, HitboxShape.CIRCLE, radiusPixels = 60, hitGroup = g, groundTarget = true, maxRangePixels = 300, hit = hit) }
    )

    private val seen = ArrayList<WorldSimulationEvent>()

    private fun run(hit: SkillHit): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(4L), game = MorganaClassic.COMPILED)
        sim.modules = ModuleFlags.ALL_ENABLED
        sim.combat = ActionCombatModule()
        sim.npcs[9] = dummy
        sim.actionSkills[1] = skill(hit)
        sim.weaponSets[1] = WeaponSetDefinition(id = 1, name = "Set", skillIds = listOf(1), entries = listOf(ComboEntry("RMB", 1)))
        sim.registerMap(MapDefinition(id = 1, name = "Arena", width = 24, height = 16, npcSpawns = listOf(MapNpcSpawnSlot(0, 9, 9, 5))))
        sim.spawnOrUpdatePlayer(CharacterState(id = "ann", accountId = "a", name = "Ann", level = 10, position = WorldPosition.fromTile(1, 4, 5, Direction.RIGHT)))
        seen.clear()
        sim.handleAction("ann", "input", mapOf("token" to "RMB", "aimX" to (9 * 32 + 16).toString(), "aimY" to (5 * 32 + 16).toString()))
        repeat(4) { seen += sim.stepTick() }
        return sim
    }

    private fun WorldSimulation.stunned() = mapNpcs.getValue(1).single().statusEffects.any { it.id == "action-shock" && it.type == StatusEffectType.STUN }

    @Test
    fun `an electrical hit stuns the monster and shows an electric hit effect`() {
        val sim = run(SkillHit(damagePercent = 100, electric = true, stunTicks = 40))
        assertTrue(sim.stunned())
        assertTrue(seen.any { it is WorldSimulationEvent.SkillEffect && it.effect == "shock" })
    }

    @Test
    fun `a plain hit does not stun`() {
        val sim = run(SkillHit(damagePercent = 100))
        assertFalse(sim.stunned())
    }

    @Test
    fun `a critical hit electrifies a skill that allows it, stunning and flashing the area`() {
        val sim = run(SkillHit(damagePercent = 100, critElectrifies = true, critBonusBp = 10_000))
        assertTrue(sim.stunned(), "a certain crit electrified it")
        assertTrue(seen.any { it is WorldSimulationEvent.SkillEffect && it.effect == "electrify" })
    }

    @Test
    fun `with no chance to crit the skill is never electrified`() {
        val sim = run(SkillHit(damagePercent = 100, critElectrifies = true, critScalePercent = 0))
        assertFalse(sim.stunned())
        assertTrue(seen.none { it is WorldSimulationEvent.SkillEffect && it.effect == "electrify" })
    }

    @Test
    fun `a skill's crit chance scales with its own percent of the agility chance`() {
        // With a certain chance of 0 from scale and no bonus, 60 hits never crit; with scale 0 and a 100% bonus, all do.
        var crits = 0
        repeat(60) {
            val sim = run(SkillHit(damagePercent = 100, critElectrifies = true, critScalePercent = 0, critBonusBp = 0))
            if (sim.stunned()) crits++
        }
        assertEquals(0, crits)
        val sure = run(SkillHit(damagePercent = 100, critElectrifies = true, critScalePercent = 0, critBonusBp = 10_000))
        assertTrue(sure.stunned())
    }
}
