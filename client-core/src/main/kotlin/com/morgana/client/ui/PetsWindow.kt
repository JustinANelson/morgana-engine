package com.morgana.client.ui

import com.badlogic.gdx.scenes.scene2d.Action
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import com.morgana.client.state.ClientWorldState

/**
 * The Pets window: every pet the character has adopted with its level and XP, a Summon button for each, Dismiss for the
 * one that is out, and a rename field. The buttons send the `/pet ...` chat commands ([PetsModel]), so the window and the
 * chat agree; the window rebuilds itself when the character's pets change.
 */
class PetsWindow(
    private val stage: Stage,
    private val worldState: ClientWorldState,
    private val sendCommand: (String) -> Unit
) {
    private val theme = GameTheme.shared
    private var window: HudWindow? = null

    val isOpen: Boolean get() = window?.stage != null

    fun toggle() {
        if (isOpen) { close(); return }
        open(null)
    }

    fun close() {
        window?.remove()
        window = null
    }

    private fun button(label: String, onClick: () -> Unit) = VisTextButton(label, theme.buttonStyle).apply {
        addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: Actor?) { onClick() }
        })
    }

    private fun open(at: Pair<Float, Float>?) {
        val ch = worldState.localCharacter ?: return
        val shownPets = ch.pets
        val shownActive = ch.activePetNpcId
        val win = HudWindow("Pets")
        win.addCloseButton()
        win.isMovable = true
        win.addAction(object : Action() {
            override fun act(delta: Float): Boolean {
                val now = worldState.localCharacter
                if (window === win && now != null && (now.pets != shownPets || now.activePetNpcId != shownActive)) {
                    val spot = win.x to win.y
                    close()
                    open(spot)
                }
                return false
            }
        })
        val body = VisTable().apply { top().left(); defaults().pad(3f).left() }
        val rows = PetsModel.rows(ch.pets, ch.activePetNpcId, worldState.catalog.npcs)
        if (rows.isEmpty()) {
            body.add(VisLabel("You have no pets. A pet handler sells whistles that bring one home.", theme.mutedLabelStyle)).width(300f).row()
        } else {
            rows.forEach { r ->
                val text = VisTable().apply { left(); defaults().left() }
                text.add(VisLabel(r.title + if (r.active) "  (with you)" else "")).row()
                text.add(VisLabel(r.progress, theme.mutedLabelStyle)).row()
                body.add(text).width(220f)
                if (r.active) body.add(button("Dismiss") { sendCommand(PetsModel.DISMISS) }).row()
                else body.add(button("Summon") { sendCommand(PetsModel.summon(r.npcId)) }).row()
            }
            if (ch.activePetNpcId != 0) {
                val name = VisTextField().apply { messageText = "New name for the pet with you"; maxLength = 16 }
                body.add(name).width(220f)
                body.add(button("Rename") {
                    PetsModel.rename(name.text)?.let(sendCommand)
                        ?: worldState.toasts.add("Type a name first.", ToastKind.WARNING, ToastClock.now())
                }).row()
            }
        }
        win.add(body)
        win.pack()
        if (at != null) win.setPosition(at.first, at.second) else win.setPosition((stage.width - win.width) / 2f, (stage.height - win.height) / 2f)
        stage.addActor(win)
        window = win
    }
}
