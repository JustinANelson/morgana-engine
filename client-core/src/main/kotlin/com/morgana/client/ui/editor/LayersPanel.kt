package com.morgana.client.ui.editor

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisImageButton
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.morgana.client.editor.MapEditSession
import com.morgana.domain.map.MapLayer

/**
 * The layers panel: one row per map layer, top (drawn last) first. The eye button shows or hides the layer in the editor
 * (a viewing aid; nothing is saved), and the name button chooses which layer painting and erasing target. A muted note
 * says whether the layer is above or below characters. Under the list, **Clear** empties the selected layer across the
 * whole map (the second click confirms; Ctrl+Z undoes it).
 */
class LayersPanel(private val stage: com.badlogic.gdx.scenes.scene2d.Stage, private val theme: EditorTheme, private val editor: MapEditSession) {

    val content: Actor

    private val nameButtons = linkedMapOf<MapLayer, VisTextButton>()
    private val eyeButtons = linkedMapOf<MapLayer, VisImageButton>()
    private val clearButton: VisTextButton

    /** The layer whose Clear button was pressed once and now waits for the confirming second press. */
    private var confirming: MapLayer? = null

    init {
        val rows = LiveTable { refresh() }
        rows.defaults().pad(2f)
        val note = theme.muted("Click a layer to paint on it. The eye shows or hides it. Above: drawn over characters. Below: drawn under them.")
        note.setWrap(true)
        rows.add(note).colspan(3).growX().minWidth(0f).padBottom(4f).row()
        for (layer in MapLayer.entries.reversed()) {
            val eye = theme.iconButton("eye", "Show or hide this layer", toggle = true)
            eye.onChange { if (eye.isChecked != editor.isLayerVisible(layer)) editor.toggleLayerVisible(layer) }
            val name = VisTextButton(layerTitle(layer), theme.buttonStyle).also { it.setProgrammaticChangeEvents(false) }
            name.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    if (name.isChecked) editor.layer = layer
                }
            })
            eyeButtons[layer] = eye
            nameButtons[layer] = name
            rows.add(eye).size(28f)
            rows.add(name).growX().minWidth(0f).height(28f)
            rows.add(theme.muted(if (layer.aboveEntities) "above" else "below")).width(48f).padLeft(4f).row()
        }
        theme.selectGroup(*nameButtons.values.toTypedArray())
        clearButton = theme.textButton("") {
            val target = editor.layer
            if (confirming == target) {
                confirming = null
                editor.clearLayer(target)
            } else {
                confirming = target
            }
        }
        theme.tip(clearButton, "Remove every tile on the selected layer across the whole map. Ctrl+Z undoes it.")
        rows.add(clearButton).colspan(3).growX().height(28f).padTop(6f).row()
        rows.add().growY()
        // In a short docked slot the list scrolls instead of spilling over the editor above it.
        val pane = com.kotcrab.vis.ui.widget.VisScrollPane(rows)
        pane.setScrollingDisabled(true, false)
        pane.setFadeScrollBars(false)
        pane.setOverscroll(false, false)
        giveScrollFocusOnHover(stage, pane)
        content = pane
        refresh()
    }

    /** Keeps the buttons in step with the session (the eyedropper and other tools can change the layer or visibility). */
    private fun refresh() {
        for ((layer, button) in eyeButtons) button.isChecked = editor.isLayerVisible(layer)
        nameButtons[editor.layer]?.isChecked = true
        // A pending confirmation is for one layer only; choosing another layer cancels it.
        if (confirming != null && confirming != editor.layer) confirming = null
        val title = layerTitle(editor.layer)
        val count = editor.tilesOn(editor.layer)
        val text = when {
            confirming == editor.layer -> "Click again: remove $count tile${if (count == 1) "" else "s"}"
            count == 0 -> "$title is empty"
            else -> "Clear $title"
        }
        if (clearButton.text.toString() != text) clearButton.setText(text)
        clearButton.isDisabled = count == 0
    }

    companion object {
        fun layerTitle(layer: MapLayer): String =
            layer.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
