package com.morgana.client.ui.editor

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.editor.forms.GameRulesForm
import com.morgana.client.state.ClientWorldState
import com.morgana.domain.game.GameDefinition

/**
 * The Game rules panel: the game's `game.json` (stats, vitals, levels, combat formulas, HUD, death and movement systems, loot
 * rarities and affixes, new-player tips) as a form built from [GameRulesForm]. Save sends the whole definition; the server checks
 * it, writes `game.json` and says so in chat. The running server keeps its current rules until it restarts.
 */
class GameRulesPanel(
    private val stage: Stage,
    private val theme: EditorTheme,
    private val assets: ProceduralRetroAssets,
    private val worldState: ClientWorldState,
    private val save: (GameDefinition) -> Unit
) {
    val content: Actor

    private val formTable = VisTable()
    private val problems = VisLabel("", theme.warningLabel)
    private val status = theme.muted("")
    private val saveButton: VisTextButton

    private var base: GameDefinition? = null
    private var typed: MutableMap<String, String> = linkedMapOf()
    private var loaded: Map<String, String> = emptyMap()
    private var sentAt = 0L

    init {
        problems.setWrap(true)
        status.setWrap(true)
        saveButton = theme.textButton("Save game rules") { send() }
        formTable.defaults().left().growX().pad(2f)
        val pane = VisScrollPane(formTable)
        pane.setScrollingDisabled(true, false)
        pane.setFadeScrollBars(false)
        pane.setOverscroll(false, false)
        giveScrollFocusOnHover(stage, pane)

        val whole = LiveTable { follow() }
        whole.add(pane).grow().row()
        whole.add(problems).growX().minWidth(0f).pad(2f).row()
        whole.add(status).growX().minWidth(0f).pad(2f).row()
        whole.add(saveButton).growX().pad(2f).row()
        content = whole
    }

    private fun follow() {
        val definition = worldState.gameDefinition
        if (definition == null) {
            status.setText("This server has not sent its game rules.")
            saveButton.isDisabled = true
            return
        }
        // Load once, and again only when the form is clean and the server's rules changed under it.
        if (base == null || (definition != base && typed == loaded)) load(definition)
    }

    private fun load(definition: GameDefinition) {
        base = definition
        val values = GameRulesForm.valuesOf(definition)
        loaded = values
        typed = values.toMutableMap()
        formTable.clearChildren()
        val view = FormFieldsView(stage, theme, assets, worldState, typed) { refresh() }
        formTable.add(theme.muted("Changes are saved to game.json and take effect after the server restarts.")).growX().minWidth(0f).row()
        for (section in GameRulesForm.sections()) {
            formTable.add(theme.heading(section.title)).growX().padTop(6f).row()
            for (f in section.fields) view.add(formTable, f)
        }
        formTable.add().growY()
        refresh()
    }

    private fun refresh() {
        val b = base ?: return
        val list = GameRulesForm.problems(b, typed)
        problems.setText(list.joinToString("\n"))
        saveButton.isDisabled = list.isNotEmpty() || typed == loaded
        status.setText(
            when {
                System.currentTimeMillis() - sentAt < 8000 && sentAt != 0L -> "Sent. The server's answer appears in chat."
                typed != loaded -> "Unsaved changes."
                else -> ""
            }
        )
    }

    private fun send() {
        val b = base ?: return
        if (GameRulesForm.problems(b, typed).isNotEmpty()) return
        save(GameRulesForm.apply(b, typed))
        loaded = typed.toMap()
        sentAt = System.currentTimeMillis()
        refresh()
    }
}
