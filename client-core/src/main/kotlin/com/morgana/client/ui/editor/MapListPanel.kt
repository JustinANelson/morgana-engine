package com.morgana.client.ui.editor

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisScrollPane
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.morgana.client.editor.MapPropertiesForm
import com.morgana.client.net.ClientTransport
import com.morgana.client.state.ClientWorldState
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.TilePlacement
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.MapSummary

/**
 * The map list: every map in the world (from the server's catalog), the one being edited highlighted. Clicking a map walks the
 * editor there (an admin warp). When the open map has unsaved changes the first click only warns, and a second click on the
 * same map discards them. "New map" creates an empty ground-filled map with the next free id and goes to it.
 */
class MapListPanel(stage: Stage, private val theme: EditorTheme, private val worldState: ClientWorldState, private val net: ClientTransport) {

    val content: Actor

    private val editor = worldState.editor
    private val rows = VisTable()
    private val warning = VisLabel("", theme.warningLabel)
    private val buttons = linkedMapOf<Int, VisTextButton>()

    private var shownList: List<MapSummary>? = null
    private var shownCurrent = -1

    /** The map the user clicked while there were unsaved changes; clicking it again confirms. */
    private var pendingDiscard: Int? = null

    init {
        warning.setWrap(true)
        val root = LiveTable { follow() }
        root.defaults().left().growX().pad(2f)
        val pane = VisScrollPane(rows)
        pane.setScrollingDisabled(true, false)
        pane.setFadeScrollBars(false)
        pane.setOverscroll(false, false)
        giveScrollFocusOnHover(stage, pane)
        root.add(pane).grow().row()
        root.add(warning).growX().minWidth(0f).row()
        root.add(theme.textButton("New map") { createMap() }).growX().row()
        rows.defaults().left().growX().pad(1f)
        content = root
    }

    private fun follow() {
        val list = worldState.catalog.maps
        val current = editor.draft?.id ?: -1
        if (list != shownList || current != shownCurrent) {
            shownList = list
            shownCurrent = current
            rebuild(list, current)
        }
        if (pendingDiscard != null && !editor.isDirty) pendingDiscard = null
        warning.setText(if (pendingDiscard != null) "Unsaved changes will be lost. Click the map again to switch anyway." else "")
    }

    private fun rebuild(list: List<MapSummary>, current: Int) {
        rows.clearChildren()
        buttons.clear()
        if (list.isEmpty()) {
            rows.add(theme.muted("No maps yet (the list loads when the editor opens).")).row()
            return
        }
        for (m in list) {
            val button = VisTextButton("${m.id}: ${m.name}", theme.buttonStyle).also { it.setProgrammaticChangeEvents(false) }
            button.isChecked = m.id == current
            button.label.setAlignment(com.badlogic.gdx.utils.Align.left)
            button.addListener(object : com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
                override fun clicked(event: com.badlogic.gdx.scenes.scene2d.InputEvent?, x: Float, y: Float) { open(m) }
            })
            theme.tip(button, "${m.width} x ${m.height} tiles")
            buttons[m.id] = button
            rows.add(button).growX().row()
        }
    }

    private fun open(map: MapSummary) {
        if (map.id == editor.draft?.id) return
        if (editor.isDirty && pendingDiscard != map.id) {
            pendingDiscard = map.id
            return
        }
        pendingDiscard = null
        net.send(ClientPacket.AdminSlashCommand("/warpto ${map.id} ${map.width / 2} ${map.height / 2}"))
    }

    private fun createMap() {
        if (editor.isDirty && pendingDiscard != NEW_MAP) {
            pendingDiscard = NEW_MAP
            return
        }
        pendingDiscard = null
        val id = MapPropertiesForm.nextFreeMapId(worldState.catalog.maps.map { it.id })
        val ground = MapCell(layers = mapOf(MapLayer.GROUND to TilePlacement(1, 0, 0)))
        val map = MapDefinition(id = id, name = "New map $id", width = 24, height = 18, cells = List(24 * 18) { ground })
        net.send(ClientPacket.SaveMapDefinition(map))
        net.send(ClientPacket.AdminSlashCommand("/warpto $id 12 9"))
        // The list is not pushed when a map is added, so ask for it again.
        net.send(ClientPacket.RequestAdminCatalog)
    }

    private companion object {
        const val NEW_MAP = -2
    }
}
