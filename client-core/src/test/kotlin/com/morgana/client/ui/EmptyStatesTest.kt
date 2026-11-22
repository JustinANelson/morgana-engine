package com.morgana.client.ui

import com.morgana.client.ui.bag.BagFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmptyStatesTest {
    @Test
    fun `an empty bag list says why`() {
        assertEquals("No items match \"axe\". Clear the search to see everything.", EmptyStates.bag(BagFilter.ALL, "  axe "))
        assertTrue(EmptyStates.bag(BagFilter.GEAR, "").contains("no gear items"))
        assertEquals("Your bag is empty.", EmptyStates.bag(BagFilter.ALL, ""))
        assertTrue(EmptyStates.bag(BagFilter.USABLE, "zzz").startsWith("No items match"), "the search wins over the tab")
    }

    @Test
    fun `an empty realm list tells you how to get unstuck`() {
        assertTrue(EmptyStates.realms(filtering = true).contains("Clear the filters"))
        assertTrue(EmptyStates.realms(filtering = false).contains("Direct Join"))
    }

    @Test
    fun `no empty text names a place or person from any particular game`() {
        val all = listOf(EmptyStates.QUESTS, EmptyStates.RECIPES, EmptyStates.SPELLS, EmptyStates.VAULT, EmptyStates.VAULT_BAG, EmptyStates.LOCAL_WORLDS)
        for (text in all) {
            assertFalse(text.contains("Havenwood") || text.contains("Thorne"), text)
            assertTrue(text.isNotBlank())
        }
    }
}
