package com.morgana.client.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import com.kotcrab.vis.ui.widget.VisWindow
import com.morgana.client.realms.CharacterSelectModel
import com.morgana.protocol.CharacterSlot
import com.morgana.protocol.ServerPacket

/**
 * Shown after a correct password when the account may hold several characters (see [ServerPacket.CharacterList]): pick one
 * to play, make a new one, or delete one. It only draws and reports choices; the server decides everything, and answers
 * a refused request with a fresh list whose message says why (which [show] draws in place).
 */
class CharacterSelectWindow(
    private val stage: Stage,
    private val onChoose: (CharacterSlot) -> Unit,
    private val onCreate: (name: String, classId: Int) -> Unit,
    private val onDelete: (slot: CharacterSlot, confirmName: String) -> Unit,
    private val onBack: () -> Unit
) {
    private var window: VisWindow? = null
    private var list: ServerPacket.CharacterList? = null
    private var selectedId: String? = null
    private var confirmingDelete = false
    private var nameDraft = ""
    private var confirmDraft = ""
    private var classIndex = 0
    private var localMessage: String? = null

    fun isOpen(): Boolean = window?.stage != null

    /** Opens the window, or updates it in place when it is already open. */
    fun show(newList: ServerPacket.CharacterList) {
        val previous = list
        list = newList
        localMessage = null
        // A deletion that went through (or any refusal) ends the confirmation step.
        confirmingDelete = false
        confirmDraft = ""
        if (previous == null || !isOpen()) { nameDraft = ""; classIndex = 0 }
        if (selectedId == null || newList.characters.none { it.id == selectedId }) {
            selectedId = CharacterSelectModel.initialSelection(newList, null)?.id
        }
        val win = window?.takeIf { isOpen() } ?: VisWindow("Choose your character").also {
            it.isResizable = false
            it.isMovable = true
            // Esc leaves a text field first, then goes back to the realms.
            it.addCaptureListener(object : com.badlogic.gdx.scenes.scene2d.InputListener() {
                override fun keyDown(event: com.badlogic.gdx.scenes.scene2d.InputEvent?, keycode: Int): Boolean {
                    if (keycode != com.badlogic.gdx.Input.Keys.ESCAPE) return false
                    val focused = stage.keyboardFocus
                    if (focused is com.kotcrab.vis.ui.widget.VisTextField || focused is com.badlogic.gdx.scenes.scene2d.ui.TextField) stage.keyboardFocus = null
                    else onBack()
                    return true
                }
            })
            window = it
        }
        build(win)
        win.pack()
        win.centerWindow()
        if (win.stage == null) stage.addActor(win)
    }

    fun close() {
        window?.remove()
        window = null
        list = null
        selectedId = null
    }

    private fun button(text: String, action: () -> Unit): VisTextButton =
        VisTextButton(text).also { b ->
            b.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) = action()
            })
        }

    private fun muted(text: String, width: Float): VisLabel =
        VisLabel(text).also { it.color = GameTheme.shared.palette.textMuted; it.setWrap(true); it.width = width }

    private fun rebuild() {
        val win = window ?: return
        build(win)
        win.pack()
        win.centerWindow()
    }

    private fun build(win: VisWindow) {
        val l = list ?: return
        win.clearChildren()
        win.defaults().pad(4f).left()
        val width = 380f

        win.add(VisLabel(CharacterSelectModel.slotsLine(l)).also { it.color = GameTheme.shared.palette.textMuted }).width(width).row()
        val message = localMessage ?: l.message
        if (message.isNotBlank()) {
            val good = message.startsWith("Deleted")
            win.add(VisLabel(message).also {
                it.setWrap(true); it.width = width
                it.color = if (good) GameTheme.shared.palette.success else GameTheme.shared.palette.danger
            }).width(width).row()
        }

        // The characters, one card each; a click selects, a double click plays.
        for (slot in l.characters) {
            val card = VisTable()
            card.defaults().left()
            card.background = if (slot.id == selectedId) GameTheme.shared.selectedCardBackground else GameTheme.shared.cardBackground
            card.add(VisLabel(slot.name)).width(width - 20f).row()
            card.add(muted(CharacterSelectModel.subtitle(slot), width - 20f)).width(width - 20f)
            card.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    if (selectedId != slot.id) { selectedId = slot.id; confirmingDelete = false; rebuild() }
                    else if (tapCount >= 2) onChoose(slot)
                }
            })
            win.add(card).width(width).row()
        }

        val selected = l.characters.firstOrNull { it.id == selectedId }
        if (selected != null) {
            if (confirmingDelete) {
                win.add(muted(CharacterSelectModel.deleteWarning(selected), width).also { it.color = GameTheme.shared.palette.danger }).width(width).row()
                val confirm = VisTextField(confirmDraft)
                confirm.messageText = "Type ${selected.name}"
                confirm.setTextFieldListener { f, c ->
                    confirmDraft = f.text
                    if (c == '\r' || c == '\n') tryDelete(selected)
                }
                win.add(confirm).width(width).row()
                val row = VisTable()
                row.add(button("Delete forever") { tryDelete(selected) }).padRight(4f)
                row.add(button("Keep it") { confirmingDelete = false; confirmDraft = ""; rebuild() })
                win.add(row).row()
                stage.keyboardFocus = confirm
            } else {
                val row = VisTable()
                row.add(button("Play ${selected.name}") { onChoose(selected) }).padRight(4f)
                row.add(button("Delete...") { confirmingDelete = true; rebuild() })
                win.add(row).padTop(2f).row()
            }
        }

        // A new character.
        if (!confirmingDelete && CharacterSelectModel.canCreate(l)) {
            win.add(VisLabel(if (l.characters.isEmpty()) "Make your first character:" else "Or make a new one:")).padTop(8f).row()
            val nameField = VisTextField(nameDraft)
            nameField.messageText = "Character name"
            nameField.maxLength = com.morgana.protocol.CharacterNames.MAX
            val classBox = VisSelectBox<String>()
            classBox.setItems(*l.classes.map { it.name }.toTypedArray())
            val selectedIdx = if (l.classes.isNotEmpty()) classIndex.coerceIn(0, l.classes.size - 1) else 0
            if (l.classes.isNotEmpty()) classBox.selectedIndex = selectedIdx
            val classDescLabel = muted(l.classes.getOrNull(selectedIdx)?.description.orEmpty(), width)
            classBox.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    classIndex = classBox.selectedIndex
                    classDescLabel.setText(l.classes.getOrNull(classIndex)?.description.orEmpty())
                }
            })
            fun create() {
                nameDraft = nameField.text
                val problem = CharacterSelectModel.createProblem(l, nameField.text)
                    ?: if (l.classes.isEmpty()) "This realm has no classes to choose from." else null
                if (problem != null) { localMessage = problem; rebuild(); return }
                onCreate(nameField.text.trim(), l.classes[classBox.selectedIndex.coerceAtLeast(0)].id)
            }
            nameField.setTextFieldListener { f, c -> nameDraft = f.text; if (c == '\r' || c == '\n') create() }
            val row = VisTable()
            row.add(nameField).width(170f).padRight(4f)
            row.add(classBox).width(110f).padRight(4f)
            row.add(button("Create") { create() })
            win.add(row).row()
            if (l.classes.isNotEmpty()) win.add(classDescLabel).width(width).row()
            if (l.characters.isEmpty()) stage.keyboardFocus = nameField
        }

        win.add(button("Back to realms") { onBack() }).padTop(8f).row()
    }

    private fun tryDelete(slot: CharacterSlot) {
        if (!CharacterSelectModel.deleteConfirmed(slot, confirmDraft)) {
            localMessage = "Type ${slot.name} exactly to confirm."
            rebuild()
            return
        }
        onDelete(slot, confirmDraft.trim())
    }
}
