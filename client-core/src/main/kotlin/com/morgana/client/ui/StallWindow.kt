package com.morgana.client.ui

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import com.morgana.client.state.ClientWorldState

/**
 * The player-stall window: a form over the `/stall ...` chat commands, so nobody has to remember their syntax.
 * It sells from the bag (pick an item, a price and a count), opens and closes the stall, and lists the open stalls within
 * reach with a View button each; the goods of the stall you looked at (or your own) are listed in the window with Buy and
 * Remove buttons, from the server's `StallViewed` packet. Replies and refusals still appear in chat.
 */
class StallWindow(
    private val stage: Stage,
    private val worldState: ClientWorldState,
    private val sendCommand: (String) -> Unit
) {
    private val theme = GameTheme.shared
    private var window: HudWindow? = null

    val isOpen: Boolean get() = window?.stage != null

    fun toggle() {
        if (isOpen) { close(); return }
        open()
    }

    fun close() {
        window?.remove()
        window = null
    }

    private fun button(label: String, onClick: () -> Unit) = VisTextButton(label, theme.buttonStyle).apply {
        addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { onClick() }
        })
    }

    /** Sends [command], or says why not when the form was filled in wrongly. */
    private fun run(command: String?, problem: String) {
        if (command == null) worldState.toasts.add(problem, ToastKind.WARNING, ToastClock.now()) else sendCommand(command)
    }

    /** What the goods section lists for [view]: "1. 3 x Salve +1 at 20 each", with names looked up in [items]. */
    private fun describe(good: com.morgana.domain.game.StallGood, items: Map<Int, com.morgana.domain.item.ItemDefinition>): String {
        val plus = if (good.plus > 0) " +${good.plus}" else ""
        return "${good.number}. ${good.amount} x ${items[good.itemId]?.name ?: "item ${good.itemId}"}$plus at ${good.unitPrice} each"
    }

    private fun open(at: Pair<Float, Float>? = null) {
        val rules = worldState.gameDefinition?.stall
        val ch = worldState.localCharacter ?: return
        if (!StallCommands.availableOn(rules, ch.position.mapId)) {
            worldState.toasts.add("Stalls are not available here.", ToastKind.WARNING, ToastClock.now())
            return
        }
        val shownView = worldState.stallView
        val win = HudWindow("Player Stall")
        // The server sends the goods after every change, view and purchase; rebuild the window when a new list arrives.
        win.addAction(object : com.badlogic.gdx.scenes.scene2d.Action() {
            override fun act(delta: Float): Boolean {
                if (worldState.stallView !== shownView && window === win) { val spot = win.x to win.y; close(); open(spot) }
                return false
            }
        })
        win.addCloseButton()
        win.isMovable = true
        val body = VisTable()
        body.top().left()
        body.defaults().pad(3f).left()

        // --- selling
        body.add(VisLabel("Sell from your bag", theme.mutedLabelStyle)).colspan(2).row()
        val items = worldState.catalog.items.associateBy { it.id }
        val slots = ch.inventory.slots.mapIndexedNotNull { i, s -> if (s == null) null else i to s }
        val choice = VisSelectBox<String>()
        choice.setItems(*slots.map { (i, s) ->
            val plus = if (s.plus > 0) " +${s.plus}" else ""
            "${i + 1}: ${items[s.itemId]?.name ?: "item ${s.itemId}"}$plus x${s.amount}"
        }.toTypedArray().ifEmpty { arrayOf("(bag is empty)") })
        body.add(choice).width(260f).colspan(2).row()
        val price = VisTextField().apply { messageText = "Price each"; maxLength = 9 }
        val units = VisTextField().apply { messageText = "Units (blank = all)"; maxLength = 6 }
        body.add(price).width(125f)
        body.add(units).width(125f).row()
        body.add(button("Add to stall") {
            run(slots.getOrNull(choice.selectedIndex)?.let { StallCommands.add(it.first, price.text, units.text) }, "Pick an item and a price of 1 or more.")
        }).colspan(2).row()
        val removeNo = VisTextField().apply { messageText = "Listing #"; maxLength = 3 }
        body.add(removeNo).width(125f)
        body.add(button("Remove listing") { run(StallCommands.remove(removeNo.text), "Type the listing number to remove.") }).row()
        val title = VisTextField().apply { messageText = "Stall title"; maxLength = 24 }
        body.add(title).width(260f).colspan(2).row()
        val own = VisTable().apply { defaults().padRight(4f) }
        own.add(button("Show my stall") { sendCommand(StallCommands.LIST) })
        own.add(button("Open") { sendCommand(StallCommands.open(title.text)) })
        own.add(button("Close") { sendCommand(StallCommands.CLOSE) })
        body.add(own).colspan(2).row()

        // --- the goods of the stall last looked at (your own after any change)
        val view = shownView
        if (view != null) {
            body.add(VisLabel(if (view.own) "Your stall \"${view.title}\" (${if (view.open) "open" else "closed"})" else "${view.title} (${view.ownerName})", theme.mutedLabelStyle)).colspan(2).padTop(10f).row()
            if (view.goods.isEmpty()) body.add(VisLabel("Nothing is listed.", theme.mutedLabelStyle)).colspan(2).row()
            view.goods.forEach { g ->
                body.add(VisLabel(describe(g, items)).apply { setWrap(false) }).width(200f)
                if (view.own) {
                    if (!view.open) body.add(button("Remove") { sendCommand("/stall remove ${g.number}") }).row() else body.add(VisLabel("")).row()
                } else {
                    body.add(button("Buy 1") { sendCommand("/stall buy ${view.ownerName} ${g.number} 1") }).row()
                }
            }
        }

        // --- buying
        body.add(VisLabel("Buy from a stall nearby", theme.mutedLabelStyle)).colspan(2).padTop(10f).row()
        val buyer = VisTextField().apply { messageText = "Seller name"; maxLength = 24 }
        val number = VisTextField().apply { messageText = "Listing #"; maxLength = 3 }
        val count = VisTextField().apply { messageText = "Units (blank = 1)"; maxLength = 6 }
        val near = StallCommands.nearby(worldState.playersOnMap.values, ch.id, ch.position.tileX, ch.position.tileY, rules?.rangeTiles ?: 6)
        if (near.isEmpty()) {
            body.add(VisLabel("No open stalls within reach.", theme.mutedLabelStyle)).colspan(2).row()
        } else {
            near.take(6).forEach { p ->
                body.add(VisLabel("${p.name}: ${p.stallTitle}").apply { setWrap(false) }).width(160f)
                body.add(button("View") { buyer.text = p.name; sendCommand("/stall view ${p.name}") }).row()
            }
        }
        body.add(buyer).width(125f)
        body.add(number).width(125f).row()
        body.add(count).width(125f)
        body.add(button("Buy") { run(StallCommands.buy(buyer.text, number.text, count.text), "Name the seller and the listing number.") }).row()

        win.add(body)
        win.pack()
        if (at != null) win.setPosition(at.first, at.second) else win.setPosition((stage.width - win.width) / 2f, (stage.height - win.height) / 2f)
        stage.addActor(win)
        window = win
    }
}
