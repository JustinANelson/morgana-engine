package com.morgana.tilegen.autotile

import com.morgana.tilegen.ProceduralTilesetGenerator
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.MaterialPattern
import com.morgana.tilegen.material.MaterialSpec
import com.morgana.tilegen.material.MaterialTileGenerator
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PixelDither
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.material.TileNoise
import kotlin.math.hypot
import kotlin.math.round

/**
 * Built-in terrain/material transition pairs for autotile sheet generation.
 */
enum class TerrainPair {
    GRASS_ON_DIRT,
    SAND_ON_WATER,
    GRASS_ON_WATER,
    COBBLE_ON_GRASS,
    SNOW_ON_GRASS,
    CLIFF_ON_DIRT,
    DUNGEON_WALL_ON_FLOOR
}

/**
 * How the border between foreground and background terrain is finished.
 *
 * [SOFT] is the original look (a wobbly cut with an optional one-pixel rim). [OUTLINED] and [LUSH] aim at the hand-pixelled
 * look of commercial terrain sheets: a hard-edged silhouette, a dark outline taken from the fill itself, a lit
 * inner band on the upper-left edges and a shaded one on the lower-right ones, and a translucent shadow on the
 * background. [LUSH] also breaks the silhouette into grass-like tufts that jut over the edge.
 */
enum class AutotileEdge { SOFT, OUTLINED, LUSH }

/**
 * Visual parameters controlling how a foreground terrain transitions onto a background terrain.
 *
 * @property margin Inset in pixels from an open (non-connected) edge to the transition boundary.
 * @property rimWidth Width in pixels of the transition border/rim outside the foreground boundary.
 * @property wobbleAmplitude Peak-to-peak amplitude (in pixels) of periodic edge perturbation.
 * @property rimColor Optional packed RGBA8888 color for the transition rim; `null` disables the rim. For the
 *   [AutotileEdge.OUTLINED] and [AutotileEdge.LUSH] finishes it replaces the derived outline colour.
 * @property edge Border finish, see [AutotileEdge].
 * @property shadow Strength `0..1` of the shadow the foreground casts on the background (finished edges only).
 */
data class AutotileStyle(
    val margin: Float = 5f,
    val rimWidth: Float = 2f,
    val wobbleAmplitude: Float = 3.5f,
    val rimColor: Int? = null,
    val pixelStyle: PixelStyle = PixelStyle.STANDARD,
    val edge: AutotileEdge = AutotileEdge.SOFT,
    val shadow: Float = 0.25f
) {
    init {
        require(shadow in 0f..1f) { "shadow must be in 0..1, got $shadow" }
        require(margin >= 0f) { "margin must be non-negative, got $margin" }
        require(rimWidth >= 0f) { "rimWidth must be non-negative, got $rimWidth" }
        require(wobbleAmplitude >= 0f) { "wobbleAmplitude must be non-negative, got $wobbleAmplitude" }
    }
}

/**
 * Metadata for a single tile inside an [AutotileSheet].
 */
data class AutotileEntry(
    val index: Int,
    val mask: Int,
    val col: Int,
    val row: Int,
    val name: String
)

/**
 * A generated autotile sprite sheet together with its bitmask-to-grid mapping.
 */
data class AutotileSheet(
    val image: TileImage,
    val mode: AutotileMode,
    val tileSize: Int,
    val columns: Int,
    val rows: Int,
    val entries: List<AutotileEntry>
) {
    /**
     * Looks up the [AutotileEntry] matching a raw neighbor [rawMask].
     */
    fun entryForMask(rawMask: Int): AutotileEntry {
        val canonical = when (mode) {
            AutotileMode.BLOB_47 -> AutotileBitmask.canonicalBlobMask(rawMask)
            AutotileMode.CARDINAL_16 -> rawMask and 0xF
        }
        return entries.first { it.mask == canonical }
    }

    /**
     * Serializes the sheet layout and bitmask mapping to deterministic JSON for game engines.
     */
    fun toMetadataJson(): String = buildString {
        append("{\n")
        append("  \"mode\": \"${mode.name}\",\n")
        append("  \"tileSize\": $tileSize,\n")
        append("  \"columns\": $columns,\n")
        append("  \"rows\": $rows,\n")
        append("  \"width\": ${image.width},\n")
        append("  \"height\": ${image.height},\n")
        append("  \"tiles\": [\n")
        entries.forEachIndexed { idx, e ->
            val comma = if (idx < entries.lastIndex) "," else ""
            append("    {\"index\": ${e.index}, \"mask\": ${e.mask}, \"col\": ${e.col}, \"row\": ${e.row}, \"x\": ${e.col * tileSize}, \"y\": ${e.row * tileSize}, \"name\": \"${e.name}\"}$comma\n")
        }
        append("  ]\n")
        append("}")
    }
}

/**
 * Deterministic generator for seamless 47-tile blob and 16-tile cardinal autotile sheets.
 *
 * Edge perturbation uses periodic [TileNoise.wrappedFbm] with period equal to
 * [tileSize], guaranteeing pixel-exact continuity across shared tile boundaries at any
 * supported tile resolution (e.g. 16x16, 32x32, 64x64).
 */
class AutotileGenerator(
    val seed: Long = 1337L,
    val tileSize: Int = 32
) {
    init {
        require(tileSize >= 8 && tileSize % 2 == 0) {
            "tileSize must be an even integer >= 8, got $tileSize"
        }
    }

    private val base by lazy { ProceduralTilesetGenerator(seed) }
    private val materialGen by lazy { MaterialTileGenerator(seed, tileSize) }

    private fun rgba(r: Int, g: Int, b: Int, a: Int = 255): Int =
        ((r and 0xFF) shl 24) or ((g and 0xFF) shl 16) or ((b and 0xFF) shl 8) or (a and 0xFF)

    /**
     * Generates a complete autotile sheet for a built-in [TerrainPair].
     */
    fun generateTerrainAutotile(
        pair: TerrainPair,
        mode: AutotileMode = AutotileMode.BLOB_47,
        columns: Int = defaultColumns(mode)
    ): AutotileSheet {
        val (fg, bg, style) = buildTerrainPair(pair)
        return generateAutotile(fg, bg, mode, style, columns)
    }

    /**
     * Generates a complete autotile sheet blending [foreground] over [background] using [style].
     */
    fun generateAutotile(
        foreground: TileImage,
        background: TileImage,
        mode: AutotileMode = AutotileMode.BLOB_47,
        style: AutotileStyle = AutotileStyle(),
        columns: Int = defaultColumns(mode)
    ): AutotileSheet {
        require(foreground.width == tileSize && foreground.height == tileSize) {
            "foreground must be ${tileSize}x$tileSize, got ${foreground.width}x${foreground.height}"
        }
        require(background.width == tileSize && background.height == tileSize) {
            "background must be ${tileSize}x$tileSize, got ${background.width}x${background.height}"
        }
        require(columns > 0) { "columns must be positive, got $columns" }

        val masks = when (mode) {
            AutotileMode.BLOB_47 -> AutotileBitmask.BLOB_47_MASKS
            AutotileMode.CARDINAL_16 -> AutotileBitmask.CARDINAL_16_MASKS
        }
        val rows = ((masks.size + columns - 1) / columns).coerceAtLeast(1)
        val sheet = TileImage(columns * tileSize, rows * tileSize)
        val entries = ArrayList<AutotileEntry>(masks.size)

        masks.forEachIndexed { index, mask ->
            val col = index % columns
            val row = index / columns
            val tile = renderAutotileTile(foreground, background, mask, mode, style)
            sheet.drawImage(tile, 0, 0, tileSize, tileSize, col * tileSize, row * tileSize)
            entries += AutotileEntry(
                index = index,
                mask = mask,
                col = col,
                row = row,
                name = AutotileBitmask.describeMask(mask, mode)
            )
        }

        return AutotileSheet(
            image = sheet,
            mode = mode,
            tileSize = tileSize,
            columns = columns,
            rows = rows,
            entries = entries
        )
    }

    /**
     * Renders a single [tileSize]x[tileSize] autotile for [mask] under [mode].
     */
    fun renderAutotileTile(
        foreground: TileImage,
        background: TileImage,
        mask: Int,
        mode: AutotileMode,
        style: AutotileStyle = AutotileStyle()
    ): TileImage {
        val out = background.copy()
        val (n, ne, e, se, s, sw, w, nw) = unpackNeighbors(mask, mode)
        val sizeF = tileSize.toFloat()
        val finished = style.edge != AutotileEdge.SOFT

        // Foreground coverage, computed once so a finished edge can look at neighbouring pixels.
        val inside = BooleanArray(tileSize * tileSize)
        val effective = FloatArray(tileSize * tileSize)
        for (py in 0 until tileSize) {
            val y = py + 0.5f
            for (px in 0 until tileSize) {
                val x = px + 0.5f
                val dist = distanceToEmpty(x, y, sizeF, n, ne, e, se, s, sw, w, nw)
                val rawWobble = (TileNoise.wrappedFbm(px.toFloat(), py.toFloat(), period = tileSize, octaves = 2, seed = seed + 4100L) - 0.5f) *
                    style.wobbleAmplitude
                var wobble = when (style.pixelStyle) {
                    PixelStyle.RETRO_8BIT -> round(rawWobble)
                    PixelStyle.CLASSIC_RPG, PixelStyle.JRPG_32BIT -> rawWobble + PixelDither.threshold(px, py, 4) * 0.75f
                    PixelStyle.PAINTED_25D, PixelStyle.MORGANA, PixelStyle.STANDARD -> rawWobble
                }
                if (style.edge == AutotileEdge.LUSH) wobble += tuft(px, py)
                effective[py * tileSize + px] = dist + wobble
                inside[py * tileSize + px] = dist + wobble >= style.margin
            }
        }
        fun covered(px: Int, py: Int): Boolean =
            inside[py.coerceIn(0, tileSize - 1) * tileSize + px.coerceIn(0, tileSize - 1)]

        if (!finished) {
            for (py in 0 until tileSize) {
                for (px in 0 until tileSize) {
                    val eff = effective[py * tileSize + px]
                    if (eff >= style.margin) {
                        out.drawPixel(px, py, foreground.getPixel(px, py))
                    } else if (style.rimColor != null && eff >= style.margin - style.rimWidth) {
                        out.drawPixel(px, py, style.rimColor)
                    }
                }
            }
            return out
        }

        val lush = style.edge == AutotileEdge.LUSH
        if (style.shadow > 0f) {
            for (py in 0 until tileSize) {
                for (px in 0 until tileSize) {
                    if (covered(px, py)) continue
                    // Shadow falls down and to the right of the raised terrain (light from the upper left).
                    if (covered(px - 1, py - 1) || covered(px - 2, py - 1) || covered(px - 1, py - 2)) {
                        out.setPixel(px, py, shade(out.getPixel(px, py), 1f - style.shadow))
                    }
                }
            }
        }
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                if (!covered(px, py)) continue
                val color = foreground.getPixel(px, py)
                val ring1 = !covered(px, py - 1) || !covered(px - 1, py) || !covered(px, py + 1) || !covered(px + 1, py)
                val lit = !covered(px, py - 2) || !covered(px - 2, py) || !covered(px - 1, py - 1)
                val dark = !covered(px, py + 2) || !covered(px + 2, py) || !covered(px + 1, py + 1)
                out.setPixel(
                    px, py,
                    when {
                        ring1 -> style.rimColor ?: shade(color, if (lush) 0.62f else 0.5f)
                        lit && !dark -> shade(color, if (lush) 1.28f else 1.18f)
                        dark && !lit -> shade(color, 0.82f)
                        else -> color
                    }
                )
            }
        }
        return out
    }

    /** Pixel-pair offset (in pixels of edge distance) that turns a smooth edge into jutting blades and notches. */
    private fun tuft(px: Int, py: Int): Float {
        val h = TileNoise.hash(px / 2, py / 2, seed + 7700L)
        return when {
            h > 0.66f -> 1.7f
            h < 0.22f -> -1.3f
            else -> 0f
        }
    }

    /** Scales the colour towards black ([factor] < 1, cooled) or white ([factor] > 1, warmed), keeping alpha. */
    private fun shade(rgba: Int, factor: Float): Int {
        val a = rgba and 0xFF
        if (a == 0) return rgba
        val r = (rgba ushr 24) and 0xFF
        val g = (rgba ushr 16) and 0xFF
        val b = (rgba ushr 8) and 0xFF
        val warm = if (factor > 1f) 1.05f else 0.94f
        val cool = if (factor > 1f) 0.93f else 1.06f
        return rgba(
            (r * factor * warm).toInt().coerceIn(0, 255),
            (g * factor).toInt().coerceIn(0, 255),
            (b * factor * cool).toInt().coerceIn(0, 255),
            a
        )
    }

    private fun distanceToEmpty(
        x: Float,
        y: Float,
        size: Float,
        n: Boolean,
        ne: Boolean,
        e: Boolean,
        se: Boolean,
        s: Boolean,
        sw: Boolean,
        w: Boolean,
        nw: Boolean
    ): Float {
        var minDist = size
        if (!n) minDist = minOf(minDist, y)
        if (!s) minDist = minOf(minDist, size - y)
        if (!w) minDist = minOf(minDist, x)
        if (!e) minDist = minOf(minDist, size - x)
        if (n && e && !ne) minDist = minOf(minDist, hypot(size - x, y))
        if (e && s && !se) minDist = minOf(minDist, hypot(size - x, size - y))
        if (s && w && !sw) minDist = minOf(minDist, hypot(x, size - y))
        if (w && n && !nw) minDist = minOf(minDist, hypot(x, y))
        return minDist
    }

    private data class Neighbors(
        val n: Boolean,
        val ne: Boolean,
        val e: Boolean,
        val se: Boolean,
        val s: Boolean,
        val sw: Boolean,
        val w: Boolean,
        val nw: Boolean
    )

    private fun unpackNeighbors(mask: Int, mode: AutotileMode): Neighbors = when (mode) {
        AutotileMode.BLOB_47 -> {
            val m = AutotileBitmask.canonicalBlobMask(mask)
            Neighbors(
                n = (m and AutotileBitmask.N) != 0,
                ne = (m and AutotileBitmask.NE) != 0,
                e = (m and AutotileBitmask.E) != 0,
                se = (m and AutotileBitmask.SE) != 0,
                s = (m and AutotileBitmask.S) != 0,
                sw = (m and AutotileBitmask.SW) != 0,
                w = (m and AutotileBitmask.W) != 0,
                nw = (m and AutotileBitmask.NW) != 0
            )
        }
        AutotileMode.CARDINAL_16 -> {
            val m = mask and 0xF
            val n = (m and AutotileBitmask.CARDINAL_N) != 0
            val e = (m and AutotileBitmask.CARDINAL_E) != 0
            val s = (m and AutotileBitmask.CARDINAL_S) != 0
            val w = (m and AutotileBitmask.CARDINAL_W) != 0
            Neighbors(
                n = n,
                ne = n && e,
                e = e,
                se = e && s,
                s = s,
                sw = s && w,
                w = w,
                nw = w && n
            )
        }
    }

    private fun buildTerrainPair(pair: TerrainPair): Triple<TileImage, TileImage, AutotileStyle> {
        if (tileSize != 32) {
            return buildScaledTerrainPair(pair)
        }
        val fg = TileImage(tileSize, tileSize)
        val bg = TileImage(tileSize, tileSize)
        return when (pair) {
            TerrainPair.GRASS_ON_DIRT -> {
                base.drawGrassTile(fg, 0, 0)
                base.drawDirtTile(bg, 0, 0, withPebbles = true)
                Triple(fg, bg, AutotileStyle(margin = 5f, rimWidth = 2f, wobbleAmplitude = 4f, rimColor = base.dirtDeep))
            }
            TerrainPair.SAND_ON_WATER -> {
                base.drawSandTile(fg, 0, 0)
                base.drawWaterTile(bg, 0, 0, frameIndex = 0)
                Triple(fg, bg, AutotileStyle(margin = 5.5f, rimWidth = 2.5f, wobbleAmplitude = 3.5f, rimColor = base.waterFoam))
            }
            TerrainPair.GRASS_ON_WATER -> {
                val grass = materialGen.generateTile(MaterialSpec("grass", MaterialPattern.ORGANIC_FBM, PalettePresets.CLASSIC_RPG_GRASS, style = PixelStyle.CLASSIC_RPG))
                val water = materialGen.generateTile(MaterialSpec("water", MaterialPattern.WATER_CAUSTICS, PalettePresets.CALM_WATER, style = PixelStyle.CLASSIC_RPG))
                Triple(grass, water, AutotileStyle(margin = 5.5f, rimWidth = 2.5f, wobbleAmplitude = 3.5f, rimColor = PalettePresets.DESERT_SAND.mid))
            }
            TerrainPair.COBBLE_ON_GRASS -> {
                base.drawCobbleRoadTile(fg, 0, 0)
                base.drawGrassTile(bg, 0, 0)
                Triple(fg, bg, AutotileStyle(margin = 4.5f, rimWidth = 1.5f, wobbleAmplitude = 2f, rimColor = base.mortarCrevice))
            }
            TerrainPair.SNOW_ON_GRASS -> {
                base.drawSnowTile(fg, 0, 0)
                base.drawGrassTile(bg, 0, 0)
                Triple(fg, bg, AutotileStyle(margin = 5f, rimWidth = 2f, wobbleAmplitude = 4f, rimColor = base.snowShadow))
            }
            TerrainPair.CLIFF_ON_DIRT -> {
                base.drawCliffWallTile(fg, 0, 0, isTop = true)
                base.drawDirtTile(bg, 0, 0, withPebbles = false)
                Triple(fg, bg, AutotileStyle(margin = 4f, rimWidth = 2.5f, wobbleAmplitude = 1.5f, rimColor = rgba(28, 25, 23)))
            }
            TerrainPair.DUNGEON_WALL_ON_FLOOR -> {
                base.drawDungeonWallTile(fg, 0, 0)
                base.drawCryptFloorTile(bg, 0, 0)
                Triple(fg, bg, AutotileStyle(margin = 4f, rimWidth = 2f, wobbleAmplitude = 1f, rimColor = rgba(15, 23, 42)))
            }
        }
    }

    private fun buildScaledTerrainPair(pair: TerrainPair): Triple<TileImage, TileImage, AutotileStyle> {
        val scale = tileSize / 32f
        return when (pair) {
            TerrainPair.GRASS_ON_DIRT -> Triple(
                materialGen.generateTile(MaterialSpec("grass", MaterialPattern.ORGANIC_FBM, PalettePresets.MEADOW_GRASS)),
                materialGen.generateTile(MaterialSpec("dirt", MaterialPattern.ORGANIC_FBM, PalettePresets.EARTH_DIRT)),
                AutotileStyle(margin = 5f * scale, rimWidth = 2f * scale, wobbleAmplitude = 4f * scale, rimColor = PalettePresets.EARTH_DIRT.shadow)
            )
            TerrainPair.SAND_ON_WATER -> Triple(
                materialGen.generateTile(MaterialSpec("sand", MaterialPattern.ORGANIC_FBM, PalettePresets.DESERT_SAND)),
                materialGen.generateTile(MaterialSpec("water", MaterialPattern.WATER_CAUSTICS, PalettePresets.OCEAN_WATER)),
                AutotileStyle(margin = 5.5f * scale, rimWidth = 2.5f * scale, wobbleAmplitude = 3.5f * scale, rimColor = PalettePresets.OCEAN_WATER.highlight)
            )
            TerrainPair.GRASS_ON_WATER -> Triple(
                materialGen.generateTile(MaterialSpec("grass", MaterialPattern.ORGANIC_FBM, PalettePresets.CLASSIC_RPG_GRASS, style = PixelStyle.CLASSIC_RPG)),
                materialGen.generateTile(MaterialSpec("water", MaterialPattern.WATER_CAUSTICS, PalettePresets.CALM_WATER, style = PixelStyle.CLASSIC_RPG)),
                AutotileStyle(margin = 5.5f * scale, rimWidth = 2.5f * scale, wobbleAmplitude = 3.5f * scale, rimColor = PalettePresets.DESERT_SAND.mid)
            )
            TerrainPair.COBBLE_ON_GRASS -> Triple(
                materialGen.generateTile(MaterialSpec("cobble", MaterialPattern.COBBLESTONE, PalettePresets.SLATE_STONE)),
                materialGen.generateTile(MaterialSpec("grass", MaterialPattern.ORGANIC_FBM, PalettePresets.MEADOW_GRASS)),
                AutotileStyle(margin = 4.5f * scale, rimWidth = 1.5f * scale, wobbleAmplitude = 2f * scale, rimColor = PalettePresets.SLATE_STONE.shadow)
            )
            TerrainPair.SNOW_ON_GRASS -> Triple(
                materialGen.generateTile(MaterialSpec("snow", MaterialPattern.ORGANIC_FBM, PalettePresets.FROST_SNOW)),
                materialGen.generateTile(MaterialSpec("grass", MaterialPattern.ORGANIC_FBM, PalettePresets.MEADOW_GRASS)),
                AutotileStyle(margin = 5f * scale, rimWidth = 2f * scale, wobbleAmplitude = 4f * scale, rimColor = PalettePresets.FROST_SNOW.shadow)
            )
            TerrainPair.CLIFF_ON_DIRT -> Triple(
                materialGen.generateTile(MaterialSpec("cliff", MaterialPattern.COBBLESTONE, PalettePresets.SLATE_STONE)),
                materialGen.generateTile(MaterialSpec("dirt", MaterialPattern.ORGANIC_FBM, PalettePresets.EARTH_DIRT)),
                AutotileStyle(margin = 4f * scale, rimWidth = 2.5f * scale, wobbleAmplitude = 1.5f * scale, rimColor = rgba(28, 25, 23))
            )
            TerrainPair.DUNGEON_WALL_ON_FLOOR -> Triple(
                materialGen.generateTile(MaterialSpec("wall", MaterialPattern.BRICK_MASONRY, PalettePresets.SLATE_STONE)),
                materialGen.generateTile(MaterialSpec("floor", MaterialPattern.COBBLESTONE, PalettePresets.SLATE_STONE)),
                AutotileStyle(margin = 4f * scale, rimWidth = 2f * scale, wobbleAmplitude = 1f * scale, rimColor = rgba(15, 23, 42))
            )
        }
    }

    companion object {
        fun defaultColumns(mode: AutotileMode): Int = when (mode) {
            AutotileMode.BLOB_47 -> 8
            AutotileMode.CARDINAL_16 -> 4
        }
    }
}
