package com.morgana.domain.game

import kotlinx.serialization.Serializable

/** What one level of a claim covers: how far it reaches and how much may be built inside it. */
@Serializable
data class ClaimLevel(
    /** Tiles from the claim's centre in every direction (a square of `2r + 1` tiles). */
    val radiusTiles: Int = 10,
    /** Most pieces (world objects that are not claims) that may stand inside it. */
    val maxPieces: Int = 150
)

/**
 * A world object kind that claims land (`game.json` `territory.claimKinds`): a totem, a banner, a deed post. Placing one marks the
 * ground around it as its owner's; only the owner (and, for a guild claim, the guild, plus anyone the owner allows) may build there.
 */
@Serializable
data class ClaimKindDefinition(
    /** A `worldObjects.kinds[].id`. */
    val kind: String,
    /** Level 1 is the first entry; a claim can be upgraded up to the last. */
    val levels: List<ClaimLevel> = listOf(ClaimLevel()),
    /** True when the claim belongs to the placer's guild (they must be in one) and every member may build in it. */
    val guild: Boolean = false,
    /** True when nobody can attack anybody inside the claim, except while an attack window is open. */
    val safeZone: Boolean = true,
    /** How many claims of this kind one character (or, for a guild claim, one guild) may hold on a map. */
    val maxPerOwner: Int = 1
) {
    /** The level definition for a claim at [level] (1 or more), capped at the last. */
    fun level(level: Int): ClaimLevel = levels[(level - 1).coerceIn(0, levels.lastIndex)]
}

/** The `territory` section of `game.json`: claiming land and what that allows. Needs the `worldState` system and the kinds it names. */
@Serializable
data class TerritoryDefinition(
    val claimKinds: List<ClaimKindDefinition> = emptyList(),
    /** Nothing may be placed within this many tiles of a resource node or a monster's spawn tile (0 = no such rule). */
    val noBuildRadiusTiles: Int = 0,
    /** Extra tiles that must separate two claims beyond their radii: claims need `radius + radius + this` between their centres. */
    val minClaimSpacingTiles: Int = 0
) {
    fun claimKind(id: String): ClaimKindDefinition? = claimKinds.firstOrNull { it.kind == id }

    companion object {
        const val MAX_RADIUS = 200
        const val MAX_PIECES = 100_000
        const val MAX_LEVELS = 20
        const val MAX_NO_BUILD_RADIUS = 64
        const val MAX_SPACING = 500
        const val MAX_PER_OWNER = 1_000
    }
}
