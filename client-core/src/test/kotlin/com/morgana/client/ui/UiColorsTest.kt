package com.morgana.client.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class UiColorsTest {
    @Test
    fun `health is red and mana is blue wherever they sit in the list`() {
        assertEquals(UiColors.vital("hp", 0), UiColors.vital("hp", 5))
        assertEquals(UiColors.vital("anything", 0), UiColors.vital("hp", 3), "the first vital is health-coloured")
        assertEquals(UiColors.vital("mp", 1), UiColors.vital("mana_pool_mp", 4))
        assertNotEquals(UiColors.vital("hp", 0), UiColors.vital("mp", 1))
    }

    @Test
    fun `later vitals get their own colours`() {
        assertNotEquals(UiColors.vital("sp", 2), UiColors.vital("xx", 3))
        assertNotEquals(UiColors.vital("sp", 2), UiColors.vital("mp", 1))
    }
}
