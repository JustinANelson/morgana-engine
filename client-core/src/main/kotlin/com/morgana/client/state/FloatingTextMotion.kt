package com.morgana.client.state

/** How floating combat text moves: it pops in large and settles, then fades out. Pure, so it is unit-tested. */
object FloatingTextMotion {
    const val POP_SECONDS = 0.18f
    const val FADE_SECONDS = 0.4f

    /** Size multiplier [ageSeconds] after the text appeared: starts about 1.7x (crits larger) and settles to the base size. */
    fun scale(ageSeconds: Float, crit: Boolean): Float {
        val base = if (crit) 1.35f else 1f
        val t = (ageSeconds / POP_SECONDS).coerceIn(0f, 1f)
        val inv = 1f - t
        return base * (1f + 0.7f * inv * inv)
    }

    /** Opacity with [remainingSeconds] left: 1 until the last [FADE_SECONDS], then fading to 0. */
    fun alpha(remainingSeconds: Float): Float = (remainingSeconds / FADE_SECONDS).coerceIn(0f, 1f)
}
