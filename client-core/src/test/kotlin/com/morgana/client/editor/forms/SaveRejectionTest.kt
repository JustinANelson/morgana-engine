package com.morgana.client.editor.forms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SaveRejectionTest {

    @Test
    fun `the servers refusal line is understood`() {
        val r = SaveRejection.parse("Save rejected for items #12: /price: price must be >= 0; /iconId: unknown icon (+2 more)")!!
        assertEquals("items", r.collection)
        assertEquals(12, r.id)
        assertEquals("/price: price must be >= 0; /iconId: unknown icon (+2 more)", r.reason)
    }

    @Test
    fun `a refusal matches only its own kind and id`() {
        val r = SaveRejection.parse("Save rejected for npcs #4: /spells/0: unknown spell 9")!!
        assertTrue(r.isFor("npc", 4))
        assertFalse(r.isFor("npc", 5))
        assertFalse(r.isFor("item", 4))
    }

    @Test
    fun `other chat lines are not refusals`() {
        assertNull(SaveRejection.parse("Welcome to Hearthvale"))
        assertNull(SaveRejection.parse("Save rejected for items: no id"))
        assertNull(SaveRejection.parse("Save rejected for items #x: bad"))
    }
}
