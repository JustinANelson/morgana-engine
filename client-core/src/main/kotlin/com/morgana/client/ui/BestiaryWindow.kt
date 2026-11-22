package com.morgana.client.ui

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.morgana.client.state.ClientWorldState

/**
 * The Bestiary: the monsters the character has killed, with more shown the more of them they have defeated
 * ([BestiaryModel]). A list on the left, the chosen monster's details on the right.
 */
class BestiaryWindow(private val stage: Stage, private val worldState: ClientWorldState) {
    private val theme = GameTheme.shared
    private var window: HudWindow? = null
    private var selected: Int? = null

    val isOpen: Boolean get() = window?.stage != null

    fun toggle() {
        if (isOpen) { close(); return }
        open()
    }

    fun close() {
        window?.remove()
        window = null
    }

    private fun open() {
        val rules = worldState.gameDefinition?.bestiary?.takeIf { it.enabled } ?: return
        val ch = worldState.localCharacter ?: return
        val win = HudWindow("Bestiary")
        win.addCloseButton()
        win.isMovable = true
        val rows = BestiaryModel.rows(ch.bestiary, worldState.catalog.npcs, rules)
        if (rows.isEmpty()) {
            win.add(VisLabel("Defeat monsters to fill the bestiary.", theme.mutedLabelStyle)).pad(12f)
        } else {
            val items = worldState.catalog.items.associateBy { it.id }
            val attrs = worldState.gameDefinition?.attributes?.associate { it.id to it.short.ifBlank { it.name } }.orEmpty()
            val defs = worldState.catalog.npcs.associateBy { it.id }
            val list = VisTable().apply { top().left(); defaults().left().padBottom(2f) }
            val detail = VisTable().apply { top().left(); defaults().left().padBottom(3f) }
            fun show(npcId: Int) {
                selected = npcId
                detail.clearChildren()
                val row = rows.first { it.npcId == npcId }
                detail.add(VisLabel(row.name)).row()
                for (line in BestiaryModel.detail(row, defs.getValue(npcId), items, attrs, rules)) {
                    detail.add(VisLabel(line, theme.mutedLabelStyle).apply { setWrap(true) }).width(230f).row()
                }
            }
            for (row in rows) {
                list.add(VisTextButton("${row.name}  (${row.kills})", theme.buttonStyle).apply {
                    addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent?, actor: Actor?) { show(row.npcId) }
                    })
                }).width(170f).row()
            }
            show(selected?.takeIf { id -> rows.any { it.npcId == id } } ?: rows.first().npcId)
            win.add(VisScrollPane(list)).width(190f).height(260f).pad(6f)
            win.add(detail).width(240f).top().pad(6f)
        }
        win.pack()
        win.setPosition((stage.width - win.width) / 2f, (stage.height - win.height) / 2f)
        stage.addActor(win)
        window = win
    }
}
