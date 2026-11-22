package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.NpcDropEntry
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpellCastingAndLootTest {

    @Test
    fun `spell with cast time delays resolution until ticks elapsed and sets cooldown`() {
        val sim = WorldSimulation(SeededRandomSource(12345L), game = MorganaClassic.COMPILED)

        val slime = NpcDefinition(
            id = 1,
            name = "Practice Dummy",
            behavior = NpcBehavior.PASSIVE,
            vitals = mapOf("hp" to 100),
            attributes = mapOf("str" to 1, "def" to 1, "int" to 1, "agi" to 1, "vit" to 1)
        )
        sim.npcs[1] = slime

        val map = MapDefinition(
            id = 1,
            name = "Mage Tower",
            width = 10,
            height = 10,
            npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 1, spawnX = 5, spawnY = 5, respawnTicks = 100))
        )
        sim.registerMap(map)

        val pyroblast = SpellDefinition(
            id = 10,
            name = "Pyroblast",
            targetType = SpellTargetType.SINGLE_TARGET,
            costs = mapOf("mp" to 15),
            cooldownTicks = 20,
            castTimeTicks = 10,
            basePower = 50,
            rangeTiles = 3
        )
        sim.spells[10] = pyroblast

        val wizard = CharacterState(
            id = "wiz",
            accountId = "acc1",
            name = "Gandalf",
            attributes = mapOf("str" to 10, "def" to 10, "int" to 25, "agi" to 10, "vit" to 10),
            position = WorldPosition.fromTile(1, 5, 4, Direction.DOWN),
            knownSpellIds = listOf(10)
        ).recalculateVitals(sim.game, sim.items, refill = true)
        sim.spawnOrUpdatePlayer(wizard)
        sim.stepTick()

        val mpBefore = sim.players["wiz"]!!.vitals["mp"]?.cur ?: 0

        // Start casting Pyroblast at the Dummy facing DOWN at (5,5)
        val startEvents = sim.playerCastSpell("wiz", 10)
        assertTrue(startEvents.any { it is WorldSimulationEvent.SpellCastStarted }, "Should emit SpellCastStarted: $startEvents")
        assertTrue(startEvents.none { it is WorldSimulationEvent.SpellCooldownStarted }, "the cooldown starts when the cast finishes, not when it begins")
        val wizCasting = sim.players["wiz"]!!
        assertTrue(wizCasting.isCasting, "Player should be in casting state")
        assertEquals(10, wizCasting.castingSpellId)
        assertEquals(10, wizCasting.castTotalTicks)
        assertEquals(mpBefore - 15, wizCasting.vitals["mp"]?.cur)

        // Dummy should NOT have taken damage yet!
        val dummyBefore = sim.mapNpcs[1]!!.first()
        assertEquals(100, dummyBefore.vitals["hp"]?.cur)

        // Second cast attempt while casting must fail
        val duplicateCast = sim.playerCastSpell("wiz", 10)
        assertTrue(duplicateCast.isEmpty(), "Cannot cast again while already casting")

        // Advance 5 ticks: should still be casting
        repeat(5) { sim.stepTick() }
        assertTrue(sim.players["wiz"]!!.isCasting)

        // Advance 5 more ticks (total 10 ticks = cast finishes)
        val tickEvents = mutableListOf<WorldSimulationEvent>()
        repeat(5) {
            tickEvents.addAll(sim.stepTick())
        }

        val wizFinished = sim.players["wiz"]!!
        assertFalse(wizFinished.isCasting, "Player should no longer be casting")
        assertNull(wizFinished.castingSpellId)
        assertTrue(wizFinished.spellCooldowns[10] != null, "Cooldown should be set on finish")
        assertTrue(tickEvents.any { it is WorldSimulationEvent.SpellCast }, "Should emit SpellCast on finish: $tickEvents")
        assertTrue(
            tickEvents.contains(WorldSimulationEvent.SpellCooldownStarted("wiz", 10, 20)),
            "the client is told the exact cooldown when the cast finishes: $tickEvents"
        )

        // Dummy took the damage now!
        val dummyAfter = sim.mapNpcs[1]!!.first()
        assertTrue((dummyAfter.vitals["hp"]?.cur ?: 100) < 100, "Dummy should have taken spell damage on cast complete")
    }

    @Test
    fun `movement interrupts an active spell cast`() {
        val sim = WorldSimulation(SeededRandomSource(12345L), game = MorganaClassic.COMPILED)

        val map = MapDefinition(
            id = 1,
            name = "Field",
            width = 10,
            height = 10
        )
        sim.registerMap(map)

        val ritual = SpellDefinition(
            id = 20,
            name = "Long Ritual",
            targetType = SpellTargetType.SELF,
            costs = mapOf("mp" to 5),
            cooldownTicks = 10,
            castTimeTicks = 20,
            basePower = 20
        )
        sim.spells[20] = ritual

        val player = CharacterState(
            id = "p1",
            accountId = "acc1",
            name = "Runner",
            attributes = mapOf("str" to 10, "def" to 10, "int" to 15, "agi" to 10, "vit" to 10),
            position = WorldPosition.fromTile(1, 2, 2, Direction.DOWN),
            knownSpellIds = listOf(20)
        ).recalculateVitals(sim.game, sim.items, refill = true)
        sim.spawnOrUpdatePlayer(player)
        sim.stepTick()

        val castEvents = sim.playerCastSpell("p1", 20)
        assertTrue(castEvents.any { it is WorldSimulationEvent.SpellCastStarted })
        assertTrue(sim.players["p1"]!!.isCasting)

        // Player moves while casting
        val moveEvents = sim.movePlayer("p1", Direction.RIGHT)
        assertTrue(moveEvents.any { it is WorldSimulationEvent.SpellCastInterrupted }, "Movement should interrupt cast: $moveEvents")

        val pAfterMove = sim.players["p1"]!!
        assertFalse(pAfterMove.isCasting, "Player casting state should be cleared after interrupt")
        assertNull(pAfterMove.castingSpellId)
    }

    @Test
    fun `npc multi drop loot table drops all matching entries`() {
        val sim = WorldSimulation(SeededRandomSource(12345L), game = MorganaClassic.COMPILED)

        val sword = ItemDefinition(id = 101, name = "Iron Sword", type = ItemType.WEAPON)
        val shield = ItemDefinition(id = 102, name = "Wooden Shield", type = ItemType.ARMOR)
        val potion = ItemDefinition(id = 103, name = "Health Potion", type = ItemType.CONSUMABLE)
        sim.items[101] = sword
        sim.items[102] = shield
        sim.items[103] = potion

        val bossDef = NpcDefinition(
            id = 99,
            name = "Loot Goblin",
            behavior = NpcBehavior.PASSIVE,
            vitals = mapOf("hp" to 10),
            attributes = mapOf("str" to 1, "def" to 1, "int" to 1, "agi" to 1, "vit" to 1),
            xpReward = 50L,
            drops = listOf(
                NpcDropEntry(itemId = 101, minAmount = 1, maxAmount = 1, chanceBasisPoints = 10_000),
                NpcDropEntry(itemId = 102, minAmount = 1, maxAmount = 1, chanceBasisPoints = 10_000),
                NpcDropEntry(itemId = 103, minAmount = 3, maxAmount = 3, chanceBasisPoints = 10_000)
            )
        )
        sim.npcs[99] = bossDef

        val map = MapDefinition(
            id = 1,
            name = "Treasure Room",
            width = 10,
            height = 10,
            npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 99, spawnX = 3, spawnY = 3, respawnTicks = 100))
        )
        sim.registerMap(map)

        val player = CharacterState(
            id = "slayer",
            accountId = "acc1",
            name = "Slayer",
            attributes = mapOf("str" to 50, "def" to 10, "int" to 10, "agi" to 10, "vit" to 10),
            position = WorldPosition.fromTile(1, 3, 2, Direction.DOWN),
            lastAttackTick = -20L
        ).recalculateVitals(sim.game, sim.items, refill = true)
        sim.spawnOrUpdatePlayer(player)
        sim.stepTick()

        // Attack and kill the boss facing DOWN at (3,3)
        val attackEvents = sim.playerAttack("slayer")
        assertTrue(attackEvents.any { it is WorldSimulationEvent.NpcKilled }, "Boss should be killed")

        // Ground items on map 1 should contain all 3 drop entries!
        val groundItems = sim.mapGroundItems[1]
        assertNotNull(groundItems)
        assertEquals(3, groundItems!!.size, "All 3 loot drops should drop on death")
        val itemIds = groundItems.map { it.stack.itemId }.toSet()
        assertTrue(itemIds.containsAll(listOf(101, 102, 103)), "Ground items should contain sword, shield, and potions")
        val potDrop = groundItems.first { it.stack.itemId == 103 }
        assertEquals(3, potDrop.stack.amount, "Potion should drop 3 count")
    }

    @Test
    fun `hostile npc with offensive spell casts spell at player at range and inflicts damage`() {
        val sim = WorldSimulation(SeededRandomSource(42L), game = MorganaClassic.COMPILED)

        val bolt = SpellDefinition(
            id = 5,
            name = "Shadow Bolt",
            targetType = SpellTargetType.SINGLE_TARGET,
            cooldownTicks = 20,
            basePower = 25,
            rangeTiles = 4
        )
        sim.spells[5] = bolt

        val shaman = NpcDefinition(
            id = 10,
            name = "Lizardman Shaman",
            behavior = NpcBehavior.ATTACK_ON_SIGHT,
            aggroRangeTiles = 5,
            vitals = mapOf("hp" to 100),
            attributes = mapOf("str" to 5, "def" to 5, "int" to 15, "agi" to 5, "vit" to 10),
            spellIds = listOf(5)
        )
        sim.npcs[10] = shaman

        val map = MapDefinition(
            id = 1,
            name = "Marsh",
            width = 10,
            height = 10,
            npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 10, spawnX = 5, spawnY = 5, respawnTicks = 100))
        )
        sim.registerMap(map)

        // Player positioned 3 tiles away at (5, 2)
        val player = CharacterState(
            id = "hero",
            accountId = "acc1",
            name = "Hero",
            attributes = mapOf("str" to 10, "def" to 10, "int" to 10, "agi" to 10, "vit" to 10),
            position = WorldPosition.fromTile(1, 5, 2, Direction.DOWN)
        ).recalculateVitals(sim.game, sim.items, refill = true)
        sim.spawnOrUpdatePlayer(player)

        val startingHp = sim.players["hero"]!!.vitals["hp"]!!.cur

        // Step simulation: Shaman acquires player at range 3 and casts Shadow Bolt
        sim.stepTick()

        val afterHp = sim.players["hero"]!!.vitals["hp"]!!.cur
        assertTrue(afterHp < startingHp, "Player should take spell damage from the Shaman at range (starting: $startingHp, after: $afterHp)")
    }

    @Test
    fun `damaged npc with heal spell casts heal on itself restoring hp`() {
        val sim = WorldSimulation(SeededRandomSource(42L), game = MorganaClassic.COMPILED)

        val heal = SpellDefinition(
            id = 6,
            name = "Lesser Heal",
            targetType = SpellTargetType.SELF,
            isHealing = true,
            cooldownTicks = 20,
            basePower = 35
        )
        sim.spells[6] = heal

        val cleric = NpcDefinition(
            id = 11,
            name = "Cleric NPC",
            behavior = NpcBehavior.ATTACK_ON_SIGHT,
            aggroRangeTiles = 5,
            vitals = mapOf("hp" to 100),
            attributes = mapOf("str" to 5, "def" to 5, "int" to 15, "agi" to 5, "vit" to 10),
            spellIds = listOf(6)
        )
        sim.npcs[11] = cleric

        val map = MapDefinition(
            id = 1,
            name = "Sanctuary",
            width = 10,
            height = 10,
            npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 11, spawnX = 5, spawnY = 5, respawnTicks = 100))
        )
        sim.registerMap(map)

        val player = CharacterState(
            id = "hero",
            accountId = "acc1",
            name = "Hero",
            attributes = mapOf("str" to 10, "def" to 10, "int" to 10, "agi" to 10, "vit" to 10),
            position = WorldPosition.fromTile(1, 5, 4, Direction.DOWN)
        ).recalculateVitals(sim.game, sim.items, refill = true)
        sim.spawnOrUpdatePlayer(player)

        // Spawn NPCs and acquire target
        sim.stepTick()
        val spawnedNpc = sim.mapNpcs[1]!!.first()

        // Manually damage the NPC below 60% (e.g. 30 / 100 HP)
        val damagedNpc = spawnedNpc.copy(
            vitals = spawnedNpc.vitals + ("hp" to spawnedNpc.vitals["hp"]!!.copy(cur = 30)),
            lastSpellCastTick = -50L
        )
        sim.mapNpcs[1]!![0] = damagedNpc

        // Next tick: NPC should prioritize casting heal on itself!
        sim.stepTick()

        val healedNpc = sim.mapNpcs[1]!!.first()
        val healedHp = healedNpc.vitals["hp"]!!.cur
        assertTrue(healedHp > 30, "Damaged NPC should have cast Lesser Heal to restore HP (was 30, now $healedHp)")
    }
}
