package com.morgana.domain

import com.morgana.domain.content.NpcDropEntry
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.content.ResourceCategory
import com.morgana.domain.content.ResourceDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.ToolType
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MiningTest {
    private val pickaxe = ItemDefinition(id = 10, name = "Pickaxe", type = ItemType.TOOL, toolType = ToolType.PICKAXE)
    private val ore = ItemDefinition(id = 11, name = "Iron Ore", type = ItemType.MATERIAL, maxStack = 99)
    private val gem = ItemDefinition(id = 12, name = "Kylin Gem", type = ItemType.MATERIAL, maxStack = 99)

    private fun vein(bonus: List<NpcDropEntry>) = ResourceDefinition(
        id = 1, name = "Iron Vein", category = ResourceCategory.ORE_VEIN, requiredTool = ToolType.PICKAXE, rewardItemId = 11,
        minYield = 2, maxYield = 2, maxHealth = 2, respawnTicks = 100, swingText = "Mine!", bonusDrops = bonus
    )

    private fun sim(bonus: List<NpcDropEntry>, withPickaxe: Boolean): WorldSimulation {
        val s = WorldSimulation(SeededRandomSource(8L), game = CompiledGame.compile(MorganaClassic.definition))
        s.resources[1] = vein(bonus)
        s.registerMap(MapDefinition(id = 1, name = "Mine", width = 10, height = 10).withCell(6, 5, MapCell(attribute = TileAttribute.ResourceNode(1))))
        for (d in listOf(pickaxe, ore, gem)) s.items[d.id] = d
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "p1", accountId = "a", name = "Miner", position = WorldPosition.fromTile(1, 5, 5, Direction.RIGHT),
                inventory = if (withPickaxe) Inventory().addItem(pickaxe, 1).updatedInventory else Inventory()
            )
        )
        return s
    }

    private fun WorldSimulation.count(id: Int) = players.getValue("p1").inventory.totalCountOf(id)

    @Test
    fun `a miner with a pickaxe breaks a vein over several swings and gets ore`() {
        val s = sim(emptyList(), withPickaxe = true)
        val first = s.playerInteractFacing("p1")
        assertTrue(first.filterIsInstance<WorldSimulationEvent.FloatingTextEmitted>().any { it.text.startsWith("Mine!") }, "the swing text is the resource's own")
        assertEquals(0, s.count(11), "one swing is not enough")
        // Rapid-fire swing within the cooldown is rate-limited
        val tooSoon = s.playerInteractFacing("p1")
        assertTrue(tooSoon.isEmpty(), "mining is rate-limited within HARVEST_COOLDOWN_TICKS")
        assertEquals(0, s.count(11))

        repeat(com.morgana.domain.sim.HARVEST_COOLDOWN_TICKS.toInt()) { s.stepTick() }
        s.playerInteractFacing("p1")
        assertEquals(2, s.count(11))

        repeat(com.morgana.domain.sim.HARVEST_COOLDOWN_TICKS.toInt()) { s.stepTick() }
        s.playerInteractFacing("p1")
        assertEquals(2, s.count(11), "the vein is empty until it grows back")
    }

    @Test
    fun `no pickaxe means no ore`() {
        val s = sim(emptyList(), withPickaxe = false)
        repeat(3) { s.playerInteractFacing("p1") }
        assertEquals(0, s.count(11))
    }

    @Test
    fun `bonus drops come with the last swing only at their chance`() {
        val always = sim(listOf(NpcDropEntry(itemId = 12, minAmount = 1, maxAmount = 1, chanceBasisPoints = 10_000)), withPickaxe = true)
        always.playerInteractFacing("p1")
        repeat(com.morgana.domain.sim.HARVEST_COOLDOWN_TICKS.toInt()) { always.stepTick() }
        always.playerInteractFacing("p1")
        assertEquals(1, always.count(12), "a sure bonus is found")

        val never = sim(listOf(NpcDropEntry(itemId = 12, minAmount = 1, maxAmount = 1, chanceBasisPoints = 1)), withPickaxe = true)
        never.playerInteractFacing("p1")
        repeat(com.morgana.domain.sim.HARVEST_COOLDOWN_TICKS.toInt()) { never.stepTick() }
        never.playerInteractFacing("p1")
        assertEquals(0, never.count(12), "a 0.01% bonus did not turn up on this seed")
        assertEquals(2, never.count(11))
    }
}
