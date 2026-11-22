package com.morgana.client.realms

import com.morgana.client.preview.demoCharacterList
import com.morgana.client.preview.demoRealmDirectory
import com.morgana.client.preview.fakeCharactersFromQuery
import com.morgana.client.preview.fakeRealmsFromQuery
import com.morgana.protocol.CharacterSlot
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CharacterSelectModelTest {
    private val list = demoCharacterList(2)

    @Test
    fun `cards and the slot line read plainly`() {
        assertEquals("Level 14 Knight", CharacterSelectModel.subtitle(list.characters[0]))
        assertEquals("2 of 3 characters", CharacterSelectModel.slotsLine(list))
        assertEquals("No characters yet", CharacterSelectModel.slotsLine(demoCharacterList(0)))
        assertEquals("1 of 1 character", CharacterSelectModel.slotsLine(demoCharacterList(1).copy(maxCharacters = 1)))
    }

    @Test
    fun `a new name is checked before the server is asked`() {
        assertNull(CharacterSelectModel.createProblem(list, "Wren"))
        assertTrue(CharacterSelectModel.createProblem(list, "x")!!.contains("2 to 20"))
        assertTrue(CharacterSelectModel.createProblem(list, "Bad!")!!.contains("letters"))
        assertTrue(CharacterSelectModel.createProblem(list, "mirabel")!!.contains("already have"))
        assertTrue(CharacterSelectModel.createProblem(demoCharacterList(3), "Wren")!!.contains("most characters"))
        assertFalse(CharacterSelectModel.canCreate(demoCharacterList(3)))
        assertTrue(CharacterSelectModel.canCreate(list))
    }

    @Test
    fun `deleting needs the name typed`() {
        val slot = list.characters[1]
        assertTrue(CharacterSelectModel.deleteConfirmed(slot, "  tobias the brewer "))
        assertFalse(CharacterSelectModel.deleteConfirmed(slot, "Tobias"))
        assertTrue(CharacterSelectModel.deleteWarning(slot).contains("Tobias the Brewer"))
    }

    @Test
    fun `the first character is preselected unless another was played last`() {
        assertEquals("Mirabel", CharacterSelectModel.initialSelection(list, null)!!.name)
        assertEquals("Tobias the Brewer", CharacterSelectModel.initialSelection(list, "tobias the brewer")!!.name)
        assertNull(CharacterSelectModel.initialSelection(ServerPacket.CharacterList(emptyList(), 3), null))
    }

    @Test
    fun `fakeCharacters in a page address asks for a demo list`() {
        assertEquals(2, fakeCharactersFromQuery("?port=7022&fakeCharacters=2"))
        assertEquals(0, fakeCharactersFromQuery("?fakeCharacters=0"))
        assertNull(fakeCharactersFromQuery("?fakeCharacters=9"))
        assertNull(fakeCharactersFromQuery(""))
        assertEquals(3, demoCharacterList(9).characters.size.coerceAtMost(3))
        assertEquals(CharacterSlot("demo-1", "Mirabel", 1, "Knight", 14, 1), demoCharacterList().characters[0])
    }
}
