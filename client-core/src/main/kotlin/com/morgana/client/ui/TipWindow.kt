package com.morgana.client.ui

import com.badlogic.gdx.scenes.scene2d.Stage
import com.kotcrab.vis.ui.widget.VisTable
import com.morgana.client.ui.bag.BagUi
import com.morgana.client.ui.bag.Tone

/**
 * A new-player tip: a small card near the top of the screen with the advice, **Got it** to close it and **Turn off tips**
 * to stop them for good. It never blocks the game (the player can keep moving) and closes itself after [LIFETIME_SECONDS],
 * since the server already counted it as shown.
 *
 * @param onClosed called once when the card goes away for any reason (the player's tip queue moves on)
 * @param onTurnOff called when the player asks for no more tips
 */
class TipWindow(
    stage: Stage,
    title: String,
    text: String,
    private val onClosed: () -> Unit,
    onTurnOff: () -> Unit
) : HudWindow(title.ifBlank { "Tip" }) {

    private var age = 0f
    private var closed = false

    init {
        style = GameTheme.shared.windowStyle
        isMovable = true
        addCloseButton()
        defaults().pad(4f)
        val width = minOf(CARD_WIDTH, stage.width - 24f).coerceAtLeast(220f)
        val body = VisTable()
        body.add(BagUi.wrapped(text, Tone.NEUTRAL)).width(width - 24f).left().row()
        val buttons = VisTable()
        buttons.add(BagUi.button("Got it") { close() }).height(BagUi.buttonHeight).padRight(6f)
        buttons.add(BagUi.button("Turn off tips") { onTurnOff(); close() }).height(BagUi.buttonHeight)
        body.add(buttons).left().padTop(6f)
        add(body).grow()
        pack()
        setWidth(width)
        pack()
        setPosition((stage.width - this.width) / 2f, stage.height - this.height - TOP_MARGIN)
    }

    override fun act(delta: Float) {
        super.act(delta)
        age += delta
        if (age >= LIFETIME_SECONDS) close()
    }

    /** The close (X) button and Got it both end here. */
    override fun close() {
        if (closed) return
        closed = true
        remove()
        onClosed()
    }

    companion object {
        const val CARD_WIDTH = 360f
        const val TOP_MARGIN = 96f
        const val LIFETIME_SECONDS = 60f
    }
}
