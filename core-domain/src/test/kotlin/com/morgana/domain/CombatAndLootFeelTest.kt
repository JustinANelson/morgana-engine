package com.morgana.domain

import com.morgana.domain.combat.ActionCombatModule
import com.morgana.domain.combat.RealtimeCombatModule
import com.morgana.domain.content.ActionSkillDefinition
import com.morgana.domain.content.ComboEntry
import com.morgana.domain.content.HitboxShape
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SkillDamageKind
import com.morgana.domain.content.SkillHit
import com.morgana.domain.content.SkillHitbox
import com.morgana.domain.content.SkillProjectile
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.content.WeaponSetDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.replay.StateHasher
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CombatAndLootFeelTest {

    private fun setupWorld(actionCombat: Boolean = false, seed: Long = 77L): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(seed), game = MorganaClassic.COMPILED)
        sim.modules = ModuleFlags.ALL_ENABLED
        sim.combat = if (actionCombat) ActionCombatModule() else RealtimeCombatModule()
        sim.npcs[10] = NpcDefinition(id = 10, name = "Dummy", behavior = NpcBehavior.PASSIVE, level = 1, vitals = mapOf("hp" to 200), attackDamage = 0, aggroRangeTiles = 0, moveSpeedTicks = 999)
        sim.registerMap(
            MapDefinition(
                id = 1,
                name = "Range",
                width = 20,
                height = 20,
                npcSpawns = listOf(
                    MapNpcSpawnSlot(0, 10, 7, 5, respawnTicks = 100),
                    MapNpcSpawnSlot(1, 10, 9, 5, respawnTicks = 100),
                    MapNpcSpawnSlot(2, 10, 12, 12, respawnTicks = 100)
                )
            )
        )
        val hero = CharacterState(
            id = "hero",
            accountId = "a",
            name = "Hero",
            level = 10,
            classId = 1,
            position = WorldPosition.fromTile(1, 5, 5, Direction.RIGHT),
            knownSpellIds = listOf(1, 2, 3, 4)
        )
        sim.spawnOrUpdatePlayer(hero)
        return sim
    }

    private fun WorldSimulation.hpAt(x: Int, y: Int): Int =
        mapNpcs.getValue(1).first { it.tileX == x && it.tileY == y }.vitals.getValue("hp").cur

    @Test
    fun `piercing directional spell hits multiple lined-up monsters once each`() {
        val sim = setupWorld()
        sim.spells[1] = SpellDefinition(
            id = 1, name = "Piercing Bolt", targetType = SpellTargetType.DIRECTIONAL_PROJECTILE,
            rangeTiles = 8, basePower = 30, cooldownTicks = 1, projectileSpriteId = 1, pierce = true
        )
        sim.playerCastSpell("hero", 1, null, 10, 5)
        repeat(10) { sim.stepTick() }

        val firstHp = sim.hpAt(7, 5)
        val secondHp = sim.hpAt(9, 5)
        val farHp = sim.hpAt(12, 12)

        assertTrue(firstHp < 200, "first target in line was hit")
        assertTrue(secondHp < 200, "second target in line was also hit by piercing bolt")
        assertEquals(firstHp, secondHp, "each target was hit exactly once for the same damage")
        assertEquals(200, farHp, "off-line monster untouched")
    }

    @Test
    fun `non-piercing directional spell still stops on first target`() {
        val sim = setupWorld()
        sim.spells[2] = SpellDefinition(
            id = 2, name = "Plain Bolt", targetType = SpellTargetType.DIRECTIONAL_PROJECTILE,
            rangeTiles = 8, basePower = 30, cooldownTicks = 1, projectileSpriteId = 1, pierce = false
        )
        sim.playerCastSpell("hero", 2, null, 10, 5)
        repeat(10) { sim.stepTick() }

        assertTrue(sim.hpAt(7, 5) < 200, "first target hit")
        assertEquals(200, sim.hpAt(9, 5), "second target shielded by first")
    }

    @Test
    fun `action combat piercing projectile passes through multiple targets`() {
        val sim = setupWorld(actionCombat = true)
        sim.actionSkills[1] = ActionSkillDefinition(
            id = 1, name = "Piercing Arrow", startupTicks = 0, activeTicks = 2, recoveryTicks = 2,
            hitboxes = listOf(
                SkillHitbox(
                    fromTick = 0, toTick = 1, shape = HitboxShape.RECT,
                    hit = SkillHit(damageKind = SkillDamageKind.RANGED, damagePercent = 100),
                    projectile = SkillProjectile(spriteId = 1, speedPixelsPerTick = 32, rangePixels = 256, pierce = true)
                )
            )
        )
        sim.weaponSets[1] = WeaponSetDefinition(id = 1, name = "Bow", skillIds = listOf(1), entries = listOf(ComboEntry("LMB", 1)))

        sim.handleAction("hero", "input", mapOf("token" to "LMB", "aimX" to (12 * 32).toString(), "aimY" to (5 * 32).toString()))
        repeat(12) { sim.stepTick() }

        assertTrue(sim.hpAt(7, 5) < 200, "first monster struck")
        assertTrue(sim.hpAt(9, 5) < 200, "second monster struck by piercing action projectile")
    }

    @Test
    fun `ground AoE spell hits enemies around chosen tile and clamps out-of-range clicks`() {
        val sim = setupWorld()
        sim.spells[3] = SpellDefinition(
            id = 3, name = "Meteor", targetType = SpellTargetType.GROUND_AOE,
            rangeTiles = 4, aoeRadiusTiles = 1, basePower = 40, cooldownTicks = 1, animationId = 2
        )
        // Click at (8, 5) — within range 4 of (5, 5), radius 1 covers both (7, 5) and (9, 5), misses (12, 12)
        val events = sim.playerCastSpell("hero", 3, null, 8, 5)
        assertTrue(events.any { it is WorldSimulationEvent.SpellCast })
        assertTrue(sim.hpAt(7, 5) < 200)
        assertTrue(sim.hpAt(9, 5) < 200)
        assertEquals(200, sim.hpAt(12, 12))

        // Out-of-range click at (12, 12) gets clamped to (9, 9) (max range 4 from (5,5)), so (12, 12) is not hit
        sim.stepTick()
        sim.stepTick()
        val farEvents = sim.playerCastSpell("hero", 3, null, 12, 12)
        assertEquals(200, sim.hpAt(12, 12))
        val anim = farEvents.filterIsInstance<WorldSimulationEvent.AnimationTriggered>().firstOrNull()
        assertEquals(9, anim?.tileX)
        assertEquals(9, anim?.tileY)
    }

    @Test
    fun `ground AoE healing spell heals allies in targeted area and replays deterministically`() {
        fun runOnce(): Pair<Int, String> {
            val sim = setupWorld(seed = 123L)
            sim.spells[4] = SpellDefinition(
                id = 4, name = "Sanctuary", targetType = SpellTargetType.GROUND_AOE,
                rangeTiles = 5, aoeRadiusTiles = 2, basePower = 35, isHealing = true, cooldownTicks = 1
            )
            val wounded = sim.players["hero"]!!.let { p ->
                val hp = p.vitals["hp"]!!
                p.copy(vitals = p.vitals + ("hp" to hp.copy(cur = 20)))
            }
            sim.spawnOrUpdatePlayer(wounded)
            sim.playerCastSpell("hero", 4, null, 6, 5)
            sim.stepTick()
            return sim.players["hero"]!!.vitals["hp"]!!.cur to StateHasher.hash(sim)
        }
        val (hp1, hash1) = runOnce()
        val (hp2, hash2) = runOnce()
        assertTrue(hp1 > 20, "caster inside ground healing radius was healed")
        assertEquals(hp1, hp2)
        assertEquals(hash1, hash2)
    }
}
