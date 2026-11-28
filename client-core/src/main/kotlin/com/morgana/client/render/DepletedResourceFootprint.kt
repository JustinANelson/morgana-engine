package com.morgana.client.render

import com.morgana.domain.entity.MapResourceState
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.spatial.TileCoord

/**
 * Resolves all map-layer tile positions belonging to currently depleted resource nodes on a map.
 *
 * Multi-tile resources (such as 3x3 trees with canopy tiles on FRINGE_1, or 2x2 boulders and
 * bushes spanning adjacent MASK/FRINGE tiles) are stamped contiguously from a single tileset sheet
 * around their anchor resource cell. When depleted, every tile matching the anchor's
 * `(tilesetId, baseSrcX + dx, baseSrcY + dy)` within the stamp window is suppressed so canopies,
 * upper halves, and cast shadows disappear cleanly together with the base tile.
 */
object DepletedResourceFootprint {

    private val RESOURCE_LAYERS = listOf(MapLayer.MASK_1, MapLayer.MASK_2, MapLayer.FRINGE_1, MapLayer.FRINGE_2)

    fun pack(tx: Int, ty: Int, layer: MapLayer): Long =
        ((tx.toLong() and 0xFFFFFL) shl 24) or ((ty.toLong() and 0xFFFFFL) shl 4) or (layer.ordinal.toLong() and 0xFL)

    fun collectHiddenTiles(
        map: MapDefinition,
        resources: Map<TileCoord, MapResourceState>,
        out: MutableSet<Long>
    ) {
        out.clear()
        if (resources.isEmpty()) return
        for ((coord, state) in resources) {
            if (!state.depleted) continue
            val baseCell = map.getCell(coord.x, coord.y) ?: continue
            val baseTile = baseCell.layers[MapLayer.MASK_1] ?: baseCell.layers[MapLayer.MASK_2]
            if (MapLayer.MASK_1 in baseCell.layers) out.add(pack(coord.x, coord.y, MapLayer.MASK_1))
            if (MapLayer.MASK_2 in baseCell.layers) out.add(pack(coord.x, coord.y, MapLayer.MASK_2))
            if (baseTile == null) continue
            val tsId = baseTile.tilesetId
            val baseSrcX = baseTile.srcTileX
            val baseSrcY = baseTile.srcTileY
            for (dy in -3..0) {
                val ty = coord.y + dy
                if (ty < 0 || ty >= map.height) continue
                for (dx in -2..2) {
                    val tx = coord.x + dx
                    if (tx < 0 || tx >= map.width) continue
                    val cell = map.getCell(tx, ty) ?: continue
                    val expectedX = baseSrcX + dx
                    val expectedY = baseSrcY + dy
                    for (layer in RESOURCE_LAYERS) {
                        val placement = cell.layers[layer] ?: continue
                        if (placement.tilesetId == tsId &&
                            placement.srcTileX == expectedX &&
                            placement.srcTileY == expectedY
                        ) {
                            out.add(pack(tx, ty, layer))
                        }
                    }
                }
            }
        }
    }
}
