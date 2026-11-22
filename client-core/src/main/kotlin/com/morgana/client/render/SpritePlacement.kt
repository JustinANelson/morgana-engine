package com.morgana.client.render

import com.morgana.domain.game.SpriteProfile

/** Screen/world placement of a sprite cell anchored bottom-center on its occupied tile. */
data class SpriteDrawBox(
    val drawX: Float,
    val drawY: Float,
    val drawWidth: Float,
    val drawHeight: Float,
    val footPixelY: Float,
    val centerX: Float,
    val uiTopY: Float,
    /** Pixels the sprite is raised above its tile (a jump hop); the ground shadow stays at `drawY - lift`. */
    val lift: Float = 0f
)

/** Sort key for deterministic back-to-front (top-of-map to bottom-of-map) entity rendering by feet. */
data class EntitySortKey(
    val footPixelY: Float,
    val kindOrder: Int,
    val id: String
) : Comparable<EntitySortKey> {
    override fun compareTo(other: EntitySortKey): Int {
        val yCmp = footPixelY.compareTo(other.footPixelY)
        if (yCmp != 0) return yCmp
        val kCmp = kindOrder.compareTo(other.kindOrder)
        if (kCmp != 0) return kCmp
        return id.compareTo(other.id)
    }
}

/**
 * Pure pixel-coordinate placement, world-UI offset, and foot Y-sorting helpers for classic and taller sprites.
 */
object SpritePlacement {

    const val UI_HEADROOM_GAP_PX = 4f
    const val KIND_NPC = 0
    const val KIND_PLAYER = 1

    /**
     * Computes bottom-center tile-anchored sprite bounds and world-UI baseline in LibGDX render space (where +Y points up).
     * For classic `32 x 32` sprites this matches exact tile bounds `(footPixelX, pixelToRenderY, tileSize, tileSize)`.
     * For taller sprites (e.g. `32 x 64`), the feet stay on the bottom edge of the tile while the head extends upward
     * and [uiTopY] clears the full sprite height.
     */
    fun place(
        footPixelX: Float,
        footPixelY: Float,
        mapHeight: Int,
        tileSize: Int = 32,
        profile: SpriteProfile? = null,
        lift: Float = 0f,
        spriteId: Int = 0,
        scale: Float = profile?.scaleOf(spriteId) ?: 1f
    ): SpriteDrawBox {
        val drawW = (profile?.cellWidth ?: tileSize).toFloat() * scale
        val drawH = (profile?.cellHeight ?: tileSize).toFloat() * scale
        val labelH = (profile?.uiHeights?.get(spriteId)?.toFloat() ?: profile?.cellHeight?.toFloat() ?: tileSize.toFloat()) * scale
        val bottomRenderY = ((mapHeight - 1) * tileSize) - footPixelY
        val drawX = footPixelX + (tileSize - drawW) * 0.5f
        val centerX = footPixelX + tileSize * 0.5f
        return SpriteDrawBox(
            drawX = drawX,
            drawY = bottomRenderY + lift,
            drawWidth = drawW,
            drawHeight = drawH,
            footPixelY = footPixelY,
            centerX = centerX,
            uiTopY = bottomRenderY + lift + labelH + UI_HEADROOM_GAP_PX,
            lift = lift
        )
    }

    /**
     * Placement for a square legacy overlay (such as a `32 x 32` paperdoll layer) over an entity box:
     * identical to `(drawX, drawY, ts, ts)` in classic `32 x 32` mode, and centered vertically on taller sprites.
     */
    fun placeOverlay(box: SpriteDrawBox, overlaySize: Float): Pair<Float, Float> {
        val ox = box.drawX + (box.drawWidth - overlaySize) * 0.5f
        val oy = box.drawY + (box.drawHeight - overlaySize) * 0.5f
        return ox to oy
    }
}

