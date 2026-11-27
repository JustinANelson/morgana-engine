package com.morgana.client.ui.editor

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import com.morgana.client.editor.MapPropertiesForm
import com.morgana.client.editor.MapPropertiesValues
import com.morgana.client.state.ClientWorldState
import com.morgana.domain.map.MapMoral

/**
 * The map properties panel: name, rules (moral), music, weather, light, indoors, the four neighbouring maps (shown by name) and
 * the map's size. Changes are applied to the draft with one button, as one undo step, and the map on screen updates at once
 * (light and weather are drawn from the draft). The fields follow the draft, so undo, or switching maps, refreshes them.
 */
class MapPropertiesPanel(stage: Stage, private val theme: EditorTheme, private val worldState: ClientWorldState) {

    val content: Actor

    private val editor = worldState.editor

    private val nameField = VisTextField()
    private val moralBox = VisSelectBox<String>()
    private val bgmField = VisTextField()
    private val zoneBox = VisSelectBox<String>()
    private val zoneNote = theme.muted("")
    private val interiorCheck = VisCheckBox("Interior (no weather, no day/night)")
    private val instancedCheck = VisCheckBox("Instanced (private copy per party)")
    private val lightField = VisTextField()
    private val borderFields = linkedMapOf("North" to VisTextField(), "South" to VisTextField(), "East" to VisTextField(), "West" to VisTextField())
    private val borderNames = linkedMapOf<String, VisLabel>()
    private val worldXField = VisTextField()
    private val worldYField = VisTextField()
    private val widthField = VisTextField()
    private val heightField = VisTextField()

    private val problems = VisLabel("", theme.warningLabel)
    private val title = theme.muted("")
    private val apply: VisTextButton
    private val resize: VisTextButton

    /** What the draft looked like when the fields were last filled; a different draft means the fields should reload. */
    private var loadedFrom: MapPropertiesValues? = null
    private var loadedMapId = -1
    private var loadedSize = 0 to 0

    init {
        moralBox.setItems(*MapMoral.entries.map { it.displayName }.toTypedArray())
        zoneNote.setWrap(true)
        problems.setWrap(true)

        apply = theme.textButton("Apply changes") { applyChanges() }
        resize = theme.textButton("Resize map") { applyResize() }

        val root = LiveTable { follow() }
        root.defaults().left().growX().pad(2f)
        root.add(title).row()
        root.add(theme.muted("Name")).row()
        root.add(nameField).growX().row()
        root.add(theme.muted("Rules")).row()
        root.add(moralBox).growX().row()
        root.add(theme.muted("Music track")).row()
        root.add(bgmField).growX().row()
        root.add(theme.muted("Ambient light (#RRGGBB)")).row()
        root.add(lightField).growX().row()

        root.add(theme.heading("Weather and setting")).growX().padTop(6f).row()
        root.add(interiorCheck).left().fill(false, false).row()
        root.add(theme.muted("Weather zone")).row()
        root.add(zoneBox).growX().row()
        root.add(zoneNote).growX().minWidth(0f).row()
        root.add(instancedCheck).left().fill(false, false).row()

        root.add(theme.heading("Neighbouring maps")).growX().padTop(6f).row()
        root.add(theme.muted("Map id on each side, 0 for none.")).growX().minWidth(0f).row()
        for ((side, field) in borderFields) {
            val name = theme.muted("")
            borderNames[side] = name
            val row = VisTable()
            row.add(theme.text(side)).width(48f).left()
            row.add(field).width(54f)
            row.add(name).growX().minWidth(0f).padLeft(6f).left()
            root.add(row).growX().row()
        }

        root.add(theme.heading("World position")).growX().padTop(6f).row()
        val worldNote = theme.muted("Seamless worlds: the tile this map's top left corner holds in the shared world grid. Leave blank for a map that is not part of it.")
        worldNote.setWrap(true)
        root.add(worldNote).growX().minWidth(0f).row()
        val worldRow = VisTable()
        worldRow.add(theme.text("X")).padRight(4f)
        worldRow.add(worldXField).width(64f)
        worldRow.add(theme.text("Y")).padLeft(10f).padRight(4f)
        worldRow.add(worldYField).width(64f)
        root.add(worldRow).left().fill(false, false).row()

        root.add(theme.heading("Size")).growX().padTop(8f).row()
        val sizeRow = VisTable()
        sizeRow.add(theme.text("W")).padRight(4f)
        sizeRow.add(widthField).width(54f)
        sizeRow.add(theme.text("H")).padLeft(10f).padRight(4f)
        sizeRow.add(heightField).width(54f)
        root.add(sizeRow).left().fill(false, false).row()
        val note = theme.muted("Keeps tiles from the top-left corner; new space is empty and spawns outside are dropped.")
        note.setWrap(true)
        root.add(note).growX().minWidth(0f).row()
        root.add(resize).growX().row()
        root.add().growY()

        val pane = VisScrollPane(root)
        pane.setScrollingDisabled(true, false)
        pane.setFadeScrollBars(false)
        pane.setOverscroll(false, false)
        giveScrollFocusOnHover(stage, pane)
        // The problem list and the Apply button stay visible below the scrolling form.
        val whole = VisTable()
        whole.add(pane).grow().row()
        whole.add(problems).growX().minWidth(0f).pad(2f).row()
        whole.add(apply).growX().pad(2f).row()
        content = whole

        // Typing refreshes the problem list and the neighbour names, nothing else (the draft changes on Apply).
        val refresh = object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { refreshHints() }
        }
        (listOf(nameField, bgmField, lightField, widthField, heightField, worldXField, worldYField) + borderFields.values).forEach { it.addListener(refresh) }
        moralBox.addListener(refresh)
        zoneBox.addListener(refresh)
        interiorCheck.addListener(refresh)
        instancedCheck.addListener(refresh)
    }

    // ---- draft <-> fields ----

    private fun follow() {
        val draft = editor.draft
        if (draft == null) {
            title.setText("No map loaded")
            apply.isDisabled = true
            resize.isDisabled = true
            return
        }
        val current = MapPropertiesForm.valuesOf(draft)
        if (draft.id != loadedMapId || current != loadedFrom || (draft.width to draft.height) != loadedSize) {
            // Reload only when the map itself changed (switched map, undo, redo, apply); typing alone never reaches here.
            load(draft.id, current, draft.width to draft.height)
        }
        refreshNames()
    }

    private fun load(mapId: Int, v: MapPropertiesValues, size: Pair<Int, Int>) {
        loadedMapId = mapId
        loadedFrom = v
        loadedSize = size
        title.setText("Map $mapId")
        nameField.text = v.name
        moralBox.selected = v.moral.displayName
        bgmField.text = v.bgm
        lightField.text = v.light
        interiorCheck.isChecked = v.interior
        instancedCheck.isChecked = v.instanced
        loadZones(v.zone)
        borderFields.getValue("North").text = v.north
        borderFields.getValue("South").text = v.south
        borderFields.getValue("East").text = v.east
        borderFields.getValue("West").text = v.west
        worldXField.text = v.worldX
        worldYField.text = v.worldY
        widthField.text = size.first.toString()
        heightField.text = size.second.toString()
        refreshHints()
    }

    private fun typed(): MapPropertiesValues = MapPropertiesValues(
        name = nameField.text,
        moral = MapMoral.entries.firstOrNull { it.displayName == moralBox.selected } ?: MapMoral.SAFE_ZONE,
        bgm = bgmField.text,
        light = lightField.text,
        interior = interiorCheck.isChecked,
        zone = zoneBox.selected?.substringBefore(":")?.trim() ?: loadedFrom?.zone ?: "0",
        instanced = instancedCheck.isChecked,
        north = borderFields.getValue("North").text,
        south = borderFields.getValue("South").text,
        east = borderFields.getValue("East").text,
        west = borderFields.getValue("West").text,
        worldX = worldXField.text,
        worldY = worldYField.text
    )

    private fun knownIds(): Set<Int> = worldState.catalog.maps.map { it.id }.toSet()

    /** The weather zones the game defines, or null when it defines none (then each map keeps its own legacy weather). */
    private fun zones() = worldState.gameDefinition?.weather?.zones

    private fun knownZoneIds(): Set<Int>? = zones()?.map { it.id }?.toSet()

    /** Fills the zone list from the game's weather zones (zone 0 is always offered) and selects [current]. */
    private fun loadZones(current: String) {
        val zones = zones()
        if (zones == null) {
            zoneBox.setItems("0: (this game has no weather zones)")
            zoneBox.isDisabled = true
            zoneNote.setText("Weather is set per game in game.json (\"weather\" zones); this game has none, so each map keeps its own fixed weather.")
            return
        }
        zoneBox.isDisabled = false
        val items = (listOf("0: World") .filter { zones.none { z -> z.id == 0 } } + zones.map { "${it.id}: ${it.name}" })
        zoneBox.setItems(*items.toTypedArray())
        zoneBox.selected = items.firstOrNull { it.substringBefore(":") == current } ?: items.first()
        zoneNote.setText("Interior maps ignore the zone's weather.")
    }

    private fun refreshHints() {
        val draft = editor.draft ?: return
        val list = MapPropertiesForm.problems(draft.id, typed(), knownIds(), knownZoneIds())
        val sizeProblem = MapPropertiesForm.sizeProblem(widthField.text, heightField.text)
        problems.setText((list + listOfNotNull(sizeProblem)).joinToString("\n"))
        apply.isDisabled = list.isNotEmpty() || typed() == loadedFrom
        resize.isDisabled = sizeProblem != null || (widthField.text.trim() == draft.width.toString() && heightField.text.trim() == draft.height.toString())
        refreshNames()
    }

    private fun refreshNames() {
        val known = worldState.catalog.maps.associateBy { it.id }
        for ((side, field) in borderFields) {
            val id = field.text.trim().toIntOrNull()
            borderNames.getValue(side).setText(
                when {
                    id == null || id == 0 -> ""
                    else -> known[id]?.name?.let { "= $it" } ?: if (known.isEmpty()) "" else "(no such map)"
                }
            )
        }
    }

    // ---- actions ----

    private fun applyChanges() {
        val draft = editor.draft ?: return
        if (MapPropertiesForm.problems(draft.id, typed(), knownIds(), knownZoneIds()).isNotEmpty()) return
        val values = typed()
        editor.updateProperties { MapPropertiesForm.apply(it, values) }
        stageUnfocus()
    }

    private fun applyResize() {
        if (MapPropertiesForm.sizeProblem(widthField.text, heightField.text) != null) return
        editor.resize(widthField.text.trim().toInt(), heightField.text.trim().toInt())
        stageUnfocus()
    }

    private fun stageUnfocus() {
        content.stage?.keyboardFocus = null
    }

}
