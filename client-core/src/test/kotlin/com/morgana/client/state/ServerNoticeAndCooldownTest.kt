package com.morgana.client.state

import com.morgana.client.ui.ToastClock
import com.morgana.client.ui.ToastKind
import com.morgana.domain.content.SpellDefinition
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.NoticeKind
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.WorldContentCatalogDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerNoticeAndCooldownTest {
    private fun system(message: String, notice: NoticeKind? = null) =
        ServerPacket.ChatBroadcast(ChatChannel.SYSTEM, senderName = "System", message = message, showOverheadBubble = false, notice = notice)

    @Test
    fun `a typed notice decides the toast whatever the words say`() {
        val state = ClientWorldState()
        state.applyServerPacket(system("Welcome, traveller.", NoticeKind.WARNING))
        val toast = state.toasts.visible.single()
        assertEquals(ToastKind.WARNING, toast.kind)
        assertEquals("Welcome, traveller.", toast.text)
    }

    @Test
    fun `an untyped message from an older server is still guessed from its words`() {
        val state = ClientWorldState()
        state.applyServerPacket(system("Welcome, traveller."))
        assertTrue(state.toasts.visible.isEmpty(), "nothing in it looks like a notice")
        state.applyServerPacket(system("Not enough gold."))
        assertEquals(ToastKind.WARNING, state.toasts.visible.single().kind)
    }

    @Test
    fun `the server's exact cooldown replaces the estimate and stops further estimating`() {
        val state = ClientWorldState()
        state.catalog = WorldContentCatalogDto(spells = listOf(SpellDefinition(id = 3, name = "Bolt", cooldownTicks = 40), SpellDefinition(id = 4, name = "Nova", cooldownTicks = 40)))

        state.noteSpellCast(3) // before the server has said anything: a 2 s estimate
        assertTrue(state.spellCooldowns.remaining(3, ToastClock.now()) <= 3f, "the guess is about a 2 s estimate")

        state.applyServerPacket(ServerPacket.SpellCooldown(spellId = 3, durationTicks = 600)) // really 30 s (the bound stays loose: a loaded machine can lose seconds between the two calls)
        assertTrue(state.spellCooldowns.remaining(3, ToastClock.now()) in 5f..30f, "far more than the 2 s guess")

        state.noteSpellCast(4) // the server reports cooldowns now, so no guess for another spell
        assertEquals(0f, state.spellCooldowns.remaining(4, ToastClock.now()))
    }

    @Test
    fun `broadcast channel announcements produce a BROADCAST toast`() {
        val state = ClientWorldState()
        state.applyServerPacket(
            ServerPacket.ChatBroadcast(
                ChatChannel.BROADCAST,
                senderName = "SYSTEM",
                message = "Aria found a Wyrm Pearl in Glasswaste (12, 18)!",
                showOverheadBubble = false
            )
        )
        val toast = state.toasts.visible.single()
        assertEquals(ToastKind.BROADCAST, toast.kind)
        assertTrue("Wyrm Pearl" in toast.text)
    }
}
