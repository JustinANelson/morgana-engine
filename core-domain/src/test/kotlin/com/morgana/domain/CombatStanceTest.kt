package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.ClaimKindDefinition
import com.morgana.domain.game.ClaimLevel
import com.morgana.domain.game.CombatStanceDefinition
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.Stance
import com.morgana.domain.game.StanceMapRule
import com.morgana.domain.game.StancePermission
import com.morgana.domain.game.TerritoryDefinition
import com.morgana.domain.game.WorldObjectKind
import com.morgana.domain.game.WorldObjectsDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.systems.CombatStanceSystem
import com.morgana.domain.systems.TerritorySystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** Combat stance: SAFE / PvE / PvP, arming, per-map permission and the siege override. */
class CombatStanceTest {
    private val rules = CombatStanceDefinition(
        defaultPermission = StancePermission.NONE,
        maps = listOf(StanceMapRule(listOf(2), StancePermission.OPTIONAL), StanceMapRule(listOf(3), StancePermission.FORCED)),
        armTicks = 40, disarmTicks = 100, switchTicks = 20
    )

    private fun sim(stance: CombatStanceDefinition? = rules, modules: ModuleFlags = ModuleFlags()): WorldSimulation {
        val definition = MorganaClassic.definition.copy(
            combatStance = stance,
            worldObjects = WorldObjectsDefinition(listOf(WorldObjectKind("totem", "Totem", maxHp = 100))),
            territory = TerritoryDefinition(listOf(ClaimKindDefinition("totem", levels = listOf(ClaimLevel(5, 10)), safeZone = true)))
        )
        val s = WorldSimulation(SeededRandomSource(2L), game = CompiledGame.compile(definition))
        s.modules = modules
        for (id in 1..3) s.registerMap(MapDefinition(id = id, name = "Map $id", width = 40, height = 40))
        for (id in listOf("a", "b")) {
            s.spawnOrUpdatePlayer(CharacterState(id = id, accountId = "acc-$id", name = id.uppercase(), position = WorldPosition.fromTile(2, 10, 10, Direction.DOWN)))
        }
        return s
    }

    private fun WorldSimulation.say(id: String, text: String) = executeAdminSlashCommand(id, text)
        .filterIsInstance<WorldSimulationEvent.ChatEmitted>().map { it.message }

    private fun WorldSimulation.ticks(n: Int) = repeat(n) { stepTick() }
    private fun WorldSimulation.hit(a: String, b: String) = canAttackPlayer(players.getValue(a), players.getValue(b))

    @Test
    fun `nobody can hit anybody until both are in the PvP stance, which takes time to arm`() {
        val s = sim()
        assertFalse(s.hit("a", "b"), "PvE by default")
        assertEquals(listOf("The PvP stance arms in 2 seconds. Taking a hit cancels it."), s.say("a", "/stance pvp"))
        s.ticks(10)
        assertEquals(Stance.PVE, s.players.getValue("a").stance, "still arming")
        s.ticks(40)
        assertEquals(Stance.PVP, s.players.getValue("a").stance)
        assertFalse(s.hit("a", "b"), "b is still PvE: neither can be hit by the other")
        assertFalse(s.hit("b", "a"))
        s.say("b", "/stance pvp"); s.ticks(45)
        assertTrue(s.hit("a", "b"))
        assertTrue(s.hit("b", "a"))
    }

    @Test
    fun `where PvP is not allowed the stance is ignored, and where it is forced everybody is in it`() {
        val s = sim()
        s.players["a"] = s.players.getValue("a").copy(position = WorldPosition.fromTile(1, 10, 10, Direction.DOWN))
        assertEquals(listOf("PvP is not allowed here."), s.say("a", "/stance pvp"))
        // Move both to the optional map, arm, then take them back to a map with no PvP.
        for (id in listOf("a", "b")) s.players[id] = s.players.getValue(id).copy(stance = Stance.PVP, position = WorldPosition.fromTile(2, 10, 10, Direction.DOWN))
        assertTrue(s.hit("a", "b"))
        for (id in listOf("a", "b")) s.players[id] = s.players.getValue(id).copy(position = WorldPosition.fromTile(1, 10, 10, Direction.DOWN))
        assertFalse(s.hit("a", "b"), "map 1 allows none; the stance is kept but ignored")
        for (id in listOf("a", "b")) s.players[id] = s.players.getValue(id).copy(stance = Stance.SAFE, position = WorldPosition.fromTile(3, 10, 10, Direction.DOWN))
        assertTrue(s.hit("a", "b"), "map 3 is forced")
        assertEquals(listOf("You cannot change your stance here."), s.say("a", "/stance pve"))
    }

    @Test
    fun `taking a hit cancels arming, and leaving PvP or switching needs a quiet spell`() {
        val s = sim()
        s.say("a", "/stance pvp")
        s.players["a"] = CombatStanceSystem.afterStruck(s, s.players.getValue("a"), mutableListOf())
        assertEquals(-1L, s.players.getValue("a").stanceArmsAtTick, "the hit cancelled it")
        s.ticks(50)
        assertEquals(Stance.PVE, s.players.getValue("a").stance)

        s.players["a"] = s.players.getValue("a").copy(stance = Stance.PVP, lastCombatTick = s.effectiveWorldTick())
        assertTrue(s.say("a", "/stance pve").single().startsWith("You are still in combat."))
        s.ticks(101)
        assertEquals(listOf("Stance: PvE."), s.say("a", "/stance pve"))
        assertEquals(listOf("Stance: Safe."), s.say("a", "/stance safe"))
        assertTrue(CombatStanceSystem.ticksSinceCombat(s, "a")!! > 100)
        assertFalse(CombatStanceSystem.isInCombat(s, "a", 100))
        assertNull(CombatStanceSystem.ticksSinceCombat(s, "b"), "never fought")
    }

    @Test
    fun `hitting with a weapon while safe makes a character PvE`() {
        val s = sim()
        s.players["a"] = s.players.getValue("a").copy(stance = Stance.SAFE)
        CombatStanceSystem.afterAttack(s, "a", mutableListOf())
        assertEquals(Stance.PVE, s.players.getValue("a").stance)
        assertTrue(s.players.getValue("a").lastCombatTick >= 0)
    }

    @Test
    fun `an open siege window forces everyone inside the claim into PvP`() {
        val s = sim()
        val t0 = Instant.parse("2026-11-27T12:00:00Z").toEpochMilli()
        s.advanceWallClock(t0)
        val claim = (s.placeWorldObject("totem", 2, 12, 10, "a") as com.morgana.domain.systems.WorldObjectResult.Placed).obj
        for (id in listOf("a", "b")) s.players[id] = s.players.getValue(id).copy(stance = Stance.SAFE)
        assertFalse(s.hit("a", "b"))
        assertTrue(TerritorySystem.bookWindow(s, claim.id, t0 + 1000, 600))
        s.advanceWallClock(t0 + 2000)
        assertTrue(s.hit("a", "b"), "forced into PvP inside the open window")
        assertEquals(listOf("You cannot change your stance here."), s.say("a", "/stance safe"))
        s.players["b"] = s.players.getValue("b").copy(position = WorldPosition.fromTile(2, 30, 30, Direction.DOWN))
        assertFalse(s.hit("a", "b"), "outside it, b is safe")
    }

    @Test
    fun `without a combatStance section, or with the system off, nothing changes`() {
        val none = sim(stance = null)
        assertTrue(none.hit("a", "b"), "the old rules")
        assertEquals(listOf("There are no stances in this game."), none.say("a", "/stance"))
        val off = sim(modules = ModuleFlags(disabled = setOf(OptionalModule.COMBAT_STANCE)))
        assertTrue(off.hit("a", "b"))
    }

    @Test
    fun `the section is validated`() {
        fun pointers(c: CombatStanceDefinition) = GameDefinitionValidator.validate(MorganaClassic.definition.copy(combatStance = c)).map { it.pointer }
        assertTrue(GameDefinitionValidator.validate(MorganaClassic.definition.copy(combatStance = rules)).isEmpty())
        assertTrue("/combatStance/armTicks" in pointers(rules.copy(armTicks = -1)))
        assertTrue("/combatStance/defaultStance" in pointers(rules.copy(defaultStance = Stance.PVP)))
        assertTrue("/combatStance/maps/1/mapIds" in pointers(rules.copy(maps = listOf(StanceMapRule(listOf(2)), StanceMapRule(listOf(2))))))
        assertTrue("/combatStance/maps/0/mapIds" in pointers(rules.copy(maps = listOf(StanceMapRule(emptyList())))))
    }
}
