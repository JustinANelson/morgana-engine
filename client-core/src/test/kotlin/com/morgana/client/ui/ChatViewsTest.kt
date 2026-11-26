package com.morgana.client.ui

import com.morgana.protocol.ChatChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatViewsTest {
    @Test
    fun `All shows every channel and a channel view shows only its own`() {
        for (c in ChatChannel.entries) assertTrue(ChatView.ALL.shows(c))
        assertTrue(ChatView.PARTY.shows(ChatChannel.PARTY))
        assertFalse(ChatView.PARTY.shows(ChatChannel.GUILD))
        assertTrue(ChatView.LOCAL.shows(ChatChannel.MAP))
        assertFalse(ChatView.LOCAL.shows(ChatChannel.GLOBAL))
    }

    @Test
    fun `every channel is readable in some view`() {
        for (c in ChatChannel.entries) assertTrue(ChatView.entries.any { it != ChatView.ALL && it.shows(c) }, "no view shows $c")
    }

    @Test
    fun `on All routine system lines stay quiet and everything else wakes the chat`() {
        assertFalse(ChatActivity.wakes(ChatView.ALL, ChatChannel.SYSTEM))
        for (c in ChatChannel.entries.filter { it != ChatChannel.SYSTEM }) assertTrue(ChatActivity.wakes(ChatView.ALL, c), "$c")
    }

    @Test
    fun `a channel view wakes only for its own channel, and the system view wakes for system lines`() {
        assertTrue(ChatActivity.wakes(ChatView.GUILD, ChatChannel.GUILD))
        assertFalse(ChatActivity.wakes(ChatView.GUILD, ChatChannel.PARTY))
        assertTrue(ChatActivity.wakes(ChatView.SYSTEM, ChatChannel.SYSTEM))
    }

    @Test
    fun `the phone chip walks every view and comes back to All`() {
        var v = ChatView.ALL
        val seen = mutableListOf<ChatView>()
        repeat(ChatView.entries.size) { seen += v; v = v.next() }
        assertEquals(ChatView.entries.toList(), seen)
        assertSame(ChatView.ALL, v)
    }

    @Test
    fun `new messages are the ones after the last seen, or all when it was trimmed away`() {
        val a = Any(); val b = Any(); val c = Any()
        assertEquals(listOf(c), ChatActivity.newSince(listOf(a, b, c), b))
        assertEquals(emptyList<Any>(), ChatActivity.newSince(listOf(a, b, c), c))
        assertEquals(listOf(b, c), ChatActivity.newSince(listOf(b, c), a))
        assertEquals(listOf(a, b), ChatActivity.newSince(listOf(a, b), null))
    }
}
