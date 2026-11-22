package com.morgana.client.ui

/** A window's rectangle in stage units: [x] and [y] are the bottom-left corner (y up), as in Scene2D. */
data class WindowRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val top: Float get() = y + height
}

/**
 * The pure rules for where HUD windows sit: keep them on screen, snap them to screen edges and to each other while they
 * are dragged, and write down where one was left so it comes back there. No graphics, so every rule is unit-tested.
 */
object WindowPlacement {
    /** How close (in stage units) an edge must be to another edge before it snaps to it. */
    const val SNAP_DISTANCE = 10f

    /** [rect] moved the least amount needed to lie inside the stage; a window larger than the stage is pinned to its top-left. */
    fun clamp(rect: WindowRect, stageWidth: Float, stageHeight: Float): WindowRect {
        val x = if (rect.width >= stageWidth) 0f else rect.x.coerceIn(0f, stageWidth - rect.width)
        val y = if (rect.height >= stageHeight) stageHeight - rect.height else rect.y.coerceIn(0f, stageHeight - rect.height)
        return rect.copy(x = x, y = y)
    }

    /**
     * [rect] after snapping: its edges jump to the stage's edges and to the edges of [others] that lie within [distance].
     * Horizontal and vertical are decided separately, each taking the closest candidate, so a window can snap to a corner.
     * Edges are only matched where the two windows actually overlap along the other axis, so a window does not snap to
     * one on the far side of the screen.
     */
    fun snap(rect: WindowRect, stageWidth: Float, stageHeight: Float, others: List<WindowRect>, distance: Float = SNAP_DISTANCE): WindowRect {
        var bestDx = Float.MAX_VALUE
        var bestDy = Float.MAX_VALUE
        fun tryX(delta: Float) { if (Math.abs(delta) <= distance && Math.abs(delta) < Math.abs(bestDx)) bestDx = delta }
        fun tryY(delta: Float) { if (Math.abs(delta) <= distance && Math.abs(delta) < Math.abs(bestDy)) bestDy = delta }

        tryX(0f - rect.x)
        tryX(stageWidth - rect.right)
        tryY(0f - rect.y)
        tryY(stageHeight - rect.top)
        for (o in others) {
            val overlapsY = rect.y < o.top + distance && rect.top > o.y - distance
            val overlapsX = rect.x < o.right + distance && rect.right > o.x - distance
            if (overlapsY) {
                tryX(o.right - rect.x) // my left edge to their right edge
                tryX(o.x - rect.right) // my right edge to their left edge
                tryX(o.x - rect.x) // left edges line up
                tryX(o.right - rect.right) // right edges line up
            }
            if (overlapsX) {
                tryY(o.top - rect.y)
                tryY(o.y - rect.top)
                tryY(o.y - rect.y)
                tryY(o.top - rect.top)
            }
        }
        return rect.copy(
            x = if (bestDx == Float.MAX_VALUE) rect.x else rect.x + bestDx,
            y = if (bestDy == Float.MAX_VALUE) rect.y else rect.y + bestDy
        )
    }

    /**
     * The text saved for a window: its left edge and its top edge, so a window that is collapsed (shorter) when saved still
     * comes back with its title bar in the same place.
     */
    fun format(rect: WindowRect): String = "${Math.round(rect.x)},${Math.round(rect.top)}"

    /**
     * Where a saved window goes now that it is [width] by [height]: the saved left and top edges, kept on screen.
     * Null when [saved] is missing or not in the format [format] writes.
     */
    fun restore(saved: String?, width: Float, height: Float, stageWidth: Float, stageHeight: Float): WindowRect? {
        val parts = saved?.split(',') ?: return null
        if (parts.size != 2) return null
        val x = parts[0].trim().toFloatOrNull() ?: return null
        val top = parts[1].trim().toFloatOrNull() ?: return null
        return clamp(WindowRect(x, top - height, width, height), stageWidth, stageHeight)
    }
}
