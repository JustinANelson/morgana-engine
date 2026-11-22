package com.morgana.domain.sim

import com.morgana.domain.content.CraftingStationType
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemRollText
import com.morgana.domain.item.ItemStack
import kotlinx.serialization.Serializable
import kotlin.math.max

/**
 * Economy: shops, bank, and crafting.
 *
 * Implemented as internal extension functions over [WorldSimulation] state;
 * the public API is exposed by thin delegating methods on [WorldSimulation].
 */

/** The most of one thing a single buy or sell request may move; keeps the arithmetic far from overflow. */
private const val MAX_TRADE_QUANTITY = 999

/** How far (in tiles) a player may walk from where they opened a shop and still use it. */
const val SHOP_RANGE_TILES = 6

/** The shop a player has open: recorded when the shop window is opened, checked on every buy and sell. */
data class ShopSession(val shopId: Int, val mapId: Int, val tileX: Int, val tileY: Int)

/** The most sold stacks a shop remembers per player; the oldest is forgotten first. */
const val SHOP_BUYBACK_LIMIT = 10

/** One thing sold to a shop: the exact [stack] (rolls, durability, sockets kept) and the [price] the shop paid, which is also the price to buy it back. */
@Serializable
data class ShopBuybackEntry(val stack: ItemStack, val price: Int)

/** What a player has sold to shop [shopId] this visit, oldest first. Opening a different shop starts a fresh list. */
data class ShopBuybackList(val shopId: Int, val entries: List<ShopBuybackEntry>)

private fun WorldSimulation.recordBuybacks(characterId: String, shopId: Int, sold: List<ShopBuybackEntry>): WorldSimulationEvent {
    val before = shopBuybacks[characterId]?.takeIf { it.shopId == shopId }?.entries.orEmpty()
    val list = ShopBuybackList(shopId, (before + sold).takeLast(SHOP_BUYBACK_LIMIT))
    shopBuybacks[characterId] = list
    return WorldSimulationEvent.ShopBuybackChanged(characterId, shopId, list.entries)
}

/** Records that [characterId] opened shop [shopId] where they stand and asks their client to show it. */
internal fun WorldSimulation.openShopFor(characterId: String, shopId: Int, out: MutableList<WorldSimulationEvent>) {
    val player = players[characterId]
    if (player != null) shopSessions[characterId] = ShopSession(shopId, player.position.mapId, player.position.tileX, player.position.tileY)
    out.add(WorldSimulationEvent.OpenUiRequested(characterId, "SHOP", shopId = shopId))
    if (player != null) {
        if (shopBuybacks[characterId]?.shopId != shopId) shopBuybacks.remove(characterId)
        out.add(WorldSimulationEvent.ShopBuybackChanged(characterId, shopId, shopBuybacks[characterId]?.entries.orEmpty()))
    }
}

/** A one-line system message to one player (chat window), for results the client cannot see any other way. */
internal fun WorldSimulation.tellPlayer(player: com.morgana.domain.entity.CharacterState, text: String, notice: String? = null): WorldSimulationEvent =
    WorldSimulationEvent.ChatEmitted(
        channel = "SYSTEM", mapId = player.position.mapId, senderId = "system", senderName = "System",
        targetCharacterId = player.id, message = text, showOverheadBubble = false, notice = notice
    )

/** Null when [player] may trade with shop [shopId] now, otherwise the reason to tell them. */
private fun WorldSimulation.shopRefusal(player: com.morgana.domain.entity.CharacterState, shopId: Int): String? {
    val session = shopSessions[player.id]
    if (session == null || session.shopId != shopId) return "Open the shop first."
    val p = player.position
    if (session.mapId != p.mapId || maxOf(Math.abs(session.tileX - p.tileX), Math.abs(session.tileY - p.tileY)) > SHOP_RANGE_TILES) {
        return "You are too far from the shop."
    }
    return null
}

internal fun WorldSimulation.shopBuyImpl(characterId: String, shopId: Int, entryIndex: Int, quantity: Int = 1): List<WorldSimulationEvent> {
    if (quantity <= 0) return emptyList()
    val player = players[characterId] ?: return emptyList()
    val shop = shops[shopId] ?: return emptyList()
    shopRefusal(player, shopId)?.let { return listOf(tellPlayer(player, it, "WARNING")) }
    val entry = shop.entries.getOrNull(entryIndex) ?: return emptyList()
    val itemDef = items[entry.itemId] ?: return emptyList()
    val count = quantity.coerceAtMost(MAX_TRADE_QUANTITY)

    val unitPrice = if (entry.priceOverride > 0) {
        entry.priceOverride
    } else {
        max(1, (itemDef.price * shop.buyRatePercent) / 100)
    }
    val totalCost = unitPrice * count
    val currencyId = if (entry.currencyItemId > 0) entry.currencyItemId else shop.currencyItemId
    val currencyName = items[currencyId]?.name ?: "gold"

    val afterPay = player.inventory.removeByItemId(currencyId, totalCost)
        ?: return listOf(tellPlayer(player, "You need $totalCost $currencyName for that.", "WARNING"))
    val addRes = afterPay.addItem(itemDef, entry.amount * count)
    // Never take payment for goods that do not fit: nothing changes unless the whole purchase does.
    if (addRes.overflowAmount > 0) return listOf(tellPlayer(player, "Your bag is too full for that. Sell or drop something first.", "WARNING"))

    val updated = player.copy(inventory = addRes.updatedInventory)
    players[characterId] = updated
    val bought = entry.amount * count
    return listOf(
        WorldSimulationEvent.CharacterUpdated(updated),
        tellPlayer(player, "Bought ${if (bought > 1) "$bought x " else ""}${itemDef.name} for $totalCost $currencyName.", "GOOD")
    )
}

internal fun WorldSimulation.shopSellImpl(
    characterId: String,
    shopId: Int,
    inventorySlotIndex: Int = -1,
    quantity: Int = 1,
    slotIndices: List<Int> = emptyList()
): List<WorldSimulationEvent> {
    val player = players[characterId] ?: return emptyList()
    val shop = shops[shopId] ?: return emptyList()
    shopRefusal(player, shopId)?.let { return listOf(tellPlayer(player, it, "WARNING")) }
    val currencyDef = items[shop.currencyItemId] ?: return emptyList()

    if (slotIndices.isNotEmpty()) {
        var workingInv = player.inventory
        var totalPayout = 0
        var totalItems = 0
        var soldStacks = 0
        var lastSoldName = ""
        val soldEntries = mutableListOf<ShopBuybackEntry>()
        for (slotIdx in slotIndices.distinct()) {
            val stack = workingInv.getSlot(slotIdx) ?: continue
            val itemDef = items[stack.itemId] ?: continue
            if (itemDef.boundOnPickup || itemDef.id == shop.currencyItemId) continue
            val sellQty = stack.amount.coerceIn(1, MAX_TRADE_QUANTITY)
            val unitValue = max(1, (ItemRollText.price(itemDef.price, stack.rolls, game.definition.loot, stack.plus) * shop.sellRatePercent) / 100)
            val (afterRemove, removed) = workingInv.removeFromSlot(slotIdx, sellQty) ?: continue
            workingInv = afterRemove
            soldEntries += ShopBuybackEntry(removed, unitValue * sellQty)
            totalPayout = (totalPayout.toLong() + unitValue.toLong() * sellQty.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            totalItems += sellQty
            soldStacks++
            lastSoldName = itemDef.name
        }
        if (soldStacks == 0) return emptyList()
        val pay = workingInv.addItem(currencyDef, totalPayout)
        if (pay.overflowAmount > 0) return listOf(tellPlayer(player, "Your bag has no room for the ${currencyDef.name}. Free a slot first.", "WARNING"))
        val updated = player.copy(inventory = pay.updatedInventory)
        players[characterId] = updated
        val message = if (soldStacks == 1) {
            "Sold ${if (totalItems > 1) "$totalItems x " else ""}$lastSoldName for $totalPayout ${currencyDef.name}."
        } else {
            "Sold $totalItems items for $totalPayout ${currencyDef.name}."
        }
        return listOf(
            WorldSimulationEvent.CharacterUpdated(updated),
            tellPlayer(player, message, "GOOD"),
            recordBuybacks(characterId, shopId, soldEntries)
        )
    }

    if (quantity <= 0) return emptyList()
    val stack = player.inventory.getSlot(inventorySlotIndex) ?: return emptyList()
    val itemDef = items[stack.itemId] ?: return emptyList()
    if (itemDef.boundOnPickup) return listOf(tellPlayer(player, "${itemDef.name} is bound to you and cannot be sold.", "WARNING"))
    if (itemDef.id == shop.currencyItemId) return emptyList()

    val sellQty = quantity.coerceIn(1, minOf(stack.amount, MAX_TRADE_QUANTITY))
    val unitValue = max(1, (ItemRollText.price(itemDef.price, stack.rolls, game.definition.loot, stack.plus) * shop.sellRatePercent) / 100)
    val totalPayout = unitValue * sellQty

    val (afterRemove, removed) = player.inventory.removeFromSlot(inventorySlotIndex, sellQty) ?: return emptyList()
    val pay = afterRemove.addItem(currencyDef, totalPayout)
    // Never give away goods for money that does not fit: the sale is refused whole.
    if (pay.overflowAmount > 0) return listOf(tellPlayer(player, "Your bag has no room for the ${currencyDef.name}. Free a slot first.", "WARNING"))
    val updated = player.copy(inventory = pay.updatedInventory)
    players[characterId] = updated
    return listOf(
        WorldSimulationEvent.CharacterUpdated(updated),
        tellPlayer(player, "Sold ${if (sellQty > 1) "$sellQty x " else ""}${itemDef.name} for $totalPayout ${currencyDef.name}.", "GOOD"),
        recordBuybacks(characterId, shopId, listOf(ShopBuybackEntry(removed, totalPayout)))
    )
}

/** Buys back one thing the player sold to this shop, for exactly what the shop paid. Refused whole if they cannot pay or it does not fit. */
internal fun WorldSimulation.shopBuybackImpl(characterId: String, shopId: Int, entryIndex: Int): List<WorldSimulationEvent> {
    val player = players[characterId] ?: return emptyList()
    val shop = shops[shopId] ?: return emptyList()
    shopRefusal(player, shopId)?.let { return listOf(tellPlayer(player, it, "WARNING")) }
    val list = shopBuybacks[characterId]?.takeIf { it.shopId == shopId } ?: return emptyList()
    val entry = list.entries.getOrNull(entryIndex) ?: return emptyList()
    val itemDef = items[entry.stack.itemId] ?: return emptyList()
    val currencyName = items[shop.currencyItemId]?.name ?: "gold"

    val afterPay = player.inventory.removeByItemId(shop.currencyItemId, entry.price)
        ?: return listOf(tellPlayer(player, "You need ${entry.price} $currencyName to buy that back.", "WARNING"))
    val added = afterPay.addStack(itemDef, entry.stack)
    if (added.overflowAmount > 0) return listOf(tellPlayer(player, "Your bag is too full for that. Sell or drop something first.", "WARNING"))

    val updated = player.copy(inventory = added.updatedInventory)
    players[characterId] = updated
    val remaining = ShopBuybackList(shopId, list.entries.filterIndexed { i, _ -> i != entryIndex })
    shopBuybacks[characterId] = remaining
    val amount = entry.stack.amount
    return listOf(
        WorldSimulationEvent.CharacterUpdated(updated),
        tellPlayer(player, "Bought back ${if (amount > 1) "$amount x " else ""}${itemDef.name} for ${entry.price} $currencyName.", "GOOD"),
        WorldSimulationEvent.ShopBuybackChanged(characterId, shopId, remaining.entries)
    )
}

/** Tidies [characterId]'s bag: merges stacks and packs items by type and name (see [com.morgana.domain.item.Inventory.sorted]). */
internal fun WorldSimulation.sortInventoryImpl(characterId: String): List<WorldSimulationEvent> {
    val player = players[characterId] ?: return emptyList()
    val sorted = player.inventory.sorted(items)
    if (sorted == player.inventory) return emptyList()
    val updated = player.copy(inventory = sorted)
    players[characterId] = updated
    return listOf(WorldSimulationEvent.CharacterUpdated(updated))
}

internal fun WorldSimulation.bankDepositImpl(characterId: String, inventorySlotIndex: Int, amount: Int = 1): List<WorldSimulationEvent> {
    val player = players[characterId] ?: return emptyList()
    val stack = player.inventory.getSlot(inventorySlotIndex) ?: return emptyList()
    val itemDef = items[stack.itemId] ?: return emptyList()
    val transferQty = amount.coerceIn(1, stack.amount)

    val (afterInv, moved) = player.inventory.removeFromSlot(inventorySlotIndex, transferQty) ?: return emptyList()
    val bankRes = player.bank.addStack(itemDef, moved)
    if (bankRes.overflowAmount > 0) return emptyList()

    val updated = player.copy(inventory = afterInv, bank = bankRes.updatedInventory)
    players[characterId] = updated
    return listOf(WorldSimulationEvent.CharacterUpdated(updated))
}

internal fun WorldSimulation.bankWithdrawImpl(characterId: String, bankSlotIndex: Int, amount: Int = 1): List<WorldSimulationEvent> {
    val player = players[characterId] ?: return emptyList()
    val stack = player.bank.getSlot(bankSlotIndex) ?: return emptyList()
    val itemDef = items[stack.itemId] ?: return emptyList()
    val transferQty = amount.coerceIn(1, stack.amount)

    val (afterBank, moved) = player.bank.removeFromSlot(bankSlotIndex, transferQty) ?: return emptyList()
    val invRes = player.inventory.addStack(itemDef, moved)
    if (invRes.overflowAmount > 0) return emptyList()

    val updated = player.copy(inventory = invRes.updatedInventory, bank = afterBank)
    players[characterId] = updated
    return listOf(WorldSimulationEvent.CharacterUpdated(updated))
}

internal fun WorldSimulation.craftRecipeImpl(characterId: String, recipeId: Int): List<WorldSimulationEvent> {
    val player = players[characterId] ?: return emptyList()
    val recipe = recipes[recipeId] ?: return emptyList()
    if (player.level < recipe.levelReq) return emptyList()
    if (recipe.stationType != CraftingStationType.ANYWHERE) {
        val anchor = craftingAnchors[characterId]
        val atStation = anchor != null && anchor.stationType == recipe.stationType &&
            anchor.mapId == player.position.mapId && anchor.tileX == player.position.tileX && anchor.tileY == player.position.tileY
        if (!atStation) {
            return listOf(
                WorldSimulationEvent.ChatEmitted(
                    channel = "SYSTEM",
                    mapId = player.position.mapId,
                    senderId = "system",
                    senderName = "System",
                    targetCharacterId = characterId,
                    message = "You need to be at a ${recipe.stationType.name.lowercase().replace('_', ' ')} to craft ${recipe.name}.",
                    showOverheadBubble = false,
                    notice = "WARNING"
                )
            )
        }
    }
    val outputDef = items[recipe.outputItemId] ?: return emptyList()

    // Verify all ingredients
    var workingInv = player.inventory
    for (ing in recipe.ingredients) {
        workingInv = workingInv.removeByItemId(ing.itemId, ing.amount) ?: return emptyList()
    }
    val addRes = workingInv.addItem(outputDef, recipe.outputAmount)
    if (addRes.overflowAmount > 0) return emptyList()

    val out = mutableListOf<WorldSimulationEvent>()
    val updated = player.copy(inventory = addRes.updatedInventory)
    players[characterId] = updated
    grantXpToCharacter(characterId, recipe.xpReward, out)
    advanceQuestProgress(characterId, QuestObjectiveType.CRAFT_RECIPE, recipe.id, 1, out)
    return out
}

/** Merges [materialSlotIndex] into [targetSlotIndex] to increase the target gear's `+N` bonus by 1. */
internal fun WorldSimulation.mergeGearImpl(
    characterId: String,
    targetSlotIndex: Int,
    materialSlotIndex: Int
): List<WorldSimulationEvent> {
    val player = players[characterId] ?: return emptyList()
    if (!player.isAlive(game.lifeVitalId)) return emptyList()
    if (targetSlotIndex == materialSlotIndex) return emptyList()

    val targetStack = player.inventory.getSlot(targetSlotIndex) ?: return emptyList()
    val materialStack = player.inventory.getSlot(materialSlotIndex) ?: return emptyList()
    val targetDef = items[targetStack.itemId] ?: return emptyList()
    val materialDef = items[materialStack.itemId] ?: return emptyList()

    val targetSlot = targetDef.equipmentSlotOrNull()
    val materialSlot = materialDef.equipmentSlotOrNull()

    fun warn(message: String): List<WorldSimulationEvent> = listOf(tellPlayer(player, message, "WARNING"))

    if (game.definition.gearUpgrade?.enhancement != null) {
        return warn("Gear is enhanced with materials at the Gear Master, not merged.")
    }
    if (targetSlot == null || targetSlot == EquipmentSlot.AMMO || targetDef.isEffectivelyStackable ||
        materialSlot == null || materialSlot == EquipmentSlot.AMMO || materialDef.isEffectivelyStackable
    ) {
        return warn("Only equippable gear can be merged.")
    }
    if (targetSlot != materialSlot) {
        return warn("You can only merge gear of the same slot.")
    }
    if (targetStack.plus >= ItemStack.MAX_PLUS) {
        return warn("${targetDef.name} is already at maximum +${ItemStack.MAX_PLUS}.")
    }
    if (materialStack.plus < targetStack.plus) {
        val slotLabel = targetSlot.name.lowercase()
        return warn("Need a +${targetStack.plus} or higher $slotLabel to upgrade +${targetStack.plus} gear.")
    }

    val upgraded = targetStack.copy(plus = targetStack.plus + 1)
    val mutableSlots = player.inventory.slots.toMutableList()
    mutableSlots[targetSlotIndex] = upgraded
    mutableSlots[materialSlotIndex] = null
    val updatedPlayer = player.copy(inventory = player.inventory.copy(slots = mutableSlots))
    players[characterId] = updatedPlayer

    val upgradedName = ItemRollText.displayName(targetDef, upgraded.rolls, game.definition.loot, upgraded.plus)
    return listOf(
        WorldSimulationEvent.CharacterUpdated(updatedPlayer),
        tellPlayer(updatedPlayer, "Merged ${materialDef.name} into $upgradedName!", "GOOD")
    )
}
