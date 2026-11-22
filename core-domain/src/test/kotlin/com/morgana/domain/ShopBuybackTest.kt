package com.morgana.domain

import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.ShopEntry
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SHOP_BUYBACK_LIMIT
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.ShopSession
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.openShopFor
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The shop's buy-back list: what is remembered, what buying back costs, and what is refused. */
class ShopBuybackTest {
    private val gold = ItemDefinition(id = 1, name = "Gold", type = ItemType.CURRENCY, stackable = true, maxStack = 9999, price = 1)
    private val potion = ItemDefinition(id = 2, name = "Potion", type = ItemType.CONSUMABLE, stackable = true, price = 20)
    private val sword = ItemDefinition(id = 3, name = "Sword", type = ItemType.WEAPON, price = 100)
    private val relic = ItemDefinition(id = 7, name = "Relic", type = ItemType.QUEST, boundOnPickup = true, price = 500)
    private val shop = ShopDefinition(id = 1, name = "Shop", currencyItemId = 1, buyRatePercent = 100, sellRatePercent = 50, entries = listOf(ShopEntry(itemId = 2)))
    private val otherShop = shop.copy(id = 2, name = "Other")

    private fun world(bag: List<ItemStack?>, slots: Int = 8): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(3L), game = MorganaClassic.COMPILED)
        listOf(gold, potion, sword, relic).forEach { sim.items[it.id] = it }
        sim.shops[1] = shop
        sim.shops[2] = otherShop
        sim.registerMap(MapDefinition(id = 1, name = "Town", width = 20, height = 20))
        sim.spawnOrUpdatePlayer(CharacterState(id = "p", accountId = "a", name = "P", position = WorldPosition.fromTile(1, 5, 5, Direction.DOWN)))
        sim.players["p"] = sim.players.getValue("p").copy(inventory = Inventory(maxSlots = slots, slots = List(slots) { bag.getOrNull(it) }))
        sim.shopSessions["p"] = ShopSession(1, 1, 5, 5)
        return sim
    }

    private fun WorldSimulation.bag() = players.getValue("p").inventory
    private fun WorldSimulation.list() = shopBuybacks["p"]?.entries.orEmpty()
    private fun WorldSimulation.said(events: List<WorldSimulationEvent>) = events.filterIsInstance<WorldSimulationEvent.ChatEmitted>().map { it.message }

    @Test
    fun `a sale is remembered at the price the shop paid, and buying it back restores the exact stack`() {
        val sim = world(listOf(ItemStack(1, 10), ItemStack(3, durability = 40)))
        val sold = sim.shopSell("p", 1, 1, 1)
        assertTrue(sold.any { it is WorldSimulationEvent.ShopBuybackChanged })
        assertEquals(60, sim.bag().totalCountOf(1))
        assertEquals(0, sim.bag().totalCountOf(3))
        assertEquals(50, sim.list().single().price)

        sim.shopBuyback("p", 1, 0)
        assertEquals(10, sim.bag().totalCountOf(1))
        assertEquals(40, sim.bag().slots.filterNotNull().first { it.itemId == 3 }.durability)
        assertTrue(sim.list().isEmpty())
    }

    @Test
    fun `quick-selling several stacks remembers each one`() {
        val sim = world(listOf(ItemStack(1, 5), ItemStack(2, 4), ItemStack(3)))
        sim.shopSell("p", 1, slotIndices = listOf(1, 2))
        assertEquals(listOf(2, 3), sim.list().map { it.stack.itemId })
        assertEquals(listOf(40, 50), sim.list().map { it.price })
    }

    @Test
    fun `buying back is refused when you cannot pay, and nothing changes`() {
        val sim = world(listOf(ItemStack(3)))
        sim.shopSell("p", 1, 0, 1)
        sim.players["p"] = sim.players.getValue("p").copy(inventory = Inventory(maxSlots = 8, slots = List(8) { null }))
        val said = sim.said(sim.shopBuyback("p", 1, 0))
        assertEquals(listOf("You need 50 Gold to buy that back."), said)
        assertEquals(1, sim.list().size)
    }

    @Test
    fun `buying back is refused when the bag is full, and the gold is kept`() {
        val sim = world(listOf(ItemStack(1, 1000), ItemStack(3)), slots = 2)
        sim.shopSell("p", 1, 1, 1)
        sim.players["p"] = sim.players.getValue("p").copy(inventory = Inventory(maxSlots = 2, slots = listOf(ItemStack(1, 1000), ItemStack(2))))
        val said = sim.said(sim.shopBuyback("p", 1, 0))
        assertEquals(listOf("Your bag is too full for that. Sell or drop something first."), said)
        assertEquals(1000, sim.bag().totalCountOf(1))
        assertEquals(1, sim.list().size)
    }

    @Test
    fun `the list keeps the newest few and needs the shop open`() {
        val total = SHOP_BUYBACK_LIMIT + 2
        val sim = world(List(total) { ItemStack(3, durability = it + 1) }, slots = total)
        repeat(total) { sim.shopSell("p", 1, it, 1) }
        assertEquals(SHOP_BUYBACK_LIMIT, sim.list().size)
        assertEquals(3, sim.list().first().stack.durability) // the two oldest sales were forgotten
        assertEquals(total, sim.list().last().stack.durability)
        sim.shopSessions.clear()
        assertEquals(listOf("Open the shop first."), sim.said(sim.shopBuyback("p", 1, 0)))
    }

    @Test
    fun `a bad index or another shop changes nothing, and a different shop starts a fresh list`() {
        val sim = world(listOf(ItemStack(3)))
        sim.shopSell("p", 1, 0, 1)
        assertTrue(sim.shopBuyback("p", 1, 5).isEmpty())
        assertTrue(sim.shopBuyback("p", 1, -1).isEmpty())
        sim.shopSessions["p"] = ShopSession(2, 1, 5, 5)
        assertTrue(sim.shopBuyback("p", 2, 0).isEmpty())
        val opened = mutableListOf<WorldSimulationEvent>()
        sim.openShopFor("p", 2, opened)
        assertTrue(sim.list().isEmpty())
        assertEquals(emptyList<Any>(), opened.filterIsInstance<WorldSimulationEvent.ShopBuybackChanged>().single().entries)
    }

    @Test
    fun `bound items are never sold, so never remembered`() {
        val sim = world(listOf(ItemStack(7)))
        sim.shopSell("p", 1, 0, 1)
        assertTrue(sim.list().isEmpty())
    }

    @Test
    fun `logging out forgets the list`() {
        val sim = world(listOf(ItemStack(3)))
        sim.shopSell("p", 1, 0, 1)
        sim.removePlayer("p")
        assertTrue(sim.shopBuybacks.isEmpty())
    }
}
