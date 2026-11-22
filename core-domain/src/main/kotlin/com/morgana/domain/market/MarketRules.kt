package com.morgana.domain.market

import com.morgana.domain.entity.MarketListingDocument
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType

/**
 * Pure rules for the player market: what may be listed, the sale tax, when a listing runs out, and how a purchase
 * moves currency and the item between bags. The server stores the [MarketListingDocument]s and pays the seller by mail.
 */
object MarketRules {
    const val MAX_LISTINGS_PER_SELLER = 10
    const val MAX_PRICE = 1_000_000_000

    /** Most listings sent to a client at once (the newest ones). */
    const val MAX_VISIBLE = 200

    /** A listing lasts seven days of server time (20 ticks a second); an unsold item then returns to its seller by mail. */
    const val LISTING_TICKS: Long = 7L * 24 * 60 * 60 * 20

    /** Share of the price the market keeps, in percent (rounded down). */
    const val TAX_PERCENT = 5

    /** The game's currency: item 1 when it is a currency item, else the lowest-numbered currency item. Null when there is none. */
    fun currencyItem(items: Map<Int, ItemDefinition>): ItemDefinition? =
        items[1]?.takeIf { it.type == ItemType.CURRENCY } ?: items.values.filter { it.type == ItemType.CURRENCY }.minByOrNull { it.id }

    fun tax(price: Int): Int = (price.toLong() * TAX_PERCENT / 100).toInt()

    /** What the seller receives. */
    fun proceeds(price: Int): Int = price - tax(price)

    fun isExpired(listing: MarketListingDocument, nowTick: Long): Boolean = nowTick - listing.createdAtTick >= LISTING_TICKS

    /** Why [item] cannot be listed at [price], or null when it can. [sellerListingCount] is how many the seller already has up. */
    fun listingProblem(item: ItemDefinition?, currency: ItemDefinition?, price: Int, sellerListingCount: Int): String? = when {
        currency == null -> "This world has no currency, so nothing can be sold."
        item == null -> "That item does not exist."
        item.type == ItemType.CURRENCY -> "Currency cannot be sold on the market."
        item.boundOnPickup -> "${item.name} is bound to you and cannot be sold."
        price < 1 || price > MAX_PRICE -> "The price must be between 1 and $MAX_PRICE ${currency.name}."
        sellerListingCount >= MAX_LISTINGS_PER_SELLER -> "You already have $MAX_LISTINGS_PER_SELLER listings up."
        else -> null
    }

    sealed interface Purchase {
        /** [bag] is the buyer's bag after paying and receiving the item. */
        data class Bought(val bag: Inventory) : Purchase
        data class Refused(val reason: String) : Purchase
    }

    /** Pays [listing]'s price from [bag] and puts the item in. All or nothing: a refusal leaves everything as it was. */
    fun buy(listing: MarketListingDocument, buyerCharacterId: String, bag: Inventory, itemDef: ItemDefinition?, currency: ItemDefinition?): Purchase {
        if (itemDef == null || currency == null) return Purchase.Refused("That listing can no longer be bought.")
        if (listing.sellerCharacterId == buyerCharacterId) return Purchase.Refused("That is your own listing; cancel it instead.")
        val paid = bag.removeByItemId(currency.id, listing.priceCurrencyAmount)
            ?: return Purchase.Refused("You need ${listing.priceCurrencyAmount} ${currency.name} for that.")
        if (paid.roomFor(itemDef) < listing.item.amount) return Purchase.Refused("Your bag is too full for ${itemDef.name} x${listing.item.amount}.")
        val added = paid.addStack(itemDef, listing.item)
        if (added.overflowAmount > 0) return Purchase.Refused("Your bag is too full for ${itemDef.name} x${listing.item.amount}.")
        return Purchase.Bought(added.updatedInventory)
    }
}
