package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.HealthOrb
import com.morgana.domain.entity.MapGroundItem
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.WorldLayoutDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The rest of the world carries over a border too: loot, health orbs, and who counts as being "here". */
class CrossBorderWorldTest {

    private fun sim(playerX: Int): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(2L), game = CompiledGame.compile(MorganaClassic.COMPILED.definition.copy(worldLayout = WorldLayoutDefinition())))
        sim.items[1] = ItemDefinition(id = 1, name = "Coin", type = ItemType.MATERIAL, maxStack = 99)
        sim.registerMap(MapDefinition(id = 1, name = "West", width = 40, height = 40, worldX = 0, worldY = 0))
        sim.registerMap(MapDefinition(id = 2, name = "East", width = 40, height = 40, worldX = 40, worldY = 0))
        sim.spawnOrUpdatePlayer(CharacterState(id = "ann", accountId = "a", name = "Ann", level = 5, position = WorldPosition.fromTile(1, playerX, 10, Direction.RIGHT)))
        return sim
    }

    @Test
    fun `loot just across a border can be picked up, and leaves its own map`() {
        val s = sim(playerX = 39)
        s.mapGroundItems[2] = mutableListOf(MapGroundItem("coin-1", 2, 1, 10, ItemStack(1, 3)))
        val events = s.pickupGroundItem("ann")
        assertTrue(events.isNotEmpty(), "something was picked up")
        assertTrue(s.mapGroundItems.getValue(2).isEmpty(), "the loot is gone from the east map")
        assertEquals(3, s.players.getValue("ann").inventory.slots.filterNotNull().filter { it.itemId == 1 }.sumOf { it.amount })
        assertEquals(39, s.players.getValue("ann").position.tileX, "and the player did not move")
    }

    @Test
    fun `loot far across the border is out of reach`() {
        val s = sim(playerX = 30)
        s.mapGroundItems[2] = mutableListOf(MapGroundItem("coin-1", 2, 1, 10, ItemStack(1, 3)))
        assertTrue(s.pickupGroundItem("ann").isEmpty())
        assertEquals(1, s.mapGroundItems.getValue(2).size)
    }

    @Test
    fun `a health orb across the border heals a hurt player who walks near it`() {
        val s = sim(playerX = 39)
        val life = s.game.lifeVitalId
        val hurt = s.players.getValue("ann").let { it.copy(vitals = it.vitals + (life to VitalState(10, 100))) }
        s.spawnOrUpdatePlayer(hurt)
        s.healthOrbs[2] = mutableListOf(HealthOrb("orb-1", 1f * 32f + 16f, 10f * 32f + 16f, s.currentTick + 600, 20))
        repeat(60) { s.stepTick() }
        assertTrue(s.players.getValue("ann").vitals.getValue(life).cur > 10, "healed by the orb from the other map")
        assertTrue(s.healthOrbs.getValue(2).isEmpty(), "and the orb is used up")
        assertEquals(39, s.players.getValue("ann").position.tileX)
    }

    @Test
    fun `someone standing just across the border can be spoken to`() {
        val s = sim(playerX = 39)
        s.npcs[5] = com.morgana.domain.content.NpcDefinition(id = 5, name = "Greeter", behavior = com.morgana.domain.content.NpcBehavior.FRIENDLY_ROAM, chatGreeting = "Well met!")
        s.registerMap(MapDefinition(id = 2, name = "East", width = 40, height = 40, worldX = 40, worldY = 0, npcSpawns = listOf(com.morgana.domain.map.MapNpcSpawnSlot(0, 5, 0, 10))))
        val events = s.playerInteractFacing("ann")
        assertTrue(events.any { it is com.morgana.domain.sim.WorldSimulationEvent.ChatEmitted && it.message == "Well met!" }, "the greeter answered: $events")
    }

    @Test
    fun `a locked door just across the border is tried and reports what it needs`() {
        val s = sim(playerX = 39)
        val cells = List(40 * 40) { i -> if (i % 40 == 0 && i / 40 == 10) com.morgana.domain.map.MapCell(attribute = com.morgana.domain.map.TileAttribute.KeyDoor(keyItemId = 1)) else com.morgana.domain.map.MapCell() }
        s.registerMap(MapDefinition(id = 2, name = "East", width = 40, height = 40, worldX = 40, worldY = 0, cells = cells))
        val events = s.playerInteractFacing("ann")
        assertTrue(events.any { it is com.morgana.domain.sim.WorldSimulationEvent.ChatEmitted && it.message.startsWith("Locked!") }, "the door on the other map answered: $events")
    }

    @Test
    fun `players on either side of a border can trade and are measured across it`() {
        val s = sim(playerX = 39)
        s.spawnOrUpdatePlayer(CharacterState(id = "bob", accountId = "b", name = "Bob", level = 5, position = WorldPosition.fromTile(2, 2, 10, Direction.LEFT)))
        assertTrue(s.initiateTrade("ann", "bob").isNotEmpty(), "they are three tiles apart across the border")
        s.spawnOrUpdatePlayer(s.players.getValue("bob").copy(position = WorldPosition.fromTile(2, 39, 10, Direction.LEFT)))
        val s2 = sim(playerX = 3)
        s2.spawnOrUpdatePlayer(CharacterState(id = "bob", accountId = "b", name = "Bob", level = 5, position = WorldPosition.fromTile(2, 39, 10, Direction.LEFT)))
        assertTrue(s2.initiateTrade("ann", "bob").isEmpty(), "70 tiles apart is too far")
    }
}
