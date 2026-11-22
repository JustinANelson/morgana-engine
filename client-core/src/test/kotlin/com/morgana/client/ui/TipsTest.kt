package com.morgana.client.ui

import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TipsTest {
    private fun tip(id: String) = ServerPacket.Tip(id, "T $id", "text $id")

    @Test
    fun `tips show one at a time in the order they arrived and never stack twice`() {
        val q = TipQueue()
        assertNull(q.current)
        q.add(tip("a")); q.add(tip("b")); q.add(tip("a"))
        assertEquals(2, q.size)
        assertEquals("a", q.current?.id)
        assertEquals("a", q.dismiss()?.id)
        assertEquals("b", q.current?.id)
        q.dismiss()
        assertNull(q.current)
        assertNull(q.dismiss())
        q.add(tip("c")); q.clear()
        assertNull(q.current)
    }

    @Test
    fun `placeholders become the player's keys and the game's word for skills`() {
        val keys = mapOf("INVENTORY" to "I", "CHARACTER" to "V")
        val out = TipText.render("Open {key:INVENTORY} or {key:CHARACTER}. New {abilities}!", { keys[it] }, "Skills")
        assertEquals("Open I or V. New Skills!", out)
        assertEquals("Press nothing special.", TipText.render("Press {key:NOTHING_SPECIAL}.", { null }, "Skills").replace("nothing special", "nothing special"))
        assertEquals("no placeholders", TipText.render("no placeholders", { "?" }, "Skills"))
    }
}
