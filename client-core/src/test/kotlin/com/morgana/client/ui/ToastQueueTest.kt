package com.morgana.client.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToastQueueTest {
    @Test
    fun classifiesUsefulSystemMessages() {
        assertEquals(ToastKind.LEVEL, ToastClassifier.classify("Level up! You are now level 3."))
        assertEquals(ToastKind.GOOD, ToastClassifier.classify("You picked up Iron Sword."))
        assertEquals(ToastKind.GOOD, ToastClassifier.classify("Quest updated: Rats in the Cellar"))
        assertEquals(ToastKind.WARNING, ToastClassifier.classify("Not enough gold."))
        assertNull(ToastClassifier.classify("Welcome to the realm."))
        assertNull(ToastClassifier.classify("Art import rejected: too big"))
    }

    @Test
    fun keepsOnlyTheNewestAndExpiresByTime() {
        val q = ToastQueue(maxVisible = 2, lifetimeSeconds = 4f)
        q.add("a", ToastKind.INFO, 0f)
        q.add("b", ToastKind.INFO, 1f)
        q.add("c", ToastKind.INFO, 2f)
        assertEquals(listOf("b", "c"), q.visible.map { it.text })
        assertTrue(q.expire(5.5f)) // b (1s) expired, c (2s) not
        assertEquals(listOf("c"), q.visible.map { it.text })
    }

    @Test
    fun repeatedMessageRestartsItsTimerInsteadOfStacking() {
        val q = ToastQueue()
        q.add("You picked up Herb.", ToastKind.GOOD, 0f)
        q.add("You picked up Herb.", ToastKind.GOOD, 3f)
        assertEquals(1, q.visible.size)
        assertTrue(!q.expire(5f)) // restarted at 3s, so still alive at 5s
        assertEquals(1, q.drainFresh().size)
        assertTrue(q.drainFresh().isEmpty())
    }

    @Test
    fun offerIgnoresUnremarkableMessages() {
        val q = ToastQueue()
        q.offer("Welcome to the realm.", 0f)
        assertTrue(q.visible.isEmpty())
        q.offer("Inventory is full.", 0f)
        assertEquals(ToastKind.WARNING, q.visible.single().kind)
    }
}
