package com.morgana.client.state

import com.morgana.domain.content.AbilityTerminology
import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.protocol.WorldContentCatalogDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AbilityTerminologyAndCraftingUiTest {

    @Test
    fun `abilityTerminology follows the local character class and defaults to SPELLS`() {
        val state = ClientWorldState()
        assertEquals(AbilityTerminology.SPELLS, state.abilityTerminology())

        state.catalog = WorldContentCatalogDto(
            classes = listOf(
                ClassDefinition(id = 1, name = "Trojan", abilityTerminology = AbilityTerminology.SKILLS),
                ClassDefinition(id = 4, name = "Water Taoist", abilityTerminology = AbilityTerminology.SPELLS)
            )
        )
        state.localCharacter = CharacterState(id = "c1", accountId = "a", name = "Hero", classId = 1)
        assertEquals(AbilityTerminology.SKILLS, state.abilityTerminology())

        state.localCharacter = CharacterState(id = "c1", accountId = "a", name = "Hero", classId = 4)
        assertEquals(AbilityTerminology.SPELLS, state.abilityTerminology())
    }

    @Test
    fun `showCraftingUi requires crafting module enabled and at least one recipe`() {
        val state = ClientWorldState()
        assertFalse(state.showCraftingUi())

        state.catalog = WorldContentCatalogDto(
            recipes = listOf(RecipeDefinition(id = 1, name = "Test", outputItemId = 1))
        )
        assertTrue(state.showCraftingUi())

        state.disabledModules = setOf("crafting")
        assertFalse(state.showCraftingUi())
    }
}
