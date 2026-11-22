package com.morgana.domain.systems

import com.morgana.domain.sim.GameSystem
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.tellPlayer
import com.morgana.domain.sim.ActionGate
import com.morgana.domain.sim.ActivityInterrupter
import com.morgana.domain.sim.ChatCommandHandler
import com.morgana.domain.sim.CombatObserver
import com.morgana.domain.sim.EventCommandHandler
import com.morgana.domain.sim.PlayerActivity

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.StallDefinition
import com.morgana.domain.item.ItemStack

/** One line of a stall: [stack] is what is offered (its amount is the number of units), [unitPrice] the price of one unit. */
data class StallListing(val slotIndex: Int, val stack: ItemStack, val unitPrice: Int)

/** A player's stall: the goods they have laid out, and whether it is open for business. */
class Stall(var title: String) {
    val listings: MutableList<StallListing> = mutableListOf()
    var open: Boolean = false
}

/**
 * Player stalls as a system: while a stall is open its owner cannot move, fight, cast, use items or trade, a stall closes when
 * its owner dies or an arena match starts, and `/stall ...` runs it.
 */
object StallSystem : ActionGate, ActivityInterrupter, ChatCommandHandler, GameSystem {
    override val key: String = OptionalModule.STALLS.configKey
    override fun blocks(sim: WorldSimulation, characterId: String, activity: PlayerActivity): Boolean = sim.isStalled(characterId)

    override fun interrupt(sim: WorldSimulation, characterId: String, out: MutableList<WorldSimulationEvent>) =
        sim.closeStall(characterId, out)

    override fun handle(sim: WorldSimulation, player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>): Boolean {
        if (parts.firstOrNull()?.lowercase() != "stall") return false
        sim.stallCommand(player, parts, out)
        return true
    }
}

private const val MAX_PRICE = 100_000_000
private const val MAX_TITLE = 24

/** True while [characterId] has an open stall; they cannot move, fight, cast or use items until they close it. */
fun WorldSimulation.isStalled(characterId: String): Boolean = stalls[characterId]?.open == true

/** Shuts and forgets a character's stall (on `/stall close`, death, logout), telling the map's clients to refresh them. */
internal fun WorldSimulation.closeStall(characterId: String, out: MutableList<WorldSimulationEvent>) {
    val stall = stalls.remove(characterId) ?: return
    if (stall.open) players[characterId]?.let { out.add(WorldSimulationEvent.CharacterUpdated(it)) }
    players[characterId]?.let { out.add(WorldSimulationEvent.StallViewed(characterId, com.morgana.domain.game.StallView(it.name, stall.title, own = true, open = false, goods = emptyList()))) }
}

/** `/stall ...` chat command handler. [parts] is the command line split on spaces, with `stall` first. */
internal fun WorldSimulation.stallCommand(player: CharacterState, parts: List<String>, out: MutableList<WorldSimulationEvent>) {
    val rules = game.definition.stall?.takeIf { it.enabled }
    if (rules == null) {
        out.add(tellPlayer(player, "Stalls are not available here.", "WARN"))
        return
    }
    fun say(text: String, category: String = "INFO") { out.add(tellPlayer(player, text, category)) }
    if (!player.isAlive(game.lifeVitalId)) return
    val stall = stalls[player.id]
    when (parts.getOrNull(1)?.lowercase()) {
        "add" -> {
            if (stall?.open == true) return say("Close your stall before changing it.", "WARN")
            val slot = (parts.getOrNull(2)?.toIntOrNull() ?: return say("Usage: /stall add <bag slot> <price each> [units]", "WARN")) - 1
            val price = parts.getOrNull(3)?.toIntOrNull() ?: return say("Usage: /stall add <bag slot> <price each> [units]", "WARN")
            val held = player.inventory.getSlot(slot) ?: return say("There is nothing in that bag slot.", "WARN")
            val def = items[held.itemId] ?: return
            val units = (parts.getOrNull(4)?.toIntOrNull() ?: held.amount).coerceIn(1, held.amount)
            val current = stall ?: Stall("${player.name}'s Stall").also { stalls[player.id] = it }
            when {
                price !in 1..MAX_PRICE -> say("The price must be 1 to $MAX_PRICE.", "WARN")
                def.boundOnPickup -> say("${def.name} is bound to you and cannot be sold.", "WARN")
                current.listings.any { it.slotIndex == slot } -> say("That bag slot is already on your stall.", "WARN")
                current.listings.size >= rules.maxListings -> say("A stall holds at most ${rules.maxListings} goods.", "WARN")
                else -> {
                    current.listings.add(StallListing(slot, held.copy(amount = units), price))
                    say("Listed $units x ${def.name} at $price each (${current.listings.size}/${rules.maxListings}).", "GOOD")
                    showOwnStall(player, out)
                }
            }
        }
        "remove" -> {
            if (stall?.open == true) return say("Close your stall before changing it.", "WARN")
            val index = (parts.getOrNull(2)?.toIntOrNull() ?: return say("Usage: /stall remove <number>", "WARN")) - 1
            if (stall == null || index !in stall.listings.indices) return say("You have no such listing.", "WARN")
            stall.listings.removeAt(index)
            say("Listing removed.")
            showOwnStall(player, out)
        }
        "list" -> {
            if (stall == null || stall.listings.isEmpty()) return say("Your stall is empty. Use /stall add <bag slot> <price each>.")
            say("Your stall (${if (stall.open) "open" else "closed"}):")
            describeListings(stall).forEach { say(it) }
            showOwnStall(player, out)
        }
        "open" -> {
            if (stall == null || stall.listings.isEmpty()) return say("Add goods first with /stall add <bag slot> <price each>.", "WARN")
            if (stall.open) return say("Your stall is already open.", "WARN")
            if (rules.mapIds.isNotEmpty() && player.position.mapId !in rules.mapIds) return say("You cannot open a stall on this map.", "WARN")
            if (player.isGhost || player.isCasting) return
            val typed = parts.drop(2).joinToString(" ").trim().take(MAX_TITLE)
            if (typed.isNotEmpty()) stall.title = typed
            stall.open = true
            out.add(WorldSimulationEvent.CharacterUpdated(player))
            say("Your stall \"${stall.title}\" is open. You cannot move until you /stall close.", "GOOD")
            showOwnStall(player, out)
        }
        "close" -> {
            if (stall == null) return say("You have no stall.", "WARN")
            closeStall(player.id, out)
            say("Your stall is closed.")
        }
        "view" -> {
            val target = stallOwnerFor(player, parts.getOrNull(2), rules) ?: return say("No open stall by that name is within reach.", "WARN")
            val theirs = stalls.getValue(target.id)
            out.add(WorldSimulationEvent.StallViewed(player.id, stallViewOf(player.id, target, theirs)))
            say("${theirs.title} (${target.name}):")
            describeListings(theirs).forEach { say(it) }
            say("Buy with /stall buy ${target.name} <number> [units].")
        }
        "buy" -> buyFromStall(player, parts, rules, out)
        else -> say("Stall commands: add, remove, list, open, close, view <player>, buy <player> <number> [units].")
    }
}

/** What a client shows for [stall] (owned by [owner]) when [viewerId] looks at it. */
private fun WorldSimulation.stallViewOf(viewerId: String, owner: CharacterState, stall: Stall) = com.morgana.domain.game.StallView(
    ownerName = owner.name, title = stall.title, own = owner.id == viewerId, open = stall.open,
    goods = stall.listings.mapIndexed { i, l -> com.morgana.domain.game.StallGood(i + 1, l.stack.itemId, l.stack.plus, l.stack.amount, l.unitPrice) }
)

private fun WorldSimulation.showOwnStall(player: CharacterState, out: MutableList<WorldSimulationEvent>) {
    val stall = stalls[player.id] ?: return
    out.add(WorldSimulationEvent.StallViewed(player.id, stallViewOf(player.id, players[player.id] ?: player, stall)))
}

private fun WorldSimulation.describeListings(stall: Stall): List<String> =
    stall.listings.mapIndexed { i, l ->
        val def = items[l.stack.itemId]
        val plus = if (l.stack.plus > 0) " +${l.stack.plus}" else ""
        "${i + 1}. ${l.stack.amount} x ${def?.name ?: "item ${l.stack.itemId}"}$plus at ${l.unitPrice} each"
    }

/** The open stall's owner named [name] who stands on [buyer]'s map within the stall's range, or null. */
private fun WorldSimulation.stallOwnerFor(buyer: CharacterState, name: String?, rules: StallDefinition): CharacterState? {
    if (name == null) return null
    val owner = players.values.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
    if (owner.id == buyer.id || !isStalled(owner.id) || owner.position.mapId != buyer.position.mapId) return null
    return owner.takeIf { it.position.tileCoord.chebyshevDistanceTo(buyer.position.tileCoord) <= rules.rangeTiles }
}

private fun WorldSimulation.buyFromStall(buyer: CharacterState, parts: List<String>, rules: StallDefinition, out: MutableList<WorldSimulationEvent>) {
    fun say(text: String, category: String = "WARN") { out.add(tellPlayer(buyer, text, category)) }
    if (buyer.isGhost || isStalled(buyer.id)) return say("You cannot buy right now.")
    val seller = stallOwnerFor(buyer, parts.getOrNull(2), rules) ?: return say("No open stall by that name is within reach.")
    val stall = stalls.getValue(seller.id)
    val index = (parts.getOrNull(3)?.toIntOrNull() ?: return say("Usage: /stall buy <player> <number> [units]")) - 1
    val listing = stall.listings.getOrNull(index) ?: return say("That stall has no such listing.")
    val units = (parts.getOrNull(4)?.toIntOrNull() ?: 1).coerceAtLeast(1)
    val def = items[listing.stack.itemId] ?: return

    // The goods must still be where the seller listed them.
    val held = seller.inventory.getSlot(listing.slotIndex)
    if (held == null || held.itemId != listing.stack.itemId || held.amount < listing.stack.amount) {
        stall.listings.removeAt(index)
        return say("That listing is no longer available.")
    }
    if (units > listing.stack.amount) return say("Only ${listing.stack.amount} are for sale.")
    val total = listing.unitPrice.toLong() * units
    if (total > MAX_PRICE) return say("That is too much to buy at once.")
    val currency = items[rules.currencyItemId] ?: return
    val afterPay = buyer.inventory.removeByItemId(currency.id, total.toInt())
        ?: return say("You need $total ${currency.name} for that.")
    val (sellerBag, taken) = seller.inventory.removeFromSlot(listing.slotIndex, units) ?: return say("That listing is no longer available.")
    val received = afterPay.addStack(def, taken)
    if (received.overflowAmount > 0) return say("Your bag is too full for that.")
    val paid = sellerBag.addItem(currency, total.toInt())
    if (paid.overflowAmount > 0) return say("The seller's bag is too full to take payment.")

    val newBuyer = buyer.copy(inventory = received.updatedInventory)
    val newSeller = seller.copy(inventory = paid.updatedInventory)
    players[buyer.id] = newBuyer
    players[seller.id] = newSeller
    val left = listing.stack.amount - units
    if (left <= 0) stall.listings.removeAt(index) else stall.listings[index] = listing.copy(stack = listing.stack.copy(amount = left))
    out.add(WorldSimulationEvent.CharacterUpdated(newBuyer))
    out.add(WorldSimulationEvent.CharacterUpdated(newSeller))
    out.add(WorldSimulationEvent.StallViewed(buyer.id, stallViewOf(buyer.id, newSeller, stall)))
    out.add(WorldSimulationEvent.StallViewed(seller.id, stallViewOf(seller.id, newSeller, stall)))
    out.add(tellPlayer(newBuyer, "Bought $units x ${def.name} from ${seller.name} for $total ${currency.name}.", "GOOD"))
    out.add(tellPlayer(newSeller, "${buyer.name} bought $units x ${def.name} for $total ${currency.name}.", "GOOD"))
}
