package com.morgana.client.ui.editor

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextField
import com.morgana.client.editor.dock.LayoutLibrary
import com.morgana.client.editor.dock.LayoutPreset
import com.morgana.client.editor.dock.PanelInfo
import com.morgana.client.editor.dock.PanelSearch
import com.morgana.client.editor.dock.PanelState

/**
 * The "Editors" popup: type to find any editor and open it (Enter opens the first match), and keep arrangements of
 * panels as layouts. Layouts are the built-in [presets] plus the developer's own, saved by name in the [library]; saving
 * stores the arrangement as it is now (which panels are shown, where, and how big).
 *
 * Opened from the toolbar's Editors button or the P key; Escape or the close button dismisses it. Drag its title to move it.
 */
class PanelFinder(
    private val stage: Stage,
    private val theme: EditorTheme,
    private val workspace: EditorWorkspace,
    private val presets: List<LayoutPreset>,
    private val library: LayoutLibrary
) {
    private val root = VisTable()
    private val search = VisTextField()
    private val panelList = VisTable()
    private val layoutList = VisTable()
    private val nameField = VisTextField()
    private val message = theme.muted("")
    private val panelCell: com.badlogic.gdx.scenes.scene2d.ui.Cell<*>

    val isOpen: Boolean get() = root.stage != null

    /** Where the developer dragged the finder (its bottom-left corner), or null for the default top-right spot. */
    private var movedTo: Pair<Float, Float>? = null

    /** Moves the finder, keeping its title row on screen so it can always be dragged back. */
    private fun moveTo(x: Float, y: Float) {
        val nx = x.coerceIn(-(root.width - GRAB_MARGIN), stage.width - GRAB_MARGIN)
        val ny = y.coerceIn(-(root.height - TITLE_HEIGHT), stage.height - root.height)
        root.setPosition(Math.round(nx).toFloat(), Math.round(ny).toFloat())
    }

    init {
        root.background = theme.bordered(theme.palette.panel, theme.palette.border, padX = 8f, padY = 8f)
        root.touchable = Touchable.enabled
        search.messageText = "Find an editor (type part of its name)"
        nameField.messageText = "Name this layout"

        val header = VisTable()
        header.touchable = Touchable.enabled
        header.add(theme.heading("Editors")).growX()
        val closeButton = theme.iconButton("close", "Close (Esc)") { close() }
        header.add(closeButton).size(24f).padLeft(6f).top()
        root.add(header).growX().padBottom(4f).row()
        // Drag the title to move the finder out of the way; it reopens where it was left.
        header.addListener(object : InputListener() {
            private var grabX = 0f
            private var grabY = 0f

            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                if (button != Input.Buttons.LEFT || event.target.isDescendantOf(closeButton)) return false
                grabX = event.stageX - root.x
                grabY = event.stageY - root.y
                return true
            }

            override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                moveTo(event.stageX - grabX, event.stageY - grabY)
                movedTo = root.x to root.y
            }
        })
        root.add(search).growX().padBottom(4f).row()

        val panelScroll = VisScrollPane(panelList)
        panelScroll.setFadeScrollBars(false)
        panelScroll.setScrollingDisabled(true, false)
        giveScrollFocusOnHover(stage, panelScroll)
        panelCell = root.add(panelScroll).growX().height(PANEL_LIST_HEIGHT).padBottom(6f)
        root.row()

        root.add(theme.heading("Layouts")).growX().row()
        val layoutScroll = VisScrollPane(layoutList)
        layoutScroll.setFadeScrollBars(false)
        layoutScroll.setScrollingDisabled(true, false)
        giveScrollFocusOnHover(stage, layoutScroll)
        root.add(layoutScroll).growX().height(LAYOUT_LIST_HEIGHT).padBottom(4f).row()

        val saveRow = VisTable()
        saveRow.add(nameField).growX().padRight(4f)
        saveRow.add(theme.textButton("Save current layout") { saveLayout() })
        root.add(saveRow).growX().padBottom(2f).row()
        root.add(message).growX().left().row()
        val keys = theme.muted("Keys: B brush, R rectangle, G bucket, E eraser, I eyedropper, N NPC spawn, H grid, D dim layers, - and = zoom, Ctrl+Z undo, Ctrl+S save map, P this window, F1 close the editor.")
        keys.setWrap(true)
        root.add(keys).growX().width(WIDTH - 24f).padTop(4f).row()

        search.setTextFieldListener { _, _ -> fillPanels() }
        root.addCaptureListener(object : InputListener() {
            override fun keyDown(event: InputEvent, keycode: Int): Boolean {
                if (keycode == Input.Keys.ESCAPE) { close(); return true }
                if (keycode == Input.Keys.ENTER && stage.keyboardFocus === search) { openFirstMatch(); return true }
                return false
            }
        })
        workspace.onLayoutChanged = { if (isOpen) { fillPanels(); fillLayouts() } }
    }

    fun toggle() { if (isOpen) close() else open() }

    fun open() {
        if (isOpen) return
        search.text = ""
        pendingDelete = null
        nameField.text = ""
        message.setText("")
        fillPanels()
        fillLayouts()
        // As tall as the screen allows (so more panels show without scrolling); the layouts below keep their size.
        val total = (stage.height - TOP_OFFSET - 12f).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        panelCell.height((total - LAYOUT_LIST_HEIGHT - CHROME_HEIGHT).coerceAtLeast(120f))
        root.invalidate()
        root.setSize(WIDTH, total)
        val placed = movedTo
        if (placed != null) moveTo(placed.first, placed.second)
        else root.setPosition(Math.round((stage.width - WIDTH - 8f).coerceAtLeast(0f)).toFloat(), Math.round((stage.height - TOP_OFFSET - root.height).coerceAtLeast(0f)).toFloat())
        stage.addActor(root)
        root.toFront()
        stage.keyboardFocus = search
    }

    fun close() {
        if (!isOpen) return
        if (stage.keyboardFocus === search || stage.keyboardFocus === nameField) stage.keyboardFocus = null
        root.remove()
    }

    private fun openFirstMatch() {
        val first = PanelSearch.filter(workspace.panelInfos(), search.text).firstOrNull() ?: return
        openPanel(first)
    }

    private fun openPanel(panel: PanelInfo) {
        workspace.openPanel(panel.id)
        close()
    }

    private fun fillPanels() {
        panelList.clearChildren()
        panelList.defaults().growX().pad(1f)
        val matches = PanelSearch.filter(workspace.panelInfos(), search.text)
        if (matches.isEmpty()) {
            panelList.add(theme.muted("No editor matches \"${search.text.trim()}\"")).left().pad(4f).row()
            return
        }
        var group: String? = null
        for (p in matches) {
            if (p.group != group) {
                group = p.group
                if (p.group.isNotEmpty()) panelList.add(VisLabel(p.group.uppercase(), theme.accentLabel)).left().padTop(4f).row()
            }
            val row = VisTable()
            row.add(theme.textButton(p.title) { openPanel(p) }).width(124f).height(26f)
            val about = theme.muted(p.description)
            about.setWrap(true)
            row.add(about).left().growX().minWidth(0f).padLeft(8f)
            row.add(theme.muted(when (p.state) { PanelState.SHOWN -> "open"; PanelState.MINIMIZED -> "minimized"; PanelState.HIDDEN -> "" })).width(66f).padLeft(6f)
            panelList.add(row).row()
        }
        panelList.add().growY().row()
    }

    private fun fillLayouts() {
        layoutList.clearChildren()
        layoutList.defaults().growX().pad(1f)
        // The built-in layouts as a grid of two buttons per row; hovering one says what it shows.
        val grid = VisTable()
        presets.forEachIndexed { i, preset ->
            val use = theme.textButton(preset.name) { applyPreset(preset) }
            theme.tip(use, preset.description)
            grid.add(use).growX().uniformX().height(26f).pad(1f)
            if (i % 2 == 1) grid.row()
        }
        layoutList.add(grid).growX().row()
        val saved = library.names()
        if (saved.isNotEmpty()) layoutList.add(VisLabel("YOURS", theme.accentLabel)).left().padTop(4f).row()
        for (name in saved) {
            val row = VisTable()
            row.add(VisLabel(name, theme.label)).left().growX()
            row.add(theme.textButton("Load") { loadLayout(name) }).padLeft(6f).height(24f)
            val confirming = pendingDelete == name
            row.add(theme.textButton(if (confirming) "Really delete?" else "Delete") { if (confirming) deleteLayout(name) else { pendingDelete = name; fillLayouts(); message.setText("Click again to delete \"$name\"") } }).padLeft(4f).height(24f)
            layoutList.add(row).row()
        }
        layoutList.add().growY().row()
    }

    private fun applyPreset(preset: LayoutPreset) {
        workspace.applyPreset(preset.panelIds)
        message.setText("Showing the ${preset.name} layout")
    }

    private fun loadLayout(name: String) {
        val text = library.load(name)
        if (text == null) { message.setText("Could not find \"$name\""); return }
        workspace.applyLayoutText(text)
        message.setText("Loaded \"$name\"")
    }

    private var pendingDelete: String? = null

    private fun deleteLayout(name: String) {
        pendingDelete = null
        library.delete(name)
        fillLayouts()
        message.setText("Deleted \"$name\"")
    }

    private fun saveLayout() {
        val name = LayoutLibrary.tidy(nameField.text)
        val problem = library.save(name, workspace.layoutText())
        if (problem != null) { message.setText(problem); return }
        nameField.text = ""
        fillLayouts()
        message.setText("Saved \"$name\"")
    }

    companion object {
        private const val WIDTH = 480f
        private const val PANEL_LIST_HEIGHT = 240f
        private const val LAYOUT_LIST_HEIGHT = 120f
        private const val CHROME_HEIGHT = 230f
        private const val MIN_HEIGHT = 420f
        private const val MAX_HEIGHT = 640f
        private const val TOP_OFFSET = 48f
        private const val GRAB_MARGIN = 60f
        private const val TITLE_HEIGHT = 36f
    }
}
