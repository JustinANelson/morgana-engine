package com.morgana.tilegen.structure

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.EdgeSide
import com.morgana.tilegen.material.Heightfield
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelCanvas
import com.morgana.tilegen.material.PixelDither
import com.morgana.tilegen.material.PixelOutline
import com.morgana.tilegen.material.TileNoise
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Cohesive color and mood profiles for [DungeonTilesetGenerator].
 */
enum class DungeonTheme(
    val label: String,
    val floor: PaletteRamp,
    val wall: PaletteRamp,
    val liquid: PaletteRamp,
    val statue: PaletteRamp,
    val gold: PaletteRamp,
    val bone: PaletteRamp,
    val flame: PaletteRamp,
    val wood: PaletteRamp,
    val ink: Int,
    val shadowRgb: Int
) {
    /** Warm taupe flagstones, dark plum cobble walls, deep teal water, cool slate statues, spectral cyan flames. */
    CRYPT(
        label = "Sunken Crypt",
        floor = PalettePresets.DUNGEON_FLAGSTONE,
        wall = PalettePresets.DUNGEON_COBBLE_WALL,
        liquid = PalettePresets.DUNGEON_TEAL_WATER,
        statue = PalettePresets.DUNGEON_SCULPTED_SLATE,
        gold = PalettePresets.DUNGEON_TREASURE_GOLD,
        bone = PalettePresets.DUNGEON_BONE,
        flame = PalettePresets.DUNGEON_SPECTRAL_FLAME,
        wood = PalettePresets.DUNGEON_CHEST_WOOD,
        ink = PaletteRamp.rgba(27, 20, 26),
        shadowRgb = PaletteRamp.rgba(18, 12, 22)
    ),

    /** Basalt flagstones, volcanic obsidian cobble, molten lava moat, bronze statues, amber-orange flames. */
    EMBER_FORGE(
        label = "Ember Forge",
        floor = PaletteRamp.fromBaseColor(PaletteRamp.rgba(86, 78, 84), steps = 6, hueShift = 0.05f, valueSpread = 0.28f),
        wall = PaletteRamp.fromBaseColor(PaletteRamp.rgba(52, 40, 44), steps = 6, hueShift = 0.05f, valueSpread = 0.26f),
        liquid = PalettePresets.LAVA_MAGMA,
        statue = PalettePresets.CO_DRAGON_BRONZE,
        gold = PalettePresets.DUNGEON_TREASURE_GOLD,
        bone = PalettePresets.DUNGEON_BONE,
        flame = PaletteRamp(
            intArrayOf(
                PaletteRamp.rgba(86, 26, 18),
                PaletteRamp.rgba(164, 54, 24),
                PaletteRamp.rgba(224, 108, 32),
                PaletteRamp.rgba(248, 174, 58),
                PaletteRamp.rgba(255, 226, 128),
                PaletteRamp.rgba(255, 250, 218)
            )
        ),
        wood = PalettePresets.DUNGEON_CHEST_WOOD,
        ink = PaletteRamp.rgba(24, 15, 18),
        shadowRgb = PaletteRamp.rgba(18, 10, 14)
    ),

    /** Weathered limestone flagstones, mossy slate cobble, emerald subterranean water, marble statues, golden flames. */
    MOSSY_RUINS(
        label = "Mossy Ruins",
        floor = PaletteRamp.fromBaseColor(PaletteRamp.rgba(124, 122, 112), steps = 6, hueShift = 0.05f, valueSpread = 0.26f),
        wall = PaletteRamp.fromBaseColor(PaletteRamp.rgba(64, 72, 74), steps = 6, hueShift = 0.05f, valueSpread = 0.26f),
        liquid = PalettePresets.CO_IMPERIAL_JADE,
        statue = PalettePresets.TF_RUINS_MARBLE,
        gold = PalettePresets.DUNGEON_TREASURE_GOLD,
        bone = PalettePresets.DUNGEON_BONE,
        flame = PaletteRamp(
            intArrayOf(
                PaletteRamp.rgba(66, 54, 22),
                PaletteRamp.rgba(138, 108, 32),
                PaletteRamp.rgba(204, 166, 54),
                PaletteRamp.rgba(244, 216, 104),
                PaletteRamp.rgba(255, 244, 178),
                PaletteRamp.rgba(255, 252, 232)
            )
        ),
        wood = PalettePresets.DUNGEON_CHEST_WOOD,
        ink = PaletteRamp.rgba(22, 24, 26),
        shadowRgb = PaletteRamp.rgba(14, 18, 20)
    )
}

/**
 * Generated high-craft 2.5D dungeon tileset bundle: individual named 32x32 tiles,
 * multi-tile centerpiece props, a packed tileset sheet, and an assembled room showcase.
 */
data class DungeonTilesetBundle(
    val theme: DungeonTheme,
    val tileSize: Int,
    val columns: Int,
    val tiles: List<NamedTile>,
    val sheet: TileImage,
    val props: Map<String, TileImage>,
    val showcase: TileImage
)

/**
 * High-craft 2.5D top-down dungeon pixel-art generator built on [Heightfield], [PixelCanvas],
 * and [PixelOutline].
 *
 * Produces cohesive, game-ready 32x32 tilesets featuring:
 * - Low-contrast warm beveled flagstones with mortar seams, cracks, and directional wall AO.
 * - Multi-course rounded cobblestone 2.5D walls with dark void top-caps, crisp rim bevels, and alcoves.
 * - Overhanging floor-to-water ledges with vertical drop-walls, shoreline foam, and rippled teal water.
 * - Sculpted multi-tile centerpieces (lich statue, effigy sarcophagus, guardian knight, open coffin)
 *   and 1x1 props (chests, gold mounds, skulls, floor spikes, stairs, grates, glowing braziers & candles).
 */
class DungeonTilesetGenerator(
    val seed: Long = 1337L,
    val theme: DungeonTheme = DungeonTheme.CRYPT
) {
    val tileSize: Int = 32

    private fun PaletteRamp.at(idx: Int): Int = colors[idx.coerceIn(0, colors.lastIndex)]

    fun generateBundle(): DungeonTilesetBundle {
        val namedTiles = mutableListOf<NamedTile>()
        fun addTile(name: String, img: TileImage) {
            namedTiles += NamedTile(name, img)
        }

        // Row 0: Flagstone floors & wall-cast AO variants
        val floorClean = generateFloorTile(variant = 0)
        val floorCracked = generateFloorTile(variant = 1)
        val floorWorn = generateFloorTile(variant = 2)
        val floorDouble = generateFloorTile(variant = 3)
        val floorAoTop = applyEdgeAo(floorClean, EdgeSide.TOP, 10, 128)
        val floorAoLeft = applyEdgeAo(floorClean, EdgeSide.LEFT, 9, 110)
        val floorAoCorner = applyEdgeAo(floorAoTop, EdgeSide.LEFT, 9, 95)
        val floorAoBottom = applyEdgeAo(floorClean, EdgeSide.BOTTOM, 6, 85)

        addTile("floor_clean", floorClean)
        addTile("floor_cracked", floorCracked)
        addTile("floor_worn", floorWorn)
        addTile("floor_double", floorDouble)
        addTile("floor_ao_top", floorAoTop)
        addTile("floor_ao_left", floorAoLeft)
        addTile("floor_ao_corner", floorAoCorner)
        addTile("floor_ao_bottom", floorAoBottom)

        // Row 1: 2.5D Cobblestone walls & dark caps
        val wallCapVoid = generateWallCapTile(bottomEdge = false, topEdge = false, leftEdge = false, rightEdge = false)
        val wallCapBottom = generateWallCapTile(bottomEdge = true, topEdge = false, leftEdge = false, rightEdge = false)
        val wallCapTop = generateWallCapTile(bottomEdge = false, topEdge = true, leftEdge = false, rightEdge = false)
        val wallFaceUpper = generateCobbleWallFace(isLower = false, salt = 11L)
        val wallFaceLower = generateCobbleWallFace(isLower = true, salt = 29L)
        val wallFaceArch = generateWallArchFace()
        val wallFaceGargoyle = generateWallGargoyleFace()
        val wallPillar = generateWallPillarTile()

        addTile("wall_cap_void", wallCapVoid)
        addTile("wall_cap_bottom", wallCapBottom)
        addTile("wall_cap_top", wallCapTop)
        addTile("wall_face_upper", wallFaceUpper)
        addTile("wall_face_lower", wallFaceLower)
        addTile("wall_face_arch", wallFaceArch)
        addTile("wall_face_gargoyle", wallFaceGargoyle)
        addTile("wall_pillar", wallPillar)

        // Row 2: Teal water, overhanging floor ledges, stone bridges
        val waterCalm = generateWaterTile(frame = 0, deepAoTop = false)
        val waterRipple = generateWaterTile(frame = 1, deepAoTop = false)
        val waterDeepAo = generateWaterTile(frame = 0, deepAoTop = true)
        val ledgeSouth = generateFloorWaterLedge(EdgeSide.BOTTOM)
        val ledgeNorth = generateFloorWaterLedge(EdgeSide.TOP)
        val ledgeWest = generateFloorWaterLedge(EdgeSide.LEFT)
        val ledgeEast = generateFloorWaterLedge(EdgeSide.RIGHT)
        val bridgeH = generateBridgeTile(horizontal = true)

        addTile("water_calm", waterCalm)
        addTile("water_ripple", waterRipple)
        addTile("water_deep_ao", waterDeepAo)
        addTile("ledge_south", ledgeSouth)
        addTile("ledge_north", ledgeNorth)
        addTile("ledge_west", ledgeWest)
        addTile("ledge_east", ledgeEast)
        addTile("bridge_h", bridgeH)

        // Row 3: Vertical bridge, stairs, hazards, grates, 1x1 props
        val bridgeV = generateBridgeTile(horizontal = false)
        val stairsDown = generateStairsTile(descending = true, leftCurb = false, rightCurb = false)
        val spikesRetracted = generateSpikesTile(extended = false)
        val spikesExtended = generateSpikesTile(extended = true)
        val ironGrate = generateIronGrateTile()
        val chestClosed = generateChestTile(open = false)
        val chestOpen = generateChestTile(open = true)
        val goldPile = generateGoldPileTile()

        addTile("bridge_v", bridgeV)
        addTile("stairs_down", stairsDown)
        addTile("spikes_retracted", spikesRetracted)
        addTile("spikes_extended", spikesExtended)
        addTile("iron_grate", ironGrate)
        addTile("chest_closed", chestClosed)
        addTile("chest_open", chestOpen)
        addTile("gold_pile", goldPile)

        // Row 4: More 1x1 props (skulls, candles, key altar, stone block)
        val skullPile = generateSkullPileTile()
        val skullSingle = generateSingleSkullTile()
        val candleCluster = generateCandleClusterTile()
        val keyAltar = generateKeyAltarTile()
        val brazierSingle = generateBrazierProp().crop(8, 16, 32, 32)
        val wallSkullMedallion = generateWallSkullMedallion()
        val stairsLeft = generateStairsTile(descending = true, leftCurb = true, rightCurb = false)
        val stairsRight = generateStairsTile(descending = true, leftCurb = false, rightCurb = true)

        addTile("skull_pile", skullPile)
        addTile("skull_single", skullSingle)
        addTile("candle_cluster", candleCluster)
        addTile("key_altar", keyAltar)
        addTile("brazier_compact", brazierSingle)
        addTile("wall_skull_medallion", wallSkullMedallion)
        addTile("stairs_left", stairsLeft)
        addTile("stairs_right", stairsRight)

        // Multi-tile sculpted props
        val props = linkedMapOf(
            "lich_statue" to generateLichStatueProp(),              // 64x96 (2x3)
            "sarcophagus_effigy" to generateSarcophagusEffigyProp(), // 64x64 (2x2)
            "knight_statue" to generateKnightStatueProp(),           // 32x64 (1x2)
            "spectral_brazier" to generateBrazierProp(),             // 32x48 (1x1.5)
            "open_tomb_skeleton" to generateOpenTombProp(),          // 64x32 (2x1)
            "hanging_skeleton" to generateHangingSkeletonProp()      // 32x48
        )

        // Pack 8-column tileset sheet
        val columns = 8
        val rows = (namedTiles.size + columns - 1) / columns
        val sheet = TileImage(columns * tileSize, rows * tileSize)
        namedTiles.forEachIndexed { idx, nt ->
            sheet.drawImage(nt.image, 0, 0, tileSize, tileSize, (idx % columns) * tileSize, (idx / columns) * tileSize)
        }

        val showcase = renderShowcaseRoom(
            tiles = namedTiles.associate { it.name to it.image },
            props = props
        )

        return DungeonTilesetBundle(
            theme = theme,
            tileSize = tileSize,
            columns = columns,
            tiles = namedTiles,
            sheet = sheet,
            props = props,
            showcase = showcase
        )
    }

    // =================================================================================================================
    // 1. Flagstone Floors (low-contrast warm slabs with crisp recessed grout & optional cracks)
    // =================================================================================================================

    fun generateFloorTile(variant: Int = 0): TileImage {
        val out = TileImage(tileSize, tileSize)
        val c = PixelCanvas(out)
        val s = seed + variant * 197L

        val baseMid = theme.floor.colors[3]
        val baseDark = theme.floor.colors[2]
        val baseLit = theme.floor.colors[4]
        val grout = theme.floor.colors[1]

        // Calm, low-contrast warm stone slab fill with very subtle organic variation
        for (y in 0 until tileSize) {
            for (x in 0 until tileSize) {
                val n = TileNoise.wrappedFbm(x * 0.5f, y * 0.5f, period = tileSize, octaves = 2, seed = s)
                val col = when {
                    n > 0.72f -> baseLit
                    n < 0.24f -> baseDark
                    else -> baseMid
                }
                c.set(x, y, col)
            }
        }

        // Clean, crisp 1 px recessed flagstone seams (large slabs so floor stays calm & readable)
        if (variant == 1 || variant == 3) {
            for (x in 0 until tileSize) {
                if (x != 7 && x != 23) c.set(x, 15, grout)
                if (x in 2..13 || x in 18..29) c.set(x, 16, baseLit)
            }
            val vxTop = if (variant == 3) 10 else 16
            val vxBot = if (variant == 3) 22 else 4
            for (y in 0..14) c.set(vxTop, y, grout)
            for (y in 16 until tileSize) c.set(vxBot, y, grout)
        } else if (variant == 2) {
            // Subtle worn slab with a small L-shaped flagstone seam & tiny stone speck
            for (x in 4..26) c.set(x, 16, baseDark)
            for (y in 16..28) c.set(16, y, baseDark)
            c.set(8, 9, baseDark)
            c.set(9, 10, grout)
            c.set(10, 10, baseLit)
            c.set(23, 22, baseDark)
        } else {
            // Cleanest tile: subtle corner slab accents only (mirrors the reference's calm warm floors)
            for (x in 0..7) c.set(x, 0, baseDark)
            for (y in 0..5) c.set(0, y, baseDark)
            for (x in 18..25) c.set(x, 16, baseDark)
        }

        // Delicate crack on cracked variant
        if (variant == 1) {
            c.drawLine(5, 5, 11, 10, grout)
            c.drawLine(11, 10, 9, 13, baseDark)
            c.set(6, 4, baseLit)
        }
        return out
    }

    private fun applyEdgeAo(base: TileImage, side: EdgeSide, depth: Int, maxAlpha: Int): TileImage {
        val copy = base.copy()
        val canvas = PixelCanvas(copy)
        when (side) {
            EdgeSide.TOP -> canvas.drawDirectionalAO(0, 0, tileSize - 1, depth, EdgeSide.TOP, maxAlpha, theme.shadowRgb)
            EdgeSide.BOTTOM -> canvas.drawDirectionalAO(0, tileSize - 1 - depth, tileSize - 1, tileSize - 1, EdgeSide.BOTTOM, maxAlpha, theme.shadowRgb)
            EdgeSide.LEFT -> canvas.drawDirectionalAO(0, 0, depth, tileSize - 1, EdgeSide.LEFT, maxAlpha, theme.shadowRgb)
            EdgeSide.RIGHT -> canvas.drawDirectionalAO(tileSize - 1 - depth, 0, tileSize - 1, tileSize - 1, EdgeSide.RIGHT, maxAlpha, theme.shadowRgb)
        }
        return copy
    }

    // =================================================================================================================
    // 2. 2.5D Rounded Cobblestone Walls & Dark Void Caps
    // =================================================================================================================

    fun generateWallCapTile(
        bottomEdge: Boolean,
        topEdge: Boolean,
        leftEdge: Boolean,
        rightEdge: Boolean
    ): TileImage {
        val out = TileImage(tileSize, tileSize)
        val c = PixelCanvas(out)
        val voidBase = theme.ink
        val capMid = theme.wall.colors[1]
        val capTrim = theme.wall.colors[2]
        val capLit = theme.wall.colors[3]

        c.fillRect(0, 0, tileSize - 1, tileSize - 1, voidBase)
        // Subtle dark stone texture inside void cap
        for (y in 0 until tileSize) {
            for (x in 0 until tileSize) {
                if (TileNoise.hash(x, y, seed + 404L) > 0.90f) {
                    c.set(x, y, capMid)
                }
            }
        }
        if (bottomEdge) {
            c.fillRect(0, tileSize - 4, tileSize - 1, tileSize - 4, capMid)
            c.fillRect(0, tileSize - 3, tileSize - 1, tileSize - 2, capTrim)
            for (x in 0 until tileSize step 8) {
                c.set(x, tileSize - 2, capMid)
                c.set(x + 1, tileSize - 3, capLit)
            }
            c.fillRect(0, tileSize - 1, tileSize - 1, tileSize - 1, theme.ink)
        }
        if (topEdge) {
            c.fillRect(0, 0, tileSize - 1, 0, theme.ink)
            c.fillRect(0, 1, tileSize - 1, 2, capTrim)
            c.fillRect(0, 3, tileSize - 1, 3, capMid)
        }
        if (leftEdge) {
            c.fillRect(0, 0, 0, tileSize - 1, theme.ink)
            c.fillRect(1, 0, 2, tileSize - 1, capTrim)
        }
        if (rightEdge) {
            c.fillRect(tileSize - 3, 0, tileSize - 2, tileSize - 1, capMid)
            c.fillRect(tileSize - 1, 0, tileSize - 1, tileSize - 1, theme.ink)
        }
        return out
    }

    /**
     * Generates a 3D rounded cobblestone wall face tile using [Heightfield] stone domes
     * in staggered masonry courses, with deep dark mortar and top-left stone highlights.
     */
    fun generateCobbleWallFace(isLower: Boolean, salt: Long = 0L): TileImage {
        val out = TileImage(tileSize, tileSize)
        val hf = Heightfield(tileSize, tileSize)
        // Fill mask so mortar crevices render
        for (y in 0 until tileSize) for (x in 0 until tileSize) hf.set(x, y, 0.02f)

        val rows = 4
        val rowH = tileSize.toFloat() / rows
        for (r in -1..rows) {
            val cols = if (r % 2 == 0) 4 else 4
            val colW = tileSize.toFloat() / cols
            val rowOffset = if (Math.floorMod(r, 2) == 1) colW * 0.5f else 0f
            for (col in -1..cols) {
                val h1 = TileNoise.hash(Math.floorMod(col, cols), Math.floorMod(r, rows), seed + salt + 71L)
                val h2 = TileNoise.hash(Math.floorMod(col, cols), Math.floorMod(r, rows), seed + salt + 131L)
                val cx = col * colW + rowOffset + colW * 0.5f + (h1 - 0.5f) * 1.6f
                val cy = r * rowH + rowH * 0.5f + (h2 - 0.5f) * 1.1f
                val rx = colW * (0.45f + h1 * 0.07f)
                val ry = rowH * (0.44f + h2 * 0.07f)
                val peak = 0.78f + h1 * 0.22f
                hf.addDome(cx, cy, rx, ry, peak = peak, power = 0.52f)
            }
        }

        // Shade cobbles with crisp dark mortar & top-left illumination
        hf.shadeInto(
            target = out,
            ramp = theme.wall,
            lightX = -0.62f,
            lightY = -0.82f,
            lightZ = 0.52f,
            ambient = if (isLower) 0.36f else 0.48f,
            contrast = 0.72f,
            aoStrength = 0.45f,
            normalScale = 2.8f,
            ditherStrength = 0.06f,
            mortarThreshold = 0.14f,
            mortarColor = theme.ink,
            wrap = true
        )

        val c = PixelCanvas(out)
        if (!isLower) {
            // Subtle cornice shadow right under the wall cap lip
            c.drawDirectionalAO(0, 0, tileSize - 1, 4, EdgeSide.TOP, 110, theme.shadowRgb)
        } else {
            // Base plinth darkening & crisp floor-contact shadow line
            c.drawDirectionalAO(0, tileSize - 9, tileSize - 1, tileSize - 1, EdgeSide.BOTTOM, 115, theme.shadowRgb)
            c.fillRect(0, tileSize - 1, tileSize - 1, tileSize - 1, theme.ink)
        }
        return out
    }

    private fun generateWallArchFace(): TileImage {
        val out = generateCobbleWallFace(isLower = true, salt = 55L)
        val c = PixelCanvas(out)
        // Dark arched recessed alcove
        val cx = 15.5f
        val cy = 17f
        for (y in 4 until tileSize) {
            for (x in 4 until tileSize - 4) {
                val dx = (x + 0.5f - cx) / 10.5f
                val dy = if (y < cy) (y + 0.5f - cy) / 12.5f else 0f
                val d2 = dx * dx + dy * dy
                if (d2 <= 1f) {
                    val isRim = d2 > 0.74f && y < tileSize - 1
                    val isOuterRim = d2 > 0.88f
                    when {
                        isOuterRim -> c.set(x, y, theme.ink)
                        isRim -> c.set(x, y, if (x < cx) theme.wall.colors[4] else theme.wall.colors[2])
                        else -> c.set(x, y, theme.ink)
                    }
                }
            }
        }
        return out
    }

    private fun generateWallGargoyleFace(): TileImage {
        val out = generateCobbleWallFace(isLower = false, salt = 88L)
        val garg = TileImage(tileSize, tileSize)
        val hf = Heightfield(tileSize, tileSize)
        // Sculpted horned gargoyle/dragon head relief
        hf.addDome(16f, 15f, 8.5f, 8.5f, 0.92f, 0.6f)   // Cranium
        hf.addDome(16f, 21f, 6.2f, 5.2f, 0.98f, 0.55f)  // Snout
        hf.addDome(9f, 9f, 3.2f, 4.5f, 0.78f, 0.6f)     // Left horn/ear
        hf.addDome(23f, 9f, 3.2f, 4.5f, 0.78f, 0.6f)    // Right horn/ear
        hf.addDome(12.5f, 14f, 2.4f, 1.8f, 1.0f, 0.5f)  // Left brow
        hf.addDome(19.5f, 14f, 2.4f, 1.8f, 1.0f, 0.5f)  // Right brow
        hf.shadeInto(garg, theme.wall, ambient = 0.54f, contrast = 0.70f, ditherStrength = 0.05f, mortarThreshold = 0.04f)
        val gc = PixelCanvas(garg)
        // Dark mouth opening & glowing spectral eyes
        gc.fillRect(13, 22, 18, 25, theme.ink)
        gc.set(12, 16, theme.flame.colors[4])
        gc.set(13, 16, theme.flame.colors[3])
        gc.set(18, 16, theme.flame.colors[4])
        gc.set(19, 16, theme.flame.colors[3])
        val outlined = PixelOutline.applyCrispOutline(garg, theme.ink)
        out.drawImage(outlined, 0, 0, tileSize, tileSize, 0, 0)
        // Subtle eye glow
        PixelCanvas(out).drawGlow(12.5f, 16f, 5f, theme.flame.colors[3], intensity = 0.45f)
        PixelCanvas(out).drawGlow(18.5f, 16f, 5f, theme.flame.colors[3], intensity = 0.45f)
        return out
    }

    private fun generateWallPillarTile(): TileImage {
        val out = TileImage(tileSize, tileSize)
        val hf = Heightfield(tileSize, tileSize)
        // Cylindrical stone buttress column
        for (y in 0 until tileSize) {
            for (x in 5 until tileSize - 5) {
                val dx = (x + 0.5f - 16f) / 11f
                val cyl = kotlin.math.sqrt((1f - dx * dx).coerceAtLeast(0f))
                val fluting = sin(dx * 9f) * 0.06f
                hf.set(x, y, (0.35f + cyl * 0.65f + fluting).coerceIn(0.1f, 1f))
            }
        }
        // Horizontal band rings at top & bottom
        hf.addBeveledBox(3, 1, 28, 6, peak = 1.05f, bevel = 1.5f)
        hf.addBeveledBox(3, 26, 28, 30, peak = 1.02f, bevel = 1.5f)
        hf.shadeInto(out, theme.wall, ambient = 0.50f, contrast = 0.68f, ditherStrength = 0.06f, mortarThreshold = 0.05f)
        return PixelOutline.applyCrispOutline(out, theme.ink)
    }

    // =================================================================================================================
    // 3. Teal Subterranean Water, Overhanging Floor-to-Water Ledges & Stone Bridges
    // =================================================================================================================

    fun generateWaterTile(frame: Int = 0, deepAoTop: Boolean = false): TileImage {
        val out = TileImage(tileSize, tileSize)
        val c = PixelCanvas(out)
        val shiftX = frame * 6
        val base = theme.liquid.colors[2]
        val deeper = theme.liquid.colors[1]
        val midLit = theme.liquid.colors[3]
        val foam = theme.liquid.colors[4]

        // Calm, clean teal surface with subtle horizontal depth bands (no noisy pixel static)
        for (y in 0 until tileSize) {
            val band = sin((y * 0.42f + frame * 0.8f).toDouble()).toFloat()
            for (x in 0 until tileSize) {
                val wave = sin(((x + shiftX) * 0.22f + y * 0.15f).toDouble()).toFloat()
                val col = when {
                    band + wave > 1.25f -> midLit
                    band + wave < -1.15f -> deeper
                    else -> base
                }
                c.set(x, y, col)
            }
        }
        // Crisp horizontal pixel-art shimmer streaks
        val ripples = listOf(
            Triple((3 + shiftX) % tileSize, 6, 8),
            Triple((18 + shiftX) % tileSize, 13, 9),
            Triple((8 + shiftX) % tileSize, 21, 7),
            Triple((21 + shiftX) % tileSize, 27, 8)
        )
        for ((rx, ry, len) in ripples) {
            for (i in 0 until len) {
                val px = (rx + i) % tileSize
                val isCenter = i in 2 until (len - 2)
                c.set(px, ry, if (isCenter) foam else midLit)
            }
        }
        if (deepAoTop) {
            c.drawDirectionalAO(0, 0, tileSize - 1, 10, EdgeSide.TOP, 125, theme.shadowRgb)
        }
        return out
    }

    /**
     * Generates a 2.5D overhanging floor-over-water ledge tile:
     * crisp beveled floor edge -> dark submerged vertical stone drop-wall -> bright shoreline foam -> teal water!
     */
    fun generateFloorWaterLedge(waterSide: EdgeSide): TileImage {
        val out = generateWaterTile(frame = 0, deepAoTop = false)
        val floor = generateFloorTile(variant = 0)
        val c = PixelCanvas(out)

        when (waterSide) {
            EdgeSide.BOTTOM -> {
                // Top 11 px: overhanging stone floor with bright top bevel & dark outline lip
                out.drawImage(floor, 0, 0, tileSize, 11, 0, 0)
                c.fillRect(0, 9, tileSize - 1, 9, theme.floor.colors[5])
                c.fillRect(0, 10, tileSize - 1, 10, theme.ink)
                // 10 px dark vertical stone drop-wall beneath the overhang
                val dropHf = Heightfield(tileSize, 11)
                for (y in 0 until 11) for (x in 0 until tileSize) dropHf.set(x, y, 0.04f)
                for (row in 0..1) {
                    val y0 = row * 5
                    val off = if (row == 1) 4 else 0
                    for (col in 0..4) {
                        val bx = (col * 8 + off - 4)
                        dropHf.addBeveledBox(bx + 1, y0 + 1, bx + 7, y0 + 4, peak = 0.72f - row * 0.14f, bevel = 1.2f)
                    }
                }
                dropHf.shadeInto(out, theme.wall, dstX = 0, dstY = 11, ambient = 0.34f, contrast = 0.55f, mortarColor = theme.ink)
                c.fillRect(0, 21, tileSize - 1, 21, theme.ink)
                // Shoreline water reflection & bright teal foam line
                c.drawDirectionalAO(0, 22, tileSize - 1, 28, EdgeSide.TOP, 95, theme.shadowRgb)
                for (x in 0 until tileSize) {
                    val foam = if ((x / 3 + x) % 4 != 0) theme.liquid.at(4) else theme.liquid.at(3)
                    c.set(x, 22, foam)
                    if (x % 5 == 0) c.set(x, 23, theme.liquid.highlight)
                }
            }
            EdgeSide.TOP -> {
                // Water on top, floor rising below with crisp curb lip
                c.drawDirectionalAO(0, 10, tileSize - 1, 16, EdgeSide.BOTTOM, 75, theme.shadowRgb)
                for (x in 0 until tileSize) {
                    if (x % 3 != 0) c.set(x, 15, theme.liquid.colors[4])
                }
                out.drawImage(floor, 0, 16, tileSize, 16, 0, 16)
                c.fillRect(0, 16, tileSize - 1, 16, theme.ink)
                c.fillRect(0, 17, tileSize - 1, 17, theme.floor.colors[5])
                c.fillRect(0, 18, tileSize - 1, 18, theme.floor.colors[4])
            }
            EdgeSide.LEFT -> {
                // Water on left, floor on right with dark side lip
                c.drawDirectionalAO(9, 0, 15, tileSize - 1, EdgeSide.RIGHT, 90, theme.shadowRgb)
                for (y in 0 until tileSize) {
                    if (y % 3 != 0) c.set(14, y, theme.liquid.colors[4])
                }
                out.drawImage(floor, 16, 0, 16, tileSize, 16, 0)
                c.fillRect(15, 0, 15, tileSize - 1, theme.ink)
                c.fillRect(16, 0, 16, tileSize - 1, theme.floor.colors[5])
            }
            EdgeSide.RIGHT -> {
                // Floor on left, water on right with dark drop edge
                out.drawImage(floor, 0, 0, 16, tileSize, 0, 0)
                c.fillRect(15, 0, 15, tileSize - 1, theme.floor.colors[2])
                c.fillRect(16, 0, 16, tileSize - 1, theme.ink)
                c.drawDirectionalAO(17, 0, 23, tileSize - 1, EdgeSide.LEFT, 105, theme.shadowRgb)
                for (y in 0 until tileSize) {
                    if (y % 4 != 0) c.set(17, y, theme.liquid.colors[4])
                }
            }
        }
        return out
    }

    private fun generateBridgeTile(horizontal: Boolean): TileImage {
        val out = generateWaterTile(frame = 0, deepAoTop = false)
        val c = PixelCanvas(out)
        if (horizontal) {
            // Cast shadow into water beneath bridge
            c.drawDirectionalAO(0, 22, tileSize - 1, 29, EdgeSide.TOP, 125, theme.shadowRgb)
            // Stone bridge slab (y = 7..22)
            c.fillRect(0, 6, tileSize - 1, 6, theme.ink)
            c.fillBeveledRect(0, 7, tileSize - 1, 21, theme.floor, baseTone = 0.62f, topBoost = 0.24f, bottomDrop = 0.20f)
            // Plank/stone slab joints
            for (x in listOf(8, 16, 24)) {
                c.drawLine(x, 8, x, 20, theme.floor.colors[1])
                c.drawLine(x + 1, 8, x + 1, 20, theme.floor.colors[4])
            }
            // Dark stone bridge underside
            c.fillRect(0, 22, tileSize - 1, 24, theme.wall.colors[1])
            c.fillRect(0, 25, tileSize - 1, 25, theme.ink)
            for (x in 0 until tileSize step 4) c.set(x, 26, theme.liquid.colors[4])
        } else {
            // Vertical bridge (x = 7..24)
            c.drawDirectionalAO(25, 0, 30, tileSize - 1, EdgeSide.LEFT, 110, theme.shadowRgb)
            c.fillRect(6, 0, 6, tileSize - 1, theme.ink)
            c.fillRect(25, 0, 25, tileSize - 1, theme.ink)
            c.fillBeveledRect(7, 0, 24, tileSize - 1, theme.floor, baseTone = 0.62f, topBoost = 0.16f, bottomDrop = 0.16f)
            // Side curbs
            c.fillBeveledRect(7, 0, 9, tileSize - 1, theme.floor, baseTone = 0.72f)
            c.fillBeveledRect(22, 0, 24, tileSize - 1, theme.floor, baseTone = 0.48f)
            for (y in listOf(8, 16, 24)) {
                c.drawLine(10, y, 21, y, theme.floor.colors[1])
                c.drawLine(10, y + 1, 21, y + 1, theme.floor.colors[4])
            }
        }
        return out
    }

    // =================================================================================================================
    // 4. Hazards, Stairs, Grates & 1x1 Dungeon Props
    // =================================================================================================================

    fun generateStairsTile(
        descending: Boolean = true,
        leftCurb: Boolean = true,
        rightCurb: Boolean = true
    ): TileImage {
        val out = TileImage(tileSize, tileSize)
        val c = PixelCanvas(out)
        val x0 = if (leftCurb) 3 else 0
        val x1 = if (rightCurb) tileSize - 4 else tileSize - 1
        val steps = 5
        val stepH = tileSize / steps
        for (i in 0 until steps) {
            val y0 = i * stepH
            val y1 = if (i == steps - 1) tileSize - 1 else (i + 1) * stepH - 1
            val tone = if (descending) 0.72f - i * 0.08f else 0.40f + i * 0.08f
            c.fillBeveledRect(x0, y0, x1, y1, theme.floor, baseTone = tone, topBoost = 0.18f, bottomDrop = 0.24f, dither = 0.04f)
            c.fillRect(x0, y1, x1, y1, theme.ink)
        }
        if (leftCurb) {
            c.fillBeveledRect(0, 0, 2, tileSize - 1, theme.wall, baseTone = 0.52f)
            c.fillRect(0, 0, 0, tileSize - 1, theme.ink)
        }
        if (rightCurb) {
            c.fillBeveledRect(tileSize - 3, 0, tileSize - 1, tileSize - 1, theme.wall, baseTone = 0.42f)
            c.fillRect(tileSize - 1, 0, tileSize - 1, tileSize - 1, theme.ink)
        }
        return out
    }

    fun generateSpikesTile(extended: Boolean): TileImage {
        val out = generateFloorTile(variant = 0)
        val c = PixelCanvas(out)
        // Dark circular/beveled iron socket in floor center
        c.fillShadedEllipse(16f, 20f, 6.2f, 4.4f, theme.statue, baseTone = 0.28f)
        c.fillShadedEllipse(16f, 20f, 4.4f, 3.0f, theme.wall, baseTone = 0.06f)
        // Rim highlight
        c.drawLine(12, 23, 19, 23, theme.floor.colors[4])
        if (extended) {
            val spike = TileImage(tileSize, tileSize)
            val sc = PixelCanvas(spike)
            // Tall conical metallic spike rising from socket
            for (y in 4..20) {
                val v = (y - 4f) / 16f
                val halfW = (0.6f + v * 3.8f).roundToInt()
                for (x in (16 - halfW)..(16 + halfW)) {
                    val u = if (halfW == 0) 0f else (x - 16f) / halfW
                    val tone = (0.72f - u * 0.34f - v * 0.14f).coerceIn(0.14f, 0.98f)
                    sc.set(x, y, theme.statue.sample(tone))
                }
            }
            // Bright metallic tip glint
            sc.set(15, 5, theme.statue.highlight)
            sc.set(15, 7, theme.statue.highlight)
            sc.set(14, 10, theme.statue.at(4))
            val outlined = PixelOutline.applyCrispOutline(spike, theme.ink, litEdgeBlend = 0.18f)
            // Subtle cast shadow to bottom-right
            c.drawContactShadow(18.5f, 21f, 5.5f, 2.5f, theme.shadowRgb, coreAlpha = 95, rimAlpha = 45)
            out.drawImage(outlined, 0, 0, tileSize, tileSize, 0, 0)
        }
        return out
    }

    private fun generateIronGrateTile(): TileImage {
        val out = generateFloorWaterLedge(EdgeSide.BOTTOM)
        val c = PixelCanvas(out)
        // Recessed sewer iron grate embedded into the vertical drop-wall above the water
        c.fillBeveledRect(7, 10, 24, 22, theme.statue, baseTone = 0.45f)
        c.fillRect(9, 12, 22, 21, theme.ink)
        for (bx in listOf(11, 14, 17, 20)) {
            c.fillRect(bx, 12, bx, 21, theme.statue.colors[4])
            c.fillRect(bx + 1, 12, bx + 1, 21, theme.statue.colors[2])
        }
        c.fillRect(9, 16, 22, 16, theme.statue.colors[3])
        // Bright water splash/runoff beneath the grate
        for (x in 10..21 step 2) c.set(x, 22, theme.liquid.highlight)
        return out
    }

    fun generateChestTile(open: Boolean): TileImage {
        val sprite = TileImage(tileSize, tileSize)
        val c = PixelCanvas(sprite)

        // Chest body (x = 5..26, y = 12..26)
        c.fillBeveledRect(5, 13, 26, 26, theme.wood, baseTone = 0.48f, topBoost = 0.18f, bottomDrop = 0.22f)
        // Horizontal wood plank seams
        c.drawLine(6, 17, 25, 17, theme.wood.colors[1])
        c.drawLine(6, 21, 25, 21, theme.wood.colors[1])

        // Cool slate-steel iron bands & corner brackets
        c.fillBeveledRect(5, 13, 8, 26, theme.statue, baseTone = 0.56f)
        c.fillBeveledRect(23, 13, 26, 26, theme.statue, baseTone = 0.44f)
        c.fillRect(5, 24, 26, 26, theme.statue.colors[2])

        if (!open) {
            // Closed arched lid (y = 6..13)
            c.fillBeveledRect(5, 6, 26, 13, theme.wood, baseTone = 0.62f, topBoost = 0.26f, bottomDrop = 0.16f)
            c.fillBeveledRect(5, 6, 8, 13, theme.statue, baseTone = 0.66f)
            c.fillBeveledRect(23, 6, 26, 13, theme.statue, baseTone = 0.52f)
            c.fillRect(6, 13, 25, 13, theme.ink)
            // Gold/iron clasp in center
            c.fillBeveledRect(14, 11, 17, 16, theme.gold, baseTone = 0.68f)
            c.set(15, 14, theme.ink)
        } else {
            // Open lid tipped back (y = 3..10)
            c.fillBeveledRect(5, 3, 26, 10, theme.wood, baseTone = 0.36f, topBoost = 0.20f, bottomDrop = 0.15f)
            c.fillBeveledRect(5, 3, 8, 10, theme.statue, baseTone = 0.58f)
            c.fillBeveledRect(23, 3, 26, 10, theme.statue, baseTone = 0.46f)
            c.fillRect(8, 10, 23, 14, theme.ink)
            // Overflowing gleaming gold coins inside!
            val goldHf = Heightfield(tileSize, tileSize)
            goldHf.addDome(13f, 13.5f, 5.2f, 3.5f, 0.95f, 0.55f)
            goldHf.addDome(18.5f, 13.5f, 5.2f, 3.5f, 0.92f, 0.55f)
            goldHf.addDome(16f, 12f, 5.0f, 3.2f, 1.0f, 0.5f)
            goldHf.shadeInto(sprite, theme.gold, ambient = 0.64f, contrast = 0.65f, ditherStrength = 0.08f, mortarThreshold = 0.05f)
            // Bright coin sparkles
            c.set(13, 11, theme.gold.colors[5])
            c.set(17, 10, theme.gold.colors[5])
            c.set(19, 12, theme.gold.colors[5])
            c.fillBeveledRect(14, 15, 17, 18, theme.statue, baseTone = 0.65f)
        }

        val out = TileImage(tileSize, tileSize)
        PixelCanvas(out).drawContactShadow(16f, 27f, 12.5f, 3.6f, theme.shadowRgb, coreAlpha = 135, rimAlpha = 65)
        out.drawImage(PixelOutline.applyCrispOutline(sprite, theme.ink), 0, 0, tileSize, tileSize, 0, 0)
        return out
    }

    fun generateGoldPileTile(): TileImage {
        val sprite = TileImage(tileSize, tileSize)
        val hf = Heightfield(tileSize, tileSize)
        // Multi-lobed sculpted mound of coins
        hf.addDome(16f, 20f, 10.5f, 6.2f, 0.85f, 0.55f)
        hf.addDome(14f, 17f, 6.8f, 5.2f, 0.96f, 0.52f)
        hf.addDome(19f, 18.5f, 5.8f, 4.4f, 0.88f, 0.55f)
        hf.addDome(15.5f, 14.5f, 4.2f, 3.4f, 1.0f, 0.5f)
        // Individual coin bumps
        val coins = listOf(10 to 21, 13 to 18, 16 to 15, 19 to 18, 22 to 21, 15 to 21, 12 to 23, 18 to 23, 21 to 23)
        for ((cx, cy) in coins) {
            hf.addDome(cx.toFloat(), cy.toFloat(), 2.1f, 1.5f, 1.04f, 0.45f)
        }
        hf.shadeInto(sprite, theme.gold, ambient = 0.58f, contrast = 0.72f, ditherStrength = 0.08f, mortarThreshold = 0.08f)
        val sc = PixelCanvas(sprite)
        sc.set(14, 14, theme.gold.colors[5])
        sc.set(16, 16, theme.gold.colors[5])
        sc.set(12, 19, theme.gold.colors[5])
        sc.set(19, 18, theme.gold.colors[5])

        val out = TileImage(tileSize, tileSize)
        PixelCanvas(out).drawContactShadow(16f, 24.5f, 11.5f, 3.6f, theme.shadowRgb, coreAlpha = 125, rimAlpha = 55)
        out.drawImage(PixelOutline.applyCrispOutline(sprite, theme.ink), 0, 0, tileSize, tileSize, 0, 0)
        return out
    }

    fun generateSkullPileTile(): TileImage {
        val sprite = TileImage(tileSize, tileSize)
        drawSculptedSkull(sprite, cx = 12f, cy = 14f, rx = 5.4f, ry = 5.0f)
        drawSculptedSkull(sprite, cx = 20f, cy = 18f, rx = 5.0f, ry = 4.6f)
        drawSculptedSkull(sprite, cx = 11f, cy = 22f, rx = 4.6f, ry = 4.2f)
        val out = TileImage(tileSize, tileSize)
        PixelCanvas(out).drawContactShadow(16f, 25.5f, 11f, 3.8f, theme.shadowRgb, coreAlpha = 120, rimAlpha = 55)
        out.drawImage(PixelOutline.applyCrispOutline(sprite, theme.ink), 0, 0, tileSize, tileSize, 0, 0)
        return out
    }

    fun generateSingleSkullTile(): TileImage {
        val sprite = TileImage(tileSize, tileSize)
        drawSculptedSkull(sprite, cx = 16f, cy = 18f, rx = 5.8f, ry = 5.4f)
        val out = TileImage(tileSize, tileSize)
        PixelCanvas(out).drawContactShadow(16f, 24.5f, 6.5f, 2.8f, theme.shadowRgb, coreAlpha = 115, rimAlpha = 50)
        out.drawImage(PixelOutline.applyCrispOutline(sprite, theme.ink), 0, 0, tileSize, tileSize, 0, 0)
        return out
    }

    private fun drawSculptedSkull(target: TileImage, cx: Float, cy: Float, rx: Float, ry: Float) {
        val skull = TileImage(target.width, target.height)
        val hf = Heightfield(target.width, target.height)
        hf.addDome(cx, cy - ry * 0.15f, rx, ry * 0.88f, 1.0f, 0.55f)       // Cranium
        hf.addDome(cx, cy + ry * 0.58f, rx * 0.65f, ry * 0.45f, 0.82f, 0.5f) // Jaw
        hf.shadeInto(skull, theme.bone, ambient = 0.58f, contrast = 0.65f, ditherStrength = 0.05f, mortarThreshold = 0.08f)
        val c = PixelCanvas(skull)
        val ix = cx.roundToInt()
        val iy = cy.roundToInt()
        // Eye sockets & nose cavity
        c.fillRect(ix - 3, iy, ix - 2, iy + 1, theme.ink)
        c.fillRect(ix + 1, iy, ix + 2, iy + 1, theme.ink)
        c.set(ix, iy + 2, theme.bone.colors[1])
        // Teeth ridge
        c.set(ix - 2, iy + 4, theme.bone.colors[1])
        c.set(ix, iy + 4, theme.bone.colors[1])
        c.set(ix + 2, iy + 4, theme.bone.colors[1])
        val outlined = PixelOutline.applyCrispOutline(skull, theme.ink, litEdgeBlend = 0.25f)
        target.drawImage(outlined, 0, 0, target.width, target.height, 0, 0)
    }

    fun generateCandleClusterTile(): TileImage {
        val out = TileImage(tileSize, tileSize)
        val c = PixelCanvas(out)
        c.drawContactShadow(16f, 25f, 8.5f, 3f, theme.shadowRgb, coreAlpha = 105, rimAlpha = 45)
        // Three small wax candles
        val sprite = TileImage(tileSize, tileSize)
        val sc = PixelCanvas(sprite)
        sc.fillBeveledRect(10, 17, 13, 24, theme.bone, baseTone = 0.65f)
        sc.fillBeveledRect(15, 14, 18, 25, theme.bone, baseTone = 0.72f)
        sc.fillBeveledRect(20, 19, 22, 24, theme.bone, baseTone = 0.58f)
        out.drawImage(PixelOutline.applyCrispOutline(sprite, theme.ink), 0, 0, tileSize, tileSize, 0, 0)
        // Glowing spectral flames on each wick
        c.drawFlame(11.5f, 16f, 2.2f, 4.5f, theme.flame, seed = seed + 1, withGlow = true)
        c.drawFlame(16.5f, 13f, 2.6f, 5.5f, theme.flame, seed = seed + 2, withGlow = true)
        c.drawFlame(21.0f, 18f, 2.0f, 4.0f, theme.flame, seed = seed + 3, withGlow = false)
        return out
    }

    private fun generateKeyAltarTile(): TileImage {
        val out = generateFloorTile(variant = 0)
        val sprite = TileImage(tileSize, tileSize)
        val c = PixelCanvas(sprite)
        // Carved stone dais with glowing cyan/slate key relief
        c.fillBeveledRect(4, 7, 27, 25, theme.wall, baseTone = 0.52f, topBoost = 0.22f, bottomDrop = 0.24f)
        c.fillBeveledRect(6, 9, 25, 22, theme.floor, baseTone = 0.44f)
        // Sculpted key emblem
        c.fillBeveledRect(9, 13, 14, 18, theme.flame, baseTone = 0.65f)
        c.fillRect(11, 15, 12, 16, theme.wall.colors[1])
        c.fillRect(15, 15, 23, 16, theme.flame.colors[4])
        c.fillRect(19, 17, 20, 19, theme.flame.colors[3])
        c.fillRect(22, 17, 23, 19, theme.flame.colors[3])
        out.drawImage(PixelOutline.applyCrispOutline(sprite, theme.ink), 0, 0, tileSize, tileSize, 0, 0)
        PixelCanvas(out).drawGlow(16f, 16f, 10f, theme.flame.colors[3], intensity = 0.30f)
        return out
    }

    private fun generateWallSkullMedallion(): TileImage {
        val out = generateCobbleWallFace(isLower = false, salt = 313L)
        val med = TileImage(tileSize, tileSize)
        val mc = PixelCanvas(med)
        mc.fillShadedEllipse(16f, 16f, 10f, 10f, theme.wall, baseTone = 0.55f)
        drawSculptedSkull(med, 16f, 16f, 6.2f, 5.8f)
        out.drawImage(PixelOutline.applyCrispOutline(med, theme.ink), 0, 0, tileSize, tileSize, 0, 0)
        return out
    }

    // =================================================================================================================
    // 5. Multi-Tile Centerpiece Props (Lich Statue, Effigy Sarcophagus, Knight Statue, Brazier, Open Tomb)
    // =================================================================================================================

    /**
     * Grand 2x3 (64x96) carved stone Lich / Sovereign Statue on a stepped plinth,
     * holding twin glowing spectral flames!
     */
    fun generateLichStatueProp(): TileImage {
        val w = 64
        val h = 96
        val sprite = TileImage(w, h)
        val hf = Heightfield(w, h)
        val cx = 32f

        // Stepped stone pedestal base (y = 74..90)
        hf.addBeveledBox(12, 80, 51, 90, peak = 0.85f, bevel = 2.5f)
        hf.addBeveledBox(16, 73, 47, 81, peak = 0.92f, bevel = 2.0f)

        // Flowing ribbed robes & lower body (y = 46..75)
        for (y in 46..75) {
            val v = (y - 46f) / 29f
            val halfW = 8.5f + v * 6.5f
            for (x in (cx - halfW).toInt()..(cx + halfW).toInt()) {
                val u = (x + 0.5f - cx) / halfW
                val cyl = kotlin.math.sqrt((1f - u * u).coerceAtLeast(0f))
                val fold = sin(u * 10f) * 0.09f
                hf.raiseMax(x, y, (0.32f + cyl * 0.62f + fold).coerceIn(0.1f, 1f))
            }
        }

        // Ribbed skeletal cuirass & draped collar mantle (y = 28..48)
        hf.addDome(cx, 38f, 11.5f, 10.5f, peak = 0.95f, power = 0.55f)
        // Flared wide pauldrons / bat-like shoulder mantle
        hf.addDome(cx - 12f, 34f, 8.5f, 5.2f, peak = 0.94f, power = 0.52f)
        hf.addDome(cx + 12f, 34f, 8.5f, 5.2f, peak = 0.94f, power = 0.52f)

        // Raised forearms holding twin flame chalices
        hf.addDome(cx - 17f, 40f, 3.6f, 6.0f, peak = 0.85f, power = 0.55f)
        hf.addDome(cx + 17f, 40f, 3.6f, 6.0f, peak = 0.85f, power = 0.55f)
        hf.addBeveledBox(11, 32, 18, 36, peak = 0.96f, bevel = 1.2f)
        hf.addBeveledBox(45, 32, 52, 36, peak = 0.96f, bevel = 1.2f)

        // Crowned Lich skull head (y = 11..29)
        hf.addDome(cx, 22f, 7.2f, 6.8f, peak = 1.0f, power = 0.52f)
        hf.addDome(cx, 27f, 4.6f, 3.2f, peak = 0.85f, power = 0.5f)
        // 3-spiked iron/stone crown
        hf.addBeveledBox(24, 14, 39, 18, peak = 1.02f, bevel = 1.2f)
        hf.addDome(32f, 11.5f, 2.6f, 4.2f, peak = 1.08f, power = 0.45f)
        hf.addDome(26f, 13.5f, 2.0f, 3.2f, peak = 1.0f, power = 0.5f)
        hf.addDome(38f, 13.5f, 2.0f, 3.2f, peak = 1.0f, power = 0.5f)

        hf.shadeInto(sprite, theme.statue, ambient = 0.48f, contrast = 0.72f, ditherStrength = 0.06f, mortarThreshold = 0.06f)

        val sc = PixelCanvas(sprite)
        // Dark hollow skull eye sockets & jaw details
        sc.fillRect(28, 22, 30, 24, theme.ink)
        sc.fillRect(33, 22, 35, 24, theme.ink)
        sc.set(29, 23, theme.flame.colors[4]) // Faint spectral glint in eyes
        sc.set(34, 23, theme.flame.colors[4])
        sc.set(31, 26, theme.ink)
        sc.set(32, 26, theme.ink)
        // Ribbed robe belt & chest brooch
        sc.fillBeveledRect(27, 45, 36, 48, theme.statue, baseTone = 0.68f)
        sc.fillRect(30, 46, 33, 47, theme.flame.colors[3])

        val out = TileImage(w, h)
        val oc = PixelCanvas(out)
        oc.drawContactShadow(cx, 90f, 22f, 5.5f, theme.shadowRgb, coreAlpha = 140, rimAlpha = 70)
        out.drawImage(PixelOutline.applyCrispOutline(sprite, theme.ink), 0, 0, w, h, 0, 0)

        // Luminous twin spectral flames in the lich's hands!
        oc.drawFlame(14.5f, 32f, 5.0f, 16f, theme.flame, seed = seed + 301, withGlow = true)
        oc.drawFlame(48.5f, 32f, 5.0f, 16f, theme.flame, seed = seed + 302, withGlow = true)
        return out
    }

    /**
     * Ornate 2x2 (64x64) carved stone sarcophagus with sculpted reclining knight effigy,
     * stepped plinth, front decorative panels, and tiny glowing candles.
     */
    fun generateSarcophagusEffigyProp(): TileImage {
        val w = 64
        val h = 64
        val sprite = TileImage(w, h)
        val hf = Heightfield(w, h)

        // Stepped tomb plinth (y = 38..56)
        hf.addBeveledBox(6, 42, 57, 56, peak = 0.72f, bevel = 2.0f)
        // Main sarcophagus body & front relief panels (y = 26..48)
        hf.addBeveledBox(9, 26, 54, 48, peak = 0.88f, bevel = 2.2f)
        // Recessed carved niches along front wall
        for (nx in listOf(14, 23, 32, 41)) {
            hf.carveLine(nx.toFloat(), 41f, (nx + 5).toFloat(), 41f, depth = 0.28f, radius = 1.6f)
            hf.carveLine(nx.toFloat(), 44f, (nx + 5).toFloat(), 44f, depth = 0.24f, radius = 1.4f)
        }

        // Top bevel lid (y = 14..36)
        hf.addBeveledBox(11, 14, 52, 36, peak = 0.96f, bevel = 2.5f)

        // Sculpted reclining effigy lying horizontally on the lid (head on left, feet on right)
        hf.addDome(20f, 25f, 5.2f, 5.4f, peak = 1.14f, power = 0.5f)   // Helmeted head
        hf.addDome(31f, 25f, 8.2f, 6.2f, peak = 1.12f, power = 0.55f)  // Breastplate & folded arms
        hf.addDome(43f, 25f, 6.8f, 4.6f, peak = 1.06f, power = 0.55f)  // Armored legs
        hf.addDome(49f, 25f, 2.6f, 4.2f, peak = 1.02f, power = 0.5f)   // Sabatons

        hf.shadeInto(sprite, theme.statue, ambient = 0.52f, contrast = 0.68f, ditherStrength = 0.05f, mortarThreshold = 0.05f)

        val sc = PixelCanvas(sprite)
        // Visor slit & sword line across effigy chest
        sc.drawLine(18, 22, 18, 28, theme.ink)
        sc.drawLine(25, 25, 45, 25, theme.statue.highlight)
        sc.drawLine(25, 26, 45, 26, theme.statue.at(1))
        // Lid overhang shadow line on front face
        sc.fillRect(10, 36, 53, 37, theme.ink)

        val out = TileImage(w, h)
        val oc = PixelCanvas(out)
        oc.drawContactShadow(32f, 56.5f, 27f, 5.2f, theme.shadowRgb, coreAlpha = 140, rimAlpha = 70)
        out.drawImage(PixelOutline.applyCrispOutline(sprite, theme.ink), 0, 0, w, h, 0, 0)

        // Small vigil candles flickering in front of the tomb
        oc.fillBeveledRect(24, 51, 26, 56, theme.bone, baseTone = 0.70f)
        oc.drawFlame(25f, 50f, 2.2f, 4.5f, theme.flame, seed = seed + 41, withGlow = true)
        oc.fillBeveledRect(45, 52, 47, 57, theme.bone, baseTone = 0.68f)
        oc.drawFlame(46f, 51f, 2.2f, 4.5f, theme.flame, seed = seed + 42, withGlow = true)
        return out
    }

    /**
     * 1x2 (32x64) sculpted slate-stone Guardian Knight Statue on a beveled pedestal.
     */
    fun generateKnightStatueProp(): TileImage {
        val w = 32
        val h = 64
        val sprite = TileImage(w, h)
        val hf = Heightfield(w, h)
        val cx = 16f

        // Pedestal (y = 44..58)
        hf.addBeveledBox(5, 48, 26, 58, peak = 0.86f, bevel = 2.0f)
        hf.addBeveledBox(7, 44, 24, 49, peak = 0.92f, bevel = 1.5f)
        // Greaves & armored legs (y = 33..45)
        hf.addDome(12.5f, 39f, 3.2f, 6.2f, peak = 0.88f, power = 0.55f)
        hf.addDome(19.5f, 39f, 3.2f, 6.2f, peak = 0.88f, power = 0.55f)
        // Cuirass & pauldrons (y = 20..35)
        hf.addDome(cx, 27f, 7.2f, 7.5f, peak = 0.98f, power = 0.55f)
        hf.addDome(9.5f, 24f, 4.0f, 4.0f, peak = 0.96f, power = 0.52f)
        hf.addDome(22.5f, 24f, 4.0f, 4.0f, peak = 0.96f, power = 0.52f)
        // Visored knight helm & plume (y = 9..21)
        hf.addDome(cx, 15.5f, 5.2f, 5.6f, peak = 1.04f, power = 0.5f)
        hf.addDome(cx, 10f, 2.2f, 3.0f, peak = 0.95f, power = 0.5f)

        hf.shadeInto(sprite, theme.statue, ambient = 0.50f, contrast = 0.72f, ditherStrength = 0.05f, mortarThreshold = 0.06f)

        val sc = PixelCanvas(sprite)
        // Helm visor T-slit
        sc.fillRect(13, 15, 18, 16, theme.ink)
        sc.fillRect(15, 16, 16, 18, theme.ink)
        // Vertical greatsword held down the center
        sc.fillRect(15, 25, 16, 44, theme.statue.highlight)
        sc.fillRect(16, 25, 16, 44, theme.statue.at(2))
        sc.fillRect(12, 27, 19, 28, theme.statue.at(4))

        val out = TileImage(w, h)
        PixelCanvas(out).drawContactShadow(cx, 58.5f, 11.5f, 3.6f, theme.shadowRgb, coreAlpha = 135, rimAlpha = 65)
        out.drawImage(PixelOutline.applyCrispOutline(sprite, theme.ink), 0, 0, w, h, 0, 0)
        return out
    }

    /**
     * 48x64 glowing spectral brazier / torch pillar with soft cyan radial bloom
     * (extra padding so the glow halo fades smoothly to zero with no rectangular clipping).
     */
    fun generateBrazierProp(): TileImage {
        val w = 48
        val h = 64
        val sprite = TileImage(w, h)
        val hf = Heightfield(w, h)
        val cx = 24f
        // Stone pedestal & iron bowl (y = 34..56)
        hf.addBeveledBox(17, 49, 30, 56, peak = 0.85f, bevel = 1.8f)
        hf.addBeveledBox(19, 40, 28, 50, peak = 0.82f, bevel = 1.5f)
        hf.addDome(cx, 38f, 8.2f, 4.2f, peak = 0.96f, power = 0.55f)
        hf.shadeInto(sprite, theme.statue, ambient = 0.46f, contrast = 0.68f, ditherStrength = 0.05f, mortarThreshold = 0.06f)
        val sc = PixelCanvas(sprite)
        sc.fillShadedEllipse(cx, 36f, 6.2f, 2.2f, theme.wall, baseTone = 0.10f)

        val out = TileImage(w, h)
        val oc = PixelCanvas(out)
        oc.drawContactShadow(cx, 56.5f, 9f, 3.2f, theme.shadowRgb, coreAlpha = 130, rimAlpha = 60)
        out.drawImage(PixelOutline.applyCrispOutline(sprite, theme.ink), 0, 0, w, h, 0, 0)
        oc.drawFlame(cx, 36f, 6.0f, 17f, theme.flame, seed = seed + 505, withGlow = true)
        return out
    }

    /**
     * 2x1 (64x32) open stone/wood coffin revealing a reclining skeleton.
     */
    fun generateOpenTombProp(): TileImage {
        val w = 64
        val h = 32
        val sprite = TileImage(w, h)
        val c = PixelCanvas(sprite)
        // Outer stone tomb rim
        c.fillBeveledRect(5, 5, 58, 26, theme.statue, baseTone = 0.55f, topBoost = 0.24f, bottomDrop = 0.24f)
        // Deep dark recessed interior
        c.fillRect(9, 9, 54, 22, theme.ink)
        c.fillRect(10, 10, 53, 21, theme.wall.colors[0])
        // Reclining skeleton inside (skull on left, ribs center, legs right)
        drawSculptedSkull(sprite, cx = 17f, cy = 15.5f, rx = 4.6f, ry = 4.2f)
        // Spine & ribcage
        c.drawLine(22, 15, 38, 15, theme.bone.colors[3])
        for (rx in listOf(25, 28, 31, 34)) {
            c.drawLine(rx, 11, rx, 19, theme.bone.colors[4])
            c.set(rx + 1, 12, theme.bone.colors[2])
        }
        // Pelvis & leg bones
        c.fillBeveledRect(37, 13, 40, 18, theme.bone, baseTone = 0.62f)
        c.drawLine(41, 13, 51, 12, theme.bone.colors[4])
        c.drawLine(41, 17, 51, 18, theme.bone.colors[3])

        val out = TileImage(w, h)
        PixelCanvas(out).drawContactShadow(32f, 27f, 27f, 4f, theme.shadowRgb, coreAlpha = 135, rimAlpha = 65)
        out.drawImage(PixelOutline.applyCrispOutline(sprite, theme.ink), 0, 0, w, h, 0, 0)
        return out
    }

    private fun generateHangingSkeletonProp(): TileImage {
        val w = 32
        val h = 48
        val sprite = TileImage(w, h)
        val c = PixelCanvas(sprite)
        // Iron chains from top
        for (y in 2..14 step 2) {
            c.set(10, y, theme.statue.colors[4])
            c.set(11, y + 1, theme.statue.colors[2])
            c.set(21, y, theme.statue.colors[4])
            c.set(20, y + 1, theme.statue.colors[2])
        }
        // Hanging skeleton torso, arms & legs
        c.drawLine(10, 14, 15, 22, theme.bone.colors[3])
        c.drawLine(21, 14, 17, 22, theme.bone.colors[3])
        c.fillRect(14, 21, 18, 29, theme.bone.colors[3])
        for (ry in listOf(22, 24, 26)) c.drawLine(13, ry, 19, ry, theme.bone.colors[4])
        c.drawLine(14, 30, 13, 40, theme.bone.colors[3])
        c.drawLine(18, 30, 19, 40, theme.bone.colors[3])
        drawSculptedSkull(sprite, 16f, 16f, 4.4f, 4.2f)
        return PixelOutline.applyCrispOutline(sprite, theme.ink)
    }

    // =================================================================================================================
    // 6. Assembled Dungeon Room Showcase Renderer (16x11 tiles = 512x352 px)
    // =================================================================================================================

    /**
     * Composes a complete 16x11 tile dungeon sanctum scene using only the generated tiles and props,
     * demonstrating how walls, floors, water moats, bridges, traps, statues, and spectral lighting compose.
     */
    fun renderShowcaseRoom(
        tiles: Map<String, TileImage>,
        props: Map<String, TileImage>
    ): TileImage {
        val cols = 16
        val rows = 11
        val scene = TileImage(cols * tileSize, rows * tileSize)
        val c = PixelCanvas(scene)

        fun tile(name: String): TileImage = tiles.getValue(name)
        fun put(tx: Int, ty: Int, img: TileImage) {
            scene.drawImage(img, 0, 0, img.width, img.height, tx * tileSize, ty * tileSize)
        }
        fun putPx(px: Int, py: Int, img: TileImage) {
            scene.drawImage(img, 0, 0, img.width, img.height, px, py)
        }

        // 1. Base floor fill across the room
        for (ty in 0 until rows) {
            for (tx in 0 until cols) {
                val h = TileNoise.hash(tx, ty, seed + 888L)
                val fName = when {
                    h < 0.55f -> "floor_clean"
                    h < 0.78f -> "floor_worn"
                    h < 0.92f -> "floor_cracked"
                    else -> "floor_double"
                }
                put(tx, ty, tile(fName))
            }
        }

        // 2. Top 2.5D Cobble Wall (rows 0..2)
        for (tx in 0 until cols) {
            put(tx, 0, tile("wall_cap_bottom"))
            put(tx, 1, tile("wall_face_upper"))
            put(tx, 2, tile("wall_face_lower"))
        }
        // Center alcove & gargoyle reliefs
        put(7, 0, tile("wall_skull_medallion"))
        put(8, 0, tile("wall_cap_bottom"))
        put(3, 2, tile("wall_face_gargoyle"))
        put(12, 2, tile("wall_face_gargoyle"))
        put(5, 1, tile("wall_pillar"))
        put(5, 2, tile("wall_pillar"))
        put(10, 1, tile("wall_pillar"))
        put(10, 2, tile("wall_pillar"))

        // Side framing walls
        for (ty in 0..3) {
            put(0, ty, tile("wall_cap_void"))
            put(15, ty, tile("wall_cap_void"))
        }

        // 3. Wall-cast ambient occlusion onto floor row 3
        for (tx in 1..14) {
            put(tx, 3, tile("floor_ao_top"))
        }

        // 4. Water Moat around central sanctum (left/right channels + horizontal moat at row 6)
        for (ty in 3..5) {
            put(1, ty, tile("water_deep_ao"))
            put(2, ty, tile("ledge_west"))
            put(13, ty, tile("ledge_east"))
            put(14, ty, tile("water_deep_ao"))
        }
        // Horizontal moat across row 6 with overhanging ledges & bridges
        for (tx in 1..14) {
            when (tx) {
                5, 10 -> put(tx, 6, tile("bridge_v"))
                else -> put(tx, 6, tile("ledge_south"))
            }
        }
        // Sewer grates embedded in the ledge wall flanking the bridges
        put(4, 6, tile("iron_grate"))
        put(11, 6, tile("iron_grate"))
        // Side bridges on row 4
        put(1, 4, tile("bridge_h"))
        put(14, 4, tile("bridge_h"))

        // 5. Floor-spike trap corridor on row 7 & seamless grand staircase on row 9
        for (tx in 1..14) {
            put(tx, 7, tile(if (tx % 2 == 0) "spikes_extended" else "spikes_retracted"))
        }
        put(6, 9, tile("stairs_left"))
        put(7, 9, tile("stairs_down"))
        put(8, 9, tile("stairs_down"))
        put(9, 9, tile("stairs_right"))
        for (tx in 6..9) {
            put(tx, 10, tile("floor_clean"))
        }
        // Lower-right vault nook with key altar
        put(12, 8, tile("wall_cap_bottom"))
        put(13, 8, tile("wall_cap_bottom"))
        put(14, 8, tile("wall_cap_bottom"))
        put(12, 9, tile("wall_face_lower"))
        put(13, 9, tile("wall_face_arch"))
        put(14, 9, tile("wall_face_lower"))
        put(13, 10, tile("key_altar"))

        // 6. Sculpted Multi-Tile Centerpieces & Dungeon Props
        // Hanging skeletons on back wall
        props["hanging_skeleton"]?.let {
            putPx(4 * tileSize + 8, 2 * tileSize - 10, it)
            putPx(10 * tileSize + 24, 2 * tileSize - 10, it)
        }
        // Grand Lich Statue centered behind the altar
        props["lich_statue"]?.let { putPx(7 * tileSize, 1 * tileSize + 2, it) }
        // Carved Effigy Sarcophagus in front of the Lich Statue
        props["sarcophagus_effigy"]?.let { putPx(7 * tileSize, 3 * tileSize + 14, it) }

        // Flanking Knight Guardian Statues
        props["knight_statue"]?.let {
            putPx(4 * tileSize - 4, 2 * tileSize + 12, it)
            putPx(11 * tileSize + 4, 2 * tileSize + 12, it)
        }

        // Open & closed treasure chests + gold mounds
        put(5, 3, tile("chest_open"))
        put(10, 3, tile("chest_open"))
        put(5, 4, tile("chest_open"))
        put(0, 1, tile("chest_closed"))

        putPx(6 * tileSize - 4, 3 * tileSize + 2, tile("gold_pile"))
        putPx(9 * tileSize + 6, 3 * tileSize + 2, tile("gold_pile"))
        putPx(6 * tileSize - 6, 4 * tileSize + 8, tile("gold_pile"))
        putPx(10 * tileSize - 4, 4 * tileSize + 6, tile("gold_pile"))

        // Skulls & open skeleton tomb
        putPx(4 * tileSize + 14, 4 * tileSize - 4, tile("skull_pile"))
        putPx(11 * tileSize + 6, 4 * tileSize - 2, tile("skull_single"))
        props["open_tomb_skeleton"]?.let { putPx(11 * tileSize + 2, 4 * tileSize + 16, it) }
        putPx(1 * tileSize + 4, 8 * tileSize + 14, tile("skull_single"))
        props["open_tomb_skeleton"]?.let { putPx(1 * tileSize + 2, 9 * tileSize + 16, it) }

        // Glowing Spectral Braziers & Candle Clusters (48x64 centered offset)
        props["spectral_brazier"]?.let {
            putPx(3 * tileSize - 14, 4 * tileSize - 10, it)
            putPx(12 * tileSize, 4 * tileSize - 10, it)
            putPx(4 * tileSize - 4, 8 * tileSize - 2, it)
            putPx(10 * tileSize + 12, 8 * tileSize - 2, it)
        }
        putPx(1 * tileSize + 16, 7 * tileSize + 14, tile("candle_cluster"))

        // Subtle atmospheric vignette darkening on far corners
        c.drawDirectionalAO(0, 0, cols * tileSize - 1, 14, EdgeSide.TOP, 55, theme.shadowRgb)

        return scene
    }
}
