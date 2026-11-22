package com.morgana.domain

import com.morgana.domain.entity.MarketListingDocument
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.market.MarketRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketRulesTest {
    private val gold = ItemDefinition(id = 1, name = "Gold", type = ItemType.CURRENCY, stackable = true, maxStack = 1_000_000)
    private val gem = ItemDefinition(id = 2, name = "Gem")
    private fun listing(price: Int = 100, amount: Int = 1, createdAt: Long = 0) =
        MarketListingDocument("l1", "seller", "Sam", ItemStack(2, amount), price, createdAt)

    @Test
    fun `the currency is item 1 when it is one, else the lowest currency item`() {
        assertEquals(gold, MarketRules.currencyItem(mapOf(1 to gold, 2 to gem)))
        val coin = gold.copy(id = 5, name = "Coin")
        assertEquals(coin, MarketRules.currencyItem(mapOf(1 to gem.copy(id = 1), 5 to coin, 9 to gold.copy(id = 9))))
        assertNull(MarketRules.currencyItem(mapOf(2 to gem)))
    }

    @Test
    fun `tax rounds down and proceeds are the rest`() {
        assertEquals(5, MarketRules.tax(100))
        assertEquals(0, MarketRules.tax(19))
        assertEquals(95, MarketRules.proceeds(100))
        assertEquals(19, MarketRules.proceeds(19))
    }

    @Test
    fun `listing checks`() {
        assertNull(MarketRules.listingProblem(gem, gold, 50, 0))
        assertNotNull(MarketRules.listingProblem(gem, null, 50, 0))
        assertNotNull(MarketRules.listingProblem(null, gold, 50, 0))
        assertNotNull(MarketRules.listingProblem(gold, gold, 50, 0))
        assertNotNull(MarketRules.listingProblem(gem.copy(boundOnPickup = true), gold, 50, 0))
        assertNotNull(MarketRules.listingProblem(gem, gold, 0, 0))
        assertNotNull(MarketRules.listingProblem(gem, gold, MarketRules.MAX_PRICE + 1, 0))
        assertNotNull(MarketRules.listingProblem(gem, gold, 50, MarketRules.MAX_LISTINGS_PER_SELLER))
    }

    @Test
    fun `listings expire after the listing time`() {
        assertFalse(MarketRules.isExpired(listing(createdAt = 10), 10 + MarketRules.LISTING_TICKS - 1))
        assertTrue(MarketRules.isExpired(listing(createdAt = 10), 10 + MarketRules.LISTING_TICKS))
    }

    @Test
    fun `a purchase pays the price and delivers the item`() {
        val bag = Inventory(maxSlots = 3).addItem(gold, 250).updatedInventory
        val bought = MarketRules.buy(listing(100, 2), "buyer", bag, gem, gold) as MarketRules.Purchase.Bought
        assertEquals(150, bought.bag.totalCountOf(1))
        assertEquals(1, bought.bag.totalCountOf(2).coerceAtMost(1))
    }

    @Test
    fun `refused purchases change nothing`() {
        val poor = Inventory(maxSlots = 3).addItem(gold, 99).updatedInventory
        assertTrue(MarketRules.buy(listing(100), "buyer", poor, gem, gold) is MarketRules.Purchase.Refused)
        val rich = Inventory(maxSlots = 3).addItem(gold, 500).updatedInventory
        assertTrue(MarketRules.buy(listing(100), "seller", rich, gem, gold) is MarketRules.Purchase.Refused, "own listing")
        assertTrue(MarketRules.buy(listing(100), "buyer", rich, null, gold) is MarketRules.Purchase.Refused)
    }

    @Test
    fun `a full bag refuses the purchase even though paying frees a slot only when the stack is used up`() {
        val full = Inventory(maxSlots = 2).addItem(gold, 100).updatedInventory.addItem(gem.copy(id = 7, name = "Rock"), 1).updatedInventory
        // Paying exactly 100 empties the gold slot, so the gem fits.
        assertTrue(MarketRules.buy(listing(100), "buyer", full, gem, gold) is MarketRules.Purchase.Bought)
        val fuller = Inventory(maxSlots = 2).addItem(gold, 150).updatedInventory.addItem(gem.copy(id = 7, name = "Rock"), 1).updatedInventory
        assertTrue(MarketRules.buy(listing(100), "buyer", fuller, gem, gold) is MarketRules.Purchase.Refused)
    }
}
