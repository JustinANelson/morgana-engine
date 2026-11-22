package com.morgana.client

import com.morgana.client.state.ChatFormatting
import com.morgana.domain.chat.ChatStyle
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatFormattingTest {

    private fun msg(style: ChatStyle, channel: ChatChannel = ChatChannel.MAP) =
        ServerPacket.ChatBroadcast(channel = channel, senderEntityId = "e1", senderName = "Aria", message = "waves", style = style)

    @Test
    fun `each style reads as role-play`() {
        assertEquals("[Map] Aria: waves", ChatFormatting.line(msg(ChatStyle.NORMAL)))
        assertEquals("* Aria waves", ChatFormatting.line(msg(ChatStyle.ACTION, ChatChannel.EMOTE)))
        assertEquals("[OOC] Aria: waves", ChatFormatting.line(msg(ChatStyle.OOC)))
        assertEquals("Aria shouts: waves", ChatFormatting.line(msg(ChatStyle.SHOUT)))
        assertEquals("Aria says quietly: waves", ChatFormatting.line(msg(ChatStyle.QUIET)))
        assertEquals("~ waves ~", ChatFormatting.line(msg(ChatStyle.NARRATION)))
    }

    @Test
    fun `bubbles mark actions and quiet speech`() {
        assertEquals("* waves *", ChatFormatting.bubble(msg(ChatStyle.ACTION)))
        assertEquals("(waves)", ChatFormatting.bubble(msg(ChatStyle.QUIET)))
        assertEquals("waves", ChatFormatting.bubble(msg(ChatStyle.NORMAL)))
    }

    @Test
    fun `the out-of-character filter hides only OOC lines`() {
        assertFalse(ChatFormatting.isShown(msg(ChatStyle.OOC), hideOoc = true))
        assertTrue(ChatFormatting.isShown(msg(ChatStyle.OOC), hideOoc = false))
        assertTrue(ChatFormatting.isShown(msg(ChatStyle.ACTION), hideOoc = true))
    }
}
