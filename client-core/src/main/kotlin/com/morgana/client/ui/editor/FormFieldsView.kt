package com.morgana.client.ui.editor

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextArea
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.editor.ContentNames
import com.morgana.client.editor.FieldRef
import com.morgana.client.editor.forms.ChoiceText
import com.morgana.client.editor.forms.FormField
import com.morgana.client.editor.forms.FormFieldType
import com.morgana.client.editor.forms.FormRules
import com.morgana.client.state.ClientWorldState
import com.morgana.client.ui.VisualAnimationPickerDialog
import com.morgana.client.ui.VisualIconPickerDialog
import com.morgana.client.ui.VisualSpritePickerDialog
import com.morgana.domain.spatial.Direction

/**
 * A row of a list (events, items, steps): left-aligned, highlighted when [checked], and cut short with "..." when the text is
 * wider than the list instead of spilling out of both sides.
 */
internal fun EditorTheme.listButton(text: String, checked: Boolean, onClick: () -> Unit): VisTextButton {
    val b = VisTextButton(text, buttonStyle).also { it.setProgrammaticChangeEvents(false) }
    b.isChecked = checked
    b.label.setAlignment(Align.left)
    b.label.setEllipsis(true)
    b.labelCell.minWidth(0f) // without this the label keeps its full width and the row overflows
    b.addListener(object : ClickListener() {
        override fun clicked(event: InputEvent?, x: Float, y: Float) { onClick() }
    })
    return b
}

/**
 * Draws [FormField]s as widgets into a table and keeps the typed text in [typed]: text boxes, tick boxes, pick-lists with an
 * explanation of the chosen value, pickers with a preview for icons, sprites and animations, and the name of the content an id
 * points at. Shared by every editor form, so they all look and behave the same. [onChange] runs after every edit.
 */
class FormFieldsView(
    private val stage: Stage,
    private val theme: EditorTheme,
    private val assets: ProceduralRetroAssets,
    private val worldState: ClientWorldState,
    private val typed: MutableMap<String, String>,
    private val onChange: () -> Unit
) {
    private val refLabels = linkedMapOf<String, Pair<FormField, VisLabel>>()
    private val choiceLabels = linkedMapOf<String, Pair<FormField, VisLabel>>()

    /** Adds the label, input and hints of [f] as rows of [table]. */
    fun add(table: VisTable, f: FormField) {
        if (f.type == FormFieldType.BOOL) {
            val box = VisCheckBox(f.label)
            box.isChecked = (typed[f.key] ?: f.default) == "true"
            box.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) { typed[f.key] = box.isChecked.toString(); onChange() }
            })
            table.add(box).left().fill(false, false).row()
            return
        }
        table.add(theme.muted(f.label)).row()
        table.add(input(f)).growX().row()
        if (f.type == FormFieldType.CHOICE) {
            val explain = theme.muted("")
            explain.setWrap(true)
            choiceLabels[f.key] = f to explain
            table.add(explain).growX().minWidth(0f).row()
        }
        if (f.ref != FieldRef.NONE) {
            val label = theme.muted("")
            label.setWrap(true)
            refLabels[f.key] = f to label
            table.add(label).growX().minWidth(0f).row()
        }
        if (f.hint.isNotEmpty()) {
            val hint = theme.muted(f.hint)
            hint.setWrap(true)
            table.add(hint).growX().minWidth(0f).row()
        }
    }

    private fun input(f: FormField): Actor = when (f.type) {
        FormFieldType.CHOICE -> {
            val box = VisSelectBox<String>()
            val none = "(none)"
            val labels = f.choices.map { ChoiceText.label(it) }
            box.setItems(*((if (f.optional) listOf(none) else emptyList()) + labels).toTypedArray())
            val start = typed[f.key].takeUnless { it.isNullOrEmpty() }
            box.selected = start?.let { ChoiceText.label(it) } ?: if (f.optional) none else labels.firstOrNull() ?: ""
            fun raw(): String = if (box.selected == none) "" else ChoiceText.rawFor(box.selected, f.choices) ?: ""
            box.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) { typed[f.key] = raw(); onChange() }
            })
            box
        }
        FormFieldType.LINES -> {
            val area = VisTextArea(typed[f.key] ?: "")
            area.setPrefRows(4f)
            area.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) { typed[f.key] = area.text; onChange() }
            })
            area
        }
        FormFieldType.ICON, FormFieldType.SPRITE, FormFieldType.ANIMATION -> pickerRow(f)
        else -> {
            val field = VisTextField(typed[f.key] ?: f.default)
            field.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) { typed[f.key] = field.text; onChange() }
            })
            field
        }
    }

    /** A number field with a Pick button (and a small preview for icons and sprites). */
    private fun pickerRow(f: FormField): Actor {
        val field = VisTextField(typed[f.key] ?: f.default)
        val preview = Image().apply { setScaling(com.badlogic.gdx.utils.Scaling.fit) }
        fun showPreview() {
            val id = field.text.trim().toIntOrNull() ?: return
            when (f.type) {
                FormFieldType.ICON -> preview.drawable = TextureRegionDrawable(assets.getItemIconRegion(id))
                FormFieldType.SPRITE -> preview.drawable = if (id > 0) TextureRegionDrawable(assets.getSpriteRegion(id, Direction.DOWN, 0)) else null
                else -> Unit
            }
        }
        showPreview()
        field.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { typed[f.key] = field.text; showPreview(); onChange() }
        })
        val pick = theme.textButton("Pick") {
            val currentId = (field.text.trim().toIntOrNull() ?: 1).coerceAtLeast(1)
            val chosen: (Int) -> Unit = { id -> field.text = id.toString() }
            when (f.type) {
                FormFieldType.ICON -> VisualIconPickerDialog(stage, assets) { chosen(it) }.show(currentId)
                FormFieldType.SPRITE -> VisualSpritePickerDialog(stage, assets) { chosen(it) }.show(currentId)
                else -> VisualAnimationPickerDialog(stage, worldState.catalog.animations) { chosen(it) }.show(currentId)
            }
        }
        val row = VisTable()
        if (f.type != FormFieldType.ANIMATION) row.add(preview).size(32f).padRight(4f)
        row.add(field).growX().padRight(4f)
        row.add(pick)
        return row
    }

    /** Updates the lines under the fields: what the chosen value means, and the name of the content each id points at. */
    fun refreshHints() {
        for ((key, pair) in choiceLabels) {
            val (field, label) = pair
            val raw = typed[key].orEmpty().ifEmpty { if (field.optional) "" else field.choices.firstOrNull().orEmpty() }
            label.setText(if (raw.isEmpty()) "" else ChoiceText.explain(raw, field.key))
        }
        for ((key, pair) in refLabels) {
            val (field, label) = pair
            val raw = typed[key].orEmpty()
            label.setText(
                if (field.type == FormFieldType.ID_LIST) {
                    FormRules.parseIds(raw)?.joinToString(", ") { ContentNames.nameOf(worldState.catalog, field.ref, it) ?: "no such ${field.ref.name.lowercase()}" }.orEmpty()
                } else {
                    val id = raw.trim().toIntOrNull()
                    if (id == null || id == 0) "" else ContentNames.hint(worldState.catalog, field.ref, id)
                }
            )
        }
    }
}
