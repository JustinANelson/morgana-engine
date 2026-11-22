package com.morgana.domain.game

import kotlinx.serialization.Serializable

/**
 * Player stalls: a character lays out items from their bag with a price each, opens a stall where they stand and then
 * cannot move, fight or cast until they close it; nearby players browse and buy. The goods stay in the seller's bag until
 * they are bought, so a closed stall, a logout or a death returns nothing because nothing left. Everything is driven by
 * chat commands (`/stall add|remove|list|open|close|view|buy`), so it replays and needs no extra client window.
 *
 * Games without this section have no stalls.
 */
@Serializable
data class StallDefinition(
    /** Easy kill-switch: set false in `game.json` to disable stalls. */
    val enabled: Boolean = true,
    /** Maps where a stall may be opened; empty means any map. */
    val mapIds: Set<Int> = emptySet(),
    /** Most different goods one stall may list. */
    val maxListings: Int = 10,
    /** How far in tiles a buyer may stand from the stall. */
    val rangeTiles: Int = 6,
    /** Item id the goods are priced in (the game's money item). */
    val currencyItemId: Int = 1
)

/** One line of a stall as a client sees it: [number] counts from 1, [amount] is the units for sale, [unitPrice] the price of one. */
@Serializable
data class StallGood(val number: Int, val itemId: Int, val plus: Int = 0, val amount: Int, val unitPrice: Int)

/**
 * The goods of one stall, sent to a player who looks at it (`/stall view`, `/stall list`, the Stall window) so the window
 * can show them. [own] is true for the viewer's own stall; [open] says whether it is open for business.
 */
@Serializable
data class StallView(val ownerName: String, val title: String, val own: Boolean, val open: Boolean, val goods: List<StallGood>)
