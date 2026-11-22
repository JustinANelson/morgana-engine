package com.morgana.client.ui.bag

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.morgana.client.assets.ProceduralRetroAssets

/**
 * One square of an [ItemGrid]. [key] is whatever the owner wants handed back (a bag slot, a shop entry index); [iconId]
 * 0 or less draws an empty square; [dim] greys the square out (cannot afford, cannot sell); [badge] replaces the count.
 */
data class GridCell(val key: Int, val iconId: Int, val amount: Int = 1, val dim: Boolean = false, val badge: String? = null, val borderColor: Color? = null)

/**
 * A grid of item icons with a stack count in the corner, a highlight on the selected square and an outline on a square
 * that is "armed" (picked up to be moved). Tapping a square calls [onTap]; the grid lives inside a scroll pane, so it
 * reports a preferred size of [columns] by as many rows as the cells need and lets the pane scroll it.
 *
 * Drawn by hand in one pass, so a bag of 200 slots costs a few hundred quads rather than 200 widgets.
 */
class ItemGrid(
    private val assets: ProceduralRetroAssets,
    private val font: BitmapFont,
    /** Side of one square in stage units (larger on touch screens). */
    private val cell: Float = 44f
) : Widget() {

    var columns: Int = 8
        set(value) { if (value != field) { field = value.coerceAtLeast(1); invalidateHierarchy() } }

    var cells: List<GridCell> = emptyList()
        set(value) { field = value; invalidateHierarchy() }

    var selectedKey: Int? = null
    var armedKey: Int? = null
    var onTap: (GridCell) -> Unit = {}

    /** What to say about a square when the pointer rests on it; null (or no tooltip function) shows nothing. */
    var tipFor: (GridCell) -> ItemTip? = { null }

    private val layout = GlyphLayout()

    /** The key of the square under the pointer, drawn with a hover highlight; null when the pointer is elsewhere. */
    private var hoverKey: Int? = null
    private var hoverSeconds = 0f
    private val tipPopup = ItemTipPopup()
    private val longPress = com.morgana.client.ui.LongPress(LONG_PRESS_SECONDS)
    private var pressKey: Int? = null
    private var swallowTap = false
    private var pointerStageX = 0f
    private var pointerStageY = 0f

    init {
        addListener(object : ClickListener() {
            override fun mouseMoved(event: InputEvent, x: Float, y: Float): Boolean {
                val key = cellAt(x, y)?.key
                if (key != hoverKey) {
                    hoverKey = key
                    hoverSeconds = 0f
                    tipPopup.hide()
                }
                val p = localToStageCoordinates(com.badlogic.gdx.math.Vector2(x, y))
                pointerStageX = p.x
                pointerStageY = p.y
                return false
            }

            override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                super.exit(event, x, y, pointer, toActor)
                if (pointer == -1) {
                    hoverKey = null
                    tipPopup.hide()
                }
            }

            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                if (com.morgana.client.ui.HudWindow.touchMode) {
                    pressKey = cellAt(x, y)?.key
                    val p = localToStageCoordinates(com.badlogic.gdx.math.Vector2(x, y))
                    pointerStageX = p.x
                    pointerStageY = p.y
                    if (pressKey != null) longPress.begin(p.x, p.y)
                }
                return super.touchDown(event, x, y, pointer, button)
            }

            override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                val p = localToStageCoordinates(com.badlogic.gdx.math.Vector2(x, y))
                longPress.move(p.x, p.y)
                super.touchDragged(event, x, y, pointer)
            }

            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                // A long press that showed the card must not also count as a tap on the item.
                swallowTap = longPress.end()
                tipPopup.hide()
                pressKey = null
                super.touchUp(event, x, y, pointer, button)
            }

            override fun clicked(event: InputEvent, x: Float, y: Float) {
                tipPopup.hide()
                if (swallowTap) {
                    swallowTap = false
                    return
                }
                val hit = cellAt(x, y) ?: return
                onTap(hit)
            }

        })
    }

    override fun setStage(stage: com.badlogic.gdx.scenes.scene2d.Stage?) {
        if (stage == null) tipPopup.hide()
        super.setStage(stage)
    }

    /** Shows the tooltip once the pointer has rested on an item for a moment; hides it when the grid leaves the stage. */
    override fun act(delta: Float) {
        super.act(delta)
        val st = stage
        if (st != null && pressKey != null && longPress.update(delta)) {
            val cell = cells.firstOrNull { it.key == pressKey }
            val tip = if (cell != null && cell.iconId > 0) tipFor(cell) else null
            if (tip != null) tipPopup.show(st, tip, pointerStageX, pointerStageY, this, aboveFinger = true)
        }
        val key = hoverKey
        if (key == null || st == null) {
            if (tipPopup.isShowing && pressKey == null) tipPopup.hide() // a long-press card stays until the finger lifts
            return
        }
        hoverSeconds += delta
        if (hoverSeconds >= TIP_DELAY_SECONDS && !tipPopup.isShowing) {
            val cell = cells.firstOrNull { it.key == key }
            val tip = if (cell != null && cell.iconId > 0) tipFor(cell) else null
            if (tip != null) tipPopup.show(st, tip, pointerStageX, pointerStageY)
        }
    }

    private companion object {
        const val TIP_DELAY_SECONDS = 0.3f
    }

    private fun rows(): Int = maxOf(1, (cells.size + columns - 1) / columns)

    override fun getPrefWidth(): Float = columns * cell
    override fun getPrefHeight(): Float = rows() * cell

    /** The cell under a point given in the widget's own coordinates (y up), or null. */
    internal fun cellAt(px: Float, py: Float): GridCell? {
        if (px < 0f || py < 0f || px >= width || py >= height) return null
        val col = (px / cell).toInt()
        val row = ((height - py) / cell).toInt()
        if (col !in 0 until columns) return null
        return cells.getOrNull(row * columns + col)
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val white = assets.whitePixel
        val top = y + height
        for ((i, c) in cells.withIndex()) {
            val cx = x + (i % columns) * cell
            val cy = top - ((i / columns) + 1) * cell
            val empty = c.iconId <= 0
            // Square, with a 1 px gap between neighbours.
            val slot = com.morgana.client.ui.GameTheme.shared.palette.let { if (empty) it.background else it.panelRaised }
            batch.setColor(slot.r, slot.g, slot.b, parentAlpha)
            batch.draw(white, cx + 1f, cy + 1f, cell - 2f, cell - 2f)
            if (c.key == hoverKey && !empty) {
                val hi = com.morgana.client.ui.GameTheme.shared.palette.hover
                batch.setColor(hi.r, hi.g, hi.b, 0.6f * parentAlpha)
                batch.draw(white, cx + 1f, cy + 1f, cell - 2f, cell - 2f)
            }
            if (!empty) {
                batch.setColor(1f, 1f, 1f, parentAlpha * if (c.dim) 0.35f else 1f)
                val pad = cell * 0.14f
                batch.draw(assets.getItemIconRegion(c.iconId), cx + pad, cy + pad, cell - 2 * pad, cell - 2 * pad)
            }
            if (c.borderColor != null && c.key != selectedKey && c.key != armedKey) {
                outline(batch, white, cx, cy, c.borderColor, parentAlpha * if (c.dim) 0.5f else 1f, 1f)
            }
            if (c.key == hoverKey && c.key != selectedKey && c.borderColor == null) outline(batch, white, cx, cy, com.morgana.client.ui.GameTheme.shared.palette.accentDim, parentAlpha, 1f)
            if (c.key == selectedKey) outline(batch, white, cx, cy, com.morgana.client.ui.GameTheme.shared.palette.accent, parentAlpha, 2f)
            if (c.key == armedKey) outline(batch, white, cx, cy, com.morgana.client.ui.GameTheme.shared.palette.strong, parentAlpha, 2f)
        }
        // Counts last, so a neighbour's square never covers them.
        for ((i, c) in cells.withIndex()) {
            val text = c.badge ?: if (c.amount > 1) c.amount.toString() else continue
            val cx = x + (i % columns) * cell
            val cy = top - ((i / columns) + 1) * cell
            layout.setText(font, text)
            val tx = cx + cell - layout.width - 4f
            val ty = cy + layout.height + 3f
            val old = font.color.cpy()
            font.setColor(0f, 0f, 0f, parentAlpha)
            font.draw(batch, layout, tx + 1f, ty - 1f)
            font.setColor(1f, 1f, 1f, parentAlpha * if (c.dim) 0.5f else 1f)
            font.draw(batch, layout, tx, ty)
            font.color = old
        }
        batch.setColor(1f, 1f, 1f, 1f)
    }

    private fun outline(batch: Batch, white: com.badlogic.gdx.graphics.g2d.TextureRegion, cx: Float, cy: Float, color: Color, alpha: Float, t: Float) {
        batch.setColor(color.r, color.g, color.b, alpha)
        batch.draw(white, cx + 1f, cy + 1f, cell - 2f, t)
        batch.draw(white, cx + 1f, cy + cell - 1f - t, cell - 2f, t)
        batch.draw(white, cx + 1f, cy + 1f, t, cell - 2f)
        batch.draw(white, cx + cell - 1f - t, cy + 1f, t, cell - 2f)
    }
}
