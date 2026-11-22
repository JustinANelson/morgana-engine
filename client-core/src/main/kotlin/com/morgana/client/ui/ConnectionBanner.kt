package com.morgana.client.ui

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton

/**
 * Slim banner at the top centre of the screen telling the player the connection dropped, with Retry and Log out buttons.
 * Hidden while the connection is healthy. It never blocks the map, so a brief hiccup is not disruptive.
 */
class ConnectionBanner(
    private val stage: Stage,
    private val onRetry: () -> Unit,
    private val onLogout: () -> Unit
) {
    private val label = VisLabel("")
    private val table = VisTable(true)

    init {
        table.background = GameTheme.shared.windowStyle.background
        table.color.a = 0.9f
        table.pad(6f)
        table.add(label).padRight(10f)
        table.add(button("Retry now", onRetry))
        table.add(button("Log out", onLogout))
        table.isVisible = false
        stage.addActor(table)
    }

    private fun button(text: String, action: () -> Unit) = VisTextButton(text).also { b ->
        b.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) {
                action()
            }
        })
    }

    /** Shows [text] (or hides the banner when null/blank) and keeps it centred and on top. */
    fun update(text: String?) {
        val show = !text.isNullOrBlank()
        table.isVisible = show
        if (!show) return
        if (label.text.toString() != text) label.setText(text)
        table.pack()
        table.setPosition((stage.width - table.width) / 2f, stage.height - table.height - 24f) // below the link indicator
        table.toFront()
    }
}
