package com.morgana.client.editor

import com.badlogic.gdx.Input
import com.morgana.client.state.MapEditorTool
import com.morgana.domain.map.MapLayer

/** What a keyboard shortcut asks the map editor to do. */
sealed interface EditorCommand {
    data class SelectTool(val tool: MapEditorTool) : EditorCommand
    data object Undo : EditorCommand
    data object Redo : EditorCommand
    data object Save : EditorCommand
    data object ToggleGrid : EditorCommand
    data object ToggleDimLayers : EditorCommand
    data object TogglePanels : EditorCommand
    /** Zoom the map in (+1) or out (-1) by one editor level. */
    data class Zoom(val delta: Int) : EditorCommand
}

/**
 * The map editor's keyboard shortcuts as a pure table, so the keys are unit-tested and the game only forwards key presses.
 *
 * | Key | Action |
 * |---|---|
 * | B / R / G / E / I / N / V | brush, rectangle, bucket, eraser, eyedropper, NPC spawn, event |
 * | Ctrl+Z, Ctrl+Y or Ctrl+Shift+Z | undo, redo |
 * | Ctrl+S | save the map |
 * | H | show or hide the grid |
 * | D | dim the layers you are not painting on |
 * | P | find and open editors, and save or load layouts |
 * | - / = | zoom the map out / in |
 *
 * Shortcuts never fire while the user types in a text field ([typing]) and plain keys never fire with Ctrl, Alt or Shift held,
 * so they cannot clash with other bindings.
 */
object EditorShortcuts {

    fun resolve(keycode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean, typing: Boolean): EditorCommand? {
        if (typing || alt) return null
        if (ctrl) {
            return when (keycode) {
                Input.Keys.Z -> if (shift) EditorCommand.Redo else EditorCommand.Undo
                Input.Keys.Y -> if (shift) null else EditorCommand.Redo
                Input.Keys.S -> if (shift) null else EditorCommand.Save
                else -> null
            }
        }
        if (shift) return null
        return when (keycode) {
            Input.Keys.B -> EditorCommand.SelectTool(MapEditorTool.BRUSH)
            Input.Keys.R -> EditorCommand.SelectTool(MapEditorTool.RECTANGLE)
            Input.Keys.G -> EditorCommand.SelectTool(MapEditorTool.FLOOD_FILL)
            Input.Keys.E -> EditorCommand.SelectTool(MapEditorTool.ERASER)
            Input.Keys.I -> EditorCommand.SelectTool(MapEditorTool.EYEDROPPER)
            Input.Keys.N -> EditorCommand.SelectTool(MapEditorTool.NPC_SPAWN)
            Input.Keys.V -> EditorCommand.SelectTool(MapEditorTool.EVENT)
            Input.Keys.H -> EditorCommand.ToggleGrid
            Input.Keys.D -> EditorCommand.ToggleDimLayers
            Input.Keys.P -> EditorCommand.TogglePanels
            Input.Keys.MINUS -> EditorCommand.Zoom(-1)
            Input.Keys.EQUALS -> EditorCommand.Zoom(+1)
            else -> null
        }
    }

    /** How opaque [layer] is drawn while editing: layers other than the one being painted fade when dimming is on. */
    fun layerAlpha(layer: MapLayer, activeLayer: MapLayer, dimOthers: Boolean): Float =
        if (dimOthers && layer != activeLayer) DIMMED_ALPHA else 1f

    const val DIMMED_ALPHA = 0.3f
}
