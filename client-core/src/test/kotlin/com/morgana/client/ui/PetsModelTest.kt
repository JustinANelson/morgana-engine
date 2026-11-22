package com.morgana.client.ui

import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.PetTraits
import com.morgana.domain.entity.PetState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PetsModelTest {
    private val finch = NpcDefinition(id = 90, name = "Tame Finch", pet = PetTraits(maxLevel = 5, xpPerLevel = 100))
    private val whelp = NpcDefinition(id = 91, name = "Ember Whelp", pet = PetTraits(maxLevel = 5, xpPerLevel = 100))
    private val rat = NpcDefinition(id = 7, name = "Rat")

    @Test
    fun `rows put the pet that is out first and show its title and progress`() {
        val rows = PetsModel.rows(mapOf(90 to PetState(2, 40, "Pip"), 91 to PetState(5, 0)), activeNpcId = 91, npcs = listOf(finch, whelp, rat))
        assertEquals(listOf(91, 90), rows.map { it.npcId })
        assertTrue(rows[0].active)
        assertEquals("Level 5 (highest)", rows[0].progress)
        assertEquals("Pip the Tame Finch", rows[1].title)
        assertEquals("Level 2, 40/200 XP", rows[1].progress)
        assertEquals("Ember Whelp", rows[0].title)
    }

    @Test
    fun `pets whose kind is gone are left out and pets are detected`() {
        assertTrue(PetsModel.rows(mapOf(99 to PetState()), 0, listOf(finch)).isEmpty())
        assertTrue(PetsModel.gameHasPets(listOf(rat, finch)))
        assertFalse(PetsModel.gameHasPets(listOf(rat)))
    }

    @Test
    fun `buttons send the pet chat commands`() {
        assertEquals("/pet summon 90", PetsModel.summon(90))
        assertEquals("/pet dismiss", PetsModel.DISMISS)
        assertEquals("/pet name Pip", PetsModel.rename("  Pip "))
        assertEquals("/pet name 0123456789abcdef", PetsModel.rename("0123456789abcdefXYZ"))
        assertNull(PetsModel.rename("   "))
    }
}
