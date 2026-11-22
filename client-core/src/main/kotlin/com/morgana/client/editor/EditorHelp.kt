package com.morgana.client.editor

import com.badlogic.gdx.Input

/** One heading and its paragraphs, as the Help panel shows them. */
data class HelpSection(val title: String, val paragraphs: List<String>)

/** One keyboard shortcut for the Help panel: the keys as written, what they do, and the key code that triggers it (checked by a test). */
data class HelpShortcut(val keys: String, val does: String, val keycode: Int, val ctrl: Boolean = false, val shift: Boolean = false)

/**
 * The text of the editor's Help panel, kept as plain data so it can be tested: every shortcut listed here must really do
 * something in [EditorShortcuts], and the panel names listed must exist.
 */
object EditorHelp {

    val sections: List<HelpSection> = listOf(
        HelpSection("What this is", listOf(
            "The editor changes the running game. Paint on the map, then edit items, creatures, spells, shops and quests, all while standing in the world. Press F1 or the Close editor button to go back to playing."
        )),
        HelpSection("The first steps", listOf(
            "1. Pick tiles in the Tileset editor on the left. Drag across several tiles to paint them as one stamp.",
            "2. Choose a layer in the Layers editor. Ground is the floor; layers marked above are drawn over characters. Clear, under the layers, empties the selected layer across the whole map (click twice; Ctrl+Z undoes it).",
            "3. Click or drag on the map to paint. Right-click erases. The status bar at the bottom says what the current tool does.",
            "4. Press Ctrl+S, or the disk button, to save the map for everyone in the realm.",
            "To animate tiles (water, torches, flowers), draw the frames side by side on the tileset, pick the first one, and raise Anim above the tiles to the number of frames. Painted tiles then cycle through the tiles to their right."
        )),
        HelpSection("Finding things", listOf(
            "The map editors (Tileset, Layers, Attributes, Maps and Map properties) are open at first. Click Editors (or press P) to search for any other editor by name or by what it does, and press Enter to open the first match. Drag the Editors window by its title to move it out of the way.",
            "An editor you minimize drops to the Minimized bar along the bottom. Click its name there to bring it back.",
            "Drag an editor's title bar to move it: let go in the middle of the screen to float it, or near an edge to dock it there. Drag the inner edge of a docked column to make it wider or narrower, the line between two docked editors to share the height differently, and the corner of a floating editor to resize it."
        )),
        HelpSection("Saving", listOf(
            "Map edits are saved with Ctrl+S; the toolbar says unsaved changes until you do. Content such as items, creatures and quests has its own Save button under each form.",
            "If the server refuses a save, the form says why. Nothing you typed is lost, so fix it and save again."
        )),
        HelpSection("Layouts", listOf(
            "A layout is where your editors are. Open Editors and pick Minimal, Map editing, Content editing or Everything for a ready-made one, or type a name and press Save current layout to keep your own. Layouts are remembered in this browser or on this computer."
        ))
    )

    val shortcuts: List<HelpShortcut> = listOf(
        HelpShortcut("B", "Brush", Input.Keys.B),
        HelpShortcut("R", "Rectangle", Input.Keys.R),
        HelpShortcut("G", "Bucket fill", Input.Keys.G),
        HelpShortcut("E", "Eraser", Input.Keys.E),
        HelpShortcut("I", "Pick a tile from the map (eyedropper)", Input.Keys.I),
        HelpShortcut("N", "Place NPC spawn points", Input.Keys.N),
        HelpShortcut("V", "Place, move or select events", Input.Keys.V),
        HelpShortcut("H", "Show or hide the tile grid", Input.Keys.H),
        HelpShortcut("D", "Dim the layers you are not painting on", Input.Keys.D),
        HelpShortcut("P", "Find and open editors", Input.Keys.P),
        HelpShortcut("-", "Zoom the map out", Input.Keys.MINUS),
        HelpShortcut("=", "Zoom the map in", Input.Keys.EQUALS),
        HelpShortcut("Ctrl+Z", "Undo", Input.Keys.Z, ctrl = true),
        HelpShortcut("Ctrl+Y", "Redo", Input.Keys.Y, ctrl = true),
        HelpShortcut("Ctrl+S", "Save the map", Input.Keys.S, ctrl = true),
        HelpShortcut("Esc", "Close the Editors finder", Input.Keys.ESCAPE)
    )
}
