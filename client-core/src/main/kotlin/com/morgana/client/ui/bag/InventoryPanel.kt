package com.morgana.client.ui.bag

import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.kotcrab.vis.ui.widget.VisTable
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.net.ClientTransport
import com.morgana.client.state.ClientWorldState
import com.morgana.client.state.HotbarSlotAction
import com.morgana.client.ui.EmptyStates
import com.morgana.client.ui.GameTheme
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.BlankGame
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemRollText
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.protocol.ClientPacket

/**
 * The Inventory tab of the [CharacterMenu]: the bag as a grid of icons (every slot shown, with filter tabs, search and
 * Sort) and a panel describing the selected item (stats compared with what is worn, requirements, value) with Use or
 * Equip, Drop, Quickbar and Move.
 *
 * It follows the character: the server's updates redraw it, so using an item, equipping, dropping or sorting never
 * closes the menu. The rules about what to list and say are in [BagView] and [ItemInfo].
 */
class InventoryPanel(
    private val uiStage: Stage,
    private val worldState: ClientWorldState,
    private val netClient: ClientTransport,
    private val assets: ProceduralRetroAssets,
    private val onAssignHotbar: (HotbarSlotAction) -> Unit,
    private val winW: Float,
    private val winH: Float
) : VisTable() {

    private var filter = BagFilter.ALL
    private var compactMode = false
    /** The bag has no search box (the room goes to the item details); kept so [BagView] still takes a query. */
    private val query = ""
    private var selectedSlot: Int? = null
    /** The slot picked up by Move; the next square tapped swaps with it. */
    private var movingFrom: Int? = null
    /** The gear slot picked for Merge; the next compatible gear tapped upgrades this one. */
    private var mergingFrom: Int? = null
    /** The slot whose "drop everything" button was tapped once and now asks for a second tap. */
    private var confirmDropSlot: Int? = null

    private val theme = GameTheme.shared
    private val cell = BagUi.cellSize
    private val grid = ItemGrid(assets, BagUi.font, cell)
    private val detail = VisTable()
    private val body = VisTable()
    private val tabBar = VisTable()
    private val goldCell = VisTable()
    private val capacityHolder = VisTable()
    /** Holds the buttons for the selected item under its stats, so they stay in view however long the stats run. */
    private val actionsHolder = VisTable()
    private var lastSignature: List<Any?>? = null

    init {
        defaults().pad(3f)
        grid.columns = (((winW * 0.5f) - 28f) / cell).toInt().coerceIn(4, 10)
        grid.onTap = ::onCellTapped

        add(tabBar).growX().row()
        add(body).grow().row()
        rebuildTabs()
        rebuildBody()
        refresh()
    }

    // ---- live updates ----

    override fun act(delta: Float) {
        super.act(delta)
        val ch = worldState.localCharacter ?: return
        val signature = listOf(
            ch.inventory,
            ch.equipment,
            ch.level,
            ch.xp,
            ch.unspentStatPoints,
            ch.attributes,
            ch.variables,
            worldState.catalog.items.size
        )
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
        val itemMap = items()
        val equippedWeaponType = ch.equipment.getEquipped(EquipmentSlot.WEAPON)?.let { itemMap[it.itemId]?.weaponType }.orEmpty()
        return WearerInfo(
            level = ch.level,
            attributes = ch.effectiveAttributes(game, itemMap),
            classId = ch.classId,
            equippedWeaponType = equippedWeaponType,
            weaponProficiencyLevels = ch.weaponProficiencyLevels
        )
    }

    // ---- structure ----

    private fun rebuildTabs() {
        tabBar.clearChildren()
        tabBar.add().growX()
        tabBar.add(goldCell).right().padRight(4f)
    }

    private fun rebuildBody() {
        body.clearChildren()
        // Toolbar: filter chips and Sort, above the grid only so the item details can use the full height on the right.
        val toolbar = VisTable()
        val group = ButtonGroup<com.kotcrab.vis.ui.widget.VisTextButton>().also { it.setMinCheckCount(1); it.setMaxCheckCount(1) }
        for (f in BagFilter.entries) {
            val chip = BagUi.chip(f.label, filter == f) { filter = f; movingFrom = null; refresh() }
            group.add(chip)
            toolbar.add(chip).height(BagUi.buttonHeight).padRight(3f)
        }
        toolbar.add(BagUi.button("Sort") { netClient.send(ClientPacket.SortInventory); movingFrom = null })
            .height(BagUi.buttonHeight).padLeft(4f)
        toolbar.add(BagUi.chip("Compact", compactMode) { compactMode = !compactMode; rebuildBody(); refresh() })
            .height(BagUi.buttonHeight).padLeft(4f)
        toolbar.add().growX()

        val bodyH = winH - 168f
        val toolbarH = BagUi.buttonHeight + 4f
        if (compactMode) {
            grid.columns = ((winW - 36f) / cell).toInt().coerceIn(6, 12)
            val fullW = winW - 24f
            val col = VisTable()
            col.add(toolbar).growX().height(toolbarH).top().padBottom(4f).row()
            col.add(BagUi.scroll(grid, uiStage)).width(fullW).height(bodyH - 54f).top().row()
            col.add(capacityHolder).left().padTop(4f).row()
            col.add(actionsHolder).left().padTop(4f).row()
            body.add(col).width(fullW).height(bodyH + toolbarH).top().row()
        } else {
            grid.columns = (((winW * 0.5f) - 28f) / cell).toInt().coerceIn(4, 10)
            val gridW = winW * 0.5f
            val detailW = winW - gridW - 36f
            // Room taken by the Character window's title bar and tab bar, this panel's gold row and the toolbar. If the
            // content is taller than the window, the layout overflows upward and the tab bar slides under the title bar.
            val leftCol = VisTable()
            leftCol.add(toolbar).growX().height(toolbarH).top().padBottom(4f).row()
            leftCol.add(BagUi.scroll(grid, uiStage)).width(gridW).height(bodyH - 24f).top().row()
            leftCol.add(capacityHolder).left().padTop(4f).row()
            val detailCard = VisTable().apply {
                background = theme.cardBackground
                add(BagUi.scroll(detail, uiStage)).grow().top().row()
                add(actionsHolder).left().pad(2f, 4f, 4f, 4f)
            }
            body.add(leftCol).width(gridW).height(bodyH + toolbarH).top()
            body.add(detailCard).width(detailW).height(bodyH + toolbarH).top().padLeft(6f).row()
        }
    }



    // ---- data ----

    private fun refresh() {
        val ch = worldState.localCharacter ?: return
        val itemMap = items()

        goldCell.clearChildren()
        val currency = itemMap.values.filter { it.type == ItemType.CURRENCY }.minByOrNull { it.id }
        if (currency != null) {
            goldCell.add(BagUi.label("${currency.name}: ${BagView.gold(ch.inventory, currency.id)}").also { it.color = BagUi.gold })
        }

        // A selection that no longer holds an item (used up, dropped, sold) is forgotten.
        if (selectedSlot != null && ch.inventory.getSlot(selectedSlot!!) == null) selectedSlot = null
        if (movingFrom != null && ch.inventory.getSlot(movingFrom!!) == null) movingFrom = null
        if (confirmDropSlot != selectedSlot) confirmDropSlot = null

        val entries = BagView.entries(ch.inventory, itemMap, filter, query)
        val loot = worldState.gameDefinition?.loot
        grid.cells = entries.map { e ->
            val stack = e.stack
            GridCell(
                key = e.slot,
                iconId = if (stack == null) 0 else (e.def?.iconId ?: stack.itemId),
                amount = stack?.amount ?: 0,
                borderColor = BagUi.hexColor(ItemInfo.slotBorderHex(e.def, stack, loot))
            )
        }
        grid.selectedKey = selectedSlot
        grid.armedKey = movingFrom ?: mergingFrom
        grid.tipFor = { cell ->
            val stack = ch.inventory.getSlot(cell.key)
            val def = stack?.let { itemMap[it.itemId] }
            if (stack == null || def == null) null
            else ItemInfo.tip(def, stack.amount, def.equipmentSlotOrNull()?.let { ch.equipment.getEquipped(it) }?.let { itemMap[it.itemId] }, itemMap, names(), wearer(ch), stack.rolls, worldState.gameDefinition?.loot, stack.plus, spells(), stack.durability, stack.sockets, ItemInfo.wornFor(def, ch.equipment, itemMap), worldState.gameDefinition?.gearRules ?: com.morgana.domain.item.GearRules.DEFAULT)
        }

        capacityHolder.clearChildren()
        val canMove = filter == BagFilter.ALL && query.isBlank()
        val hint = when {
            mergingFrom != null -> "  Tap another matching gear item to merge into this one."
            movingFrom != null -> "  Tap a square to put the item there."
            !canMove -> "  (Clear the filter and search to move items.)"
            else -> ""
        }
        capacityHolder.add(BagUi.label(BagView.capacityText(ch.inventory) + hint, Tone.MUTED))

        emptyHint = if (entries.none { it.stack != null }) EmptyStates.bag(filter, query) else null
        buildDetail(ch, itemMap)
    }

    /** What the detail pane says instead of the usual prompt when the bag list shows nothing. */
    private var emptyHint: String? = null

    private fun onCellTapped(cellTapped: GridCell) {
        val ch = worldState.localCharacter ?: return
        val slot = cellTapped.key
        val mergeTarget = mergingFrom
        if (mergeTarget != null) {
            if (slot != mergeTarget) netClient.send(ClientPacket.MergeGear(mergeTarget, slot))
            mergingFrom = null
            selectedSlot = mergeTarget
            refresh()
            return
        }
        val from = movingFrom
        if (from != null) {
            if (slot != from) netClient.send(ClientPacket.SwapInventorySlots(from, slot))
            movingFrom = null
            selectedSlot = slot
            refresh()
            return
        }
        selectedSlot = if (ch.inventory.getSlot(slot) != null) slot else null
        confirmDropSlot = null
        refresh()
    }

    private fun buildDetail(ch: CharacterState, itemMap: Map<Int, ItemDefinition>) {
        detail.clearChildren()
        actionsHolder.clearChildren()
        detail.top().left()
        val slot = selectedSlot
        val stack = slot?.let { ch.inventory.getSlot(it) }
        val textW = winW - winW * 0.5f - 70f
        if (slot == null || stack == null) {
            detail.add(BagUi.wrapped(emptyHint ?: "Tap an item to see what it does.", Tone.MUTED)).width(textW).pad(6f).left().row()
            detail.add(BagUi.wrapped("Sort tidies the bag. Use the tabs to show only gear, things to use, or materials.", Tone.MUTED)).width(textW).pad(6f).left().row()
            return
        }
        val def = itemMap[stack.itemId]
        if (def == null) {
            detail.add(BagUi.wrapped("Unknown item #${stack.itemId}", Tone.BAD)).width(textW).pad(6f).row()
            return
        }

        val loot = worldState.gameDefinition?.loot
        val header = VisTable()
        header.add(BagUi.icon(assets, def.iconId, 40f)).size(40f).padRight(8f)
        val title = VisTable()
        title.add(BagUi.rarityTitle(ItemRollText.displayName(def, stack.rolls, loot, stack.plus), ItemRollText.color(stack.rolls, loot))).width(textW - 56f).left().row()
        title.add(BagUi.label(ItemInfo.rolledKind(def, stack.rolls, loot) + if (stack.amount > 1) "  x${stack.amount}" else "", Tone.MUTED)).left()
        header.add(title).left()
        detail.add(header).left().pad(4f).row()

        if (def.description.isNotBlank()) detail.add(BagUi.wrapped(def.description, Tone.MUTED)).width(textW).left().padLeft(4f).padBottom(4f).row()

        val equippedSlot = def.equipmentSlotOrNull()
        val worn = equippedSlot?.let { ch.equipment.getEquipped(it) }?.let { itemMap[it.itemId] }
        val me = wearer(ch)
        val spellMap = spells()
        val extraLines = ItemInfo.durabilityLines(stack.durability, worldState.gameDefinition?.brokenGearStatPercent ?: 0) + ItemInfo.socketLines(stack.sockets, itemMap, names()) + ItemInfo.gemLines(def, names())
        val compare = ItemInfo.wornFor(def, ch.equipment, itemMap)?.let { ItemInfo.compareLines(def, stack.rolls, stack.plus, it, names(), worldState.gameDefinition?.gearRules ?: com.morgana.domain.item.GearRules.DEFAULT) }.orEmpty()
        for (line in ItemInfo.describe(def, worn, itemMap, names(), me, stack.rolls, stack.plus, spellMap, worldState.gameDefinition?.gearRules ?: com.morgana.domain.item.GearRules.DEFAULT) + compare + extraLines) {
            detail.add(BagUi.wrapped(line.text, line.tone)).width(textW).left().padLeft(4f).row()
        }
        detail.add(BagUi.label("Value ${ItemRollText.price(def.price, stack.rolls, loot, stack.plus)}" + if (stack.amount > 1) " each" else "", Tone.MUTED)).left().padLeft(4f).padTop(2f).row()

        // Actions.
        val actions = VisTable()
        val usable = equippedSlot != null || def.type == ItemType.CONSUMABLE || def.type == ItemType.SPELL_SCROLL
        if (usable) {
            val label = if (equippedSlot != null) "Equip" else "Use"
            actions.add(BagUi.button(label, enabled = !ItemInfo.unmet(def, me, spellMap)) { netClient.send(ClientPacket.UseInventorySlot(slot)) })
                .height(BagUi.buttonHeight).padRight(4f).padBottom(4f)
        }
        if (equippedSlot != null && equippedSlot != EquipmentSlot.AMMO && !def.isEffectivelyStackable && stack.plus < ItemStack.MAX_PLUS) {
            actions.add(BagUi.button(if (mergingFrom == slot) "Cancel merge" else "Merge") {
                mergingFrom = if (mergingFrom == slot) null else slot
                movingFrom = null
                refresh()
            }).height(BagUi.buttonHeight).padRight(4f).padBottom(4f)
        }
        if (def.type == ItemType.CONSUMABLE || def.type == ItemType.SPELL_SCROLL) {
            actions.add(BagUi.button("Quickbar") { onAssignHotbar(HotbarSlotAction.UseItem(def.id, def.name, def.iconId)) })
                .height(BagUi.buttonHeight).padRight(4f).padBottom(4f)
        }
        val canMove = filter == BagFilter.ALL && query.isBlank()
        actions.add(BagUi.button(if (movingFrom == slot) "Cancel move" else "Move", enabled = canMove) {
            movingFrom = if (movingFrom == slot) null else slot
            mergingFrom = null
            refresh()
        }).height(BagUi.buttonHeight).padRight(4f).padBottom(4f)
        actions.row()
        if (stack.amount > 1) {
            actions.add(BagUi.button("Drop 1") { netClient.send(ClientPacket.DropInventorySlot(slot, 1)) }).height(BagUi.buttonHeight).padRight(4f)
        }
        val confirming = confirmDropSlot == slot
        actions.add(BagUi.button(if (confirming) "Really drop?" else if (stack.amount > 1) "Drop all" else "Drop") {
            if (confirmDropSlot == slot) {
                netClient.send(ClientPacket.DropInventorySlot(slot, stack.amount))
                confirmDropSlot = null
            } else {
                confirmDropSlot = slot
                refresh()
            }
        }).height(BagUi.buttonHeight)
        actionsHolder.add(actions).left()
    }
}
