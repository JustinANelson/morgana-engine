package com.morgana.client.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CombatTargetingTest {
    private fun c(id: String, x: Float, y: Float = 0f, boss: Boolean = false, hp: Float = 1f, me: Boolean = false) =
        TargetCandidate(id, x, y, boss, hp, me)

    @Test
    fun `the nearest enemy is picked when nothing else sets them apart`() {
        val pick = CombatTargeting.pickAuto(0f, 0f, listOf(c("far", 150f), c("near", 40f)), null)
        assertEquals("near", pick?.id)
    }

    @Test
    fun `a boss, an enemy hitting you, and a nearly dead one beat a slightly nearer plain one`() {
        assertEquals("boss", CombatTargeting.pickAuto(0f, 0f, listOf(c("plain", 40f), c("boss", 110f, boss = true)), null)?.id)
        assertEquals("me", CombatTargeting.pickAuto(0f, 0f, listOf(c("plain", 40f), c("me", 60f, me = true)), null)?.id)
        assertEquals("low", CombatTargeting.pickAuto(0f, 0f, listOf(c("plain", 40f), c("low", 50f, hp = 0.2f)), null)?.id)
    }

    @Test
    fun `nothing in range gives no target so the attack goes the way the character faces`() {
        assertNull(CombatTargeting.pickAuto(0f, 0f, listOf(c("away", CombatTargeting.AUTO_RANGE + 50f)), null))
        assertNull(CombatTargeting.pickAuto(0f, 0f, emptyList(), null))
    }

    @Test
    fun `a locked target wins while within reach, even over a boss, and is dropped when far`() {
        val list = listOf(c("locked", 150f), c("boss", 30f, boss = true))
        assertEquals("locked", CombatTargeting.pickAuto(0f, 0f, list, "locked")?.id)
        val farLocked = listOf(c("locked", CombatTargeting.AUTO_RANGE * CombatTargeting.LOCK_KEEP + 20f), c("near", 30f))
        assertEquals("near", CombatTargeting.pickAuto(0f, 0f, farLocked, "locked")?.id)
    }

    @Test
    fun `cycling walks the enemies near to far and wraps`() {
        val list = listOf(c("b", 100f), c("a", 40f), c("c", 180f))
        assertEquals("a", CombatTargeting.cycle(0f, 0f, list, null)?.id)
        assertEquals("b", CombatTargeting.cycle(0f, 0f, list, "a")?.id)
        assertEquals("c", CombatTargeting.cycle(0f, 0f, list, "b")?.id)
        assertEquals("a", CombatTargeting.cycle(0f, 0f, list, "c")?.id)
        assertNull(CombatTargeting.cycle(0f, 0f, emptyList(), null))
    }

    @Test
    fun `an area skill goes to the biggest cluster in reach, not the nearest lone enemy`() {
        val lone = c("lone", 40f, 0f)
        val pack = listOf(c("p1", 160f, 20f), c("p2", 175f, 40f), c("p3", 150f, 55f))
        val centre = CombatTargeting.clusterCenter(0f, 0f, listOf(lone) + pack, reach = 260f, radius = 64f)
        assertNotNull(centre)
        assertTrue(centre!!.first in 140f..190f && centre.second in 10f..60f, "the pack's centre: $centre")
    }

    @Test
    fun `an area skill with nothing in reach has no cluster`() {
        assertNull(CombatTargeting.clusterCenter(0f, 0f, listOf(c("far", 900f)), reach = 260f, radius = 64f))
        assertNull(CombatTargeting.clusterCenter(0f, 0f, emptyList(), reach = 260f, radius = 64f))
    }

    @Test
    fun `a tap picks the enemy under it within a radius`() {
        val list = listOf(c("a", 100f, 100f), c("b", 300f, 100f))
        assertEquals("a", CombatTargeting.nearestTo(110f, 95f, list)?.id)
        assertNull(CombatTargeting.nearestTo(200f, 100f, list))
    }

    @Test
    fun `a drag aims along the drag, with screen up as map up`() {
        val right = CombatTargeting.dragAim(100f, 100f, 50f, 0f, reach = 100f)
        assertNotNull(right)
        assertEquals(200f, right!!.first, 0.01f)
        assertEquals(100f, right.second, 0.01f)
        val up = CombatTargeting.dragAim(100f, 100f, 0f, 30f, reach = 100f)!!
        assertEquals(100f, up.first, 0.01f)
        assertTrue(up.second < 100f, "map y is down, so up on screen is a smaller y")
        assertNull(CombatTargeting.dragAim(0f, 0f, 0f, 0f))
    }
}
