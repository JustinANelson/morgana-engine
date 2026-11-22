package com.morgana.client.input

import com.morgana.domain.content.InputTokens
import com.morgana.domain.spatial.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActionCombatInputTest {
    @Test
    fun `movement is turned into a direction relative to where the character faces`() {
        assertEquals(null, ActionCombatInput.direction(0, 0, Direction.RIGHT))
        assertEquals("FORWARD", ActionCombatInput.direction(1, 0, Direction.RIGHT))
        assertEquals("BACK", ActionCombatInput.direction(-1, 0, Direction.RIGHT))
        assertEquals("RIGHT", ActionCombatInput.direction(0, 1, Direction.RIGHT), "facing right, the right-hand side is down the screen")
        assertEquals("LEFT", ActionCombatInput.direction(0, -1, Direction.RIGHT))
        assertEquals("FORWARD", ActionCombatInput.direction(0, -1, Direction.UP))
        assertEquals("LEFT", ActionCombatInput.direction(-1, 0, Direction.UP))
        assertEquals("FORWARD", ActionCombatInput.direction(1, -1, Direction.UP_RIGHT))
    }

    @Test
    fun `every token the client builds is one the engine accepts`() {
        for (key in listOf("LMB", "RMB", "SPACE", "Q", "F", "TAB")) for (shift in listOf(false, true)) for (mx in -1..1) for (my in -1..1) for (f in Direction.entries) {
            val token = ActionCombatInput.token(key, shift, mx, my, f)
            assertTrue(InputTokens.isValid(token), "$token is a valid token")
        }
        assertEquals("SHIFT+BACK+SPACE", ActionCombatInput.token("SPACE", true, -1, 0, Direction.RIGHT))
    }

    @Test
    fun `aim pixels flip the renderer's y and the packet carries token and aim`() {
        assertEquals(100f to (384f - 40f), ActionCombatInput.aimPixels(100f, 40f, 384))
        val p = ActionCombatInput.packet("LMB", 10f to 20f)
        assertEquals("input", p.actionId)
        assertEquals("LMB", p.params["token"])
        assertEquals("10.0", p.params["aimX"])
        assertEquals(setOf("token"), ActionCombatInput.packet("RMB", null).params.keys)
        assertEquals("release", ActionCombatInput.release().actionId)
    }
}
