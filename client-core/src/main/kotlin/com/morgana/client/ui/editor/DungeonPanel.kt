package com.morgana.client.ui.editor

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.editor.forms.DungeonForm
import com.morgana.client.state.ClientWorldState
import com.morgana.domain.map.MapDefinition

/**
 * The dungeon panel: the floor recipe and the party scaling and difficulty tiers of the map being edited (a [DungeonForm] drawn
 * with the shared field view). Apply writes them to the draft as one undo step; save the map as usual.
 */
class DungeonPanel(
    private val stage: Stage,
    private val theme: EditorTheme,
    private val assets: ProceduralRetroAssets,
    private val worldState: ClientWorldState
) {
    val content: Actor

    private val editor = worldState.editor
    private val formTable = VisTable()
    private val problems = VisLabel("", theme.warningLabel)
    private val title = theme.muted("")
    private val apply: VisTextButton

    private var typed: MutableMap<String, String> = linkedMapOf()
    private var loaded: Map<String, String> = emptyMap()
    private var loadedMapId = -1

    init {
        problems.setWrap(true)
        apply = theme.textButton("Apply to map") { applyChanges() }

        formTable.defaults().left().growX().pad(2f)
        val pane = VisScrollPane(formTable)
        pane.setScrollingDisabled(true, false)
        pane.setFadeScrollBars(false)
        pane.setOverscroll(false, false)
        giveScrollFocusOnHover(stage, pane)

        val whole = LiveTable { follow() }
        whole.add(title).left().pad(2f).row()
        whole.add(pane).grow().row()
        whole.add(problems).growX().minWidth(0f).pad(2f).row()
        whole.add(apply).growX().pad(2f).row()
        content = whole
    }

    private fun follow() {
        val draft = editor.draft
        if (draft == null) {
            title.setText("No map loaded")
            apply.isDisabled = true
            return
        }
        val values = DungeonForm.valuesOf(draft)
        // Reload only when the map itself changed (another map, undo, redo, apply); typing never reaches here.
        if (draft.id != loadedMapId || values != loaded) load(draft, values)
    }

    private fun load(draft: MapDefinition, values: Map<String, String>) {
        loadedMapId = draft.id
        loaded = values
        typed = values.toMutableMap()
        title.setText("Map ${draft.id}: dungeon settings")
        formTable.clearChildren()
        val view = FormFieldsView(stage, theme, assets, worldState, typed) { refresh() }
        for (section in DungeonForm.sections()) {
            formTable.add(theme.heading(section.title)).growX().padTop(6f).row()
            for (f in section.fields) view.add(formTable, f)
        }
        formTable.add().growY()
        refresh()
    }

    private fun refresh() {
        val draft = editor.draft ?: return
        val catalog = worldState.catalog
        val list = DungeonForm.problems(
            typed, draft.instanced, catalog.maps.map { it.id }.toSet(), catalog.npcs.map { it.id }.toSet(), catalog.items.map { it.id }.toSet()
        )
        problems.setText(list.joinToString("\n"))
        apply.isDisabled = list.isNotEmpty() || typed == loaded
    }

    private fun applyChanges() {
        val draft = editor.draft ?: return
        val catalog = worldState.catalog
        if (DungeonForm.problems(typed, draft.instanced, catalog.maps.map { it.id }.toSet(), catalog.npcs.map { it.id }.toSet(), catalog.items.map { it.id }.toSet()).isNotEmpty()) return
        val values = typed.toMap()
        editor.updateProperties { DungeonForm.apply(it, values) }
        content.stage?.keyboardFocus = null
    }
}
