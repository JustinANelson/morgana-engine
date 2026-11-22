package com.morgana.domain.game

import kotlinx.serialization.Serializable

/**
 * Arena matchmaking (`arena` in `game.json`): players queue with `/arena join [team size]`, the server pairs players of
 * similar rating into two teams, and each match is fought in its own copy of [mapId]. Nothing is lost by dying there:
 * a player who falls leaves the match with full life, and the last team standing wins. Wins and losses move a rating
 * (Elo style). Games without this section have no arena queue.
 */
@Serializable
data class ArenaDefinition(
    val enabled: Boolean = true,
    /** The arena map: an instanced map (ideally `ARENA` moral, so PvP is on) that is copied for every match. */
    val mapId: Int,
    /** Team sizes players may queue for (a size of 2 means two against two). */
    val teamSizes: List<Int> = listOf(1, 2, 3),
    /** Where team A and team B start; players take the spots in order. */
    val teamASpawns: List<ArenaSpawn> = emptyList(),
    val teamBSpawns: List<ArenaSpawn> = emptyList(),
    /** Ticks between the teams arriving and the fight starting; nobody can be hurt until then. */
    val countdownTicks: Int = 100,
    /** Ticks a fight may last; then the team with more life left wins, and an equal one draws. */
    val matchTicks: Int = 3600,
    /** The rating of a player who has never fought. */
    val startRating: Int = 1000,
    /** How far a rating moves after a match (the usual Elo K factor). */
    val ratingK: Int = 24,
    /** The widest gap between the best and worst rating in one match at first; it grows by [gapGrowth] every [gapGrowthTicks] the oldest ticket has waited. */
    val maxRatingGap: Int = 200,
    val gapGrowth: Int = 100,
    val gapGrowthTicks: Int = 600,
    /** Item id to amount given to each member of the winning team. */
    val winnerRewards: Map<Int, Int> = emptyMap(),
    /** Item id to amount given to each member of the losing team. */
    val loserRewards: Map<Int, Int> = emptyMap()
)

/** A tile in the arena map where a team member starts. */
@Serializable
data class ArenaSpawn(val x: Int, val y: Int)
