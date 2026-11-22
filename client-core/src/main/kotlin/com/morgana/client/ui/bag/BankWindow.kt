package com.morgana.client.ui.bag

import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.net.ClientTransport
import com.morgana.client.state.ClientWorldState
import com.morgana.client.ui.EmptyStates
import com.morgana.client.ui.GameTheme
import com.morgana.client.ui.HudWindow
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.BlankGame
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemRollText
import com.morgana.domain.item.ItemType
import com.morgana.domain.social.GuildBankLog
import com.morgana.protocol.ClientPacket

/**
 * Warehouse / bank vault window styled like [InventoryPanel]. With [guildVault] it is the guild's shared vault instead of
 * the player's own: the same grid, but the vault is the guild's (`activeGuild.bank`), moves go to the guild packets, the
 * founder can change who may withdraw, and the newest vault movements are listed.
 *

 * - **Withdraw** tab shows the vault grid with filters, search, quality borders, tooltips, and quantity controls.
 * - **Deposit** tab shows the player's inventory grid so items can be deposited without closing the window.
 */
class BankWindow(
    private val stage: Stage,
    private val worldState: ClientWorldState,
    private val netClient: ClientTransport,
    private val assets: ProceduralRetroAssets,
    private val guildVault: Boolean = false
) : HudWindow(if (guildVault) "Guild Vault" else "Royal Bank Vault") {

    private enum class Tab { WITHDRAW, DEPOSIT }

    private var tab = Tab.WITHDRAW
    private var filter = BagFilter.ALL
    private var query = ""
    private var selectedSlot: Int? = null
    private var quantity = 1
    /** The guild vault tab being shown and deposited into (each tab is `guildBank.tabSlots` slots). */
    private var vaultTab = 0

    private val cell = BagUi.cellSize
    private val grid = ItemGrid(assets, BagUi.font, cell)
    private val detail = VisTable()
    private val body = VisTable()
    private val tabBar = VisTable()
    private val goldCell = VisTable()
    private val capacityHolder = VisTable()
    private val searchField = VisTextField()
    private var lastSignature: List<Any?>? = null
    private var emptyHint: String? = null

    private val winW: Float
    private val winH: Float
    private val openedMap: Int = worldState.localCharacter?.position?.mapId ?: 0

    init {
        isMovable = true
        addCloseButton()
        defaults().pad(3f)
        val (w, h) = BagUi.fit(stage, 680f, 430f)
        winW = w
        winH = h
        grid.columns = (((winW * 0.56f) - 28f) / cell).toInt().coerceIn(4, 10)
        grid.onTap = { c -> onCellTapped(c) }
        searchField.messageText = "Search"
        searchField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                if (searchField.text != query) {
                    query = searchField.text
                    refresh()
                }
            }
        })

        add(tabBar).growX().row()
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
        if (ch.position.mapId != openedMap) {
            remove()
            return
        }
        if (guildVault && worldState.activeGuild == null) {
            remove()
            return
        }
        val signature = listOf(vault(ch), worldState.activeGuild?.bankLog, worldState.activeGuild?.bankWithdrawRank, ch.inventory, ch.equipment, ch.level, worldState.catalog.items.size, vaultTab)
        if (signature != lastSignature) {
            lastSignature = signature
            refresh()
        }
    }

    private fun items(): Map<Int, ItemDefinition> = worldState.catalog.items.associateBy { it.id }

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

    /** The vault being shown: the guild's or the player's own. */
    private fun vault(ch: CharacterState) = if (guildVault) worldState.activeGuild?.bank ?: Inventory(maxSlots = 0) else ch.bank

    private fun activeContainer(ch: CharacterState) =
        if (tab == Tab.WITHDRAW) vault(ch) else ch.inventory

    private fun targetContainer(ch: CharacterState) =
        if (tab == Tab.WITHDRAW) ch.inventory else vault(ch)

    /** Who may withdraw now: the guild's own setting, else the game's default. */
    private fun withdrawRank(): String =
        worldState.activeGuild?.bankWithdrawRank?.ifBlank { null } ?: worldState.gameDefinition?.guildBank?.defaultWithdrawRank ?: "Officer"

    private fun rebuildTabs() {
        tabBar.clearChildren()
        val group = ButtonGroup<VisTextButton>().also { it.setMinCheckCount(1); it.setMaxCheckCount(1) }
        val withdraw = BagUi.chip("Withdraw (Vault)", tab == Tab.WITHDRAW) {
            tab = Tab.WITHDRAW
            selectedSlot = null
            quantity = 1
            refresh()
        }
        val deposit = BagUi.chip("Deposit (Bag)", tab == Tab.DEPOSIT) {
            tab = Tab.DEPOSIT
            selectedSlot = null
            quantity = 1
            refresh()
        }
        group.add(withdraw); group.add(deposit)
        tabBar.add(withdraw).height(BagUi.buttonHeight).padRight(4f)
        tabBar.add(deposit).height(BagUi.buttonHeight)
        tabBar.add().growX()
        if (guildVault) {
            val isFounder = worldState.localCharacter?.guildRank == "Founder"
            val need = withdrawRank()
            if (isFounder) {
                val next = when (need) { "Officer" -> "Member"; "Member" -> "Founder"; else -> "Officer" }
                tabBar.add(BagUi.chip("Withdraw: $need+", false) { netClient.send(ClientPacket.GuildBankSetAccess(next)) }).height(BagUi.buttonHeight).padRight(6f)
            } else {
                val myLimit = worldState.gameDefinition?.guildBank?.withdrawLimits?.get(worldState.localCharacter?.guildRank)
                tabBar.add(BagUi.label("Withdraw: $need+" + (if (myLimit != null) " ($myLimit/day)" else ""), Tone.MUTED)).padRight(6f)
            }
        }
        if (guildVault) {
            val config = worldState.gameDefinition?.guildBank
            val guild = worldState.activeGuild
            if (config != null && guild != null) {
                val marks = guild.bank.totalCountOf(config.currencyItemId)
                val markName = items()[config.currencyItemId]?.name ?: "Marks"
                tabBar.add(BagUi.label("Vault $markName: $marks", Tone.MUTED)).padRight(6f)
                val next = config.upgrades.getOrNull(guild.bankLevel)
                if (next != null && worldState.localCharacter?.guildRank == "Founder") {
                    tabBar.add(BagUi.chip("Upgrade +${next.extraSlots} (${next.cost})", false) { netClient.send(ClientPacket.GuildBankUpgrade) }).height(BagUi.buttonHeight).padRight(6f)
                }
            }
        }
        tabBar.add(goldCell).right().padRight(4f)
    }

    /** How many tabs the guild vault has now (one when the vault is one tab or smaller). */
    private fun vaultTabCount(): Int {
        val slots = worldState.activeGuild?.bank?.maxSlots ?: return 1
        val per = worldState.gameDefinition?.guildBank?.tabSlots ?: return 1
        return ((slots + per - 1) / per).coerceAtLeast(1)
    }

    private fun rebuildBody() {
        body.clearChildren()
        val toolbar = VisTable()
        val group = ButtonGroup<VisTextButton>().also { it.setMinCheckCount(1); it.setMaxCheckCount(1) }
        for (f in BagFilter.entries) {
            val chip = BagUi.chip(f.label, filter == f) {
                filter = f
                refresh()
            }
            group.add(chip)
            toolbar.add(chip).height(BagUi.buttonHeight).padRight(3f)
        }
        if (guildVault && vaultTabCount() > 1) {
            if (vaultTab >= vaultTabCount()) vaultTab = 0
            val tabGroup = ButtonGroup<VisTextButton>().also { it.setMinCheckCount(1); it.setMaxCheckCount(1) }
            for (t in 0 until vaultTabCount()) {
                val chip = BagUi.chip("Tab ${t + 1}", vaultTab == t) {
                    vaultTab = t
                    selectedSlot = null
                    refresh()
                }
                tabGroup.add(chip)
                toolbar.add(chip).height(BagUi.buttonHeight).padLeft(if (t == 0) 8f else 2f)
            }
        }
        toolbar.add(searchField).width(if (winW < 560f) 80f else 120f).height(BagUi.buttonHeight).padLeft(4f).padRight(4f)
        toolbar.add().growX()
        body.add(toolbar).growX().colspan(2).padBottom(4f).row()

        val gridW = winW * 0.56f
        val detailW = winW - gridW - 36f
        val bodyH = winH - 132f
        val leftCol = VisTable()
        leftCol.add(BagUi.scroll(grid, stage)).width(gridW).height(bodyH - 24f).top().row()
        leftCol.add(capacityHolder).left().padTop(4f).row()
        val detailCard = VisTable().apply {
            background = GameTheme.shared.cardBackground
            add(BagUi.scroll(detail, this@BankWindow.stage)).grow().top()
        }
        body.add(leftCol).width(gridW).height(bodyH).top()
        body.add(detailCard).width(detailW).height(bodyH).top().padLeft(6f).row()
    }

    private fun refresh() {
        val ch = worldState.localCharacter ?: return
        val itemMap = items()
        val container = activeContainer(ch)
        if (guildVault) rebuildTabs()

        goldCell.clearChildren()
        val currency = itemMap.values.filter { it.type == ItemType.CURRENCY }.minByOrNull { it.id }
        if (currency != null) {
            goldCell.add(BagUi.label("${currency.name}: ${BagView.gold(ch.inventory, currency.id)}").also { it.color = BagUi.gold })
        }

        if (selectedSlot != null && container.getSlot(selectedSlot!!) == null) {
            selectedSlot = null
            quantity = 1
        }

        val perTab = worldState.gameDefinition?.guildBank?.tabSlots ?: Int.MAX_VALUE
        val entries = BagView.entries(container, itemMap, filter, query).let { all ->
            if (guildVault && tab == Tab.WITHDRAW) all.filter { it.slot / perTab == vaultTab } else all
        }
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
        grid.armedKey = null
        grid.tipFor = { cell ->
            val stack = container.getSlot(cell.key)
            val def = stack?.let { itemMap[it.itemId] }
            if (stack == null || def == null) null
            else ItemInfo.tip(
                def, stack.amount,
                def.equipmentSlotOrNull()?.let { ch.equipment.getEquipped(it) }?.let { itemMap[it.itemId] },
                itemMap, names(), wearer(ch), stack.rolls, loot, stack.plus
            )
        }

        capacityHolder.clearChildren()
        val label = if (tab == Tab.WITHDRAW) "Vault: ${BagView.capacityText(container)}" else "Bag: ${BagView.capacityText(container)}"
        capacityHolder.add(BagUi.label(label, Tone.MUTED))

        emptyHint = if (entries.none { it.stack != null }) {
            if (filter == BagFilter.ALL && query.isBlank()) {
                if (tab == Tab.WITHDRAW) EmptyStates.VAULT else EmptyStates.VAULT_BAG
            } else {
                EmptyStates.bag(filter, query)
            }
        } else null

        buildDetail(ch, itemMap)
    }

    private fun onCellTapped(cellTapped: GridCell) {
        val ch = worldState.localCharacter ?: return
        val slot = cellTapped.key
        selectedSlot = if (activeContainer(ch).getSlot(slot) != null) slot else null
        quantity = 1
        refresh()
    }

    private fun buildDetail(ch: CharacterState, itemMap: Map<Int, ItemDefinition>) {
        detail.clearChildren()
        detail.top().left()
        val container = activeContainer(ch)
        val slot = selectedSlot
        val stack = slot?.let { container.getSlot(it) }
        val textW = winW - winW * 0.56f - 70f
        if (slot == null || stack == null) {
            if (guildVault) {
                val dayTicks = worldState.gameDefinition?.guildBank?.limitDayTicks ?: 1_728_000L
                val log = worldState.activeGuild?.bankLog.orEmpty().takeLast(12).reversed().map { GuildBankLog.display(it, dayTicks) }
                detail.add(BagUi.wrapped(if (tab == Tab.WITHDRAW) "Tap a stored item to inspect or withdraw it." else "Tap an item in your bag to put it in the guild vault.", Tone.MUTED)).width(textW).pad(6f).left().row()
                if (log.isNotEmpty()) {
                    detail.add(BagUi.label("Recent", Tone.MUTED)).pad(6f).left().row()
                    for (line in log) detail.add(BagUi.wrapped(line, Tone.NEUTRAL)).width(textW).padLeft(6f).padBottom(2f).left().row()
                }
                return
            }
            val prompt = emptyHint ?: if (tab == Tab.WITHDRAW) {
                "Tap a stored item to inspect or withdraw it."
            } else {
                "Tap an item in your bag to deposit it into the vault."
            }
            detail.add(BagUi.wrapped(prompt, Tone.MUTED)).width(textW).pad(6f).left().row()
            return
        }
        val def = itemMap[stack.itemId]
        if (def == null) {
            detail.add(BagUi.wrapped("Unknown item #${stack.itemId}", Tone.BAD)).width(textW).pad(6f).row()
            return
        }

        val loot = worldState.gameDefinition?.loot
        val tip = ItemInfo.tip(
            def, stack.amount,
            def.equipmentSlotOrNull()?.let { ch.equipment.getEquipped(it) }?.let { itemMap[it.itemId] },
            itemMap, names(), wearer(ch), stack.rolls, loot, stack.plus
        )

        val head = VisTable()
        head.add(Image(TextureRegionDrawable(TextureRegion(assets.getItemIconRegion(def.iconId))))).size(40f).padRight(8f)
        val title = VisTable()
        title.add(BagUi.rarityTitle(ItemRollText.displayName(def, stack.rolls, loot, stack.plus), ItemRollText.color(stack.rolls, loot)))
            .width(textW - 56f).left().row()
        title.add(BagUi.wrapped(tip.kind, Tone.MUTED)).width(textW - 56f).left().row()
        head.add(title).left()
        detail.add(head).pad(6f).left().row()

        if (tip.description.isNotBlank()) {
            detail.add(BagUi.wrapped(tip.description, Tone.MUTED)).width(textW).padLeft(6f).padBottom(4f).left().row()
        }
        for (line in tip.lines) {
            detail.add(BagUi.wrapped(line.text, line.tone)).width(textW).padLeft(6f).padBottom(2f).left().row()
        }

        val maxTransfer = stack.amount.coerceAtLeast(1)
        quantity = quantity.coerceIn(1, maxTransfer)
        if (maxTransfer > 1) {
            val qtyRow = VisTable()
            qtyRow.add(BagUi.label("Amount: $quantity", Tone.MUTED)).padRight(6f)
            for (q in TradeMath.quantityChoices(maxTransfer)) {
                val lbl = if (q == maxTransfer) "Max" else q.toString()
                qtyRow.add(BagUi.chip(lbl, quantity == q) {
                    quantity = q
                    buildDetail(ch, itemMap)
                }).height(BagUi.buttonHeight).padRight(3f)
            }
            detail.add(qtyRow).pad(6f).left().row()
        }

        val room = targetContainer(ch).roomFor(def)
        val actionLabel = if (tab == Tab.WITHDRAW) "Withdraw" else "Deposit"
        val canTransfer = room > 0
        val btn = BagUi.button(if (maxTransfer > 1) "$actionLabel x$quantity" else actionLabel, enabled = canTransfer) {
            if (tab == Tab.WITHDRAW) {
                netClient.send(if (guildVault) ClientPacket.GuildBankWithdraw(slot, quantity) else ClientPacket.BankWithdraw(slot, quantity))
            } else {
                netClient.send(if (guildVault) ClientPacket.GuildBankDeposit(slot, quantity, vaultTab) else ClientPacket.BankDeposit(slot, quantity))
            }
            quantity = 1
        }
        val actions = VisTable()
        actions.add(btn).height(BagUi.buttonHeight).padRight(6f)
        if (!canTransfer) {
            val fullMsg = if (tab == Tab.WITHDRAW) "Bag is full." else "Vault is full."
            actions.add(BagUi.label(fullMsg, Tone.BAD))
        }
        detail.add(actions).pad(6f).left().row()
    }
}
