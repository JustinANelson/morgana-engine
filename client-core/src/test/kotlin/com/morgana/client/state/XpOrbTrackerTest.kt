package com.morgana.client.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XpOrbTrackerTest {

    @Test
    fun `npc kill spawns orb and holds xp until orb reaches player`() {
        val tracker = XpOrbTracker()
        tracker.onNpcKilled(mapId = 1, worldX = 160f, worldY = 96f)
        assertTrue(tracker.onXpFloatingText(mapId = 1, worldX = 32f, worldY = 32f, text = "+40 XP", colorHex = "#38BDF8", enabled = true))
        tracker.onLocalXpChange(prevLevel = 1, prevXp = 10L, newLevel = 1, newXp = 50L, enabled = true)

        assertEquals(1, tracker.activeOrbs.size)
        assertEquals(40L, tracker.pendingVisualXp)

        val arrived = mutableListOf<XpOrb>()
        val fallbacks = mutableListOf<String>()
        tracker.update(0.20f, arrived::add) { _, _, _, t, _ -> fallbacks.add(t) }
        assertTrue(arrived.isEmpty())
        assertEquals(40L, tracker.pendingVisualXp)

        tracker.update(0.50f, arrived::add) { _, _, _, t, _ -> fallbacks.add(t) }
        assertEquals(1, arrived.size)
        assertEquals("+40 XP", arrived.first().floatingText)
        assertEquals(0L, tracker.pendingVisualXp)
        assertEquals(1, tracker.absorbPulses.size)
        assertTrue(fallbacks.isEmpty())
    }

    @Test
    fun `popup arriving before npc death still pairs into an orb`() {
        val tracker = XpOrbTracker()
        assertTrue(tracker.onXpFloatingText(mapId = 1, worldX = 32f, worldY = 32f, text = "+25 XP", colorHex = "#38BDF8", enabled = true))
        tracker.onLocalXpChange(prevLevel = 1, prevXp = 0L, newLevel = 1, newXp = 25L, enabled = true)
        assertEquals(25L, tracker.pendingVisualXp)

        tracker.onNpcKilled(mapId = 1, worldX = 200f, worldY = 120f)
        assertEquals(1, tracker.activeOrbs.size)
        assertEquals(200f, tracker.activeOrbs.first().startX)
        assertEquals(25L, tracker.activeOrbs.first().xpAmount)
    }

    @Test
    fun `non-kill xp popup releases via fallback and clears pending xp`() {
        val tracker = XpOrbTracker()
        assertTrue(tracker.onXpFloatingText(mapId = 1, worldX = 32f, worldY = 32f, text = "+100 XP", colorHex = "#38BDF8", enabled = true))
        tracker.onLocalXpChange(prevLevel = 1, prevXp = 0L, newLevel = 1, newXp = 100L, enabled = true)

        val fallbacks = mutableListOf<String>()
        tracker.update(0.20f, {}) { _, _, _, t, _ -> fallbacks.add(t) }
        assertEquals(listOf("+100 XP"), fallbacks)
        assertEquals(0L, tracker.pendingVisualXp)
    }

    @Test
    fun `level up flushes pending visual xp immediately and disabled flag bypasses capture`() {
        val tracker = XpOrbTracker()
        assertFalse(tracker.onXpFloatingText(mapId = 1, worldX = 32f, worldY = 32f, text = "+15 XP", colorHex = "#38BDF8", enabled = false))

        tracker.onNpcKilled(mapId = 1, worldX = 160f, worldY = 96f)
        tracker.onXpFloatingText(mapId = 1, worldX = 32f, worldY = 32f, text = "+50 XP", colorHex = "#38BDF8", enabled = true)
        tracker.onLocalXpChange(prevLevel = 1, prevXp = 70L, newLevel = 2, newXp = 10L, enabled = true)
        assertEquals(0L, tracker.pendingVisualXp)
    }

    @Test
    fun `npc kill by another player without xp popup spawns no orb or toast`() {
        val tracker = XpOrbTracker()
        tracker.onNpcKilled(mapId = 1, worldX = 160f, worldY = 96f)

        val arrived = mutableListOf<XpOrb>()
        val fallbacks = mutableListOf<String>()
        tracker.update(0.25f, arrived::add) { _, _, _, t, _ -> fallbacks.add(t) }

        assertTrue(tracker.activeOrbs.isEmpty())
        assertTrue(arrived.isEmpty())
        assertTrue(fallbacks.isEmpty())
        assertEquals(0L, tracker.pendingVisualXp)
    }
}
