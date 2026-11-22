package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.NpcCombatDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Monster pacing rules (`combat.npcs`): the pile-on cap, the first-strike delay, telegraphed spells and caster spacing. */
class NpcPacingTest {

    private fun game(npcs: NpcCombatDefinition): CompiledGame {
        val base = MorganaClassic.definition
        return CompiledGame.compile(base.copy(combat = base.combat.copy(npcs = npcs)))
    }

    private fun hero(sim: WorldSimulation, x: Int, y: Int): CharacterState {
        val c = CharacterState(
            id = "hero",
            accountId = "acc1",
            name = "Hero",
            attributes = mapOf("str" to 10, "def" to 10, "int" to 10, "agi" to 10, "vit" to 10),
            position = WorldPosition.fromTile(1, x, y, Direction.DOWN)
        ).recalculateVitals(sim.game, sim.items, refill = true)
        sim.spawnOrUpdatePlayer(c)
        return sim.players["hero"]!!
    }

    private fun wolfPack(sim: WorldSimulation, count: Int) {
        sim.npcs[10] = NpcDefinition(
            id = 10, name = "Wolf", behavior = NpcBehavior.ATTACK_ON_SIGHT, aggroRangeTiles = 6,
            vitals = mapOf("hp" to 100), attributes = mapOf("str" to 5, "def" to 5, "agi" to 5, "vit" to 10),
            attackSpeedTicks = 10
        )
        sim.registerMap(
            MapDefinition(
                id = 1, name = "Wolves", width = 20, height = 20,
                npcSpawns = (0 until count).map { MapNpcSpawnSlot(slotIndex = it, npcId = 10, spawnX = 5 + it, spawnY = 5, respawnTicks = 100) }
            )
        )
    }

    @Test
    fun `without a cap every monster in range hunts the player`() {
        val sim = WorldSimulation(SeededRandomSource(1L), game = game(NpcCombatDefinition()))
        wolfPack(sim, 4)
        hero(sim, 7, 8)
        sim.stepTick()
        assertEquals(4, sim.mapNpcs[1]!!.count { it.targetEntityId == "hero" })
    }

    @Test
    fun `the attacker cap leaves the rest of the pack idle`() {
        val sim = WorldSimulation(SeededRandomSource(1L), game = game(NpcCombatDefinition(maxAttackersPerTarget = 2)))
        wolfPack(sim, 4)
        hero(sim, 7, 8)
        repeat(3) { sim.stepTick() }
        assertEquals(2, sim.mapNpcs[1]!!.count { it.targetEntityId == "hero" })
    }

    @Test
    fun `a waiting monster takes over when a hunter dies`() {
        val sim = WorldSimulation(SeededRandomSource(1L), game = game(NpcCombatDefinition(maxAttackersPerTarget = 1)))
        wolfPack(sim, 2)
        hero(sim, 7, 8)
        sim.stepTick()
        val first = sim.mapNpcs[1]!!.single { it.targetEntityId == "hero" }
        val list = sim.mapNpcs[1]!!
        val i = list.indexOfFirst { it.instanceId == first.instanceId }
        list[i] = first.copy(vitals = first.vitals.mapValues { (_, v) -> v.copy(cur = 0) }, respawnAtTick = sim.currentTick + 1000L, targetEntityId = null)
        sim.stepTick()
        assertEquals(1, sim.mapNpcs[1]!!.count { it.targetEntityId == "hero" && it.isAlive })
    }

    @Test
    fun `the first strike waits for the delay even when the monster is already adjacent`() {
        val sim = WorldSimulation(SeededRandomSource(1L), game = game(NpcCombatDefinition(firstStrikeDelayTicks = 15)))
        wolfPack(sim, 1)
        val start = hero(sim, 5, 6).vitals["hp"]!!.cur
        repeat(14) { sim.stepTick() }
        assertEquals(start, sim.players["hero"]!!.vitals["hp"]!!.cur, "no blow before the delay is over")
        repeat(4) { sim.stepTick() }
        assertTrue(sim.players["hero"]!!.vitals["hp"]!!.cur < start, "the blow lands once the delay is over")
    }

    private fun caster(sim: WorldSimulation) {
        sim.spells[5] = SpellDefinition(
            id = 5, name = "Shadow Bolt", targetType = SpellTargetType.SINGLE_TARGET, cooldownTicks = 40, basePower = 25, rangeTiles = 4
        )
        sim.npcs[10] = NpcDefinition(
            id = 10, name = "Shaman", behavior = NpcBehavior.ATTACK_ON_SIGHT, aggroRangeTiles = 5,
            vitals = mapOf("hp" to 100), attributes = mapOf("str" to 5, "def" to 5, "int" to 15, "agi" to 5, "vit" to 10),
            spellIds = listOf(5)
        )
        sim.registerMap(
            MapDefinition(
                id = 1, name = "Marsh", width = 20, height = 20,
                npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 10, spawnX = 5, spawnY = 5, respawnTicks = 100))
            )
        )
    }

    @Test
    fun `a spell with a wind-up is announced first and lands later`() {
        val sim = WorldSimulation(SeededRandomSource(1L), game = game(NpcCombatDefinition(spellWindUpTicks = 10)))
        caster(sim)
        val start = hero(sim, 5, 8).vitals["hp"]!!.cur
        val events = sim.stepTick()
        assertTrue(
            events.any { it is WorldSimulationEvent.FloatingTextEmitted && it.text.startsWith("Casting Shadow Bolt") },
            "the player is warned"
        )
        assertEquals(5, sim.mapNpcs[1]!!.first().castingSpellId)
        assertEquals(start, sim.players["hero"]!!.vitals["hp"]!!.cur, "nothing lands during the wind-up")
        repeat(11) { sim.stepTick() }
        assertTrue(sim.players["hero"]!!.vitals["hp"]!!.cur < start, "the spell lands when the wind-up ends")
        assertNull(sim.mapNpcs[1]!!.first().castingSpellId)
    }

    @Test
    fun `stepping out of range during the wind-up makes the spell fizzle`() {
        val sim = WorldSimulation(SeededRandomSource(1L), game = game(NpcCombatDefinition(spellWindUpTicks = 10)))
        caster(sim)
        val start = hero(sim, 5, 8).vitals["hp"]!!.cur
        sim.stepTick()
        val moved = sim.players["hero"]!!.let { it.copy(position = WorldPosition.fromTile(1, 5, 16, Direction.DOWN)) }
        sim.spawnOrUpdatePlayer(moved)
        val events = (0 until 12).flatMap { sim.stepTick() }
        assertTrue(events.any { it is WorldSimulationEvent.FloatingTextEmitted && it.text.endsWith("fizzles") })
        assertEquals(start, sim.players["hero"]!!.vitals["hp"]!!.cur)
    }

    @Test
    fun `a caster that keeps its distance does not walk into melee while the spell recharges`() {
        val sim = WorldSimulation(
            SeededRandomSource(1L),
            game = game(NpcCombatDefinition(spellWindUpTicks = 4, casterKeepsDistance = true))
        )
        caster(sim)
        hero(sim, 5, 8)
        repeat(60) { sim.stepTick() }
        val npc = sim.mapNpcs[1]!!.first()
        assertEquals(5, npc.tileX)
        assertEquals(5, npc.tileY, "the caster stays where it can cast")
    }
}
