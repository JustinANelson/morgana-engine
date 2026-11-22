package com.morgana.client.assets

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.morgana.client.render.ComposedPixelLayer
import com.morgana.client.render.PaperdollCompositeCache
import com.morgana.client.render.PaperdollCompositeKey
import com.morgana.client.render.RgbaCell
import com.morgana.domain.game.SpriteProfile
import com.morgana.domain.game.SpriteState
import com.morgana.domain.item.PaperdollComposer
import com.morgana.domain.item.PaperdollLayer
import com.morgana.domain.item.PaperdollSlot
import com.morgana.domain.spatial.Direction
import com.morgana.tilegen.ProceduralTilesetGenerator
import kotlin.math.hypot

/**
 * Generates crisp 32x32 retro pixel-art tilesets, 4-direction x 3-frame animated character/NPC sprites,
 * item icons, paperdoll overlays, projectile/spell FX, and radial lighting textures in-memory.
 */
class ProceduralRetroAssets : Disposable {
    val tileSize: Int = 32
    val tilesetColumns: Int = 16
    val tilesetRows: Int = 3

    lateinit var whitePixel: TextureRegion
        private set
    lateinit var radialLightTexture: Texture
        private set

    var spriteProfile: SpriteProfile? = null
        private set

    private val disposableTextures: MutableList<Texture> = mutableListOf()
    private val starterSpriteTextures: MutableList<Texture> = mutableListOf()
    private val tilesetRegions: MutableMap<Int, Array<Array<TextureRegion>>> = mutableMapOf()
    // spriteId -> [dirRow 0..3 (DOWN, LEFT, RIGHT, UP)][frameCol]
    private val characterDirectionalSheets: MutableMap<Int, Array<Array<TextureRegion>>> = mutableMapOf()
    private val spriteRgbaCells: MutableMap<Int, Array<Array<RgbaCell>>> = mutableMapOf()
    private val itemIconRegions: MutableMap<Int, TextureRegion> = mutableMapOf()
    private val paperdollRegions: MutableMap<Int, TextureRegion> = mutableMapOf()
    private val paperdollSheets: MutableMap<Int, Array<Array<TextureRegion>>> = mutableMapOf()
    private val paperdollRgbaCells: MutableMap<Int, Pair<Boolean, Array<Array<RgbaCell>>>> = mutableMapOf()
    private val projectileRegions: MutableMap<Int, TextureRegion> = mutableMapOf()
    private val animationStrips: MutableMap<Int, Array<TextureRegion>> = mutableMapOf()

    // A realm's own art (see applyManifest) sits on top of the built-ins and is removed by resetCustomArt().
    private val customTilesets = ReplaceableArt(tilesetRegions)
    private val customSprites = ReplaceableArt(characterDirectionalSheets)
    private val customSpriteCells = ReplaceableArt(spriteRgbaCells)
    private val customIcons = ReplaceableArt(itemIconRegions)
    private val customPaperdolls = ReplaceableArt(paperdollRegions)
    private val customPaperdollSheets = ReplaceableArt(paperdollSheets)
    private val customPaperdollCells = ReplaceableArt(paperdollRgbaCells)
    private val customProjectiles = ReplaceableArt(projectileRegions)
    private val customAnimations = ReplaceableArt(animationStrips)
    private val customPictures: MutableMap<String, TextureRegion> = mutableMapOf()
    private val customTextures: MutableList<Texture> = mutableListOf()
    private val customTexturesByPath: MutableMap<String, Texture> = mutableMapOf()

    private var artEpoch: Long = 0L
    private val paperdollCompositeCache = PaperdollCompositeCache<TextureRegion> { region ->
        disposableTextures.remove(region.texture)
        region.texture.dispose()
    }

    /** Total live GPU textures currently held by this asset manager (atlas pages + standalone sheets). */
    val activeGpuTextureCount: Int get() = disposableTextures.size

    val tileDescriptions: List<Pair<Int, String>> = listOf(
        0 to "Meadow Grass",
        1 to "Crypt Floor",
        2 to "Cobble Road",
        3 to "Deep Water (Anim)",
        4 to "Ancient Oak Tree",
        5 to "Copper Ore Vein",
        6 to "Shop Anvil",
        7 to "Bank Vault Chest",
        8 to "Healing Fountain",
        9 to "Brazier Torch",
        10 to "Runic Warp Portal",
        11 to "Dungeon Brick Wall",
        12 to "Locked Key Gate",
        13 to "Roof Shingles",
        14 to "Wooden Bridge",
        15 to "Flowers / Shrub",
        // Row 1 (ids 16+): columns 0 and 1 hold the water animation frames.
        18 to "Snow",
        19 to "Ice (slippery)",
        20 to "Snowy Pine",
        21 to "Beach Sand",
        22 to "Wooden Planks",
        23 to "Arena Sand",
        24 to "Cooking Fire",
        25 to "Workbench",
        26 to "Fishing Spot",
        27 to "Frost Crystal",
        28 to "Wooden Fence",
        29 to "Hot Spring",
        30 to "Tilled Soil",
        31 to "Wheat",
        32 to "Turnip",
        33 to "Pumpkin",
        34 to "Tomato Vines",
        35 to "Berry Bush",
        36 to "Apple Tree",
        37 to "Beehive",
        38 to "Scarecrow",
        39 to "Stone Well",
        40 to "Plank Wall",
        41 to "Thatched Roof",
        42 to "Hay Bale",
        43 to "Bed",
        44 to "Baker's Oven",
        45 to "Millstone",
        46 to "Hen Nest",
        47 to "Milking Trough"
    )

    fun loadAll() {
        createWhitePixel()
        createRadialLight()
        createStarterTileset()
        createStarterCharacterAndNpcSprites()
        createStarterUiAndFxAtlas()
    }

    fun getTileRegion(tilesetId: Int, srcX: Int, srcY: Int): TextureRegion {
        val grid = tilesetRegions[tilesetId] ?: tilesetRegions[1]!!
        val row = grid.getOrElse(srcY.coerceIn(0, grid.size - 1)) { grid[0] }
        return row.getOrElse(srcX.coerceIn(0, row.size - 1)) { row[0] }
    }

    /** Ids of every tileset available: the built-in one (1) and any the realm ships (see [applyManifest]). */
    fun tilesetIds(): List<Int> = tilesetRegions.keys.sorted()

    /** A tileset's size in tiles (columns, rows), or null when there is no tileset with that id. */
    fun tilesetGrid(tilesetId: Int): Pair<Int, Int>? = tilesetRegions[tilesetId]?.let { it[0].size to it.size }

    /** A tileset's whole sheet as one region (for the editor's tile palette), or null when there is no such tileset. */
    fun tilesetSheet(tilesetId: Int): TextureRegion? {
        val grid = tilesetRegions[tilesetId] ?: return null
        val first = grid[0][0]
        return TextureRegion(first.texture, first.regionX, first.regionY, grid[0].size * tileSize, grid.size * tileSize)
    }

    fun getSpriteRegion(
        spriteId: Int,
        direction: Direction = Direction.DOWN,
        walkFrame: Int = 0,
        state: SpriteState? = null,
        elapsedSeconds: Float = 0f,
        actionProgress: Float? = null
    ): TextureRegion {
        val grid = characterDirectionalSheets[spriteId] ?: characterDirectionalSheets[1]!!
        val dirRow = when (direction.primaryCardinal()) {
            Direction.DOWN -> 0
            Direction.LEFT -> 1
            Direction.RIGHT -> 2
            Direction.UP -> 3
            else -> 0
        }.coerceIn(0, grid.size - 1)
        val row = grid[dirRow]
        val col = SpriteSheetLayout.resolveColumn(
            gridColumns = row.size,
            profile = spriteProfile,
            state = state,
            walkFrame = walkFrame,
            elapsedSeconds = elapsedSeconds,
            actionProgress = actionProgress
        )
        return row[col]
    }

    fun getItemIconRegion(iconId: Int): TextureRegion =
        itemIconRegions[iconId] ?: itemIconRegions[1]!!

    /** Every id that has art of [kind] (built-in or the realm's own), for the editor's Art panel. */
    fun artIds(kind: com.morgana.client.editor.ArtKind): List<Int> = when (kind) {
        com.morgana.client.editor.ArtKind.ICONS -> itemIconRegions.keys
        com.morgana.client.editor.ArtKind.SPRITES -> characterDirectionalSheets.keys
        com.morgana.client.editor.ArtKind.PAPERDOLLS -> paperdollRegions.keys
        com.morgana.client.editor.ArtKind.PROJECTILES -> projectileRegions.keys
        com.morgana.client.editor.ArtKind.ANIMATIONS -> animationStrips.keys
        com.morgana.client.editor.ArtKind.TILESETS -> tilesetRegions.keys
    }.sorted()

    /** The picture for one art id of [kind]: the first frame for characters and animation strips, or whole sheet for tilesets. */
    fun artRegion(kind: com.morgana.client.editor.ArtKind, id: Int): TextureRegion? = when (kind) {
        com.morgana.client.editor.ArtKind.ICONS -> itemIconRegions[id]
        com.morgana.client.editor.ArtKind.SPRITES -> characterDirectionalSheets[id]?.get(0)?.get(0)
        com.morgana.client.editor.ArtKind.PAPERDOLLS -> paperdollRegions[id]
        com.morgana.client.editor.ArtKind.PROJECTILES -> projectileRegions[id]
        com.morgana.client.editor.ArtKind.ANIMATIONS -> animationStrips[id]?.firstOrNull()
        com.morgana.client.editor.ArtKind.TILESETS -> tilesetSheet(id)
    }

    /** Whether the realm supplied this art itself (rather than the built-in picture). */
    fun isCustomArt(kind: com.morgana.client.editor.ArtKind, id: Int): Boolean = id in when (kind) {
        com.morgana.client.editor.ArtKind.ICONS -> customIcons.replacedIds
        com.morgana.client.editor.ArtKind.SPRITES -> customSprites.replacedIds
        com.morgana.client.editor.ArtKind.PAPERDOLLS -> customPaperdolls.replacedIds
        com.morgana.client.editor.ArtKind.PROJECTILES -> customProjectiles.replacedIds
        com.morgana.client.editor.ArtKind.ANIMATIONS -> customAnimations.replacedIds
        com.morgana.client.editor.ArtKind.TILESETS -> customTilesets.replacedIds
    }

    fun getPaperdollRegion(paperdollId: Int): TextureRegion? =
        paperdollRegions[paperdollId]

    /**
     * Resolves a paperdoll frame matching [direction]/[state]/[walkFrame] when directional sheet art is installed,
     * returning `(region, isDirectionalSheet)`. Legacy square overlays return `(region, false)`.
     */
    fun getPaperdollLayerFrame(
        paperdollId: Int,
        direction: Direction = Direction.DOWN,
        walkFrame: Int = 0,
        state: SpriteState? = null,
        elapsedSeconds: Float = 0f,
        actionProgress: Float? = null
    ): Pair<TextureRegion, Boolean>? {
        val sheet = paperdollSheets[paperdollId]
        if (sheet != null && sheet.isNotEmpty()) {
            val dirRow = directionRow(direction).coerceIn(0, sheet.size - 1)
            val row = sheet[dirRow]
            val col = SpriteSheetLayout.resolveColumn(
                gridColumns = row.size,
                profile = spriteProfile,
                state = state,
                walkFrame = walkFrame,
                elapsedSeconds = elapsedSeconds,
                actionProgress = actionProgress
            )
            return row[col] to true
        }
        return paperdollRegions[paperdollId]?.let { it to false }
    }

    /**
     * Returns a single pre-composited [TextureRegion] for a Paperdolling v2 character frame (`1` GPU draw),
     * caching per `(characterId, spriteId, layers, direction, col)` and evicting/disposing superseded entries
     * automatically. Returns null when [layers] is empty or pixel buffers are unavailable.
     */
    fun getOrComposeCharacterRegion(
        characterId: String,
        spriteId: Int,
        layers: List<PaperdollLayer>,
        direction: Direction = Direction.DOWN,
        walkFrame: Int = 0,
        state: SpriteState? = null,
        elapsedSeconds: Float = 0f,
        actionProgress: Float? = null
    ): TextureRegion? {
        val composed = PaperdollComposer.composeLayers(layers, direction)
        if (composed.isEmpty()) return null
        val grid = characterDirectionalSheets[spriteId] ?: characterDirectionalSheets[1] ?: return null
        val dirRow = directionRow(direction).coerceIn(0, grid.size - 1)
        val row = grid[dirRow]
        val col = SpriteSheetLayout.resolveColumn(
            gridColumns = row.size,
            profile = spriteProfile,
            state = state,
            walkFrame = walkFrame,
            elapsedSeconds = elapsedSeconds,
            actionProgress = actionProgress
        )
        val bodyCells = spriteRgbaCells[spriteId] ?: spriteRgbaCells[1] ?: return null
        val bodyCell = bodyCells.getOrNull(dirRow)?.getOrNull(col) ?: return null

        val pixelLayers = mutableListOf<ComposedPixelLayer>()
        if (composed.none { it.slot == PaperdollSlot.BODY }) {
            pixelLayers += ComposedPixelLayer(PaperdollSlot.BODY, bodyCell, null)
        }
        for (layer in composed) {
            val (isDirectional, pdGrid) = paperdollRgbaCells[layer.spriteId] ?: return null
            val pdCell = if (isDirectional) {
                val pDirRow = dirRow.coerceIn(0, pdGrid.size - 1)
                val pRow = pdGrid[pDirRow]
                val pCol = SpriteSheetLayout.resolveColumn(
                    gridColumns = pRow.size,
                    profile = spriteProfile,
                    state = state,
                    walkFrame = walkFrame,
                    elapsedSeconds = elapsedSeconds,
                    actionProgress = actionProgress
                )
                pRow[pCol]
            } else {
                pdGrid[0][0]
            }
            pixelLayers += ComposedPixelLayer(layer.slot, pdCell, layer.tintHex, layer.glow)
        }

        val key = PaperdollCompositeKey(
            spriteId = spriteId,
            directionRow = dirRow,
            column = col,
            cellWidth = bodyCell.width,
            cellHeight = bodyCell.height,
            layers = composed,
            artEpoch = artEpoch
        )
        return paperdollCompositeCache.getOrPut(characterId, key) {
            val pixels = PaperdollCompositeCache.composeRgbaCell(bodyCell.width, bodyCell.height, pixelLayers, direction)
            TextureRegion(registerTexture(rgba8888UploadPixmap(bodyCell.width, bodyCell.height, pixels)))
        }
    }

    /** Invalidates cached composites for [characterId] (called when a character equips/unequips or leaves). */
    fun invalidateCharacterComposite(characterId: String) {
        paperdollCompositeCache.invalidateCharacter(characterId)
    }

    private fun invalidatePaperdollComposites() {
        artEpoch++
        paperdollCompositeCache.invalidateAll()
    }

    private fun directionRow(direction: Direction): Int = when (direction.primaryCardinal()) {
        Direction.DOWN -> 0
        Direction.LEFT -> 1
        Direction.RIGHT -> 2
        Direction.UP -> 3
        else -> 0
    }

    fun getProjectileRegion(projectileSpriteId: Int): TextureRegion =
        projectileRegions[projectileSpriteId] ?: projectileRegions[1]!!

    fun getAnimationFrame(sheetId: Int, frameIndex: Int): TextureRegion {
        val strip = animationStrips[sheetId] ?: animationStrips[1]!!
        return strip[frameIndex.coerceIn(0, strip.size - 1)]
    }

    fun animationFrameCount(sheetId: Int): Int =
        (animationStrips[sheetId] ?: animationStrips[1]!!).size

    fun getAnimationRegion(animationId: Int): TextureRegion =
        getAnimationFrame(animationId, 0)

    /**
     * Resolves a picture id for the event ShowPicture command: checks custom PNGs under `assets/pictures/`, then explicit
     * `icon:<id>`, `sprite:<id>`, `anim:<id>` prefixes, and falls back to item icon `<id>` when [pictureId] is numeric.
     */
    fun getPictureRegion(pictureId: String): TextureRegion? {
        val raw = pictureId.trim()
        if (raw.isEmpty()) return null
        val norm = raw.lowercase().removePrefix("pictures/").removeSuffix(".png")
        customPictures[norm]?.let { return it }
        val colon = norm.indexOf(':')
        if (colon > 0) {
            val prefix = norm.substring(0, colon)
            val id = norm.substring(colon + 1).toIntOrNull() ?: return null
            return when (prefix) {
                "icon" -> itemIconRegions[id]
                "sprite" -> characterDirectionalSheets[id]?.get(0)?.get(0)
                "anim" -> animationStrips[id]?.firstOrNull()
                else -> null
            }
        }
        return norm.toIntOrNull()?.let { itemIconRegions[it] }
    }

    /**
     * Removes every realm-supplied sheet and restores the built-in art, freeing the realm's textures. Call it when a new
     * login succeeds, before [applyManifest], so a realm never inherits the previous realm's art.
     *
     * When [newSpriteProfile] differs from the active [spriteProfile], regenerates starter character/NPC sheets to match
     * the new realm's cell dimensions and animation states.
     */
    fun resetCustomArt(newSpriteProfile: SpriteProfile? = spriteProfile) {
        invalidatePaperdollComposites()
        customTilesets.restore()
        customSprites.restore()
        customSpriteCells.restore()
        customIcons.restore()
        customPaperdolls.restore()
        customPaperdollSheets.restore()
        customPaperdollCells.restore()
        customProjectiles.restore()
        customAnimations.restore()
        customPictures.clear()
        customTextures.forEach { it.dispose() }
        disposableTextures.removeAll(customTextures.toSet())
        customTextures.clear()
        customTexturesByPath.clear()
        if (newSpriteProfile != spriteProfile) {
            spriteProfile = newSpriteProfile
            starterSpriteTextures.forEach { it.dispose() }
            disposableTextures.removeAll(starterSpriteTextures.toSet())
            starterSpriteTextures.clear()
            characterDirectionalSheets.clear()
            spriteRgbaCells.clear()
            createStarterCharacterAndNpcSprites()
        }
    }

    fun applyManifest(manifest: com.morgana.protocol.realms.AssetManifest, cache: AssetCache) {
        val toPack = linkedMapOf<String, Pair<com.morgana.protocol.realms.AssetManifestEntry, Pixmap>>()
        for ((_, entry) in manifest.assets) {
            if (entry.kind == com.morgana.protocol.realms.AssetKind.MUSIC) continue // played by the sound engine, not drawn
            val bytes = cache.load(entry.sha256) ?: continue
            if (entry.kind == com.morgana.protocol.realms.AssetKind.TILESET) {
                applyAssetEntry(entry, bytes)
            } else {
                try {
                    val pm = Pixmap(bytes, 0, bytes.size)
                    toPack[entry.path] = entry to pm
                } catch (_: Exception) {
                }
            }
        }
        if (toPack.isEmpty()) return

        val gridCache = linkedMapOf<Int, Array<Array<SpriteCellRect>>>()
        val pdSheetIds = hashSetOf<Int>()
        for ((_, pair) in toPack) {
            val (entry, pm) = pair
            when (entry.kind) {
                com.morgana.protocol.realms.AssetKind.SPRITE -> {
                    val id = entry.path.substringBeforeLast('.').filter { it.isDigit() }.toIntOrNull() ?: 1
                    val cells = SpriteSheetLayout.computeSheetGrid(pm.width, pm.height, spriteProfile)
                    gridCache[id] = cells
                    customSpriteCells.replace(id, extractSheetCells(pm, cells, isOverlay = false))
                }
                com.morgana.protocol.realms.AssetKind.PAPERDOLL -> {
                    val id = entry.path.substringBeforeLast('.').filter { it.isDigit() }.toIntOrNull() ?: continue
                    val profile = spriteProfile
                    if (profile != null && pm.width == profile.sheetWidth && pm.height == profile.sheetHeight) {
                        val cells = SpriteSheetLayout.computeSheetGrid(pm.width, pm.height, profile)
                        customPaperdollCells.replace(id, true to extractSheetCells(pm, cells, isOverlay = false))
                        pdSheetIds += id
                    } else {
                        val single = arrayOf(arrayOf(SpriteCellRect(0, 0, pm.width, pm.height)))
                        customPaperdollCells.replace(id, false to extractSheetCells(pm, single, isOverlay = true))
                    }
                }
                else -> {}
            }
        }

        val packed = packPixmapsIntoAtlases(
            sources = toPack.mapValues { it.value.second },
            trackBucket = customTextures
        )
        for ((path, pair) in toPack) {
            val (entry, _) = pair
            val reg = packed[path] ?: continue
            when (entry.kind) {
                com.morgana.protocol.realms.AssetKind.SPRITE -> {
                    val id = path.substringBeforeLast('.').filter { it.isDigit() }.toIntOrNull() ?: 1
                    val cells = gridCache[id] ?: SpriteSheetLayout.computeSheetGrid(reg.width, reg.height, spriteProfile)
                    val sheet = Array(cells.size) { r ->
                        Array(cells[r].size) { c ->
                            val rect = cells[r][c]
                            TextureRegion(reg.texture, reg.x + rect.x, reg.y + rect.y, rect.width, rect.height)
                        }
                    }
                    customSprites.replace(id, sheet)
                }
                com.morgana.protocol.realms.AssetKind.ICON -> {
                    val id = path.substringBeforeLast('.').filter { it.isDigit() }.toIntOrNull() ?: continue
                    customIcons.replace(id, TextureRegion(reg.texture, reg.x, reg.y, reg.width, reg.height))
                }
                com.morgana.protocol.realms.AssetKind.PAPERDOLL -> {
                    val id = path.substringBeforeLast('.').filter { it.isDigit() }.toIntOrNull() ?: continue
                    if (id in pdSheetIds) {
                        val cells = SpriteSheetLayout.computeSheetGrid(reg.width, reg.height, spriteProfile)
                        val sheet = Array(cells.size) { r ->
                            Array(cells[r].size) { c ->
                                val rect = cells[r][c]
                                TextureRegion(reg.texture, reg.x + rect.x, reg.y + rect.y, rect.width, rect.height)
                            }
                        }
                        customPaperdollSheets.replace(id, sheet)
                        customPaperdolls.replace(id, sheet[0][0])
                    } else {
                        customPaperdollSheets.replace(id, emptyArray())
                        customPaperdolls.replace(id, TextureRegion(reg.texture, reg.x, reg.y, reg.width, reg.height))
                    }
                }
                com.morgana.protocol.realms.AssetKind.PROJECTILE -> {
                    val id = path.substringBeforeLast('.').filter { it.isDigit() }.toIntOrNull() ?: continue
                    customProjectiles.replace(id, TextureRegion(reg.texture, reg.x, reg.y, reg.width, reg.height))
                }
                com.morgana.protocol.realms.AssetKind.ANIMATION -> {
                    val id = path.substringBeforeLast('.').filter { it.isDigit() }.toIntOrNull() ?: continue
                    customAnimations.replace(id, sliceAnimationStrip(reg.texture, reg.x, reg.y, reg.width, reg.height))
                }
                com.morgana.protocol.realms.AssetKind.MISC -> if (path.startsWith("pictures/")) {
                    val key = path.removePrefix("pictures/").removeSuffix(".png").lowercase()
                    customPictures[key] = TextureRegion(reg.texture, reg.x, reg.y, reg.width, reg.height)
                }
                else -> {}
            }
        }
        invalidatePaperdollComposites()
    }

    /** Decodes and swaps one realm asset [entry] onto the live GPU texture tables (used at join and after live F1 import). */
    fun applyAssetEntry(entry: com.morgana.protocol.realms.AssetManifestEntry, bytes: ByteArray) {
        try {
            customTexturesByPath.remove(entry.path)?.let { prev ->
                customTextures.remove(prev)
                disposableTextures.remove(prev)
                prev.dispose()
            }
            when (entry.kind) {
                com.morgana.protocol.realms.AssetKind.TILESET -> {
                    val pixmap = Pixmap(bytes, 0, bytes.size)
                    val id = entry.path.substringBeforeLast('.').filter { it.isDigit() }.toIntOrNull() ?: 1
                    registerCustomTileset(id, pixmap, entry.path)
                }
                com.morgana.protocol.realms.AssetKind.SPRITE -> {
                    val pixmap = Pixmap(bytes, 0, bytes.size)
                    val id = entry.path.substringBeforeLast('.').filter { it.isDigit() }.toIntOrNull() ?: 1
                    registerCustomSprite(id, pixmap, entry.path)
                }
                com.morgana.protocol.realms.AssetKind.ICON -> registerCustomSingle(customIcons, entry.path, bytes)
                com.morgana.protocol.realms.AssetKind.PAPERDOLL -> {
                    val id = entry.path.substringBeforeLast('.').filter { it.isDigit() }.toIntOrNull()
                    if (id != null) registerCustomPaperdoll(id, Pixmap(bytes, 0, bytes.size), entry.path)
                }
                com.morgana.protocol.realms.AssetKind.PROJECTILE -> registerCustomSingle(customProjectiles, entry.path, bytes)
                com.morgana.protocol.realms.AssetKind.ANIMATION -> {
                    val id = entry.path.substringBeforeLast('.').filter { it.isDigit() }.toIntOrNull()
                    if (id != null) registerCustomAnimation(id, Pixmap(bytes, 0, bytes.size), entry.path)
                }
                com.morgana.protocol.realms.AssetKind.MISC -> if (entry.path.startsWith("pictures/")) {
                    val key = entry.path.removePrefix("pictures/").removeSuffix(".png").lowercase()
                    val tex = registerCustomTexture(Pixmap(bytes, 0, bytes.size), entry.path)
                    customPictures[key] = TextureRegion(tex)
                }
                else -> {}
            }
        } catch (_: Exception) {
        }
    }

    private fun registerCustomTexture(pixmap: Pixmap, assetPath: String?): Texture =
        registerTexture(pixmap).also { tex ->
            customTextures.add(tex)
            if (assetPath != null) customTexturesByPath[assetPath] = tex
        }

    /** A single-image asset (icon, paperdoll layer, projectile or animation image): the whole PNG, drawn at tile size. */
    private fun registerCustomSingle(target: ReplaceableArt<TextureRegion>, path: String, bytes: ByteArray) {
        val id = path.substringBeforeLast('.').filter { it.isDigit() }.toIntOrNull() ?: return
        val tex = registerCustomTexture(Pixmap(bytes, 0, bytes.size), path)
        target.replace(id, TextureRegion(tex))
    }

    fun registerCustomTileset(tilesetId: Int, pixmap: Pixmap, assetPath: String? = null) {
        val tex = registerCustomTexture(pixmap, assetPath)
        val cols = (tex.width / tileSize).coerceAtLeast(1)
        val rows = (tex.height / tileSize).coerceAtLeast(1)
        val regions = Array(rows) { y ->
            Array(cols) { x -> insetTile(tex, x * tileSize, y * tileSize, tileSize) }
        }
        customTilesets.replace(tilesetId, regions)
    }

    /**
     * One tile of a sheet, with its texture coordinates pulled in by a sliver of a texel. At the edge of a region a GPU
     * (WebGL especially, at low shader precision) can sample the neighbouring tile's row, drawing a thin white or blue line
     * between tile rows; staying just inside the tile removes it without changing what a tile looks like.
     */
    private fun insetTile(tex: Texture, x: Int, y: Int, size: Int): TextureRegion {
        val e = 0.02f
        val w = tex.width.toFloat()
        val h = tex.height.toFloat()
        return TextureRegion(tex, (x + e) / w, (y + e) / h, (x + size - e) / w, (y + size - e) / h)
    }

    fun registerCustomSprite(spriteId: Int, pixmap: Pixmap, assetPath: String? = null) {
        val cells = SpriteSheetLayout.computeSheetGrid(pixmap.width, pixmap.height, spriteProfile)
        customSpriteCells.replace(spriteId, extractSheetCells(pixmap, cells, isOverlay = false))
        val tex = registerCustomTexture(pixmap, assetPath)
        val sheet = Array(cells.size) { r ->
            Array(cells[r].size) { c ->
                val rect = cells[r][c]
                TextureRegion(tex, rect.x, rect.y, rect.width, rect.height)
            }
        }
        customSprites.replace(spriteId, sheet)
        invalidatePaperdollComposites()
    }

    /** Registers a custom paperdoll PNG (legacy square overlay or directional multi-frame sheet matching [spriteProfile]). */
    fun registerCustomPaperdoll(paperdollId: Int, pixmap: Pixmap, assetPath: String? = null) {
        val profile = spriteProfile
        if (profile != null && pixmap.width == profile.sheetWidth && pixmap.height == profile.sheetHeight) {
            val cells = SpriteSheetLayout.computeSheetGrid(pixmap.width, pixmap.height, profile)
            customPaperdollCells.replace(paperdollId, true to extractSheetCells(pixmap, cells, isOverlay = false))
            val tex = registerCustomTexture(pixmap, assetPath)
            val sheet = Array(cells.size) { r ->
                Array(cells[r].size) { c ->
                    val rect = cells[r][c]
                    TextureRegion(tex, rect.x, rect.y, rect.width, rect.height)
                }
            }
            customPaperdollSheets.replace(paperdollId, sheet)
            customPaperdolls.replace(paperdollId, sheet[0][0])
        } else {
            val single = arrayOf(arrayOf(SpriteCellRect(0, 0, pixmap.width, pixmap.height)))
            customPaperdollCells.replace(paperdollId, false to extractSheetCells(pixmap, single, isOverlay = true))
            val tex = registerCustomTexture(pixmap, assetPath)
            customPaperdollSheets.replace(paperdollId, emptyArray())
            customPaperdolls.replace(paperdollId, TextureRegion(tex))
        }
        invalidatePaperdollComposites()
    }

    private fun extractSheetCells(
        pixmap: Pixmap,
        grid: Array<Array<SpriteCellRect>>,
        isOverlay: Boolean
    ): Array<Array<RgbaCell>> = Array(grid.size) { r ->
        Array(grid[r].size) { c ->
            val rect = grid[r][c]
            val out = IntArray(rect.width * rect.height)
            for (y in 0 until rect.height) {
                val py = rect.y + y
                val off = y * rect.width
                for (x in 0 until rect.width) {
                    out[off + x] = pixmap.getPixel(rect.x + x, py)
                }
            }
            RgbaCell(rect.width, rect.height, out, isOverlay = isOverlay)
        }
    }

    /** Registers a custom animation PNG (single square frame or horizontal strip of square frames). */
    fun registerCustomAnimation(animationId: Int, pixmap: Pixmap, assetPath: String? = null) {
        val tex = registerCustomTexture(pixmap, assetPath)
        customAnimations.replace(animationId, sliceAnimationStrip(tex, 0, 0, tex.width, tex.height))
    }

    private fun sliceAnimationStrip(
        tex: Texture,
        offsetX: Int = 0,
        offsetY: Int = 0,
        width: Int = tex.width,
        height: Int = tex.height
    ): Array<TextureRegion> =
        AnimationStripLayout.computeStripFrames(width, height).map { rect ->
            TextureRegion(tex, offsetX + rect.x, offsetY + rect.y, rect.width, rect.height)
        }.toTypedArray()

    private class PackedGpuRegion(val texture: Texture, val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * Packs [sources] into shared power-of-two GPU atlas textures (disposing each input [Pixmap]) and falls back
     * to standalone textures only for oversized inputs.
     */
    private fun packPixmapsIntoAtlases(
        sources: Map<String, Pixmap>,
        trackBucket: MutableList<Texture>? = null
    ): Map<String, PackedGpuRegion> {
        if (sources.isEmpty()) return emptyMap()
        val rects = ArrayList<AtlasRectInput>(sources.size)
        val dims = LinkedHashMap<String, Pair<Int, Int>>(sources.size)
        val pixelsByKey = LinkedHashMap<String, IntArray>(sources.size)
        for ((key, pm) in sources) {
            val w = pm.width
            val h = pm.height
            dims[key] = w to h
            rects += AtlasRectInput(key, w, h)
            val arr = IntArray(w * h)
            for (y in 0 until h) {
                val off = y * w
                for (x in 0 until w) arr[off + x] = pm.getPixel(x, y)
            }
            pixelsByKey[key] = arr
            pm.dispose()
        }

        val packResult = TextureAtlasPacker.pack(rects)
        val out = LinkedHashMap<String, PackedGpuRegion>(sources.size)
        for (page in packResult.pages) {
            val buf = TextureAtlasPacker.blitPageRgba(page, pixelsByKey)
            val tex = uploadRgbaTexture(page.width, page.height, buf).also { trackBucket?.add(it) }
            for (p in page.placements) {
                out[p.key] = PackedGpuRegion(tex, p.x, p.y, p.width, p.height)
            }
        }
        for (key in packResult.oversizedKeys) {
            val (w, h) = dims.getValue(key)
            val tex = uploadRgbaTexture(w, h, pixelsByKey.getValue(key)).also { trackBucket?.add(it) }
            out[key] = PackedGpuRegion(tex, 0, 0, w, h)
        }
        return out
    }

    /** One bulk pixel write, not a drawPixel loop: per-pixel calls are quadratic on the browser (see [writeRgba8888]). */
    private fun uploadRgbaTexture(width: Int, height: Int, pixels: IntArray): Texture =
        registerTexture(rgba8888UploadPixmap(width, height, pixels))

    private fun registerTexture(pixmap: Pixmap): Texture {
        val tex = Texture(pixmap)
        tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
        pixmap.dispose()
        disposableTextures.add(tex)
        return tex
    }

    private fun createWhitePixel() {
        val pm = Pixmap(2, 2, Pixmap.Format.RGBA8888)
        pm.setColor(Color.WHITE)
        pm.fill()
        whitePixel = TextureRegion(registerTexture(pm))
    }

    private fun createRadialLight() {
        val size = 128
        val pm = Pixmap(size, size, Pixmap.Format.RGBA8888)
        val center = size * 0.5f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dist = hypot((x - center).toDouble(), (y - center).toDouble()).toFloat() / center
                val alpha = (1f - dist).coerceIn(0f, 1f).let { it * it }
                pm.setColor(1f, 1f, 1f, alpha)
                pm.drawPixel(x, y)
            }
        }
        val tex = Texture(pm)
        tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        pm.dispose()
        disposableTextures.add(tex)
        radialLightTexture = tex
    }

    private fun createStarterTileset() {
        val image = starterTilesetImage()
        val tex = uploadRgbaTexture(image.width, image.height, image.pixels)
        tilesetRegions[1] = Array(tex.height / tileSize) { y -> Array(tex.width / tileSize) { x -> insetTile(tex, x * tileSize, y * tileSize, tileSize) } }
    }

    /**
     * Draws the built-in tileset (16 x 3 tiles of 32 x 32) into a new [Pixmap] owned by the caller using
     * [ProceduralTilesetGenerator]. The same pixels the client shows by default, so a game can export them
     * as `assets/tilesets/1.png` and edit them.
     */
    fun buildStarterTilesetPixmap(seed: Long = 1337L): Pixmap = starterTilesetImage(seed).toPixmap()

    private fun starterTilesetImage(seed: Long = 1337L) =
        ProceduralTilesetGenerator(seed).generateTileset(tilesetColumns, tilesetRows)

    fun buildLegacyTilesetPixmap(): Pixmap {
        val pm = Pixmap(tilesetColumns * tileSize, tilesetRows * tileSize, Pixmap.Format.RGBA8888)

        fun drawTile(col: Int, row: Int, draw: (ox: Int, oy: Int) -> Unit) {
            draw(col * tileSize, row * tileSize)
        }

        // Tile 0: Lush Meadow Grass
        drawTile(0, 0) { ox, oy ->
            pm.setColor(Color.valueOf("2E6F40"))
            pm.fillRectangle(ox, oy, tileSize, tileSize)
            pm.setColor(Color.valueOf("3B8550"))
            for (i in 0 until 12) {
                val bx = ox + ((i * 7 + 3) % 28)
                val by = oy + ((i * 11 + 5) % 28)
                pm.fillRectangle(bx, by, 2, 3)
            }
        }

        // Tile 1: Crypt Stone Floor
        drawTile(1, 0) { ox, oy ->
            pm.setColor(Color.valueOf("333947"))
            pm.fillRectangle(ox, oy, tileSize, tileSize)
            pm.setColor(Color.valueOf("242834"))
            pm.drawRectangle(ox, oy, tileSize, tileSize)
            pm.drawLine(ox, oy + 16, ox + 31, oy + 16)
            pm.drawLine(ox + 16, oy, ox + 16, oy + 16)
            pm.drawLine(ox + 8, oy + 16, ox + 8, oy + 31)
        }

        // Tile 2: Cobblestone / Dirt Road
        drawTile(2, 0) { ox, oy ->
            pm.setColor(Color.valueOf("8B6D4B"))
            pm.fillRectangle(ox, oy, tileSize, tileSize)
            pm.setColor(Color.valueOf("A3835E"))
            pm.fillRectangle(ox + 3, oy + 3, 10, 8)
            pm.fillRectangle(ox + 16, oy + 5, 11, 9)
            pm.fillRectangle(ox + 5, oy + 17, 12, 10)
            pm.fillRectangle(ox + 19, oy + 18, 9, 9)
        }

        // Tile 3 (and row 1 frames 0,1): Animated Water Frames
        listOf(Triple(3, 0, 0), Triple(0, 1, 4), Triple(1, 1, 8)).forEach { (col, row, waveOffset) ->
            drawTile(col, row) { ox, oy ->
                pm.setColor(Color.valueOf("1D4ED8"))
                pm.fillRectangle(ox, oy, tileSize, tileSize)
                pm.setColor(Color.valueOf("60A5FA"))
                pm.fillRectangle(ox + ((4 + waveOffset) % 20), oy + 8, 8, 2)
                pm.fillRectangle(ox + ((14 + waveOffset) % 22), oy + 20, 8, 2)
            }
        }

        // Tile 4: Ancient Oak Tree (transparent background so GROUND shows underneath!)
        drawTile(4, 0) { ox, oy ->
            pm.setColor(Color.valueOf("5C3A21"))
            pm.fillRectangle(ox + 12, oy + 18, 8, 12)
            pm.setColor(Color.valueOf("166534"))
            pm.fillCircle(ox + 16, oy + 13, 11)
            pm.setColor(Color.valueOf("22C55E"))
            pm.fillCircle(ox + 14, oy + 11, 7)
        }

        // Tile 5: Rich Copper Ore Vein
        drawTile(5, 0) { ox, oy ->
            pm.setColor(Color.valueOf("52525B"))
            pm.fillCircle(ox + 16, oy + 18, 11)
            pm.setColor(Color.valueOf("F97316"))
            pm.fillRectangle(ox + 11, oy + 13, 4, 4)
            pm.fillRectangle(ox + 18, oy + 16, 4, 4)
            pm.fillRectangle(ox + 13, oy + 21, 5, 3)
        }

        // Tile 6: Blacksmith Shop Anvil
        drawTile(6, 0) { ox, oy ->
            pm.setColor(Color.valueOf("71717A"))
            pm.fillRectangle(ox + 6, oy + 12, 20, 8)
            pm.fillRectangle(ox + 10, oy + 20, 12, 8)
            pm.setColor(Color.valueOf("FACC15"))
            pm.drawRectangle(ox + 4, oy + 4, 24, 24)
        }

        // Tile 7: Royal Bank Chest
        drawTile(7, 0) { ox, oy ->
            pm.setColor(Color.valueOf("854D0E"))
            pm.fillRectangle(ox + 5, oy + 10, 22, 16)
            pm.setColor(Color.valueOf("FACC15"))
            pm.drawRectangle(ox + 5, oy + 10, 22, 16)
            pm.fillRectangle(ox + 14, oy + 15, 4, 5)
        }

        // Tile 8: Healing Fountain
        drawTile(8, 0) { ox, oy ->
            pm.setColor(Color.valueOf("94A3B8"))
            pm.fillCircle(ox + 16, oy + 16, 12)
            pm.setColor(Color.valueOf("38BDF8"))
            pm.fillCircle(ox + 16, oy + 16, 8)
        }

        // Tile 9: Brazier / Torch Light Source
        drawTile(9, 0) { ox, oy ->
            pm.setColor(Color.valueOf("52525B"))
            pm.fillRectangle(ox + 13, oy + 16, 6, 12)
            pm.setColor(Color.valueOf("F97316"))
            pm.fillCircle(ox + 16, oy + 12, 6)
            pm.setColor(Color.valueOf("FDE047"))
            pm.fillCircle(ox + 16, oy + 12, 3)
        }

        // Tile 10: Runic Warp Portal
        drawTile(10, 0) { ox, oy ->
            pm.setColor(Color.valueOf("7E22CE"))
            pm.fillCircle(ox + 16, oy + 16, 12)
            pm.setColor(Color.valueOf("C084FC"))
            pm.drawCircle(ox + 16, oy + 16, 9)
            pm.setColor(Color.valueOf("F3E8FF"))
            pm.fillCircle(ox + 16, oy + 16, 4)
        }

        // Tile 11: Dungeon Wall
        drawTile(11, 0) { ox, oy ->
            pm.setColor(Color.valueOf("1E293B"))
            pm.fillRectangle(ox, oy, tileSize, tileSize)
            pm.setColor(Color.valueOf("475569"))
            pm.drawRectangle(ox + 1, oy + 1, 14, 14)
            pm.drawRectangle(ox + 16, oy + 1, 14, 14)
            pm.drawRectangle(ox + 8, oy + 16, 16, 14)
        }

        // Tile 12: Locked Crypt KeyDoor
        drawTile(12, 0) { ox, oy ->
            pm.setColor(Color.valueOf("451A03"))
            pm.fillRectangle(ox + 2, oy + 2, 28, 28)
            pm.setColor(Color.valueOf("FACC15"))
            pm.drawRectangle(ox + 2, oy + 2, 28, 28)
            pm.fillCircle(ox + 16, oy + 16, 4)
        }

        // Tile 13: Roof Shingles
        drawTile(13, 0) { ox, oy ->
            pm.setColor(Color.valueOf("7C2D12"))
            pm.fillRectangle(ox, oy, tileSize, tileSize)
            pm.setColor(Color.valueOf("9A3412"))
            for (ry in 0 until 4) {
                pm.drawLine(ox, oy + ry * 8, ox + 31, oy + ry * 8)
            }
        }

        // Tile 14: Wooden Bridge Planks
        drawTile(14, 0) { ox, oy ->
            pm.setColor(Color.valueOf("78350F"))
            pm.fillRectangle(ox + 2, oy, 28, tileSize)
            pm.setColor(Color.valueOf("451A03"))
            for (ry in 0 until 4) {
                pm.drawLine(ox + 2, oy + ry * 8, ox + 29, oy + ry * 8)
            }
        }

        // Tile 15: Wildflowers / Decorative Shrub
        drawTile(15, 0) { ox, oy ->
            pm.setColor(Color.valueOf("15803D"))
            pm.fillCircle(ox + 16, oy + 18, 9)
            pm.setColor(Color.valueOf("FACC15"))
            pm.fillCircle(ox + 12, oy + 15, 2)
            pm.setColor(Color.valueOf("F472B6"))
            pm.fillCircle(ox + 19, oy + 17, 2)
        }

        // ---- Row 1, columns 2..13: tiles used by the Windmere Harbor, Frostpeak Pass and Ironwall Arena maps ----

        // Tile (2,1): Snow
        drawTile(2, 1) { ox, oy ->
            pm.setColor(Color.valueOf("E8F1F8"))
            pm.fillRectangle(ox, oy, tileSize, tileSize)
            pm.setColor(Color.valueOf("CBD9E6"))
            for (i in 0 until 8) {
                pm.fillRectangle(ox + ((i * 9 + 4) % 28), oy + ((i * 13 + 7) % 28), 3, 2)
            }
        }

        // Tile (3,1): Ice (slippery)
        drawTile(3, 1) { ox, oy ->
            pm.setColor(Color.valueOf("9ADBF2"))
            pm.fillRectangle(ox, oy, tileSize, tileSize)
            pm.setColor(Color.valueOf("D9F4FF"))
            pm.drawLine(ox + 3, oy + 26, ox + 14, oy + 12)
            pm.drawLine(ox + 16, oy + 28, ox + 28, oy + 6)
            pm.setColor(Color.valueOf("5BB5D6"))
            pm.drawRectangle(ox, oy, tileSize, tileSize)
        }

        // Tile (4,1): Snowy pine (transparent background)
        drawTile(4, 1) { ox, oy ->
            pm.setColor(Color.valueOf("5C3A21"))
            pm.fillRectangle(ox + 14, oy + 22, 4, 8)
            pm.setColor(Color.valueOf("14532D"))
            pm.fillTriangle(ox + 16, oy + 2, ox + 5, oy + 16, ox + 27, oy + 16)
            pm.fillTriangle(ox + 16, oy + 9, ox + 3, oy + 25, ox + 29, oy + 25)
            pm.setColor(Color.valueOf("F1F5F9"))
            pm.fillTriangle(ox + 16, oy + 2, ox + 11, oy + 9, ox + 21, oy + 9)
            pm.fillRectangle(ox + 8, oy + 19, 5, 2)
            pm.fillRectangle(ox + 19, oy + 22, 5, 2)
        }

        // Tile (5,1): Beach sand
        drawTile(5, 1) { ox, oy ->
            pm.setColor(Color.valueOf("E3CC94"))
            pm.fillRectangle(ox, oy, tileSize, tileSize)
            pm.setColor(Color.valueOf("C9B079"))
            for (i in 0 until 10) {
                pm.fillRectangle(ox + ((i * 11 + 2) % 29), oy + ((i * 7 + 9) % 29), 2, 1)
            }
        }

        // Tile (6,1): Wooden planks (docks, tavern floor)
        drawTile(6, 1) { ox, oy ->
            pm.setColor(Color.valueOf("9A6B3A"))
            pm.fillRectangle(ox, oy, tileSize, tileSize)
            pm.setColor(Color.valueOf("6B4423"))
            for (ry in 0 until 4) {
                pm.drawLine(ox, oy + ry * 8, ox + 31, oy + ry * 8)
            }
            pm.fillRectangle(ox + 6, oy + 3, 1, 1)
            pm.fillRectangle(ox + 22, oy + 11, 1, 1)
            pm.fillRectangle(ox + 12, oy + 19, 1, 1)
            pm.fillRectangle(ox + 26, oy + 27, 1, 1)
        }

        // Tile (7,1): Arena sand
        drawTile(7, 1) { ox, oy ->
            pm.setColor(Color.valueOf("C79A5A"))
            pm.fillRectangle(ox, oy, tileSize, tileSize)
            pm.setColor(Color.valueOf("B3854A"))
            for (i in 0 until 9) {
                pm.fillRectangle(ox + ((i * 13 + 5) % 28), oy + ((i * 9 + 3) % 28), 3, 2)
            }
            pm.setColor(Color.valueOf("7F1D1D"))
            pm.drawRectangle(ox, oy, tileSize, tileSize)
        }

        // Tile (8,1): Cooking fire pit with a pot (transparent background)
        drawTile(8, 1) { ox, oy ->
            pm.setColor(Color.valueOf("52525B"))
            pm.fillCircle(ox + 16, oy + 22, 9)
            pm.setColor(Color.valueOf("EA580C"))
            pm.fillTriangle(ox + 16, oy + 8, ox + 9, oy + 24, ox + 23, oy + 24)
            pm.setColor(Color.valueOf("FDE047"))
            pm.fillTriangle(ox + 16, oy + 14, ox + 12, oy + 24, ox + 20, oy + 24)
            pm.setColor(Color.valueOf("27272A"))
            pm.fillRectangle(ox + 9, oy + 10, 14, 5)
        }

        // Tile (9,1): Carpenter's workbench (transparent background)
        drawTile(9, 1) { ox, oy ->
            pm.setColor(Color.valueOf("92400E"))
            pm.fillRectangle(ox + 3, oy + 10, 26, 6)
            pm.fillRectangle(ox + 5, oy + 16, 3, 12)
            pm.fillRectangle(ox + 24, oy + 16, 3, 12)
            pm.setColor(Color.valueOf("A1A1AA"))
            pm.fillRectangle(ox + 8, oy + 6, 10, 3)
            pm.setColor(Color.valueOf("78350F"))
            pm.fillRectangle(ox + 18, oy + 4, 2, 6)
        }

        // Tile (10,1): Fishing spot (rippling water with a bobber)
        drawTile(10, 1) { ox, oy ->
            pm.setColor(Color.valueOf("2563EB"))
            pm.fillRectangle(ox, oy, tileSize, tileSize)
            pm.setColor(Color.valueOf("93C5FD"))
            pm.drawCircle(ox + 16, oy + 18, 9)
            pm.drawCircle(ox + 16, oy + 18, 5)
            pm.setColor(Color.valueOf("DC2626"))
            pm.fillCircle(ox + 16, oy + 16, 3)
            pm.setColor(Color.WHITE)
            pm.fillRectangle(ox + 15, oy + 13, 2, 2)
        }

        // Tile (11,1): Frost crystal (transparent background)
        drawTile(11, 1) { ox, oy ->
            pm.setColor(Color.valueOf("38BDF8"))
            pm.fillTriangle(ox + 16, oy + 3, ox + 8, oy + 27, ox + 24, oy + 27)
            pm.setColor(Color.valueOf("BAE6FD"))
            pm.fillTriangle(ox + 16, oy + 7, ox + 13, oy + 24, ox + 18, oy + 24)
            pm.setColor(Color.valueOf("0EA5E9"))
            pm.fillTriangle(ox + 7, oy + 14, ox + 3, oy + 27, ox + 11, oy + 27)
        }

        // Tile (12,1): Wooden fence / railing (transparent background)
        drawTile(12, 1) { ox, oy ->
            pm.setColor(Color.valueOf("7C4A21"))
            pm.fillRectangle(ox + 2, oy + 12, 28, 3)
            pm.fillRectangle(ox + 2, oy + 20, 28, 3)
            pm.fillRectangle(ox + 3, oy + 8, 4, 20)
            pm.fillRectangle(ox + 25, oy + 8, 4, 20)
        }

        // Tile (13,1): Hot spring
        drawTile(13, 1) { ox, oy ->
            pm.setColor(Color.valueOf("64748B"))
            pm.fillCircle(ox + 16, oy + 16, 13)
            pm.setColor(Color.valueOf("5EEAD4"))
            pm.fillCircle(ox + 16, oy + 16, 10)
            pm.setColor(Color.valueOf("CCFBF1"))
            pm.fillRectangle(ox + 11, oy + 12, 3, 1)
            pm.fillRectangle(ox + 18, oy + 19, 4, 1)
        }

        // ---- Farming tiles (stock art for any game that wants them): row 1 columns 14-15, then all of row 2 ----

        // Tile (14,1): Tilled soil
        drawTile(14, 1) { ox, oy ->
            pm.setColor(Color.valueOf("5B3A24"))
            pm.fillRectangle(ox, oy, tileSize, tileSize)
            pm.setColor(Color.valueOf("3F2616"))
            for (ry in 0 until 4) {
                pm.fillRectangle(ox, oy + 3 + ry * 8, tileSize, 2)
            }
        }

        // Tile (15,1): Wheat (golden stalks, transparent background)
        drawTile(15, 1) { ox, oy ->
            pm.setColor(Color.valueOf("CA8A04"))
            for (i in 0 until 5) {
                pm.fillRectangle(ox + 4 + i * 6, oy + 12, 2, 16)
            }
            pm.setColor(Color.valueOf("FACC15"))
            for (i in 0 until 5) {
                pm.fillRectangle(ox + 3 + i * 6, oy + 5, 4, 8)
            }
        }

        // Tile (0,2): Turnip
        drawTile(0, 2) { ox, oy ->
            pm.setColor(Color.valueOf("22C55E"))
            pm.fillRectangle(ox + 9, oy + 8, 4, 8)
            pm.fillRectangle(ox + 19, oy + 8, 4, 8)
            pm.setColor(Color.valueOf("F5F3FF"))
            pm.fillCircle(ox + 11, oy + 20, 5)
            pm.fillCircle(ox + 21, oy + 20, 5)
            pm.setColor(Color.valueOf("A855F7"))
            pm.fillRectangle(ox + 8, oy + 17, 7, 2)
            pm.fillRectangle(ox + 18, oy + 17, 7, 2)
        }

        // Tile (1,2): Pumpkin
        drawTile(1, 2) { ox, oy ->
            pm.setColor(Color.valueOf("15803D"))
            pm.fillRectangle(ox + 15, oy + 6, 3, 6)
            pm.setColor(Color.valueOf("EA580C"))
            pm.fillCircle(ox + 16, oy + 19, 9)
            pm.setColor(Color.valueOf("C2410C"))
            pm.drawLine(ox + 16, oy + 11, ox + 16, oy + 28)
            pm.drawLine(ox + 11, oy + 12, ox + 11, oy + 27)
            pm.drawLine(ox + 21, oy + 12, ox + 21, oy + 27)
        }

        // Tile (2,2): Tomato vines
        drawTile(2, 2) { ox, oy ->
            pm.setColor(Color.valueOf("166534"))
            pm.fillRectangle(ox + 14, oy + 4, 3, 24)
            pm.fillRectangle(ox + 8, oy + 8, 8, 3)
            pm.fillRectangle(ox + 15, oy + 14, 9, 3)
            pm.setColor(Color.valueOf("DC2626"))
            pm.fillCircle(ox + 9, oy + 15, 3)
            pm.fillCircle(ox + 22, oy + 21, 3)
            pm.fillCircle(ox + 12, oy + 22, 3)
        }

        // Tile (3,2): Berry bush
        drawTile(3, 2) { ox, oy ->
            pm.setColor(Color.valueOf("15803D"))
            pm.fillCircle(ox + 16, oy + 18, 11)
            pm.setColor(Color.valueOf("22C55E"))
            pm.fillCircle(ox + 12, oy + 15, 5)
            pm.setColor(Color.valueOf("BE185D"))
            pm.fillCircle(ox + 10, oy + 19, 2)
            pm.fillCircle(ox + 18, oy + 14, 2)
            pm.fillCircle(ox + 21, oy + 21, 2)
            pm.fillCircle(ox + 15, oy + 23, 2)
        }

        // Tile (4,2): Apple tree
        drawTile(4, 2) { ox, oy ->
            pm.setColor(Color.valueOf("5C3A21"))
            pm.fillRectangle(ox + 13, oy + 18, 6, 12)
            pm.setColor(Color.valueOf("16A34A"))
            pm.fillCircle(ox + 16, oy + 12, 11)
            pm.setColor(Color.valueOf("4ADE80"))
            pm.fillCircle(ox + 13, oy + 9, 6)
            pm.setColor(Color.valueOf("DC2626"))
            pm.fillCircle(ox + 10, oy + 14, 2)
            pm.fillCircle(ox + 20, oy + 11, 2)
            pm.fillCircle(ox + 17, oy + 17, 2)
        }

        // Tile (5,2): Beehive
        drawTile(5, 2) { ox, oy ->
            pm.setColor(Color.valueOf("92400E"))
            pm.fillRectangle(ox + 12, oy + 22, 8, 8)
            pm.setColor(Color.valueOf("F59E0B"))
            pm.fillCircle(ox + 16, oy + 15, 8)
            pm.setColor(Color.valueOf("B45309"))
            pm.drawLine(ox + 9, oy + 13, ox + 23, oy + 13)
            pm.drawLine(ox + 9, oy + 17, ox + 23, oy + 17)
            pm.setColor(Color.BLACK)
            pm.fillRectangle(ox + 15, oy + 19, 3, 3)
        }

        // Tile (6,2): Scarecrow
        drawTile(6, 2) { ox, oy ->
            pm.setColor(Color.valueOf("78350F"))
            pm.fillRectangle(ox + 15, oy + 8, 3, 22)
            pm.fillRectangle(ox + 6, oy + 13, 20, 3)
            pm.setColor(Color.valueOf("FACC15"))
            pm.fillRectangle(ox + 8, oy + 16, 3, 6)
            pm.fillRectangle(ox + 21, oy + 16, 3, 6)
            pm.setColor(Color.valueOf("92400E"))
            pm.fillCircle(ox + 16, oy + 8, 4)
            pm.fillRectangle(ox + 10, oy + 4, 13, 3)
        }

        // Tile (7,2): Stone well
        drawTile(7, 2) { ox, oy ->
            pm.setColor(Color.valueOf("78716C"))
            pm.fillCircle(ox + 16, oy + 18, 11)
            pm.setColor(Color.valueOf("1D4ED8"))
            pm.fillCircle(ox + 16, oy + 18, 7)
            pm.setColor(Color.valueOf("78350F"))
            pm.fillRectangle(ox + 6, oy + 4, 3, 14)
            pm.fillRectangle(ox + 23, oy + 4, 3, 14)
            pm.fillRectangle(ox + 6, oy + 4, 20, 3)
        }

        // Tile (8,2): Plank wall (house, barn)
        drawTile(8, 2) { ox, oy ->
            pm.setColor(Color.valueOf("A16207"))
            pm.fillRectangle(ox, oy, tileSize, tileSize)
            pm.setColor(Color.valueOf("713F12"))
            for (rx in 0 until 4) {
                pm.drawLine(ox + rx * 8, oy, ox + rx * 8, oy + 31)
            }
            pm.fillRectangle(ox + 3, oy + 6, 2, 2)
            pm.fillRectangle(ox + 19, oy + 22, 2, 2)
        }

        // Tile (9,2): Thatched roof
        drawTile(9, 2) { ox, oy ->
            pm.setColor(Color.valueOf("CA8A04"))
            pm.fillRectangle(ox, oy, tileSize, tileSize)
            pm.setColor(Color.valueOf("A16207"))
            for (ry in 0 until 5) {
                pm.drawLine(ox, oy + 3 + ry * 6, ox + 31, oy + 3 + ry * 6)
            }
            pm.setColor(Color.valueOf("FDE047"))
            pm.fillRectangle(ox + 5, oy + 8, 6, 1)
            pm.fillRectangle(ox + 20, oy + 21, 6, 1)
        }

        // Tile (10,2): Hay bale
        drawTile(10, 2) { ox, oy ->
            pm.setColor(Color.valueOf("EAB308"))
            pm.fillRectangle(ox + 3, oy + 10, 26, 18)
            pm.setColor(Color.valueOf("A16207"))
            pm.drawLine(ox + 10, oy + 10, ox + 10, oy + 27)
            pm.drawLine(ox + 22, oy + 10, ox + 22, oy + 27)
            pm.drawRectangle(ox + 3, oy + 10, 26, 18)
        }

        // Tile (11,2): Bed
        drawTile(11, 2) { ox, oy ->
            pm.setColor(Color.valueOf("78350F"))
            pm.fillRectangle(ox + 4, oy + 6, 24, 22)
            pm.setColor(Color.valueOf("FEF3C7"))
            pm.fillRectangle(ox + 6, oy + 8, 20, 6)
            pm.setColor(Color.valueOf("B91C1C"))
            pm.fillRectangle(ox + 6, oy + 14, 20, 12)
        }

        // Tile (12,2): Baker's oven
        drawTile(12, 2) { ox, oy ->
            pm.setColor(Color.valueOf("7C2D12"))
            pm.fillRectangle(ox + 3, oy + 6, 26, 24)
            pm.setColor(Color.valueOf("1C1917"))
            pm.fillRectangle(ox + 9, oy + 16, 14, 12)
            pm.setColor(Color.valueOf("F97316"))
            pm.fillRectangle(ox + 12, oy + 22, 8, 6)
            pm.setColor(Color.valueOf("57534E"))
            pm.fillRectangle(ox + 12, oy + 1, 8, 6)
        }

        // Tile (13,2): Millstone
        drawTile(13, 2) { ox, oy ->
            pm.setColor(Color.valueOf("78716C"))
            pm.fillCircle(ox + 16, oy + 18, 12)
            pm.setColor(Color.valueOf("A8A29E"))
            pm.fillCircle(ox + 16, oy + 18, 8)
            pm.setColor(Color.valueOf("44403C"))
            pm.fillCircle(ox + 16, oy + 18, 2)
            pm.setColor(Color.valueOf("FEF3C7"))
            pm.fillRectangle(ox + 8, oy + 8, 4, 3)
        }

        // Tile (14,2): Hen coop nest
        drawTile(14, 2) { ox, oy ->
            pm.setColor(Color.valueOf("92400E"))
            pm.fillRectangle(ox + 5, oy + 14, 22, 14)
            pm.setColor(Color.valueOf("EAB308"))
            pm.fillRectangle(ox + 7, oy + 24, 18, 3)
            pm.setColor(Color.valueOf("FEFCE8"))
            pm.fillCircle(ox + 12, oy + 20, 3)
            pm.fillCircle(ox + 20, oy + 21, 3)
            pm.setColor(Color.valueOf("B45309"))
            pm.fillTriangle(ox + 3, oy + 15, ox + 16, oy + 4, ox + 29, oy + 15)
        }

        // Tile (15,2): Milking trough
        drawTile(15, 2) { ox, oy ->
            pm.setColor(Color.valueOf("78350F"))
            pm.fillRectangle(ox + 3, oy + 16, 26, 10)
            pm.setColor(Color.valueOf("F8FAFC"))
            pm.fillRectangle(ox + 5, oy + 17, 22, 4)
            pm.setColor(Color.valueOf("A1A1AA"))
            pm.fillRectangle(ox + 12, oy + 6, 8, 9)
            pm.setColor(Color.valueOf("E4E4E7"))
            pm.fillRectangle(ox + 13, oy + 7, 6, 3)
        }

        return pm
    }

    private fun createStarterCharacterAndNpcSprites() {
        val profile = spriteProfile
        val pixmaps = buildStarterSpritePixmaps(profile)
        val grids = LinkedHashMap<Int, Array<Array<SpriteCellRect>>>(pixmaps.size)
        for ((id, sheet) in pixmaps) {
            val cells = SpriteSheetLayout.computeSheetGrid(sheet.width, sheet.height, profile)
            grids[id] = cells
            spriteRgbaCells[id] = extractSheetCells(sheet, cells, isOverlay = false)
        }
        val packed = packPixmapsIntoAtlases(
            sources = pixmaps.mapKeys { (id, _) -> "sprite:$id" },
            trackBucket = starterSpriteTextures
        )
        for (id in pixmaps.keys) {
            val reg = packed.getValue("sprite:$id")
            val cells = grids.getValue(id)
            characterDirectionalSheets[id] = Array(cells.size) { r ->
                Array(cells[r].size) { c ->
                    val rect = cells[r][c]
                    TextureRegion(reg.texture, reg.x + rect.x, reg.y + rect.y, rect.width, rect.height)
                }
            }
        }
    }

    /**
     * Draws every built-in character and NPC sheet into new [Pixmap]s owned by the caller, keyed by sprite id,
     * for exporting as `assets/sprites/<id>.png`. Classic mode writes `96 x 128` (`32 x 32` cells); when [profile] is
     * non-null (such as `32 x 64`), draws taller bottom-anchored characters across every declared animation state.
     */
    fun buildStarterSpritePixmaps(profile: SpriteProfile? = null): Map<Int, Pixmap> {
        val palettes = mapOf(
            1 to ("2563EB" to "FDE68A"), // blue fighter
            2 to ("15803D" to "FDE68A"), // green ranger
            3 to ("7E22CE" to "FDE68A"), // purple mage
            4 to ("D97706" to "F3F4F6"), // elder
            5 to ("78350F" to "FDE68A"), // smith
            6 to ("EAB308" to "FDE68A"), // banker
            7 to ("22C55E" to "86EFAC"), // green slime
            8 to ("E2E8F0" to "94A3B8"), // skeleton
            9 to ("CBD5E1" to "94A3B8"), // frost wolf
            10 to ("22D3EE" to "A5F3FC"), // ice sprite
            11 to ("0E7490" to "FDE68A"), // harbor master
            12 to ("B91C1C" to "FDE68A"), // innkeeper
            13 to ("7F1D1D" to "D6D3D1"), // arena master
            14 to ("BAE6FD" to "E0F2FE"), // frost giant
            15 to ("F8FAFC" to "FDE68A"), // Hen
            16 to ("F5F5F4" to "44403C"), // Cow
            17 to ("E7E5E4" to "A8A29E"), // Sheep
            18 to ("EAB308" to "FDE68A"), // Beekeeper
            19 to ("16A34A" to "FDE68A"), // Farmer
            20 to ("F59E0B" to "FEF3C7"), // Baker
            21 to ("0891B2" to "FDE68A"), // Angler
            22 to ("A16207" to "FDE68A"), // Miller
            23 to ("4D7C0F" to "FDE68A"), // clerk
            24 to ("7C3AED" to "FDE68A")  // reeve
        )

        if (profile != null && (profile.cellWidth != tileSize || profile.cellHeight != tileSize || profile != SpriteProfile.CLASSIC)) {
            return buildTallStarterSpritePixmaps(palettes, profile)
        }
        return buildClassicStarterSpritePixmaps(palettes)
    }

    private fun buildTallStarterSpritePixmaps(
        palettes: Map<Int, Pair<String, String>>,
        profile: SpriteProfile
    ): Map<Int, Pixmap> {
        val result = linkedMapOf<Int, Pixmap>()
        val cw = profile.cellWidth
        val ch = profile.cellHeight
        val cols = profile.totalFrames
        val scaleY = (ch / 32f).coerceAtLeast(0.5f)
        val scaleX = (cw / 32f).coerceAtLeast(0.5f)
        fun sx(v: Float): Int = (v * scaleX).toInt()
        fun sy(v: Float): Int = (v * scaleY).toInt()

        for ((id, colors) in palettes) {
            val sheet = Pixmap(cols * cw, SpriteProfile.DIRECTIONS * ch, Pixmap.Format.RGBA8888)
            for (dirRow in 0 until SpriteProfile.DIRECTIONS) {
                var colCursor = 0
                for (state in SpriteState.entries) {
                    val count = (profile.stateFrames[state] ?: 0).coerceAtLeast(0)
                    for (stateIdx in 0 until count) {
                        val ox = colCursor * cw
                        val oy = dirRow * ch
                        val cx = ox + cw / 2
                        val footY = oy + ch - sy(3f).coerceAtLeast(2)

                        val stride = when (state) {
                            SpriteState.WALK -> when (stateIdx % 3) {
                                1 -> -sy(2.5f)
                                2 -> sy(2.5f)
                                else -> 0
                            }
                            SpriteState.ATTACK -> if (stateIdx % 2 == 1) sy(2f) else 0
                            else -> 0
                        }
                        val bobY = when (state) {
                            SpriteState.WALK -> if (stateIdx % 3 != 0) -sy(1f) else 0
                            SpriteState.IDLE -> if (stateIdx % 2 == 1) -1 else 0
                            SpriteState.HARVEST -> if (stateIdx % 2 == 1) sy(2f) else 0
                            SpriteState.CAST -> if (stateIdx % 2 == 1) -sy(1.5f) else 0
                            else -> 0
                        }

                        if (id == 7) {
                            // Slime anchored at bottom-center
                            val rad = sx(10f) + if (state == SpriteState.WALK && stateIdx % 3 != 0) 1 else 0
                            sheet.setColor(Color(0f, 0f, 0f, 0.3f))
                            sheet.fillCircle(cx, footY, sx(9f).coerceAtLeast(3))
                            sheet.setColor(Color.valueOf(colors.first))
                            sheet.fillCircle(cx, footY - rad / 2 + bobY, rad)
                            sheet.setColor(Color.valueOf(colors.second))
                            sheet.fillCircle(cx - sx(3f), footY - rad + bobY, sx(4f).coerceAtLeast(2))
                            if (dirRow != 3) {
                                sheet.setColor(Color.BLACK)
                                val eyeShift = when (dirRow) { 1 -> -sx(3f); 2 -> sx(3f); else -> 0 }
                                sheet.fillRectangle(cx - sx(4f) + eyeShift, footY - rad / 2 + bobY - 2, 2, 3)
                                sheet.fillRectangle(cx + sx(2f) + eyeShift, footY - rad / 2 + bobY - 2, 2, 3)
                            }
                        } else {
                            // Tall human/NPC proportions (~24 x 48 centered in 32 x 64)
                            val legH = sy(9f).coerceAtLeast(4)
                            val legW = sx(4f).coerceAtLeast(2)
                            val torsoW = sx(14f).coerceAtLeast(6)
                            val torsoH = sy(11f).coerceAtLeast(6)
                            val headR = sx(6f).coerceAtLeast(3)

                            // Ground shadow
                            sheet.setColor(Color(0f, 0f, 0f, 0.35f))
                            sheet.fillCircle(cx, footY, sx(9f).coerceAtLeast(4))

                            // Legs / Boots anchored at feet
                            sheet.setColor(Color.valueOf("1E293B"))
                            sheet.fillRectangle(cx - sx(5f), footY - legH + stride, legW, legH)
                            sheet.fillRectangle(cx + sx(1f), footY - legH - stride, legW, legH)

                            // Tunic / Torso extending upward
                            val torsoTop = footY - legH - torsoH + bobY
                            sheet.setColor(Color.valueOf(colors.first))
                            sheet.fillRectangle(cx - torsoW / 2, torsoTop, torsoW, torsoH)

                            // Arms & state pose cues
                            val armW = sx(3f).coerceAtLeast(2)
                            val armH = sy(8f).coerceAtLeast(3)
                            when (state) {
                                SpriteState.CAST -> {
                                    // Arms raised + arcane glow
                                    sheet.fillRectangle(cx - torsoW / 2 - armW, torsoTop - sy(4f), armW, armH)
                                    sheet.fillRectangle(cx + torsoW / 2, torsoTop - sy(4f), armW, armH)
                                    if (stateIdx % 2 == 1) {
                                        sheet.setColor(Color.valueOf("38BDF8"))
                                        sheet.fillCircle(cx, torsoTop - headR * 2 - sy(3f), sx(3f).coerceAtLeast(2))
                                    }
                                }
                                SpriteState.ATTACK -> {
                                    val dirShift = when (dirRow) { 1 -> -sx(4f); 2 -> sx(4f); else -> 0 }
                                    sheet.fillRectangle(cx + dirShift, torsoTop + sy(2f), sx(7f), sx(3f).coerceAtLeast(2))
                                    if (stateIdx % 2 == 1) {
                                        sheet.setColor(Color.valueOf("E2E8F0"))
                                        sheet.fillRectangle(cx + dirShift + sx(2f), torsoTop - sy(1f), sx(6f), 2)
                                    }
                                }
                                SpriteState.HARVEST -> {
                                    sheet.setColor(Color.valueOf("D97706"))
                                    sheet.fillRectangle(cx + sx(4f), torsoTop + sy(3f), sx(5f), 2)
                                }
                                else -> {
                                    sheet.fillRectangle(cx - torsoW / 2 - armW + 1, torsoTop + sy(1f), armW, armH)
                                    sheet.fillRectangle(cx + torsoW / 2 - 1, torsoTop + sy(1f), armW, armH)
                                }
                            }

                            // Head
                            val headY = torsoTop - headR + 1
                            sheet.setColor(Color.valueOf(colors.second))
                            sheet.fillCircle(cx, headY, headR)

                            // Directional eyes / back of head
                            sheet.setColor(Color.BLACK)
                            when (dirRow) {
                                0 -> {
                                    sheet.fillRectangle(cx - sx(3f), headY - 1, 2, 2)
                                    sheet.fillRectangle(cx + sx(1f), headY - 1, 2, 2)
                                }
                                1 -> sheet.fillRectangle(cx - sx(4f), headY - 1, 2, 2)
                                2 -> sheet.fillRectangle(cx + sx(2f), headY - 1, 2, 2)
                                3 -> {
                                    sheet.setColor(Color.valueOf(colors.first))
                                    sheet.fillRectangle(cx - headR + 1, headY - headR + 1, (headR - 1) * 2, headR)
                                }
                            }
                        }
                        colCursor++
                    }
                }
            }
            result[id] = sheet
        }
        return result
    }

    private fun buildClassicStarterSpritePixmaps(palettes: Map<Int, Pair<String, String>>): Map<Int, Pixmap> {
        val result = linkedMapOf<Int, Pixmap>()

        // 3 columns (Idle, WalkL, WalkR) x 4 rows (DOWN, LEFT, RIGHT, UP)
        for ((id, colors) in palettes) {
            val sheet = Pixmap(3 * tileSize, 4 * tileSize, Pixmap.Format.RGBA8888)
            for (dirRow in 0..3) {
                for (frameCol in 0..2) {
                    val ox = frameCol * tileSize
                    val oy = dirRow * tileSize
                    val legOffset = when (frameCol) {
                        1 -> -2
                        2 -> 2
                        else -> 0
                    }
                    val bobY = if (frameCol != 0) -1 else 0

                    if (id == 7) {
                        // Animated Slime (squashes & stretches on walk frames)
                        val radX = if (frameCol == 0) 10 else 11
                        sheet.setColor(Color(0f, 0f, 0f, 0.3f))
                        sheet.fillCircle(ox + 16, oy + 27, 9)
                        sheet.setColor(Color.valueOf(colors.first))
                        sheet.fillCircle(ox + 16, oy + 20 + bobY, radX)
                        sheet.setColor(Color.valueOf(colors.second))
                        sheet.fillCircle(ox + 13, oy + 17 + bobY, 4)
                        if (dirRow != 3) {
                            sheet.setColor(Color.BLACK)
                            val eyeShift = when (dirRow) {
                                1 -> -3
                                2 -> 3
                                else -> 0
                            }
                            sheet.fillRectangle(ox + 12 + eyeShift, oy + 18 + bobY, 2, 3)
                            sheet.fillRectangle(ox + 18 + eyeShift, oy + 18 + bobY, 2, 3)
                        }
                    } else {
                        // Shadow
                        sheet.setColor(Color(0f, 0f, 0f, 0.35f))
                        sheet.fillCircle(ox + 16, oy + 28, 8)
                        // Animated Legs / Boots
                        sheet.setColor(Color.valueOf("1E293B"))
                        sheet.fillRectangle(ox + 11, oy + 24 + legOffset, 4, 5)
                        sheet.fillRectangle(ox + 17, oy + 24 - legOffset, 4, 5)
                        // Tunic / Torso
                        sheet.setColor(Color.valueOf(colors.first))
                        sheet.fillRectangle(ox + 10, oy + 13 + bobY, 12, 12)
                        // Head
                        sheet.setColor(Color.valueOf(colors.second))
                        sheet.fillCircle(ox + 16, oy + 9 + bobY, 6)
                        // Directional eyes so facing direction is unmistakable
                        sheet.setColor(Color.BLACK)
                        when (dirRow) {
                            0 -> { // DOWN
                                sheet.fillRectangle(ox + 13, oy + 8 + bobY, 2, 2)
                                sheet.fillRectangle(ox + 17, oy + 8 + bobY, 2, 2)
                            }
                            1 -> { // LEFT
                                sheet.fillRectangle(ox + 11, oy + 8 + bobY, 2, 2)
                            }
                            2 -> { // RIGHT
                                sheet.fillRectangle(ox + 19, oy + 8 + bobY, 2, 2)
                            }
                            3 -> { // UP (back of head / hair / hood)
                                sheet.setColor(Color.valueOf(colors.first))
                                sheet.fillRectangle(ox + 11, oy + 5 + bobY, 10, 5)
                            }
                        }
                    }
                }
            }
            result[id] = sheet
        }
        return result
    }

    private fun createStarterUiAndFxAtlas() {
        val icons = buildStarterIconPixmaps()
        val paperdolls = buildStarterPaperdollPixmaps()
        val projectiles = buildStarterProjectilePixmaps()
        val animations = buildStarterAnimationPixmaps()

        for ((id, pm) in paperdolls) {
            val single = arrayOf(arrayOf(SpriteCellRect(0, 0, pm.width, pm.height)))
            paperdollRgbaCells[id] = false to extractSheetCells(pm, single, isOverlay = true)
        }

        val allSources = LinkedHashMap<String, Pixmap>()
        icons.forEach { (id, pm) -> allSources["icon:$id"] = pm }
        paperdolls.forEach { (id, pm) -> allSources["pd:$id"] = pm }
        projectiles.forEach { (id, pm) -> allSources["proj:$id"] = pm }
        animations.forEach { (id, pm) -> allSources["anim:$id"] = pm }

        val packed = packPixmapsIntoAtlases(allSources)
        for (id in icons.keys) {
            val r = packed.getValue("icon:$id")
            itemIconRegions[id] = TextureRegion(r.texture, r.x, r.y, r.width, r.height)
        }
        for (id in paperdolls.keys) {
            val r = packed.getValue("pd:$id")
            paperdollRegions[id] = TextureRegion(r.texture, r.x, r.y, r.width, r.height)
        }
        for (id in projectiles.keys) {
            val r = packed.getValue("proj:$id")
            projectileRegions[id] = TextureRegion(r.texture, r.x, r.y, r.width, r.height)
        }
        for (id in animations.keys) {
            val r = packed.getValue("anim:$id")
            animationStrips[id] = sliceAnimationStrip(r.texture, r.x, r.y, r.width, r.height)
        }
    }

    /** Draws every built-in item icon (32 x 32) into new [Pixmap]s owned by the caller, keyed by icon id, for exporting as `assets/icons/<id>.png`. */
    fun buildStarterIconPixmaps(): Map<Int, Pixmap> {
        val result = linkedMapOf<Int, Pixmap>()
        val iconColors = mapOf(
            1 to "FACC15",  // Gold Coin
            2 to "EF4444",  // Crimson Potion
            3 to "38BDF8",  // Azure Ether
            4 to "E2E8F0",  // Iron Longsword
            5 to "A16207",  // Hunter's Shortbow
            6 to "FDE047",  // Wood Arrow
            7 to "C084FC",  // Apprentice Staff
            8 to "B45309",  // Oak Buckler
            9 to "2563EB",  // Adventurer Tunic
            10 to "94A3B8", // Steel Greathelm
            11 to "D97706", // Bronze Hatchet
            12 to "64748B", // Miner's Pickaxe
            13 to "78350F", // Oak Log
            14 to "F97316", // Copper Ore
            15 to "FDE047", // Crypt Skeleton Key
            16 to "A855F7"  // Scroll of Frost Nova
        )
        for ((id, hex) in iconColors) {
            val pm = Pixmap(tileSize, tileSize, Pixmap.Format.RGBA8888)
            pm.setColor(Color.valueOf("1E293B"))
            pm.fillRectangle(2, 2, 28, 28)
            pm.setColor(Color.valueOf(hex))
            when (id) {
                1 -> pm.fillCircle(16, 16, 9)
                2, 3 -> {
                    pm.fillCircle(16, 19, 8)
                    pm.fillRectangle(13, 8, 6, 5)
                }
                4, 7, 11, 12 -> {
                    pm.fillRectangle(14, 6, 4, 20)
                    pm.fillRectangle(10, 18, 12, 3)
                }
                else -> pm.fillRectangle(8, 8, 16, 16)
            }
            pm.setColor(Color.WHITE)
            pm.drawRectangle(2, 2, 28, 28)
            result[id] = pm
        }
        return result
    }

    /** Draws every built-in paperdoll layer (32 x 32) into new [Pixmap]s owned by the caller, keyed by paperdoll id, for exporting as `assets/paperdolls/<id>.png`. */
    fun buildStarterPaperdollPixmaps(): Map<Int, Pixmap> {
        val result = linkedMapOf<Int, Pixmap>()
        val specs = mapOf(
            101 to Color.valueOf("E2E8F0"),
            102 to Color.valueOf("A16207"),
            103 to Color.valueOf("C084FC"),
            104 to Color.valueOf("D97706"),
            105 to Color.valueOf("94A3B8"),
            201 to Color.valueOf("B45309"),
            301 to Color.valueOf("38BDF8"),
            401 to Color.valueOf("CBD5E1")
        )
        for ((id, color) in specs) {
            val pm = Pixmap(tileSize, tileSize, Pixmap.Format.RGBA8888)
            pm.setColor(color)
            when (id) {
                in 101..105 -> pm.fillRectangle(22, 8, 4, 16)
                201 -> pm.fillRectangle(5, 14, 6, 10)
                301 -> pm.drawRectangle(10, 14, 12, 12)
                401 -> pm.fillRectangle(10, 3, 12, 5)
            }
            result[id] = pm
        }
        return result
    }

    /** Draws the built-in projectiles (32 x 32) for exporting as `assets/projectiles/<id>.png`. */
    fun buildStarterProjectilePixmaps(): Map<Int, Pixmap> {
        val p1 = Pixmap(tileSize, tileSize, Pixmap.Format.RGBA8888)
        p1.setColor(Color.valueOf("FDE047"))
        p1.fillRectangle(10, 14, 12, 4)

        val p2 = Pixmap(tileSize, tileSize, Pixmap.Format.RGBA8888)
        p2.setColor(Color.valueOf("F97316"))
        p2.fillCircle(16, 16, 6)
        return linkedMapOf(1 to p1, 2 to p2)
    }

    /** Draws the built-in 5-frame spell and combat animation strips (`160 x 32`) for exporting as `assets/animations/<id>.png`. */
    fun buildStarterAnimationPixmaps(): Map<Int, Pixmap> {
        val result = linkedMapOf<Int, Pixmap>()
        val frames = AnimationStripLayout.DEFAULT_FRAME_COUNT
        val styles = mapOf(
            1 to Pair(Color.valueOf("F8FAFC"), Color.valueOf("CBD5E1")), // Slash / impact
            2 to Pair(Color.valueOf("4ADE80"), Color.valueOf("BBF7D0")), // Heal aura
            3 to Pair(Color.valueOf("F97316"), Color.valueOf("FDE047")), // Flame burst
            4 to Pair(Color.valueOf("38BDF8"), Color.valueOf("E0F2FE"))  // Frost crystal
        )
        for ((id, palette) in styles) {
            val (primary, highlight) = palette
            val pm = Pixmap(tileSize * frames, tileSize, Pixmap.Format.RGBA8888)
            for (f in 0 until frames) {
                val ox = f * tileSize
                val cx = ox + tileSize / 2
                val cy = tileSize / 2
                val radius = 4 + f * 2
                val alpha = 1f - f * 0.14f
                pm.setColor(primary.r, primary.g, primary.b, alpha)
                when (id) {
                    1 -> {
                        val reach = 4 + f * 2
                        pm.drawLine(cx - reach, cy - reach, cx + reach, cy + reach)
                        pm.drawLine(cx - reach + 1, cy - reach, cx + reach + 1, cy + reach)
                        pm.setColor(highlight.r, highlight.g, highlight.b, (alpha * 0.9f).coerceIn(0f, 1f))
                        pm.drawLine(cx + reach, cy - reach, cx - reach, cy + reach)
                        pm.fillRectangle(cx - 1, cy - 1, 3, 3)
                    }
                    2 -> {
                        pm.drawCircle(cx, cy, radius)
                        pm.setColor(highlight.r, highlight.g, highlight.b, alpha)
                        val rise = (f * 2).coerceAtMost(8)
                        pm.drawLine(cx - 3, cy - rise, cx + 3, cy - rise)
                        pm.drawLine(cx, cy - rise - 3, cx, cy - rise + 3)
                        if (f > 0) pm.drawPixel(cx - radius, cy + 4 - f)
                        if (f > 1) pm.drawPixel(cx + radius, cy - f)
                    }
                    3 -> {
                        pm.fillCircle(cx, cy, (radius - 2).coerceAtLeast(2))
                        pm.drawCircle(cx, cy, radius)
                        pm.setColor(highlight.r, highlight.g, highlight.b, alpha)
                        pm.fillCircle(cx, cy, (radius / 2).coerceAtLeast(1))
                        pm.drawLine(cx - radius - 1, cy, cx + radius + 1, cy)
                        pm.drawLine(cx, cy - radius - 1, cx, cy + radius + 1)
                    }
                    else -> {
                        pm.drawCircle(cx, cy, radius)
                        pm.drawLine(cx - radius, cy - radius, cx + radius, cy + radius)
                        pm.drawLine(cx + radius, cy - radius, cx - radius, cy + radius)
                        pm.setColor(highlight.r, highlight.g, highlight.b, alpha)
                        pm.drawLine(cx - radius - 1, cy, cx + radius + 1, cy)
                        pm.drawLine(cx, cy - radius - 1, cx, cy + radius + 1)
                    }
                }
            }
            result[id] = pm
        }
        return result
    }

    override fun dispose() {
        paperdollCompositeCache.invalidateAll()
        disposableTextures.forEach { it.dispose() }
        disposableTextures.clear()
    }
}
