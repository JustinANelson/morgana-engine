package com.morgana.client.editor.dock

/** The four edges a panel can be docked to. A panel that is not docked is floating. */
enum class DockZone { LEFT, RIGHT, TOP, BOTTOM }

/** Pixels the docked panels cover on each side of the screen; the live world is shown in what is left. */
data class ViewInsets(val left: Float = 0f, val right: Float = 0f, val top: Float = 0f, val bottom: Float = 0f) {
    val isNone: Boolean get() = left == 0f && right == 0f && top == 0f && bottom == 0f

    /**
     * How far the world camera must move so the player stays centred in the free area instead of the whole screen.
     * Screen y grows downwards and world y upwards: a covered right side moves the camera right (the world appears to
     * shift left), a covered top moves it up.
     */
    fun cameraShift(zoom: Float): Pair<Float, Float> = ((right - left) * 0.5f * zoom) to ((top - bottom) * 0.5f * zoom)

    companion object {
        val NONE = ViewInsets()
    }
}

/** A rectangle in screen pixels with the origin at the top-left corner and y growing downwards. */
data class PanelRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun contains(px: Float, py: Float): Boolean = px >= x && px < x + width && py >= y && py < y + height
}

/**
 * Where one panel lives. Docked panels have a [zone] and an [order] within it; floating panels have a null zone and a
 * position and size. [lastZone] is where "dock" returns a floating panel to.
 */
data class PanelPlacement(
    val id: String,
    val zone: DockZone?,
    val order: Int,
    val lastZone: DockZone,
    val floatX: Float,
    val floatY: Float,
    val floatWidth: Float,
    val floatHeight: Float,
    val visible: Boolean = true,
    val collapsed: Boolean = false,
    /** Put away on the panel bar: not shown, but one click brings it back. A minimized panel is never [visible]. */
    val minimized: Boolean = false
) {
    val isFloating: Boolean get() = zone == null
}

/** What the layout needs to know about a panel when it is registered. */
data class PanelSpec(
    val id: String,
    val defaultZone: DockZone,
    val preferredWidth: Float,
    val preferredHeight: Float,
    val visibleByDefault: Boolean = true,
    /** Start as a floating window (docking returns it to [defaultZone]) instead of docked there. */
    val startFloating: Boolean = false
)

/**
 * The editor workspace's layout, with no graphics: which panels are docked where, floating, hidden or collapsed, the
 * rectangle each one occupies on a given screen, and the drag-to-dock rules. The Scene2D workspace only draws what this
 * decides, so every rule is unit-tested.
 *
 * The arrangement is the classic one: toolbars along the top, a status bar along the bottom (both spanning the full
 * width), and tall panel columns on the left and right between them. The free space in the middle shows the live world.
 */
class DockLayout(specs: List<PanelSpec>) {

    private val specById = specs.associateBy { it.id }
    private val placements = LinkedHashMap<String, PanelPlacement>()

    /** Widths the user dragged the side columns to; a column without one is as wide as its widest panel asks. */
    private val columnWidths = LinkedHashMap<DockZone, Float>()

    /** Heights the user dragged docked panels to; in a column they share the room in proportion to these (or the preferred height). */
    private val dockedHeights = LinkedHashMap<String, Float>()

    init {
        require(specs.map { it.id }.toSet().size == specs.size) { "Panel ids must be unique" }
        for (zone in DockZone.entries) {
            specs.filter { it.defaultZone == zone }.forEachIndexed { index, spec ->
                placements[spec.id] = PanelPlacement(
                    id = spec.id, zone = if (spec.startFloating) null else zone, order = index, lastZone = zone,
                    floatX = if (spec.startFloating) CASCADE_START else 80f + 24f * placements.size,
                    floatY = if (spec.startFloating) CASCADE_START else 80f + 24f * placements.size,
                    floatWidth = spec.preferredWidth.coerceAtLeast(MIN_FLOAT_WIDTH),
                    floatHeight = spec.preferredHeight.coerceAtLeast(MIN_FLOAT_HEIGHT),
                    visible = spec.visibleByDefault
                )
            }
        }
    }

    val ids: List<String> get() = specById.keys.toList()

    fun placement(id: String): PanelPlacement = placements[id] ?: error("Unknown panel '$id'")

    /** Visible panels docked in [zone], in display order. */
    fun docked(zone: DockZone): List<PanelPlacement> =
        placements.values.filter { it.zone == zone && it.visible }.sortedBy { it.order }

    /** Visible floating panels, back to front in registration order. */
    fun floating(): List<PanelPlacement> = placements.values.filter { it.isFloating && it.visible }

    // ---- changes ----

    /** Docks a panel into [zone] at [index] (end when null); the panel becomes visible. */
    fun dock(id: String, zone: DockZone, index: Int? = null) {
        val current = placement(id)
        val others = docked(zone).filter { it.id != id }.map { it.id }.toMutableList()
        others.add((index ?: others.size).coerceIn(0, others.size), id)
        placements[id] = current.copy(zone = zone, lastZone = zone, visible = true, minimized = false)
        renumber(zone, others)
        // The zone the panel left may have a gap in its numbering; ordering stays correct, so nothing else to do.
    }

    /** Returns a floating panel to the zone it was last docked in. */
    fun dockBack(id: String) = dock(id, placement(id).lastZone)

    /** Makes a panel float with its top-left corner at ([x], [y]); size is kept unless given. */
    fun float(id: String, x: Float, y: Float, width: Float? = null, height: Float? = null) {
        val current = placement(id)
        placements[id] = current.copy(
            zone = null, floatX = x, floatY = y,
            floatWidth = width ?: current.floatWidth, floatHeight = height ?: current.floatHeight, visible = true, minimized = false
        )
    }

    fun moveFloating(id: String, x: Float, y: Float) {
        val current = placement(id)
        require(current.isFloating) { "Panel '$id' is docked" }
        placements[id] = current.copy(floatX = x, floatY = y)
    }

    fun resizeFloating(id: String, width: Float, height: Float) {
        val current = placement(id)
        placements[id] = current.copy(floatWidth = width.coerceAtLeast(MIN_FLOAT_WIDTH), floatHeight = height.coerceAtLeast(MIN_FLOAT_HEIGHT))
    }

    /** Shows or closes a panel. Closing also takes it off the panel bar; use [minimize] to keep it there. */
    fun setVisible(id: String, visible: Boolean) {
        placements[id] = placement(id).copy(visible = visible, minimized = false)
    }

    /** Puts a panel away on the panel bar (see [minimized]); [show] or [setVisible] brings it back. */
    fun minimize(id: String) {
        placements[id] = placement(id).copy(visible = false, minimized = true)
    }

    /** The minimized panels, in registration order: what the panel bar shows. */
    fun minimized(): List<PanelPlacement> = placements.values.filter { it.minimized }

    /** The panels that are currently shown. */
    fun visibleIds(): Set<String> = placements.values.filter { it.visible }.map { it.id }.toSet()

    /**
     * Arranges the layout as a preset: everything back at its default place, then exactly the panels in [shown] visible
     * (ids that are not panels are ignored). Nothing is minimized afterwards.
     */
    fun applyPreset(shown: Collection<String>) {
        reset()
        for (id in placements.keys.toList()) setVisible(id, id in shown)
    }

    /**
     * Shows a panel. A floating panel that would land on top of another visible floating panel (its top-left corner within
     * [STACK_DISTANCE] of the other's) moves to the first free step of a cascade instead, so panels opened one after another
     * fan out rather than hiding each other. A panel the user has placed somewhere clear stays where it is.
     */
    fun show(id: String) {
        val current = placement(id)
        placements[id] = current.copy(visible = true, minimized = false)
        if (!current.isFloating) return
        val others = floating().filter { it.id != id }
        fun stacked(x: Float, y: Float) = others.any { Math.abs(it.floatX - x) < STACK_DISTANCE && Math.abs(it.floatY - y) < STACK_DISTANCE }
        if (!stacked(current.floatX, current.floatY)) return
        for (step in 0 until CASCADE_STEPS) {
            val x = CASCADE_START + CASCADE_STEP * step
            val y = CASCADE_START + CASCADE_STEP * step
            if (!stacked(x, y)) {
                placements[id] = placements.getValue(id).copy(floatX = x, floatY = y)
                return
            }
        }
    }

    fun toggleCollapsed(id: String) {
        val current = placement(id)
        placements[id] = current.copy(collapsed = !current.collapsed)
    }

    /** Moves a docked panel up or down within its zone by [delta] places (negative = earlier). */
    fun moveWithinZone(id: String, delta: Int) {
        val zone = placement(id).zone ?: return
        val ids = docked(zone).map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        if (from < 0) return
        ids.removeAt(from)
        ids.add((from + delta).coerceIn(0, ids.size), id)
        renumber(zone, ids)
    }

    /** Puts every panel back where it started, at its preferred size. */
    fun reset() {
        val fresh = DockLayout(specById.values.toList())
        placements.clear()
        placements.putAll(fresh.placements)
        columnWidths.clear()
        dockedHeights.clear()
    }

    // ---- resizing docked panels ----

    /** The width the user gave the [zone] column, or null when it follows its panels' preferred widths. */
    fun columnWidth(zone: DockZone): Float? = columnWidths[zone]

    /**
     * Sets the width of the left or right column (dragging its inner edge). The width is kept between [MIN_COLUMN_WIDTH]
     * and the share of the screen a column may take, so the world always keeps room. Top and bottom bands are ignored.
     */
    fun setColumnWidth(zone: DockZone, width: Float, screenWidth: Float) {
        if (zone != DockZone.LEFT && zone != DockZone.RIGHT) return
        val max = (screenWidth * MAX_SIDE_SHARE).coerceAtLeast(MIN_COLUMN_WIDTH)
        columnWidths[zone] = width.coerceIn(MIN_COLUMN_WIDTH, max)
    }

    /**
     * Moves the boundary between two neighbouring docked panels in a side column by [delta] pixels (positive = down), as
     * when dragging the line between them: [upperId] grows and [lowerId] shrinks by the same amount, neither below
     * [MIN_DOCKED_HEIGHT]. The other panels in the column keep their size. Returns false when the two are not neighbours
     * in one column or either is collapsed.
     */
    fun resizeSplit(upperId: String, lowerId: String, delta: Float, screenWidth: Float, screenHeight: Float): Boolean {
        val zone = placement(upperId).zone ?: return false
        if (zone != DockZone.LEFT && zone != DockZone.RIGHT || placement(lowerId).zone != zone) return false
        val siblings = docked(zone)
        val i = siblings.indexOfFirst { it.id == upperId }
        if (i < 0 || siblings.getOrNull(i + 1)?.id != lowerId) return false
        if (siblings[i].collapsed || siblings[i + 1].collapsed) return false
        // Freeze every open panel at its current pixel height, so only the two either side of the line change.
        val heights = siblings.filter { !it.collapsed }.associate { it.id to (bounds(it.id, screenWidth, screenHeight)?.height ?: 0f) }
        val upper = heights.getValue(upperId)
        val lower = heights.getValue(lowerId)
        val pair = upper + lower
        if (pair < 2 * MIN_DOCKED_HEIGHT) return false
        val newUpper = (upper + delta).coerceIn(MIN_DOCKED_HEIGHT, pair - MIN_DOCKED_HEIGHT)
        heights.forEach { (id, h) -> dockedHeights[id] = h }
        dockedHeights[upperId] = newUpper
        dockedHeights[lowerId] = pair - newUpper
        return true
    }

    /** The share-of-the-column weight of a docked panel: what the user dragged it to, else its preferred height. */
    private fun heightWeight(id: String): Float = dockedHeights[id] ?: specById.getValue(id).preferredHeight

    private fun renumber(zone: DockZone, orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, pid -> placements[pid] = placement(pid).copy(zone = zone, order = index) }
    }

    // ---- geometry ----

    /** The space the docked panels take on a [screenWidth] x [screenHeight] screen, limited so the world keeps room. */
    fun insets(screenWidth: Float, screenHeight: Float): ViewInsets {
        fun widest(zone: DockZone): Float {
            val panels = docked(zone)
            if (panels.isEmpty()) return 0f
            return columnWidths[zone] ?: panels.maxOf { specById.getValue(it.id).preferredWidth }
        }
        fun tallest(zone: DockZone) = docked(zone).maxOfOrNull { if (it.collapsed) HEADER_HEIGHT else specById.getValue(it.id).preferredHeight } ?: 0f
        val maxSide = screenWidth * MAX_SIDE_SHARE
        val maxBand = screenHeight * MAX_BAND_SHARE
        val top = tallest(DockZone.TOP).coerceAtMost(maxBand)
        val bottom = tallest(DockZone.BOTTOM).coerceAtMost(maxBand)
        var left = widest(DockZone.LEFT).coerceAtMost(maxSide)
        var right = widest(DockZone.RIGHT).coerceAtMost(maxSide)
        // Never let the two columns together swallow the screen.
        if (left + right > screenWidth * MAX_SIDE_TOTAL) {
            val scale = screenWidth * MAX_SIDE_TOTAL / (left + right)
            left *= scale
            right *= scale
        }
        return ViewInsets(left, right, top, bottom)
    }

    /** Where a visible panel is drawn, or null when it is hidden. Floating panels are kept fully reachable on screen. */
    fun bounds(id: String, screenWidth: Float, screenHeight: Float): PanelRect? {
        val p = placement(id)
        if (!p.visible) return null
        val zone = p.zone
        if (zone == null) return clampFloating(p, screenWidth, screenHeight)
        val inset = insets(screenWidth, screenHeight)
        val siblings = docked(zone)
        return when (zone) {
            DockZone.TOP -> band(id, siblings, y = 0f, height = inset.top, screenWidth = screenWidth)
            DockZone.BOTTOM -> band(id, siblings, y = screenHeight - inset.bottom, height = inset.bottom, screenWidth = screenWidth)
            DockZone.LEFT -> column(id, siblings, x = 0f, width = inset.left, top = inset.top, bottom = screenHeight - inset.bottom)
            DockZone.RIGHT -> column(id, siblings, x = screenWidth - inset.right, width = inset.right, top = inset.top, bottom = screenHeight - inset.bottom)
        }
    }

    /** Panels along the top or bottom edge sit side by side; if they do not fill the width the last one stretches. */
    private fun band(id: String, siblings: List<PanelPlacement>, y: Float, height: Float, screenWidth: Float): PanelRect {
        val widths = siblings.map { specById.getValue(it.id).preferredWidth }
        val total = widths.sum()
        val scale = if (total > screenWidth) screenWidth / total else 1f
        var x = 0f
        siblings.forEachIndexed { i, s ->
            val last = i == siblings.lastIndex
            val w = if (last && total * scale < screenWidth) screenWidth - x else widths[i] * scale
            if (s.id == id) return PanelRect(x, y, w, height)
            x += w
        }
        error("Panel $id is not in its zone")
    }

    /** Panels in a side column are stacked; collapsed ones shrink to a header, the others share the rest by preferred height. */
    private fun column(id: String, siblings: List<PanelPlacement>, x: Float, width: Float, top: Float, bottom: Float): PanelRect {
        val available = (bottom - top).coerceAtLeast(0f)
        val collapsedTotal = siblings.filter { it.collapsed }.size * HEADER_HEIGHT
        val weights = siblings.filter { !it.collapsed }.associate { it.id to heightWeight(it.id) }
        val weightSum = weights.values.sum()
        val room = (available - collapsedTotal).coerceAtLeast(0f)
        var y = top
        for (s in siblings) {
            val h = if (s.collapsed) HEADER_HEIGHT else if (weightSum > 0f) room * weights.getValue(s.id) / weightSum else 0f
            if (s.id == id) return PanelRect(x, y, width, h)
            y += h
        }
        error("Panel $id is not in its zone")
    }

    private fun clampFloating(p: PanelPlacement, screenWidth: Float, screenHeight: Float): PanelRect {
        val w = p.floatWidth.coerceAtMost(screenWidth)
        val h = (if (p.collapsed) HEADER_HEIGHT else p.floatHeight).coerceAtMost(screenHeight)
        // Keep at least the header on screen so a panel can always be grabbed.
        val x = p.floatX.coerceIn(-(w - GRAB_MARGIN), screenWidth - GRAB_MARGIN)
        val y = p.floatY.coerceIn(0f, screenHeight - HEADER_HEIGHT)
        return PanelRect(x, y, w, h)
    }

    // ---- drag to dock ----

    /**
     * The edge a floating panel would dock to if released with the pointer at ([x], [y]): the nearest edge within
     * [margin] pixels, or null in the middle of the screen. Top and bottom win over the sides in the corners, matching
     * the classic arrangement where the bands span the full width.
     */
    fun dropZoneAt(x: Float, y: Float, screenWidth: Float, screenHeight: Float, margin: Float = DROP_MARGIN): DockZone? {
        val candidates = listOf(
            DockZone.TOP to y,
            DockZone.BOTTOM to screenHeight - y,
            DockZone.LEFT to x,
            DockZone.RIGHT to screenWidth - x
        ).filter { it.second in 0f..margin }
        return candidates.minByOrNull { it.second }?.first
    }

    // ---- saving ----

    /**
     * Compact text form for remembering the layout between runs: a format line, then one line per panel. The format line
     * ([FORMAT]) changes when the default arrangement does, so a layout saved before that is dropped instead of keeping an
     * outdated, cluttered arrangement on screen.
     */
    fun serialize(): String = FORMAT + "\n" + placements.values.joinToString("\n") { p ->
        listOf(p.id, p.zone?.name ?: "FLOAT", p.order, p.lastZone.name, p.floatX, p.floatY, p.floatWidth, p.floatHeight, p.visible, p.collapsed, p.minimized, dockedHeights[p.id] ?: "")
            .joinToString("|")
    } + columnWidths.entries.joinToString("") { (zone, w) -> "\n$COLUMN_LINE|${zone.name}|$w" }

    /**
     * Applies a layout saved by [serialize] on top of the defaults. Unknown panels and damaged lines are skipped, so an
     * old or corrupt file can never prevent the editor from opening.
     */
    fun restore(text: String) {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != FORMAT) return
        for (line in lines.drop(1)) {
            val f = line.split('|')
            if (f.size == 3 && f[0] == COLUMN_LINE) {
                val zone = DockZone.entries.firstOrNull { it.name == f[1] } ?: continue
                val w = f[2].toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: continue
                if (zone == DockZone.LEFT || zone == DockZone.RIGHT) columnWidths[zone] = w.coerceAtLeast(MIN_COLUMN_WIDTH)
                continue
            }
            // Eleven fields before docked panels could be resized; a twelfth (empty when unset) holds the dragged height.
            if (f.size != 11 && f.size != 12) continue
            val id = f[0]
            val current = placements[id] ?: continue
            val zone = if (f[1] == "FLOAT") null else DockZone.entries.firstOrNull { it.name == f[1] } ?: continue
            val last = DockZone.entries.firstOrNull { it.name == f[3] } ?: continue
            val order = f[2].toIntOrNull() ?: continue
            val nums = f.subList(4, 8).map { it.toFloatOrNull() }
            if (nums.any { it == null || it.isNaN() || it.isInfinite() }) continue
            placements[id] = current.copy(
                zone = zone, order = order, lastZone = last,
                floatX = nums[0]!!, floatY = nums[1]!!,
                floatWidth = nums[2]!!.coerceAtLeast(MIN_FLOAT_WIDTH), floatHeight = nums[3]!!.coerceAtLeast(MIN_FLOAT_HEIGHT),
                visible = f[8].toBooleanStrictOrNull() ?: current.visible,
                collapsed = f[9].toBooleanStrictOrNull() ?: current.collapsed,
                minimized = f[10].toBooleanStrictOrNull() ?: current.minimized
            ).let { if (it.minimized) it.copy(visible = false) else it }
            f.getOrNull(11)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }?.let { dockedHeights[id] = it.coerceAtLeast(MIN_DOCKED_HEIGHT) }
        }
        // Saved orders may collide or have gaps; renumber each zone in a stable order.
        for (zone in DockZone.entries) renumber(zone, placements.values.filter { it.zone == zone }.sortedBy { it.order }.map { it.id })
    }

    companion object {
        /** First line of a saved layout; bumped when the default arrangement changes. */
        const val FORMAT = "layout-v3"
        const val HEADER_HEIGHT = 24f
        /** Narrowest a side column can be dragged. */
        const val MIN_COLUMN_WIDTH = 160f
        /** Shortest a docked panel in a column can be dragged (it can still be collapsed to its header). */
        const val MIN_DOCKED_HEIGHT = 60f
        /** Marks a saved line holding a column width rather than a panel. */
        const val COLUMN_LINE = "#column"
        const val MIN_FLOAT_WIDTH = 160f
        const val MIN_FLOAT_HEIGHT = 80f
        const val GRAB_MARGIN = 48f
        const val DROP_MARGIN = 56f
        const val MAX_SIDE_SHARE = 0.4f
        const val MAX_SIDE_TOTAL = 0.6f
        const val MAX_BAND_SHARE = 0.3f
        const val STACK_DISTANCE = 40f
        const val CASCADE_START = 80f
        const val CASCADE_STEP = 40f
        const val CASCADE_STEPS = 8
    }
}
