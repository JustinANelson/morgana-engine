package com.morgana.client.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.VerticalGroup
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.morgana.client.ui.editor.EditorPalette

/**
 * Draws the [ToastQueue] as compact, semi-transparent pills stacked at the bottom-center (just above the chat button)
 * so they never cover the player or the target bar. Uses a [VerticalGroup] so retired cards leave no dead layout cells.
 * Never takes input, so the map stays clickable beneath them.
 */
class ToastOverlay(private val stage: Stage, private val queue: ToastQueue) {
    private val stack = VerticalGroup().apply {
        align(Align.top)
        columnAlign(Align.center)
        space(4f)
        touchable = Touchable.disabled
    }
    private val root = VisTable().apply {
        setFillParent(true)
        bottom().padBottom(58f)
        touchable = Touchable.disabled
        add(stack).minHeight(96f).top().center()
    }
    private val cards = LinkedHashMap<String, Actor>()
    private var installed = false

    /** Call every frame: starts cards for new toasts and retires expired ones. */
    fun update() {
        if (!installed) {
            stage.addActor(root)
            installed = true
        }
        val now = ToastClock.now()
        for (toast in queue.drainFresh()) addCard(toast)
        queue.expire(now)
        // A card whose toast expired or was pushed out of the queue fades away; a restarted toast keeps its card.
        val live = queue.visible.mapTo(HashSet()) { it.text }
        for ((text, card) in cards.entries.toList()) if (text !in live) retire(text, card)
        if (root.stage != null) root.toFront()
    }

    private fun addCard(toast: Toast) {
        // Never orphan an older actor if a toast with the same text is re-added before the previous one retired.
        cards.remove(toast.text)?.let { existing ->
            existing.clearActions()
            stack.removeActor(existing)
            stack.invalidateHierarchy()
        }
        val theme = GameTheme.shared
        val isBroadcast = toast.kind == ToastKind.BROADCAST
        val isLootDrop = toast.kind == ToastKind.GOOD && toast.text.startsWith("+")
        val card = VisTable()
        if (isBroadcast) {
            card.background = theme.dynamic { t, _ ->
                t.bordered(Color(0.16f, 0.11f, 0.03f, 0.86f), BROADCAST_ACCENT, padX = 16f, padY = 8f)
            }
        } else if (!isLootDrop) {
            card.background = theme.dynamic { t, p ->
                val bg = Color(p.panelRaised.r, p.panelRaised.g, p.panelRaised.b, 0.68f)
                val b = accent(toast.kind, p)
                val border = Color(b.r, b.g, b.b, 0.85f)
                t.bordered(bg, border, padX = 10f, padY = 4f)
            }
        }
        val label = VisLabel(toast.text, theme.labelStyle)
        if (isBroadcast) {
            label.setFontScale(1.15f)
            label.color = BROADCAST_TEXT
        } else if (isLootDrop) {
            label.setFontScale(0.98f)
            label.color = LOOT_TEXT
        } else {
            label.setFontScale(0.94f)
        }
        label.setAlignment(Align.center)
        val maxW = if (isBroadcast) minOf(440f, stage.width - 48f) else minOf(260f, (stage.width - 140f).coerceAtLeast(160f))
        val naturalW = label.prefWidth
        if (isLootDrop) {
            // Crisp 4-way outlined text with no background box so loot drops stay readable on bright sand/grass and dark dungeons alike.
            val wrap = naturalW > maxW
            if (wrap) label.setWrap(true)
            val layered = com.badlogic.gdx.scenes.scene2d.ui.Stack()
            val outlineOffsets = arrayOf(
                -1f to 0f,
                1f to 0f,
                0f to -1f,
                0f to 1f,
                1f to 1f
            )
            for ((ox, oy) in outlineOffsets) {
                val outline = VisLabel(toast.text, theme.labelStyle).apply {
                    setFontScale(0.98f)
                    color = Color(0f, 0f, 0f, 0.92f)
                    setAlignment(Align.center)
                    if (wrap) setWrap(true)
                }
                val wrapTable = VisTable().apply {
                    val cell = if (wrap) add(outline).width(maxW) else add(outline)
                    if (ox > 0f) cell.padLeft(ox) else if (ox < 0f) cell.padRight(-ox)
                    if (oy > 0f) cell.padTop(oy) else if (oy < 0f) cell.padBottom(-oy)
                }
                layered.add(wrapTable)
            }
            val fgWrap = VisTable().apply {
                if (wrap) add(label).width(maxW) else add(label)
            }
            layered.add(fgWrap)
            card.add(layered).pad(1f, 4f, 1f, 4f)
        } else if (naturalW > maxW) {
            label.setWrap(true)
            card.add(label).width(maxW)
        } else {
            card.add(label)
        }
        if (com.morgana.client.access.Accessibility.reduceMotion) {
            card.color.a = 1f
        } else if (isBroadcast) {
            card.isTransform = true
            card.color.a = 0f
            card.setScale(0.9f)
            card.addAction(
                Actions.parallel(
                    Actions.fadeIn(0.2f),
                    Actions.sequence(Actions.scaleTo(1.04f, 1.04f, 0.16f), Actions.scaleTo(1f, 1f, 0.12f))
                )
            )
        } else {
            card.color.a = 0f
            card.addAction(Actions.fadeIn(0.14f))
        }
        stack.addActor(card)
        cards[toast.text] = card
    }

    private fun retire(text: String, card: Actor) {
        cards.remove(text)
        card.clearActions()
        if (com.morgana.client.access.Accessibility.reduceMotion) {
            stack.removeActor(card)
            stack.invalidateHierarchy()
            return
        }
        card.addAction(
            Actions.sequence(
                Actions.fadeOut(0.18f),
                Actions.run { stack.removeActor(card); stack.invalidateHierarchy() }
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
        private val LOOT_TEXT = Color(0.92f, 1f, 0.86f, 1f)
    }
}
