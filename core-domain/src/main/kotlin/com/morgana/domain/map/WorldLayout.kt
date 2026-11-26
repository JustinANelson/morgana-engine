package com.morgana.domain.map

import kotlinx.serialization.Serializable

/**
 * Settings of the seamless world (`game.json` -> `worldLayout`). With it on, every map that names a world position
 * (`MapDefinition.worldX`/`worldY`, the tile its top left corner holds in one shared world grid) is part of one continuous space:
 * walking off an edge puts the player on whichever map covers that world tile, at the same place, with no teleport and no reload
 * of what they were looking at; maps of any size can meet at any offset, along any edge or corner. Without it (or on maps that
 * name no position) the older per-map neighbour links and warps work as before.
 */
@Serializable
data class WorldLayoutDefinition(
    val enabled: Boolean = true,
    /** How far from the active map's edge, in tiles, a neighbouring map is still sent to the client and drawn. */
    val neighborMarginTiles: Int = 40,
    /** How far from the player, in tiles, entities of the maps around them are shown across a border. */
    val seamViewTiles: Int = 28,
    /**
     * Warn (validation `world_border_mismatch`) about a border tile open on one side and blocked on the other. Right for hand-built seams;
     * switch it off for a generated world, where a lake or river crossing a border legitimately blocks one side of a pair of tiles.
     */
    val borderWarnings: Boolean = true
) {
    companion object {
        const val DEFAULT_MARGIN = 40
        const val DEFAULT_SEAM_VIEW = 28
    }
}

/** A tile position in the shared world grid. */
@Serializable
data class WorldTile(val x: Int, val y: Int)

/** Where map [mapId]'s top left tile sits relative to another map's: ([x], [y]) tiles east and south. */
@Serializable
data class TileOffset(val x: Int, val y: Int)

/** The result of stepping off a map: the map and tile reached, and how far the position moves between the two maps' coordinates. */
data class Crossing(val map: MapDefinition, val tileX: Int, val tileY: Int, val shiftTilesX: Int, val shiftTilesY: Int)

/**
 * The placed maps as one world: pure geometry over [MapDefinition.worldX]/[MapDefinition.worldY], no state. Instanced maps and maps
 * without a position are not part of it.
 */
class WorldLayout(maps: Collection<MapDefinition>) {
    private val placed: List<MapDefinition> = maps.filter { it.worldX != null && it.worldY != null && !it.instanced }.sortedBy { it.id }
    private val byId: Map<Int, MapDefinition> = placed.associateBy { it.id }

    val isEmpty: Boolean get() = placed.isEmpty()

    fun isPlaced(mapId: Int): Boolean = mapId in byId

    private fun x0(m: MapDefinition) = m.worldX!!
    private fun y0(m: MapDefinition) = m.worldY!!

    /** The map covering world tile ([wx], [wy]), or null where there is none. */
    fun mapAt(wx: Int, wy: Int): MapDefinition? =
        placed.firstOrNull { wx >= x0(it) && wx < x0(it) + it.width && wy >= y0(it) && wy < y0(it) + it.height }

    /** The world tile of ([tx], [ty]) on map [mapId]; null if the map is not placed. */
    fun toWorld(mapId: Int, tx: Int, ty: Int): WorldTile? = byId[mapId]?.let { WorldTile(x0(it) + tx, y0(it) + ty) }

    /** Where map [other]'s top left tile is, in tiles, from map [from]'s top left tile; null unless both are placed. */
    fun offsetBetween(from: Int, other: Int): TileOffset? {
        val a = byId[from] ?: return null
        val b = byId[other] ?: return null
        return TileOffset(x0(b) - x0(a), y0(b) - y0(a))
    }

    /**
     * Stepping to tile ([tx], [ty]) of map [from] (outside it): the map that lies there and the tile on it, or null where the world has
     * no map (the edge of the world). The shift is what to add to a position on [from] to express it on the new map.
     */
    fun crossing(from: MapDefinition, tx: Int, ty: Int): Crossing? {
        if (from.id !in byId) return null
        val wx = x0(from) + tx
        val wy = y0(from) + ty
        val to = mapAt(wx, wy) ?: return null
        if (to.id == from.id) return null
        return Crossing(to, wx - x0(to), wy - y0(to), x0(from) - x0(to), y0(from) - y0(to))
    }

    /**
     * The placed maps within [margin] tiles of [mapId]'s rectangle (counting a corner as touching), with where each one's top left
     * tile sits from the top left tile of [mapId]. These are the maps the client draws around the active one.
     */
    fun around(mapId: Int, margin: Int): List<Pair<MapDefinition, TileOffset>> {
        val a = byId[mapId] ?: return emptyList()
        return placed.filter { it.id != mapId }.mapNotNull { b ->
            val gapX = maxOf(x0(b) - (x0(a) + a.width), x0(a) - (x0(b) + b.width), 0)
            val gapY = maxOf(y0(b) - (y0(a) + a.height), y0(a) - (y0(b) + b.height), 0)
            if (gapX <= margin && gapY <= margin) b to TileOffset(x0(b) - x0(a), y0(b) - y0(a)) else null
        }
    }

    /** The N/S/E/W links this layout implies for [map]: the map sharing most of each edge (an edge has to meet exactly). 0 when none. */
    fun linksOf(map: MapDefinition): MapNeighborLinks {
        if (map.id !in byId) return map.neighbors
        fun best(pick: (MapDefinition) -> Boolean, overlap: (MapDefinition) -> Int): Int =
            placed.filter { it.id != map.id && pick(it) }.map { it to overlap(it) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first?.id ?: 0
        fun spanX(o: MapDefinition) = minOf(x0(map) + map.width, x0(o) + o.width) - maxOf(x0(map), x0(o))
        fun spanY(o: MapDefinition) = minOf(y0(map) + map.height, y0(o) + o.height) - maxOf(y0(map), y0(o))
        return MapNeighborLinks(
            northMapId = best({ y0(it) + it.height == y0(map) }, ::spanX),
            southMapId = best({ y0(it) == y0(map) + map.height }, ::spanX),
            eastMapId = best({ x0(it) == x0(map) + map.width }, ::spanY),
            westMapId = best({ x0(it) + it.width == x0(map) }, ::spanY)
        )
    }

    /** Pairs of placed maps whose rectangles overlap: a layout error. */
    fun overlaps(): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (i in placed.indices) for (j in i + 1 until placed.size) {
            val a = placed[i]
            val b = placed[j]
            if (x0(a) < x0(b) + b.width && x0(b) < x0(a) + a.width && y0(a) < y0(b) + b.height && y0(b) < y0(a) + a.height) out += a.id to b.id
        }
        return out
    }

    /**
     * Where two maps meet along an edge, the border tiles that are walkable on one side and blocked on the other, as (world x, world y)
     * of the tile on [a]'s side: a player could walk to the edge and find a wall, or the other way round.
     */
    fun mismatchedBorderTiles(a: MapDefinition, b: MapDefinition): List<WorldTile> {
        if (a.id !in byId || b.id !in byId) return emptyList()
        fun open(m: MapDefinition, wx: Int, wy: Int): Boolean {
            val c = m.getCell(wx - x0(m), wy - y0(m)) ?: return false
            return c.attribute !is TileAttribute.Blocked
        }
        val out = ArrayList<WorldTile>()
        // Vertical border: a's east edge meets b's west edge, or the reverse.
        for ((l, r) in listOf(a to b, b to a)) {
            if (x0(l) + l.width == x0(r)) {
                for (wy in maxOf(y0(l), y0(r)) until minOf(y0(l) + l.height, y0(r) + r.height)) {
                    if (open(l, x0(r) - 1, wy) != open(r, x0(r), wy)) out += WorldTile(x0(r) - 1, wy)
                }
            }
            if (y0(l) + l.height == y0(r)) {
                for (wx in maxOf(x0(l), x0(r)) until minOf(x0(l) + l.width, x0(r) + r.width)) {
                    if (open(l, wx, y0(r) - 1) != open(r, wx, y0(r))) out += WorldTile(wx, y0(r) - 1)
                }
            }
        }
        return out
    }
}
