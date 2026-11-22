package com.morgana.client.net

/**
 * Small phone-only conveniences the shared client asks for and a platform may provide: a short buzz for hits and
 * level-ups, and keeping the screen awake while playing. Every default does nothing, so desktop and tests need no
 * implementation.
 */
interface DeviceFeatures {
    /** Vibrates for [millis] ms (a pattern is not needed here). Ignored where the device cannot. */
    fun vibrate(millis: Int) {}

    /** Asks the device not to dim or lock the screen while [on] (the player is in the world). */
    fun keepScreenOn(on: Boolean) {}

    /**
     * Called when the shared client hits an unexpected error that repeats every frame, so the platform can show its own
     * message (the page cannot rely on the broken frame loop to draw one). Returns true when it showed something.
     */
    fun showFatalError(message: String): Boolean = false

    companion object {
        val NONE: DeviceFeatures = object : DeviceFeatures {}
    }
}
