package com.morgana.client.render

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.morgana.client.assets.AnimationStripLayout
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.client.state.ClientWorldState
import com.morgana.client.state.EntityVisualSmoothing
import com.morgana.client.state.MapEditorTool
import com.morgana.domain.game.SpriteState
import com.morgana.domain.item.ItemRollText
import com.morgana.domain.item.ItemRolls
import com.morgana.domain.item.LootRules
import com.morgana.domain.item.PaperdollComposer
import com.morgana.domain.item.PaperdollLayer
import com.morgana.domain.item.PaperdollSlot
import com.morgana.domain.map.AutotileMode
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.WeatherType
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.TileCoord
import kotlin.math.sin

/** How quickly ambient light and weather ease towards a new map's values (about a second to settle). */
private const val LIGHT_FADE_PER_SECOND = 1.8f

/**
 * Renders the 6-layer Morgana map stack, seamless N/S/E/W neighbor maps, Y-sorted entities with
 * smooth pixel interpolation, 4-direction walk animations, layered paperdolls, dynamic 2D lighting,
 * weather particles, F1 Map Editor hover preview & attribute badges, overhead nameplates/HP bars,
 * chat bubbles, and rising floating action text.
 */
class MorganaWorldRenderer(
    private val assets: ProceduralRetroAssets,
    private val font: BitmapFont
) {
    private val glyphLayout = GlyphLayout()

    /** Screen pixels covered by docked editor panels on each side; the world is centred in the rest. */
    var viewInsets: com.morgana.client.editor.dock.ViewInsets = com.morgana.client.editor.dock.ViewInsets.NONE
    private var elapsedSeconds: Float = 0f

    // Visible world rectangle for the current frame (see renderWorld); tile passes skip everything outside it.
    private var viewLeft = -Float.MAX_VALUE
    private var viewRight = Float.MAX_VALUE
    private var viewBottom = -Float.MAX_VALUE
    private var viewTop = Float.MAX_VALUE

    /** Milliseconds on the renderer's clock, for animated tiles ([com.morgana.domain.map.TileAnimation]). */
    private fun animationClockMs(): Long = (elapsedSeconds * 1000f).toLong()

    // Smoothed lighting/weather state (see renderLightingAndWeather).
    private var lastLightingSeconds: Float = 0f
    private var shownDarkness: Float = 0f
    private var shownWeather: WeatherType = WeatherType.NONE
    private var weatherAlpha: Float = 1f
    private var shownIntensity: Float = 0f
    private var lightingInitialised: Boolean = false

    fun toRenderY(mapHeight: Int, tileY: Int, tileSize: Int = 32): Float =
        ((mapHeight - 1 - tileY) * tileSize).toFloat()

    fun pixelToRenderY(mapHeight: Int, pixelY: Float, tileSize: Int = 32): Float =
        ((mapHeight - 1) * tileSize) - pixelY

    fun screenToTileCoord(
        camera: OrthographicCamera,
        screenX: Int,
        screenY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        map: MapDefinition
    ): TileCoord {
        val worldX = camera.position.x - (viewportWidth * 0.5f * camera.zoom) + (screenX * camera.zoom)
        val worldY = camera.position.y + (viewportHeight * 0.5f * camera.zoom) - (screenY * camera.zoom)
        val tx = kotlin.math.floor(worldX / map.tileSize).toInt()
        val invY = kotlin.math.floor(worldY / map.tileSize).toInt()
        val ty = (map.height - 1) - invY
        return TileCoord(tx, ty)
    }

    fun renderWorld(
        batch: SpriteBatch,
        camera: OrthographicCamera,
        worldState: ClientWorldState,
        deltaSeconds: Float
    ) {
        elapsedSeconds += deltaSeconds
        val activeMap = worldState.activeMap ?: return
        val tileSize = activeMap.tileSize

        // Smoothly center camera on local player's interpolated pixel position
        val localChar = worldState.localCharacter
        val spriteProfile = worldState.gameDefinition?.sprites
        if (localChar != null && localChar.position.mapId == activeMap.id) {
            val vis = worldState.playerVisuals[localChar.id]
            val px = vis?.renderPixelX ?: (localChar.position.tileX * tileSize.toFloat())
            val py = vis?.renderPixelY ?: (localChar.position.tileY * tileSize.toFloat())
            val heroBox = SpritePlacement.place(px, py, activeMap.height, tileSize, spriteProfile, spriteId = localChar.spriteId)
            camera.position.set(
                heroBox.centerX,
                heroBox.drawY + heroBox.drawHeight * 0.5f,
                0f
            )
            // Editor panels docked to the screen edges cover part of the view: centre the player in what is left.
            // (Only here, where the position was just set; shifting a camera that is not re-centred would drift.)
            if (!viewInsets.isNone) {
                val (shiftX, shiftY) = viewInsets.cameraShift(camera.zoom)
                camera.position.x += shiftX
                camera.position.y += shiftY
            }
        }
        // Snap to a whole screen pixel so scaled tiles and sprites do not shimmer (the editor may use in-between scales).
        val zoomScale = 1f / camera.zoom
        camera.position.x = WorldZoom.snap(camera.position.x, zoomScale)
        camera.position.y = WorldZoom.snap(camera.position.y, zoomScale)
        camera.update()
        batch.projectionMatrix = camera.combined
        // The world-space rectangle on screen; tile passes draw only what falls inside it (one tile of margin).
        val halfW = camera.viewportWidth * camera.zoom * 0.5f
        val halfH = camera.viewportHeight * camera.zoom * 0.5f
        viewLeft = camera.position.x - halfW - activeMap.tileSize
        viewRight = camera.position.x + halfW + activeMap.tileSize
        viewBottom = camera.position.y - halfH - activeMap.tileSize
        viewTop = camera.position.y + halfH + activeMap.tileSize

        batch.begin()

        // 1. Render seamless N/S/E/W stitched neighbor maps (Lower Layers)
        renderNeighborMapsPass(batch, activeMap, worldState.neighborMaps, aboveEntities = false)

        // 2. Render Active Map Lower Layers (GROUND, MASK_1, MASK_2)
        val editing = worldState.mapEditorActive
        val hiddenLayers = if (editing) worldState.editor.hiddenLayers else emptySet()
        val dimmed = if (editing && worldState.editor.dimOtherLayers) worldState.editor.layer else null
        renderMapLayers(batch, activeMap, offsetX = 0f, offsetY = 0f, aboveEntities = false, fadeRoof = false, hidden = hiddenLayers, dimExcept = dimmed)

        if (!editing) castShadowSky()?.let { renderTileShadows(batch, activeMap, it) }

        // 3. Render Y-Sorted Entities (Ground Items, Resource Nodes, active & seam NPCs/Players + Paperdoll, Projectiles, Animations)
        if (!editing) {
            renderFacingTargetReticle(batch, activeMap, worldState)
        }
        renderEntitiesAndProjectiles(batch, activeMap, worldState)
        renderXpOrbs(batch, activeMap, worldState)

        // 4. Render Upper Layers (FRINGE_1, FRINGE_2, and auto-fading ROOF)
        val playerUnderRoof = localChar?.let {
            activeMap.getCell(it.position.tileX, it.position.tileY)?.layers?.containsKey(MapLayer.ROOF)
        } ?: false
        renderNeighborMapsPass(batch, activeMap, worldState.neighborMaps, aboveEntities = true)
        renderMapLayers(batch, activeMap, offsetX = 0f, offsetY = 0f, aboveEntities = true, fadeRoof = playerUnderRoof, hidden = hiddenLayers, dimExcept = dimmed)

        // 5. Dynamic 2D Ambient Tint + Point Lights + Weather Particles
        renderLightingAndWeather(batch, activeMap, worldState)

        // 6. Live F1 Map Editor Overlay (Attributes, NPC Spawn Markers, Event Markers, and Live Hover Tile Preview)
        if (worldState.mapEditorActive) {
            if (worldState.editor.showGrid) renderEditorGrid(batch, activeMap)
            if (worldState.showAttributeOverlay) {
                renderAttributeOverlay(batch, activeMap, worldState)
            }
            if (worldState.showAttributeOverlay || worldState.editor.tool == MapEditorTool.EVENT) {
                renderEventMarkers(batch, activeMap, worldState)
            }
            renderEditorHoverCursor(batch, activeMap, worldState)
        }

        // 7. World-Space Overlays: Nameplates, HP Bars, Overhead Chat Bubbles, Floating Combat Text
        renderWorldSpaceUi(batch, activeMap, worldState)

        // 8. Quest waypoints (the tracker's targets on this map)
        renderBossTelegraphs(batch, activeMap, worldState)
        renderGroundSpellReticle(batch, activeMap, worldState)
        renderQuestWaypoints(batch, camera, activeMap, worldState)

        batch.end()
    }

    private val seamTint = Color(0.9f, 0.9f, 0.95f, 1f)

    /** Parsed rarity colours by `#rrggbb`, so the per-frame ground-item pass allocates nothing. */
    private val rarityColors = HashMap<String, Color?>()

    private fun rarityColor(rolls: ItemRolls?, loot: LootRules?): Color? {
        val hex = ItemRollText.color(rolls, loot) ?: return null
        return rarityColors.getOrPut(hex) { com.morgana.client.ui.bag.BagUi.hexColor(hex) }
    }

    private data class DrawableCharacter(
        val sortKey: EntitySortKey,
        val characterId: String,
        val box: SpriteDrawBox,
        val spriteId: Int,
        val direction: com.morgana.domain.spatial.Direction,
        val walkFrame: Int,
        val state: SpriteState?,
        val actionProgress: Float?,
        val paperdollSpriteIds: List<Int>,
        val paperdollLayers: List<PaperdollLayer>,
        val tint: Color,
        /** Rare-variant ground glow colour (Feral/Exalted/Swift/Golden monsters); null for everything else. */
        val aura: Color? = null
    )

    private fun resolveCharacterState(
        vis: EntityVisualSmoothing?,
        isCasting: Boolean
    ): Pair<SpriteState?, Float?> {
        if (isCasting) return SpriteState.CAST to ((elapsedSeconds * 3f) % 1f)
        if (vis != null && vis.leapDuration > 0f) return SpriteState.JUMP to (vis.leapTimer / vis.leapDuration).coerceIn(0f, 1f)
        vis?.actionState?.let { return it to vis.actionProgress }
        if (vis?.isMoving == true) return SpriteState.WALK to null
        return SpriteState.IDLE to null
    }

    private fun renderNeighborMapsPass(
        batch: SpriteBatch,
        activeMap: MapDefinition,
        neighbors: Map<Int, MapDefinition>,
        aboveEntities: Boolean
    ) {
        val ts = activeMap.tileSize.toFloat()
        batch.color = Color(0.78f, 0.78f, 0.82f, 1f)

        neighbors[activeMap.neighbors.eastMapId]?.let { east ->
            renderMapLayers(batch, east, offsetX = activeMap.width * ts, offsetY = sideNeighborOffsetY(activeMap.height, east.height, ts), aboveEntities = aboveEntities, fadeRoof = false)
        }
        neighbors[activeMap.neighbors.westMapId]?.let { west ->
            renderMapLayers(batch, west, offsetX = -west.width * ts, offsetY = sideNeighborOffsetY(activeMap.height, west.height, ts), aboveEntities = aboveEntities, fadeRoof = false)
        }
        neighbors[activeMap.neighbors.northMapId]?.let { north ->
            renderMapLayers(batch, north, offsetX = 0f, offsetY = activeMap.height * ts, aboveEntities = aboveEntities, fadeRoof = false)
        }
        neighbors[activeMap.neighbors.southMapId]?.let { south ->
            renderMapLayers(batch, south, offsetX = 0f, offsetY = -south.height * ts, aboveEntities = aboveEntities, fadeRoof = false)
        }
        batch.color = Color.WHITE
    }

    private fun renderMapLayers(
        batch: SpriteBatch,
        map: MapDefinition,
        offsetX: Float,
        offsetY: Float,
        aboveEntities: Boolean,
        fadeRoof: Boolean,
        hidden: Set<MapLayer> = emptySet(),
        /** When set, every other layer is drawn faded (the editor's "dim other layers" aid). */
        dimExcept: MapLayer? = null
    ) {
        val ts = map.tileSize
        val targetLayers = MapLayer.entries.filter { it.aboveEntities == aboveEntities && it !in hidden }
        val waterFrames = listOf(3 to 0, 0 to 1, 1 to 1)
        val waterAnimIdx = ((elapsedSeconds * 3.5f).toInt()) % waterFrames.size

        // Only the tiles on screen: a 140x140 map would otherwise cost tens of thousands of lookups per layer per frame.
        val vis = VisibleTiles.of(map.width, map.height, ts, offsetX, offsetY, viewLeft, viewRight, viewBottom, viewTop)
        val firstCol = vis.firstCol; val lastCol = vis.lastCol; val firstRow = vis.firstRow; val lastRow = vis.lastRow
        for (layer in targetLayers) {
            val roofAlpha = if (layer == MapLayer.ROOF && fadeRoof) 0.25f else 1f
            val alpha = if (dimExcept != null) roofAlpha * com.morgana.client.editor.EditorShortcuts.layerAlpha(layer, dimExcept, dimOthers = true) else roofAlpha
            batch.setColor(1f, 1f, 1f, alpha)
            for (ty in firstRow until lastRow) {
                val ry = offsetY + toRenderY(map.height, ty, ts)
                for (tx in firstCol until lastCol) {
                    val cell = map.getCell(tx, ty) ?: continue
                    val placement = cell.layers[layer] ?: continue
                    val (srcX, srcY) = if (placement.autotileMode == AutotileMode.ANIMATED_3_FRAME) {
                        waterFrames[waterAnimIdx]
                    } else {
                        com.morgana.domain.map.TileAnimation.sourceAt(placement, animationClockMs())
                    }
                    val region = assets.getTileRegion(placement.tilesetId, srcX, srcY)
                    batch.draw(region, offsetX + tx * ts, ry, ts.toFloat(), ts.toFloat())
                }
            }
        }
        batch.color = Color.WHITE
    }

    /** Draws flying essence orbs homing into the local player and brief absorb pulses on impact. */
    private fun renderXpOrbs(batch: SpriteBatch, activeMap: MapDefinition, worldState: ClientWorldState) {
        val orbs = worldState.xpOrbTracker.activeOrbs
        val pulses = worldState.xpOrbTracker.absorbPulses
        if (orbs.isEmpty() && pulses.isEmpty()) return
        val ch = worldState.localCharacter ?: return
        val vis = worldState.playerVisuals[ch.id]
        val ts = activeMap.tileSize.toFloat()
        val spriteH = (worldState.gameDefinition?.sprites?.cellHeight ?: activeMap.tileSize).toFloat()
        val mapRenderHeight = activeMap.height * ts
        val targetX = (vis?.renderPixelX ?: ch.position.pixelX) + ts * 0.5f
        val targetY = (vis?.renderPixelY ?: ch.position.pixelY) + ts - spriteH * 0.55f
        val targetRenderY = mapRenderHeight - targetY
        val white = assets.whitePixel

        for (orb in orbs) {
            if (orb.mapId != activeMap.id) continue
            val t = orb.progress
            // Two soft trailing afterimages + 3-layer glowing core
            for (trail in 2 downTo 1) {
                val tt = (t - trail * 0.06f).coerceAtLeast(0f)
                if (tt <= 0f) continue
                val (tx, ty) = orb.positionAt(tt, targetX, targetY)
                val renderTy = mapRenderHeight - ty
                val size = if (trail == 2) 4f else 6f
                val alpha = if (trail == 2) 0.18f else 0.32f
                batch.setColor(0.22f, 0.74f, 0.97f, alpha)
                batch.draw(white, tx - size * 0.5f, renderTy - size * 0.5f, size, size)
            }
            val (ox, oy) = orb.positionAt(t, targetX, targetY)
            val renderOy = mapRenderHeight - oy
            batch.setColor(0.22f, 0.74f, 0.97f, 0.30f)
            batch.draw(white, ox - 6f, renderOy - 6f, 12f, 12f)
            batch.setColor(0.49f, 0.83f, 0.99f, 0.70f)
            batch.draw(white, ox - 3.5f, renderOy - 3.5f, 7f, 7f)
            batch.setColor(1f, 1f, 1f, 0.95f)
            batch.draw(white, ox - 1.5f, renderOy - 1.5f, 3f, 3f)
        }

        for (pulse in pulses) {
            if (pulse.mapId != activeMap.id) continue
            val p = pulse.progress
            val radius = 6f + p * 14f
            val alpha = (1f - p) * 0.45f
            batch.setColor(0.49f, 0.83f, 0.99f, alpha)
            batch.draw(white, targetX - radius, targetRenderY - radius, radius * 2f, radius * 2f)
        }
        batch.setColor(1f, 1f, 1f, 1f)
    }

    private fun renderEntitiesAndProjectiles(
        batch: SpriteBatch,
        map: MapDefinition,
        worldState: ClientWorldState
    ) {
        val ts = map.tileSize

        // Ground Items (rendered using real item icon textures!)
        val reduceMotion = com.morgana.client.access.Accessibility.reduceMotion
        val highlightPulse = if (reduceMotion) 0.75f else 0.5f + 0.5f * sin(elapsedSeconds * 5.5f)
        for (item in worldState.groundItemsOnMap) {
            val itemDef = worldState.catalog.items.firstOrNull { it.id == item.stack.itemId }
            val iconRegion = assets.getItemIconRegion(itemDef?.iconId ?: item.stack.itemId)
            val baseX = item.tileX * ts.toFloat()
            val baseY = toRenderY(map.height, item.tileY, ts)
            if (isHighlightedGroundItem(itemDef)) {
                val glowColor = highlightColor(itemDef)
                // Soft vertical drop beacon + flashing halo behind the item
                batch.setColor(glowColor.r, glowColor.g, glowColor.b, 0.14f + highlightPulse * 0.22f)
                batch.draw(assets.whitePixel, baseX + ts * 0.5f - 3f, baseY + ts * 0.5f, 6f, ts * 1.4f)
                val haloSize = ts + 6f + highlightPulse * 6f
                val haloOffset = (ts - haloSize) * 0.5f
                batch.setColor(glowColor.r, glowColor.g, glowColor.b, 0.20f + highlightPulse * 0.30f)
                batch.draw(assets.whitePixel, baseX + haloOffset, baseY + haloOffset, haloSize, haloSize)
                batch.color = Color.WHITE
                val iconSize = 30f + if (reduceMotion) 0f else highlightPulse * 3f
                val offset = (ts - iconSize) * 0.5f
                batch.draw(iconRegion, baseX + offset, baseY + offset, iconSize, iconSize)
            } else if (itemDef?.type == com.morgana.domain.item.ItemType.CURRENCY) {
                val tier = CurrencyPileTier.forAmount(item.stack.amount)
                val rx = baseX + (ts - tier.iconSize) * 0.5f
                val ry = baseY + (ts - tier.iconSize) * 0.5f
                if (tier.haloAlpha > 0f) {
                    val pulseAlpha = tier.haloAlpha + if (reduceMotion) 0f else highlightPulse * 0.12f
                    batch.setColor(tier.tint.r, tier.tint.g, tier.tint.b, pulseAlpha)
                    val haloSize = tier.iconSize + 6f
                    batch.draw(assets.whitePixel, baseX + (ts - haloSize) * 0.5f, baseY + (ts - haloSize) * 0.5f, haloSize, haloSize)
                }
                batch.color = tier.tint
                val offsets = CURRENCY_PILE_OFFSETS
                for (copyIdx in 0 until tier.stackCopies) {
                    val (ox, oy) = offsets[copyIdx]
                    batch.draw(iconRegion, rx + ox, ry + oy, tier.iconSize, tier.iconSize)
                }
                batch.color = Color.WHITE
            } else {
                val rx = baseX + 4f
                val ry = baseY + 4f
                // A rolled item (Diablo-style loot) lies on a bar in its rarity colour, so good drops stand out.
                rarityColor(item.stack.rolls, worldState.gameDefinition?.loot)?.let { c ->
                    batch.color = c
                    batch.draw(assets.whitePixel, rx - 1f, ry - 2f, 26f, 3f)
                    batch.color = Color.WHITE
                }
                batch.draw(iconRegion, rx, ry, 24f, 24f)
            }
        }

        // Depleted Resource Node Overlay
        for ((coord, resState) in worldState.resourcesOnMap) {
            if (resState.depleted) {
                val rx = coord.x * ts.toFloat()
                val ry = toRenderY(map.height, coord.y, ts)
                // Cover the resource sprite with the ground that was under it (tilled soil stays soil, grass stays grass).
                val ground = map.getCell(coord.x, coord.y)?.layers?.get(MapLayer.GROUND)
                val groundRegion = if (ground != null) assets.getTileRegion(ground.tilesetId, ground.srcTileX, ground.srcTileY) else assets.getTileRegion(1, 0, 0)
                batch.draw(groundRegion, rx, ry, ts.toFloat(), ts.toFloat())
            }
        }

        // World objects: walls, chests, totems placed by the world state system. A kind with a tileset sprite draws it; otherwise a coloured block.
        if (worldState.worldObjectsOnMap.isNotEmpty()) {
            val kinds = worldState.gameDefinition?.worldObjects
            for (obj in worldState.worldObjectsOnMap.values) {
                val kind = kinds?.kind(obj.kind)
                val ox = obj.tileX * ts.toFloat()
                val oy = toRenderY(map.height, obj.tileY, ts)
                if (kind != null && kind.spriteTilesetId > 0) {
                    batch.draw(assets.getTileRegion(kind.spriteTilesetId, kind.spriteTileX, kind.spriteTileY), ox, oy, ts.toFloat(), ts.toFloat())
                } else {
                    val shade = 0.35f + (obj.kind.hashCode() and 0xFF) / 255f * 0.45f
                    batch.color = Color(shade, 0.9f * shade, 0.7f * shade, 1f)
                    batch.draw(assets.whitePixel, ox + 2f, oy + 2f, ts - 4f, ts - 4f)
                    batch.color = Color(0f, 0f, 0f, 0.6f)
                    batch.draw(assets.whitePixel, ox + 2f, oy + 2f, ts - 4f, 2f)
                    batch.draw(assets.whitePixel, ox + 2f, oy + ts - 4f, ts - 4f, 2f)
                    batch.color = Color.WHITE
                }
                // A claim shows how far it reaches: a tinted square with an outline (the level is kept in the object's data).
                val claimKind = worldState.gameDefinition?.territory?.claimKind(obj.kind)
                if (claimKind != null) {
                    val radius = claimKind.level(obj.data["level"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1).radiusTiles
                    val left = (obj.tileX - radius) * ts.toFloat()
                    val size = (2 * radius + 1) * ts.toFloat()
                    val bottom = toRenderY(map.height, obj.tileY + radius, ts)
                    val open = obj.data["window"] == "open"
                    batch.color = if (open) Color(0.9f, 0.2f, 0.2f, 0.14f) else Color(0.3f, 0.6f, 1f, 0.12f)
                    batch.draw(assets.whitePixel, left, bottom, size, size)
                    batch.color = if (open) Color(0.9f, 0.2f, 0.2f, 0.8f) else Color(0.3f, 0.6f, 1f, 0.7f)
                    batch.draw(assets.whitePixel, left, bottom, size, 2f)
                    batch.draw(assets.whitePixel, left, bottom + size - 2f, size, 2f)
                    batch.draw(assets.whitePixel, left, bottom, 2f, size)
                    batch.draw(assets.whitePixel, left + size - 2f, bottom, 2f, size)
                    batch.color = Color.WHITE
                }
                val maxHp = kind?.maxHp ?: obj.hp
                if (maxHp > 0 && obj.hp < maxHp) {
                    batch.color = Color(0f, 0f, 0f, 0.7f)
                    batch.draw(assets.whitePixel, ox + 2f, oy + ts - 6f, ts - 4f, 4f)
                    batch.color = Color(0.9f, 0.25f, 0.2f, 1f)
                    batch.draw(assets.whitePixel, ox + 3f, oy + ts - 5f, (ts - 6f) * obj.hp.coerceAtLeast(0) / maxHp, 2f)
                    batch.color = Color.WHITE
                }
            }
        }

        val spriteProfile = worldState.gameDefinition?.sprites

        // Seam ground items (muted)
        if (worldState.seamMaps.isNotEmpty()) {
            batch.color = seamTint
            for (m in worldState.seamMaps) {
                val (ox, oy) = worldState.seamOffsetOf(m.mapId) ?: continue
                for (item in m.groundItems) {
                    val itemDef = worldState.catalog.items.firstOrNull { it.id == item.stack.itemId }
                    val icon = assets.getItemIconRegion(itemDef?.iconId ?: item.stack.itemId)
                    batch.draw(icon, item.tileX * ts + ox + 4f, pixelToRenderY(map.height, item.tileY * ts.toFloat() + oy, ts) + 4f, 24f, 24f)
                }
            }
            batch.color = Color.WHITE
        }

        // Collect active + seam NPCs and players, then sort jointly by foot Y so tall sprites overlap accurately.
        val drawables = ArrayList<DrawableCharacter>(
            worldState.npcsOnMap.size + worldState.playersOnMap.size + worldState.seamMaps.sumOf { it.npcs.size + it.players.size }
        )
        for (npc in worldState.npcsOnMap.values) {
            if (!npc.isAlive) continue
            val npcDef = worldState.catalog.npcs.firstOrNull { it.id == npc.npcId }
            val variantColor = npc.variantId?.let { vid ->
                worldState.gameDefinition?.combat?.enemyVariants?.firstOrNull { it.id == vid }
            }?.let { v -> runCatching { Color.valueOf(v.colorHex.removePrefix("#")) }.getOrNull() }
            val vis = worldState.npcVisuals[npc.instanceId]
            val fx = vis?.renderPixelX ?: (npc.tileX * ts.toFloat())
            val fy = vis?.renderPixelY ?: (npc.tileY * ts.toFloat())
            val (state, progress) = resolveCharacterState(vis, isCasting = false)
            drawables.add(
                DrawableCharacter(
                    sortKey = EntitySortKey(fy, SpritePlacement.KIND_NPC, npc.instanceId),
                    characterId = npc.instanceId,
                    box = SpritePlacement.place(fx, fy, map.height, ts, spriteProfile, spriteId = npcDef?.spriteId ?: 7),
                    spriteId = npcDef?.spriteId ?: 7,
                    direction = vis?.direction ?: npc.direction,
                    walkFrame = vis?.walkFrame ?: 0,
                    state = state,
                    actionProgress = progress,
                    paperdollSpriteIds = emptyList(),
                    paperdollLayers = emptyList(),
                    // Rare variants are recoloured toward their variant colour and stand in a pulsing glow.
                    tint = if (variantColor != null) Color(0.55f + 0.45f * variantColor.r, 0.55f + 0.45f * variantColor.g, 0.55f + 0.45f * variantColor.b, 1f) else Color.WHITE,
                    aura = variantColor
                )
            )
        }
        for (player in worldState.playersOnMap.values) {
            val vis = worldState.playerVisuals[player.characterId]
            val fx = vis?.renderPixelX ?: (player.position.pixelX)
            val fy = vis?.renderPixelY ?: (player.position.pixelY)
            val (state, progress) = resolveCharacterState(vis, isCasting = player.castingSpellId != null && player.castTotalTicks > 0)
            drawables.add(
                DrawableCharacter(
                    sortKey = EntitySortKey(fy, SpritePlacement.KIND_PLAYER, player.characterId),
                    characterId = player.characterId,
                    box = SpritePlacement.place(fx, fy, map.height, ts, spriteProfile, vis?.hopHeight ?: 0f, player.spriteId),
                    spriteId = player.spriteId,
                    direction = vis?.direction ?: player.position.direction,
                    walkFrame = vis?.walkFrame ?: 0,
                    state = state,
                    actionProgress = progress,
                    paperdollSpriteIds = player.paperdollSpriteIds,
                    paperdollLayers = player.paperdollLayers,
                    tint = if (player.isGhost) GHOST_TINT else Color.WHITE
                )
            )
        }
        for (m in worldState.seamMaps) {
            for (npc in m.npcs) {
                val key = "${m.mapId}:${npc.instanceId}"
                val vis = worldState.seamNpcVisuals[key] ?: continue
                val npcDef = worldState.catalog.npcs.firstOrNull { it.id == npc.npcId }
                val (state, progress) = resolveCharacterState(vis, isCasting = false)
                drawables.add(
                    DrawableCharacter(
                        sortKey = EntitySortKey(vis.renderPixelY, SpritePlacement.KIND_NPC, "seam:$key"),
                        characterId = "seam:$key",
                        box = SpritePlacement.place(vis.renderPixelX, vis.renderPixelY, map.height, ts, spriteProfile, spriteId = npcDef?.spriteId ?: 7),
                        spriteId = npcDef?.spriteId ?: 7,
                        direction = vis.direction,
                        walkFrame = vis.walkFrame,
                        state = state,
                        actionProgress = progress,
                        paperdollSpriteIds = emptyList(),
                        paperdollLayers = emptyList(),
                        tint = seamTint
                    )
                )
            }
            for (player in m.players) {
                val vis = worldState.seamPlayerVisuals[player.characterId] ?: continue
                val (state, progress) = resolveCharacterState(vis, isCasting = player.castingSpellId != null && player.castTotalTicks > 0)
                drawables.add(
                    DrawableCharacter(
                        sortKey = EntitySortKey(vis.renderPixelY, SpritePlacement.KIND_PLAYER, "seam:${m.mapId}:${player.characterId}"),
                        characterId = "seam:${m.mapId}:${player.characterId}",
                        box = SpritePlacement.place(vis.renderPixelX, vis.renderPixelY, map.height, ts, spriteProfile, vis.hopHeight, player.spriteId),
                        spriteId = player.spriteId,
                        direction = vis.direction,
                        walkFrame = vis.walkFrame,
                        state = state,
                        actionProgress = progress,
                        paperdollSpriteIds = player.paperdollSpriteIds,
                        paperdollLayers = player.paperdollLayers,
                        tint = seamTint
                    )
                )
            }
        }
        // Spell/combat animations ordered beneath characters (e.g. ground runes, heal circles)
        renderAnimations(batch, map, worldState, aboveEntities = false)

        drawables.sortBy { it.sortKey }
        for (entity in drawables) {
            drawEntityCharacter(batch, entity, ts.toFloat())
        }
        batch.color = Color.WHITE

        // Projectiles in flight
        for (proj in worldState.projectilesOnMap) {
            val rx = proj.currentTileX * ts.toFloat()
            // A tall shooter's hands are above the tile's middle, so the shot starts at the height of its body, not the tile.
            val sourceSprite = if (proj.isFromPlayer) worldState.playersOnMap[proj.sourceEntityId]?.spriteId
            else worldState.npcsOnMap[proj.sourceEntityId]?.let { n -> worldState.catalog.npcs.firstOrNull { it.id == n.npcId }?.spriteId }
            val ry = toRenderY(map.height, proj.currentTileY, ts) + projectileLift(spriteProfile, sourceSprite, ts.toFloat())
            val half = ts * 0.5f
            batch.draw(
                assets.getProjectileRegion(proj.spriteId),
                rx,
                ry,
                half,
                half,
                ts.toFloat(),
                ts.toFloat(),
                1f,
                1f,
                projectileRotationDegrees(proj.direction)
            )
        }

        // Arrows and bolts flying to where a ranged spell lands
        for (shot in worldState.spellShots) {
            val half = ts * 0.5f
            batch.draw(
                assets.getProjectileRegion(shot.spriteId),
                shot.x - half, pixelToRenderY(map.height, shot.y, ts) + half,
                half, half, ts.toFloat(), ts.toFloat(), 1f, 1f, shot.angleDegrees
            )
        }

        // Active Spell/Combat Animations drawn above entities
        renderAnimations(batch, map, worldState, aboveEntities = true)
    }

    /** Soft elliptical ground shadow under an entity; shrinks and fades when jumping. */
    private fun drawGroundShadow(batch: SpriteBatch, box: SpriteDrawBox, tileSizePx: Float, alphaScale: Float = 1f) {
        val fade = (1f - box.lift / 40f).coerceIn(0.3f, 1f)
        val w = tileSizePx * 0.68f * fade
        val h = tileSizePx * 0.16f * fade
        val cx = box.centerX
        val y = box.drawY - box.lift + tileSizePx * 0.04f
        batch.color = Color(0f, 0f, 0f, 0.24f * fade * alphaScale)
        batch.draw(assets.whitePixel, cx - w * 0.5f, y, w, h)
        batch.draw(assets.whitePixel, cx - w * 0.35f, y - h * 0.4f, w * 0.7f, h * 1.8f)
        batch.color = Color.WHITE
    }

    /** A soft pulsing ground glow in the variant colour so rare monsters read at a glance without a nameplate. */
    private fun drawVariantAura(batch: SpriteBatch, box: SpriteDrawBox, tileSizePx: Float, color: Color) {
        val pulse = 0.5f + 0.5f * kotlin.math.sin(elapsedSeconds * 3.2f)
        val cx = box.centerX
        val y = box.drawY - box.lift
        val w = tileSizePx * (1.05f + 0.1f * pulse)
        val h = tileSizePx * (0.34f + 0.04f * pulse)
        batch.setColor(color.r, color.g, color.b, 0.16f + 0.1f * pulse)
        batch.draw(assets.whitePixel, cx - w * 0.5f, y - h * 0.15f, w, h)
        batch.setColor(color.r, color.g, color.b, 0.3f + 0.12f * pulse)
        batch.draw(assets.whitePixel, cx - w * 0.38f, y - h * 0.05f, w * 0.76f, h * 0.75f)
        batch.color = Color.WHITE
    }

    /**
     * A sun or moon shadow: the character's own silhouette flipped about its feet, squashed and leaned away from the light.
     * The lean and length follow the game clock, so shadows swing across the ground through the day. Drawn black with the sprite's
     * alpha, so it needs no extra art and follows every paperdoll layer for free.
     */
    private fun drawCastShadow(
        batch: SpriteBatch,
        region: com.badlogic.gdx.graphics.g2d.TextureRegion,
        box: SpriteDrawBox,
        sky: SkyState,
        alphaScale: Float
    ) {
        val fade = (1f - box.lift / 60f).coerceIn(0.3f, 1f)
        val h = box.drawHeight * sky.shadowLength * 0.55f
        val lean = sky.shadowShear * box.drawHeight * sky.shadowLength * 0.9f
        val rootY = box.drawY - box.lift + box.drawHeight * 0.04f
        val left = box.drawX
        val right = box.drawX + box.drawWidth
        val color = Color.toFloatBits(0f, 0f, 0f, sky.shadowAlpha * fade * alphaScale)
        val v = shadowVertices
        // Bottom-left, top-left, top-right, bottom-right of the sprite: the feet stay put, the head swings out along the ground.
        fun put(i: Int, x: Float, y: Float, u: Float, vv: Float) {
            v[i] = x; v[i + 1] = y; v[i + 2] = color; v[i + 3] = u; v[i + 4] = vv
        }
        put(0, left, rootY, region.u, region.v2)
        put(5, left + lean, rootY - h, region.u, region.v)
        put(10, right + lean, rootY - h, region.u2, region.v)
        put(15, right, rootY, region.u2, region.v2)
        batch.draw(region.texture, v, 0, 20)
    }

    private val shadowVertices = FloatArray(20)
    private val shadowPoint = FloatArray(2)

    /**
     * Sun/moon shadows for standing props (trees, boulders, buildings, towers): every tile of a prop's column is flipped about
     * the base tile's bottom edge and leaned away from the light (see [TileShadowModel]), drawn after the ground and before the
     * entities so characters walk over them. Only the active map is shadowed; neighbour maps are drawn plain.
     */
    private fun renderTileShadows(batch: SpriteBatch, map: MapDefinition, sky: SkyState) {
        val ts = map.tileSize
        val vis = VisibleTiles.of(map.width, map.height, ts, 0f, 0f, viewLeft, viewRight, viewBottom, viewTop)
        // A base above the view still throws its shadow down into it, so look a few rows further up.
        val firstRow = (vis.firstRow - TileShadowModel.MAX_STACK).coerceAtLeast(0)
        val color = Color.toFloatBits(0f, 0f, 0f, sky.shadowAlpha * 0.9f)
        val tsf = ts.toFloat()
        for (ty in firstRow until vis.lastRow) {
            for (tx in (vis.firstCol - TileShadowModel.MAX_STACK).coerceAtLeast(0) until (vis.lastCol + TileShadowModel.MAX_STACK).coerceAtMost(map.width)) {
                if (!TileShadowModel.isBase(map, tx, ty)) continue
                val rootY = toRenderY(map.height, ty, ts) + tsf * 0.04f
                val left = tx * tsf
                val right = left + tsf
                val stack = TileShadowModel.stackHeight(map, tx, ty)
                for (k in 0..stack) {
                    val cell = map.getCell(tx, ty - k) ?: continue
                    val layers = if (k == 0) SHADOW_BASE_LAYERS else SHADOW_UPPER_LAYERS
                    for (layer in layers) {
                        val placement = cell.layers[layer] ?: continue
                        val (srcX, srcY) = com.morgana.domain.map.TileAnimation.sourceAt(placement, animationClockMs())
                        val region = assets.getTileRegion(placement.tilesetId, srcX, srcY)
                        val v = shadowVertices
                        fun put(i: Int, hh: Float, x: Float, u: Float, vv: Float) {
                            TileShadowModel.project(hh, sky, shadowPoint)
                            v[i] = x + shadowPoint[0]; v[i + 1] = rootY + shadowPoint[1]; v[i + 2] = color; v[i + 3] = u; v[i + 4] = vv
                        }
                        // Bottom edge of this tile sits k tiles above the base; its top edge one tile higher.
                        put(0, k * tsf, left, region.u, region.v2)
                        put(5, (k + 1) * tsf, left, region.u, region.v)
                        put(10, (k + 1) * tsf, right, region.u2, region.v)
                        put(15, k * tsf, right, region.u2, region.v2)
                        batch.draw(region.texture, v, 0, 20)
                    }
                }
            }
        }
    }


    /** The cast-shadow sky for this frame, or null when the oval blob should be used (no clock, indoors, or the effect is off). */
    private fun castShadowSky(): SkyState? = if (VisualFx.shadows) VisualFx.sky else null

    private fun drawEntityCharacter(batch: SpriteBatch, entity: DrawableCharacter, tileSizePx: Float) {
        val castSky = castShadowSky()
        // Under a cast shadow the oval shrinks to a small contact shadow so feet still sit on the ground.
        drawGroundShadow(batch, entity.box, tileSizePx, entity.tint.a * if (castSky != null) 0.55f else 1f)
        entity.aura?.let { drawVariantAura(batch, entity.box, tileSizePx, it) }
        batch.color = entity.tint
        if (entity.paperdollLayers.isNotEmpty()) {
            val composed = assets.getOrComposeCharacterRegion(
                characterId = entity.characterId,
                spriteId = entity.spriteId,
                layers = entity.paperdollLayers,
                direction = entity.direction,
                walkFrame = entity.walkFrame,
                state = entity.state,
                elapsedSeconds = elapsedSeconds,
                actionProgress = entity.actionProgress
            )
            if (composed != null) {
                castSky?.let { drawCastShadow(batch, composed, entity.box, it, entity.tint.a) }
                batch.draw(composed, entity.box.drawX, entity.box.drawY, entity.box.drawWidth, entity.box.drawHeight)
                return
            }
            val ordered = PaperdollComposer.composeLayers(entity.paperdollLayers, entity.direction)
            var bodyDrawn = ordered.any { it.slot == PaperdollSlot.BODY }
            for (layer in ordered) {
                if (!bodyDrawn && PaperdollComposer.drawOrderRank(PaperdollSlot.BODY, entity.direction) < PaperdollComposer.drawOrderRank(layer.slot, entity.direction)) {
                    batch.color = entity.tint
                    drawBodyCell(batch, entity)
                    bodyDrawn = true
                }
                val (pdRegion, isDirectional) = assets.getPaperdollLayerFrame(
                    paperdollId = layer.spriteId,
                    direction = entity.direction,
                    walkFrame = entity.walkFrame,
                    state = entity.state,
                    elapsedSeconds = elapsedSeconds,
                    actionProgress = entity.actionProgress
                ) ?: continue
                batch.color = multiplyTint(entity.tint, layer.tintHex)
                drawPaperdollRegion(batch, entity.box, pdRegion, isDirectional, tileSizePx)
            }
            if (!bodyDrawn) {
                batch.color = entity.tint
                drawBodyCell(batch, entity)
            }
            return
        }

        drawBodyCell(batch, entity)
        for (pdId in entity.paperdollSpriteIds) {
            val (pdRegion, isDirectional) = assets.getPaperdollLayerFrame(
                paperdollId = pdId,
                direction = entity.direction,
                walkFrame = entity.walkFrame,
                state = entity.state,
                elapsedSeconds = elapsedSeconds,
                actionProgress = entity.actionProgress
            ) ?: continue
            drawPaperdollRegion(batch, entity.box, pdRegion, isDirectional, tileSizePx)
        }
    }

    private fun drawBodyCell(batch: SpriteBatch, entity: DrawableCharacter) {
        val region = assets.getSpriteRegion(
            spriteId = entity.spriteId,
            direction = entity.direction,
            walkFrame = entity.walkFrame,
            state = entity.state,
            elapsedSeconds = elapsedSeconds,
            actionProgress = entity.actionProgress
        )
        castShadowSky()?.let { drawCastShadow(batch, region, entity.box, it, entity.tint.a) }
        batch.draw(region, entity.box.drawX, entity.box.drawY, entity.box.drawWidth, entity.box.drawHeight)
    }

    private fun drawPaperdollRegion(
        batch: SpriteBatch,
        box: SpriteDrawBox,
        region: com.badlogic.gdx.graphics.g2d.TextureRegion,
        isDirectional: Boolean,
        tileSizePx: Float
    ) {
        if (isDirectional) {
            batch.draw(region, box.drawX, box.drawY, box.drawWidth, box.drawHeight)
        } else {
            val (ox, oy) = SpritePlacement.placeOverlay(box, tileSizePx)
            batch.draw(region, ox, oy, tileSizePx, tileSizePx)
        }
    }

    private fun multiplyTint(base: Color, tintHex: String?): Color {
        val rgba = PaperdollCompositeCache.parseHexRgba8888(tintHex)
        val r = ((rgba ushr 24) and 0xFF) / 255f
        val g = ((rgba ushr 16) and 0xFF) / 255f
        val b = ((rgba ushr 8) and 0xFF) / 255f
        return Color(base.r * r, base.g * g, base.b * b, base.a)
    }

    private fun renderAnimations(
        batch: SpriteBatch,
        map: MapDefinition,
        worldState: ClientWorldState,
        aboveEntities: Boolean
    ) {
        val ts = map.tileSize
        for (anim in worldState.activeAnimations) {
            if (anim.renderAboveEntities != aboveEntities) continue
            if (anim.elapsedSeconds < 0f) continue // still waiting for its shot to land
            val available = assets.animationFrameCount(anim.spriteSheetId)
            val frameIndex = AnimationStripLayout.resolveFrameIndex(
                elapsedSeconds = anim.elapsedSeconds,
                frameDurationMs = anim.frameDurationMs,
                definitionFrames = anim.frameCount,
                availableFrames = available
            )
            val rx = anim.tileX * ts.toFloat()
            val ry = toRenderY(map.height, anim.tileY, ts)
            batch.draw(assets.getAnimationFrame(anim.spriteSheetId, frameIndex), rx, ry, ts.toFloat(), ts.toFloat())
        }
    }

    private fun renderLightingAndWeather(
        batch: SpriteBatch,
        map: MapDefinition,
        worldState: ClientWorldState
    ) {
        val ts = map.tileSize
        val dt = (elapsedSeconds - lastLightingSeconds).coerceIn(0f, 0.1f)
        lastLightingSeconds = elapsedSeconds
        val blend = (dt * LIGHT_FADE_PER_SECOND).coerceAtMost(1f)

        // Darkness and weather ease between maps instead of cutting, so walking over a border feels continuous.
        val lit = map.isIndoors || map.ambientLightHex != "#FFFDF5"
        val darknessTarget = if (lit) {
            val c = runCatching { Color.valueOf(map.ambientLightHex.removePrefix("#")) }.getOrDefault(Color.WHITE)
            (1f - ((c.r + c.g + c.b) / 3f)).coerceIn(0f, 0.65f)
        } else {
            0f
        }
        shownDarkness = if (!lightingInitialised) darknessTarget else shownDarkness + (darknessTarget - shownDarkness) * blend
        if (shownDarkness > 0.05f) {
            // Cover the neighbouring maps too: they are drawn on both sides of this one.
            batch.setColor(0.05f, 0.06f, 0.14f, shownDarkness)
            batch.draw(assets.whitePixel, -map.width * ts.toFloat(), -map.height * ts.toFloat(), map.width * ts * 3f, map.height * ts * 3f)
        }

        if (lit) {

            batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
            val pulse = 1f + 0.06f * sin(elapsedSeconds * 6f)
            for (ty in 0 until map.height) {
                for (tx in 0 until map.width) {
                    val attr = map.getCell(tx, ty)?.attribute
                    if (attr is TileAttribute.LightSource) {
                        val lCol = runCatching { Color.valueOf(attr.colorHex.removePrefix("#")) }.getOrDefault(Color.ORANGE)
                        val r = attr.radiusPixels * (if (attr.flicker) pulse else 1f)
                        val cx = tx * ts + ts * 0.5f
                        val cy = toRenderY(map.height, ty, ts) + ts * 0.5f
                        batch.setColor(lCol.r, lCol.g, lCol.b, 0.45f)
                        batch.draw(assets.radialLightTexture, cx - r, cy - r, r * 2f, r * 2f)
                    }
                }
            }
            batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
            batch.color = Color.WHITE
        }

        // Weather fades out, switches, then fades in. Zone weather for exterior maps; interiors are always clear.
        val weather = worldState.weatherNow(map)
        shownIntensity += (weather.intensity - shownIntensity) * blend
        if (!lightingInitialised) {
            shownWeather = weather.type
            shownIntensity = weather.intensity
            weatherAlpha = 1f
        } else if (shownWeather != weather.type) {
            weatherAlpha -= blend + dt * 0.5f
            if (weatherAlpha <= 0f) {
                weatherAlpha = 0f
                shownWeather = weather.type
            }
        } else {
            weatherAlpha = (weatherAlpha + blend).coerceAtMost(1f)
        }
        lightingInitialised = true

        if (shownWeather != WeatherType.NONE && weatherAlpha > 0.01f) {
            val mapW = map.width * ts
            val mapH = map.height * ts
            val wa = weatherAlpha
            val still = com.morgana.client.access.Accessibility.reduceMotion
            // Reduce motion: no falling streaks or flakes, just a faint still tint so the weather stays readable.
            fun stillTint(r: Float, g: Float, b: Float, a: Float) {
                batch.setColor(r, g, b, a * wa)
                batch.draw(assets.whitePixel, -mapW.toFloat(), -mapH.toFloat(), mapW * 3f, mapH * 3f)
            }
            when (shownWeather) {
                WeatherType.RAIN -> if (still) stillTint(0.55f, 0.75f, 1f, 0.06f + 0.14f * shownIntensity) else {
                    batch.setColor(0.55f, 0.75f, 1f, 0.55f * wa)
                    for (i in 0 until (20 + 90 * shownIntensity).toInt()) {
                        val px = ((i * 73 + (elapsedSeconds * 160f).toInt()) % mapW).toFloat()
                        val py = ((i * 97 - (elapsedSeconds * 340f).toInt()).mod(mapH)).toFloat()
                        batch.draw(assets.whitePixel, px, py, 2f, 10f)
                    }
                }
                WeatherType.SNOW, WeatherType.ASH -> if (still) stillTint(0.95f, 0.95f, 1f, 0.06f + 0.16f * shownIntensity) else {
                    batch.setColor(0.95f, 0.95f, 1f, 0.7f * wa)
                    for (i in 0 until (15 + 70 * shownIntensity).toInt()) {
                        val px = ((i * 61 + (elapsedSeconds * 40f).toInt()) % mapW).toFloat()
                        val py = ((i * 89 - (elapsedSeconds * 85f).toInt()).mod(mapH)).toFloat()
                        batch.draw(assets.whitePixel, px, py, 3f, 3f)
                    }
                }
                WeatherType.FOG -> {
                    batch.setColor(0.8f, 0.85f, 0.92f, (0.06f + 0.2f * shownIntensity) * wa)
                    batch.draw(assets.whitePixel, -mapW.toFloat(), -mapH.toFloat(), mapW * 3f, mapH * 3f)
                }
                else -> {}
            }
            batch.color = Color.WHITE
        }
    }

    private fun renderAttributeOverlay(batch: SpriteBatch, map: MapDefinition, worldState: ClientWorldState) {
        val ts = map.tileSize
        for (ty in 0 until map.height) {
            val ry = toRenderY(map.height, ty, ts)
            for (tx in 0 until map.width) {
                val attr = map.getCell(tx, ty)?.attribute ?: TileAttribute.None
                if (attr is TileAttribute.None) continue
                val (badge, color) = when (attr) {
                    is TileAttribute.Blocked -> "B" to Color.RED
                    is TileAttribute.DirectionalBlock -> "D" to Color.ORANGE
                    is TileAttribute.Warp -> "W:${attr.targetMapId}" to Color.CYAN
                    is TileAttribute.KeyDoor -> "K:${attr.keyItemId}" to Color.GOLD
                    is TileAttribute.ResourceNode -> "R:${attr.resourceId}" to Color.GREEN
                    is TileAttribute.ShopTile -> "S:${attr.shopId}" to Color.YELLOW
                    is TileAttribute.BankTile -> "BANK" to Color.GOLD
                    is TileAttribute.HealTrap -> (if (attr.isTrapDamage) "TRAP" else "HEAL") to Color.PINK
                    is TileAttribute.Slide -> "SLD" to Color.SKY
                    is TileAttribute.LightSource -> "LGT" to Color.ORANGE
                    else -> "A" to Color.WHITE
                }
                batch.setColor(color.r, color.g, color.b, 0.28f)
                batch.draw(assets.whitePixel, tx * ts.toFloat(), ry, ts.toFloat(), ts.toFloat())
                batch.color = Color.WHITE
                font.color = color
                font.draw(batch, badge, tx * ts + 3f, ry + 20f)
            }
        }

        // Also show NPC spawn slots configured on the map draft
        val draft = worldState.workingMapDraft ?: map
        for (spawn in draft.npcSpawns) {
            val rx = spawn.spawnX * ts.toFloat()
            val ry = toRenderY(map.height, spawn.spawnY, ts)
            batch.setColor(0.8f, 0.2f, 1f, 0.35f)
            batch.draw(assets.whitePixel, rx, ry, ts.toFloat(), ts.toFloat())
            batch.color = Color.WHITE
            font.color = Color.MAGENTA
            font.draw(batch, "N#${spawn.npcId}", rx + 2f, ry + 14f)
        }
        font.color = Color.WHITE
    }

    /** Draws a boss's danger zones: a red field over the tiles that will be hit, filling in as the wind-up runs out. */
    private fun renderBossTelegraphs(batch: SpriteBatch, map: MapDefinition, worldState: ClientWorldState) {
        val zones = worldState.telegraphs
        if (zones.isEmpty()) return
        val now = System.currentTimeMillis()
        zones.removeAll { it.untilMs < now || it.mapId != map.id }
        val ts = map.tileSize.toFloat()
        for (z in zones) {
            val progress = ((now - z.startedMs).toFloat() / (z.untilMs - z.startedMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
            val alpha = 0.18f + 0.30f * progress
            for (dx in -z.radius..z.radius) for (dy in -z.radius..z.radius) {
                val tx = z.centerX + dx
                val ty = z.centerY + dy
                if (!map.isInBounds(tx, ty)) continue
                batch.setColor(0.93f, 0.15f, 0.15f, alpha)
                batch.draw(assets.whitePixel, tx * ts, toRenderY(map.height, ty, map.tileSize), ts, ts)
                if (dx == -z.radius || dx == z.radius || dy == -z.radius || dy == z.radius) {
                    batch.setColor(1f, 0.45f, 0.35f, 0.75f)
                    val ry = toRenderY(map.height, ty, map.tileSize)
                    if (dy == -z.radius) batch.draw(assets.whitePixel, tx * ts, ry + ts - 2f, ts, 2f)
                    if (dy == z.radius) batch.draw(assets.whitePixel, tx * ts, ry, ts, 2f)
                    if (dx == -z.radius) batch.draw(assets.whitePixel, tx * ts, ry, 2f, ts)
                    if (dx == z.radius) batch.draw(assets.whitePixel, tx * ts + ts - 2f, ry, 2f, ts)
                }
            }
        }
        batch.color = Color.WHITE
    }

    /** Draws the cursor-aimed AoE reticle while the player is placing a GROUND_AOE spell. */
    private fun renderGroundSpellReticle(batch: SpriteBatch, map: MapDefinition, worldState: ClientWorldState) {
        val spellId = worldState.pendingGroundSpellId ?: return
        val hovered = worldState.hoveredTile ?: return
        val ch = worldState.localCharacter ?: return
        val spell = worldState.catalog.spells.firstOrNull { it.id == spellId } ?: return
        val reach = spell.rangeTiles.coerceAtLeast(1)
        val cx = hovered.x.coerceIn(ch.position.tileX - reach, ch.position.tileX + reach).coerceIn(0, (map.width - 1).coerceAtLeast(0))
        val cy = hovered.y.coerceIn(ch.position.tileY - reach, ch.position.tileY + reach).coerceIn(0, (map.height - 1).coerceAtLeast(0))
        val radius = spell.aoeRadiusTiles.coerceAtLeast(1)
        val ts = map.tileSize.toFloat()
        val fillR = if (spell.isHealing) 0.20f else 0.95f
        val fillG = if (spell.isHealing) 0.85f else 0.55f
        val fillB = if (spell.isHealing) 0.55f else 0.15f
        for (dx in -radius..radius) for (dy in -radius..radius) {
            val tx = cx + dx
            val ty = cy + dy
            if (!map.isInBounds(tx, ty)) continue
            val ry = toRenderY(map.height, ty, map.tileSize)
            batch.setColor(fillR, fillG, fillB, 0.26f)
            batch.draw(assets.whitePixel, tx * ts, ry, ts, ts)
            if (dx == -radius || dx == radius || dy == -radius || dy == radius) {
                batch.setColor(fillR, fillG, fillB, 0.85f)
                if (dy == -radius) batch.draw(assets.whitePixel, tx * ts, ry + ts - 2f, ts, 2f)
                if (dy == radius) batch.draw(assets.whitePixel, tx * ts, ry, ts, 2f)
                if (dx == -radius) batch.draw(assets.whitePixel, tx * ts, ry, 2f, ts)
                if (dx == radius) batch.draw(assets.whitePixel, tx * ts + ts - 2f, ry, 2f, ts)
            }
        }
        batch.color = Color.WHITE
    }

    /** Draws a marker on each tile that has an event (using the live draft position for the event open in EventsPanel). */
    /**
     * Marks where the tracked quests point: a gold diamond bobbing over the target tile, or, when the target is outside the
     * view, a smaller one on the screen edge in its direction. [QuestTracker] decides which tiles; this only draws them.
     */
    private fun renderQuestWaypoints(batch: SpriteBatch, camera: OrthographicCamera, map: MapDefinition, worldState: ClientWorldState) {
        val waypoints = worldState.questWaypoints()
        if (waypoints.isEmpty()) return
        val ts = map.tileSize.toFloat()
        val bob = if (com.morgana.client.access.Accessibility.reduceMotion) 0f else kotlin.math.sin(elapsedSeconds * 4f) * 2.5f
        val halfW = camera.viewportWidth * camera.zoom * 0.5f
        val halfH = camera.viewportHeight * camera.zoom * 0.5f
        val left = camera.position.x - halfW
        val right = camera.position.x + halfW
        val bottom = camera.position.y - halfH
        val top = camera.position.y + halfH
        for (wp in waypoints) {
            val cx = wp.tileX * ts + ts * 0.5f
            val cy = toRenderY(map.height, wp.tileY, map.tileSize) + ts * 0.5f
            if (cx in left..right && cy in bottom..top) {
                drawQuestDiamond(batch, cx, cy + ts * 0.85f + bob, 7f)
                drawCenteredText(batch, wp.label, cx, cy + ts * 1.45f + bob, Color.GOLD)
            } else {
                val margin = 16f
                drawQuestDiamond(batch, cx.coerceIn(left + margin, right - margin), cy.coerceIn(bottom + margin, top - margin), 5f)
            }
        }
        font.color = Color.WHITE
    }

    private fun drawQuestDiamond(batch: SpriteBatch, cx: Float, cy: Float, half: Float) {
        batch.setColor(0f, 0f, 0f, 0.7f)
        batch.draw(assets.whitePixel, cx - half - 1.5f, cy - half - 1.5f, half + 1.5f, half + 1.5f, (half + 1.5f) * 2f, (half + 1.5f) * 2f, 1f, 1f, 45f)
        batch.setColor(1f, 0.82f, 0.2f, 1f)
        batch.draw(assets.whitePixel, cx - half, cy - half, half, half, half * 2f, half * 2f, 1f, 1f, 45f)
        batch.color = Color.WHITE
    }

    private fun renderEventMarkers(batch: SpriteBatch, map: MapDefinition, worldState: ClientWorldState) {
        val ts = map.tileSize
        val editing = worldState.editingEventDraft
        val events = buildList {
            for (e in worldState.catalog.events) {
                add(if (editing != null && e.id == editing.id) editing else e)
            }
            if (editing != null && worldState.catalog.events.none { it.id == editing.id }) {
                add(editing)
            }
        }.filter { it.mapId == map.id && map.isInBounds(it.tileX, it.tileY) }

        for (e in events) {
            val rx = e.tileX * ts.toFloat()
            val ry = toRenderY(map.height, e.tileY, ts)
            val selected = editing?.id == e.id
            if (selected) batch.setColor(1f, 0.78f, 0.15f, 0.42f) else batch.setColor(0.15f, 0.78f, 0.85f, 0.30f)
            batch.draw(assets.whitePixel, rx, ry, ts.toFloat(), ts.toFloat())
            val border = if (selected) 2f else 1f
            if (selected) batch.setColor(1f, 0.9f, 0.25f, 0.95f) else batch.setColor(0.35f, 0.95f, 1f, 0.85f)
            batch.draw(assets.whitePixel, rx, ry, ts.toFloat(), border)
            batch.draw(assets.whitePixel, rx, ry + ts - border, ts.toFloat(), border)
            batch.draw(assets.whitePixel, rx, ry, border, ts.toFloat())
            batch.draw(assets.whitePixel, rx + ts - border, ry, border, ts.toFloat())
            batch.color = Color.WHITE
            font.color = if (selected) Color.GOLD else Color.valueOf("67E8F9")
            font.draw(batch, "E#${e.id}", rx + 2f, ry + ts - 3f)
        }
        font.color = Color.WHITE
    }

    /** A tinted box over the tiles a rectangle drag will fill (green) or clear (red). */
    private fun drawRectangleDrag(batch: SpriteBatch, map: MapDefinition, a: com.morgana.domain.spatial.TileCoord, b: com.morgana.domain.spatial.TileCoord, erase: Boolean) {
        val ts = map.tileSize.toFloat()
        val minX = minOf(a.x, b.x)
        val maxX = maxOf(a.x, b.x)
        val minY = minOf(a.y, b.y)
        val maxY = maxOf(a.y, b.y)
        if (erase) batch.setColor(1f, 0.25f, 0.25f, 0.35f) else batch.setColor(0.3f, 1f, 0.45f, 0.3f)
        batch.draw(assets.whitePixel, minX * ts, toRenderY(map.height, maxY, map.tileSize), (maxX - minX + 1) * ts, (maxY - minY + 1) * ts)
        batch.color = Color.WHITE
    }

    /** Thin lines between tiles so the editor shows where each tile starts. */
    private fun renderEditorGrid(batch: SpriteBatch, map: MapDefinition) {
        val ts = map.tileSize.toFloat()
        val w = map.width * ts
        val h = map.height * ts
        batch.setColor(1f, 1f, 1f, 0.16f)
        for (x in 0..map.width) batch.draw(assets.whitePixel, x * ts - 0.5f, 0f, 1f, h)
        for (y in 0..map.height) batch.draw(assets.whitePixel, 0f, y * ts - 0.5f, w, 1f)
        batch.color = Color.WHITE
    }

    private fun renderEditorHoverCursor(batch: SpriteBatch, map: MapDefinition, worldState: ClientWorldState) {
        val hovered = worldState.hoveredTile ?: return
        if (!map.isInBounds(hovered)) return
        val ts = map.tileSize
        val rx = hovered.x * ts.toFloat()
        val ry = toRenderY(map.height, hovered.y, ts)

        // Ghost preview of what the brush would place: the whole stamp for the brush and rectangle tools, one tile for the bucket.
        val editor = worldState.editor
        if (editor.tool in setOf(MapEditorTool.BRUSH, MapEditorTool.RECTANGLE, MapEditorTool.FLOOD_FILL) && !editor.attributeOnly) {
            val footprint = if (editor.tool == MapEditorTool.FLOOD_FILL) editor.footprint(hovered.x, hovered.y).take(1) else editor.footprint(hovered.x, hovered.y)
            batch.setColor(1f, 1f, 1f, 0.55f)
            for ((coord, placement) in footprint) {
                val (sx, sy) = com.morgana.domain.map.TileAnimation.sourceAt(placement, animationClockMs())
                batch.draw(assets.getTileRegion(placement.tilesetId, sx, sy), coord.x * ts.toFloat(), toRenderY(map.height, coord.y, ts), ts.toFloat(), ts.toFloat())
            }
        }

        editor.rectAnchor?.let { drawRectangleDrag(batch, map, it, hovered, editor.rectIsErase) }

        // Crisp yellow cursor outline around hovered tile
        batch.setColor(1f, 0.9f, 0.1f, 0.9f)
        batch.draw(assets.whitePixel, rx, ry, ts.toFloat(), 2f)
        batch.draw(assets.whitePixel, rx, ry + ts - 2f, ts.toFloat(), 2f)
        batch.draw(assets.whitePixel, rx, ry, 2f, ts.toFloat())
        batch.draw(assets.whitePixel, rx + ts - 2f, ry, 2f, ts.toFloat())
        batch.color = Color.WHITE

        drawCenteredText(
            batch,
            "(${hovered.x}, ${hovered.y}) [${worldState.selectedLayer.name}]",
            rx + ts * 0.5f,
            ry + ts + 14f,
            Color.YELLOW
        )
    }

    /** Draws a subtle corner-bracket reticle under the tile the local player is facing when targeting an NPC, resource, or service. */
    private fun renderFacingTargetReticle(
        batch: SpriteBatch,
        map: MapDefinition,
        worldState: ClientWorldState
    ) {
        val pos = worldState.localCharacter?.position ?: return
        if (pos.mapId != map.id) return
        val spec = TargetVisualModel.facingReticle(
            facingTarget = worldState.facingTarget(),
            tileX = pos.tileX + pos.direction.dx,
            tileY = pos.tileY + pos.direction.dy,
            mapWidth = map.width,
            mapHeight = map.height
        ) ?: return

        val ts = map.tileSize.toFloat()
        val rx = spec.tileX * ts
        val ry = toRenderY(map.height, spec.tileY, map.tileSize)
        val c = Color.valueOf(spec.colorHex)

        batch.setColor(c.r, c.g, c.b, 0.14f)
        batch.draw(assets.whitePixel, rx, ry, ts, ts)

        val corner = (ts * 0.25f).coerceAtLeast(5f)
        val thick = 2f
        batch.setColor(c.r, c.g, c.b, 0.85f)
        // Bottom-left
        batch.draw(assets.whitePixel, rx, ry, corner, thick)
        batch.draw(assets.whitePixel, rx, ry, thick, corner)
        // Bottom-right
        batch.draw(assets.whitePixel, rx + ts - corner, ry, corner, thick)
        batch.draw(assets.whitePixel, rx + ts - thick, ry, thick, corner)
        // Top-left
        batch.draw(assets.whitePixel, rx, ry + ts - thick, corner, thick)
        batch.draw(assets.whitePixel, rx, ry + ts - corner, thick, corner)
        // Top-right
        batch.draw(assets.whitePixel, rx + ts - corner, ry + ts - thick, corner, thick)
        batch.draw(assets.whitePixel, rx + ts - thick, ry + ts - corner, thick, corner)
        batch.color = Color.WHITE
    }

    private fun renderWorldSpaceUi(
        batch: SpriteBatch,
        map: MapDefinition,
        worldState: ClientWorldState
    ) {
        val ts = map.tileSize
        val spriteProfile = worldState.gameDefinition?.sprites

        val floorBossNpcId = map.generator?.bossNpcId ?: 0

        // NPC Nameplates & HP Bars
        for (npc in worldState.npcsOnMap.values) {
            if (!npc.isAlive) continue
            val npcDef = worldState.catalog.npcs.firstOrNull { it.id == npc.npcId } ?: continue
            val vis = worldState.npcVisuals[npc.instanceId]
            val fx = vis?.renderPixelX ?: (npc.tileX * ts.toFloat())
            val fy = vis?.renderPixelY ?: (npc.tileY * ts.toFloat())
            val box = SpritePlacement.place(fx, fy, map.height, ts, spriteProfile, spriteId = npcDef.spriteId)
            val cx = box.centerX
            val topY = box.uiTopY

            val variant = npc.variantId?.let { vid ->
                worldState.gameDefinition?.combat?.enemyVariants?.firstOrNull { it.id == vid }
            }
            val style = TargetVisualModel.npcNameplateStyle(
                npcDef = if (npc.displayName.isNotBlank()) npcDef.copy(name = npc.displayName) else npcDef,
                floorBossNpcId = floorBossNpcId,
                playerLevel = worldState.localCharacter?.level,
                levelOffsetColors = worldState.gameDefinition?.combat?.levelOffsetXpScaling == true,
                variant = variant,
                levelShift = npc.scale?.levelBonus ?: 0
            )
            val barWidth = if (style.isBoss) 36f else 28f
            val lifeVital = npc.vitals["hp"] ?: npc.vitals.values.firstOrNull()
            val curHp = lifeVital?.cur ?: 0
            val maxHp = lifeVital?.max ?: 1
            val isDamaged = curHp < maxHp
            val isMonster = (npcDef.behavior == com.morgana.domain.content.NpcBehavior.ATTACK_ON_SIGHT ||
                npcDef.behavior == com.morgana.domain.content.NpcBehavior.PASSIVE) &&
                npcDef.eventId == 0 && npcDef.shopId == 0
            // Plain, uninjured monsters stay anonymous; rare variants always show their bar and [Label] name.
            val isRare = isMonster && variant != null
            if (isDamaged || style.isBoss || isRare) {
                drawHpBar(batch, cx - barWidth * 0.5f, topY, barWidth, 4f, curHp, maxHp, hostile = isMonster)
            }
            if (!isMonster || isDamaged || style.isBoss || isRare) {
                drawCenteredText(batch, style.label, cx, topY + 16f, Color.valueOf(style.colorHex))
            }

            val questIndicator = TargetVisualModel.npcQuestIndicator(
                npcDef = npcDef,
                events = worldState.catalog.events,
                quests = worldState.catalog.quests,
                player = worldState.localCharacter
            )
            if (questIndicator != null) {
                drawCenteredText(batch, "[${questIndicator.symbol}]", cx, topY + 30f, Color.valueOf(questIndicator.colorHex))
            }

            worldState.chatBubbles[npc.instanceId]?.let { bubble ->
                val bubbleY = if (questIndicator != null) topY + 46f else topY + 32f
                drawChatBubble(batch, bubble.text, cx, bubbleY)
            }
        }

        // Player Nameplates (Color-coded by Access Rank & PK Status) + HP Bars + Chat Bubbles
        for (player in worldState.playersOnMap.values) {
            val vis = worldState.playerVisuals[player.characterId]
            val fx = vis?.renderPixelX ?: player.position.pixelX
            val fy = vis?.renderPixelY ?: player.position.pixelY
            val box = SpritePlacement.place(fx, fy, map.height, ts, spriteProfile, vis?.hopHeight ?: 0f, player.spriteId)
            val cx = box.centerX
            val topY = box.uiTopY

            drawHpBar(batch, cx - 14f, topY, 28f, 4f, player.hp, player.maxHp)
            var nameY = topY + 16f
            if (player.castingSpellId != null && player.castTotalTicks > 0) {
                val ratio = worldState.playerCastProgress[player.characterId] ?: 0f
                drawCastBar(batch, cx - 14f, topY + 6f, 28f, 3f, ratio)
                val spellName = worldState.catalog.spells.firstOrNull { it.id == player.castingSpellId }?.name ?: "Casting..."
                drawCenteredText(batch, spellName, cx, topY + 15f, Color.CYAN)
                nameY = topY + 26f
            }

            val label = buildString {
                if (player.guildTag.isNotBlank()) append("[${player.guildTag}] ")
                append(player.name)
                if (player.isGhost) append(" (ghost)")
                if (player.stallTitle.isNotBlank()) append(" [${player.stallTitle}]")
            }
            val pkRules = worldState.gameDefinition?.combat?.pk
            val nameColor = when {
                pkRules != null && player.pkPoints >= pkRules.blackNameThreshold -> Color.valueOf("111827")
                (pkRules != null && player.pkPoints >= pkRules.redNameThreshold) || player.isRoguePk -> Color.SCARLET
                else -> runCatching { Color.valueOf(player.nameplateColorHex.removePrefix("#")) }.getOrDefault(Color.WHITE)
            }
            drawCenteredText(batch, label, cx, nameY, nameColor)

            worldState.chatBubbles[player.characterId]?.let { bubble ->
                drawChatBubble(batch, bubble.text, cx, nameY + 16f)
            }
        }

        // Ground Item Labels (highlighted drops always; all drops when holding Alt)
        val labelFlash = if (com.morgana.client.access.Accessibility.reduceMotion) 0f else 0.5f + 0.5f * sin(elapsedSeconds * 5.5f)
        val itemsById = worldState.catalog.items.associateBy { it.id }
        val groundLabels = com.morgana.client.ui.GroundLootLabels.layout(
            groundItems = worldState.groundItemsOnMap,
            itemsById = itemsById,
            lootRules = worldState.gameDefinition?.loot,
            mapHeightTiles = map.height,
            tileSize = ts,
            showAll = worldState.showAllGroundLabels,
            isHighlighted = ::isHighlightedGroundItem,
            measureWidth = { text -> glyphLayout.setText(font, text); glyphLayout.width },
            textHeight = font.capHeight.coerceAtLeast(10f)
        )
        worldState.lastGroundLootLabels = groundLabels
        for (entry in groundLabels) {
            val baseCol = if (entry.highlighted) {
                highlightColor(itemsById[entry.item.stack.itemId])
            } else {
                runCatching { Color.valueOf(entry.colorHex.removePrefix("#")) }.getOrDefault(Color.WHITE)
            }
            val textCol = if (entry.highlighted) {
                Color(
                    baseCol.r + (1f - baseCol.r) * (labelFlash * 0.45f),
                    baseCol.g + (1f - baseCol.g) * (labelFlash * 0.45f),
                    baseCol.b + (1f - baseCol.b) * (labelFlash * 0.45f),
                    1f
                )
            } else {
                baseCol
            }
            batch.setColor(0.06f, 0.07f, 0.11f, 0.80f)
            batch.draw(assets.whitePixel, entry.boxX, entry.boxY, entry.boxW, entry.boxH)
            batch.color = Color.WHITE
            drawCenteredText(batch, entry.text, entry.centerX, entry.baselineY, textCol)
        }

        // Rising Floating Action Text
        for (ft in worldState.floatingTexts) {
            val col = runCatching {
                Color.valueOf(com.morgana.client.access.PaletteColours.floatingText(ft.colorHex, com.morgana.client.access.Accessibility.palette).removePrefix("#"))
            }.getOrDefault(Color.WHITE)
            val worldRenderY = (map.height * ts) - ft.worldY
            col.a = com.morgana.client.state.FloatingTextMotion.alpha(ft.remainingSeconds)
            val oldScale = font.data.scaleX
            val pop = if (com.morgana.client.access.Accessibility.reduceMotion) (if (ft.crit) 1.35f else 1f) else com.morgana.client.state.FloatingTextMotion.scale(ft.ageSeconds, ft.crit)
            font.data.setScale(oldScale * pop)
            drawCenteredText(batch, ft.text, ft.worldX, worldRenderY, col)
            font.data.setScale(oldScale)
        }
        font.color = Color.WHITE
    }

    private fun drawHpBar(batch: SpriteBatch, x: Float, y: Float, width: Float, height: Float, hp: Int, maxHp: Int, hostile: Boolean = false) {
        val ratio = (hp.toFloat() / maxHp.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        batch.setColor(0.1f, 0.1f, 0.1f, 0.85f)
        batch.draw(assets.whitePixel, x - 1f, y - 1f, width + 2f, height + 2f)
        val bad = com.morgana.client.access.Accessibility.colour(com.morgana.client.access.PaletteRole.BAD)
        val good = com.morgana.client.access.Accessibility.colour(com.morgana.client.access.PaletteRole.GOOD)
        if (hostile) {
            batch.setColor(0.16f, 0.08f, 0.08f, 0.9f)
            batch.draw(assets.whitePixel, x, y, width, height)
            batch.setColor(bad.r, bad.g, bad.b, 0.95f)
            batch.draw(assets.whitePixel, x, y, width * ratio, height)
        } else {
            batch.setColor(bad.r, bad.g, bad.b, 0.9f)
            batch.draw(assets.whitePixel, x, y, width, height)
            batch.setColor(good.r, good.g, good.b, 0.95f)
            batch.draw(assets.whitePixel, x, y, width * ratio, height)
        }
        batch.color = Color.WHITE
    }

    private fun drawCastBar(batch: SpriteBatch, x: Float, y: Float, width: Float, height: Float, ratio: Float) {
        val r = ratio.coerceIn(0f, 1f)
        batch.setColor(0.1f, 0.1f, 0.1f, 0.85f)
        batch.draw(assets.whitePixel, x - 1f, y - 1f, width + 2f, height + 2f)
        batch.setColor(0.2f, 0.25f, 0.35f, 0.9f)
        batch.draw(assets.whitePixel, x, y, width, height)
        batch.setColor(0.2f, 0.75f, 0.95f, 0.95f)
        batch.draw(assets.whitePixel, x, y, width * r, height)
        batch.color = Color.WHITE
    }

    private fun drawCenteredText(batch: SpriteBatch, text: String, centerX: Float, y: Float, color: Color) {
        glyphLayout.setText(font, text)
        val x = centerX - glyphLayout.width * 0.5f
        val darkText = (color.r + color.g + color.b) < 0.45f
        font.color = if (darkText) Color(0.9f, 0.9f, 0.94f, color.a) else Color(0f, 0f, 0f, color.a)
        font.draw(batch, text, x + 1f, y - 1f)
        font.color = color
        font.draw(batch, text, x, y)
    }

    private fun drawChatBubble(batch: SpriteBatch, text: String, centerX: Float, bottomY: Float) {
        glyphLayout.setText(font, text)
        val padX = 6f
        val padY = 4f
        val boxW = glyphLayout.width + padX * 2f
        val boxH = glyphLayout.height + padY * 2f
        val boxX = centerX - boxW * 0.5f

        batch.setColor(0.08f, 0.10f, 0.15f, 0.88f)
        batch.draw(assets.whitePixel, boxX, bottomY, boxW, boxH)
        batch.color = Color.WHITE
        font.color = Color.WHITE
        font.draw(batch, text, boxX + padX, bottomY + boxH - padY)
    }

    private fun highlightColor(itemDef: com.morgana.domain.item.ItemDefinition?): Color {
        val hex = itemDef?.dropHighlightColorHex?.takeIf { it.isNotBlank() } ?: "#FBBF24"
        return rarityColors.getOrPut(hex) { com.morgana.client.ui.bag.BagUi.hexColor(hex) } ?: Color.GOLD
    }

    companion object {
        private val SHADOW_BASE_LAYERS = listOf(MapLayer.MASK_1, MapLayer.MASK_2)
        private val SHADOW_UPPER_LAYERS = listOf(MapLayer.FRINGE_1, MapLayer.FRINGE_2, MapLayer.ROOF)
        /** A ghost is drawn pale blue and see-through. */
        private val GHOST_TINT = Color(0.7f, 0.85f, 1f, 0.5f)

        /** Whether a ground item should be drawn larger with a flashing beacon and overhead name. */
        fun isHighlightedGroundItem(itemDef: com.morgana.domain.item.ItemDefinition?): Boolean =
            itemDef != null && (itemDef.highlightOnGround || itemDef.broadcastOnDrop)

        /** Offsets for stacked currency pile icons on the ground. */
        private val CURRENCY_PILE_OFFSETS = listOf(0f to 0f, 4f to 3f, -3f to 5f)

        /** Visual distinction tiers for ground currency piles (e.g. Marks) by stack size. */
        enum class CurrencyPileTier(
            val minAmount: Int,
            val stackCopies: Int,
            val iconSize: Float,
            val tint: Color,
            val haloAlpha: Float
        ) {
            SMALL(1, 1, 20f, Color.WHITE, 0f),
            MEDIUM(100, 2, 22f, Color(1f, 0.85f, 0.45f, 1f), 0.14f),
            LARGE(1000, 3, 24f, Color(1f, 0.72f, 0.18f, 1f), 0.24f),
            HUGE(10_000, 3, 26f, Color(1f, 0.96f, 0.65f, 1f), 0.34f);

            companion object {
                fun forAmount(amount: Int): CurrencyPileTier = when {
                    amount >= HUGE.minAmount -> HUGE
                    amount >= LARGE.minAmount -> LARGE
                    amount >= MEDIUM.minAmount -> MEDIUM
                    else -> SMALL
                }
            }
        }

        /** Overhead label text for a highlighted ground item. */
        fun groundItemLabel(itemDef: com.morgana.domain.item.ItemDefinition, amount: Int): String =
            if (amount > 1) "${itemDef.name} x$amount" else itemDef.name

        /**
         * Vertical render offset for an east or west neighbour. Maps are drawn bottom-up, so two maps of different heights
         * share their bottom edge unless shifted; the border joins tile row N to tile row N (that is where the server moves a
         * hero across), so the neighbour is moved by the difference in height to line the rows up.
         */
        fun sideNeighborOffsetY(activeHeight: Int, neighborHeight: Int, tileSize: Float): Float = (activeHeight - neighborHeight) * tileSize

        /** Pixels a projectile is raised so it leaves the middle of its shooter's body rather than the middle of the tile. */
        fun projectileLift(profile: com.morgana.domain.game.SpriteProfile?, spriteId: Int?, tileSize: Float): Float {
            if (profile == null || spriteId == null) return 0f
            return (profile.uiHeightOf(spriteId) * 0.5f - tileSize * 0.5f).coerceAtLeast(0f)
        }

        /** CCW degrees in LibGDX render space for a projectile sprite authored pointing [Direction.RIGHT]. */
        fun projectileRotationDegrees(direction: Direction): Float = when (direction) {
            Direction.RIGHT -> 0f
            Direction.UP_RIGHT -> 45f
            Direction.UP -> 90f
            Direction.UP_LEFT -> 135f
            Direction.LEFT -> 180f
            Direction.DOWN_LEFT -> 225f
            Direction.DOWN -> 270f
            Direction.DOWN_RIGHT -> 315f
        }
    }
}