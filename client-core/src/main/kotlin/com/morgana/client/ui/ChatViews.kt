package com.morgana.client.ui

import com.morgana.protocol.ChatChannel

/**
 * What the chat shows: everything (the default) or one channel. [sendTo] is the channel a message typed in this view goes to
 * (the views that only read, like All, Whisper and System, send to Local). A new channel in the protocol (trade, say) is one more entry here.
 */
enum class ChatView(val label: String, private val channels: Set<ChatChannel>?, val sendTo: ChatChannel) {
    ALL("All", null, ChatChannel.MAP),
    LOCAL("Local", setOf(ChatChannel.MAP, ChatChannel.EMOTE), ChatChannel.MAP),
    GLOBAL("Global", setOf(ChatChannel.GLOBAL, ChatChannel.BROADCAST), ChatChannel.GLOBAL),
    PARTY("Party", setOf(ChatChannel.PARTY), ChatChannel.PARTY),
    GUILD("Guild", setOf(ChatChannel.GUILD), ChatChannel.GUILD),
    WHISPER("Whisper", setOf(ChatChannel.WHISPER), ChatChannel.MAP),
    SYSTEM("System", setOf(ChatChannel.SYSTEM), ChatChannel.MAP);

    /** True when a message on [channel] appears in this view. */
    fun shows(channel: ChatChannel): Boolean = channels?.contains(channel) ?: true

    /** The next view in the list, for the phone's one-button switch. */
    fun next(): ChatView = entries[(ordinal + 1) % entries.size]
}

/** Which new messages bring the chat up, kept apart from the window so the rule is unit-tested. */
object ChatActivity {
    /**
     * True when a message on [channel] should pop the chat up while the player is looking at [view]: it must be in the view, and
     * on All the system channel (XP, loot, notices) stays quiet so routine lines do not keep waking it. Everything else still
     * shows when the chat is already up, and counts as unread on the chat button while it is hidden.
     */
    fun wakes(view: ChatView, channel: ChatChannel): Boolean =
        view.shows(channel) && !(view == ChatView.ALL && channel == ChatChannel.SYSTEM)

    /** The items of [history] after the item [lastSeen] (compared by identity), or all of them when it is no longer there. */
    fun <T : Any> newSince(history: List<T>, lastSeen: T?): List<T> {
        if (lastSeen == null) return history.toList()
        val at = history.indexOfLast { it === lastSeen }
        return if (at < 0) history.toList() else history.subList(at + 1, history.size).toList()
    }
}
