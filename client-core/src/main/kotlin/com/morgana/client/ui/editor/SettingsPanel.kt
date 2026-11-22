package com.morgana.client.ui.editor

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable

/**
 * The editor's Settings: the colour scheme, chosen from [EditorPalette.SCHEMES]. Each scheme is shown with a strip of its
 * own colours; [onChoose] saves the choice and redraws the editor in it (the host rebuilds every editor with a new theme).
 */
class SettingsPanel(stage: Stage, theme: EditorTheme, current: EditorPalette, onChoose: (EditorPalette) -> Unit) {
    val content: Actor

    init {
        val body = VisTable()
        body.top().left()
        body.defaults().left().growX().pad(3f)
        body.add(theme.heading("Colour scheme")).growX().row()
        val note = theme.muted("How the editor looks. Saved on this computer (or in this browser); the game's own windows keep their look.")
        note.setWrap(true)
        body.add(note).growX().minWidth(0f).padBottom(4f).row()
        for (scheme in EditorPalette.SCHEMES) {
            val row = VisTable()
            row.background = theme.bordered(scheme.panel, if (scheme.id == current.id) scheme.accent else scheme.border, padX = 6f, padY = 5f)
            val swatches = VisTable()
            for (color in listOf(scheme.background, scheme.panelRaised, scheme.accent, scheme.text, scheme.success, scheme.danger)) {
                swatches.add(Image(theme.solid(color))).size(14f).padRight(2f)
            }
            val texts = VisTable()
            texts.add(VisLabel(scheme.name, com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle(theme.font, scheme.text))).left().row()
            val about = VisLabel(scheme.description, com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle(theme.font, scheme.textMuted))
            about.setWrap(true)
            texts.add(about).growX().minWidth(0f).left()
            row.add(swatches).left().padRight(8f)
            row.add(texts).growX().minWidth(0f)
            if (scheme.id == current.id) row.add(VisLabel("In use", com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle(theme.font, scheme.accent))).padLeft(6f)
            else row.add(theme.textButton("Use") { onChoose(scheme) }).padLeft(6f).height(26f)
            body.add(row).growX().padBottom(2f).row()
        }
        body.add().growY()
        val pane = VisScrollPane(body)
        pane.setScrollingDisabled(true, false)
        pane.setFadeScrollBars(false)
        pane.setOverscroll(false, false)
        giveScrollFocusOnHover(stage, pane)
        content = pane
    }
}
