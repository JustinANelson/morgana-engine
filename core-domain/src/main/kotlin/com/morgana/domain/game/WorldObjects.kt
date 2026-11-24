package com.morgana.domain.game

import kotlinx.serialization.Serializable

/**
 * One kind of thing a game lets be placed in the world (`game.json` `worldObjects.kinds`): what it is called, whether it blocks
 * walking, how tough it is. An item that places it names the kind in `ItemDefinition.placesObject`.
 */
@Serializable
data class WorldObjectKind(
    /** Lower-case letters, digits and `_`, 1 to 32 characters. Unique. */
    val id: String,
    /** The name shown to players; [id] when blank. */
    val name: String = "",
    /** True when characters and monsters cannot walk onto its tile. */
    val blocksMovement: Boolean = true,
    /** Hit points a new one has; it is removed when they run out. */
    val maxHp: Int = DEFAULT_MAX_HP,
    /** Free labels a system or script can test (`wall`, `storage`, `claim`). */
    val tags: List<String> = emptyList(),
    /** The tileset the client draws it from (0 = the game's first), and the tile in it. Rendering is not built yet; kept so content can be authored now. */
    val spriteTilesetId: Int = 0,
    val spriteTileX: Int = 0,
    val spriteTileY: Int = 0
) {
    val displayName: String get() = name.ifBlank { id }

    companion object {
        const val DEFAULT_MAX_HP = 100
        const val MAX_HP = 10_000_000
        val ID_PATTERN = Regex("[a-z0-9_]{1,32}")
    }
}

/** The `worldObjects` section of `game.json`: the kinds of thing that can be placed, and how many a map may hold. */
@Serializable
data class WorldObjectsDefinition(
    val kinds: List<WorldObjectKind> = emptyList(),
    /** The most objects one map may hold, so a runaway script or a huge base cannot make a map unmanageable. */
    val maxPerMap: Int = DEFAULT_MAX_PER_MAP
) {
    fun kind(id: String): WorldObjectKind? = kinds.firstOrNull { it.id == id }

    companion object {
        const val DEFAULT_MAX_PER_MAP = 20_000
        const val MAX_PER_MAP = 500_000
        const val MAX_KINDS = 512
    }
}
