package com.morgana.domain

import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.ShopEntry
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.replay.SimInput
import com.morgana.domain.sim.SHOP_RANGE_TILES
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.ShopSession
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Buying, selling and tidying the bag: the rules the shop and inventory windows rely on. */
class ShopAndBagTest {

    private val gold = ItemDefinition(id = 1, name = "Gold", type = ItemType.CURRENCY, stackable = true, maxStack = 9999, price = 1)
    private val potion = ItemDefinition(id = 2, name = "Potion", type = ItemType.CONSUMABLE, stackable = true, price = 20)
    private val sword = ItemDefinition(id = 3, name = "Sword", type = ItemType.WEAPON, price = 100)
    private val axe = ItemDefinition(id = 4, name = "Axe", type = ItemType.WEAPON, price = 60)
    private val helm = ItemDefinition(id = 5, name = "Helm", type = ItemType.HELMET, price = 80)
    private val bone = ItemDefinition(id = 6, name = "Bone", type = ItemType.MATERIAL, stackable = true, price = 3)
    private val relic = ItemDefinition(id = 7, name = "Relic", type = ItemType.QUEST, boundOnPickup = true, price = 500)
    private val all = listOf(gold, potion, sword, axe, helm, bone, relic)

    private val shop = ShopDefinition(
        id = 1, name = "Shop", currencyItemId = 1, buyRatePercent = 100, sellRatePercent = 50,
        entries = listOf(ShopEntry(itemId = 2, amount = 1), ShopEntry(itemId = 3, amount = 1), ShopEntry(itemId = 6, amount = 10))
    )

    private fun world(bag: List<ItemStack?>, slots: Int = 8): Pair<WorldSimulation, CharacterState> {
        val sim = WorldSimulation(SeededRandomSource(3L), game = MorganaClassic.COMPILED)
        all.forEach { sim.items[it.id] = it }
        sim.shops[1] = shop
        sim.registerMap(MapDefinition(id = 1, name = "Town", width = 20, height = 20))
        val inv = Inventory(maxSlots = slots, slots = List(slots) { bag.getOrNull(it) })
        val player = CharacterState(id = "p", accountId = "a", name = "P", position = WorldPosition.fromTile(1, 5, 5, Direction.DOWN))
        sim.spawnOrUpdatePlayer(player)
        // Set the bag after spawning: spawning grows a small bag to the game's size, and these tests want a small one.
        sim.players["p"] = sim.players.getValue("p").copy(inventory = inv)
        sim.shopSessions["p"] = ShopSession(1, 1, 5, 5)
        return sim to player
    }

    private fun WorldSimulation.bag() = players.getValue("p").inventory
    private fun WorldSimulation.messages(events: List<WorldSimulationEvent>) = events.filterIsInstance<WorldSimulationEvent.ChatEmitted>().map { it.message }

    @Test
    fun `buying needs the shop to be open and nearby`() {
        val (sim, _) = world(listOf(ItemStack(1, 100)))
        sim.shopSessions.clear()
        val closed = sim.shopBuy("p", 1, 0, 1)
        assertEquals(listOf("Open the shop first."), sim.messages(closed))
        assertEquals(100, sim.bag().totalCountOf(1), "nothing was taken")

        sim.shopSessions["p"] = ShopSession(2, 1, 5, 5) // another shop
        assertEquals(listOf("Open the shop first."), sim.messages(sim.shopBuy("p", 1, 0, 1)))

        sim.shopSessions["p"] = ShopSession(1, 1, 5 + SHOP_RANGE_TILES + 1, 5)
        assertEquals(listOf("You are too far from the shop."), sim.messages(sim.shopBuy("p", 1, 0, 1)))
        sim.shopSessions["p"] = ShopSession(1, 9, 5, 5)
        assertEquals(listOf("You are too far from the shop."), sim.messages(sim.shopBuy("p", 1, 0, 1)))

        sim.shopSessions["p"] = ShopSession(1, 1, 5 + SHOP_RANGE_TILES, 5)
        assertTrue(sim.shopBuy("p", 1, 0, 1).any { it is WorldSimulationEvent.CharacterUpdated }, "just inside the range works")
    }

    @Test
    fun `buying several takes the right gold and tells the player`() {
        val (sim, _) = world(listOf(ItemStack(1, 100)))
        val events = sim.shopBuy("p", 1, 0, 3)
        assertEquals(3, sim.bag().totalCountOf(2))
        assertEquals(100 - 60, sim.bag().totalCountOf(1))
        assertEquals(listOf("Bought 3 x Potion for 60 Gold."), sim.messages(events))
        // An entry that sells a bundle: 10 bones per purchase, 2 purchases.
        sim.shopBuy("p", 1, 2, 2)
        assertEquals(20, sim.bag().totalCountOf(6))
        assertEquals(40 - 2 * 3, sim.bag().totalCountOf(1))
    }

    @Test
    fun `shop results carry a notice kind so the client can toast them`() {
        val (sim, _) = world(listOf(ItemStack(1, 100)))
        fun notices(events: List<WorldSimulationEvent>) = events.filterIsInstance<WorldSimulationEvent.ChatEmitted>().map { it.notice }
        assertEquals(listOf("GOOD"), notices(sim.shopBuy("p", 1, 0, 1)), "a purchase is good news")
        assertEquals(listOf("WARNING"), notices(sim.shopBuy("p", 1, 1, 5)), "not enough gold is a refusal")
        sim.shopSessions.clear()
        assertEquals(listOf("WARNING"), notices(sim.shopBuy("p", 1, 0, 1)), "so is a closed shop")
    }

    @Test
    fun `not enough gold changes nothing and says so`() {
        val (sim, _) = world(listOf(ItemStack(1, 50)))
        val events = sim.shopBuy("p", 1, 1, 1) // sword costs 100
        assertEquals(listOf("You need 100 Gold for that."), sim.messages(events))
        assertEquals(50, sim.bag().totalCountOf(1))
        assertEquals(0, sim.bag().totalCountOf(3))
    }

    @Test
    fun `a bag too full for the whole purchase takes no gold and gives no items`() {
        // Four slots: gold, then three swords; buying a fourth sword has nowhere to go.
        val (sim, _) = world(listOf(ItemStack(1, 500), ItemStack(3), ItemStack(3), ItemStack(3)), slots = 4)
        val events = sim.shopBuy("p", 1, 1, 1)
        assertTrue(sim.messages(events).single().startsWith("Your bag is too full"))
        assertEquals(500, sim.bag().totalCountOf(1))
        assertEquals(3, sim.bag().totalCountOf(3))
        // Potions stack, so two of them fit in the one free slot... but only if there is a free slot.
        val (roomy, _) = world(listOf(ItemStack(1, 500), ItemStack(3), ItemStack(3)), slots = 4)
        roomy.shopBuy("p", 1, 0, 5)
        assertEquals(5, roomy.bag().totalCountOf(2))
    }

    @Test
    fun `selling pays half the price and can sell a whole stack`() {
        val (sim, _) = world(listOf(ItemStack(1, 10), ItemStack(6, 40), ItemStack(3)))
        val events = sim.shopSell("p", 1, 1, 25)
        assertEquals(15, sim.bag().totalCountOf(6))
        assertEquals(10 + 25 * 1, sim.bag().totalCountOf(1), "3 gold at 50% is 1 each (never below 1)")
        assertEquals(listOf("Sold 25 x Bone for 25 Gold."), sim.messages(events))
        sim.shopSell("p", 1, 1, 999) // more than held: sells what is there
        assertEquals(0, sim.bag().totalCountOf(6))
        sim.shopSell("p", 1, 2, 1) // the sword: 100 * 50% = 50
        assertEquals(0, sim.bag().totalCountOf(3))
        assertEquals(10 + 25 + 15 + 50, sim.bag().totalCountOf(1))
    }

    @Test
    fun `bulk selling multiple slots pays total currency in one transaction and skips unsellable slots`() {
        val (sim, _) = world(listOf(
            ItemStack(1, 10),   // slot 0: gold
            ItemStack(3),       // slot 1: sword (50 gold)
            ItemStack(4),       // slot 2: axe (30 gold)
            ItemStack(5),       // slot 3: helm (40 gold)
            ItemStack(7)        // slot 4: bound relic (skipped)
        ))
        val events = sim.shopSell("p", 1, slotIndices = listOf(1, 2, 3, 4, 0, 1))
        assertEquals(0, sim.bag().totalCountOf(3))
        assertEquals(0, sim.bag().totalCountOf(4))
        assertEquals(0, sim.bag().totalCountOf(5))
        assertEquals(1, sim.bag().totalCountOf(7), "bound item is left untouched")
        assertEquals(10 + 50 + 30 + 40, sim.bag().totalCountOf(1))
        assertEquals(listOf("Sold 3 items for 120 Gold."), sim.messages(events))
    }

    @Test
    fun `bound items and the shop's own currency cannot be sold, and the sale is refused whole if the gold does not fit`() {
        val (sim, _) = world(listOf(ItemStack(1, 10), ItemStack(7)))
        assertEquals(listOf("Relic is bound to you and cannot be sold."), sim.messages(sim.shopSell("p", 1, 1, 1)))
        assertEquals(1, sim.bag().totalCountOf(7))
        assertTrue(sim.shopSell("p", 1, 0, 5).isEmpty(), "gold is not sold for gold")
        assertEquals(10, sim.bag().totalCountOf(1))

        // A full bag with a full gold stack: selling one potion from a stack of five has no room for its 10 gold, so it stays.
        val (full, _) = world(listOf(ItemStack(1, 9999), ItemStack(2, 5), ItemStack(3), ItemStack(3)), slots = 4)
        val events = full.shopSell("p", 1, 1, 1)
        assertTrue(full.messages(events).single().startsWith("Your bag has no room for the Gold"))
        assertEquals(5, full.bag().totalCountOf(2))
        assertEquals(9999, full.bag().totalCountOf(1))
        // Selling the whole sword frees its own slot, so that one works.
        assertTrue(full.messages(full.shopSell("p", 1, 2, 1)).single().startsWith("Sold Sword"))
    }

    @Test
    fun `selling is refused away from the shop`() {
        val (sim, _) = world(listOf(ItemStack(1, 1), ItemStack(3)))
        sim.shopSessions.clear()
        assertEquals(listOf("Open the shop first."), sim.messages(sim.shopSell("p", 1, 1, 1)))
        assertEquals(1, sim.bag().totalCountOf(3))
    }

    @Test
    fun `logging out forgets the open shop`() {
        val (sim, _) = world(listOf(ItemStack(1, 1)))
        sim.removePlayer("p")
        assertTrue(sim.shopSessions.isEmpty())
    }

    // ---- sorting ----

    private val catalog = all.associateBy { it.id }

    @Test
    fun `sorting merges stacks, packs to the front and orders by type then name`() {
        val bag = Inventory(maxSlots = 12, slots = listOf(
            ItemStack(6, 30), null, ItemStack(3), ItemStack(2, 5), null, ItemStack(6, 12), ItemStack(1, 70), ItemStack(4), ItemStack(2, 7), null, ItemStack(5), null
        ))
        val sorted = bag.sorted(catalog)
        assertEquals(12, sorted.maxSlots)
        assertEquals(
            listOf(4 to 1, 3 to 1, 5 to 1, 2 to 12, 1 to 70, 6 to 42), // Axe, Sword, Helm, Potion, Gold, Bone
            sorted.slots.filterNotNull().map { it.itemId to it.amount }.let { it }
        )
        assertTrue(sorted.slots.drop(6).all { it == null }, "empty slots end up last")
        for (id in catalog.keys) assertEquals(bag.totalCountOf(id), sorted.totalCountOf(id), "item $id total is unchanged")
        assertEquals(sorted, sorted.sorted(catalog), "sorting twice changes nothing")
    }

    @Test
    fun `a stack bigger than the stack limit is split when merged, and gear keeps its durability`() {
        val bag = Inventory(maxSlots = 8, slots = listOf(ItemStack(2, 999), ItemStack(2, 999), ItemStack(2, 5), ItemStack(3, 1, durability = 42)) + List(4) { null })
        val sorted = bag.sorted(catalog)
        assertEquals(listOf(999, 999, 5), sorted.slots.filterNotNull().filter { it.itemId == 2 }.map { it.amount })
        assertEquals(42, sorted.slots.filterNotNull().first { it.itemId == 3 }.durability)
    }

    @Test
    fun `sorting through the simulation works and does nothing when already tidy`() {
        val (sim, _) = world(listOf(ItemStack(6, 5), ItemStack(3), ItemStack(6, 5)))
        val first = sim.sortInventory("p")
        assertTrue(first.any { it is WorldSimulationEvent.CharacterUpdated })
        assertEquals(listOf(3, 6), sim.bag().slots.filterNotNull().map { it.itemId })
        assertTrue(sim.sortInventory("p").isEmpty())
        assertEquals(emptyList<WorldSimulationEvent>(), SimInput.SortInventory("nobody").applyTo(sim))
    }

    // ---- bag size ----

    @Test
    fun `a bag grows but never shrinks`() {
        val bag = Inventory(maxSlots = 24, slots = List(24) { if (it == 3) ItemStack(3) else null })
        val grown = bag.grownTo(40)
        assertEquals(40, grown.maxSlots)
        assertEquals(ItemStack(3), grown.getSlot(3))
        assertEquals(24, grown.grownTo(10).let { bag.grownTo(10).maxSlots })
        assertEquals(bag, bag.grownTo(24))
        assertEquals(Inventory.MAX_SLOTS_LIMIT, bag.grownTo(5000).maxSlots)
    }

    @Test
    fun `a game's bag size is validated, and existing characters get the room at login`() {
        val tooSmall = MorganaClassic.definition.copy(inventorySlots = 4)
        assertTrue(GameDefinitionValidator.validate(tooSmall).any { it.pointer == "/inventorySlots" })
        assertTrue(GameDefinitionValidator.validate(MorganaClassic.definition.copy(inventorySlots = 40)).none { it.pointer == "/inventorySlots" })
        assertFalse(GameDefinitionValidator.validate(MorganaClassic.definition.copy(inventorySlots = 201)).isEmpty())

        val sim = WorldSimulation(SeededRandomSource(3L), com.morgana.domain.game.CompiledGame.compile(MorganaClassic.definition.copy(inventorySlots = 40)))
        sim.registerMap(MapDefinition(id = 1, name = "Town", width = 20, height = 20))
        sim.spawnOrUpdatePlayer(CharacterState(id = "old", accountId = "a", name = "Old", position = WorldPosition.fromTile(1, 5, 5, Direction.DOWN)))
        assertEquals(40, sim.players.getValue("old").inventory.maxSlots)
    }
}
