package com.morgana.domain.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RolePlayChatTest {

    @Test
    fun `slash commands map to styles and aliases agree`() {
        assertEquals(RolePlayLine(ChatStyle.ACTION, "draws her sword", "me"), RolePlayChat.parseCommand("/me draws her sword"))
        assertEquals(ChatStyle.ACTION, RolePlayChat.parseCommand("/EMOTE waves")!!.style)
        assertEquals(ChatStyle.ACTION, RolePlayChat.parseCommand("/em waves")!!.style)
        assertEquals(ChatStyle.OOC, RolePlayChat.parseCommand("/ooc brb")!!.style)
        assertEquals(ChatStyle.SHOUT, RolePlayChat.parseCommand("/yell Help!")!!.style)
        assertEquals(ChatStyle.QUIET, RolePlayChat.parseCommand("/mutter psst")!!.style)
        assertEquals(ChatStyle.NARRATION, RolePlayChat.parseCommand("/describe The wind howls.")!!.style)
        assertEquals(RolePlayLine(ChatStyle.NORMAL, "Hello", "say"), RolePlayChat.parseCommand("  /say   Hello "))
    }

    @Test
    fun `other commands and plain text are not role-play commands`() {
        assertNull(RolePlayChat.parseCommand("/mute Pat 5m"))
        assertNull(RolePlayChat.parseCommand("/meow"), "a command only matches as a whole word")
        assertNull(RolePlayChat.parseCommand("me waves"))
    }

    @Test
    fun `a command with no words parses with blank text so the server can show usage`() {
        assertEquals("", RolePlayChat.parseCommand("/me")!!.text)
        assertEquals("", RolePlayChat.parseCommand("/ooc (( ))")!!.text)
    }

    @Test
    fun `double brackets mark out-of-character text and the emote channel marks actions`() {
        assertEquals(RolePlayLine(ChatStyle.OOC, "back in 5"), RolePlayChat.parseText("((back in 5))", emoteChannel = false))
        assertEquals(RolePlayLine(ChatStyle.OOC, "lag"), RolePlayChat.parseText("(( lag", emoteChannel = true))
        assertEquals(RolePlayLine(ChatStyle.ACTION, "bows"), RolePlayChat.parseText("bows", emoteChannel = true))
        assertEquals(RolePlayLine(ChatStyle.NORMAL, "(not ooc)"), RolePlayChat.parseText("(not ooc)", emoteChannel = false))
        assertEquals(RolePlayLine(ChatStyle.OOC, "fine", "ooc"), RolePlayChat.parseCommand("/ooc ((fine))"))
    }

    private val speaker = Listener("me", 1, 10, 10)
    private val near = Listener("near", 1, 12, 8)      // 2 tiles away; a diagonal step counts as one
    private val mid = Listener("mid", 1, 15, 10)       // 5 tiles
    private val far = Listener("far", 1, 30, 30)       // 20 tiles
    private val north = Listener("north", 2, 10, 10)   // on a neighbouring map
    private val elsewhere = Listener("elsewhere", 9, 10, 10)
    private val everyone = listOf(far, speaker, near, mid, north, elsewhere)
    private val neighbors: (Int) -> Set<Int> = { if (it == 1) setOf(2, 0, 1) else emptySet() }

    private fun hear(style: ChatStyle, settings: RolePlayChatSettings) =
        RolePlayChat.recipients(speaker, everyone, style, settings, neighbors)

    @Test
    fun `by default speech fills the whole map, as map chat always did`() {
        val defaults = RolePlayChatSettings()
        assertEquals(listOf("far", "me", "near", "mid"), hear(ChatStyle.NORMAL, defaults))
        assertEquals(listOf("far", "me", "near", "mid"), hear(ChatStyle.ACTION, defaults))
        assertEquals(listOf("far", "me", "near", "mid"), hear(ChatStyle.OOC, defaults))
    }

    @Test
    fun `a say radius limits speech and actions but not out-of-character talk or narration`() {
        val ranged = RolePlayChatSettings(sayRadius = 5)
        assertEquals(listOf("me", "near", "mid"), hear(ChatStyle.NORMAL, ranged))
        assertEquals(listOf("me", "near", "mid"), hear(ChatStyle.ACTION, ranged))
        assertEquals(listOf("far", "me", "near", "mid"), hear(ChatStyle.OOC, ranged))
        assertEquals(listOf("far", "me", "near", "mid"), hear(ChatStyle.NARRATION, ranged))
    }

    @Test
    fun `quiet speech reaches only those right beside the speaker`() {
        assertEquals(listOf("me", "near"), hear(ChatStyle.QUIET, RolePlayChatSettings()))
        assertEquals(listOf("me"), hear(ChatStyle.QUIET, RolePlayChatSettings(quietRadius = 1)))
    }

    @Test
    fun `a shout carries to neighbouring maps unless switched off, never further`() {
        assertEquals(listOf("far", "me", "near", "mid", "north"), hear(ChatStyle.SHOUT, RolePlayChatSettings(sayRadius = 3)))
        assertEquals(listOf("far", "me", "near", "mid"), hear(ChatStyle.SHOUT, RolePlayChatSettings(shoutReachesNeighbors = false)))
    }

    @Test
    fun `older clients get the style folded into the text`() {
        assertEquals("Aria" to "* Aria waves", RolePlayChat.plainFallback(ChatStyle.ACTION, "Aria", "waves"))
        assertEquals("Aria" to "(( brb ))", RolePlayChat.plainFallback(ChatStyle.OOC, "Aria", "brb"))
        assertEquals(RolePlayChat.NARRATOR_NAME to "Night falls.", RolePlayChat.plainFallback(ChatStyle.NARRATION, "GM", "Night falls."))
        assertEquals("Aria" to "hi", RolePlayChat.plainFallback(ChatStyle.NORMAL, "Aria", "hi"))
    }

    @Test
    fun `settings reject radii out of range`() {
        assertThrows(IllegalArgumentException::class.java) { RolePlayChatSettings(sayRadius = -1) }
        assertThrows(IllegalArgumentException::class.java) { RolePlayChatSettings(quietRadius = 0) }
        assertThrows(IllegalArgumentException::class.java) { RolePlayChatSettings(sayRadius = RolePlayChatSettings.MAX_RADIUS + 1) }
    }
}
