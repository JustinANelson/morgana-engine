package com.morgana.client.state

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/** A client-side spirit/essence orb flying from a slain enemy toward the local player. */
data class XpOrb(
    val mapId: Int,
    val startX: Float,
    val startY: Float,
    val arcSign: Float,
    val arcHeight: Float,
    val durationSeconds: Float,
    var ageSeconds: Float = 0f,
    var xpAmount: Long = 0L,
    var floatingText: String? = null,
    var colorHex: String = "#38BDF8"
) {
    val progress: Float get() = if (durationSeconds <= 0f) 1f else (ageSeconds / durationSeconds).coerceIn(0f, 1f)

    /** World position along an accelerating curved path toward ([targetX], [targetY]). */
    fun positionAt(t: Float, targetX: Float, targetY: Float): Pair<Float, Float> {
        val u = t.coerceIn(0f, 1f)
        // Accelerate into the player like magnetic essence.
        val eased = u * u
        val bx = startX + (targetX - startX) * eased
        val by = startY + (targetY - startY) * eased
        val dx = targetX - startX
        val dy = targetY - startY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist < 1f) return bx to by
        val nx = -dy / dist
        val ny = dx / dist
        val offset = sin(u * PI).toFloat() * arcHeight * arcSign
        return (bx + nx * offset) to (by + ny * offset)
    }
}

/** Brief glow pulse on the player when an essence orb is absorbed. */
data class XpAbsorbPulse(
    val mapId: Int,
    var ageSeconds: Float = 0f,
    val durationSeconds: Float = 0.24f
) {
    val progress: Float get() = if (durationSeconds <= 0f) 1f else (ageSeconds / durationSeconds).coerceIn(0f, 1f)
}

/**
 * Coordinates flying essence orbs and client-side visual XP accrual when `ui.xpOrbs` is enabled.
 *
 * The server remains authoritative and grants XP immediately on kill; this tracker simply holds
 * the visual XP delta and `+XP` popup until the orb reaches the player.
 */
class XpOrbTracker {

    private data class DeadNpcMarker(
        val mapId: Int,
        val worldX: Float,
        val worldY: Float,
        var ageSeconds: Float = 0f
    )

    private data class PendingPopup(
        val mapId: Int,
        val worldX: Float,
        val worldY: Float,
        val text: String,
        val colorHex: String,
        var ageSeconds: Float = 0f
    )

    val activeOrbs: MutableList<XpOrb> = mutableListOf()
    val absorbPulses: MutableList<XpAbsorbPulse> = mutableListOf()

    var pendingVisualXp: Long = 0L
        private set

    private val recentDeadNpcs: MutableList<DeadNpcMarker> = mutableListOf()
    private val pendingPopups: MutableList<PendingPopup> = mutableListOf()
    private var spawnCounter: Int = 0

    fun clear() {
        activeOrbs.clear()
        absorbPulses.clear()
        recentDeadNpcs.clear()
        pendingPopups.clear()
        pendingVisualXp = 0L
    }

    /** Records an NPC death on [mapId] at ([worldX], [worldY]). Pairs with any waiting XP popup. */
    fun onNpcKilled(mapId: Int, worldX: Float, worldY: Float) {
        val waitingIdx = pendingPopups.indexOfFirst { it.mapId == mapId }
        if (waitingIdx >= 0) {
            val popup = pendingPopups.removeAt(waitingIdx)
            spawnOrb(mapId, worldX, worldY, popup.text, popup.colorHex)
            return
        }
        recentDeadNpcs.add(DeadNpcMarker(mapId, worldX, worldY))
        if (recentDeadNpcs.size > MAX_RECENT_DEAD) recentDeadNpcs.removeAt(0)
    }

    /**
     * Handles an incoming `XP_GAIN` floating text. Returns `true` if the popup was captured by an
     * essence orb (or queued briefly to pair with an NPC death), or `false` if it should render immediately.
     */
    fun onXpFloatingText(
        mapId: Int,
        worldX: Float,
        worldY: Float,
        text: String,
        colorHex: String,
        enabled: Boolean
    ): Boolean {
        if (!enabled) return false
        val deadIdx = recentDeadNpcs.indexOfFirst { it.mapId == mapId }
        if (deadIdx >= 0) {
            val marker = recentDeadNpcs.removeAt(deadIdx)
            spawnOrb(mapId, marker.worldX, marker.worldY, text, colorHex)
            return true
        }
        pendingPopups.add(PendingPopup(mapId, worldX, worldY, text, colorHex))
        return true
    }

    /**
     * Records a local XP gain from `CharacterSync`. When orbs are active (or an NPC just died this tick),
     * holds [deltaXp] in [pendingVisualXp] until the orb arrives. On level-up, flushes [pendingVisualXp].
     */
    fun onLocalXpChange(prevLevel: Int, prevXp: Long, newLevel: Int, newXp: Long, enabled: Boolean) {
        if (!enabled || newLevel != prevLevel) {
            flushPendingXp()
            return
        }
        val delta = newXp - prevXp
        if (delta <= 0L) return
        val targetOrb = activeOrbs.lastOrNull { it.xpAmount == 0L } ?: activeOrbs.lastOrNull()
        if (targetOrb != null || pendingPopups.isNotEmpty() || recentDeadNpcs.isNotEmpty()) {
            pendingVisualXp += delta
            if (targetOrb != null) {
                targetOrb.xpAmount += delta
            } else {
                // CharacterSync arrived before NpcUpdated/FloatingText; attach when the orb spawns or flush on timeout.
                unassignedPendingXp += delta
            }
        }
    }

    private var unassignedPendingXp: Long = 0L

    private fun spawnOrb(mapId: Int, startX: Float, startY: Float, text: String, colorHex: String) {
        if (activeOrbs.size >= MAX_ACTIVE_ORBS) {
            val evicted = activeOrbs.removeAt(0)
            releaseOrbXp(evicted)
        }
        val idx = spawnCounter++
        val arcSign = if (idx % 2 == 0) 1f else -1f
        val arcHeight = 14f + (idx % 3) * 6f
        val duration = 0.48f + (idx % 3) * 0.07f
        val orb = XpOrb(
            mapId = mapId,
            startX = startX,
            startY = startY,
            arcSign = arcSign,
            arcHeight = arcHeight,
            durationSeconds = duration,
            xpAmount = unassignedPendingXp,
            floatingText = text,
            colorHex = colorHex
        )
        unassignedPendingXp = 0L
        activeOrbs.add(orb)
    }

    /**
     * Advances orbs, pulses, and pairing timeouts by [deltaSeconds].
     * Calls [onOrbArrived] for each orb that reaches the player, and [onFallbackPopup] for any
     * `XP_GAIN` popup that did not correspond to an NPC kill (e.g., quest or crafting XP).
     */
    fun update(
        deltaSeconds: Float,
        onOrbArrived: (XpOrb) -> Unit,
        onFallbackPopup: (mapId: Int, worldX: Float, worldY: Float, text: String, colorHex: String) -> Unit
    ) {
        // Expire unmatched dead-NPC markers
        val deadIt = recentDeadNpcs.iterator()
        while (deadIt.hasNext()) {
            val d = deadIt.next()
            d.ageSeconds += deltaSeconds
            if (d.ageSeconds >= PAIR_TIMEOUT_SECONDS) deadIt.remove()
        }

        // Release unmatched XP popups (e.g., quest / crafting / admin XP) immediately after brief window
        val popupIt = pendingPopups.iterator()
        while (popupIt.hasNext()) {
            val p = popupIt.next()
            p.ageSeconds += deltaSeconds
            if (p.ageSeconds >= PAIR_TIMEOUT_SECONDS) {
                popupIt.remove()
                if (unassignedPendingXp > 0L) {
                    pendingVisualXp = (pendingVisualXp - unassignedPendingXp).coerceAtLeast(0L)
                    unassignedPendingXp = 0L
                }
                onFallbackPopup(p.mapId, p.worldX, p.worldY, p.text, p.colorHex)
            }
        }

        // Advance existing absorb pulses before new arrivals add fresh pulses.
        val pulseIt = absorbPulses.iterator()
        while (pulseIt.hasNext()) {
            val pulse = pulseIt.next()
            pulse.ageSeconds += deltaSeconds
            if (pulse.ageSeconds >= pulse.durationSeconds) pulseIt.remove()
        }

        // Advance active orbs
        val orbIt = activeOrbs.iterator()
        while (orbIt.hasNext()) {
            val orb = orbIt.next()
            orb.ageSeconds += deltaSeconds
            if (orb.ageSeconds >= orb.durationSeconds) {
                orbIt.remove()
                releaseOrbXp(orb)
                absorbPulses.add(XpAbsorbPulse(orb.mapId))
                if (absorbPulses.size > MAX_PULSES) absorbPulses.removeAt(0)
                onOrbArrived(orb)
            }
        }

        // Safety: if no orbs remain and no popups are pairing, ensure zero drift.
        if (activeOrbs.isEmpty() && pendingPopups.isEmpty() && unassignedPendingXp == 0L) {
            pendingVisualXp = 0L
        }
    }

    private fun releaseOrbXp(orb: XpOrb) {
        if (orb.xpAmount > 0L) {
            pendingVisualXp = (pendingVisualXp - orb.xpAmount).coerceAtLeast(0L)
            orb.xpAmount = 0L
        }
    }

    fun flushPendingXp() {
        pendingVisualXp = 0L
        unassignedPendingXp = 0L
        for (orb in activeOrbs) orb.xpAmount = 0L
    }

    companion object {
        const val MAX_ACTIVE_ORBS = 64
        private const val MAX_RECENT_DEAD = 32
        private const val MAX_PULSES = 16
        private const val PAIR_TIMEOUT_SECONDS = 0.16f
    }
}
