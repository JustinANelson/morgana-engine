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
        assertTrue(!q.expire(4.5f)) // restarted at 3s, still alive at 4.5s
        assertTrue(q.expire(5.0f)) // expired past 1.9s lifetime
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

    @Test
    fun batchedToastsStaggerAndExpireOneAtATime() {
        val q = ToastQueue(maxVisible = 5, lifetimeSeconds = 4f, staggerSeconds = 0.7f)
        q.add("+Rawhide x3", ToastKind.GOOD, 10f)
        q.add("+Copper Scrap x2", ToastKind.GOOD, 10f)
        q.add("+Linen", ToastKind.GOOD, 10f)
        assertEquals(listOf("+Rawhide x3", "+Copper Scrap x2", "+Linen"), q.visible.map { it.text })
        assertTrue(q.expire(14.1f))
        assertEquals(listOf("+Copper Scrap x2", "+Linen"), q.visible.map { it.text })
        assertTrue(q.expire(14.8f))
        assertEquals(listOf("+Linen"), q.visible.map { it.text })
        assertTrue(q.expire(15.5f))
        assertTrue(q.visible.isEmpty())
    }

    @Test
    fun evictedToastsAreRemovedFromFreshQueue() {
        val q = ToastQueue(maxVisible = 2, lifetimeSeconds = 2f, staggerSeconds = 0.35f)
        q.add("+A", ToastKind.GOOD, 0f)
        q.add("+B", ToastKind.GOOD, 0f)
        q.add("+C", ToastKind.GOOD, 0f)
        q.add("+A", ToastKind.GOOD, 0f)
        assertEquals(listOf("+C", "+A"), q.visible.map { it.text })
        assertEquals(listOf("+C", "+A"), q.drainFresh().map { it.text })
    }
}
