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
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.WorldLayoutDefinition
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fighting across a map border in a seamless world: skills and arrows reach over it, monsters notice and strike over it, and nobody is moved by it. */
class CrossBorderCombatTest {

    private val wolf = NpcDefinition(
        id = 9, name = "Wolf", behavior = NpcBehavior.ATTACK_ON_SIGHT, level = 1, vitals = mapOf("hp" to 100_000),
        attackDamage = 5, attackSpeedTicks = 6, aggroRangeTiles = 6, moveSpeedTicks = 6
    )

    private fun swing(radius: Int) = ActionSkillDefinition(
        id = 7, name = "Swing", startupTicks = 1, activeTicks = 2, recoveryTicks = 2,
        hitboxes = listOf(SkillHitbox(1, 2, HitboxShape.CIRCLE, radiusPixels = radius, hit = SkillHit(damagePercent = 100)))
    )

    private fun shot() = ActionSkillDefinition(
        id = 8, name = "Shot", startupTicks = 1, activeTicks = 2, recoveryTicks = 2,
        hitboxes = listOf(SkillHitbox(1, 1, HitboxShape.CIRCLE, radiusPixels = 8, hit = SkillHit(damagePercent = 100), projectile = SkillProjectile(speedPixelsPerTick = 20, rangePixels = 400, freeAngle = true)))
    )

    /** West is 40 x 40 at the origin, east 40 x 40 beside it; a wolf stands on the east map at ([wolfX], 10). The player is on the west map at ([playerX], 10). */
    private fun world(skill: ActionSkillDefinition, playerX: Int, wolfX: Int, layout: Boolean = true): WorldSimulation {
        val def = MorganaClassic.COMPILED.definition.let { if (layout) it.copy(worldLayout = WorldLayoutDefinition()) else it }
        val sim = WorldSimulation(SeededRandomSource(5L), game = CompiledGame.compile(def))
        sim.modules = ModuleFlags.ALL_ENABLED
        sim.combat = ActionCombatModule()
        sim.npcs[9] = wolf
        sim.actionSkills[skill.id] = skill
        sim.weaponSets[1] = WeaponSetDefinition(id = 1, name = "Set", skillIds = listOf(skill.id), entries = listOf(ComboEntry("RMB", skill.id)))
        sim.registerMap(MapDefinition(id = 1, name = "West", width = 40, height = 40, worldX = 0, worldY = 0))
        sim.registerMap(MapDefinition(id = 2, name = "East", width = 40, height = 40, worldX = 40, worldY = 0, npcSpawns = listOf(MapNpcSpawnSlot(0, 9, wolfX, 10))))
        sim.spawnOrUpdatePlayer(CharacterState(id = "ann", accountId = "a", name = "Ann", level = 10, position = WorldPosition.fromTile(1, playerX, 10, Direction.RIGHT)))
        return sim
    }

    private fun WorldSimulation.wolfHp() = mapNpcs.getValue(2).single().vitals.getValue("hp").cur

    @Test
    fun `a skill reaches a monster on the other side of the border and the damage lands on its own map`() {
        val sim = world(swing(radius = 130), playerX = 38, wolfX = 1) // three tiles across: 96 px away
        val before = sim.wolfHp()
        sim.handleAction("ann", "input", mapOf("token" to "RMB"))
        repeat(10) { sim.stepTick() }
        assertTrue(sim.wolfHp() < before, "the wolf across the border was hit")
        assertEquals(1, sim.players.getValue("ann").position.mapId)
        assertEquals(38, sim.players.getValue("ann").position.tileX, "the attacker did not move")
    }

    @Test
    fun `without the seamless world the same swing does not reach across`() {
        val sim = world(swing(radius = 130), playerX = 38, wolfX = 1, layout = false)
        val before = sim.wolfHp()
        sim.handleAction("ann", "input", mapOf("token" to "RMB"))
        repeat(10) { sim.stepTick() }
        assertEquals(before, sim.wolfHp())
    }

    @Test
    fun `a monster too far away is not hit even across the border`() {
        val sim = world(swing(radius = 60), playerX = 30, wolfX = 8) // 18 tiles apart
        val before = sim.wolfHp()
        sim.handleAction("ann", "input", mapOf("token" to "RMB"))
        repeat(10) { sim.stepTick() }
        assertEquals(before, sim.wolfHp())
    }

    @Test
    fun `an arrow flies over the border and hits the monster there`() {
        val sim = world(shot(), playerX = 37, wolfX = 4)
        val before = sim.wolfHp()
        sim.handleAction("ann", "input", mapOf("token" to "RMB", "aimX" to (41 * 32 + 16).toString(), "aimY" to (10 * 32 + 16).toString()))
        repeat(30) { sim.stepTick() }
        assertTrue(sim.wolfHp() < before, "the arrow reached the wolf on the east map")
    }

    @Test
    fun `an arrow stops at the edge of the world`() {
        val sim = world(shot(), playerX = 1, wolfX = 4)
        sim.handleAction("ann", "input", mapOf("token" to "RMB", "aimX" to "0", "aimY" to (10 * 32 + 16).toString()))
        repeat(30) { sim.stepTick() }
        assertTrue(sim.mapProjectiles[1].orEmpty().isEmpty(), "nothing is left flying")
    }

    @Test
    fun `a monster notices a player across the border, strikes them, and never moves them`() {
        val sim = world(swing(50), playerX = 39, wolfX = 0)
        val start = sim.players.getValue("ann").position
        val startHp = sim.players.getValue("ann").vitals.getValue("hp").cur
        val updates = ArrayList<WorldSimulationEvent.CharacterUpdated>()
        repeat(60) { updates += sim.stepTick().filterIsInstance<WorldSimulationEvent.CharacterUpdated>() }
        val after = sim.players.getValue("ann")
        assertTrue(after.vitals.getValue("hp").cur < startHp, "the wolf bit the player across the border")
        assertEquals(start, after.position, "the player's real position is untouched")
        assertTrue(updates.isNotEmpty() && updates.all { it.character.position == start }, "no update ever carried a shifted position: ${updates.map { it.character.position }.toSet()}")
        assertEquals(2, sim.mapNpcs.getValue(2).single().mapId, "the wolf stayed on its own map")
    }

    @Test
    fun `a monster far from the border ignores a player on the other map`() {
        val sim = world(swing(50), playerX = 20, wolfX = 20)
        val startHp = sim.players.getValue("ann").vitals.getValue("hp").cur
        repeat(60) { sim.stepTick() }
        assertEquals(startHp, sim.players.getValue("ann").vitals.getValue("hp").cur)
    }
}
