package com.morgana.client.desktop

/** How big the desktop window opens. */
internal object DesktopWindow {
    /** The size the window used to open at, and still the smallest it opens at when the monitor has room. */
    const val MIN_WIDTH = 1280
    const val MIN_HEIGHT = 768

    /** Share of the monitor the window opens at, so a dense screen does not get a postage stamp. */
    private const val MONITOR_SHARE = 0.8f

    /**
     * The opening size on a [monitorWidth] x [monitorHeight] monitor: 80% of it, but never below 1280 x 768 unless the
     * monitor itself is smaller, and never larger than the monitor.
     */
    fun initialSize(monitorWidth: Int, monitorHeight: Int): Pair<Int, Int> {
        fun side(monitor: Int, min: Int): Int =
            if (monitor <= 0) min else Math.max((monitor * MONITOR_SHARE).toInt(), min).coerceAtMost(monitor)
        return side(monitorWidth, MIN_WIDTH) to side(monitorHeight, MIN_HEIGHT)
    }
}
