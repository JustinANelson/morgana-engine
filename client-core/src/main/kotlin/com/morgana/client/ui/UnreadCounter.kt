package com.morgana.client.ui

/**
 * Counts how many entries arrived in a capped, append-only list since it was last looked at, so a minimized window can
 * show "Chat (3)". Entries are told apart by identity; when the remembered newest entry has scrolled out of the list
 * every entry counts as new.
 */
class UnreadCounter<T : Any> {
    private var newest: T? = null
    private var primed = false

    /** Entries that arrived while [watching] and were not read; reset to 0 whenever not watching. */
    var unread: Int = 0
        private set

    /** Call once per frame with the current [entries]; [watching] is true while the window is minimized. */
    fun update(entries: List<T>, watching: Boolean): Int {
        val last = entries.lastOrNull()
        if (last !== newest) {
            val remembered = newest
            val seenAt = if (remembered == null) -1 else entries.indexOfLast { it === remembered }
            if (primed) unread += entries.size - 1 - seenAt // the list as first seen is the baseline, not news
            newest = last
        }
        primed = true
        if (!watching) unread = 0
        return unread
    }
}
