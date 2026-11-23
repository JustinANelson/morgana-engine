package com.morgana.client.state

import com.morgana.domain.entity.MapGroundItem
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoLootModelTest {
    private val gold = ItemDefinition(id = 1, name = "Gold", type = ItemType.CURRENCY, stackable = true, maxStack = 9999)
    private val sword = ItemDefinition(id = 3, name = "Sword", type = ItemType.WEAPON)
    private val items = listOf(gold, sword).associateBy { it.id }

    private fun drop(id: String, item: Int, x: Int = 5, y: Int = 5, owner: String? = null, amount: Int = 1) =
        MapGroundItem(id, 1, x, y, ItemStack(item, amount), ownerCharacterId = owner)

    private fun empty(size: Int = 4) = Inventory(maxSlots = size, slots = List(size) { null })
    private fun full(size: Int = 2) = Inventory(maxSlots = size, slots = List(size) { ItemStack(3) })

    private fun AutoLootModel.ask(ground: List<MapGroundItem>, inv: Inventory = empty(), now: Long = 0, on: Boolean = true, alive: Boolean = true, x: Int = 5) =
        shouldPickUp(on, alive, "me", 1, x, 5, ground, inv, items, now)

    @Test
    fun `loot in reach that fits is picked up, loot out of reach or on another map is not`() {
        assertTrue(AutoLootModel().ask(listOf(drop("a", 3))))
        assertFalse(AutoLootModel().ask(listOf(drop("a", 3, x = 9))), "four tiles away")
        assertFalse(AutoLootModel().ask(listOf(MapGroundItem("a", 2, 5, 5, ItemStack(3)))), "other map")
    }

    @Test
    fun `it does nothing when switched off, dead or without a bag`() {
        val g = listOf(drop("a", 3))
        assertFalse(AutoLootModel().ask(g, on = false))
        assertFalse(AutoLootModel().ask(g, alive = false))
        assertFalse(AutoLootModel().shouldPickUp(true, true, "me", 1, 5, 5, g, null, items, 0))
    }

    @Test
    fun `loot owned by someone else is left alone`() {
        assertFalse(AutoLootModel().ask(listOf(drop("a", 3, owner = "other"))))
        assertTrue(AutoLootModel().ask(listOf(drop("a", 3, owner = "me"))))
    }

    @Test
    fun `a full bag stops it unless the item stacks onto what is held`() {
        assertFalse(AutoLootModel().ask(listOf(drop("a", 3)), inv = full()), "no room for a sword")
        val goldBag = Inventory(maxSlots = 2, slots = listOf(ItemStack(1, 10), ItemStack(3)))
        assertTrue(AutoLootModel().ask(listOf(drop("g", 1)), inv = goldBag), "gold joins the stack")
    }

    @Test
    fun `the same loot is not asked for again until it changes or the wait passes`() {
        val m = AutoLootModel()
        val g = listOf(drop("a", 3))
        assertTrue(m.ask(g, now = 0))
        assertFalse(m.ask(g, now = 1_000), "still there: the server refused or has not answered")
        assertTrue(m.ask(g, now = AutoLootModel.RETRY_MS), "tries again after the wait")
        assertTrue(m.ask(g + drop("b", 3, x = 6), now = AutoLootModel.RETRY_MS + 10), "new loot changes the picture")
    }

    @Test
    fun `walking away and coming back asks again`() {
        val m = AutoLootModel()
        val g = listOf(drop("a", 3))
        assertTrue(m.ask(g, now = 0))
        assertFalse(m.ask(g, now = 100, x = 20))
        assertTrue(m.ask(g, now = 200), "the signature was cleared while nothing was in reach")
    }

    @Test
    fun `the gather pose plays only for loot the player could take`() {
        val g = listOf(drop("a", 3))
        assertTrue(AutoLootModel.lootInReach(g, "me", 1, 5, 5))
        assertTrue(AutoLootModel.lootInReach(g, "me", 1, 6, 5), "one tile away is in reach")
        assertFalse(AutoLootModel.lootInReach(g, "me", 1, 9, 5), "out of reach")
        assertFalse(AutoLootModel.lootInReach(g, "me", 2, 5, 5), "other map")
        assertFalse(AutoLootModel.lootInReach(listOf(drop("a", 3, owner = "other")), "me", 1, 5, 5), "owned by someone else")
        assertTrue(AutoLootModel.lootInReach(listOf(drop("a", 3, owner = "me")), "me", 1, 5, 5))
        assertFalse(AutoLootModel.lootInReach(emptyList(), "me", 1, 5, 5), "nothing on the ground")
    }
}
