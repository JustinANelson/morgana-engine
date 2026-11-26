package com.morgana.client.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class ActionRingLayoutTest {
    private val screens = listOf(800f to 480f, 720f to 360f, 640f to 320f, 844f to 390f, 390f to 844f, 360f to 640f, 1280f to 768f)
    private val core = listOf(RingRole.ATTACK, RingRole.MORE, RingRole.DODGE, RingRole.CONTEXT, RingRole.JUMP)

    private fun layout(w: Float, h: Float): Map<RingKey, TouchSlot> =
        ringLayout(w - 14f, 14f, (minOf(w, h) * 0.17f).coerceIn(54f, 92f))

    private fun coreKeys() = core.map { RingKey(it) } + (0 until RING_SKILLS).map { RingKey(RingRole.SKILL, it) }

    @Test
    fun `the core buttons stay on screen and no two buttons overlap`() {
        for ((w, h) in screens) {
            val slots = layout(w, h)
            for (k in coreKeys()) {
                val s = slots.getValue(k)
                assertTrue(s.left >= 0f && s.bottom >= 13.9f && s.right <= w - 13.9f && s.top <= h, "$k inside ${w}x$h")
                assertTrue(s.diameter >= 36f, "$k is big enough to tap")
            }
            val all = slots.entries.toList()
            for (i in all.indices) for (j in i + 1 until all.size) {
                val a = all[i].value; val b = all[j].value
                assertFalse(hypot(a.cx - b.cx, a.cy - b.cy) < (a.diameter + b.diameter) / 2 - 0.01f, "${all[i].key} overlaps ${all[j].key} on ${w}x$h")
            }
        }
    }

    @Test
    fun `the attack is the biggest button and the skills follow an arc from the thumb upwards`() {
        val slots = layout(844f, 390f)
        val attack = slots.getValue(RingKey(RingRole.ATTACK))
        assertTrue(slots.filterKeys { it.role != RingRole.ATTACK }.values.all { it.diameter < attack.diameter })
        val skills = (0 until RING_SKILLS).map { slots.getValue(RingKey(RingRole.SKILL, it)) }
        assertTrue(skills.zipWithNext().all { (a, b) -> b.cx > a.cx }, "the skills sweep from the left round to the top")
        assertTrue(skills.drop(1).all { it.cy > skills[0].cy }, "the first skill, nearest the thumb, is the lowest")
        assertTrue(skills.all { it.cx < attack.cx + attack.diameter / 2 }, "skills are not past the attack's right edge")
        assertEquals(RING_SKILLS, skills.size)
    }
}
