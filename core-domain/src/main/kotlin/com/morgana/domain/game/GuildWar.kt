package com.morgana.domain.game

import kotlinx.serialization.Serializable

/**
 * Guild War: a timed contest for a guild-held area. While a war is on, members of any guild except the current holder can
 * strike the NPCs whose `guildWarRole` is `POLE` (damage is only counted, the pole never falls) and `GATE` (an ordinary
 * destructible barrier that comes back whole when a war starts or ends). When the war ends the guild that dealt the most
 * pole damage becomes the holder, `GuildDocument.holdsGuildArea`, and its online members receive [rewardItems].
 *
 * The war starts by itself every [intervalTicks] (0 = only when staff type `/guildwar start`) and lasts [durationTicks].
 * Everything runs off the simulation tick, so it replays deterministically. Games without this section have no war.
 */
@Serializable
data class GuildWarDefinition(
    /** Easy kill-switch: set false in `game.json` to disable the war. */
    val enabled: Boolean = true,
    /** The map the war is fought on (where the pole and gates stand); used for the announcement. */
    val mapId: Int = 0,
    /** Ticks between the starts of two scheduled wars; 0 means wars only start by staff command. */
    val intervalTicks: Int = 72_000,
    /** Ticks a war lasts. */
    val durationTicks: Int = 12_000,
    /** Least pole damage a guild must deal to win; below it the holder keeps the area. */
    val minDamage: Int = 1,
    /** Item id to amount given to each online member of the winning guild when the war ends. */
    val rewardItems: Map<Int, Int> = emptyMap()
) {
    companion object {
        /** The values `NpcDefinition.guildWarRole` may take besides blank (not part of the war). */
        val ROLES = setOf("POLE", "GATE")
    }
}
