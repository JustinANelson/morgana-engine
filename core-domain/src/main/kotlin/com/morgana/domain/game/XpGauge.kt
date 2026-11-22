package com.morgana.domain.game

import kotlinx.serialization.Serializable

/**
 * XP gauge. The gauge is an ordinary vital ([vitalId], declared in `vitals`, usually with a slow passive
 * `regen`); landing a killing blow adds [gainPerKill] to it. Burst skills are ordinary spells that list the vital in
 * their `costs`, so casting one empties the gauge, and a spell with `killExtendTicks` lengthens its running status
 * effect on every kill. Games without this section have no kill-charge.
 */
@Serializable
data class XpGaugeDefinition(
    /** Easy kill-switch: set false in `game.json` to stop kills charging the gauge. */
    val enabled: Boolean = true,
    /** Id of the vital that holds the gauge. */
    val vitalId: String = "xp_gauge",
    /** Gauge points a kill adds (capped at the vital's maximum). */
    val gainPerKill: Int = 5
)
