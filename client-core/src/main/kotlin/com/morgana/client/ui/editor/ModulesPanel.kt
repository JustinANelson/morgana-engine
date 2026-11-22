package com.morgana.client.ui.editor

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.editor.forms.ModulesForm
import com.morgana.client.state.ClientWorldState
import com.morgana.domain.sim.SystemTags

/**
 * The Systems panel: tick boxes for the server's optional systems (parties, instancing, projectiles, mail...). Apply sends the
 * switches to the server (Owner only), which saves `server.json` and uses them at once; players see the change in their HUD the
 * next time they sign in. Systems are listed under the genre they belong to, and a genre button ticks that genre's systems and
 * unticks the rest (nothing is sent until Apply).
 */
class ModulesPanel(
    private val stage: Stage,
    private val theme: EditorTheme,
    private val assets: ProceduralRetroAssets,
    private val worldState: ClientWorldState,
    private val save: (List<String>) -> Unit
) {
    val content: Actor

    private val formTable = VisTable()
    private val notes = VisLabel("", theme.warningLabel)
    private val status = theme.muted("")
    private val apply: VisTextButton

    private var typed: MutableMap<String, String> = linkedMapOf()
    private var loaded: Map<String, String> = emptyMap()
    private var shownFor: Set<String>? = null
    private var sentAt = 0L

    init {
        notes.setWrap(true)
        status.setWrap(true)
        apply = theme.textButton("Apply systems") { send() }
        formTable.defaults().left().growX().pad(2f)
        val pane = VisScrollPane(formTable)
        pane.setScrollingDisabled(true, false)
        pane.setFadeScrollBars(false)
        pane.setOverscroll(false, false)
        giveScrollFocusOnHover(stage, pane)
        val whole = LiveTable { follow() }
        whole.add(pane).grow().row()
        whole.add(notes).growX().minWidth(0f).pad(2f).row()
        whole.add(status).growX().minWidth(0f).pad(2f).row()
        whole.add(apply).growX().pad(2f).row()
        content = whole
    }

    private fun follow() {
        val disabled = worldState.disabledModules
        if (shownFor == null || (disabled != shownFor && typed == loaded)) load(disabled)
    }

    private fun load(disabled: Set<String>) {
        shownFor = disabled
        val values = ModulesForm.valuesOf(disabled)
        loaded = values
        typed = values.toMutableMap()
        render()
    }

    /** Draws the genre buttons and the grouped tick boxes from the values typed so far. */
    private fun render() {
        formTable.clearChildren()
        val view = FormFieldsView(stage, theme, assets, worldState, typed) { refresh() }
        formTable.add(theme.muted("Switch the realm's systems on or off. Changes apply at once and are saved to server.json.")).growX().minWidth(0f).row()
        formTable.add(theme.muted("Start from a genre (ticks its systems, unticks the rest; nothing is sent until Apply):")).growX().minWidth(0f).row()
        val genres = com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup().apply {
            wrap(true)
            space(4f)
            wrapSpace(4f)
            align(com.badlogic.gdx.utils.Align.left)
        }
        for ((tag, title) in SystemTags.TITLES) {
            genres.addActor(theme.textButton(title) {
                typed.putAll(ModulesForm.valuesForGenre(tag))
                render()
            })
        }
        formTable.add(genres).growX().minWidth(0f).pad(2f).row()
        for (section in ModulesForm.sections()) {
            formTable.add(theme.muted(section.title)).growX().minWidth(0f).padTop(8f).row()
            for (f in section.fields) view.add(formTable, f)
        }
        formTable.add().growY()
        refresh()
    }

    private fun refresh() {
        notes.setText(ModulesForm.warnings(typed).joinToString("\n"))
        apply.isDisabled = typed == loaded
        status.setText(if (System.currentTimeMillis() - sentAt < 8000 && sentAt != 0L) "Sent. The server's answer appears in chat." else if (typed != loaded) "Unsaved changes." else "")
    }

    private fun send() {
        save(ModulesForm.disabledFrom(typed))
        loaded = typed.toMap()
        sentAt = System.currentTimeMillis()
        refresh()
    }
}
