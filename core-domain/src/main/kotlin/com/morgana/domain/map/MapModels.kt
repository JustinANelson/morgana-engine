package com.morgana.domain.map

import com.morgana.domain.content.ContentSchema

import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.TileCoord
import com.morgana.domain.spatial.WorldPosition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Rendering layers in the 6-layer Morgana map stack.
 * GROUND, MASK_1, MASK_2 render below entities;
 * FRINGE_1, FRINGE_2, ROOF render above entities (ROOF fades when local player stands underneath).
 */
@Serializable
enum class MapLayer(val renderOrder: Int, val aboveEntities: Boolean) {
    GROUND(0, false),
    MASK_1(1, false),
    MASK_2(2, false),
    FRINGE_1(3, true),
    FRINGE_2(4, true),
    ROOF(5, true)
}

/**
 * Autotile and animated tile modes supported by the renderer and live F1 Map Editor.
 */
@Serializable
enum class AutotileMode {
    NONE,
    RMXP_47,
    RMVX_16,
    ANIMATED_3_FRAME,
    WATERFALL
}

/**
 * Visual tile placement on a specific [MapLayer]. With [animationFrames] above 1 the tile animates: see [TileAnimation]
 * (frames are the tiles to the right of [srcTileX], [animationFrameStep] apart, each shown for [animationSpeedMs]).
 */
@Serializable
data class TilePlacement(
    val tilesetId: Int = 1,
    val srcTileX: Int = 0,
    val srcTileY: Int = 0,
    val autotileMode: AutotileMode = AutotileMode.NONE,
    val animationFrames: Int = 1,
    val animationSpeedMs: Int = 250,
    val roofGroupId: Int = 0,
    /** Tiles between one frame and the next on the sheet: 1 for a single tile, the object's width for a multi-tile one. */
    val animationFrameStep: Int = 1
)

/**
 * Vital targeted by a [TileAttribute.HealTrap].
 */
@Serializable
enum class VitalType {
    HP,
    MP,
    SP
}

/**
 * Rich tile attributes for Morgana maps, resource nodes, and dynamic 2D lighting/sound.
 */
@Serializable
sealed interface TileAttribute {
    @Serializable
    @SerialName("none")
    data object None : TileAttribute

    @Serializable
    @SerialName("blocked")
    data object Blocked : TileAttribute

    @Serializable
    @SerialName("dir_block")
    data class DirectionalBlock(
        val blockUp: Boolean = false,
        val blockDown: Boolean = false,
        val blockLeft: Boolean = false,
        val blockRight: Boolean = false
    ) : TileAttribute {
        fun isExitBlocked(direction: Direction): Boolean = when (direction) {
            Direction.UP -> blockUp
            Direction.DOWN -> blockDown
            Direction.LEFT -> blockLeft
            Direction.RIGHT -> blockRight
            Direction.UP_LEFT -> blockUp || blockLeft
            Direction.UP_RIGHT -> blockUp || blockRight
            Direction.DOWN_LEFT -> blockDown || blockLeft
            Direction.DOWN_RIGHT -> blockDown || blockRight
        }

        fun isEntryBlockedFrom(moveDirection: Direction): Boolean =
            isExitBlocked(moveDirection.opposite())
    }

    @Serializable
    @SerialName("warp")
    data class Warp(
        val targetMapId: Int,
        val targetX: Int,
        val targetY: Int,
        val targetDirection: Direction? = null
    ) : TileAttribute

    @Serializable
    @SerialName("item_spawn")
    data class ItemSpawn(
        val itemId: Int,
        val amount: Int = 1,
        val respawnTicks: Int = 200
    ) : TileAttribute

    @Serializable
    @SerialName("npc_avoid")
    data object NpcAvoid : TileAttribute

    @Serializable
    @SerialName("key_door")
    data class KeyDoor(
        val keyItemId: Int,
        val consumeKey: Boolean = false,
        val unlockDurationTicks: Int = 100
    ) : TileAttribute

    @Serializable
    @SerialName("resource_node")
    data class ResourceNode(
        val resourceId: Int
    ) : TileAttribute

    @Serializable
    @SerialName("shop")
    data class ShopTile(
        val shopId: Int
    ) : TileAttribute

    @Serializable
    @SerialName("bank")
    data object BankTile : TileAttribute

    @Serializable
    @SerialName("heal_trap")
    data class HealTrap(
        val vitalType: VitalType = VitalType.HP,
        val amount: Int = 10,
        val isTrapDamage: Boolean = false,
        val intervalTicks: Int = 20,
        val animationId: Int = 0
    ) : TileAttribute

    @Serializable
    @SerialName("slide")
    data class Slide(
        val direction: Direction
    ) : TileAttribute

    @Serializable
    @SerialName("light_source")
    data class LightSource(
        val radiusPixels: Float = 128f,
        val colorHex: String = "#FFAA44",
        val intensity: Float = 0.85f,
        val flicker: Boolean = true
    ) : TileAttribute

    @Serializable
    @SerialName("sound_emitter")
    data class SoundEmitter(
        val soundId: String,
        val radiusTiles: Int = 8,
        val intervalTicks: Int = 60
    ) : TileAttribute
}

/** Who shares one copy of an instanced map. */
@Serializable
enum class InstanceOwner {
    /** The party (or the lone player): dungeons and floors. */
    PARTY,

    /** The guild: only its members can enter, and every member shares the one copy (a guild hall). */
    GUILD
}

/**
 * Map moral / ruleset governing PvP and death penalties.
 */
@Serializable
enum class MapMoral(
    val displayName: String,
    val pvpEnabled: Boolean,
    val dropItemsOnDeath: Boolean,
    val safeRespawnInPlace: Boolean = false
) {
    SAFE_ZONE("Safe Zone", pvpEnabled = false, dropItemsOnDeath = false),
    OPEN_PVP("Contested (PvP)", pvpEnabled = true, dropItemsOnDeath = true),
    ARENA("Arena (No Drop PvP)", pvpEnabled = true, dropItemsOnDeath = false, safeRespawnInPlace = true),
    INSTANCED_DUNGEON("Dungeon", pvpEnabled = false, dropItemsOnDeath = false)
}

/**
 * Weather particle system presets.
 */
@Serializable
enum class WeatherType {
    NONE,
    RAIN,
    SNOW,
    FOG,
    ASH
}

/**
 * N/S/E/W map border connections for seamless map stitching and cross-border movement.
 * A value of 0 means no adjacent map in that direction.
 */
@Serializable
data class MapNeighborLinks(
    val northMapId: Int = 0,
    val southMapId: Int = 0,
    val eastMapId: Int = 0,
    val westMapId: Int = 0
) {
    fun neighborFor(direction: Direction): Int = when (direction) {
        Direction.UP -> northMapId
        Direction.DOWN -> southMapId
        Direction.LEFT -> westMapId
        Direction.RIGHT -> eastMapId
        else -> 0
    }
}

/**
 * NPC spawn configuration placed on a map.
 */
@Serializable
data class MapNpcSpawnSlot(
    val slotIndex: Int,
    val npcId: Int,
    val spawnX: Int = 0,
    val spawnY: Int = 0,
    val respawnTicks: Int = 100
)

/**
 * Single grid cell on a map containing layer placements and a tile attribute.
 */
@Serializable
data class MapCell(
    val layers: Map<MapLayer, TilePlacement> = emptyMap(),
    val attribute: TileAttribute = TileAttribute.None
)

/**
 * Complete authoritative map definition (serializable to JSON/MongoDB and editable live via F1).
 */
@Serializable
data class MapDefinition(
    val id: Int,
    val name: String,
    val revision: Int = 1,
    val width: Int = 24,
    val height: Int = 18,
    val tileSize: Int = WorldPosition.DEFAULT_TILE_SIZE,
    val moral: MapMoral = MapMoral.SAFE_ZONE,
    val bgmTrackId: String = "town_theme",
    /**
     * Legacy fixed weather. Weather is now a world/zone feature (see `WeatherDefinition` in the game definition); these two
     * values are used only by games that define no weather zones, so older worlds look the same. The editor no longer sets them.
     */
    val weather: WeatherType = WeatherType.NONE,
    val weatherIntensity: Float = 0.5f,
    val ambientLightHex: String = "#FFFFFF",
    /** Interior (true) or exterior (false). Interiors are not touched by weather or the day and night cycle. */
    val isIndoors: Boolean = false,
    /** The weather zone this map belongs to (0 = the default world zone). Only matters for exterior maps. */
    val zoneId: Int = 0,
    /** Marks a map that is meant to be copied per party or player. Stored and validated now; the copying itself comes later. */
    val instanced: Boolean = false,
    /** Whose copy an [instanced] map is: the entering player's party (or themselves), or their guild (guild halls). */
    val instanceOwner: InstanceOwner = InstanceOwner.PARTY,
    val neighbors: MapNeighborLinks = MapNeighborLinks(),
    val cells: List<MapCell> = List(width * height) { MapCell() },
    val npcSpawns: List<MapNpcSpawnSlot> = emptyList(),
    val eventIds: List<Int> = emptyList(),
    /** On-disk document schema version; see [ContentSchema]. */
    val schemaVersion: Int = ContentSchema.CURRENT_VERSION,
    /**
     * Makes an [instanced] map a procedurally generated floor template: each copy is generated from this recipe instead
     * of copying the map's tiles (see [FloorGenerator]). Null for an ordinary map.
     */
    val generator: FloorGenerator? = null,
    /** Makes the monsters of each copy of an [instanced] map tougher for bigger or higher-level parties; null for no scaling. */
    val scaling: InstanceScaling? = null
) {
    init {
        require(width in 5..256) { "Map width must be between 5 and 256 (got $width)" }
        require(height in 5..256) { "Map height must be between 5 and 256 (got $height)" }
        require(tileSize == 32) { "Map tileSize must be 32 (got $tileSize)" }
        require(cells.size == width * height) {
            "Map cells size (${cells.size}) must equal width * height (${width * height})"
        }
    }

    fun isInBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    fun isInBounds(coord: TileCoord): Boolean = isInBounds(coord.x, coord.y)

    fun cellIndex(x: Int, y: Int): Int = y * width + x

    fun getCell(x: Int, y: Int): MapCell? =
        if (isInBounds(x, y)) cells[cellIndex(x, y)] else null

    fun getCell(coord: TileCoord): MapCell? = getCell(coord.x, coord.y)

    fun withCell(x: Int, y: Int, newCell: MapCell, bumpRevision: Boolean = true): MapDefinition {
        require(isInBounds(x, y)) { "Coordinates ($x, $y) out of bounds for map $id ($width x $height)" }
        val updated = cells.toMutableList()
        updated[cellIndex(x, y)] = newCell
        return copy(
            revision = if (bumpRevision) revision + 1 else revision,
            cells = updated
        )
    }

    /**
     * Checks whether an entity can step from [from] to [to] in [direction].
     */
    fun canTraverse(
        from: TileCoord,
        to: TileCoord,
        direction: Direction,
        isNpc: Boolean = false,
        unlockedDoors: Set<TileCoord> = emptySet(),
        depletedResources: Set<TileCoord> = emptySet()
    ): Boolean {
        if (!isInBounds(to)) return false
        val sourceCell = getCell(from)
        if (sourceCell != null) {
            val srcAttr = sourceCell.attribute
            if (srcAttr is TileAttribute.DirectionalBlock && srcAttr.isExitBlocked(direction)) {
                return false
            }
        }
        val targetCell = getCell(to) ?: return false
        return when (val attr = targetCell.attribute) {
            is TileAttribute.Blocked -> false
            is TileAttribute.DirectionalBlock -> !attr.isEntryBlockedFrom(direction)
            is TileAttribute.NpcAvoid -> !isNpc
            is TileAttribute.KeyDoor -> to in unlockedDoors
            is TileAttribute.ResourceNode -> to in depletedResources
            else -> true
        }
    }
}

/**
 * How an [MapDefinition.instanced] dungeon toughens its monsters for the party inside (`scaling` on the map). It looks at
 * the players in the copy when they walk in: each one beyond the first adds [hpPercentPerMember] to monster life,
 * [attackPercentPerMember] to their damage and [xpPercentPerMember] to their XP reward, counting at most [maxMembers]
 * players. With [levelFollowsMembers] the monsters also follow the party's average level, [percentPerLevel] percent of
 * life and damage for each level they differ from the monster's own, at most [maxLevelShift] levels either way.
 *
 * A monster is rescaled when more players arrive only while it is untouched (full life, no target); the dungeon grows
 * with the party and never shrinks mid-run. Pure arithmetic in [scaleFor].
 */
@Serializable
data class InstanceScaling(
    val hpPercentPerMember: Int = 60,
    val attackPercentPerMember: Int = 15,
    val xpPercentPerMember: Int = 0,
    val maxMembers: Int = 5,
    val levelFollowsMembers: Boolean = false,
    val percentPerLevel: Int = 4,
    val maxLevelShift: Int = 20,
    /** Difficulty levels the player who opens the dungeon can pick, easiest first; empty means one difficulty. */
    val tiers: List<DifficultyTier> = emptyList(),
    /** The player number that picks the tier (an NPC's dialogue sets it before the warp): 0 is the first tier. */
    val tierVariable: String = "dungeon_tier"
) {
    /** The tier index a player asking for [requested] at [averageLevel] actually gets: the highest one they are allowed. */
    fun tierFor(requested: Int, averageLevel: Int): Int {
        if (tiers.isEmpty()) return 0
        var pick = requested.coerceIn(0, tiers.size - 1)
        while (pick > 0 && tiers[pick].minLevel > averageLevel) pick--
        return pick
    }

    /**
     * The scale for a monster of level [npcLevel] when [members] players of average level [averageLevel] are inside, on
     * difficulty tier [tier] (an index into [tiers]; ignored when there are none).
     */
    fun scaleFor(members: Int, averageLevel: Int, npcLevel: Int, tier: Int = 0): com.morgana.domain.entity.NpcScale {
        val t = tiers.getOrNull(tier)
        val extra = members.coerceIn(1, maxMembers.coerceAtLeast(1)) - 1
        val shift = if (levelFollowsMembers) (averageLevel - npcLevel).coerceIn(-maxLevelShift, maxLevelShift) else 0
        val levelPercent = shift * percentPerLevel
        fun tiered(percent: Int, tierPercent: Int) = (percent.toLong() * tierPercent / 100).toInt().coerceAtLeast(MIN_PERCENT)
        return com.morgana.domain.entity.NpcScale(
            hpPercent = tiered((100 + extra * hpPercentPerMember + levelPercent).coerceAtLeast(MIN_PERCENT), t?.hpPercent ?: 100),
            attackPercent = tiered((100 + extra * attackPercentPerMember + levelPercent).coerceAtLeast(MIN_PERCENT), t?.attackPercent ?: 100),
            xpPercent = tiered((100 + extra * xpPercentPerMember).coerceAtLeast(MIN_PERCENT), t?.xpPercent ?: 100),
            levelBonus = shift,
            dropRatePercent = t?.dropRatePercent ?: 100
        )
    }

    companion object {
        const val MIN_PERCENT = 10
    }
}

/** One difficulty a dungeon offers (see [InstanceScaling.tiers]): its monsters' life, damage, XP and drops in percent of the base scale. */
@Serializable
data class DifficultyTier(
    val name: String,
    val hpPercent: Int = 100,
    val attackPercent: Int = 100,
    val xpPercent: Int = 100,
    val dropRatePercent: Int = 100,
    /** The party's average level must reach this for the tier to be given; a lower-level opener gets the next tier down. */
    val minLevel: Int = 1
)
