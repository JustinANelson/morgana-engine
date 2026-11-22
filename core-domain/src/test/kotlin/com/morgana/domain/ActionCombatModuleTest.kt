package com.morgana.domain

import com.morgana.domain.combat.ActionCombatModule
import com.morgana.domain.combat.ActionCombatRules
import com.morgana.domain.content.ActionSkillDefinition
import com.morgana.domain.content.ComboEdge
import com.morgana.domain.content.ComboEntry
import com.morgana.domain.content.HitReaction
import com.morgana.domain.content.HitboxShape
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SkillArmorType
import com.morgana.domain.content.SkillArmorWindow
import com.morgana.domain.content.SkillGuard
import com.morgana.domain.content.SkillHit
import com.morgana.domain.content.SkillHitbox
import com.morgana.domain.content.SkillMove
import com.morgana.domain.content.WeaponSetDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.replay.StateHasher
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.ActiveStatusEffect
import com.morgana.domain.stats.StatusEffectType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The action combat module: combos, timing, costs, hit-stop, dashes, dodging, guarding, and a replay that hashes the same. */
class ActionCombatModuleTest {
    private val arc = SkillHitbox(2, 3, HitboxShape.ARC, radiusPixels = 56, arcDegrees = 120, hit = SkillHit(damagePercent = 100))

    private val cut = ActionSkillDefinition(id = 1, name = "Cut", startupTicks = 2, activeTicks = 2, recoveryTicks = 4, cancelFromTick = 3, hitboxes = listOf(arc))
    private val backhand = ActionSkillDefinition(id = 2, name = "Backhand", startupTicks = 2, activeTicks = 2, recoveryTicks = 4, cancelFromTick = 3, hitboxes = listOf(arc.copy(hit = SkillHit(damagePercent = 150))))
    private val finisher = ActionSkillDefinition(
        id = 3, name = "Finisher", startupTicks = 1, activeTicks = 2, recoveryTicks = 6, costs = mapOf("mp" to 5), cooldownTicks = 40,
        hitboxes = listOf(arc.copy(fromTick = 1, toTick = 2, hit = SkillHit(damagePercent = 300, reaction = HitReaction.KNOCKDOWN, reactionTicks = 20, knockbackPixels = 32, hitStopTicks = 3)))
    )
    private val dash = ActionSkillDefinition(
        id = 4, name = "Dash", startupTicks = 0, activeTicks = 4, recoveryTicks = 2,
        moves = listOf(SkillMove(0, 3, 64)), armor = listOf(SkillArmorWindow(0, 3, SkillArmorType.INVULNERABLE))
    )
    private val ward = ActionSkillDefinition(
        id = 5, name = "Ward", startupTicks = 1, activeTicks = 3, recoveryTicks = 4,
        guard = SkillGuard(arcDegrees = 120, maxHoldTicks = 30, blockCosts = mapOf("sp" to 4), damageReductionPercent = 75)
    )
    private val riposte = ActionSkillDefinition(
        id = 6, name = "Riposte", startupTicks = 1, activeTicks = 2, recoveryTicks = 4,
        hitboxes = listOf(arc.copy(fromTick = 1, toTick = 2))
    )

    private val blades = WeaponSetDefinition(
        id = 1, name = "Blades", skillIds = listOf(1, 2, 3, 4, 5, 6), inputBufferTicks = 4,
        entries = listOf(ComboEntry("LMB", 1), ComboEntry("SPACE", 4), ComboEntry("Q", 5)),
        edges = listOf(
            ComboEdge(1, "LMB", 2, windowTicks = 6),
            ComboEdge(2, "LMB", 3, windowTicks = 6),
            ComboEdge(1, "RMB", 6, windowTicks = 6, requiresHit = true)
        )
    )

    private val dummy = NpcDefinition(id = 9, name = "Dummy", behavior = NpcBehavior.PASSIVE, level = 1, vitals = mapOf("hp" to 100_000), attackDamage = 0, aggroRangeTiles = 0)

    private fun world(dummyAt: Pair<Int, Int> = 7 to 5, seed: Long = 11L): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(seed), game = MorganaClassic.COMPILED)
        sim.modules = ModuleFlags.ALL_ENABLED
        sim.combat = ActionCombatModule()
        sim.npcs[9] = dummy
        listOf(cut, backhand, finisher, dash, ward, riposte).forEach { sim.actionSkills[it.id] = it }
        sim.weaponSets[1] = blades
        sim.registerMap(MapDefinition(id = 1, name = "Arena", width = 16, height = 12, npcSpawns = listOf(MapNpcSpawnSlot(0, 9, dummyAt.first, dummyAt.second))))
        sim.spawnOrUpdatePlayer(CharacterState(id = "ann", accountId = "a", name = "Ann", level = 10, position = WorldPosition.fromTile(1, 6, 5, Direction.RIGHT)))
        return sim
    }

    private fun WorldSimulation.press(token: String) = handleAction("ann", "input", mapOf("token" to token))
    private fun WorldSimulation.ticks(n: Int) = repeat(n) { stepTick() }
    private fun WorldSimulation.dummyHp() = mapNpcs.getValue(1).single().vitals.getValue("hp").cur
    private fun WorldSimulation.skill() = actionStates["ann"]?.skillId ?: 0

    @Test
    fun `a press starts the entry skill, its hitbox lands once, and a second press chains the next skill in the window`() {
        val sim = world()
        val full = sim.dummyHp()
        sim.press("LMB")
        assertEquals(1, sim.skill())
        sim.ticks(6)
        val afterCut = sim.dummyHp()
        assertTrue(afterCut < full, "the cut landed")
        sim.ticks(1)
        assertEquals(afterCut, sim.dummyHp(), "a target is hit once per hit group, however long the hitbox lives")
        sim.press("LMB")
        assertEquals(2, sim.skill(), "the chain moved on inside the window")
        sim.ticks(6)
        assertTrue(full - sim.dummyHp() > (full - afterCut), "the backhand hits harder than the cut")
    }

    @Test
    fun `an early press is buffered and fires at the cancel point, a late one only starts a fresh entry`() {
        val sim = world()
        sim.press("LMB")
        sim.ticks(1)
        sim.press("LMB") // before the cancel tick (3): buffered
        assertEquals(1, sim.skill())
        sim.ticks(3)
        assertEquals(2, sim.skill(), "the buffered press fired when the cancel point arrived")

        val late = world()
        late.press("LMB")
        late.ticks(40) // far past the skill and its combo grace
        assertEquals(0, late.skill())
        late.press("LMB")
        assertEquals(1, late.skill(), "a fresh chain starts from the entry")
    }

    @Test
    fun `an edge that needs a hit is refused after a miss and taken after a connect`() {
        val miss = world(dummyAt = 14 to 10)
        miss.press("LMB"); miss.ticks(4)
        miss.press("RMB")
        assertEquals(1, miss.skill(), "the cut missed, so the riposte edge is not taken")

        val hit = world()
        hit.press("LMB"); hit.ticks(4)
        hit.press("RMB")
        assertEquals(6, hit.skill())
    }

    @Test
    fun `costs, cooldowns and hit-stop`() {
        val sim = world()
        sim.press("LMB"); sim.ticks(4); sim.press("LMB"); sim.ticks(4); sim.press("LMB")
        assertEquals(3, sim.skill(), "the finisher started")
        val mp = sim.players.getValue("ann").vitals.getValue("mp").cur
        assertTrue(mp < sim.players.getValue("ann").vitals.getValue("mp").max, "it cost mana")
        val start = sim.actionStates.getValue("ann").start
        sim.ticks(3)
        assertTrue(sim.actionStates.getValue("ann").start > start, "hit-stop pushed the timeline back")
        // The finisher's cooldown: cannot be restarted at once.
        val again = world()
        again.press("LMB"); again.ticks(4); again.press("LMB"); again.ticks(4); again.press("LMB"); again.ticks(30)
        again.press("LMB"); again.ticks(4); again.press("LMB"); again.ticks(4); again.press("LMB")
        assertNotEquals(3, again.skill(), "the finisher is on cooldown")
    }

    @Test
    fun `a skill that costs more than the character has does not start`() {
        val sim = world()
        sim.actionSkills[1] = cut.copy(costs = mapOf("mp" to 100_000))
        sim.press("LMB")
        assertEquals(0, sim.skill())
    }

    @Test
    fun `a knockdown staggers the monster, knocks it back and a status can ride on a hit`() {
        val sim = world(dummyAt = 7 to 5)
        sim.press("LMB"); sim.ticks(4); sim.press("LMB"); sim.ticks(4); sim.press("LMB"); sim.ticks(3)
        val npc = sim.mapNpcs.getValue(1).single()
        assertTrue(npc.statusEffects.any { it.type == StatusEffectType.STUN }, "the knockdown stuns it")
        assertEquals(8, npc.tileX, "knocked back one tile away from the player")
    }

    @Test
    fun `a dash moves the character with collision and its invulnerable frames dodge every hit`() {
        val sim = world(dummyAt = 14 to 10)
        val before = sim.players.getValue("ann").position.pixelX
        sim.press("SPACE")
        val out = mutableListOf<com.morgana.domain.sim.WorldSimulationEvent>()
        assertEquals(0, (sim.combat as ActionCombatModule).incomingDamage(sim, sim.players.getValue("ann"), 9, 5, 50, out), "dodged inside the window")
        sim.ticks(5)
        assertEquals(before + 64f, sim.players.getValue("ann").position.pixelX, "the dash covered its 64 px")
        val after = (sim.combat as ActionCombatModule).incomingDamage(sim, sim.players.getValue("ann"), 9, 5, 50, mutableListOf())
        assertEquals(50, after, "once the dash is over a hit lands in full")

        val wall = world(dummyAt = 14 to 10)
        wall.registerMap(MapDefinition(id = 1, name = "Arena", width = 16, height = 12, npcSpawns = emptyList()).let { m ->
            m.copy(cells = m.cells.mapIndexed { i, c -> if (i % m.width == 8 && i / m.width == 5) c.copy(attribute = com.morgana.domain.map.TileAttribute.Blocked) else c })
        })
        wall.spawnOrUpdatePlayer(wall.players.getValue("ann").copy(position = WorldPosition.fromTile(1, 6, 5, Direction.RIGHT)))
        wall.press("SPACE"); wall.ticks(5)
        assertTrue(wall.players.getValue("ann").position.tileX <= 7, "a wall stops the dash")
    }

    @Test
    fun `a held guard blocks hits from the front, pays its cost, and is released by the player`() {
        val sim = world(dummyAt = 14 to 10)
        sim.press("Q")
        sim.ticks(2)
        val module = sim.combat as ActionCombatModule
        val sp = sim.players.getValue("ann").vitals.getValue("sp").cur
        val front = module.incomingDamage(sim, sim.players.getValue("ann"), 8, 5, 100, mutableListOf())
        assertEquals(25, front, "75% blocked from the front")
        assertEquals(sp - 4, sim.players.getValue("ann").vitals.getValue("sp").cur, "the block cost stamina")
        val behind = module.incomingDamage(sim, sim.players.getValue("ann"), 2, 5, 100, mutableListOf())
        assertEquals(100, behind, "a hit from behind is not blocked")
        sim.ticks(10)
        assertTrue(sim.skill() == 5, "the guard is still held after its active phase")
        sim.handleAction("ann", "release")
        sim.ticks(12)
        assertTrue(sim.actionStates.getValue("ann").holdUntil <= sim.currentTick, "released: no longer held")
        sim.ticks(30)
        assertEquals(0, sim.skill(), "after its recovery and combo grace the skill is over")
    }

    @Test
    fun `stun ends a skill unless super armour covers it`() {
        val stun = ActiveStatusEffect("x", StatusEffectType.STUN, "npc", 0, emptyMap(), 30)
        val plain = world()
        plain.press("LMB"); plain.ticks(1)
        plain.players["ann"] = plain.players.getValue("ann").copy(statusEffects = listOf(stun))
        plain.ticks(1)
        assertEquals(0, plain.skill(), "a stunned character drops the skill")

        val armoured = world()
        armoured.actionSkills[1] = cut.copy(armor = listOf(SkillArmorWindow(0, 6, SkillArmorType.SUPER_ARMOR)))
        armoured.press("LMB"); armoured.ticks(1)
        armoured.players["ann"] = armoured.players.getValue("ann").copy(statusEffects = listOf(stun))
        armoured.ticks(1)
        assertEquals(1, armoured.skill(), "super armour shrugs it off")
        assertTrue(armoured.players.getValue("ann").statusEffects.none { it.type == StatusEffectType.STUN })
    }

    @Test
    fun `the same inputs replay to the same hash and a different seed does not`() {
        fun run(seed: Long): String {
            val sim = world(seed = seed)
            sim.press("LMB"); sim.ticks(4); sim.press("LMB"); sim.ticks(3); sim.press("LMB"); sim.ticks(6); sim.press("SPACE"); sim.ticks(10)
            return StateHasher.hash(sim)
        }
        assertEquals(run(5), run(5))
        assertNotEquals(run(5), run(6), "crit rolls come from the seeded random")
    }

    @Test
    fun `geometry helpers agree with the rules`() {
        assertEquals(Direction.RIGHT, ActionCombatRules.nearestDirection(1.0, 0.1))
        assertEquals(Direction.UP_LEFT, ActionCombatRules.nearestDirection(-1.0, -1.0))
        val rect = SkillHitbox(0, 1, HitboxShape.RECT, lengthPixels = 64, widthPixels = 32)
        assertTrue(ActionCombatRules.inShape(rect, 0.0, 0.0, 1.0, 0.0, 50.0, 0.0))
        assertTrue(!ActionCombatRules.inShape(rect, 0.0, 0.0, 1.0, 0.0, 120.0, 0.0))
        assertTrue(!ActionCombatRules.inShape(rect, 0.0, 0.0, 1.0, 0.0, 30.0, 40.0), "outside the width")
        assertTrue(ActionCombatRules.inGuardArc(120, 0.0, 0.0, 1.0, 0.0, 50.0, 10.0))
        assertTrue(!ActionCombatRules.inGuardArc(120, 0.0, 0.0, 1.0, 0.0, -50.0, 0.0))
    }
}
