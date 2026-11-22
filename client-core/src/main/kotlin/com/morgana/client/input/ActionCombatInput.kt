package com.morgana.client.input

import com.morgana.domain.game.GameDefinition
import com.morgana.domain.spatial.Direction
import com.morgana.protocol.ClientPacket

/**
 * What the client sends in action combat (`combat.module` `action`): every attack key press becomes an input token
 * (`LMB`, `RMB`, `SPACE`, `Q`, `F`, `TAB`, with `SHIFT+`, `CTRL+` and a direction relative to the facing) in a `c_action` `input`
 * packet, aimed at the cursor when the mouse started it. Pure, so the rules are unit-tested; `MorganaGame` calls it.
 *
 * Controls: left click or Ctrl = `LMB`, right click = `RMB`, Space = `SPACE` (a dodge in most sets), `Q` = `Q` (a held guard:
 * releasing the key sends `release`), `F` = `F` when jump is off, Tab = `TAB`. Holding Shift adds `SHIFT+`; holding a movement
 * key adds `FORWARD+`, `BACK+`, `LEFT+` or `RIGHT+` relative to where the character faces.
 */
object ActionCombatInput {
    val MODULE_IDS = setOf("action", "action_combat")

    fun isActive(game: GameDefinition?): Boolean = game?.combat?.module?.lowercase() in MODULE_IDS

    /**
     * The token for [key] with the modifiers and movement held. [moveX] and [moveY] are -1, 0 or 1 (screen axes, y down);
     * a diagonal counts as the nearer of forward and back, else sideways.
     */
    fun token(key: String, shift: Boolean, moveX: Int, moveY: Int, facing: Direction): String {
        val parts = ArrayList<String>()
        if (shift) parts += "SHIFT"
        direction(moveX, moveY, facing)?.let { parts += it }
        parts += key
        return parts.joinToString("+")
    }

    /** `FORWARD`, `BACK`, `LEFT` or `RIGHT` relative to [facing] for a held movement, or null when not moving. */
    fun direction(moveX: Int, moveY: Int, facing: Direction): String? {
        if (moveX == 0 && moveY == 0) return null
        val fl = kotlin.math.sqrt((facing.dx * facing.dx + facing.dy * facing.dy).toDouble())
        val fx = facing.dx / fl
        val fy = facing.dy / fl
        val ml = kotlin.math.sqrt((moveX * moveX + moveY * moveY).toDouble())
        val mx = moveX / ml
        val my = moveY / ml
        val dot = fx * mx + fy * my
        // The facing's right-hand side on a y-down screen is (-fy, fx).
        val side = -fy * mx + fx * my
        return when {
            dot >= 0.5 -> "FORWARD"
            dot <= -0.5 -> "BACK"
            side > 0 -> "RIGHT"
            else -> "LEFT"
        }
    }

    /** The world pixel (y down, as the simulation counts) under a cursor whose world position is ([worldX], [worldY]) on a map [mapHeightPixels] tall. */
    fun aimPixels(worldX: Float, worldY: Float, mapHeightPixels: Int): Pair<Float, Float> = worldX to (mapHeightPixels - worldY)

    fun packet(token: String, aim: Pair<Float, Float>?): ClientPacket.CustomAction =
        ClientPacket.CustomAction("input", buildMap {
            put("token", token)
            if (aim != null) {
                put("aimX", aim.first.toString())
                put("aimY", aim.second.toString())
            }
        })

    fun release(): ClientPacket.CustomAction = ClientPacket.CustomAction("release")
}

/**
 * Pure hold-to-attack repeat timer for action combat (and realtime combat): while the attack button or key is held,
 * fires again at [intervalSeconds] without flooding packets on high-FPS frames.
 */
class HoldAttackRepeat(private val intervalSeconds: Float = 0.15f) {
    private var timer: Float = 0f

    /** Call when the initial press fired an attack so the repeat starts after a full interval. */
    fun markFired() {
        timer = intervalSeconds
    }

    /** Call when the button/key is released or combat is inactive. */
    fun reset() {
        timer = 0f
    }

    /** Advance by [deltaSeconds] while [held] is true; returns true when another attack should fire. */
    fun update(held: Boolean, deltaSeconds: Float): Boolean {
        if (!held) {
            timer = 0f
            return false
        }
        timer -= deltaSeconds
        if (timer <= 0f) {
            timer = if (timer > -intervalSeconds) timer + intervalSeconds else intervalSeconds
            return true
        }
        return false
    }
}
