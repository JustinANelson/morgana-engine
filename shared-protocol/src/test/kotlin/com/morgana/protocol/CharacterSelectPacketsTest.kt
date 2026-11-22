package com.morgana.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CharacterSelectPacketsTest {
    @Test
    fun `character packets round-trip through JSON and CBOR`() {
        val list = ServerPacket.CharacterList(
            listOf(CharacterSlot("c1", "Mirabel", 1, "Knight", 4, 2)), maxCharacters = 3,
            classes = listOf(com.morgana.protocol.realms.RealmClassSummary(1, "Knight", "A fighter", 1)), message = "Deleted Nix."
        )
        assertEquals(list, PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(list)))
        assertEquals(list, PacketCodec.decodeServerCbor(PacketCodec.encodeServerCbor(list)))
        for (p in listOf<ClientPacket>(ClientPacket.ChooseCharacter("c1"), ClientPacket.CreateCharacter("Wren", 2, 3), ClientPacket.DeleteCharacter("c1", "Mirabel"))) {
            assertEquals(p, PacketCodec.decodeClientJson(PacketCodec.encodeClientJson(p)))
            assertEquals(p, PacketCodec.decodeClientCbor(PacketCodec.encodeClientCbor(p)))
        }
    }

    @Test
    fun `an older client decodes the list as Unknown`() {
        val json = PacketCodec.encodeServerJson(ServerPacket.CharacterList(emptyList(), 3)).replace("s_character_list", "s_from_the_future")
        assertTrue(PacketCodec.decodeServerJson(json) is ServerPacket.Unknown)
    }

    @Test
    fun `the capability is opt-in, not a default`() {
        assertFalse(ProtocolCapabilities.CHARACTER_SELECT in ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES)
    }

    @Test
    fun `names have plain rules`() {
        for (ok in listOf("Wren", "Tobias the Brewer", "O'Neil", "a_b-c", "Zoë9".replace("ë", "e"), "  Padded  ")) assertNull(CharacterNames.problem(ok), ok)
        for (bad in listOf("", " ", "x", "y".repeat(21), "two  spaces", "bad!", "---", "tab\tname", "emoji😀")) assertNotNull(CharacterNames.problem(bad), "'$bad'")
        assertEquals("Wren", CharacterNames.clean("  Wren "))
    }
}
