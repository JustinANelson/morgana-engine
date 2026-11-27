package com.morgana.client.ui.bag

import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.net.ClientTransport
import com.morgana.client.state.ClientWorldState
import com.morgana.client.ui.HudWindow
import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.BlankGame
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemRollText
import com.morgana.domain.item.ItemRolls
import com.morgana.domain.sim.SHOP_RANGE_TILES
import com.morgana.protocol.ClientPacket

/**
 * A shop's window: **Buy** lists what the shop sells as icons with prices, a quantity (1, 5, 10 or the most you can
 * afford and carry), the total, and what you would be wearing instead; **Sell** lists your bag, with what the shop pays,
 * a quantity (1, 5, 10 or all), and quick-sell shortcuts by gear rarity (Normal, Refined, Magic, ...) and crafting materials.
 *
 * It follows the character (gold and bag update after every trade, the window stays open) and closes itself when the
 * player walks out of the server's shop range or changes map. The arithmetic is [TradeMath], the same as the server's.
 */
class ShopWindow(
    private val stage: Stage,
    private val worldState: ClientWorldState,
    private val netClient: ClientTransport,
    private val assets: ProceduralRetroAssets,
    private val shop: ShopDefinition
) : HudWindow(shop.name) {

    private enum class Tab { BUY, SELL, BUYBACK }

    private var tab = Tab.BUY
    private var selectedBuy: Int? = null
    private var selectedSell: Int? = null
    private var selectedBuyback: Int? = null
    /** Chosen quantity for the selected item; clamped to what is possible whenever the window redraws. */
    private var quantity = 1

    private val cell = BagUi.cellSize
    private val grid = ItemGrid(assets, BagUi.font, cell)
    private val detail = VisTable()
    private val body = VisTable()
    private val tabBar = VisTable()
    private val goldCell = VisTable()
    private var lastSignature: List<Any?>? = null

    private val winW: Float
    private val winH: Float

    /** Where the shop was opened: walking [SHOP_RANGE_TILES] away (or to another map) closes it, as the server would refuse anyway. */
    private val openedMap: Int = worldState.localCharacter?.position?.mapId ?: 0
    private val openedX: Int = worldState.localCharacter?.position?.tileX ?: 0
    private val openedY: Int = worldState.localCharacter?.position?.tileY ?: 0

    init {
        isMovable = true
        addCloseButton()
        defaults().pad(3f)
        val (w, h) = BagUi.fit(stage, 660f, 400f)
        winW = w
        winH = h
        grid.columns = (((winW * 0.5f) - 28f) / cell).toInt().coerceIn(3, 9)
        grid.onTap = { c ->
            when (tab) { Tab.BUY -> selectedBuy = c.key; Tab.SELL -> selectedSell = c.key; Tab.BUYBACK -> selectedBuyback = c.key }
            quantity = 1
            refresh()
        }
        add(tabBar).growX().padBottom(2f).row()
        add(com.morgana.client.ui.GameTheme.shared.divider()).growX().padBottom(3f).row()
        add(body).grow().row()
        rebuildTabs()
        rebuildBody()
        refresh()
        setSize(winW, winH)
        centerWindow()
    }

    override fun act(delta: Float) {
        super.act(delta)
        val ch = worldState.localCharacter ?: return
        val p = ch.position
        if (p.mapId != openedMap || maxOf(Math.abs(p.tileX - openedX), Math.abs(p.tileY - openedY)) > (worldState.gameDefinition?.gameplayTuning?.shopRangeTiles ?: SHOP_RANGE_TILES)) {
            remove()
            return
        }
        val signature = listOf(ch.inventory, ch.equipment, ch.level, worldState.catalog.items.size, worldState.catalog.shops.size, worldState.shopBuybackShopId, worldState.shopBuyback)
        if (signature != lastSignature) {
            lastSignature = signature
            refresh()
        }
    }

    private fun items(): Map<Int, ItemDefinition> = worldState.catalog.items.associateBy { it.id }

    private fun spells(): Map<Int, com.morgana.domain.content.SpellDefinition> = worldState.catalog.spells.associateBy { it.id }

    private fun names(): StatNames {
        val game = (worldState.compiledGame ?: BlankGame.COMPILED).definition
        return StatNames(
            attributes = game.attributes.associate { it.id to it.short.ifBlank { it.name } },
            vitals = game.vitals.associate { it.id to it.short.ifBlank { it.name } }
        )
    }

    private fun wearer(ch: CharacterState): WearerInfo {
        val game = worldState.compiledGame ?: BlankGame.COMPILED
        return WearerInfo(ch.level, ch.effectiveAttributes(game, items()), ch.classId)
    }

    private fun rebuildTabs() {
        tabBar.clearChildren()
        val group = ButtonGroup<VisTextButton>().also { it.setMinCheckCount(1); it.setMaxCheckCount(1) }
        val buy = BagUi.chip("Buy", tab == Tab.BUY) { tab = Tab.BUY; quantity = 1; refresh() }
        val sell = BagUi.chip("Sell", tab == Tab.SELL) { tab = Tab.SELL; quantity = 1; refresh() }
        val count = buybackEntries().size
        val back = BagUi.chip(if (count > 0) "Buy back ($count)" else "Buy back", tab == Tab.BUYBACK) { tab = Tab.BUYBACK; quantity = 1; refresh() }
        group.add(buy); group.add(sell); group.add(back)
        tabBar.add(buy).height(BagUi.buttonHeight).padRight(4f)
        tabBar.add(sell).height(BagUi.buttonHeight).padRight(4f)
        tabBar.add(back).height(BagUi.buttonHeight)
        tabBar.add().growX()
        tabBar.add(goldCell).right().padRight(4f)
    }

    private fun rebuildBody() {
        body.clearChildren()
        val theme = com.morgana.client.ui.GameTheme.shared
        val gridW = ((winW - 32f) * 0.52f).coerceAtLeast(170f)
        val detailW = (winW - 32f - gridW - 6f).coerceAtLeast(150f)
        grid.columns = ((gridW - 22f) / cell).toInt().coerceIn(3, 9)
        val gridWell = VisTable().apply {
            background = theme.insetWellBackground
            add(BagUi.scroll(grid, this@ShopWindow.stage)).grow().top()
        }
        val detailCard = VisTable().apply {
            background = theme.cardBackground
            add(BagUi.scroll(detail, this@ShopWindow.stage)).grow().top()
        }
        body.add(gridWell).width(gridW).growY().top()
        body.add(detailCard).width(detailW).growY().top().padLeft(6f).row()
    }

    private fun currencyName(itemMap: Map<Int, ItemDefinition>, id: Int = shop.currencyItemId): String = itemMap[id]?.name ?: "gold"

    private fun refresh() {
        val ch = worldState.localCharacter ?: return
        val itemMap = items()
        goldCell.clearChildren()
        goldCell.add(BagUi.label("${currencyName(itemMap)}: ${BagView.gold(ch.inventory, shop.currencyItemId)}").also { it.color = BagUi.gold })
        when (tab) {
            Tab.BUY -> refreshBuy(ch, itemMap)
            Tab.SELL -> refreshSell(ch, itemMap)
            Tab.BUYBACK -> refreshBuyback(ch, itemMap)
        }
        rebuildTabs()
    }

    // ---- buying back ----

    /** What the player sold to this shop and can still buy back; the server keeps the list and resends it after each change. */
    private fun buybackEntries(): List<com.morgana.domain.sim.ShopBuybackEntry> =
        if (worldState.shopBuybackShopId == shop.id) worldState.shopBuyback else emptyList()

    private fun refreshBuyback(ch: CharacterState, itemMap: Map<Int, ItemDefinition>) {
        val entries = buybackEntries()
        val money = BagView.gold(ch.inventory, shop.currencyItemId)
        val loot = worldState.gameDefinition?.loot
        grid.cells = entries.mapIndexed { i, e ->
            val def = itemMap[e.stack.itemId]
            GridCell(
                key = i,
                iconId = def?.iconId ?: e.stack.itemId,
                amount = e.stack.amount,
                dim = e.price > money,
                borderColor = BagUi.hexColor(ItemInfo.slotBorderHex(def, e.stack, loot))
            )
        }
        grid.tipFor = { cell ->
            val e = entries.getOrNull(cell.key)
            val def = e?.let { itemMap[it.stack.itemId] }
            if (e == null || def == null) null
            else ItemInfo.tip(def, e.stack.amount, def.equipmentSlotOrNull()?.let { ch.equipment.getEquipped(it) }?.let { itemMap[it.itemId] }, itemMap, names(), wearer(ch), e.stack.rolls, loot, e.stack.plus, spells(), worn = ItemInfo.wornFor(def, ch.equipment, itemMap))
        }
        if (selectedBuyback != null && selectedBuyback !in entries.indices) selectedBuyback = null
        if (selectedBuyback == null && entries.isNotEmpty()) selectedBuyback = entries.lastIndex
        grid.selectedKey = selectedBuyback
        grid.armedKey = null

        detail.clearChildren()
        detail.top().left()
        val textW = winW - winW * 0.5f - 70f
        val index = selectedBuyback
        val entry = index?.let { entries.getOrNull(it) }
        val def = entry?.let { itemMap[it.stack.itemId] }
        if (entry == null || def == null) {
            detail.add(BagUi.wrapped("Things you sell to this shop wait here until you leave, so a mistaken sale can be undone for the same price.", Tone.MUTED)).width(textW).pad(6f).row()
            return
        }
        addItemHeader(def, entry.stack.amount, textW, entry.stack.rolls, entry.stack.plus)
        val room = ch.inventory.addStack(def, entry.stack).overflowAmount == 0
        val affordable = entry.price <= money
        detail.add(BagUi.label("Price ${entry.price} ${currencyName(itemMap)} (what the shop paid)", if (affordable) Tone.NEUTRAL else Tone.BAD)).left().padLeft(4f).padTop(4f).row()
        when {
            !affordable -> detail.add(BagUi.wrapped("You cannot afford this.", Tone.BAD)).width(textW).left().padLeft(4f).padTop(4f).row()
            !room -> detail.add(BagUi.wrapped("Your bag is full.", Tone.BAD)).width(textW).left().padLeft(4f).padTop(4f).row()
            else -> detail.add(BagUi.primaryButton("Buy back") {
                netClient.send(ClientPacket.ShopBuyback(shop.id, index))
            }).height(BagUi.buttonHeight).left().padLeft(4f).padTop(4f).row()
        }
    }

    // ---- buying ----

    private fun refreshBuy(ch: CharacterState, itemMap: Map<Int, ItemDefinition>) {
        val gold = BagView.gold(ch.inventory, shop.currencyItemId)
        grid.cells = shop.entries.mapIndexed { i, entry ->
            val def = itemMap[entry.itemId]
            val affordable = def != null && TradeMath.buyPrice(shop, entry, def) <= BagView.gold(ch.inventory, TradeMath.currencyOf(shop, entry))
            GridCell(key = i, iconId = def?.iconId ?: entry.itemId, amount = entry.amount, dim = !affordable)
        }
        grid.tipFor = { cell ->
            val entry = shop.entries.getOrNull(cell.key)
            val def = entry?.let { itemMap[it.itemId] }
            if (entry == null || def == null) null
            else ItemInfo.tip(def, entry.amount, def.equipmentSlotOrNull()?.let { ch.equipment.getEquipped(it) }?.let { itemMap[it.itemId] }, itemMap, names(), wearer(ch), spells = spells(), worn = ItemInfo.wornFor(def, ch.equipment, itemMap))
        }
        if (selectedBuy != null && selectedBuy !in shop.entries.indices) selectedBuy = null
        if (selectedBuy == null && shop.entries.isNotEmpty()) selectedBuy = 0
        grid.selectedKey = selectedBuy
        grid.armedKey = null

        detail.clearChildren()
        detail.top().left()
        val textW = winW - winW * 0.5f - 70f
        val index = selectedBuy
        val entry = index?.let { shop.entries.getOrNull(it) }
        val def = entry?.let { itemMap[it.itemId] }
        if (entry == null || def == null) {
            detail.add(BagUi.wrapped(if (shop.entries.isEmpty()) "This shop has nothing for sale." else "Tap an item to see its price.", Tone.MUTED)).width(textW).pad(6f).row()
            return
        }
        val currencyId = TradeMath.currencyOf(shop, entry)
        val money = BagView.gold(ch.inventory, currencyId)
        val price = TradeMath.buyPrice(shop, entry, def)
        val room = ch.inventory.roomFor(def)
        val max = TradeMath.maxBuy(money, price, entry, room)
        quantity = quantity.coerceIn(1, maxOf(1, max))

        addItemHeader(def, entry.amount, textW)
        describe(ch, def, itemMap, textW)
        val owned = ch.inventory.totalCountOf(def.id)
        if (owned > 0) detail.add(BagUi.label("You carry $owned", Tone.MUTED)).left().padLeft(4f).row()

        val unit = if (entry.amount > 1) "for ${entry.amount}" else "each"
        detail.add(BagUi.label("Price $price ${currencyName(itemMap, currencyId)} $unit", if (price <= money) Tone.NEUTRAL else Tone.BAD)).left().padLeft(4f).padTop(4f).row()
        if (max >= 1) {
            addQuantityRow(TradeMath.quantityChoices(max), "Max")
            val total = price * quantity
            detail.add(BagUi.label("Total $total ${currencyName(itemMap, currencyId)}", Tone.NEUTRAL)).left().padLeft(4f).padTop(4f).row()
            detail.add(BagUi.primaryButton("Buy ${if (quantity > 1) "$quantity" else ""}".trim()) {
                netClient.send(ClientPacket.ShopBuy(shop.id, index, quantity))
            }).height(BagUi.buttonHeight).left().padLeft(4f).padTop(4f).row()
        } else {
            val why = if (money < price) "You cannot afford this." else "Your bag is full."
            detail.add(BagUi.wrapped(why, Tone.BAD)).width(textW).left().padLeft(4f).padTop(4f).row()
        }
    }

    // ---- selling ----

    private fun refreshSell(ch: CharacterState, itemMap: Map<Int, ItemDefinition>) {
        val stacks = ch.inventory.slots.mapIndexedNotNull { i, s -> if (s != null) i to s else null }
        val loot = worldState.gameDefinition?.loot
        grid.cells = stacks.map { (slot, stack) ->
            val def = itemMap[stack.itemId]
            GridCell(
                key = slot,
                iconId = def?.iconId ?: stack.itemId,
                amount = stack.amount,
                dim = def == null || !TradeMath.canSell(shop, def),
                borderColor = BagUi.hexColor(ItemInfo.slotBorderHex(def, stack, loot))
            )
        }
        grid.tipFor = { cell ->
            val stack = ch.inventory.getSlot(cell.key)
            val def = stack?.let { itemMap[it.itemId] }
            if (stack == null || def == null) null
            else ItemInfo.tip(def, stack.amount, def.equipmentSlotOrNull()?.let { ch.equipment.getEquipped(it) }?.let { itemMap[it.itemId] }, itemMap, names(), wearer(ch), stack.rolls, worldState.gameDefinition?.loot, stack.plus, spells(), worn = ItemInfo.wornFor(def, ch.equipment, itemMap))
        }
        if (selectedSell != null && ch.inventory.getSlot(selectedSell!!) == null) selectedSell = null
        grid.selectedKey = selectedSell
        grid.armedKey = null

        detail.clearChildren()
        detail.top().left()
        val textW = winW - winW * 0.5f - 70f
        val slot = selectedSell
        val stack = slot?.let { ch.inventory.getSlot(it) }
        val def = stack?.let { itemMap[it.itemId] }
        if (stacks.isEmpty()) {
            detail.add(BagUi.wrapped("Your bag is empty.", Tone.MUTED)).width(textW).pad(6f).row()
            return
        }
        if (slot == null || stack == null || def == null) {
            detail.add(BagUi.wrapped("Tap something in your bag to sell it. Greyed items cannot be sold here.", Tone.MUTED)).width(textW).pad(6f).row()
            addBulkSellButtons(ch, itemMap)
            return
        }
        addItemHeader(def, stack.amount, textW, stack.rolls, stack.plus)
        if (!TradeMath.canSell(shop, def)) {
            detail.add(BagUi.wrapped(if (def.boundOnPickup) "Bound to you: this cannot be sold." else "The shop does not buy its own currency.", Tone.BAD)).width(textW).left().padLeft(4f).padTop(4f).row()
            addBulkSellButtons(ch, itemMap)
            return
        }
        val each = TradeMath.sellValue(shop, def, stack.rolls, worldState.gameDefinition?.loot, stack.plus)
        val maxSell = stack.amount.coerceAtMost(TradeMath.MAX_QUANTITY)
        quantity = quantity.coerceIn(1, maxSell)
        detail.add(BagUi.label("Shop pays $each ${currencyName(itemMap)}" + if (stack.amount > 1) " each" else "", Tone.NEUTRAL)).left().padLeft(4f).padTop(4f).row()
        addQuantityRow(TradeMath.sellChoices(stack.amount), "All")
        detail.add(BagUi.label("You get ${each * quantity} ${currencyName(itemMap)}", Tone.GOOD)).left().padLeft(4f).padTop(4f).row()
        detail.add(BagUi.primaryButton("Sell ${if (quantity > 1) "$quantity" else ""}".trim()) {
            netClient.send(ClientPacket.ShopSell(shop.id, slot, quantity))
        }).height(BagUi.buttonHeight).left().padLeft(4f).padTop(4f).row()
        addBulkSellButtons(ch, itemMap)
    }

    /** Quick-sell buttons for each gear rarity tier in the bag (Normal, Refined, Magic, ...) and for crafting materials. */
    private fun addBulkSellButtons(ch: CharacterState, itemMap: Map<Int, ItemDefinition>) {
        val groups = TradeMath.bulkSellGroups(ch.inventory, shop, itemMap, worldState.gameDefinition?.loot)
        if (groups.isEmpty()) return
        detail.add(BagUi.label("Quick sell", Tone.MUTED)).left().padLeft(4f).padTop(8f).row()
        for (group in groups) {
            val btn = BagUi.button(group.label) {
                netClient.send(ClientPacket.ShopSell(shopId = shop.id, slotIndices = group.slots))
            }
            BagUi.hexColor(group.color)?.let { btn.label.color = it }
            detail.add(btn).height(BagUi.buttonHeight).left().padLeft(4f).padTop(4f).row()
        }
    }

    // ---- shared bits ----

    private fun addItemHeader(def: ItemDefinition, amount: Int, textW: Float, rolls: ItemRolls? = null, plus: Int = 0) {
        val loot = worldState.gameDefinition?.loot
        val header = VisTable()
        header.add(BagUi.icon(assets, def.iconId, 40f)).size(40f).padRight(8f)
        val title = VisTable()
        title.add(BagUi.rarityTitle(ItemRollText.displayName(def, rolls, loot, plus), ItemRollText.color(rolls, loot))).width(textW - 56f).left().row()
        title.add(BagUi.label(ItemInfo.rolledKind(def, rolls, loot) + if (amount > 1) "  x$amount" else "", Tone.MUTED)).left()
        header.add(title).left()
        detail.add(header).left().pad(4f).row()
    }

    private fun describe(ch: CharacterState, def: ItemDefinition, itemMap: Map<Int, ItemDefinition>, textW: Float) {
        if (def.description.isNotBlank()) detail.add(BagUi.wrapped(def.description, Tone.MUTED)).width(textW).left().padLeft(4f).padBottom(4f).row()
        val slot = def.equipmentSlotOrNull()
        val worn = slot?.let { ch.equipment.getEquipped(it) }?.let { itemMap[it.itemId] }
        val compare = ItemInfo.wornFor(def, ch.equipment, itemMap)?.let { ItemInfo.compareLines(def, null, 0, it, names()) }.orEmpty()
        for (line in ItemInfo.describe(def, worn, itemMap, names(), wearer(ch), spells = spells()) + compare) {
            detail.add(BagUi.wrapped(line.text, line.tone)).width(textW).left().padLeft(4f).row()
        }
    }

    /** The quantity buttons; the last one is labelled [lastWord] ("Max" when buying, "All" when selling) unless it is 1, 5 or 10. */
    private fun addQuantityRow(choices: List<Int>, lastWord: String) {
        if (choices.size <= 1) return
        // The chosen quantity is always one of the buttons shown (the most can change after a trade).
        quantity = choices.lastOrNull { it <= quantity } ?: choices.first()
        val row = VisTable()
        val group = ButtonGroup<VisTextButton>().also { it.setMinCheckCount(1); it.setMaxCheckCount(1) }
        row.add(BagUi.label("How many", Tone.MUTED)).padRight(6f)
        for ((i, q) in choices.withIndex()) {
            val text = if (i == choices.lastIndex && q !in listOf(1, 5, 10)) "$lastWord $q" else q.toString()
            val chip = BagUi.chip(text, quantity == q) { quantity = q; refresh() }
            group.add(chip)
            row.add(chip).height(BagUi.buttonHeight).padRight(3f)
        }
        detail.add(row).left().padLeft(4f).padTop(4f).row()
    }
}
