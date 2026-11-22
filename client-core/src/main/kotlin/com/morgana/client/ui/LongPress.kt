package com.morgana.client.ui

/**
 * Detects a press that is held in place: [begin] on touch down, [update] every frame, [end] on release.
 * Moving further than [slop] from where the press began cancels it (the player is scrolling or dragging, not holding).
 * Pure and time-driven by the deltas it is given, so it is unit-tested.
 */
class LongPress(private val thresholdSeconds: Float = 0.45f, private val slop: Float = 10f) {
    private var active = false
    private var fired = false
    private var heldSeconds = 0f
    private var startX = 0f
    private var startY = 0f

    /** True from a long press firing until the finger lifts. */
    val isFired: Boolean get() = fired

    fun begin(x: Float, y: Float) {
        active = true
        fired = false
        heldSeconds = 0f
        startX = x
        startY = y
    }

    /** Moves the press; a move past the slop cancels it unless it has already fired. */
    fun move(x: Float, y: Float) {
        if (!active || fired) return
        val dx = x - startX
        val dy = y - startY
        if (dx * dx + dy * dy > slop * slop) active = false
    }

    /** Advances time; true exactly once, on the frame the hold reaches the threshold. */
    fun update(deltaSeconds: Float): Boolean {
        if (!active || fired) return false
        heldSeconds += deltaSeconds
        if (heldSeconds < thresholdSeconds) return false
        fired = true
        return true
    }

    /** Ends the press; true when it had fired, so the caller can swallow the tap that would otherwise follow. */
    fun end(): Boolean {
        val wasFired = fired
        active = false
        fired = false
        heldSeconds = 0f
        return wasFired
    }
}
