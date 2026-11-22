package com.morgana.domain.game

import kotlinx.serialization.Serializable

/**
 * The ghost state. With this section a player who dies stays where they fell as a ghost instead of respawning at once:
 * the ghost can walk around (to find a healer or a shrine) but cannot fight, cast, use items, trade or be hit. A spell with
 * `revivePercent` cast by a living player brings a ghost back on the spot; otherwise the ghost goes home with `/release`, or
 * automatically after [autoReleaseTicks].
 *
 * Arena maps (`safeRespawnInPlace`) and PK jail sentences keep their immediate respawn. Games without this section respawn
 * every death at once as before.
 */
@Serializable
data class GhostDefinition(
    /** Easy kill-switch: set false in `game.json` to go back to instant respawns. */
    val enabled: Boolean = true,
    /** Ticks a ghost may wait for a revive before being released home automatically; 0 = never (they must `/release`). */
    val autoReleaseTicks: Int = 1200,
    /**
     * Durability points every worn piece loses at the moment of death (needs a `durability` section; 0 = no cost). This is the
     * whole death penalty for a game that wants a cheap, predictable one: the death notice states it up front.
     */
    val deathWearPoints: Int = 0,
    /** Ticks after dying before the ghost may `/revive` where they fell, with no healer; 0 = the command is off. */
    val selfReviveAfterTicks: Int = 0,
    /** Percent of maximum life a ghost has after `/revive`. */
    val selfRevivePercent: Int = 30,
    /**
     * Co-op death rule: when every player on a generated dungeon floor (an instanced map with a floor `generator`) is a ghost or
     * dead, they are all sent home at once (full life, their class's start) with "the run is over". Without it ghosts wait on the
     * floor for [autoReleaseTicks].
     */
    val wipeEndsRun: Boolean = false
)
