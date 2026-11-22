package com.morgana.client.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.morgana.client.ui.editor.EditorPalette

/**
 * Draws the [ToastQueue] as small cards stacked under the top edge of the screen: each fades in, waits, fades out.
 * Cards take their border from the toast's kind and their colours from the shared [GameTheme], so they follow the scheme.
 * Never takes input, so the map stays clickable beneath them.
 */
class ToastOverlay(private val stage: Stage, private val queue: ToastQueue) {
    private val column = VisTable().apply {
        setFillParent(true)
        top().padTop(48f)
        touchable = Touchable.disabled
    }
    private val cards = LinkedHashMap<String, Actor>()
    private var installed = false

    /** Call every frame: starts cards for new toasts and retires expired ones. */
    fun update() {
        if (!installed) {
            stage.addActor(column)
            installed = true
        }
        val now = ToastClock.now()
        for (toast in queue.drainFresh()) addCard(toast)
        queue.expire(now)
        // A card whose toast expired or was pushed out of the queue fades away; a restarted toast keeps its card.
        val live = queue.visible.mapTo(HashSet()) { it.text }
        for ((text, card) in cards.entries.toList()) if (text !in live) retire(text, card)
        if (column.stage != null) column.toFront()
    }

    private fun addCard(toast: Toast) {
        val theme = GameTheme.shared
        val isBroadcast = toast.kind == ToastKind.BROADCAST
        val card = VisTable()
        if (isBroadcast) {
            card.background = theme.dynamic { t, _ ->
                t.bordered(Color(0.16f, 0.11f, 0.03f, 0.96f), BROADCAST_ACCENT, padX = 22f, padY = 12f)
            }
        } else {
            card.background = theme.dynamic { t, p -> t.bordered(p.panelRaised, accent(toast.kind, p), padX = 12f, padY = 6f) }
        }
        val label = VisLabel(toast.text, theme.labelStyle)
        if (isBroadcast) {
            label.setFontScale(1.3f)
            label.color = BROADCAST_TEXT
        }
        label.setWrap(true)
        label.setAlignment(Align.center)
        val maxW = if (isBroadcast) minOf(520f, stage.width - 32f) else minOf(360f, stage.width - 48f)
        card.add(label).width(maxW)
        if (com.morgana.client.access.Accessibility.reduceMotion) {
            card.color.a = 1f
        } else if (isBroadcast) {
            card.isTransform = true
            card.color.a = 0f
            card.setScale(0.86f)
            card.addAction(
                Actions.parallel(
                    Actions.fadeIn(0.22f),
                    Actions.sequence(Actions.scaleTo(1.06f, 1.06f, 0.18f), Actions.scaleTo(1f, 1f, 0.14f))
                )
            )
        } else {
            card.color.a = 0f
            card.addAction(Actions.fadeIn(0.18f))
        }
        column.add(card).padBottom(if (isBroadcast) 8f else 4f).row()
        cards[toast.text] = card
    }

    private fun retire(text: String, card: Actor) {
        cards.remove(text)
        card.clearActions()
        if (com.morgana.client.access.Accessibility.reduceMotion) {
            column.removeActor(card)
            column.invalidateHierarchy()
            return
        }
        card.addAction(
            Actions.sequence(
                Actions.fadeOut(0.25f),
                Actions.run { column.removeActor(card); column.invalidateHierarchy() }
            )
        )
    }

    private fun accent(kind: ToastKind, p: EditorPalette): Color = when (kind) {
        ToastKind.INFO -> p.border
        ToastKind.GOOD -> p.success
        ToastKind.WARNING -> p.warning
        ToastKind.LEVEL -> p.accent
        ToastKind.BROADCAST -> BROADCAST_ACCENT
    }

    companion object {
        private val BROADCAST_ACCENT = Color(1f, 0.78f, 0.18f, 1f)
        private val BROADCAST_TEXT = Color(1f, 0.96f, 0.80f, 1f)
    }
}
