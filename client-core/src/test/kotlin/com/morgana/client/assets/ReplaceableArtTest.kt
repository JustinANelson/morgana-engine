package com.morgana.client.assets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** A realm's own art must never leak into the next realm. */
class ReplaceableArtTest {

    @Test
    fun `restore puts back the built-in art`() {
        val live = mutableMapOf(1 to "builtin-1", 2 to "builtin-2")
        val art = ReplaceableArt(live)
        art.replace(1, "hearth-1")
        assertEquals("hearth-1", live[1])
        assertEquals(1, art.restore())
        assertEquals(mapOf(1 to "builtin-1", 2 to "builtin-2"), live)
    }

    @Test
    fun `an id the built-ins never had is removed again`() {
        val live = mutableMapOf(1 to "builtin-1")
        val art = ReplaceableArt(live)
        art.replace(99, "extra")
        art.restore()
        assertFalse(99 in live)
        assertEquals(mapOf(1 to "builtin-1"), live)
    }

    @Test
    fun `replacing twice still restores the original, not the first replacement`() {
        val live = mutableMapOf(1 to "builtin")
        val art = ReplaceableArt(live)
        art.replace(1, "realm-a")
        art.replace(1, "realm-b")
        art.restore()
        assertEquals("builtin", live[1])
    }

    @Test
    fun `switching realms leaves only the new realm's art`() {
        val live = mutableMapOf(1 to "builtin-1", 2 to "builtin-2")
        val art = ReplaceableArt(live)
        art.replace(1, "hearth-1")           // join realm A
        art.restore(); art.replace(2, "pirate-2") // join realm B
        assertEquals(mapOf(1 to "builtin-1", 2 to "pirate-2"), live)
        art.restore()                          // join a realm with no art (Classic)
        assertEquals(mapOf(1 to "builtin-1", 2 to "builtin-2"), live)
        assertEquals(0, art.restore())         // nothing left to undo
    }
}
