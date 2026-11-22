package com.morgana.client.ui.editor

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.kotcrab.vis.ui.widget.VisImageButton
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisTable
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.editor.MapEditSession
import com.morgana.client.editor.PaletteStrips
import com.morgana.client.editor.TileRect
import com.morgana.client.editor.TilesetSelection
import com.morgana.client.state.MapEditorTool

/**
 * The tile palette: every tileset the game has, stacked top to bottom in one scrolling pane, each shown as its real image
 * with no per-tile captions. The palette is a fixed 8 tiles wide (RPG Maker XP's tileset width): a wider sheet is cut into
 * 8-wide strips shown one under another ([PaletteStrips]), so the palette only ever scrolls down. Objects up to 8 tiles
 * wide that sit inside one strip stay intact.
 *
 * - Click a tile to select it; drag to select a block (a *stamp*, up to 16 x 16) that is painted as one.
 * - A map can use tiles from several tilesets: pick from any section and keep painting. The "Jump to" box scrolls to a
 *   tileset, and a section's header collapses it to keep long lists manageable.
 * - Zoom (50% to 200% in small steps) applies to every sheet.
 * - **Animation**: with more than one frame, painted tiles animate through the tiles to their right (a stamp's width
 *   apart, so a multi-tile object animates whole); the speed is how long each frame shows. See
 *   [com.morgana.domain.map.TileAnimation].
 *
 * The selection lives in the [MapEditSession] (`tilesetId`, brush position and size), so the palette and the painting tools
 * always agree.
 */
class TilesetPanel(
    private val stage: Stage,
    private val theme: EditorTheme,
    private val assets: ProceduralRetroAssets,
    private val editor: MapEditSession
) {
    private var zoom: Float = 1f
    private var hover: Triple<Int, Int, Int>? = null          // tileset, sheet column, sheet row
    private var dragTileset: Int? = null
    private var dragStart: Pair<Int, Int>? = null              // display cell where the drag began
    private var dragRect: TileRect? = null                     // sheet block

    private var listedIds: List<Int> = emptyList()
    private var listedGrids: List<Pair<Int, Int>?> = emptyList()
    private val collapsed = linkedSetOf<Int>()
    private val sheets = LinkedHashMap<Int, SheetView>()
    private val sectionHeaders = LinkedHashMap<Int, Actor>()

    private val jump = VisSelectBox<String>()
    private val info = theme.muted("")
    private val framesLabel = theme.text("")
    private val speedLabel = theme.text("")
    private val sections = VisTable()
    private val scroll = VisScrollPane(sections)

    private val checkerA = theme.solid(Color(0.105f, 0.118f, 0.145f, 1f))
    private val checkerB = theme.solid(Color(0.135f, 0.152f, 0.188f, 1f))
    private val gridLine = theme.solid(Color(1f, 1f, 1f, 0.09f))
    private val selectionFill = theme.solid(Color(theme.palette.accent.r, theme.palette.accent.g, theme.palette.accent.b, 0.30f))
    private val selectionLine = theme.solid(theme.palette.accent)
    private val hoverLine = theme.solid(Color(1f, 1f, 1f, 0.75f))
    private val stripLine = theme.solid(Color(1f, 1f, 1f, 0.35f))

    /** The widget to put in a panel. */
    val content: Actor = LiveTable { refresh() }.also { root ->
        scroll.setFadeScrollBars(false)
        scroll.setOverscroll(false, false)
        scroll.setScrollingDisabled(false, false) // sideways only when zoomed in past the column's width
        giveScrollFocusOnHover(stage, scroll)
        sections.top().left()

        jump.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                val index = jump.selectedIndex
                if (index in listedIds.indices) scrollTo(listedIds[index])
            }
        })
        val zoomOut = theme.iconButton("zoom_out", "Zoom out the tile sheets") { setZoom(TilesetSelection.stepZoom(zoom, -1)) }
        val zoomIn = theme.iconButton("zoom_in", "Zoom in on the tile sheets") { setZoom(TilesetSelection.stepZoom(zoom, +1)) }
        val toolbar = VisTable()
        toolbar.add(jump).growX().minWidth(60f).padRight(3f)
        toolbar.add(zoomOut).size(26f).padRight(2f)
        toolbar.add(zoomIn).size(26f)
        root.add(toolbar).growX().pad(3f).row()
        root.add(animationRow()).growX().padLeft(3f).padRight(3f).padBottom(3f).row()
        root.add(scroll).grow().minWidth(0f).minHeight(0f).padLeft(3f).padRight(3f).row()
        info.setEllipsis(true)
        root.add(info).growX().minWidth(0f).left().pad(3f).row()
        refresh()
    }

    private fun setZoom(value: Float) {
        zoom = value
        sheets.values.forEach { it.invalidateHierarchy() }
    }

    /** Scrolls the pane so tileset [id]'s section is at the top, expanding it if it was collapsed. */
    private fun scrollTo(id: Int) {
        if (collapsed.remove(id)) rebuildSections()
        val header = sectionHeaders[id] ?: return
        sections.validate()
        scroll.layout()
        scroll.scrollY = (sections.height - (header.y + header.height)).coerceAtLeast(0f)
    }

    /** Called every frame: keeps the tileset list and the status text current. */
    private fun refresh() {
        val ids = assets.tilesetIds()
        val grids = ids.map { assets.tilesetGrid(it) }
        if (ids != listedIds || grids != listedGrids) {
            listedIds = ids
            listedGrids = grids
            ids.forEach { id -> sheets.getOrPut(id) { SheetView(id) } }
            sheets.keys.retainAll(ids.toSet())
            collapsed.retainAll(ids.toSet())
            sheets.values.forEach { it.invalidateHierarchy() }
            jump.setItems(*ids.map { id -> "Tileset $id" }.toTypedArray())
            jump.selectedIndex = ids.indexOf(editor.tilesetId).coerceAtLeast(0)
            rebuildSections()
        }
        val frames = editor.animationFrames
        framesLabel.setText(if (frames <= 1) "Still" else "$frames frames")
        speedLabel.setText(if (frames <= 1) "-" else "${editor.animationSpeedMs} ms")
        val over = hover
        val hint = animationHint()
        val text = when {
            over != null -> "Tileset ${over.first}: tile ${over.second}, ${over.third}"
            hint.isNotEmpty() -> hint
            else -> "Brush: ${editor.brushWidth} x ${editor.brushHeight} tile${if (editor.brushWidth * editor.brushHeight == 1) "" else "s"} from tileset ${editor.tilesetId}"
        }
        if (info.text.toString() != text) info.setText(text)
    }

    /** The brush's animation controls: number of frames and how long each shows. */
    private fun animationRow(): Actor {
        val row = VisTable()
        fun small(text: String, tip: String, action: () -> Unit) = theme.textButton(text, action).also { theme.tip(it, tip) }
        framesLabel.setAlignment(com.badlogic.gdx.utils.Align.center)
        speedLabel.setAlignment(com.badlogic.gdx.utils.Align.center)
        val title = theme.muted("Anim")
        theme.tip(title, "Animate painted tiles: they cycle through the tiles to their right on the sheet")
        row.add(title).left().padRight(3f)
        row.add(small("-", "Fewer animation frames") { editor.animationFrames = editor.animationFrames - 1 }).size(20f)
        row.add(framesLabel).width(58f).center()
        row.add(small("+", "More animation frames: each painted tile cycles through the tiles to its right") { editor.animationFrames = editor.animationFrames + 1 }).size(20f)
        row.add().width(4f)
        row.add(small("-", "Faster: each frame shows for less time") { editor.animationSpeedMs = stepSpeed(editor.animationSpeedMs, -1) }).size(20f)
        row.add(speedLabel).width(54f).center()
        row.add(small("+", "Slower: each frame shows for longer") { editor.animationSpeedMs = stepSpeed(editor.animationSpeedMs, +1) }).size(20f)
        row.add().growX()
        return row
    }

    /** What the animation setting will do, or why it will not look right; empty for a still brush. */
    private fun animationHint(): String {
        val frames = editor.animationFrames
        if (frames <= 1) return ""
        val step = editor.brushWidth
        val last = editor.brushX + (editor.brushWidth - 1) + (frames - 1) * step
        val cols = assets.tilesetGrid(editor.tilesetId)?.first ?: Int.MAX_VALUE
        val where = if (step == 1) "the ${frames - 1} tile${if (frames == 2) "" else "s"} to its right" else "the next ${frames - 1} blocks of $step tiles to the right"
        return if (last >= cols) "The frames run off the right edge of the sheet: choose a tile further left or fewer frames."
        else "Painted tiles cycle through $where, ${editor.animationSpeedMs} ms each."
    }

    private fun rebuildSections() {
        sections.clearChildren()
        sectionHeaders.clear()
        for (id in listedIds) {
            val (cols, rows) = assets.tilesetGrid(id) ?: continue
            val isCollapsed = id in collapsed
            val header = sectionHeader(id, cols, rows, isCollapsed)
            sectionHeaders[id] = header
            sections.add(header).growX().padTop(4f).row()
            if (!isCollapsed) sections.add(sheets.getValue(id)).left().row()
        }
        sections.invalidateHierarchy()
    }

    private fun sectionHeader(id: Int, cols: Int, rows: Int, isCollapsed: Boolean): Actor {
        val header = VisTable()
        header.background = theme.solid(theme.palette.panelRaised)
        val toggle: VisImageButton = theme.iconButton(if (isCollapsed) "plus" else "minus", if (isCollapsed) "Show this tileset" else "Hide this tileset") {
            if (!collapsed.remove(id)) collapsed.add(id)
            rebuildSections()
        }
        header.add(theme.text("Tileset $id")).left().padLeft(6f)
        header.add(theme.muted("  $cols x $rows")).left()
        header.add().growX()
        header.add(toggle).size(22f).pad(1f)
        return header
    }

    private inner class SheetView(private val id: Int) : Widget() {
        private val tile = 32f

        private val strips: PaletteStrips get() {
            val (cols, rows) = assets.tilesetGrid(id) ?: (1 to 1)
            return PaletteStrips(cols, rows)
        }
        private val cell get() = tile * zoom
        /** One region per strip, rebuilt when the sheet or its size changes. */
        private var stripRegions: List<com.badlogic.gdx.graphics.g2d.TextureRegion> = emptyList()
        private var regionsFor: Pair<Any?, PaletteStrips>? = null

        override fun getPrefWidth(): Float = strips.displayCols * cell
        override fun getPrefHeight(): Float = strips.displayRows * cell

        init {
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    if (button != Input.Buttons.LEFT) return false
                    val start = displayCellAt(x, y)
                    dragTileset = id
                    dragStart = start
                    dragRect = strips.selection(start, start)
                    return true
                }

                override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                    val start = dragStart ?: return
                    val end = displayCellAt(x, y)
                    hoverAt(end)
                    dragRect = strips.selection(start, end)
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    val r = dragRect
                    dragTileset = null
                    dragStart = null
                    dragRect = null
                    if (r != null) choose(id, r)
                }

                override fun mouseMoved(event: InputEvent, x: Float, y: Float): Boolean {
                    hoverAt(displayCellAt(x, y))
                    return false
                }

                override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                    if (pointer == -1 && hover?.first == id) hover = null
                }
            })
        }

        private fun hoverAt(display: Pair<Int, Int>) {
            val p = strips
            hover = if (p.hasTile(display.first, display.second)) p.toSheet(display.first, display.second).let { Triple(id, it.first, it.second) } else null
        }

        private fun displayCellAt(x: Float, y: Float): Pair<Int, Int> {
            val p = strips
            return TilesetSelection.cellAt(x, height - y, cell, p.displayCols, p.displayRows)
        }

        private fun selection(): TileRect? =
            if (dragTileset == id) dragRect
            else if (editor.tilesetId == id) TileRect(editor.brushX, editor.brushY, editor.brushWidth, editor.brushHeight)
            else null

        private fun regions(sheet: com.badlogic.gdx.graphics.g2d.TextureRegion, p: PaletteStrips): List<com.badlogic.gdx.graphics.g2d.TextureRegion> {
            val key = sheet.texture to p
            if (regionsFor != key) {
                val t = tile.toInt()
                stripRegions = (0 until p.strips).map { strip ->
                    com.badlogic.gdx.graphics.g2d.TextureRegion(sheet, strip * p.columns * t, 0, p.stripWidth(strip) * t, p.sheetRows * t)
                }
                regionsFor = key
            }
            return stripRegions
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val sheet = assets.tilesetSheet(id) ?: return
            val p = strips
            val c = cell
            val top = y + height
            batch.setColor(1f, 1f, 1f, parentAlpha)

            // A two-tone chessboard behind the art, so transparent areas of a tile are visible as such.
            for (strip in 0 until p.strips) {
                val w = p.stripWidth(strip)
                val stripTop = top - strip * p.sheetRows * c
                checkerA.draw(batch, x, stripTop - p.sheetRows * c, w * c, p.sheetRows * c)
                for (r in 0 until p.sheetRows) for (col in 0 until w) {
                    if ((r + col) % 2 == 0) checkerB.draw(batch, x + col * c, stripTop - (r + 1) * c, c, c)
                }
            }
            batch.setColor(1f, 1f, 1f, parentAlpha)
            regions(sheet, p).forEachIndexed { strip, region ->
                val stripTop = top - strip * p.sheetRows * c
                batch.draw(region, x, stripTop - p.sheetRows * c, p.stripWidth(strip) * c, p.sheetRows * c)
            }

            if (c >= 16f) {
                for (strip in 0 until p.strips) {
                    val w = p.stripWidth(strip)
                    val stripTop = top - strip * p.sheetRows * c
                    val h = p.sheetRows * c
                    for (col in 0..w) gridLine.draw(batch, x + col * c, stripTop - h, 1f, h)
                    for (r in 0..p.sheetRows) gridLine.draw(batch, x, stripTop - r * c, w * c, 1f)
                }
            }
            // Strips after the first start with a stronger line, so it is clear where the sheet was folded.
            for (strip in 1 until p.strips) stripLine.draw(batch, x, top - strip * p.sheetRows * c - 1f, p.displayCols * c, 2f)

            selection()?.let { sel ->
                for (part in p.displayRects(sel)) {
                    val sx = x + part.x * c
                    val sy = top - (part.y + part.height) * c
                    selectionFill.draw(batch, sx, sy, part.width * c, part.height * c)
                    outline(batch, selectionLine, sx, sy, part.width * c, part.height * c, 2f)
                }
            }
            hover?.takeIf { it.first == id }?.let { (_, hx, hy) ->
                val (dx, dy) = p.toDisplay(hx, hy)
                outline(batch, hoverLine, x + dx * c, top - (dy + 1) * c, c, c, 1f)
            }
        }

        private fun outline(batch: Batch, line: Drawable, px: Float, py: Float, w: Float, h: Float, t: Float) {
            line.draw(batch, px, py, w, t)
            line.draw(batch, px, py + h - t, w, t)
            line.draw(batch, px, py, t, h)
            line.draw(batch, px + w - t, py, t, h)
        }
    }

    /** Makes [r] on tileset [id] the brush: a single tile or a stamp. Picking a tile after erasing or placing spawns switches back to painting. */
    private fun choose(id: Int, r: TileRect) {
        editor.selectStamp(id, r.x, r.y, r.width, r.height)
        if (editor.tool == MapEditorTool.ERASER || editor.tool == MapEditorTool.NPC_SPAWN || editor.tool == MapEditorTool.EYEDROPPER || editor.tool == MapEditorTool.EVENT) {
            editor.tool = MapEditorTool.BRUSH
        }
    }
}

/** Animation speeds the Tileset editor steps through, in milliseconds per frame. */
internal val ANIMATION_SPEEDS = listOf(50, 80, 100, 150, 200, 250, 300, 400, 500, 700, 1000, 1500, 2000)

/** The next speed from [current]: the next slower one on the list for +1, the next faster for -1 (staying at the ends). */
internal fun stepSpeed(current: Int, direction: Int): Int =
    if (direction > 0) ANIMATION_SPEEDS.firstOrNull { it > current } ?: ANIMATION_SPEEDS.last()
    else ANIMATION_SPEEDS.lastOrNull { it < current } ?: ANIMATION_SPEEDS.first()

/**
 * Scene2D only gives a scroll pane the mouse wheel after a click inside it; hovering is enough here. The pane takes wheel
 * focus when the pointer enters it and gives it up when the pointer leaves for something outside the pane.
 */
internal fun giveScrollFocusOnHover(stage: Stage, pane: VisScrollPane) {
    pane.addListener(object : InputListener() {
        override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
            if (pointer == -1) stage.scrollFocus = pane
        }

        override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
            if (pointer == -1 && (toActor == null || !toActor.isDescendantOf(pane)) && stage.scrollFocus == pane) {
                stage.scrollFocus = null
            }
        }
    })
}

/** A table that refreshes itself every frame, so toolbar, status and palette widgets follow the editor's state live. */
class LiveTable(private val refresh: () -> Unit) : VisTable() {
    override fun act(delta: Float) {
        refresh()
        super.act(delta)
    }
}
