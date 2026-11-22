package com.morgana.client.ui.editor

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import com.morgana.client.editor.EditorHelp

/** The editor's Help panel: how it works, the first steps, saving, layouts and the keyboard shortcuts. Text lives in [EditorHelp]. */
class HelpPanel(stage: Stage, theme: EditorTheme) {
    val content: Actor

    init {
        val body = VisTable()
        body.top().left()
        body.defaults().left().growX().pad(3f)
        for (section in EditorHelp.sections) {
            body.add(theme.heading(section.title)).growX().padTop(8f).row()
            for (p in section.paragraphs) {
                val label = theme.text(p)
                label.setWrap(true)
                body.add(label).growX().minWidth(0f).row()
            }
        }
        body.add(theme.heading("Keyboard shortcuts")).growX().padTop(8f).row()
        val keys = VisTable()
        keys.defaults().left().pad(2f)
        for (s in EditorHelp.shortcuts) {
            keys.add(theme.text(s.keys)).width(90f)
            keys.add(theme.muted(s.does)).growX().row()
        }
        body.add(keys).growX().row()
        body.add().growY()
        val pane = VisScrollPane(body)
        pane.setScrollingDisabled(true, false)
        pane.setFadeScrollBars(false)
        pane.setOverscroll(false, false)
        giveScrollFocusOnHover(stage, pane)
        content = pane
    }
}
