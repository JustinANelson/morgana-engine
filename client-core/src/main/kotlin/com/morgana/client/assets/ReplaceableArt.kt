package com.morgana.client.assets

/**
 * Overlay over a live id-to-art map: a realm's own art replaces entries, and [restore] puts back exactly what was there
 * before (the built-in art, or nothing for an id the built-ins never had). Keeps the built-in art intact across realm
 * switches so one realm's art can never leak into the next.
 *
 * Pure bookkeeping with no graphics calls, so it is unit-tested without a GL context.
 */
class ReplaceableArt<T : Any>(private val live: MutableMap<Int, T>) {
    private val originals = mutableMapOf<Int, T?>()

    /** The ids currently replaced by a realm's own art. */
    val replacedIds: Set<Int> get() = originals.keys.toSet()

    /** Number of ids currently replaced. */
    val replacedCount: Int get() = originals.size

    /** Replaces [id] with [art]; the first replacement of an id remembers what it displaced. */
    fun replace(id: Int, art: T) {
        if (id !in originals) originals[id] = live[id]
        live[id] = art
    }

    /** Undoes every replacement. Returns how many ids were restored. */
    fun restore(): Int {
        val count = originals.size
        for ((id, original) in originals) {
            if (original == null) live.remove(id) else live[id] = original
        }
        originals.clear()
        return count
    }
}
