package com.morgana.client.editor

import com.badlogic.gdx.Input
import com.morgana.client.state.MapEditorTool
import com.morgana.domain.map.MapLayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EditorShortcutsTest {

    private fun plain(key: Int) = EditorShortcuts.resolve(key, ctrl = false, shift = false, alt = false, typing = false)
    private fun ctrl(key: Int, shift: Boolean = false) = EditorShortcuts.resolve(key, ctrl = true, shift = shift, alt = false, typing = false)

    @Test
    fun `tool keys select the matching tool`() {
        assertEquals(EditorCommand.SelectTool(MapEditorTool.BRUSH), plain(Input.Keys.B))
        assertEquals(EditorCommand.SelectTool(MapEditorTool.RECTANGLE), plain(Input.Keys.R))
        assertEquals(EditorCommand.SelectTool(MapEditorTool.FLOOD_FILL), plain(Input.Keys.G))
        assertEquals(EditorCommand.SelectTool(MapEditorTool.ERASER), plain(Input.Keys.E))
        assertEquals(EditorCommand.SelectTool(MapEditorTool.EYEDROPPER), plain(Input.Keys.I))
        assertEquals(EditorCommand.SelectTool(MapEditorTool.NPC_SPAWN), plain(Input.Keys.N))
        assertEquals(EditorCommand.SelectTool(MapEditorTool.EVENT), plain(Input.Keys.V))
    }

    @Test
    fun `every tool has exactly one key`() {
        val keys = listOf(Input.Keys.B, Input.Keys.R, Input.Keys.G, Input.Keys.E, Input.Keys.I, Input.Keys.N, Input.Keys.V)
        val tools = keys.map { (plain(it) as EditorCommand.SelectTool).tool }
        assertEquals(MapEditorTool.entries.toSet(), tools.toSet())
    }

    @Test
    fun `view keys toggle the grid and layer dimming`() {
        assertEquals(EditorCommand.ToggleGrid, plain(Input.Keys.H))
        assertEquals(EditorCommand.ToggleDimLayers, plain(Input.Keys.D))
    }

    @Test
    fun `P opens the panel finder`() {
        assertEquals(EditorCommand.TogglePanels, plain(Input.Keys.P))
        assertEquals(EditorCommand.Zoom(-1), plain(Input.Keys.MINUS))
        assertEquals(EditorCommand.Zoom(+1), plain(Input.Keys.EQUALS))
        assertNull(ctrl(Input.Keys.P))
    }

    @Test
    fun `control shortcuts undo redo and save`() {
        assertEquals(EditorCommand.Undo, ctrl(Input.Keys.Z))
        assertEquals(EditorCommand.Redo, ctrl(Input.Keys.Y))
        assertEquals(EditorCommand.Redo, ctrl(Input.Keys.Z, shift = true))
        assertEquals(EditorCommand.Save, ctrl(Input.Keys.S))
        assertNull(ctrl(Input.Keys.S, shift = true))
        assertNull(ctrl(Input.Keys.Y, shift = true))
    }

    @Test
    fun `tool keys do nothing with modifiers held`() {
        assertNull(ctrl(Input.Keys.B))
        assertNull(EditorShortcuts.resolve(Input.Keys.B, ctrl = false, shift = true, alt = false, typing = false))
        assertNull(EditorShortcuts.resolve(Input.Keys.B, ctrl = false, shift = false, alt = true, typing = false))
        assertNull(EditorShortcuts.resolve(Input.Keys.S, ctrl = true, shift = false, alt = true, typing = false))
    }

    @Test
    fun `nothing fires while typing in a text field`() {
        for (key in listOf(Input.Keys.B, Input.Keys.E, Input.Keys.H, Input.Keys.D)) {
            assertNull(EditorShortcuts.resolve(key, ctrl = false, shift = false, alt = false, typing = true))
        }
        assertNull(EditorShortcuts.resolve(Input.Keys.Z, ctrl = true, shift = false, alt = false, typing = true))
        assertNull(EditorShortcuts.resolve(Input.Keys.S, ctrl = true, shift = false, alt = false, typing = true))
    }

    @Test
    fun `unbound keys and movement keys are ignored`() {
        for (key in listOf(Input.Keys.W, Input.Keys.A, Input.Keys.S, Input.Keys.Q, Input.Keys.SPACE, Input.Keys.NUM_1)) assertNull(plain(key))
    }

    @Test
    fun `dimming fades every layer except the active one`() {
        assertEquals(1f, EditorShortcuts.layerAlpha(MapLayer.GROUND, MapLayer.GROUND, dimOthers = true))
        assertEquals(EditorShortcuts.DIMMED_ALPHA, EditorShortcuts.layerAlpha(MapLayer.FRINGE_1, MapLayer.GROUND, dimOthers = true))
        assertEquals(1f, EditorShortcuts.layerAlpha(MapLayer.FRINGE_1, MapLayer.GROUND, dimOthers = false))
    }
}
