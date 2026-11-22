package com.morgana.mapbuilder

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
    val neighbors: MapNeighborLinks = MapNeighborLinks(),
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

/** Paints a thick line through [points] (each `[x, y]`), [width] tiles wide. */
@Serializable @SerialName("path")
data class PathOp(val terrain: String, val points: List<List<Int>>, val width: Int = 3) : MapOp

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
