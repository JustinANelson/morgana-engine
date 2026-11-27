package com.morgana.client.ui

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisWindow

/**
 * A HUD window that can be minimized to just its title bar, so players can reclaim screen space (essential on phones).
 *
 * Minimize with the `-` button in the title bar or by double-tapping/double-clicking the title; both restore it again.
 * Collapsed, it shrinks to just its title bar (width as well as height). It keeps its top-left corner in place, and is
 * semi-transparent ([opacity]) so the map stays visible behind it.
 */
open class HudWindow(private val windowTitle: String, private val rememberAs: String? = null) : VisWindow(windowTitle) {

    /** True once [close] has been triggered (e.g. via the X button), even while fade-out is still running. */
    var closedByUser: Boolean = false
        private set

    /** True while only the title bar is showing. */
    var collapsed: Boolean = false
        private set

    private val minimizeButton = VisTextButton("-", GameTheme.shared.titleButtonStyle)

    /** Actors that stay on screen while the window is minimized (the vitals of the status window); empty = everything hides. */
    private val keptWhenCollapsed = mutableSetOf<Actor>()

    /** Short text shown after the title ("3" -> "Chat (3)"), such as an unread count; empty shows the plain title. */
    var badge: String = ""
        set(value) {
            if (field == value) return
            field = value
            titleLabel.setText(if (value.isEmpty()) windowTitle else "$windowTitle ($value)")
        }

    /** The layout constraints of each row folded away while minimized, to restore them. */
    private val foldedCells = HashMap<Cell<*>, SavedSize>()

    /** A cell's size and padding constraints, which [Cell] only lets other packages read and set one by one. */
    private class SavedSize(c: Cell<*>) {
        private val minW = c.minWidthValue; private val minH = c.minHeightValue
        private val prefW = c.prefWidthValue; private val prefH = c.prefHeightValue
        private val maxW = c.maxWidthValue; private val maxH = c.maxHeightValue
        private val padT = c.padTopValue; private val padL = c.padLeftValue
        private val padB = c.padBottomValue; private val padR = c.padRightValue

        fun restore(c: Cell<*>) {
            c.minWidth(minW).minHeight(minH).prefWidth(prefW).prefHeight(prefH).maxWidth(maxW).maxHeight(maxH)
            c.padTop(padT).padLeft(padL).padBottom(padB).padRight(padR)
        }
    }

    /** Marks [actors] as staying visible when the window is minimized; the rest of the window still folds away. */
    fun keepWhenCollapsed(vararg actors: Actor) {
        keptWhenCollapsed.addAll(actors)
    }

    init {
        style = GameTheme.shared.windowStyle
        color.a = opacity
        val side = if (touchMode) TOUCH_BUTTON_SIZE else DESKTOP_BUTTON_SIZE
        val btnWidth = if (touchMode) TOUCH_BUTTON_WIDTH else DESKTOP_BUTTON_SIZE
        if (touchMode) padTop(maxOf(padTop, side + 6f))
        titleTable.padLeft(6f).padRight(4f).padTop(1f).padBottom(2f)
        minimizeButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                toggleCollapsed()
            }
        })
        // Stop touchDown from bubbling to Window so tapping '-' on mobile never triggers a window drag/snap.
        stopDragOnPress(minimizeButton)
        titleTable.add(minimizeButton).size(btnWidth, side).padRight(4f)
        titleTable.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                if (tapCount == 2) toggleCollapsed()
            }
        })
    }

    /**
     * Clearing a libGDX Window also removes its title bar (the table holding the minimize and close buttons), which left
     * windows rebuilt with `clearChildren()` with dead title buttons. Every HudWindow keeps its title bar through a clear,
     * so any window can rebuild its body without caring.
     */
    override fun clearChildren(unfocus: Boolean) {
        super.clearChildren(unfocus)
        foldedCells.clear()
        if (titleTable.parent !== this) addActor(titleTable)
    }

    /** Adds a themed bronze RPG 'X' close button matched to the minimize button, hardened for touchscreens. */
    override fun addCloseButton() {
        val side = if (touchMode) TOUCH_BUTTON_SIZE else DESKTOP_BUTTON_SIZE
        val btnWidth = if (touchMode) TOUCH_BUTTON_WIDTH else DESKTOP_BUTTON_SIZE
        val closeButton = VisTextButton("X", GameTheme.shared.titleButtonStyle).apply {
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    close()
                }
            })
        }
        stopDragOnPress(closeButton)
        titleTable.add(closeButton).size(btnWidth, side)
    }

    override fun close() {
        closedByUser = true
        super.close()
    }

    /** Fades in each time the window is added to a stage, so panels ease in instead of popping. */
    override fun setStage(stage: com.badlogic.gdx.scenes.scene2d.Stage?) {
        val appearing = stage != null && getStage() == null
        super.setStage(stage)
        if (appearing) {
            restoreSavedPosition(stage!!)
            closedByUser = false
            clearActions()
            if (com.morgana.client.access.Accessibility.reduceMotion) {
                color.a = opacity
            } else {
                color.a = 0f
                addAction(com.badlogic.gdx.scenes.scene2d.actions.Actions.alpha(opacity, FADE_IN_SECONDS))
            }
        }
    }

    /** The key this window's position is saved under, or null when the window is not one that remembers where it was left. */
    private val positionKey: String? get() = rememberAs ?: windowTitle.takeIf { it in REMEMBERED_TITLES }

    private var wasDragging = false

    private fun restoreSavedPosition(stage: com.badlogic.gdx.scenes.scene2d.Stage) {
        val key = positionKey ?: return
        val rect = WindowPlacement.restore(positionStore.get(key), width, height, stage.width, stage.height)
        if (rect != null) super.setPosition(rect.x, rect.y)
        // A window the player left minimized comes back minimized (the top-left corner stays put).
        if (positionStore.get(collapsedKey(key)) == "1") setCollapsed(true)
    }

    /** While being dragged the window snaps to the screen edges and to the other HUD windows. */
    override fun setPosition(x: Float, y: Float) {
        val st = stage
        if (!isDragging || st == null) {
            super.setPosition(x, y)
            return
        }
        val others = st.actors.filterIsInstance<HudWindow>().filter { it !== this && it.isVisible && it.stage != null }
            .map { WindowRect(it.x, it.y, it.width, it.height) }
        val snapped = WindowPlacement.snap(WindowRect(x, y, width, height), st.width, st.height, others)
        super.setPosition(snapped.x, snapped.y)
    }

    /** Saves where a remembered window was left once a drag ends. */
    override fun act(delta: Float) {
        super.act(delta)
        val dragging = isDragging
        if (wasDragging && !dragging) positionKey?.let { positionStore.put(it, WindowPlacement.format(WindowRect(x, y, width, height))) }
        wasDragging = dragging
    }

    /** While collapsed the window reports only its title bar as its preferred height, so [pack] shrinks it. */
    override fun getPrefHeight(): Float =
        if (collapsed && keptWhenCollapsed.isEmpty()) padTop + padBottom else super.getPrefHeight()

    /** The player's own minimize/restore click: also remembered for next time (a layout-driven [setCollapsed] is not). */
    fun toggleCollapsed() {
        setCollapsed(!collapsed)
        positionKey?.let { positionStore.put(collapsedKey(it), if (collapsed) "1" else "0") }
    }

    /** Where the title bar sat (left edge, top edge) the last time the window was minimized, so minimizing again returns there. */
    private var minimizedLeft = Float.NaN
    private var minimizedTop = Float.NaN

    fun setCollapsed(value: Boolean) {
        if (value == collapsed) return
        // Opening remembers the minimized spot: the window grows from it and may be pushed back on screen, but folding
        // up again puts the title bar back where the player left it (e.g. the bottom of the screen).
        if (!value) { minimizedLeft = x; minimizedTop = y + height }
        val top = y + height
        collapsed = value
        val partial = keptWhenCollapsed.isNotEmpty()
        for (cell in cells) {
            val actor = cell.actor ?: continue
            val shown = !value || actor in keptWhenCollapsed
            actor.isVisible = shown
            // A folded row is squeezed to nothing (and its constraints put back on restore), so a partly minimized
            // window shrinks to just the rows that stay.
            if (partial) {
                if (!shown) {
                    foldedCells.getOrPut(cell) { SavedSize(cell) }
                    cell.size(0f).pad(0f)
                } else {
                    foldedCells.remove(cell)?.restore(cell)
                }
            }
        }
        minimizeButton.setText(if (value) "+" else "-")
        invalidateHierarchy()
        pack()
        y = top - height
        val st = stage
        if (!value && st != null) y = y.coerceIn(0f, (st.height - height).coerceAtLeast(0f))
        if (value && !minimizedTop.isNaN()) {
            x = minimizedLeft
            y = minimizedTop - height
        }
    }

    companion object {
        /** Windows with fixed titles that remember where the player put them; windows titled by a person or a quest do not. */
        private val REMEMBERED_TITLES = setOf(
            "Morgana ORPG", "Chat & Command Console", "Quickbar (1..0)", "Minimap (M)", "Inventory", "Spellbook",
            "Skills", "Quest Log", "Crafting", "Quest Tracker", "World Map", "Party Management", "Settings", "Royal Bank Vault", "Menu"
        )

        /** Forgets every saved window position, so each remembered window opens in its default place again. */
        fun resetSavedPositions() {
            REMEMBERED_TITLES.forEach { positionStore.remove(it); positionStore.remove(collapsedKey(it)) }
        }

        /** The preference key holding whether the window with position key [key] was left minimized. */
        internal fun collapsedKey(key: String) = "$key#collapsed"

        private val positionStore by lazy { com.morgana.client.ui.editor.PreferencesKeyValueStore("morgana-hud-windows") }

        /** Window transparency (1 = solid). Change with [applyOpacity] so open windows update too. */
        var opacity: Float = 0.94f
            private set

        fun applyOpacity(stage: com.badlogic.gdx.scenes.scene2d.Stage, value: Float) {
            opacity = value.coerceIn(0.3f, 1f)
            stage.actors.forEach { if (it is HudWindow) it.color.a = opacity }
        }

        /** Prevents a button inside a draggable header/title bar from starting a drag on touchDown. */
        fun stopDragOnPress(actor: com.badlogic.gdx.scenes.scene2d.Actor) {
            actor.addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    event.stop()
                    return true
                }
            })
        }

        /** Hardens the most recently added title-bar button on a [VisWindow] for touch input. */
        fun hardenLastTitleButton(win: VisWindow) {
            val cells = win.titleTable.cells
            if (cells.size == 0) return
            val cell = cells.get(cells.size - 1)
            cell.actor?.let { stopDragOnPress(it) }
            if (touchMode) {
                win.padTop(maxOf(win.padTop, TOUCH_BUTTON_SIZE + 4f))
                cell.size(TOUCH_BUTTON_WIDTH, TOUCH_BUTTON_SIZE)
            }
        }

        /** Set once at startup by the platform (touch devices get larger hit areas). */
        var touchMode: Boolean = false
        private const val FADE_IN_SECONDS = 0.14f
        private const val DESKTOP_BUTTON_SIZE = 20f
        private const val TOUCH_BUTTON_SIZE = 34f
        private const val TOUCH_BUTTON_WIDTH = 44f
    }
}
