package com.morgana.client.ui

import com.morgana.protocol.ServerPacket

/**
 * The tips the server sent that the player has not closed yet. One shows at a time ([current]); the next appears when it
 * is closed. A tip with the id of one already waiting or showing is dropped, so a resend never stacks. Pure, so the order
 * rules are unit-tested apart from the window.
 */
class TipQueue {
    private val waiting = ArrayDeque<ServerPacket.Tip>()

    /** The tip to show now, or null. */
    val current: ServerPacket.Tip? get() = waiting.firstOrNull()

    val size: Int get() = waiting.size

    fun add(tip: ServerPacket.Tip) {
        if (waiting.none { it.id == tip.id }) waiting.addLast(tip)
    }

    /** Closes the current tip and returns it. */
    fun dismiss(): ServerPacket.Tip? = waiting.removeFirstOrNull()

    fun clear() = waiting.clear()
}

/** Fills the placeholders in a tip: `{key:ACTION}` and `{abilities}`. */
object TipText {
    private val KEY = Regex("\\{key:([A-Za-z0-9_]+)\\}")

    /**
     * @param keyName the player's key for a game action name such as `INVENTORY`, or null when there is no such action
     * @param abilities the game's word for skills or spells, for `{abilities}`
     */
    fun render(template: String, keyName: (String) -> String?, abilities: String): String =
        template
            .replace("{abilities}", abilities)
            .replace(KEY) { m -> keyName(m.groupValues[1]) ?: m.groupValues[1].lowercase().replace('_', ' ') }
}
