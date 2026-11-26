package com.morgana.client.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatFadeTest {
    @Test
    fun `the chat stays while it is used and for a few seconds after`() {
        assertTrue(ChatFade.wanted(typing = false, pointerOver = false, msSinceUse = 0))
        assertTrue(ChatFade.wanted(typing = false, pointerOver = false, msSinceUse = ChatFade.HOLD_MS - 1))
        assertFalse(ChatFade.wanted(typing = false, pointerOver = false, msSinceUse = ChatFade.HOLD_MS))
    }

    @Test
    fun `typing or hovering keeps it however long ago it was used`() {
        assertTrue(ChatFade.wanted(typing = true, pointerOver = false, msSinceUse = 999_999))
        assertTrue(ChatFade.wanted(typing = false, pointerOver = true, msSinceUse = 999_999))
    }

    @Test
    fun `opacity moves smoothly and stays between 0 and 1`() {
        assertEquals(0f, ChatFade.step(1f, wanted = false, deltaMs = ChatFade.FADE_MS), 0.001f)
        assertEquals(0.5f, ChatFade.step(1f, wanted = false, deltaMs = ChatFade.FADE_MS / 2), 0.001f)
        assertEquals(1f, ChatFade.step(0.9f, wanted = true, deltaMs = ChatFade.FADE_MS), 0.001f)
        assertEquals(0f, ChatFade.step(0f, wanted = false, deltaMs = 500), 0f)
        assertEquals(0.3f, ChatFade.step(0.3f, wanted = true, deltaMs = -50), 0.001f, "time never runs backwards")
    }
}
