package com.morgana.client.access

import com.badlogic.gdx.Input.Keys

/** The sections of the Controls window, in order. */
enum class ActionGroup(val label: String) {
    MOVEMENT("Movement"),
    ACTIONS("Actions"),
    WINDOWS("Windows"),
    QUICKBAR("Quickbar")
}

/**
 * The things a player can do with a key, in the order the Controls window lists them. [defaults] are the keys the game has
 * always used (a second key is a convenience, so players with one hand or an unusual keyboard can drop either). `F1` and
 * `Esc` are not actions: they are fixed (see [KeyBindings.isReserved]). The quickbar slots are actions like the others,
 * so their keys can be changed too.
 */
enum class GameAction(val label: String, val defaults: List<Int>, val group: ActionGroup) {
    MOVE_UP("Move up", listOf(Keys.W, Keys.UP), ActionGroup.MOVEMENT),
    MOVE_DOWN("Move down", listOf(Keys.S, Keys.DOWN), ActionGroup.MOVEMENT),
    MOVE_LEFT("Move left", listOf(Keys.A, Keys.LEFT), ActionGroup.MOVEMENT),
    MOVE_RIGHT("Move right", listOf(Keys.D, Keys.RIGHT), ActionGroup.MOVEMENT),
    ATTACK("Attack", listOf(Keys.SPACE, Keys.CONTROL_LEFT), ActionGroup.ACTIONS),
    JUMP("Jump", listOf(Keys.F), ActionGroup.ACTIONS),
    SPRINT("Sprint (hold)", listOf(Keys.SHIFT_LEFT), ActionGroup.ACTIONS),
    INTERACT("Interact", listOf(Keys.E, Keys.ENTER), ActionGroup.ACTIONS),
    PICK_UP("Pick up", listOf(Keys.G), ActionGroup.ACTIONS),
    CHARACTER("Character", listOf(Keys.V), ActionGroup.WINDOWS),
    INVENTORY("Inventory", listOf(Keys.I), ActionGroup.WINDOWS),
    SPELLBOOK("Spells / Skills", listOf(Keys.K), ActionGroup.WINDOWS),
    QUESTS("Quest log", listOf(Keys.J), ActionGroup.WINDOWS),
    CRAFTING("Crafting", listOf(Keys.C), ActionGroup.WINDOWS),
    PARTY("Party", listOf(Keys.P), ActionGroup.WINDOWS),
    MINIMAP("Minimap", listOf(Keys.M), ActionGroup.WINDOWS),
    WORLD_MAP("World map", listOf(Keys.N), ActionGroup.WINDOWS),
    CONTROLS("Controls (this list)", listOf(Keys.H), ActionGroup.WINDOWS),
    HOTBAR_1("Quickbar 1", listOf(Keys.NUM_1, Keys.NUMPAD_1), ActionGroup.QUICKBAR),
    HOTBAR_2("Quickbar 2", listOf(Keys.NUM_2, Keys.NUMPAD_2), ActionGroup.QUICKBAR),
    HOTBAR_3("Quickbar 3", listOf(Keys.NUM_3, Keys.NUMPAD_3), ActionGroup.QUICKBAR),
    HOTBAR_4("Quickbar 4", listOf(Keys.NUM_4, Keys.NUMPAD_4), ActionGroup.QUICKBAR),
    HOTBAR_5("Quickbar 5", listOf(Keys.NUM_5, Keys.NUMPAD_5), ActionGroup.QUICKBAR),
    HOTBAR_6("Quickbar 6", listOf(Keys.NUM_6, Keys.NUMPAD_6), ActionGroup.QUICKBAR),
    HOTBAR_7("Quickbar 7", listOf(Keys.NUM_7, Keys.NUMPAD_7), ActionGroup.QUICKBAR),
    HOTBAR_8("Quickbar 8", listOf(Keys.NUM_8, Keys.NUMPAD_8), ActionGroup.QUICKBAR),
    HOTBAR_9("Quickbar 9", listOf(Keys.NUM_9, Keys.NUMPAD_9), ActionGroup.QUICKBAR),
    HOTBAR_10("Quickbar 10", listOf(Keys.NUM_0, Keys.NUMPAD_0), ActionGroup.QUICKBAR);

    val isMovement: Boolean get() = group == ActionGroup.MOVEMENT

    /** The quickbar slot (0..9) this action uses, or null for other actions. */
    val hotbarIndex: Int? get() = if (group == ActionGroup.QUICKBAR) ordinal - HOTBAR_1.ordinal else null

    companion object {
        /** The action that uses quickbar slot [index] (0..9). */
        fun hotbar(index: Int): GameAction = entries[HOTBAR_1.ordinal + index]
    }
}

/** How many keys one action may have. */
const val KEYS_PER_ACTION = 2

/**
 * Which keys do which [GameAction]: an immutable value, so a change is always a new [KeyBindings] and the old one can be
 * kept for "undo". Pure (no libGDX runtime, only key code constants), so every rule is unit-tested.
 *
 * Rules: a key does at most one action (binding a key that another action uses takes it away from that action, unless that
 * would leave it with no key at all); every action keeps at least one key; the fixed keys cannot be bound.
 */
class KeyBindings private constructor(private val keys: Map<GameAction, List<Int>>) {
    /** The keys of [action] in slot order (at most [KEYS_PER_ACTION]). */
    fun keysFor(action: GameAction): List<Int> = keys[action].orEmpty()

    /** The action [keycode] triggers, or null. */
    fun actionFor(keycode: Int): GameAction? = GameAction.entries.firstOrNull { keycode in keysFor(it) }

    /** Whether any key of [action] is held, judged by [isDown] (the platform's "is this key pressed" check). */
    fun isDown(action: GameAction, isDown: (Int) -> Boolean): Boolean = keysFor(action).any(isDown)

    /** True when this is exactly the standard layout. */
    val isDefault: Boolean get() = this == DEFAULT

    sealed interface Result {
        /** [message] tells the player what happened beyond the obvious (for example which action lost the key). */
        data class Changed(val bindings: KeyBindings, val message: String) : Result
        data class Refused(val reason: String) : Result
    }

    /** Puts [keycode] in slot [slot] (0 or 1) of [action]. */
    fun bind(action: GameAction, slot: Int, keycode: Int): Result {
        require(slot in 0 until KEYS_PER_ACTION) { "slot must be 0 or 1" }
        if (isReserved(keycode)) return Result.Refused("${keyName(keycode)} is used by the game and cannot be changed.")
        if (keysFor(action).getOrNull(slot) == keycode) return Result.Changed(this, "${action.label} already uses ${keyName(keycode)}.")
        val next = keys.mapValues { it.value.toMutableList() }.toMutableMap()
        var message = "${action.label}: ${keyName(keycode)}."
        val owner = actionFor(keycode)
        if (owner != null && owner != action) {
            val ownerKeys = next.getValue(owner)
            if (ownerKeys.size <= 1) return Result.Refused("${keyName(keycode)} is the only key for ${owner.label}. Give ${owner.label} another key first.")
            ownerKeys.remove(keycode)
            message = "${action.label}: ${keyName(keycode)} (taken from ${owner.label})."
        }
        val mine = next.getValue(action)
        val at = mine.indexOf(keycode)
        if (at >= 0 && at != slot && slot < mine.size) {
            // The key is already this action's other key: swap the two instead of losing one.
            mine[at] = mine[slot]
            mine[slot] = keycode
        } else {
            mine.remove(keycode)
            if (slot < mine.size) mine[slot] = keycode else mine.add(keycode)
        }
        return Result.Changed(KeyBindings(next.mapValues { it.value.distinct().take(KEYS_PER_ACTION) }), message)
    }

    /** Removes the key in slot [slot] of [action]. An action's last key cannot be removed. */
    fun clear(action: GameAction, slot: Int): Result {
        val mine = keysFor(action)
        if (slot !in mine.indices) return Result.Changed(this, "Nothing to remove.")
        if (mine.size <= 1) return Result.Refused("${action.label} needs at least one key.")
        val next = keys.toMutableMap()
        next[action] = mine.filterIndexed { i, _ -> i != slot }
        return Result.Changed(KeyBindings(next), "${action.label}: key removed.")
    }

    fun reset(): KeyBindings = DEFAULT

    /** `ACTION=code,code;ACTION=code` for saving. Only actions that differ from the defaults are written. */
    fun encode(): String = GameAction.entries
        .filter { keysFor(it) != it.defaults }
        .joinToString(";") { a -> "${a.name}=${keysFor(a).joinToString(",")}" }

    override fun equals(other: Any?): Boolean = other is KeyBindings && GameAction.entries.all { keysFor(it) == other.keysFor(it) }
    override fun hashCode(): Int = GameAction.entries.fold(1) { h, a -> 31 * h + keysFor(a).hashCode() }
    override fun toString(): String = "KeyBindings(${encode().ifEmpty { "defaults" }})"

    companion object {
        val DEFAULT = KeyBindings(GameAction.entries.associateWith { it.defaults })

        /** Keys that are fixed: the editor key and Esc (which also cancels choosing a key, as Backspace clears one). */
        fun isReserved(keycode: Int): Boolean = keycode == Keys.F1 || keycode == Keys.ESCAPE

        /**
         * Whether pressing [keycode] types nothing into a text field (arrows, modifiers, Tab, paging, function keys). Movement on
         * such a key works even while a text field has focus, and takes the focus away from it; a letter key must not.
         */
        fun isNonTextKey(keycode: Int): Boolean = keycode in setOf(
            Keys.UP, Keys.DOWN, Keys.LEFT, Keys.RIGHT, Keys.TAB, Keys.HOME, Keys.END, Keys.PAGE_UP, Keys.PAGE_DOWN, Keys.INSERT,
            Keys.CONTROL_LEFT, Keys.CONTROL_RIGHT, Keys.SHIFT_LEFT, Keys.SHIFT_RIGHT, Keys.ALT_LEFT, Keys.ALT_RIGHT
        ) || keycode in Keys.F1..Keys.F12

        /** The text of a key for the UI (`W`, `Space`, `Left Ctrl`). */
        fun keyName(keycode: Int): String = when (keycode) {
            Keys.CONTROL_LEFT -> "Left Ctrl"
            Keys.CONTROL_RIGHT -> "Right Ctrl"
            Keys.SHIFT_LEFT -> "Left Shift"
            Keys.SHIFT_RIGHT -> "Right Shift"
            Keys.ALT_LEFT -> "Left Alt"
            Keys.ALT_RIGHT -> "Right Alt"
            else -> Keys.toString(keycode)?.takeIf { it.isNotBlank() && it != "Unknown" } ?: "Key $keycode"
        }

        /** A key's name in at most four characters, for small buttons such as the quickbar slots (`1`, `Q`, `Np1`, `LCtl`). */
        fun shortKeyName(keycode: Int): String {
            val name = keyName(keycode)
            if (name.length <= 4) return name
            return when {
                name.startsWith("Numpad ") -> "Np" + name.removePrefix("Numpad ").take(2)
                name.startsWith("Left ") -> "L" + name.removePrefix("Left ").take(3)
                name.startsWith("Right ") -> "R" + name.removePrefix("Right ").take(3)
                else -> name.take(4)
            }
        }

        /**
         * Reads what [encode] wrote. Anything unreadable (an unknown action, a bad number, a reserved key, a key used twice) is
         * skipped, so a damaged or hand-edited setting can never leave the game without movement keys.
         */
        fun decode(text: String?): KeyBindings {
            if (text.isNullOrBlank()) return DEFAULT
            // What the text asks for, first mention of a key wins and reserved or unreadable keys are dropped.
            val claimed = HashSet<Int>()
            val custom = LinkedHashMap<GameAction, List<Int>>()
            for (part in text.split(';')) {
                val action = GameAction.entries.firstOrNull { it.name == part.substringBefore('=').trim() } ?: continue
                if (action in custom) continue
                val codes = part.substringAfter('=', "").split(',').mapNotNull { it.trim().toIntOrNull() }
                    .filter { it > 0 && !isReserved(it) }.distinct().take(KEYS_PER_ACTION).filter { claimed.add(it) }
                if (codes.isNotEmpty()) custom[action] = codes
            }
            // The other actions keep their default keys unless a custom action took them.
            val result = GameAction.entries.associateWith { a -> custom[a] ?: a.defaults.filter { it !in claimed } }.toMutableMap()
            return KeyBindings(result).repaired()
        }
    }

    /**
     * Gives an action that has no key left its first default key (taking it from whoever uses it now), until every action
     * has one. The defaults are all different, so this always ends.
     */
    private fun repaired(): KeyBindings {
        val fixed = keys.toMutableMap()
        repeat(GameAction.entries.size + 1) {
            val empty = GameAction.entries.filter { fixed.getValue(it).isEmpty() }
            if (empty.isEmpty()) return KeyBindings(fixed)
            for (a in empty) {
                val key = a.defaults.first()
                for (b in GameAction.entries) if (b != a) fixed[b] = fixed.getValue(b).filter { it != key }
                fixed[a] = listOf(key)
            }
        }
        return DEFAULT
    }
}
