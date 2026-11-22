package com.morgana.client.ui

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.kotcrab.vis.ui.widget.VisTextField

/**
 * A platform's own text box for typing into the game's text fields, for platforms where the game cannot raise the
 * on-screen keyboard itself. In a phone browser the keyboard only appears for a real HTML input, and gdx-teavm's
 * `setOnscreenKeyboardVisible` does nothing, so the browser shows an input bar that mirrors the focused field.
 */
interface TextEntryBridge {
    /**
     * Shows the platform text box for one field. Calling it again while open switches the box to the new field (keeping
     * the keyboard up). [onChange] gets the whole text after every edit, [onEnter] the Enter key, [onClose] the box being
     * dismissed by the player.
     */
    fun open(text: String, hint: String, password: Boolean, onChange: (String) -> Unit, onEnter: () -> Unit, onClose: () -> Unit)

    /** Hides the box (the field lost focus in the game). Safe to call when it is not open. */
    fun close()
}

/**
 * Keeps a [TextEntryBridge] in step with the stage's keyboard focus: when a text field gains focus the box opens on it,
 * edits in the box are applied to the field as if typed (its listeners and change events fire), Enter is delivered as
 * the Enter key, and dismissing the box takes the focus away. Call [sync] once per frame.
 *
 * Works with libGDX's [TextField] and VisUI's `VisTextField` (which is not a [TextField] subclass).
 */
class TextEntrySync(private val stage: Stage, private val bridge: TextEntryBridge) {
    private var field: Actor? = null

    fun sync() {
        val focused = stage.keyboardFocus?.takeIf { textOf(it) != null }
        if (focused === field) return
        field = focused
        if (focused == null) {
            bridge.close()
            return
        }
        bridge.open(
            text = textOf(focused).orEmpty(),
            hint = hintOf(focused),
            password = isPassword(focused),
            onChange = { value -> if (field === focused) apply(focused, value) },
            onEnter = { if (field === focused) pressEnter() },
            onClose = { if (stage.keyboardFocus === focused) stage.keyboardFocus = null }
        )
    }

    /**
     * Turns the field's text into [value] by typing: Backspace over what differs at the end, then the new characters, so
     * the field's filter, length limit, listeners and change events all behave exactly as with a real keyboard.
     */
    private fun apply(f: Actor, value: String) {
        val current = textOf(f) ?: return
        if (current == value || stage.keyboardFocus !== f) return
        val common = current.commonPrefixWith(value).length
        moveCursorToEnd(f, current.length)
        repeat(current.length - common) { stage.keyTyped(BACKSPACE) }
        for (c in value.substring(common)) stage.keyTyped(c)
    }

    /** Enter as the game would see it from a keyboard: forms submit, and single-line fields may move to the next one. */
    private fun pressEnter() {
        stage.keyDown(Input.Keys.ENTER)
        stage.keyTyped('\r')
        stage.keyUp(Input.Keys.ENTER)
    }

    private companion object {
        const val BACKSPACE = '\b'

        fun textOf(a: Actor): String? = when (a) {
            is TextField -> a.text
            is VisTextField -> a.text
            else -> null
        }

        fun hintOf(a: Actor): String = when (a) {
            is TextField -> a.messageText.orEmpty()
            is VisTextField -> a.messageText.orEmpty()
            else -> ""
        }

        fun isPassword(a: Actor): Boolean = when (a) {
            is TextField -> a.isPasswordMode
            is VisTextField -> a.isPasswordMode
            else -> false
        }

        fun moveCursorToEnd(a: Actor, length: Int) {
            when (a) {
                is TextField -> { a.clearSelection(); a.cursorPosition = length }
                is VisTextField -> { a.clearSelection(); a.cursorPosition = length }
            }
        }
    }
}
