package com.morgana.client.editor

import com.morgana.client.state.MapEditorTool
import com.morgana.domain.map.AutotileMode
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.TilePlacement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The map editor's rules: tools, stamps, layers, attributes, and undo/redo in whole strokes. */
class MapEditSessionTest {

    private fun session(width: Int = 10, height: Int = 8): MapEditSession =
        MapEditSession().also { it.load(MapDefinition(id = 1, name = "Test", width = width, height = height)) }

    private fun MapEditSession.tileAt(x: Int, y: Int, layer: MapLayer = MapLayer.GROUND): TilePlacement? =
        draft!!.getCell(x, y)!!.layers[layer]

    @Test
    fun `the brush paints the selected tile on the selected layer only`() {
        val s = session()
        s.selectTile(1, 4, 2)
        s.layer = MapLayer.MASK_1

        assertTrue(s.apply(3, 3))

        assertEquals(TilePlacement(tilesetId = 1, srcTileX = 4, srcTileY = 2), s.tileAt(3, 3, MapLayer.MASK_1))
        assertNull(s.tileAt(3, 3, MapLayer.GROUND))
        assertNull(s.tileAt(4, 3, MapLayer.MASK_1), "neighbours are untouched")
    }

    @Test
    fun `the first water tile becomes the animated tile`() {
        val s = session()
        s.selectTile(1, 3, 0)
        s.apply(1, 1)
        val placed = s.tileAt(1, 1)!!
        assertEquals(AutotileMode.ANIMATED_3_FRAME, placed.autotileMode)
        assertEquals(3, placed.animationFrames)
    }

    @Test
    fun `out of bounds clicks change nothing`() {
        val s = session()
        assertFalse(s.apply(-1, 0))
        assertFalse(s.apply(10, 0))
        assertFalse(s.apply(0, 8))
        assertFalse(s.canUndo)
    }

    @Test
    fun `a stamp places a block of tiles and clips at the map edge`() {
        val s = session()
        s.selectStamp(1, 2, 4, width = 3, height = 2)

        s.apply(8, 6)   // only (8,6) (9,6) (8,7) (9,7) are inside the 10 x 8 map

        assertEquals(2, s.tileAt(8, 6)!!.srcTileX)
        assertEquals(4, s.tileAt(8, 6)!!.srcTileY)
        assertEquals(3, s.tileAt(9, 6)!!.srcTileX)
        assertEquals(5, s.tileAt(8, 7)!!.srcTileY)
        assertEquals(listOf(8 to 6, 9 to 6, 8 to 7, 9 to 7), s.footprint(8, 6).map { it.first.x to it.first.y })
    }

    @Test
    fun `stamp sizes are limited`() {
        val s = session()
        assertThrows(IllegalArgumentException::class.java) { s.selectStamp(1, 0, 0, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { s.selectStamp(1, 0, 0, 1, MapEditSession.MAX_STAMP + 1) }
    }

    @Test
    fun `the eraser and right click clear the layer`() {
        val s = session()
        s.selectTile(1, 5, 5)
        s.apply(2, 2)
        s.tool = MapEditorTool.ERASER
        assertTrue(s.apply(2, 2))
        assertNull(s.tileAt(2, 2))

        s.tool = MapEditorTool.BRUSH
        s.apply(3, 3)
        assertTrue(s.apply(3, 3, secondary = true))
        assertNull(s.tileAt(3, 3))
    }

    @Test
    fun `painting an attribute along with the tile or on its own`() {
        val s = session()
        s.attribute = TileAttribute.Blocked
        s.selectTile(1, 1, 1)
        s.apply(1, 1)
        assertEquals(TileAttribute.Blocked, s.draft!!.getCell(1, 1)!!.attribute)
        assertEquals(1, s.tileAt(1, 1)!!.srcTileX)

        s.attributeOnly = true
        s.selectTile(1, 9, 9)
        s.apply(2, 1)
        assertEquals(TileAttribute.Blocked, s.draft!!.getCell(2, 1)!!.attribute)
        assertNull(s.tileAt(2, 1), "attribute-only leaves the graphics alone")
    }

    @Test
    fun `flood fill replaces a connected region and stops at a different tile`() {
        val s = session(width = 6, height = 6)
        s.selectTile(1, 7, 0)
        s.tool = MapEditorTool.BRUSH
        for (y in 0 until 6) s.apply(3, y)          // a wall of tile 7 down column 3
        s.selectTile(1, 2, 0)
        s.tool = MapEditorTool.FLOOD_FILL

        assertTrue(s.apply(0, 0))

        assertEquals(2, s.tileAt(0, 0)!!.srcTileX)
        assertEquals(2, s.tileAt(2, 5)!!.srcTileX)
        assertEquals(7, s.tileAt(3, 3)!!.srcTileX, "the wall blocks the fill")
        assertNull(s.tileAt(4, 0), "the other side of the wall is a separate region")
    }

    @Test
    fun `filling with the tile that is already there records nothing`() {
        val s = session()
        s.selectTile(1, 2, 0)
        s.apply(0, 0)
        val depth = s.undoDepth
        s.tool = MapEditorTool.FLOOD_FILL
        s.apply(0, 0)
        s.apply(0, 0)
        assertEquals(depth, s.undoDepth)
    }

    @Test
    fun `rectangle fill covers the area between two corners in any order and clips`() {
        val s = session()
        s.selectTile(1, 6, 1)

        assertTrue(s.fillRect(4, 3, 1, 1))     // corners given backwards

        for (y in 1..3) for (x in 1..4) assertEquals(6, s.tileAt(x, y)!!.srcTileX, "($x,$y)")
        assertNull(s.tileAt(5, 1))
        assertNull(s.tileAt(0, 1))

        assertTrue(s.fillRect(8, 6, 40, 40))   // clipped to the map
        assertEquals(6, s.tileAt(9, 7)!!.srcTileX)
    }

    @Test
    fun `rectangle fill repeats a multi tile stamp`() {
        val s = session()
        s.selectStamp(1, 0, 4, width = 2, height = 1)

        s.fillRect(0, 0, 5, 0)

        assertEquals(listOf(0, 1, 0, 1, 0, 1), (0..5).map { s.tileAt(it, 0)!!.srcTileX })
    }

    @Test
    fun `rectangle erase clears the layer`() {
        val s = session()
        s.selectTile(1, 1, 1)
        s.fillRect(0, 0, 9, 7)
        s.fillRect(2, 2, 3, 3, erase = true)
        assertNull(s.tileAt(2, 2))
        assertEquals(1, s.tileAt(1, 1)!!.srcTileX)
    }

    @Test
    fun `the eyedropper copies the tile and attribute and switches to the brush`() {
        val s = session()
        s.selectTile(1, 9, 2)
        s.attribute = TileAttribute.BankTile
        s.apply(4, 4)
        s.selectTile(1, 0, 0)
        s.attribute = TileAttribute.None
        s.tool = MapEditorTool.EYEDROPPER

        assertFalse(s.apply(4, 4), "picking does not change the map")

        assertEquals(9, s.brushX)
        assertEquals(2, s.brushY)
        assertEquals(TileAttribute.BankTile, s.attribute)
        assertEquals(MapEditorTool.BRUSH, s.tool)
    }

    @Test
    fun `NPC spawns are placed replaced and removed`() {
        val s = session()
        s.tool = MapEditorTool.NPC_SPAWN
        s.npcSpawnId = 7
        s.apply(2, 2)
        s.npcSpawnId = 9
        s.apply(2, 2)
        assertEquals(listOf(9), s.draft!!.npcSpawns.map { it.npcId }, "one spawn per tile")

        s.apply(5, 5)
        s.apply(2, 2, secondary = true)
        assertEquals(listOf(5 to 5), s.draft!!.npcSpawns.map { it.spawnX to it.spawnY })
        assertEquals(listOf(0), s.draft!!.npcSpawns.map { it.slotIndex }, "slots stay numbered from zero")
    }

    // ---- history ----

    @Test
    fun `undo and redo step through single edits`() {
        val s = session()
        s.selectTile(1, 1, 0); s.apply(0, 0)
        s.selectTile(1, 2, 0); s.apply(1, 0)

        assertTrue(s.undo())
        assertNull(s.tileAt(1, 0))
        assertEquals(1, s.tileAt(0, 0)!!.srcTileX)
        assertTrue(s.undo())
        assertNull(s.tileAt(0, 0))
        assertFalse(s.undo(), "nothing left to undo")

        assertTrue(s.redo())
        assertTrue(s.redo())
        assertEquals(2, s.tileAt(1, 0)!!.srcTileX)
        assertFalse(s.redo())
    }

    @Test
    fun `a drag is one undo step`() {
        val s = session()
        s.selectTile(1, 3, 3)

        s.beginStroke()
        for (x in 0 until 6) s.apply(x, 0)
        s.endStroke()

        assertEquals(1, s.undoDepth)
        s.undo()
        assertTrue((0 until 6).all { s.tileAt(it, 0) == null }, "the whole drag is undone together")
    }

    @Test
    fun `a new edit after undo discards the redo history`() {
        val s = session()
        s.selectTile(1, 1, 0); s.apply(0, 0)
        s.undo()
        assertTrue(s.canRedo)
        s.apply(1, 1)
        assertFalse(s.canRedo)
    }

    @Test
    fun `history is limited and drops the oldest steps`() {
        val s = MapEditSession(maxHistory = 3).also { it.load(MapDefinition(id = 1, name = "T")) }
        s.selectTile(1, 1, 0)
        for (x in 0 until 6) s.apply(x, 0)

        assertEquals(3, s.undoDepth)
        repeat(3) { s.undo() }
        assertFalse(s.canUndo)
        assertEquals(1, s.tileAt(0, 0)!!.srcTileX, "the three oldest edits could no longer be undone")
    }

    @Test
    fun `dirty tracking follows the saved version`() {
        val s = session()
        assertFalse(s.isDirty)
        s.selectTile(1, 1, 0); s.apply(0, 0)
        assertTrue(s.isDirty)
        s.markSaved()
        assertFalse(s.isDirty)
        s.undo()
        assertTrue(s.isDirty, "undoing past the saved point makes it differ again")
        s.redo()
        assertFalse(s.isDirty)
    }

    @Test
    fun `loading a map clears history and counts as saved`() {
        val s = session()
        s.selectTile(1, 1, 0); s.apply(0, 0)
        s.load(MapDefinition(id = 2, name = "Other"))
        assertFalse(s.canUndo)
        assertFalse(s.isDirty)
        assertEquals(2, s.draft!!.id)
    }

    @Test
    fun `property changes are undoable`() {
        val s = session()
        s.updateProperties { it.copy(name = "Renamed") }
        assertEquals("Renamed", s.draft!!.name)
        s.undo()
        assertEquals("Test", s.draft!!.name)
    }

    @Test
    fun `every change is reported to the host including undo`() {
        val s = session()
        val seen = mutableListOf<String>()
        s.onDraftChanged = { seen += it.name }
        s.updateProperties { it.copy(name = "A") }
        s.updateProperties { it.copy(name = "B") }
        s.undo()
        assertEquals(listOf("A", "B", "A"), seen)
    }

    @Test
    fun `painting never bumps the revision so only an explicit save does`() {
        val s = session()
        val revision = s.draft!!.revision
        s.selectTile(1, 1, 0)
        s.apply(0, 0)
        assertEquals(revision, s.draft!!.revision)
    }

    @Test
    fun `layers can be hidden and shown again without touching the map`() {
        val s = session()
        val before = s.draft
        assertTrue(s.isLayerVisible(MapLayer.FRINGE_1))
        assertFalse(s.toggleLayerVisible(MapLayer.FRINGE_1))
        assertFalse(s.isLayerVisible(MapLayer.FRINGE_1))
        assertTrue(s.isLayerVisible(MapLayer.GROUND))
        assertTrue(s.toggleLayerVisible(MapLayer.FRINGE_1))
        assertEquals(before, s.draft)
        assertFalse(s.isDirty)
    }

    @Test
    fun `a rectangle drag fills the box when released as one undo step`() {
        val s = session()
        s.tool = MapEditorTool.RECTANGLE
        s.selectTile(1, 2, 0)
        s.beginStroke()
        s.beginRect(1, 1)
        assertEquals(1, s.rectAnchor!!.x)
        assertTrue(s.finishRect(3, 2))
        s.endStroke()
        assertNull(s.rectAnchor)
        assertEquals(2, s.tileAt(3, 2)!!.srcTileX)
        assertNull(s.tileAt(4, 2))
        assertEquals(1, s.undoDepth)
    }

    @Test
    fun `a rectangle drag with the secondary button clears and cancelling changes nothing`() {
        val s = session()
        s.selectTile(1, 2, 0)
        s.fillRect(0, 0, 4, 4)
        s.beginRect(1, 1, erase = true)
        assertTrue(s.rectIsErase)
        s.finishRect(2, 2)
        assertNull(s.tileAt(1, 1))
        assertEquals(2, s.tileAt(3, 3)!!.srcTileX)
        val depth = s.undoDepth
        s.beginRect(0, 0)
        s.cancelRect()
        assertNull(s.rectAnchor)
        assertFalse(s.finishRect(4, 4))
        assertEquals(depth, s.undoDepth)
    }

    @Test
    fun `a rectangle cannot start outside the map`() {
        val s = session()
        s.beginRect(-1, 3)
        assertNull(s.rectAnchor)
    }

    @Test
    fun `the event tool forwards left and right clicks without modifying or erasing the map`() {
        val s = session()
        s.selectTile(1, 2, 0)
        s.apply(2, 2)
        s.markSaved()
        val clicks = mutableListOf<List<Any>>()
        s.onEventTileClick = { mapId, x, y, secondary -> clicks += listOf(mapId, x, y, secondary) }

        s.tool = MapEditorTool.EVENT
        assertFalse(s.apply(2, 2, secondary = false))
        assertFalse(s.apply(2, 2, secondary = true))
        assertFalse(s.apply(-1, 2, secondary = false))

        assertEquals(listOf(listOf(1, 2, 2, false), listOf(1, 2, 2, true)), clicks)
        assertFalse(s.isDirty)
        assertEquals(2, s.tileAt(2, 2)!!.srcTileX)
    }

    @Test
    fun `clearing a layer empties it across the map in one undo step and leaves the rest`() {
        val session = MapEditSession()
        val cells = List(5 * 5) { i ->
            com.morgana.domain.map.MapCell(
                layers = mapOf(
                    com.morgana.domain.map.MapLayer.GROUND to com.morgana.domain.map.TilePlacement(srcTileX = 1),
                    com.morgana.domain.map.MapLayer.MASK_1 to com.morgana.domain.map.TilePlacement(srcTileX = 2)
                ).filterKeys { it == com.morgana.domain.map.MapLayer.GROUND || i % 2 == 0 },
                attribute = if (i == 5) com.morgana.domain.map.TileAttribute.Blocked else com.morgana.domain.map.TileAttribute.None
            )
        }
        session.load(com.morgana.domain.map.MapDefinition(id = 9, name = "Clear me", width = 5, height = 5, cells = cells))
        assertEquals(13, session.tilesOn(com.morgana.domain.map.MapLayer.MASK_1))

        assertTrue(session.clearLayer(com.morgana.domain.map.MapLayer.MASK_1))
        val cleared = session.draft!!
        assertEquals(0, session.tilesOn(com.morgana.domain.map.MapLayer.MASK_1))
        assertEquals(25, cleared.cells.count { com.morgana.domain.map.MapLayer.GROUND in it.layers }, "other layers stay")
        assertEquals(com.morgana.domain.map.TileAttribute.Blocked, cleared.cells[5].attribute, "attributes stay")
        assertEquals(1, session.undoDepth)
        assertTrue(session.isDirty)

        assertFalse(session.clearLayer(com.morgana.domain.map.MapLayer.MASK_1), "an empty layer is not an edit")
        assertEquals(1, session.undoDepth)

        session.undo()
        assertEquals(13, session.tilesOn(com.morgana.domain.map.MapLayer.MASK_1))
        session.layer = com.morgana.domain.map.MapLayer.GROUND
        assertTrue(session.clearLayer(), "defaults to the current layer")
        assertEquals(0, session.tilesOn(com.morgana.domain.map.MapLayer.GROUND))
        assertEquals(13, session.tilesOn(com.morgana.domain.map.MapLayer.MASK_1))
    }

    @Test
    fun `an animated brush paints animated tiles whose frames step by the stamp width`() {
        val s = session()
        s.selectStamp(2, 4, 1, 2, 1)
        s.animationFrames = 3
        s.animationSpeedMs = 150
        s.paintAt(0, 0)
        val left = s.draft!!.getCell(0, 0)!!.layers.getValue(MapLayer.GROUND)
        val right = s.draft!!.getCell(1, 0)!!.layers.getValue(MapLayer.GROUND)
        assertEquals(listOf(3, 150, 2), listOf(left.animationFrames, left.animationSpeedMs, left.animationFrameStep))
        assertEquals(5, right.srcTileX)
        assertEquals(9, com.morgana.domain.map.TileAnimation.lastFrameColumn(right), "the right half's last frame is two widths on")

        s.animationFrames = 1
        s.paintAt(3, 3)
        val still = s.draft!!.getCell(3, 3)!!.layers.getValue(MapLayer.GROUND)
        assertEquals(listOf(1, 1), listOf(still.animationFrames, still.animationFrameStep))
        assertFalse(com.morgana.domain.map.TileAnimation.isAnimated(still))
    }

    @Test
    fun `the eyedropper picks up a tile's animation and the brush settings stay in range`() {
        val s = session()
        s.selectTile(2, 6, 0)
        s.animationFrames = 4
        s.animationSpeedMs = 400
        s.paintAt(2, 2)
        s.animationFrames = 1
        s.animationSpeedMs = 100
        s.tool = MapEditorTool.EYEDROPPER
        s.apply(2, 2)
        assertEquals(4, s.animationFrames)
        assertEquals(400, s.animationSpeedMs)
        assertEquals(MapEditorTool.BRUSH, s.tool)

        s.animationFrames = 0
        assertEquals(1, s.animationFrames)
        s.animationFrames = 1000
        assertEquals(com.morgana.domain.map.TileAnimation.MAX_FRAMES, s.animationFrames)
        s.animationSpeedMs = 1
        assertEquals(com.morgana.domain.map.TileAnimation.MIN_SPEED_MS, s.animationSpeedMs)
    }

    @Test
    fun `the built-in water tile keeps its own animation whatever the brush says`() {
        val s = session()
        s.selectTile(1, 3, 0)
        s.animationFrames = 5
        s.paintAt(1, 1)
        val water = s.draft!!.getCell(1, 1)!!.layers.getValue(MapLayer.GROUND)
        assertEquals(AutotileMode.ANIMATED_3_FRAME, water.autotileMode)
        assertEquals(3, water.animationFrames)
    }
}
