package com.morgana.client.ui

/** Pure helpers for keyboard navigation, so the rules are unit-tested apart from the widgets. */
object KeyNav {
    /** [index] moved by [delta] but stopped at the ends (a list does not wrap); from "nothing selected" (-1) a move down lands on the first. -1 when [count] is 0. */
    fun step(index: Int, count: Int, delta: Int): Int {
        if (count <= 0) return -1
        if (index < 0) return if (delta >= 0) 0 else count - 1
        return (index + delta).coerceIn(0, count - 1)
    }

    /** The index after [index] in a ring of [count] items (wrapping), or before it when [backwards]. -1 when [count] is 0. */
    fun cycle(index: Int, count: Int, backwards: Boolean): Int {
        if (count <= 0) return -1
        val step = if (backwards) -1 else 1
        return Math.floorMod(index + step, count)
    }
}
