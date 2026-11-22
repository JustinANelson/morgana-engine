package com.morgana.client.editor

import com.morgana.client.state.MapEditorTool
import com.morgana.domain.map.AutotileMode
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.TilePlacement
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.TileCoord

/**
 * The map editor's brain: the map being edited (the *draft*), the current tool, layer, stamp brush and attribute, and the
 * undo/redo history. It has no graphics or networking, so every editing rule is unit-tested, and any window layout (docked
 * or floating) just drives it.
 *
 * Edits never touch the server: the draft is a private working copy that the editor saves explicitly. [onDraftChanged]
 * lets the host show the draft live (the world renderer draws it as the active map).
 *
 * **Undo steps** are strokes: a drag across many tiles is one step. Hosts call [beginStroke] on mouse-down and
 * [endStroke] on mouse-up; a lone call to [apply] or [fillRect] outside a stroke is its own step. No-op edits (painting the
 * tile that is already there) record nothing.
 */
class MapEditSession(private val maxHistory: Int = DEFAULT_HISTORY) {

    /** The map being edited, or null before one is loaded. */
    var draft: MapDefinition? = null
        private set

    var tool: MapEditorTool = MapEditorTool.BRUSH
    var layer: MapLayer = MapLayer.GROUND

    // The stamp brush: a rectangle of tiles cut from one tileset, placed with its top-left corner on the clicked tile.
    var tilesetId: Int = 1
    var brushX: Int = 0
    var brushY: Int = 0
    var brushWidth: Int = 1
    var brushHeight: Int = 1
    var autotileMode: AutotileMode = AutotileMode.NONE

    /**
     * Animation painted with the brush: with more than 1 frame each placed tile animates through the tiles to its right
     * ([com.morgana.domain.map.TileAnimation]), a brush-width apart so a multi-tile stamp animates as one object.
     */
    var animationFrames: Int = 1
        set(value) { field = value.coerceIn(1, com.morgana.domain.map.TileAnimation.MAX_FRAMES) }
    var animationSpeedMs: Int = DEFAULT_ANIMATION_SPEED_MS
        set(value) { field = value.coerceIn(com.morgana.domain.map.TileAnimation.MIN_SPEED_MS, com.morgana.domain.map.TileAnimation.MAX_SPEED_MS) }

    /** Attribute painted along with (or, with [attributeOnly], instead of) tile graphics. */
    var attribute: TileAttribute = TileAttribute.None
    var attributeOnly: Boolean = false
    var npcSpawnId: Int = 4

    /** Layers the host should not draw (a viewing aid; editing still targets [layer]). */
    val hiddenLayers: MutableSet<MapLayer> = linkedSetOf()

    /** Whether the host fades the layers other than [layer] so the one being painted stands out (a viewing aid). */
    var dimOtherLayers: Boolean = false

    /** Whether the host draws a tile grid over the map (a viewing aid). */
    var showGrid: Boolean = true

    fun isLayerVisible(layer: MapLayer): Boolean = layer !in hiddenLayers

    /** Shows or hides one layer's graphics; returns true when it is now visible. Editing still targets [layer]. */
    fun toggleLayerVisible(target: MapLayer): Boolean {
        if (!hiddenLayers.remove(target)) hiddenLayers.add(target)
        return isLayerVisible(target)
    }

    /** Called with the new draft after every change, including undo and redo. */
    var onDraftChanged: ((MapDefinition) -> Unit)? = null

    /** Called when the Event tool is clicked at tile ([x], [y]) on map [mapId]; [secondary] is right-click. */
    var onEventTileClick: ((mapId: Int, x: Int, y: Int, secondary: Boolean) -> Unit)? = null

    private val undoStack = ArrayDeque<MapDefinition>()
    private val redoStack = ArrayDeque<MapDefinition>()
    private var strokeStart: MapDefinition? = null
    private var saved: MapDefinition? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size

    /** True when the draft differs from what was last loaded or saved. */
    val isDirty: Boolean get() = draft != saved

    // ---- loading and saving ----

    /** Replaces the draft (a new map, or the server's version) and clears history; the loaded map counts as saved. */
    fun load(map: MapDefinition?) {
        draft = map
        saved = map
        undoStack.clear()
        redoStack.clear()
        strokeStart = null
    }

    /** Declares the current draft saved (call after sending it to the server). */
    fun markSaved() {
        saved = draft
    }

    /**
     * Changes the map's size, keeping the cells that still fit (anchored at the top-left corner), adding empty cells when it
     * grows and dropping NPC spawns that fall outside. Sizes are limited to the engine's range. One undo step.
     */
    fun resize(width: Int, height: Int): Boolean {
        if (width !in MapPropertiesForm.MIN_SIZE..MapPropertiesForm.MAX_SIZE || height !in MapPropertiesForm.MIN_SIZE..MapPropertiesForm.MAX_SIZE) return false
        return edit { d ->
            if (d.width == width && d.height == height) return@edit d
            val cells = List(width * height) { i -> d.getCell(i % width, i / width) ?: MapCell() }
            val spawns = d.npcSpawns.filter { it.spawnX < width && it.spawnY < height }.mapIndexed { i, s -> s.copy(slotIndex = i) }
            d.copy(width = width, height = height, cells = cells, npcSpawns = spawns)
        }
    }

    /** Applies non-painting changes such as the map's name or neighbours; recorded as one undo step. */
    fun updateProperties(change: (MapDefinition) -> MapDefinition): Boolean = edit { d -> change(d) }

    // ---- brush ----

    /** Selects one tile of a tileset as the brush. */
    fun selectTile(tilesetId: Int, x: Int, y: Int) = selectStamp(tilesetId, x, y, 1, 1)

    /** Selects a [width] x [height] block of tiles starting at ([x], [y]) as the brush. */
    fun selectStamp(tilesetId: Int, x: Int, y: Int, width: Int, height: Int) {
        require(width in 1..MAX_STAMP && height in 1..MAX_STAMP) { "Stamp must be 1..$MAX_STAMP tiles on each side" }
        this.tilesetId = tilesetId
        brushX = x
        brushY = y
        brushWidth = width
        brushHeight = height
    }

    /** The tiles the brush would place with its top-left corner at ([x], [y]), for a hover preview. */
    fun footprint(x: Int, y: Int): List<Pair<TileCoord, TilePlacement>> {
        val d = draft ?: return emptyList()
        val out = ArrayList<Pair<TileCoord, TilePlacement>>()
        for (dy in 0 until brushHeight) for (dx in 0 until brushWidth) {
            if (d.isInBounds(x + dx, y + dy)) out += TileCoord(x + dx, y + dy) to placementFor(brushX + dx, brushY + dy)
        }
        return out
    }

    // ---- strokes and history ----

    fun beginStroke() {
        if (strokeStart == null) strokeStart = draft
    }

    fun endStroke() {
        val start = strokeStart ?: return
        strokeStart = null
        val now = draft ?: return
        if (start != now) record(start)
    }

    fun undo(): Boolean {
        val current = draft ?: return false
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(current)
        commit(previous)
        return true
    }

    fun redo(): Boolean {
        val current = draft ?: return false
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(current)
        commit(next)
        return true
    }

    // ---- editing ----

    /**
     * Performs the current tool at ([x], [y]). [secondary] (right mouse button) erases, or removes an NPC spawn when that
     * tool is active. Returns true if the draft changed.
     */
    fun apply(x: Int, y: Int, secondary: Boolean = false): Boolean {
        val d = draft ?: return false
        if (!d.isInBounds(x, y)) return false
        if (tool == MapEditorTool.EVENT) {
            onEventTileClick?.invoke(d.id, x, y, secondary)
            return false
        }
        if (secondary) return when (tool) {
            MapEditorTool.NPC_SPAWN -> edit { removeSpawn(it, x, y) }
            MapEditorTool.EYEDROPPER -> false
            else -> edit { erase(it, x, y) }
        }
        return when (tool) {
            MapEditorTool.BRUSH, MapEditorTool.RECTANGLE -> edit { paint(it, x, y) }
            MapEditorTool.ERASER -> edit { erase(it, x, y) }
            MapEditorTool.FLOOD_FILL -> edit { floodFill(it, x, y) }
            MapEditorTool.EYEDROPPER -> { pick(x, y); false }
            MapEditorTool.NPC_SPAWN -> edit { placeSpawn(it, x, y) }
            MapEditorTool.EVENT -> false
        }
    }

    // ---- rectangle drag ----

    /** Corner where a rectangle drag started, or null when none is in progress. */
    var rectAnchor: TileCoord? = null
        private set
    private var rectErase = false

    /** Whether the drag in progress will clear tiles (right mouse button) instead of filling them. */
    val rectIsErase: Boolean get() = rectErase

    /** Starts a rectangle drag at ([x], [y]); [erase] clears the box instead of filling it. */
    fun beginRect(x: Int, y: Int, erase: Boolean = false) {
        if (!inBounds(x, y)) return
        rectAnchor = TileCoord(x, y)
        rectErase = erase
    }

    /** Ends the drag by filling (or clearing) the box from the anchor to ([x], [y]); returns true if the draft changed. */
    fun finishRect(x: Int, y: Int): Boolean {
        val anchor = rectAnchor ?: return false
        rectAnchor = null
        return fillRect(anchor.x, anchor.y, x, y, erase = rectErase)
    }

    /** Abandons a rectangle drag without changing anything. */
    fun cancelRect() {
        rectAnchor = null
    }

    /** Places the brush at ([x], [y]) whatever the current tool is. Returns true if the draft changed. */
    fun paintAt(x: Int, y: Int): Boolean = inBounds(x, y) && edit { paint(it, x, y) }

    /** Clears the current layer at ([x], [y]) whatever the current tool is. */
    fun eraseAt(x: Int, y: Int): Boolean = inBounds(x, y) && edit { erase(it, x, y) }

    /** Flood-fills from ([x], [y]) with the brush's first tile whatever the current tool is. */
    fun floodFillAt(x: Int, y: Int): Boolean = inBounds(x, y) && edit { floodFill(it, x, y) }

    private fun inBounds(x: Int, y: Int): Boolean = draft?.isInBounds(x, y) == true

    /**
     * Fills the rectangle between two corners (in any order, clamped to the map) with the brush, repeating a multi-tile
     * stamp across it; with [erase] it clears the current layer instead. One undo step.
     */
    fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int, erase: Boolean = false): Boolean = edit { d ->
        val minX = minOf(x0, x1).coerceAtLeast(0)
        val maxX = maxOf(x0, x1).coerceAtMost(d.width - 1)
        val minY = minOf(y0, y1).coerceAtLeast(0)
        val maxY = maxOf(y0, y1).coerceAtMost(d.height - 1)
        if (minX > maxX || minY > maxY) return@edit d
        val cells = d.cells.toMutableList()
        for (ty in minY..maxY) for (tx in minX..maxX) {
            val i = d.cellIndex(tx, ty)
            cells[i] = if (erase) erasedCell(cells[i]) else paintedCell(
                cells[i], placementFor(brushX + (tx - minX) % brushWidth, brushY + (ty - minY) % brushHeight)
            )
        }
        d.copy(cells = cells)
    }

    /**
     * Removes every tile on [target] (the current layer by default) across the whole map, leaving the other layers, the
     * attributes and the spawns alone. One undo step; returns false when the layer was already empty.
     */
    fun clearLayer(target: MapLayer = layer): Boolean = edit { d ->
        if (d.cells.none { target in it.layers }) return@edit d
        d.copy(cells = d.cells.map { if (target in it.layers) it.copy(layers = it.layers - target) else it })
    }

    /** How many tiles of the map are painted on [target]. */
    fun tilesOn(target: MapLayer): Int = draft?.cells?.count { target in it.layers } ?: 0

    // ---- operations (each takes a map and returns the changed map) ----

    private fun paint(d: MapDefinition, x: Int, y: Int): MapDefinition {
        val cells = d.cells.toMutableList()
        for (dy in 0 until brushHeight) for (dx in 0 until brushWidth) {
            val tx = x + dx
            val ty = y + dy
            if (!d.isInBounds(tx, ty)) continue
            val i = d.cellIndex(tx, ty)
            cells[i] = paintedCell(cells[i], placementFor(brushX + dx, brushY + dy))
        }
        return d.copy(cells = cells)
    }

    private fun paintedCell(cell: MapCell, placement: TilePlacement): MapCell =
        if (attributeOnly) cell.copy(attribute = attribute)
        else cell.copy(
            layers = cell.layers + (layer to placement),
            attribute = if (attribute != TileAttribute.None) attribute else cell.attribute
        )

    private fun erasedCell(cell: MapCell): MapCell = cell.copy(
        layers = cell.layers - layer,
        attribute = if (attributeOnly || attribute != TileAttribute.None) TileAttribute.None else cell.attribute
    )

    private fun erase(d: MapDefinition, x: Int, y: Int): MapDefinition {
        val cells = d.cells.toMutableList()
        val i = d.cellIndex(x, y)
        cells[i] = erasedCell(cells[i])
        return d.copy(cells = cells)
    }

    private fun floodFill(d: MapDefinition, startX: Int, startY: Int): MapDefinition {
        val target = d.getCell(startX, startY)?.layers?.get(layer)
        val fill = placementFor(brushX, brushY)
        if (!attributeOnly && target == fill && attribute == TileAttribute.None) return d

        val cells = d.cells.toMutableList()
        val visited = HashSet<TileCoord>()
        val queue = ArrayDeque<TileCoord>()
        queue.add(TileCoord(startX, startY))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!d.isInBounds(cur) || !visited.add(cur)) continue
            val i = d.cellIndex(cur.x, cur.y)
            if (cells[i].layers[layer] != target) continue
            cells[i] = paintedCell(cells[i], fill)
            Direction.CARDINALS.forEach { dir ->
                val next = cur.step(dir)
                if (d.isInBounds(next) && next !in visited) queue.add(next)
            }
        }
        return d.copy(cells = cells)
    }

    /** Copies the tile under the cursor into the brush (and its attribute), then switches to the paint tool. */
    private fun pick(x: Int, y: Int) {
        val d = draft ?: return
        val cell = d.getCell(x, y) ?: return
        val placement = cell.layers[layer] ?: cell.layers[MapLayer.GROUND]
        if (placement != null) {
            selectTile(placement.tilesetId, placement.srcTileX, placement.srcTileY)
            autotileMode = placement.autotileMode
            // An animated tile is picked up with its animation (the legacy water tile animates by itself).
            val generic = com.morgana.domain.map.TileAnimation.isAnimated(placement)
            animationFrames = if (generic) placement.animationFrames else 1
            if (generic) animationSpeedMs = placement.animationSpeedMs
        }
        attribute = cell.attribute
        tool = MapEditorTool.BRUSH
    }

    private fun placeSpawn(d: MapDefinition, x: Int, y: Int): MapDefinition {
        val kept = d.npcSpawns.filterNot { it.spawnX == x && it.spawnY == y }
        return d.copy(npcSpawns = kept + MapNpcSpawnSlot(slotIndex = kept.size, npcId = npcSpawnId, spawnX = x, spawnY = y, respawnTicks = 100))
    }

    private fun removeSpawn(d: MapDefinition, x: Int, y: Int): MapDefinition =
        d.copy(npcSpawns = d.npcSpawns.filterNot { it.spawnX == x && it.spawnY == y }.mapIndexed { i, slot -> slot.copy(slotIndex = i) })

    /**
     * The placement for source tile ([sx], [sy]). The first water tile of the built-in tileset is the engine's own animated
     * tile; otherwise the brush's animation applies, with frames a brush-width apart.
     */
    private fun placementFor(sx: Int, sy: Int): TilePlacement {
        val water = tilesetId == 1 && sx == 3 && sy == 0
        val animated = !water && animationFrames > 1
        return TilePlacement(
            tilesetId = tilesetId,
            srcTileX = sx,
            srcTileY = sy,
            autotileMode = if (water) AutotileMode.ANIMATED_3_FRAME else autotileMode,
            animationFrames = if (water) 3 else if (animated) animationFrames else 1,
            animationSpeedMs = if (animated) animationSpeedMs else DEFAULT_ANIMATION_SPEED_MS,
            animationFrameStep = if (animated) brushWidth else 1
        )
    }

    // ---- plumbing ----

    /** Runs one change as its own undo step (or as part of the open stroke). Returns true if the draft changed. */
    private fun edit(change: (MapDefinition) -> MapDefinition): Boolean {
        val before = draft ?: return false
        val own = strokeStart == null
        if (own) beginStroke()
        val after = change(before)
        val changed = after != before
        if (changed) commit(after)
        if (own) endStroke()
        return changed
    }

    private fun commit(map: MapDefinition) {
        draft = map
        onDraftChanged?.invoke(map)
    }

    private fun record(snapshot: MapDefinition) {
        undoStack.addLast(snapshot)
        while (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
    }

    companion object {
        const val DEFAULT_HISTORY = 100
        const val MAX_STAMP = 16
        const val DEFAULT_ANIMATION_SPEED_MS = 250
    }
}
