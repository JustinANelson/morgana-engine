package com.morgana.client.ui.editor

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextField
import com.morgana.client.editor.AttributeField
import com.morgana.client.editor.AttributeForm
import com.morgana.client.editor.AttributeKind
import com.morgana.client.editor.ContentNames
import com.morgana.client.editor.FieldRef
import com.morgana.client.editor.FieldType
import com.morgana.client.state.ClientWorldState
import com.morgana.client.state.MapEditorTool
import com.morgana.domain.map.VitalType
import com.morgana.domain.spatial.Direction

/**
 * The attribute panel: choose what a tile does (blocked, warp, shop...) and fill in typed fields for it instead of raw
 * "Param 1/2/3" boxes. The chosen attribute is painted along with tile graphics, or on its own ("attribute only"). The
 * form follows the editor: when the eyedropper copies a tile's attribute, the form shows it. Each id field shows the name of
 * the item, shop or resource it points at, and the panel lists anything that is wrong with the numbers.
 */
class AttributePanel(stage: com.badlogic.gdx.scenes.scene2d.Stage, private val theme: EditorTheme, private val worldState: ClientWorldState) {

    val content: Actor

    private val editor = worldState.editor
    private val typeBox = VisSelectBox<String>()
    private val fieldsTable = VisTable()
    private val problemLabel = VisLabel("", theme.warningLabel)
    private val hintLabel = theme.muted("")
    private val attributeOnly = VisCheckBox("Attribute only")
    private val overlay = VisCheckBox("Show on map")

    private var kind: AttributeKind = AttributeKind.NONE
    private var values: MutableMap<String, String> = linkedMapOf()

    /** The attribute this panel last handed to the editor; a different one in the editor means something else changed it. */
    private var lastPushed = editor.attribute

    // The field widgets for the current kind, so a change to the editor can refresh them without rebuilding.
    private val nameLabels = linkedMapOf<String, VisLabel>()

    init {
        typeBox.setItems(*AttributeKind.entries.map { it.label }.toTypedArray())
        typeBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                val chosen = AttributeKind.entries.first { it.label == typeBox.selected }
                if (chosen != kind) {
                    kind = chosen
                    values = AttributeForm.defaults(chosen).toMutableMap()
                    rebuildFields()
                    push()
                }
            }
        })
        attributeOnly.isChecked = editor.attributeOnly
        attributeOnly.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { editor.attributeOnly = attributeOnly.isChecked }
        })
        overlay.isChecked = worldState.showAttributeOverlay
        overlay.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { worldState.showAttributeOverlay = overlay.isChecked }
        })

        val root = LiveTable { followEditor() }
        root.defaults().left().growX().pad(2f)
        root.add(theme.text("What this tile does")).row()
        root.add(typeBox).growX().row()
        root.add(hintLabel).growX().minWidth(0f).row()
        fieldsTable.defaults().left().growX().padBottom(3f)
        root.add(fieldsTable).growX().row()
        problemLabel.setWrap(true)
        root.add(problemLabel).growX().minWidth(0f).row()
        root.add(attributeOnly).left().fill(false, false).row()
        root.add(overlay).left().fill(false, false).row()
        root.add(wrapped("Left-click paints, right-click erases.")).growX().minWidth(0f).row()
        root.add(theme.heading("NPC spawn")).growX().padTop(6f).row()
        root.add(buildSpawnPicker()).growX().row()
        root.add().growY()
        // Long forms (a heal trap has five fields) scroll instead of spilling over the panel below.
        val pane = com.kotcrab.vis.ui.widget.VisScrollPane(root)
        pane.setScrollingDisabled(true, false)
        pane.setFadeScrollBars(false)
        pane.setOverscroll(false, false)
        giveScrollFocusOnHover(stage, pane)
        content = pane

        loadFrom(editor.attribute)
    }

    private fun wrapped(text: String): VisLabel = theme.muted(text).also { it.setWrap(true) }

    // ---- editor <-> form ----

    private fun followEditor() {
        if (editor.attribute != lastPushed) {
            lastPushed = editor.attribute
            loadFrom(editor.attribute)
        }
        overlay.isChecked = worldState.showAttributeOverlay
        attributeOnly.isChecked = editor.attributeOnly
    }

    private fun loadFrom(attribute: com.morgana.domain.map.TileAttribute) {
        kind = AttributeForm.kindOf(attribute)
        values = (AttributeForm.defaults(kind) + AttributeForm.valuesOf(attribute)).toMutableMap()
        typeBox.selected = kind.label
        rebuildFields()
    }

    /** Hands the form's attribute to the editor (bad numbers fall back to defaults; the panel says what is wrong). */
    private fun push() {
        val built = AttributeForm.build(kind, values)
        lastPushed = built
        editor.attribute = built
        updateProblems()
    }

    private fun updateProblems() {
        val problems = AttributeForm.problems(kind, values)
        problemLabel.setText(problems.joinToString("\n"))
        for ((key, label) in nameLabels) {
            val field = kind.fields.first { it.key == key }
            label.setText(nameFor(field, values[key] ?: field.default))
        }
    }

    // ---- widgets ----

    private fun rebuildFields() {
        fieldsTable.clearChildren()
        nameLabels.clear()
        hintLabel.setText(kind.hint)
        hintLabel.setWrap(true)
        for (f in kind.fields) {
            when (f.type) {
                FieldType.BOOL -> fieldsTable.add(checkBox(f)).left().fill(false, false).row()
                else -> {
                    fieldsTable.add(theme.muted(f.label)).left().row()
                    fieldsTable.add(input(f)).growX().row()
                    if (f.ref != FieldRef.NONE) {
                        val name = theme.muted(nameFor(f, values[f.key] ?: f.default))
                        nameLabels[f.key] = name
                        fieldsTable.add(name).left().row()
                    }
                }
            }
        }
        updateProblems()
    }

    private fun checkBox(f: AttributeField): VisCheckBox {
        val box = VisCheckBox(f.label)
        box.isChecked = values[f.key] == "true"
        box.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                values[f.key] = box.isChecked.toString()
                push()
            }
        })
        return box
    }

    private fun input(f: AttributeField): Actor = when (f.type) {
        FieldType.DIRECTION, FieldType.OPTIONAL_DIRECTION -> {
            val box = VisSelectBox<String>()
            val none = "Keep facing"
            val names = Direction.entries.map { it.name.lowercase().replace('_', ' ') }
            box.setItems(*(if (f.type == FieldType.OPTIONAL_DIRECTION) listOf(none) + names else names).toTypedArray())
            val current = values[f.key].orEmpty()
            box.selected = if (current.isEmpty()) none else current.lowercase().replace('_', ' ')
            box.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    values[f.key] = if (box.selected == none) "" else box.selected.uppercase().replace(' ', '_')
                    push()
                }
            })
            box
        }
        FieldType.VITAL -> {
            val box = VisSelectBox<String>()
            box.setItems(*VitalType.entries.map { it.name }.toTypedArray())
            box.selected = values[f.key] ?: f.default
            box.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    values[f.key] = box.selected
                    push()
                }
            })
            box
        }
        else -> {
            val field = VisTextField(values[f.key] ?: f.default)
            field.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    values[f.key] = field.text
                    push()
                }
            })
            field
        }
    }

    /** The name of the content an id field points at, or a note when there is no such thing. */
    private fun nameFor(f: AttributeField, raw: String): String {
        val id = raw.trim().toIntOrNull() ?: return ""
        if (f.ref == FieldRef.MAP) {
            val known = worldState.catalog.maps.firstOrNull { it.id == id }
            return if (id == editor.draft?.id) "= this map" else known?.name?.let { "= $it" } ?: "Map $id"
        }
        return ContentNames.hint(worldState.catalog, f.ref, id)
    }

    private fun buildSpawnPicker(): Actor {
        val npcBox = VisSelectBox<String>()
        val items = worldState.catalog.npcs.map { "${it.id}: ${it.name}" }.ifEmpty { listOf("4: Meadow Slime") }
        npcBox.setItems(*items.toTypedArray())
        npcBox.selected = items.firstOrNull { it.substringBefore(":").toIntOrNull() == editor.npcSpawnId } ?: items.first()
        npcBox.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                editor.npcSpawnId = npcBox.selected.substringBefore(":").toIntOrNull() ?: 4
                editor.tool = MapEditorTool.NPC_SPAWN
            }
        })
        val table = VisTable()
        table.defaults().left().growX()
        table.add(wrapped("Choosing one switches to the spawn tool.")).growX().minWidth(0f).row()
        table.add(npcBox).growX().row()
        return table
    }
}
