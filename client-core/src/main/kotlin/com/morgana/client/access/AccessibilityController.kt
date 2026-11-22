package com.morgana.client.access

import com.badlogic.gdx.Input.Keys

/**
 * Owns the settings in force: changes them, saves every change, tells the game what changed and runs the "press a key" flow
 * for rebinding. The Accessibility window talks only to this; it has no graphics, so the flow is unit-tested.
 *
 * [systemReducedMotion] is what the operating system or browser asks for; it is the starting value of Reduce motion until
 * the player makes their own choice.
 */
class AccessibilityController(
    private val store: AccessibilityStore,
    private val systemReducedMotion: Boolean = false
) {
    enum class Change { SCALE, PALETTE, MOTION, KEYS, AUTO_LOOT }

    /** Called after every change, so the game can rescale the interface, rebuild coloured bars and refresh key hints. */
    var onChanged: (Change) -> Unit = {}

    val current: AccessibilitySettings get() = Accessibility.settings

    /** Reads the saved settings and puts them in force. Nothing is reported as a change. */
    fun load() {
        Accessibility.systemPrefersReducedMotion = systemReducedMotion
        Accessibility.apply(store.load(defaultReduceMotion = systemReducedMotion))
    }

    private fun update(change: Change, next: AccessibilitySettings) {
        Accessibility.apply(next)
        store.save(next)
        onChanged(change)
    }

    fun cycleScale(): Float {
        val next = UiScales.next(current.uiScale)
        update(Change.SCALE, current.copy(uiScale = next))
        return next
    }

    fun setPalette(palette: ColourPalette) {
        if (palette != current.palette) update(Change.PALETTE, current.copy(palette = palette))
    }

    fun cyclePalette(): ColourPalette {
        val all = ColourPalette.entries
        val next = all[(all.indexOf(current.palette) + 1) % all.size]
        setPalette(next)
        return next
    }

    fun setReduceMotion(on: Boolean) {
        if (on != current.reduceMotion) update(Change.MOTION, current.copy(reduceMotion = on))
    }

    fun setAutoLoot(on: Boolean) {
        if (on != current.autoLoot) update(Change.AUTO_LOOT, current.copy(autoLoot = on))
    }

    // ----- key rebinding

    /** The slot waiting for a key press, or null. While set, the game sends every key to [captureKey] and does nothing else with it. */
    var capture: Pair<GameAction, Int>? = null
        private set

    val isCapturing: Boolean get() = capture != null

    fun beginCapture(action: GameAction, slot: Int) {
        capture = action to slot
    }

    fun cancelCapture() {
        capture = null
    }

    /**
     * Handles a key press during capture and returns what to tell the player (null when not capturing). Esc cancels, Backspace
     * or Delete removes the key in that slot, anything else is bound if the rules allow it.
     */
    fun captureKey(keycode: Int): String? {
        val (action, slot) = capture ?: return null
        if (keycode == Keys.ESCAPE) {
            capture = null
            return "Cancelled."
        }
        val result = if (keycode == Keys.BACKSPACE || keycode == Keys.FORWARD_DEL) current.keys.clear(action, slot) else current.keys.bind(action, slot, keycode)
        return when (result) {
            is KeyBindings.Result.Refused -> result.reason.also { /* stay in capture so the player can try another key */ }
            is KeyBindings.Result.Changed -> {
                capture = null
                if (result.bindings != current.keys) update(Change.KEYS, current.copy(keys = result.bindings))
                result.message
            }
        }
    }

    fun clearKey(action: GameAction, slot: Int): String = when (val result = current.keys.clear(action, slot)) {
        is KeyBindings.Result.Refused -> result.reason
        is KeyBindings.Result.Changed -> {
            if (result.bindings != current.keys) update(Change.KEYS, current.copy(keys = result.bindings))
            result.message
        }
    }

    fun resetKeys() {
        capture = null
        if (!current.keys.isDefault) update(Change.KEYS, current.copy(keys = KeyBindings.DEFAULT))
    }
}
