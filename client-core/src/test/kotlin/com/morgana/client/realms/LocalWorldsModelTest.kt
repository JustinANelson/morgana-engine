package com.morgana.client.realms

import com.morgana.client.LocalHostingStatus
import com.morgana.client.LocalWorldDetails
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalWorldsModelTest {
    private fun world(origin: String = "blank", maps: Int = 3, played: Long? = null, default: Boolean = false, off: List<String> = emptyList()) =
        LocalWorldDetails("w", "World", origin, "/x", lastPlayedAtMillis = played, mapCount = maps, disabledFeatures = off, isDefault = default)

    @Test
    fun `origins read as words`() {
        assertEquals("Blank game", LocalWorldsModel.originText("blank"))
        assertEquals("Copy of hearthvale", LocalWorldsModel.originText("game:hearthvale"))
        assertEquals("Imported world pack (Camp)", LocalWorldsModel.originText("pack:Camp"))
        assertEquals("Your original world", LocalWorldsModel.originText("server-data"))
    }

    @Test
    fun `the row subtitle says what it is and when it was last played`() {
        val now = 10_000_000L
        assertEquals("Blank game - 3 maps - not played yet", LocalWorldsModel.subtitle(world(), now, hosting = false))
        assertEquals("Blank game - 3 maps - hosting now", LocalWorldsModel.subtitle(world(), now, hosting = true))
        assertEquals("Blank game - 1 map - played 5 min ago", LocalWorldsModel.subtitle(world(maps = 1, played = now - 300_000), now, false))
        assertEquals("Your original world - 3 maps", LocalWorldsModel.subtitle(world("server-data", default = true), now, false))
    }

    @Test
    fun `switched-off systems are named`() {
        assertEquals("All optional systems are on.", LocalWorldsModel.featuresText(world()))
        val text = LocalWorldsModel.featuresText(world(off = listOf("guilds", "no-such-thing")))
        assertTrue(text.startsWith("Switched off: ") && "no-such-thing" in text, text)
    }

    @Test
    fun `the hosting lines cover running, stopped, unencrypted and unknown address`() {
        assertTrue("Not hosting" in LocalWorldsModel.hostingLine(null))
        val s = LocalHostingStatus("w", "Hearthvale", 7001, 1, "192.168.1.20", encrypted = true)
        assertEquals("Hosting Hearthvale on port 7001 - 1 player online", LocalWorldsModel.hostingLine(s))
        assertEquals("Hosting Hearthvale on port 7001 - 3 players online", LocalWorldsModel.hostingLine(s.copy(onlinePlayers = 3)))
        assertTrue("192.168.1.20:7001" in LocalWorldsModel.friendsLine(s))
        assertTrue("once you start" in LocalWorldsModel.friendsLine(null))
        assertTrue("encryption is disabled" in LocalWorldsModel.friendsLine(s.copy(encrypted = false)))
        assertTrue("invite code" in LocalWorldsModel.friendsLine(s.copy(address = null)))
    }

    @Test
    fun `the delete warning is plain and mentions a running server`() {
        val d = world()
        assertTrue("cannot be brought back" in LocalWorldsModel.deleteWarning(d, hosting = false))
        assertTrue("stopped first" in LocalWorldsModel.deleteWarning(d, hosting = true))
    }
}
