package com.morgana.client.ui

import com.morgana.domain.game.StallDefinition
import com.morgana.protocol.PublicPlayerSnapshot

/**
 * The pure side of the Stall window: turns what a player typed into the `/stall ...` chat commands the server already
 * understands, so the window and the chat commands can never disagree. A null return means the input was not usable.
 */
object StallCommands {
    const val CLOSE = "/stall close"
    const val LIST = "/stall list"

    /** A whole number of at least 1 from [text], or null. */
    fun positive(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it >= 1 }

    /** Lists the bag slot [slotIndex] (0-based here; the command counts from 1) at [price] each; blank [units] offers the whole stack. */
    fun add(slotIndex: Int, priceText: String, unitsText: String): String? {
        if (slotIndex < 0) return null
        val price = positive(priceText) ?: return null
        val unitsPart = if (unitsText.isBlank()) "" else " " + (positive(unitsText) ?: return null)
        return "/stall add ${slotIndex + 1} $price$unitsPart"
    }

    fun remove(numberText: String): String? = positive(numberText)?.let { "/stall remove $it" }

    /** Opens the stall; blank [title] keeps the default "<name>'s Stall". */
    fun open(title: String): String = ("/stall open " + title.trim()).trimEnd()

    fun view(name: String): String? = name.trim().takeIf { it.isNotEmpty() && ' ' !in it }?.let { "/stall view $it" }

    fun buy(name: String, numberText: String, unitsText: String): String? {
        val who = name.trim().takeIf { it.isNotEmpty() && ' ' !in it } ?: return null
        val number = positive(numberText) ?: return null
        val unitsPart = if (unitsText.isBlank()) "" else " " + (positive(unitsText) ?: return null)
        return "/stall buy $who $number$unitsPart"
    }

    /** True when stalls are switched on for the game and allowed on [mapId]. */
    fun availableOn(rules: StallDefinition?, mapId: Int): Boolean =
        rules != null && rules.enabled && (rules.mapIds.isEmpty() || mapId in rules.mapIds)

    /** Open stalls close enough to buy from, nearest first: [me] is the buyer's tile. */
    fun nearby(players: Collection<PublicPlayerSnapshot>, ownId: String?, myX: Int, myY: Int, range: Int): List<PublicPlayerSnapshot> =
        players.filter {
            it.stallTitle.isNotBlank() && it.characterId != ownId &&
                maxOf(kotlin.math.abs(it.position.tileX - myX), kotlin.math.abs(it.position.tileY - myY)) <= range
        }.sortedWith(compareBy({ maxOf(kotlin.math.abs(it.position.tileX - myX), kotlin.math.abs(it.position.tileY - myY)) }, { it.name }))
}
