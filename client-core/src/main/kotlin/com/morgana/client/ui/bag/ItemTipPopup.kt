package com.morgana.client.ui.bag

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.morgana.client.ui.GameTheme

/**
 * The floating card that describes an item while the pointer rests on it. One popup is reused: [show] fills it and
 * places it beside the pointer, kept on screen; [hide] takes it off the stage. It never takes input.
 */
internal class ItemTipPopup {
    private var card: VisTable? = null

    /** Shows [tip] beside the point; when [owner] leaves the stage (its window closed) the card goes with it. */
    fun show(stage: Stage, tip: ItemTip, stageX: Float, stageY: Float, owner: Actor? = null, aboveFinger: Boolean = false) {
        hide()
        val theme = GameTheme.shared
        val t = VisTable()
        t.touchable = Touchable.disabled
        t.background = theme.dynamic { b, p -> b.beveledPanel(p.panelRaised, highlight = p.accent, cornerAccent = p.strong, padX = 10f, padY = 8f) }
        t.add(VisLabel(tip.title, theme.accentLabelStyle).also { l -> BagUi.hexColor(tip.titleColor)?.let { l.color = it } }).left().row()
        t.add(BagUi.label(tip.kind, Tone.MUTED)).left().padBottom(3f).row()
        if (tip.description.isNotBlank()) {
            t.add(wrapped(tip.description, Tone.MUTED)).width(WIDTH).left().padBottom(3f).row()
        }
        for (line in tip.lines) t.add(wrapped(line.text, line.tone)).width(WIDTH).left().row()
        t.pack()
        // Beside the pointer, flipped to the other side when it would run off the screen.
        var x = stageX + OFFSET
        var y = stageY - OFFSET - t.height
        if (x + t.width > stage.width) x = stageX - OFFSET - t.width
        if (y < 0f) y = stageY + OFFSET
        if (aboveFinger) {
            // A finger covers what is under it, so a long-press card sits above the touch point, centred on it.
            x = stageX - t.width / 2f
            y = stageY + FINGER_CLEARANCE
            if (y + t.height > stage.height) y = stageY - FINGER_CLEARANCE - t.height
        }
        t.setPosition(x.coerceIn(0f, (stage.width - t.width).coerceAtLeast(0f)), y.coerceIn(0f, (stage.height - t.height).coerceAtLeast(0f)))
        t.color.a = 0f
        t.addAction(Actions.fadeIn(0.08f))
        if (owner != null) t.addAction(Actions.forever(Actions.run { if (owner.stage == null) hide() }))
        stage.addActor(t)
        t.toFront()
        card = t
    }

    fun hide() {
        card?.remove()
        card = null
    }

    val isShowing: Boolean get() = card?.stage != null

    private fun wrapped(text: String, tone: Tone): Label = BagUi.wrapped(text, tone)

    private companion object {
        const val WIDTH = 220f
        const val OFFSET = 14f
        const val FINGER_CLEARANCE = 36f
    }
}

/**
 * Shows a tooltip card while the pointer rests on [target] for [delaySeconds]. [provider] is asked when the card is about
 * to appear, so it reflects the moment (current mana, cooldown); returning null shows nothing. Desktop and browser only.
 */
internal fun attachHoverTip(target: Actor, delaySeconds: Float = 0.3f, provider: () -> ItemTip?) {
    val popup = ItemTipPopup()
    target.addListener(object : com.badlogic.gdx.scenes.scene2d.InputListener() {
        override fun enter(event: com.badlogic.gdx.scenes.scene2d.InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
            if (pointer != -1) return
            val sx = event.stageX
            val sy = event.stageY
            target.clearActions()
            target.addAction(
                Actions.sequence(
                    Actions.delay(delaySeconds),
                    Actions.run {
                        val st = target.stage
                        val tip = provider()
                        if (st != null && tip != null) popup.show(st, tip, sx, sy, target)
                    }
                )
            )
        }

        override fun exit(event: com.badlogic.gdx.scenes.scene2d.InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
            if (pointer != -1) return
            target.clearActions()
            popup.hide()
        }

        override fun touchDown(event: com.badlogic.gdx.scenes.scene2d.InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
            target.clearActions()
            popup.hide()
            if (!com.morgana.client.ui.HudWindow.touchMode) return false
            // Touch has no hover: holding a finger on the widget shows the card above it until the finger lifts.
            val sx = event.stageX
            val sy = event.stageY
            target.addAction(
                Actions.sequence(
                    Actions.delay(LONG_PRESS_SECONDS),
                    Actions.run {
                        val st = target.stage
                        val tip = provider()
                        if (st != null && tip != null) popup.show(st, tip, sx, sy, target, aboveFinger = true)
                    }
                )
            )
            return true
        }

        override fun touchUp(event: com.badlogic.gdx.scenes.scene2d.InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
            target.clearActions()
            popup.hide()
        }
    })
}

/** How long a finger must rest on something before its tooltip card appears on touch devices. */
internal const val LONG_PRESS_SECONDS = 0.45f
