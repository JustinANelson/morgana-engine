package com.morgana.client.render

import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.TileAttribute

/**
 * Which tiles cast a sun or moon shadow, and where it falls. Pure maths over the map, unit-tested, no GL.
 *
 * Props are authored as a column: the bottom tile sits on `MASK_1`/`MASK_2` (blocked, or with more of the prop above it) and
 * the rest of the column on `FRINGE_1`/`FRINGE_2`/`ROOF`. A column like that is one caster standing on its base tile; each
 * tile's silhouette is flipped about the base and leaned away from the light, the same way a character's shadow is.
 */
object TileShadowModel {
    /** Tallest column treated as one caster, in tiles. Anything taller is cut off (towers, walls). */
    const val MAX_STACK = 8

    /** True when the tile at ([tx], [ty]) is the bottom of a standing prop and so roots a shadow. */
    fun isBase(map: MapDefinition, tx: Int, ty: Int): Boolean {
        val cell = map.getCell(tx, ty) ?: return false
        if (MapLayer.MASK_1 !in cell.layers && MapLayer.MASK_2 !in cell.layers) return false
        if (cell.attribute is TileAttribute.LightSource) return false
        if (cell.attribute is TileAttribute.Blocked) return true
        // A doorway or archway base is passable, but it still has the rest of the prop above it.
        return hasUpper(map, tx, ty - 1)
    }

    /** How many tiles of the column above the base at ([tx], [ty]) have upper-layer art, counting up until the first gap. */
    fun stackHeight(map: MapDefinition, tx: Int, ty: Int): Int {
        var n = 0
        while (n < MAX_STACK && hasUpper(map, tx, ty - 1 - n)) n++
        return n
    }

    private fun hasUpper(map: MapDefinition, tx: Int, ty: Int): Boolean {
        val cell = map.getCell(tx, ty) ?: return false
        return MapLayer.FRINGE_1 in cell.layers || MapLayer.FRINGE_2 in cell.layers || MapLayer.ROOF in cell.layers
    }

    /**
     * Where a point [height] pixels above the base lands on the ground: x leans away from the light, y falls toward the viewer,
     * squashed because the ground is seen at a slant. Returns (dx, dy), dy being negative (down the screen).
     */
    fun project(height: Float, sky: SkyState, out: FloatArray = FloatArray(2)): FloatArray {
        out[0] = sky.shadowShear * sky.shadowLength * 0.9f * height
        out[1] = -height * sky.shadowLength * 0.55f
        return out
    }
}
