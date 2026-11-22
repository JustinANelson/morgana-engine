package com.morgana.client.net

/** How the player's connection feels right now, for the small indicator and its colour. */
enum class LinkLevel(val label: String) {
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor"),

    /** No connection, or one being re-established. */
    LOST("No signal")
}

/** The indicator's content: [bars] out of 4 and a short [text] such as "42 ms". */
data class LinkQuality(val level: LinkLevel, val bars: Int, val text: String) {
    companion object {
        const val GOOD_BELOW_MS = 150L
        const val FAIR_BELOW_MS = 350L

        /**
         * Judges the link from the latest ping round trip ([rttMs], 0 before the first answer), whether the send gate
         * calls the link [stalled], and whether the connection is [connected] at all. Pure, so it is unit-tested.
         */
        fun of(rttMs: Long, stalled: Boolean, connected: Boolean): LinkQuality = when {
            !connected -> LinkQuality(LinkLevel.LOST, 0, "offline")
            stalled -> LinkQuality(LinkLevel.POOR, 1, "slow")
            rttMs <= 0L -> LinkQuality(LinkLevel.GOOD, 3, "...")
            rttMs < GOOD_BELOW_MS -> LinkQuality(LinkLevel.GOOD, 4, "$rttMs ms")
            rttMs < FAIR_BELOW_MS -> LinkQuality(LinkLevel.FAIR, 3, "$rttMs ms")
            else -> LinkQuality(LinkLevel.POOR, if (rttMs < 800L) 2 else 1, "$rttMs ms")
        }
    }
}
