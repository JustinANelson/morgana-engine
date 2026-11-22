package com.morgana.client.ui

import com.morgana.domain.game.StallDefinition
import com.morgana.domain.spatial.WorldPosition
import com.morgana.protocol.PublicPlayerSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StallCommandsTest {
    @Test
    fun `add counts bag slots from one and takes an optional unit count`() {
        assertEquals("/stall add 1 250", StallCommands.add(0, "250", ""))
        assertEquals("/stall add 4 50 3", StallCommands.add(3, " 50 ", "3"))
    }

    @Test
    fun `unusable numbers give no command`() {
        assertNull(StallCommands.add(-1, "5", ""))
        assertNull(StallCommands.add(0, "0", ""))
        assertNull(StallCommands.add(0, "abc", ""))
        assertNull(StallCommands.add(0, "5", "0"))
        assertNull(StallCommands.remove("-2"))
        assertNull(StallCommands.buy("Mira", "x", ""))
        assertNull(StallCommands.buy("", "1", ""))
        assertNull(StallCommands.buy("Two Words", "1", ""))
    }

    @Test
    fun `open keeps a blank title to the server default`() {
        assertEquals("/stall open", StallCommands.open("  "))
        assertEquals("/stall open Fine Arrows", StallCommands.open(" Fine Arrows "))
    }

    @Test
    fun `buy view and remove build the matching commands`() {
        assertEquals("/stall buy Mira 2", StallCommands.buy("Mira", "2", ""))
        assertEquals("/stall buy Mira 2 5", StallCommands.buy("Mira", "2", "5"))
        assertEquals("/stall view Mira", StallCommands.view(" Mira "))
        assertEquals("/stall remove 3", StallCommands.remove("3"))
    }

    @Test
    fun `stalls follow the game's switch and map list`() {
        assertFalse(StallCommands.availableOn(null, 2))
        assertFalse(StallCommands.availableOn(StallDefinition(enabled = false), 2))
        assertTrue(StallCommands.availableOn(StallDefinition(), 7), "an empty map list means anywhere")
        assertTrue(StallCommands.availableOn(StallDefinition(mapIds = setOf(2)), 2))
        assertFalse(StallCommands.availableOn(StallDefinition(mapIds = setOf(2)), 3))
    }

    private fun snap(id: String, name: String, x: Int, y: Int, title: String) = PublicPlayerSnapshot(
        characterId = id, name = name, level = 1, spriteId = 0, accessRank = 0, nameplateColorHex = "#FFFFFF",
        stallTitle = title, position = WorldPosition(1, x, y)
    )

    @Test
    fun `nearby lists only other open stalls in range, nearest first`() {
        val all = listOf(
            snap("me", "Me", 10, 10, "My stall"),
            snap("a", "Far", 30, 10, "Far stall"),
            snap("b", "Mid", 13, 10, "Mid stall"),
            snap("c", "Near", 11, 10, "Near stall"),
            snap("d", "Shut", 11, 11, "")
        )
        assertEquals(listOf("Near", "Mid"), StallCommands.nearby(all, "me", 10, 10, 6).map { it.name })
    }
}
