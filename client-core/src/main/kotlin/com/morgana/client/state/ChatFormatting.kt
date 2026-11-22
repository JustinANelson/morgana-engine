package com.morgana.client.state

import com.morgana.domain.chat.ChatStyle
import com.morgana.protocol.ServerPacket

/** How chat lines and speech bubbles read in the HUD, by role-play [ChatStyle]. Pure, so it is unit tested. */
object ChatFormatting {
    /** One line of the chat log. */
    fun line(msg: ServerPacket.ChatBroadcast): String = when (msg.style) {
        ChatStyle.NORMAL -> "[${msg.channel.prefixLabel}] ${msg.senderName}: ${msg.message}"
        ChatStyle.ACTION -> "* ${msg.senderName} ${msg.message}"
        ChatStyle.OOC -> "[OOC] ${msg.senderName}: ${msg.message}"
        ChatStyle.SHOUT -> "${msg.senderName} shouts: ${msg.message}"
        ChatStyle.QUIET -> "${msg.senderName} says quietly: ${msg.message}"
        ChatStyle.NARRATION -> "~ ${msg.message} ~"
    }

    /** The text in the bubble over the speaker's head. */
    fun bubble(msg: ServerPacket.ChatBroadcast): String = when (msg.style) {
        ChatStyle.ACTION -> "* ${msg.message} *"
        ChatStyle.QUIET -> "(${msg.message})"
        else -> msg.message
    }

    /** Whether a line is shown in the chat log, given the player's out-of-character filter. */
    fun isShown(msg: ServerPacket.ChatBroadcast, hideOoc: Boolean): Boolean = !(hideOoc && msg.style == ChatStyle.OOC)
}
