package com.morgana.mapbuilder

import com.morgana.domain.map.InstanceOwner
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.MapNeighborLinks
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A map described as data: a base terrain, named terrains (which tile, whether they block, whether an autotile rounds their
 * edges) and an ordered list of painting operations. [MapCompiler] turns it into a map document. Everything is deterministic
 * for the same [seed], so a recipe can be re-run, diffed and reviewed like source.
 */
@Serializable
data class MapRecipe(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val seed: Long = 1L,
    val moral: MapMoral = MapMoral.SAFE_ZONE,
    val bgm: String = "overworld",
    val indoors: Boolean = false,
    val zoneId: Int = 0,
    /** Makes the map an instanced copy-per-owner map (a dungeon or raid, a guild hall); [instanceOwner] says whose copy it is. */
    val instanced: Boolean = false,
    val instanceOwner: InstanceOwner = InstanceOwner.PARTY,
    val neighbors: MapNeighborLinks = MapNeighborLinks(),
    /** Where the map's top left tile sits in the shared world grid of a game with `worldLayout` (both or neither). Maps that meet are walked between without a warp. */
    val worldX: Int? = null,
    val worldY: Int? = null,
    /**
     * A tile (`[x, y]`) the player can stand on. When given, enemy camp spawns that cannot be walked to from it (an island, a pocket
     * behind a lake) are dropped, so no enemy is wasted where nobody can reach it. Service NPCs from `spawn` are never dropped:
     * the compiler reports them if they are unreachable.
     */
    val anchor: List<Int>? = null,
    /** The terrain every cell starts as. */
    val base: String,
    val terrains: Map<String, TerrainDef>,
    val ops: List<MapOp> = emptyList()
)

/**
 * What a terrain looks like and how it behaves.
 *
 * [tile] is a tile name from the tileset index; variants `name_v1`, `name_v2`... are used when they exist, picked per cell by a
 * position hash so large areas do not repeat. With [autotile] (an autotile name from the index) the terrain's edges are rounded
 * against the terrains it does not [joins] (itself is always joined; the map edge counts as joined). With [animatedFrames] above
 * 1 the fully surrounded cells animate using the tile's `_f0..` frames.
 */
@Serializable
data class TerrainDef(
    val tile: String,
    val blocked: Boolean = false,
    val autotile: String? = null,
    val joins: List<String> = emptyList(),
    val animatedFrames: Int = 1,
    val animationSpeedMs: Int = 250
)

@Serializable
sealed interface MapOp

/** Paints a rectangle ([x], [y] top left, [w] x [h] tiles) with [terrain]. */
@Serializable @SerialName("rect")
data class RectOp(val terrain: String, val x: Int, val y: Int, val w: Int, val h: Int) : MapOp

/** Paints an ellipse centred on ([cx], [cy]). */
@Serializable @SerialName("ellipse")
data class EllipseOp(val terrain: String, val cx: Int, val cy: Int, val rx: Int, val ry: Int) : MapOp

/**
 * Paints a thick line through [points] (each `[x, y]`), [width] tiles wide. With [meander] above 0 the line wanders sideways by up
 * to that many tiles (smooth seeded noise, one wiggle per [wavelength] tiles, none at the end points), so roads and rivers bend
 * instead of running ruler-straight.
 */
@Serializable @SerialName("path")
data class PathOp(
    val terrain: String,
    val points: List<List<Int>>,
    val width: Int = 3,
    val meander: Float = 0f,
    val wavelength: Float = 14f,
    val salt: Int = 0
) : MapOp

/**
 * Lays a seeded noise field over the map and paints [bands] by its value (0..1; the field is stretched to span exactly that), which
 * is how you get hills, lakes, swamps and glades with natural outlines. The first band whose [NoiseBand.below] is above the value
 * paints; a value above every band leaves the cell as it is. [scale] is the size of a feature in tiles, [octaves] and [persistence]
 * add finer detail, [warp] bends the shapes, [edge] and [edgeBias] sink or raise the map border. [onlyOn] limits painting to cells that
 * currently hold one of those terrains, and [region] (`[x, y, w, h]`) to an area. [store] keeps the field under a name for a later
 * [TerraceOp].
 */
@Serializable @SerialName("noise")
data class NoiseOp(
    val bands: List<NoiseBand> = emptyList(),
    val scale: Float = 18f,
    val octaves: Int = 4,
    val persistence: Float = 0.5f,
    val warp: Float = 0f,
    val edge: Float = 0f,
    val edgeBias: Float = -0.4f,
    val onlyOn: List<String> = emptyList(),
    val region: List<Int>? = null,
    val store: String? = null,
    /** Passes of 3 x 3 blurring of the field before it is banded: rounder outlines, fewer ragged one-tile edges. */
    val smooth: Int = 1,
    /** Painted areas (per terrain, 4-connected) smaller than this many tiles are undone, so no speckle of one-tile ponds is left. */
    val minArea: Int = 0,
    /**
     * Tilts the field across [region] so values rise from one side to the other: [tiltX] is added at the east edge and taken away at the
     * west edge of the region (half each), [tiltY] likewise south and north. Low values on one side and high on the other is a coast:
     * sea on the west, rock, then land, with the noise making the shoreline wander.
     */
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    val salt: Int = 0
) : MapOp

/**
 * Paints the map's terrain from a *world-wide* function: every field is sampled at world coordinates ([MapRecipe.worldX] + x), with a fixed
 * mapping to 0..1 (no per-map stretching), so two maps that touch agree tile for tile along their border and a world can grow by adding
 * maps. [fields] are evaluated in order (a field may mix in earlier ones, so temperature can fall with height); [rules] then give each tile
 * the terrain of the first rule whose ranges all contain the tile's field values. Give every map of a world the same op (same [seed]).
 */
@Serializable @SerialName("worldgen")
data class WorldGenOp(
    val seed: Long,
    /** The size of the whole world in tiles, for the edge falloff. */
    val worldWidth: Int,
    val worldHeight: Int,
    val fields: Map<String, WorldField>,
    val rules: List<WorldRule>,
    val region: List<Int>? = null
) : MapOp

/**
 * One field of a [WorldGenOp]. The raw fractal noise (about -0.5..0.5) becomes `0.5 + raw * gain`; [ridged] turns it into thin lines (near 1
 * where the noise crosses zero: rivers). [tiltX]/[tiltY] add a slope across the whole world (0 to 1 from west to east, north to south),
 * [edge] tiles of falloff at the world's border push the value by [edgeBias], and [mix] adds other (earlier) fields times a weight.
 */
@Serializable
data class WorldField(
    val scale: Float = 120f,
    val octaves: Int = 4,
    val persistence: Float = 0.5f,
    val warp: Float = 0f,
    val salt: Int = 0,
    val gain: Float = 1.3f,
    val ridged: Boolean = false,
    val ridgeWidth: Float = 2.4f,
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    val edge: Float = 0f,
    val edgeBias: Float = 0f,
    val mix: Map<String, Float> = emptyMap(),
    /** Hand-placed swells in the field: the value rises (or falls, when negative) by [WorldBump.amount] at a point, fading out over its radius. This is how a plan makes room for a district. */
    val bumps: List<WorldBump> = emptyList()
)

/** A soft swell of [amount] centred on world tile ([x], [y]), reaching [radius] tiles (the effect fades with the square of the distance). */
@Serializable
data class WorldBump(val x: Int, val y: Int, val radius: Int, val amount: Float)

/** The first [WorldRule] whose every range holds gives a tile its [terrain]. A range is `[from, to)` (a `to` of 1 or more includes 1). */
@Serializable
data class WorldRule(val terrain: String, val ranges: Map<String, List<Float>> = emptyMap())

/** One step of a [NoiseOp]: field values below [below] become [terrain]. */
@Serializable
data class NoiseBand(val below: Float, val terrain: String)

/**
 * Raises plateaus where a stored noise [field] passes each of [thresholds] (a cell above the first is level 1, above the second level 2):
 * plateau cells become that level's terrain from [terrains], the south edge of every plateau becomes a two-row cliff face from the wall
 * [set] (`<set>_top_*` over `<set>_bot_*`, blocked), and [ramps] open a [rampWidth] wide walkable gap in each long enough face about every
 * [rampEvery] tiles, painted with [rampTerrain] (default: the ground below). Plateaus narrower than [minWidth] or shallower than
 * [minDepth] are levelled so no face is ever a single row.
 */
@Serializable @SerialName("terrace")
data class TerraceOp(
    val field: String,
    val set: String,
    val thresholds: List<Float>,
    val terrains: List<String>,
    val ramps: Boolean = true,
    val rampTerrain: String? = null,
    val rampEvery: Int = 16,
    val rampWidth: Int = 3,
    val minWidth: Int = 4,
    val minDepth: Int = 4,
    /** Plateau edges snap to bands of this many rows, so cliff faces run in long horizontal stretches. */
    val rowStep: Int = 3,
    val region: List<Int>? = null,
    val salt: Int = 0
) : MapOp

/** Paints an organic blob: a circle of [radius] whose edge is pushed in and out by seeded noise ([roughness] 0 to 1). */
@Serializable @SerialName("blob")
data class BlobOp(val terrain: String, val cx: Int, val cy: Int, val radius: Int, val roughness: Float = 0.35f, val salt: Int = 0) : MapOp

/**
 * Paints a band [thickness] tiles deep along the map edges. [gaps] opens the band along an edge (`north`, `south`, `east`,
 * `west`) between two coordinates (inclusive), which is how a seam stays walkable at the same place on both maps.
 */
@Serializable @SerialName("frame")
data class FrameOp(val terrain: String, val thickness: Int, val gaps: Map<String, List<Int>> = emptyMap()) : MapOp

/**
 * A raised wall block from the 9-tile wall set called [set] (`<set>_void_*`, `<set>_top_*`, `<set>_bot_*`): rows of flat top
 * above, then a cornice row and a plinth row facing south. The block is blocked unless [passableCols] lists columns (0 based
 * from the left) to leave open in the two face rows, for gates.
 */
@Serializable @SerialName("wall")
data class WallOp(val set: String, val x: Int, val y: Int, val w: Int, val h: Int, val passableCols: List<Int> = emptyList()) : MapOp

/**
 * Places the prop [prop] (from the index) with its top left at ([x], [y]). The bottom row is blocked and drawn under
 * characters; rows above are drawn over them. [passableCols] leaves columns of the bottom row open (a gate), [walkable] makes the
 * whole prop decoration (a ground patch).
 */
@Serializable @SerialName("prop")
data class PropOp(val prop: String, val x: Int, val y: Int, val passableCols: List<Int> = emptyList(), val walkable: Boolean = false) : MapOp

/**
 * Scatters props over cells of [terrain]: each candidate cell inside [region] (`[x, y, w, h]`, default the whole map) takes one of
 * [props] with probability [density], keeping [gap] free tiles around it and never covering another prop or a blocked cell.
 */
@Serializable @SerialName("scatter")
data class ScatterOp(
    val props: List<String>,
    val terrain: String,
    val density: Float,
    val region: List<Int>? = null,
    val gap: Int = 1,
    val walkable: Boolean = false,
    val salt: Int = 0
) : MapOp

/** Puts one tile from the index on a layer at a cell, optionally blocking it. */
@Serializable @SerialName("tile")
data class TileOp(val tile: String, val x: Int, val y: Int, val layer: MapLayer = MapLayer.MASK_1, val blocked: Boolean = false) : MapOp

/** A warp tile area: stepping on any cell of the [w] x [h] area at ([x], [y]) moves the player to ([toX], [toY]) on map [toMap]. */
@Serializable @SerialName("warp")
data class WarpOp(val x: Int, val y: Int, val toMap: Int, val toX: Int, val toY: Int, val w: Int = 1, val h: Int = 1) : MapOp

/** Reserves a rectangle: later scatter and camps leave it alone (a road mouth at a seam, a landing). Paints nothing. */
@Serializable @SerialName("reserve")
data class ReserveOp(val x: Int, val y: Int, val w: Int, val h: Int) : MapOp

/** One NPC (a spawn slot) at a cell. */
@Serializable @SerialName("spawn")
data class SpawnOp(val npcId: Int, val x: Int, val y: Int, val respawnTicks: Int = 100) : MapOp

/**
 * Enemy camps. Candidate cells are walkable, prop-free cells of [terrains]; camps keep [spacing] tiles apart; each holds
 * [sizeMin]..[sizeMax] enemies scattered within [radius] of its centre. How many camps: [camps] if given, otherwise as many as
 * it takes for one enemy per [tilesPerEnemy] eligible tiles.
 */
@Serializable @SerialName("camps")
data class CampsOp(
    val npcIds: List<Int>,
    val terrains: List<String>,
    val region: List<Int>? = null,
    val camps: Int? = null,
    val tilesPerEnemy: Int = 35,
    val sizeMin: Int = 8,
    val sizeMax: Int = 10,
    val spacing: Int = 9,
    val radius: Int = 3,
    val respawnTicks: Int = 100,
    val salt: Int = 0
) : MapOp
