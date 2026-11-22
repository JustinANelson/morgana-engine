package com.morgana.client.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.kotcrab.vis.ui.VisUI
import com.morgana.client.access.Accessibility
import com.morgana.client.access.PaletteRole
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.state.ClientWorldState
import com.morgana.domain.explore.Exploration
import com.morgana.domain.explore.WorldMapTerrain
import com.morgana.protocol.ExploredMapInfo
import com.morgana.protocol.WorldMapDetailEntry

/**
 * One map on the world map. With [detail] from the server it is the map itself in miniature (water, walls, warps and open
 * ground, as on the minimap) with enemy markers and their levels; the ground the character has not seen is dark and shows
 * no enemies. Without it (before the server has answered) it falls back to the explored chunks alone.
 * The current map has a gold border and a pulsing blip where the player stands; [info] is null for a map only known
 * from a neighbour's border, which is drawn as solid fog. Hovering a marker names the creature.
 */
class WorldMapCard(
    private val assets: ProceduralRetroAssets,
    private val worldState: ClientWorldState,
    private val mapId: Int,
    private val info: ExploredMapInfo?,
    private val detail: WorldMapDetailEntry? = null
) : Actor() {
    private var blink = 0f
    private val layout = GlyphLayout()
    private val mouse = Vector2()

    private val markers: List<WorldMapMarkers.Marker> by lazy {
        if (info == null || detail == null) emptyList()
        else WorldMapMarkers.markers(detail.spawns, worldState.catalog.npcs.associateBy { it.id }) { tx, ty ->
            Exploration.isTileExplored(info.explored, info.width, tx.coerceIn(0, info.width - 1), ty.coerceIn(0, info.height - 1))
        }
    }

    override fun act(delta: Float) {
        super.act(delta)
        blink += delta
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val current = worldState.localCharacter?.position?.mapId == mapId
        if (current) batch.setColor(1f, 0.82f, 0.2f, parentAlpha) else batch.setColor(0.35f, 0.4f, 0.5f, parentAlpha)
        batch.draw(assets.whitePixel, x - 1.5f, y - 1.5f, width + 3f, height + 3f)
        batch.setColor(0.04f, 0.05f, 0.08f, parentAlpha)
        batch.draw(assets.whitePixel, x, y, width, height)
        if (info != null) {
            // The map keeps its proportions inside the card: tilePx is how many stage units one tile gets.
            val tilePx = minOf(width / info.width, height / info.height)
            val ox = x + (width - tilePx * info.width) * 0.5f
            val oy = y + (height - tilePx * info.height) * 0.5f
            if (detail != null) drawTerrain(batch, parentAlpha, detail, ox, oy, tilePx) else drawChunks(batch, parentAlpha)
            drawMarkers(batch, parentAlpha, ox, oy, tilePx)
            val me = worldState.localCharacter
            if (current && me != null) {
                val bx = ox + (me.position.tileX + 0.5f) * tilePx
                val by = oy + (info.height - me.position.tileY - 0.5f) * tilePx
                val pulse = if (Accessibility.reduceMotion || ((blink * 4f).toInt() % 2) == 0) 1f else 0.4f
                batch.setColor(0f, 0f, 0f, 0.8f * parentAlpha)
                batch.draw(assets.whitePixel, bx - 3.5f, by - 3.5f, 7f, 7f)
                batch.setColor(1f, 1f, 1f, pulse * parentAlpha)
                batch.draw(assets.whitePixel, bx - 2.5f, by - 2.5f, 5f, 5f)
            }
        }
        batch.color = Color.WHITE
    }

    private fun drawTerrain(batch: Batch, parentAlpha: Float, d: WorldMapDetailEntry, ox: Float, oy: Float, tilePx: Float) {
        val info = info ?: return
        val thumb = WorldMapTerrain.Thumbnail(d.scale, d.cols, d.rows, d.terrain)
        for (row in 0 until d.rows) {
            for (col in 0 until d.cols) {
                val seen = Exploration.isTileExplored(
                    info.explored, info.width,
                    (col * d.scale + d.scale / 2).coerceAtMost(info.width - 1),
                    (row * d.scale + d.scale / 2).coerceAtMost(info.height - 1)
                )
                val dim = if (seen) 1f else 0.22f
                when (thumb.at(col, row)) {
                    WorldMapTerrain.BLOCKED -> batch.setColor(0.35f * dim, 0.38f * dim, 0.45f * dim, parentAlpha)
                    WorldMapTerrain.WATER -> batch.setColor(0.15f * dim, 0.35f * dim, 0.75f * dim, parentAlpha)
                    WorldMapTerrain.WARP -> batch.setColor(0.2f * dim, 0.85f * dim, 0.9f * dim, parentAlpha)
                    else -> batch.setColor(0.18f * dim, 0.38f * dim, 0.22f * dim, parentAlpha)
                }
                // A block at the map's far edge may stick out past it: clip to the map. A hair of overlap hides seams.
                val left = col * d.scale
                val top = row * d.scale
                val wTiles = minOf(d.scale, info.width - left)
                val hTiles = minOf(d.scale, info.height - top)
                batch.draw(assets.whitePixel, ox + left * tilePx, oy + (info.height - top - hTiles) * tilePx, wTiles * tilePx + 0.6f, hTiles * tilePx + 0.6f)
            }
        }
    }

    /** The plain look before the server's picture arrives: lit explored chunks on dark. */
    private fun drawChunks(batch: Batch, parentAlpha: Float) {
        val info = info ?: return
        val across = Exploration.chunksAcross(info.width)
        val down = Exploration.chunksAcross(info.height)
        val cell = minOf(width / across, height / down)
        val cx0 = x + (width - cell * across) * 0.5f
        val cy0 = y + (height - cell * down) * 0.5f
        for (cy in 0 until down) for (cx in 0 until across) {
            if (Exploration.isChunkExplored(info.explored, info.width, cx, cy)) Accessibility.colour(PaletteRole.EXPLORED).let { batch.setColor(it.r * 0.8f, it.g * 0.8f, it.b * 0.8f, parentAlpha) }
            else batch.setColor(0.1f, 0.12f, 0.17f, parentAlpha)
            batch.draw(assets.whitePixel, cx0 + cx * cell + 0.5f, cy0 + (down - 1 - cy) * cell + 0.5f, cell - 1f, cell - 1f)
        }
    }

    private fun drawMarkers(batch: Batch, parentAlpha: Float, ox: Float, oy: Float, tilePx: Float) {
        val info = info ?: return
        if (markers.isEmpty()) return
        val font = VisUI.getSkin().getFont("default-font")
        val oldScaleX = font.data.scaleX
        val oldScaleY = font.data.scaleY
        val oldColour = font.color.cpy()
        font.data.setScale(0.8f)
        val playerLevel = worldState.localCharacter?.level

        // The pointer, in this actor's own coordinates, to find the marker being pointed at.
        var hovered: WorldMapMarkers.Marker? = null
        var hoveredDistance = HOVER_RADIUS
        stage?.let { st ->
            mouse.set(Gdx.input.x.toFloat(), Gdx.input.y.toFloat())
            st.screenToStageCoordinates(mouse)
            stageToLocalCoordinates(mouse)
        }

        val placed = ArrayList<FloatArray>()
        // Dots first (weakest underneath), then labels strongest-first so the dangerous creature keeps its label when two collide.
        for (m in markers) {
            val mx = ox + (m.x + 0.5f) * tilePx
            val my = oy + (info.height - m.y - 0.5f) * tilePx
            val hex = WorldMapMarkers.colourHex(playerLevel, m.level)
            val c = Color.valueOf(hex)
            val size = if (m.boss) 8f else if (m.count > 1) 6f else 5f
            batch.setColor(0f, 0f, 0f, 0.85f * parentAlpha)
            batch.draw(assets.whitePixel, mx - size / 2f - 1f, my - size / 2f - 1f, size + 2f, size + 2f)
            if (m.boss) batch.setColor(1f, 0.84f, 0.2f, parentAlpha) else batch.setColor(c.r, c.g, c.b, parentAlpha)
            batch.draw(assets.whitePixel, mx - size / 2f, my - size / 2f, size, size)
            val dist = Math.hypot((mouse.x - (mx - x)).toDouble(), (mouse.y - (my - y)).toDouble()).toFloat()
            if (dist < hoveredDistance) { hoveredDistance = dist; hovered = m }
        }
        for (m in markers.asReversed()) {
            val mx = ox + (m.x + 0.5f) * tilePx
            val my = oy + (info.height - m.y - 0.5f) * tilePx
            layout.setText(font, m.label)
            val lx = (mx + 5f).coerceAtMost(x + width - layout.width - 2f)
            val ly = my + layout.height / 2f
            val rect = floatArrayOf(lx, ly - layout.height, layout.width, layout.height)
            if (placed.any { overlaps(it, rect) }) continue
            placed += rect
            val c = Color.valueOf(WorldMapMarkers.colourHex(playerLevel, m.level))
            font.setColor(0f, 0f, 0f, parentAlpha)
            font.draw(batch, layout, lx + 1f, ly - 1f)
            font.setColor(c.r, c.g, c.b, parentAlpha)
            font.draw(batch, layout, lx, ly)
        }

        hovered?.let { m ->
            layout.setText(font, m.caption)
            val bw = layout.width + 8f
            val bh = layout.height + 6f
            val bx = x + (width - bw) * 0.5f
            val by = y + 3f
            batch.setColor(0f, 0f, 0f, 0.88f * parentAlpha)
            batch.draw(assets.whitePixel, bx, by, bw, bh)
            font.setColor(1f, 1f, 1f, parentAlpha)
            font.draw(batch, layout, bx + 4f, by + bh - 3f)
        }

        font.data.setScale(oldScaleX, oldScaleY)
        font.color = oldColour
    }

    private fun overlaps(a: FloatArray, b: FloatArray): Boolean =
        a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3]

    private companion object {
        const val HOVER_RADIUS = 9f
    }
}
