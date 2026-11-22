package com.morgana.client.ui.editor

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.kotcrab.vis.ui.widget.VisImageButton
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.morgana.client.editor.dock.DockLayout
import com.morgana.client.editor.dock.DockZone
import com.morgana.client.editor.dock.PanelInfo
import com.morgana.client.editor.dock.PanelPlacement
import com.morgana.client.editor.dock.PanelRect
import com.morgana.client.editor.dock.PanelState
import com.morgana.client.editor.dock.PanelSpec
import com.morgana.client.editor.dock.ViewInsets

/** One panel of the editor: a title, a content widget, and where it lives by default. */
class EditorPanel(
    val id: String,
    val title: String,
    val content: Actor,
    val defaultZone: DockZone,
    val preferredWidth: Float,
    val preferredHeight: Float,
    /** False for panels that are always shown (the toolbar and status bar): no hide button. */
    val closable: Boolean = true,
    /** False for panels that make no sense collapsed. */
    val collapsible: Boolean = true,
    val visibleByDefault: Boolean = true,
    /** False for bands such as the toolbar and status bar: no title, and not movable (they stay docked). */
    val showHeader: Boolean = true,
    /** Start as a floating window rather than docked in [defaultZone]. */
    val startFloating: Boolean = false,
    /** Heading the panel finder lists it under ("Map", "Content"...). */
    val group: String = ""
) {
    /** One plain-language line about what the panel is for; shown in the panel finder, which also searches it. */
    var description: String = ""

    fun spec() = PanelSpec(id, defaultZone, preferredWidth, preferredHeight, visibleByDefault, startFloating)
}

/** Where the arrangement is remembered between runs. */
interface LayoutStore {
    fun load(): String?
    fun save(text: String)
}

class MemoryLayoutStore(var text: String? = null) : LayoutStore {
    override fun load(): String? = text
    override fun save(text: String) { this.text = text }
}

/** The platform's preferences as a [KeyValueStore], for the developer's saved layouts. */
class PreferencesKeyValueStore(private val name: String = "morgana-editor-layouts") : com.morgana.client.editor.dock.KeyValueStore {
    override fun get(key: String): String? = try { Gdx.app.getPreferences(name).getString(key, "").ifEmpty { null } } catch (_: Throwable) { null }
    override fun put(key: String, value: String) {
        try { Gdx.app.getPreferences(name).also { it.putString(key, value); it.flush() } } catch (_: Throwable) { }
    }
    override fun remove(key: String) {
        try { Gdx.app.getPreferences(name).also { it.remove(key); it.flush() } } catch (_: Throwable) { }
    }
}

/** Remembers the layout in the platform's preferences (a file on desktop, local storage in a browser). */
class PreferencesLayoutStore(private val name: String = "morgana-editor", private val key: String = "dock-layout") : LayoutStore {
    override fun load(): String? = try { Gdx.app.getPreferences(name).getString(key, "").ifBlank { null } } catch (_: Throwable) { null }
    override fun save(text: String) {
        try { Gdx.app.getPreferences(name).also { it.putString(key, text); it.flush() } } catch (_: Throwable) { }
    }
}

/**
 * Draws and manages the editor's panels: each one docked to an edge of the screen (toolbars on top, a status bar below,
 * tool columns on the sides, as in classic tile editors) or floating over the world. Where everything goes is decided by
 * [DockLayout]; this class only draws it and turns mouse input into layout changes.
 *
 * - Drag a panel's header to pull it out and float it; drag a floating panel near a screen edge and a highlight shows
 *   where it will dock when released.
 * - Each header has buttons to float or dock, collapse to just the header, and hide.
 * - Floating panels have a resize handle in the bottom-right corner. Docked panels are resized by dragging the inner edge
 *   of their column (its width) or the line between two stacked panels (their heights).
 * - The arrangement is saved to [store] whenever it changes.
 *
 * Panels that are docked cover part of the screen; [insets] tells the world view how much, so the map is shown in the
 * remaining space instead of behind the panels.
 */
class EditorWorkspace(
    private val stage: Stage,
    private val theme: EditorTheme,
    panels: List<EditorPanel>,
    private val store: LayoutStore = MemoryLayoutStore()
) {
    private val panelsById = panels.associateBy { it.id }
    val layout = DockLayout(panels.map { it.spec() }).also { l -> store.load()?.let { l.restore(it) } }
    private val frames = LinkedHashMap<String, PanelFrame>()
    private val resizeHandles = LinkedHashMap<String, Actor>()
    /** Drag handles on docked panels: the inner edge of each side column and the line between stacked panels. */
    private val splitters = LinkedHashMap<String, Splitter>()
    private val dropHighlight = Image(theme.solid(Color(theme.palette.accent.r, theme.palette.accent.g, theme.palette.accent.b, 0.28f)))
    private val panelBar = PanelBar()
    private var shown = false

    /** Called after every change to the arrangement (so the panel finder can refresh itself). */
    var onLayoutChanged: (() -> Unit)? = null
    private var lastWidth = -1f
    private var lastHeight = -1f

    init {
        dropHighlight.touchable = Touchable.disabled
        dropHighlight.isVisible = false
        for (panel in panels) {
            frames[panel.id] = PanelFrame(panel)
            resizeHandles[panel.id] = ResizeHandle(panel.id)
        }
    }

    val isShown: Boolean get() = shown

    /** Space the docked panels cover, in stage units; zero while the workspace is hidden. */
    fun insets(): ViewInsets {
        if (!shown) return ViewInsets.NONE
        val base = layout.insets(stage.width, stage.height)
        return if (layout.minimized().isEmpty()) base else base.copy(bottom = base.bottom + BAR_HEIGHT)
    }

    fun show() {
        if (shown) return
        shown = true
        stage.addActor(panelBar)
        frames.values.forEach { stage.addActor(it) }
        resizeHandles.values.forEach { stage.addActor(it) }
        stage.addActor(dropHighlight)
        relayout()
    }

    fun hide() {
        shown = false
        panelBar.remove()
        frames.values.forEach { it.remove() }
        resizeHandles.values.forEach { it.remove() }
        splitters.values.forEach { it.remove() }
        splitters.clear()
        dropHighlight.remove()
        setCursor(false)
    }

    /** Re-applies the layout; call when the screen size changes. Cheap when nothing changed. */
    fun relayoutIfResized() {
        if (shown && (stage.width != lastWidth || stage.height != lastHeight)) relayout()
    }

    fun relayout() {
        if (!shown) return
        lastWidth = stage.width
        lastHeight = stage.height
        for ((id, frame) in frames) {
            val placement = layout.placement(id)
            val rect = layout.bounds(id, stage.width, stage.height)
            frame.isVisible = rect != null
            if (rect != null) placeFrame(frame, rect)
            frame.refresh(placement)
            positionHandle(id, placement, rect)
        }
        layoutSplitters()
        layoutPanelBar()
    }

    /**
     * Places the drag handles of the docked side columns: one along each column's inner edge, and one on the line between
     * every two neighbouring open panels in a column. Handles are kept by key, so one being dragged survives the relayout
     * its own drag causes. They sit above the docked panels but below any floating one.
     */
    private fun layoutSplitters() {
        val wanted = LinkedHashMap<String, Triple<SplitKind, PanelRect, Pair<String, String>?>>()
        for (zone in listOf(DockZone.LEFT, DockZone.RIGHT)) {
            val rects = layout.docked(zone).mapNotNull { p -> layout.bounds(p.id, stage.width, stage.height)?.let { p to it } }
            if (rects.isEmpty()) continue
            val top = rects.first().second.y
            val bottom = rects.last().second.let { it.y + it.height }
            val colX = rects.first().second.x
            val colW = rects.first().second.width
            val edgeX = if (zone == DockZone.LEFT) colX + colW - SPLIT_GRIP / 2f else colX - SPLIT_GRIP / 2f
            val kind = if (zone == DockZone.LEFT) SplitKind.LEFT_EDGE else SplitKind.RIGHT_EDGE
            wanted["edge:$zone"] = Triple(kind, PanelRect(edgeX, top, SPLIT_GRIP, bottom - top), null)
            for (i in 0 until rects.size - 1) {
                val (upper, ur) = rects[i]
                val lower = rects[i + 1].first
                if (upper.collapsed || lower.collapsed) continue
                val lineY = ur.y + ur.height
                wanted["split:${upper.id}:${lower.id}"] = Triple(SplitKind.BETWEEN, PanelRect(colX, lineY - SPLIT_GRIP / 2f, colW, SPLIT_GRIP), upper.id to lower.id)
            }
        }
        for (key in splitters.keys - wanted.keys) splitters.remove(key)?.remove()
        // Just above the top-most docked panel, so floating panels still cover the handles.
        val dockedTop = frames.filter { (id, f) -> f.stage != null && f.isVisible && !layout.placement(id).isFloating }.values.maxOfOrNull { it.zIndex } ?: -1
        for ((key, spec) in wanted) {
            val (kind, rect, pair) = spec
            val handle = splitters.getOrPut(key) { Splitter(kind, pair) }
            handle.setBounds(rect.x, stage.height - rect.y - rect.height, rect.width, rect.height)
            if (handle.stage == null || handle.zIndex < dockedTop) {
                handle.remove()
                stage.root.addActorAt((dockedTop + 1).coerceAtMost(stage.root.children.size), handle)
            }
        }
    }

    /** Shows the panel bar (one button per minimized panel) in the free strip above the bottom band, or hides it when empty. */
    private fun layoutPanelBar() {
        val minimized = layout.minimized()
        panelBar.isVisible = minimized.isNotEmpty()
        if (minimized.isEmpty()) return
        panelBar.setEntries(minimized.map { it.id to (panelsById[it.id]?.title ?: it.id) })
        val inset = layout.insets(stage.width, stage.height)
        val barLeft = snapToPixel(inset.left)
        val barBottom = snapToPixel(inset.bottom)
        panelBar.setBounds(barLeft, barBottom, (snapToPixel(stage.width - inset.right) - barLeft).coerceAtLeast(0f), BAR_HEIGHT)
        panelBar.toFront()
    }

    /**
     * Puts a panel frame at [rect] (layout coordinates, y down) with every edge on a whole screen pixel. Column heights are
     * shared out by weight and drags give fractions, and the pixel font drawn from a fractional position looks smeared.
     */
    private fun placeFrame(frame: Actor, rect: PanelRect) {
        val left = snapToPixel(rect.x)
        val right = snapToPixel(rect.x + rect.width)
        val bottom = snapToPixel(stage.height - rect.y - rect.height)
        val top = snapToPixel(stage.height - rect.y)
        frame.setBounds(left, bottom, right - left, top - bottom)
    }

    /** Rounds a stage coordinate to the nearest whole screen pixel (the stage may be scaled by the UI size setting). */
    private fun snapToPixel(v: Float): Float {
        val perPixel = (stage.viewport as? com.badlogic.gdx.utils.viewport.ScreenViewport)?.unitsPerPixel ?: 1f
        return Math.round(v / perPixel) * perPixel
    }

    /** Shows or hides one panel. */
    fun togglePanel(id: String) {
        if (layout.placement(id).visible) layout.setVisible(id, false) else layout.show(id)
        changed()
        if (layout.placement(id).visible) raise(id)
    }

    /** Brings a panel to the screen from wherever it is (closed or minimized) and in front of the others. */
    fun openPanel(id: String) {
        layout.show(id)
        changed()
        raise(id)
    }

    /** Puts a panel away on the panel bar. */
    fun minimizePanel(id: String) {
        layout.minimize(id)
        changed()
    }

    /** Every panel that can be closed (not the toolbar and status bar), with where it is now, for the panel finder. */
    fun panelInfos(): List<PanelInfo> = panelsById.values.filter { it.closable }.map { p ->
        val placement = layout.placement(p.id)
        PanelInfo(p.id, p.title, p.group, if (placement.minimized) PanelState.MINIMIZED else if (placement.visible) PanelState.SHOWN else PanelState.HIDDEN, p.description)
    }

    /** The arrangement as saveable text (see [com.morgana.client.editor.dock.LayoutLibrary]). */
    fun layoutText(): String = layout.serialize()

    /** Replaces the arrangement with a saved one: defaults first, then the saved panels on top. */
    fun applyLayoutText(text: String) {
        layout.reset()
        layout.restore(text)
        changed()
    }

    /** Shows exactly [panelIds] (plus the toolbar and status bar) at their default places. */
    fun applyPreset(panelIds: Collection<String>) {
        layout.applyPreset(panelIds + panelsById.values.filter { !it.closable }.map { it.id })
        changed()
    }

    /** Puts a panel in front of the others (and its resize grip in front of it). */
    private fun raise(id: String) {
        frames.getValue(id).toFront()
        resizeHandles.getValue(id).toFront()
        dropHighlight.toFront()
    }

    fun resetLayout() {
        layout.reset()
        changed()
    }

    private fun changed() {
        relayout()
        store.save(layout.serialize())
        onLayoutChanged?.invoke()
    }

    private fun positionHandle(id: String, placement: PanelPlacement, rect: com.morgana.client.editor.dock.PanelRect?) {
        val handle = resizeHandles.getValue(id)
        val show = rect != null && placement.isFloating && !placement.collapsed
        handle.isVisible = show
        if (show && rect != null) {
            handle.setPosition(rect.x + rect.width - handle.width, stage.height - rect.y - rect.height)
            handle.toFront()
        }
    }

    // ---- drag state shared by the headers ----

    private class Drag(val id: String, var grabX: Float, var grabY: Float, val startX: Float, val startY: Float, var detached: Boolean)

    private var drag: Drag? = null

    private fun beginDrag(id: String, stageX: Float, stageY: Float) {
        val placement = layout.placement(id)
        val rect = layout.bounds(id, stage.width, stage.height)
        // Offset of the pointer inside the panel (y measured downwards), so the panel does not jump when picked up.
        val gx = if (rect != null) stageX - rect.x else DETACH_GRAB_X
        val gy = if (rect != null) (stage.height - stageY) - rect.y else DETACH_GRAB_Y
        drag = Drag(id, gx, gy, stageX, stageY, detached = placement.isFloating)
        frames.getValue(id).toFront()
        resizeHandles.getValue(id).toFront()
        dropHighlight.toFront()
    }

    private fun continueDrag(stageX: Float, stageY: Float) {
        val d = drag ?: return
        if (!d.detached) {
            if (Math.hypot((stageX - d.startX).toDouble(), (stageY - d.startY).toDouble()) < DETACH_DISTANCE) return
            // Pull a docked panel out: it floats with its header under the pointer.
            d.detached = true
            d.grabX = DETACH_GRAB_X
            d.grabY = DETACH_GRAB_Y
            layout.float(d.id, stageX - d.grabX, (stage.height - stageY) - d.grabY)
            relayout()
            frames.getValue(d.id).toFront()
            resizeHandles.getValue(d.id).toFront()
            dropHighlight.toFront()
        }
        layout.moveFloating(d.id, stageX - d.grabX, (stage.height - stageY) - d.grabY)
        relayoutOne(d.id)
        showDropHighlight(layout.dropZoneAt(stageX, stage.height - stageY, stage.width, stage.height), d.id)
    }

    private fun endDrag(stageX: Float, stageY: Float) {
        val d = drag ?: return
        drag = null
        dropHighlight.isVisible = false
        if (!d.detached) return
        val zone = layout.dropZoneAt(stageX, stage.height - stageY, stage.width, stage.height)
        if (zone != null) layout.dock(d.id, zone, indexIn(zone, d.id, stageX, stage.height - stageY))
        changed()
    }

    private fun relayoutOne(id: String) {
        val rect = layout.bounds(id, stage.width, stage.height) ?: return
        placeFrame(frames.getValue(id), rect)
        positionHandle(id, layout.placement(id), rect)
    }

    /** Where among the panels already docked in [zone] a drop at ([x], [y]) (y downwards) should land. */
    private fun indexIn(zone: DockZone, movingId: String, x: Float, y: Float): Int {
        val siblings = layout.docked(zone).filter { it.id != movingId }
        return siblings.count { s ->
            val r = layout.bounds(s.id, stage.width, stage.height) ?: return@count false
            if (zone == DockZone.LEFT || zone == DockZone.RIGHT) r.y + r.height / 2f < y else r.x + r.width / 2f < x
        }
    }

    private fun showDropHighlight(zone: DockZone?, movingId: String) {
        if (zone == null) { dropHighlight.isVisible = false; return }
        val spec = panelsById.getValue(movingId)
        val w = stage.width
        val h = stage.height
        val inset = layout.insets(w, h)
        // Preview the strip or column the panel would join, using the space it asks for.
        when (zone) {
            DockZone.LEFT -> dropHighlight.setBounds(0f, inset.bottom, maxOf(inset.left, spec.preferredWidth), h - inset.top - inset.bottom)
            DockZone.RIGHT -> { val bw = maxOf(inset.right, spec.preferredWidth); dropHighlight.setBounds(w - bw, inset.bottom, bw, h - inset.top - inset.bottom) }
            DockZone.TOP -> dropHighlight.setBounds(0f, h - maxOf(inset.top, spec.preferredHeight), w, maxOf(inset.top, spec.preferredHeight))
            DockZone.BOTTOM -> dropHighlight.setBounds(0f, 0f, w, maxOf(inset.bottom, spec.preferredHeight))
        }
        dropHighlight.isVisible = true
    }

    // ---- a panel's chrome ----

    private inner class PanelFrame(private val panel: EditorPanel) : VisTable() {
        private val floatButton: VisImageButton
        private val collapseButton: VisImageButton?
        private val closeButton: VisImageButton?
        private val body = VisTable()

        init {
            touchable = Touchable.enabled   // so clicks on a panel never fall through to the world behind it
            // Content taller than the panel is cut at its edges rather than drawn over the panels next to it.
            clip = true
            background = theme.bordered(theme.palette.panel, theme.palette.border)
            // Clicking anywhere in a floating panel brings it to the front (the click still reaches what is under it).
            addCaptureListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    if (layout.placement(panel.id).isFloating) raise(panel.id)
                    return false
                }
            })

            val header = VisTable()
            header.background = theme.solid(theme.palette.panelRaised)
            header.touchable = Touchable.enabled
            header.add(VisLabel(panel.title, theme.label)).left().padLeft(8f).growX()

            floatButton = theme.iconButton("float", "Float or dock this editor") { toggleFloating(panel.id) }
            collapseButton = if (panel.collapsible) theme.iconButton("minus", "Collapse or expand this editor") { toggleCollapse(panel.id) } else null
            val minimizeButton = if (panel.closable) theme.iconButton("minimize", "Put this editor away on the Minimized bar (click its name there to bring it back)") { minimizePanel(panel.id) } else null
            closeButton = if (panel.closable) theme.iconButton("close", "Close this editor (reopen it from Editors on the toolbar)") { hidePanel(panel.id) } else null
            header.defaults().size(HEADER_BUTTON).padRight(2f)
            listOfNotNull(floatButton, collapseButton, minimizeButton, closeButton).forEach {
                com.morgana.client.ui.HudWindow.stopDragOnPress(it)
            }
            header.add(floatButton)
            collapseButton?.let { header.add(it) }
            minimizeButton?.let { header.add(it) }
            closeButton?.let { header.add(it) }
            header.addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    if (button != Input.Buttons.LEFT) return false
                    beginDrag(panel.id, event.stageX, event.stageY)
                    return true
                }

                override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) = continueDrag(event.stageX, event.stageY)

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) = endDrag(event.stageX, event.stageY)
            })

            if (panel.showHeader) add(header).growX().height(DockLayout.HEADER_HEIGHT).row()
            body.add(panel.content).grow()
            add(body).grow().pad(if (panel.showHeader) 2f else 0f)
        }

        private var shownFloating: Boolean? = null

        /** Brings the chrome in line with the panel's placement. */
        fun refresh(placement: PanelPlacement) {
            if (shownFloating != placement.isFloating) {
                shownFloating = placement.isFloating
                floatButton.style = theme.iconButtonStyle(if (placement.isFloating) "dock" else "float")
            }
            // A collapsed panel is only its header; the frame's height is fixed by the layout, so hiding the body is enough.
            body.isVisible = !placement.collapsed
        }
    }

    /** Pulls a docked panel out to float where it is, or puts a floating one back in the zone it came from. */
    private fun toggleFloating(id: String) {
        if (layout.placement(id).isFloating) {
            layout.dockBack(id)
        } else {
            val rect = layout.bounds(id, stage.width, stage.height)
            layout.float(id, (rect?.x ?: 80f) + 32f, (rect?.y ?: 80f) + 32f)
        }
        changed()
        frames.getValue(id).toFront()
        resizeHandles.getValue(id).toFront()
    }

    private fun toggleCollapse(id: String) {
        layout.toggleCollapsed(id)
        changed()
    }

    private fun hidePanel(id: String) {
        layout.setVisible(id, false)
        changed()
    }

    private enum class SplitKind { LEFT_EDGE, RIGHT_EDGE, BETWEEN }

    /** Shows the resize arrow while the pointer is over a handle (where the platform has one; harmless where it does not). */
    private fun setCursor(resize: Boolean, vertical: Boolean = false) {
        try {
            val cursor = when {
                !resize -> com.badlogic.gdx.graphics.Cursor.SystemCursor.Arrow
                vertical -> com.badlogic.gdx.graphics.Cursor.SystemCursor.VerticalResize
                else -> com.badlogic.gdx.graphics.Cursor.SystemCursor.HorizontalResize
            }
            Gdx.graphics.setSystemCursor(cursor)
        } catch (_: Throwable) { }
    }

    /**
     * A thin, invisible strip over a docked boundary that highlights when hovered: dragging a column's inner edge changes
     * the column's width, dragging the line between two stacked panels moves height from one to the other.
     */
    private inner class Splitter(private val kind: SplitKind, private val pair: Pair<String, String>?) : Actor() {
        private val line = theme.solid(theme.palette.accent)
        private var hovered = false
        private var dragging = false
        private var lastStageY = 0f

        init {
            touchable = Touchable.enabled
            addListener(object : InputListener() {
                override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    if (pointer != -1) return
                    hovered = true
                    setCursor(true, vertical = kind == SplitKind.BETWEEN)
                }

                override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                    if (pointer != -1) return
                    hovered = false
                    if (!dragging) setCursor(false)
                }

                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    if (button != Input.Buttons.LEFT) return false
                    dragging = true
                    lastStageY = event.stageY
                    return true
                }

                override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                    when (kind) {
                        SplitKind.LEFT_EDGE -> layout.setColumnWidth(DockZone.LEFT, event.stageX, stage.width)
                        SplitKind.RIGHT_EDGE -> layout.setColumnWidth(DockZone.RIGHT, stage.width - event.stageX, stage.width)
                        SplitKind.BETWEEN -> {
                            val (upper, lower) = pair ?: return
                            // Stage y grows upwards and the layout's downwards, so moving the pointer down is a positive delta.
                            layout.resizeSplit(upper, lower, lastStageY - event.stageY, stage.width, stage.height)
                            lastStageY = event.stageY
                        }
                    }
                    relayout()
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    dragging = false
                    if (!hovered) setCursor(false)
                    changed()
                }
            })
        }

        override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, parentAlpha: Float) {
            if (!hovered && !dragging) return
            if (kind == SplitKind.BETWEEN) line.draw(batch, x, y + height / 2f - 1f, width, 2f)
            else line.draw(batch, x + width / 2f - 1f, y, 2f, height)
        }
    }

    private inner class ResizeHandle(private val id: String) : Actor() {
        private val grip = theme.solid(theme.palette.textMuted)

        init {
            width = 18f
            height = 18f
            touchable = Touchable.enabled
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) = button == Input.Buttons.LEFT

                override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                    val p = layout.placement(id)
                    if (!p.isFloating) return
                    val rect = layout.bounds(id, stage.width, stage.height) ?: return
                    layout.resizeFloating(id, event.stageX - rect.x, (stage.height - event.stageY) - rect.y)
                    relayoutOne(id)
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) = changed()
            })
        }

        override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, parentAlpha: Float) {
            // A small triangle of dots, the usual resize grip.
            grip.draw(batch, x + width - 4f, y + 3f, 2f, 2f)
            grip.draw(batch, x + width - 8f, y + 3f, 2f, 2f)
            grip.draw(batch, x + width - 12f, y + 3f, 2f, 2f)
            grip.draw(batch, x + width - 4f, y + 7f, 2f, 2f)
            grip.draw(batch, x + width - 8f, y + 7f, 2f, 2f)
            grip.draw(batch, x + width - 4f, y + 11f, 2f, 2f)
        }
    }

    /** The strip of buttons for minimized panels; clicking one brings that panel back. */
    private inner class PanelBar : VisTable() {
        private var shownIds: List<Pair<String, String>>? = null

        init {
            background = theme.solid(theme.palette.panelRaised)
            touchable = Touchable.enabled
        }

        fun setEntries(entries: List<Pair<String, String>>) {
            if (entries == shownIds) return
            shownIds = entries
            clearChildren()
            add(VisLabel("Minimized:", theme.mutedLabel)).padLeft(8f).padRight(6f)
            for ((id, title) in entries) add(theme.textButton(title) { openPanel(id) }).padRight(4f).height(BAR_HEIGHT - 6f)
            add().growX()
        }
    }

    companion object {
        const val BAR_HEIGHT = 28f
        /** Thickness of the invisible strip that grabs a docked boundary. */
        private const val SPLIT_GRIP = 8f
        private const val HEADER_BUTTON = 20f
        private const val DETACH_DISTANCE = 6f
        private const val DETACH_GRAB_X = 60f
        private const val DETACH_GRAB_Y = 12f
    }
}
