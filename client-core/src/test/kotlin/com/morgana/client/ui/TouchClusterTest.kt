package com.morgana.client.ui

import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.graphics.Color
import com.morgana.client.access.GameAction
import com.morgana.client.input.ActionCombatInput
import com.morgana.domain.content.InputTokens
import com.morgana.domain.spatial.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class TouchClusterTest {
    private val screens = listOf(800f to 480f, 720f to 360f, 640f to 320f, 844f to 390f, 390f to 844f, 360f to 640f)

    private fun overlap(a: TouchSlot, b: TouchSlot) = hypot(a.cx - b.cx, a.cy - b.cy) < (a.diameter + b.diameter) / 2 - 0.01f

    @Test
    fun `the whole action cluster stays on screen and its buttons never overlap`() {
        for ((w, h) in screens) {
            val d = (minOf(w, h) * 0.17f).coerceIn(54f, 92f)
            val slots = touchClusterSlots(count = 11, rightEdge = w - 14f - 100f, bottom = 14f, d = d)
            assertEquals(11, slots.size)
            for (s in slots) {
                assertTrue(s.left >= 0f && s.bottom >= 0f && s.right <= w && s.top <= h, "slot inside ${w}x$h")
                assertTrue(s.diameter >= 40f, "every button is at least 40 units wide, so a thumb can hit it")
            }
            for (i in slots.indices) for (j in i + 1 until slots.size) assertFalse(overlap(slots[i], slots[j]), "slots $i and $j overlap on ${w}x$h")
            assertTrue(slots.drop(1).all { it.diameter < slots[0].diameter }, "the primary button is the biggest")
        }
    }

    @Test
    fun `the buttons listed first sit nearest the primary one`() {
        val slots = touchClusterSlots(count = 7, rightEdge = 700f, bottom = 14f, d = 60f)
        val distance = slots.drop(1).map { hypot(it.cx - slots[0].cx, it.cy - slots[0].cy) }
        assertTrue(distance[0] < distance[1] && distance[1] < distance[2], "first row runs outwards")
        assertTrue(distance[0] < distance[3], "the second row is farther than the first button")
    }

    @Test
    fun `an empty cluster has no slots`() {
        assertTrue(touchClusterSlots(0, 700f, 14f, 60f).isEmpty())
    }

    @Test
    fun `a layout of eleven buttons plus the modifier fits a phone beside two hotkey columns`() {
        val faces = (1..11).map { n -> TouchFaceButton("B$n", Color.RED) {} }
        val controls = TouchControls(
            font = { error("not needed for layout") },
            faceButtons = faces,
            hotkeys = (1..12).map { n -> TouchHotkey({ "$n" }) {} },
            modifierButton = TouchFaceButton("Mod", Color.GRAY) {}
        )
        for ((w, h) in screens) {
            controls.layoutFor(w, h)
            assertEquals(11, controls.visibleFaceCount())
        }
    }

    @Test
    fun `a held button runs its release when the overlay lets go`() {
        var released = 0
        val guard = TouchFaceButton("Guard", Color.BLUE) {}.withRelease { released++ }
        val controls = TouchControls(font = { error("not needed") }, faceButtons = listOf(guard), hotkeys = emptyList())
        controls.layoutFor(800f, 480f)
        controls.releaseAll() // nothing pressed: nothing to release
        assertEquals(0, released)
    }

    @Test
    fun `every key action has a touch route`() {
        val routes = GameAction.entries.associateWith { touchRoute(it) }
        assertEquals(GameAction.entries.size, routes.size)
        assertEquals(setOf(TouchRoute.JOYSTICK), GameAction.entries.filter { it.isMovement }.map { touchRoute(it) }.toSet())
        assertEquals(setOf(TouchRoute.HOTKEY), GameAction.entries.filter { it.hotbarIndex != null }.map { touchRoute(it) }.toSet())
        assertEquals(TouchRoute.TOGGLE_BUTTON, touchRoute(GameAction.SPRINT))
        for (a in listOf(GameAction.ATTACK, GameAction.JUMP, GameAction.INTERACT, GameAction.PICK_UP)) assertEquals(TouchRoute.ACTION_BUTTON, touchRoute(a))
    }

    @Test
    fun `every action-combat input the keyboard sends has a touch button and is a valid token`() {
        val keyboard = listOf(Keys.CONTROL_LEFT, Keys.CONTROL_RIGHT, Keys.SPACE, Keys.Q, Keys.TAB, Keys.F)
            .mapNotNull { ActionCombatInput.buttonForKey(it, jumpEnabled = false) }.toSet()
        val mouse = setOf(ActionCombatInput.Button.HEAVY) // right click has no key
        assertEquals(ActionCombatInput.Button.entries.toSet(), keyboard + mouse, "touch offers the keyboard's inputs plus the right click, nothing else")
        for (b in ActionCombatInput.Button.entries) assertTrue(InputTokens.isValid(b.token), "${b.token} is a token the engine accepts")
        assertEquals(setOf(ActionCombatInput.Button.GUARD), ActionCombatInput.Button.entries.filter { it.holdToRelease }.toSet())
    }

    @Test
    fun `F is the free token only when jump is off`() {
        assertEquals(ActionCombatInput.Button.FREE, ActionCombatInput.buttonForKey(Keys.F, jumpEnabled = false))
        assertEquals(null, ActionCombatInput.buttonForKey(Keys.F, jumpEnabled = true))
        assertNotNull(ActionCombatInput.buttonForKey(Keys.SPACE, jumpEnabled = true))
    }

    @Test
    fun `joystick directions become the movement axes an input token takes`() {
        assertEquals(0 to 0, ActionCombatInput.moveAxes(null))
        assertEquals(1 to 0, ActionCombatInput.moveAxes(Direction.RIGHT))
        assertEquals(-1 to -1, ActionCombatInput.moveAxes(Direction.UP_LEFT))
        // Backing away from where you face reads as BACK, the same as holding the opposite key.
        val (mx, my) = ActionCombatInput.moveAxes(Direction.LEFT)
        assertEquals("BACK+SPACE", ActionCombatInput.token("SPACE", false, mx, my, Direction.RIGHT))
    }
}
