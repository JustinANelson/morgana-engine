package com.morgana.tilegen.material

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Procedural material patterns supported by [MaterialTileGenerator].
 */
enum class MaterialPattern {
    /** Multi-octave periodic fBm with optional organic speckles/tufts (grass, dirt, sand, snow, moss). */
    ORGANIC_FBM,

    /** Periodic Voronoi cobblestones with mortar crevices, top-left bevels, and per-stone variation. */
    COBBLESTONE,

    /** Running-bond staggered masonry bricks with mortar seams and subtle weathering. */
    BRICK_MASONRY,

    /** Horizontal wood planks with grain streaks, plank seams, and staggered vertical joints. */
    WOOD_PLANKS,

    /** Animated-ready Voronoi ridge caustics with periodic drift (water, lava, toxic slime). */
    WATER_CAUSTICS,

    /** Brushed metal hull plating with seam grooves and corner rivets. */
    METAL_PLATING,

    /** 45-degree diagonal staggered stone pavers with soft mortar and cel-shaded bevels. */
    DIAGONAL_PAVER,

    /** Curved Oriental glazed clay roof tiles with bamboo/gold ridge caps. */
    GLAZED_ROOF_TILE,

    /** Imperial carved jade/marble courtyard flagstones with inlaid octagonal border filigree. */
    JADE_COURTYARD,

    /** Heavy medieval European herringbone masonry cobbles with deep bevels and moss crevices. */
    HERRINGBONE_COBBLE,

    /** Dark jagged obsidian and dark rift stone with glowing abyssal fissure veins. */
    OBSIDIAN_CRAG,

    /** Overlapping scalloped leaf-clump clusters with sunlit top-left crowns and deep under-clump AO. */
    LEAF_CANOPY_CLUSTER,

    /** Cream plaster wall panels framed by weathered horizontal, vertical, and diagonal cross-braced timber beams. */
    HALF_TIMBER_PLASTER,

    /** Overlapping scalloped fish-scale/terracotta roof shingles with eave bevels and cast drop shadows. */
    SHINGLE_THATCH_ROOF,

    /** Grand 2.5D beveled sanctuary flagstones with diamond inlays and corner rune medallions. */
    SANCTUARY_COURTYARD,

    /** Cracked wet urban asphalt with fine aggregate speckle and optional lane markings. */
    ASPHALT_ROAD,

    /** Industrial steel floor grating with diamond-plate ribs and diagonal yellow-black hazard trim. */
    HAZARD_GRATE,

    /** Illuminated cybernetic circuit-board mainframe floor with glowing trace lines and node vias. */
    CYBER_CIRCUIT,

    /**
     * Hand-pixelled flat ground in the style of commercial terrain sheets: a nearly flat mid tone with a few big,
     * posterised light and dark patches, and short lit blades (grass) or chips (dirt) scattered on top. Far calmer
     * than [ORGANIC_FBM]; `detail` sets how many tufts, `accentColor` adds sparse flowers or pebbles.
     */
    TUFTED_GROUND,

    /**
     * Rounded, individually outlined boulders packed edge to edge (cliff faces, rubble banks): each stone has a
     * dark gap around it, a lit upper-left shoulder and a shaded lower-right underside, with a per-stone tint.
     * `scale` sets the stones per tile (about 3 per tile at 1.0).
     */
    ROUNDED_STONES,

    /**
     * Flat, hand-pixelled water: a calm posterised base with a few deeper patches and short light streaks that
     * drift sideways with `frameIndex` (four frames loop seamlessly). Calmer than [WATER_CAUSTICS].
     */
    FLAT_WATER,

    /**
     * Smooth paving in the same hand-pixelled style: flat, outlined flagstones laid in running bond, each with a lit
     * upper-left edge, a shaded lower-right edge and its own tint. `scale` sets the stones per tile (about 3 at 1.0).
     */
    FLAT_PAVER,

    /**
     * Smooth wooden planks: flat horizontal boards with a dark seam, a lit top edge, per-board tint, staggered butt
     * joints and a few grain flecks. `scale` sets the boards per tile (about 4 at 1.0).
     */
    FLAT_PLANKS,

    /** Coursed grey ashlar blocks: dark mortar, lit top-left edges, per-block tint and weathering specks (castle walls, flagstones). */
    ASHLAR,

    /** Dark tilled soil in horizontal furrows with lit ridges and clods (fields). */
    FURROWED_SOIL
}

/**
 * Data-driven recipe for synthesizing a seamless tile of any supported [MaterialPattern].
 */
data class MaterialSpec(
    val name: String,
    val pattern: MaterialPattern,
    val palette: PaletteRamp,
    val accentColor: Int? = null,
    val scale: Float = 1f,
    val detail: Float = 0.5f,
    val frameIndex: Int = 0,
    val style: PixelStyle = PixelStyle.STANDARD
) {
    init {
        require(name.isNotBlank()) { "Material name must not be blank" }
        require(scale > 0f) { "scale must be positive, got $scale" }
        require(detail in 0f..1f) { "detail must be in 0..1, got $detail" }
    }
}

/**
 * Resolution-independent procedural tile generator supporting arbitrary even tile sizes
 * (e.g. 16x16, 32x32, 64x64), custom [PaletteRamp]s, and animated tile frames.
 */
class MaterialTileGenerator(
    val seed: Long = 1337L,
    val tileSize: Int = 32
) {
    init {
        require(tileSize >= 8 && tileSize % 2 == 0) {
            "tileSize must be an even integer >= 8, got $tileSize"
        }
    }

    /**
     * Generates a single seamless [tileSize]x[tileSize] tile from [spec].
     */
    fun generateTile(spec: MaterialSpec): TileImage {
        val out = TileImage(tileSize, tileSize)
        val s = seed + spec.name.hashCode().toLong() * 31L
        val effectiveSpec = if (spec.style == PixelStyle.RETRO_8BIT) {
            spec.copy(palette = spec.palette.limitColors(4), accentColor = spec.accentColor?.let { spec.palette.limitColors(4).quantize(it) })
        } else {
            spec
        }
        when (effectiveSpec.pattern) {
            MaterialPattern.ORGANIC_FBM -> drawOrganicFbm(out, effectiveSpec, s)
            MaterialPattern.COBBLESTONE -> drawCobblestone(out, effectiveSpec, s)
            MaterialPattern.BRICK_MASONRY -> drawBrickMasonry(out, effectiveSpec, s)
            MaterialPattern.WOOD_PLANKS -> drawWoodPlanks(out, effectiveSpec, s)
            MaterialPattern.WATER_CAUSTICS -> drawWaterCaustics(out, effectiveSpec, s)
            MaterialPattern.METAL_PLATING -> drawMetalPlating(out, effectiveSpec, s)
            MaterialPattern.DIAGONAL_PAVER -> drawDiagonalPaver(out, effectiveSpec, s)
            MaterialPattern.GLAZED_ROOF_TILE -> drawGlazedRoofTile(out, effectiveSpec, s)
            MaterialPattern.JADE_COURTYARD -> drawJadeCourtyard(out, effectiveSpec, s)
            MaterialPattern.HERRINGBONE_COBBLE -> drawHerringboneCobble(out, effectiveSpec, s)
            MaterialPattern.OBSIDIAN_CRAG -> drawObsidianCrag(out, effectiveSpec, s)
            MaterialPattern.LEAF_CANOPY_CLUSTER -> drawLeafCanopyCluster(out, effectiveSpec, s)
            MaterialPattern.HALF_TIMBER_PLASTER -> drawHalfTimberPlaster(out, effectiveSpec, s)
            MaterialPattern.SHINGLE_THATCH_ROOF -> drawShingleThatchRoof(out, effectiveSpec, s)
            MaterialPattern.SANCTUARY_COURTYARD -> drawSanctuaryCourtyard(out, effectiveSpec, s)
            MaterialPattern.ASPHALT_ROAD -> drawAsphaltRoad(out, effectiveSpec, s)
            MaterialPattern.HAZARD_GRATE -> drawHazardGrate(out, effectiveSpec, s)
            MaterialPattern.CYBER_CIRCUIT -> drawCyberCircuit(out, effectiveSpec, s)
            MaterialPattern.TUFTED_GROUND -> drawTuftedGround(out, effectiveSpec, s)
            MaterialPattern.ROUNDED_STONES -> drawRoundedStones(out, effectiveSpec, s)
            MaterialPattern.FLAT_WATER -> drawFlatWater(out, effectiveSpec, s)
            MaterialPattern.FLAT_PAVER -> drawFlatPaver(out, effectiveSpec, s)
            MaterialPattern.FLAT_PLANKS -> drawFlatPlanks(out, effectiveSpec, s)
            MaterialPattern.ASHLAR -> drawAshlar(out, effectiveSpec, s)
            MaterialPattern.FURROWED_SOIL -> drawFurrowedSoil(out, effectiveSpec, s)
        }
        // Hand-pixelled patterns are already flat and posterised; dithering them only adds static (8-bit still limits colours).
        val handPixelled = spec.pattern == MaterialPattern.TUFTED_GROUND || spec.pattern == MaterialPattern.ROUNDED_STONES ||
            spec.pattern == MaterialPattern.FLAT_WATER || spec.pattern == MaterialPattern.FLAT_PAVER ||
            spec.pattern == MaterialPattern.FLAT_PLANKS || spec.pattern == MaterialPattern.ASHLAR || spec.pattern == MaterialPattern.FURROWED_SOIL
        if (handPixelled && spec.style != PixelStyle.RETRO_8BIT) return out
        return PixelDither.applyStyle(out, spec.style, spec.palette)
    }

    /**
     * Generates a sequence of [frameCount] seamless animation frames for [spec]
     * (most useful for [MaterialPattern.WATER_CAUSTICS] and [MaterialPattern.ORGANIC_FBM]).
     */
    fun generateAnimatedFrames(spec: MaterialSpec, frameCount: Int): List<TileImage> {
        require(frameCount > 0) { "frameCount must be positive, got $frameCount" }
        return (0 until frameCount).map { frame ->
            generateTile(spec.copy(frameIndex = frame))
        }
    }

    /**
     * Generates named tiles for a list of [specs].
     */
    fun generateNamedTiles(specs: List<MaterialSpec>): List<NamedTile> =
        specs.map { NamedTile(it.name, generateTile(it)) }

    /**
     * Generates a bundled sprite sheet containing one tile per [MaterialSpec] in [specs].
     */
    fun generateSheet(specs: List<MaterialSpec>, columns: Int = 8): TileImage {
        require(columns > 0) { "columns must be positive, got $columns" }
        require(specs.isNotEmpty()) { "specs must not be empty" }
        val rows = (specs.size + columns - 1) / columns
        val sheet = TileImage(columns * tileSize, rows * tileSize)
        specs.forEachIndexed { idx, spec ->
            val col = idx % columns
            val row = idx / columns
            val tile = generateTile(spec)
            sheet.drawImage(tile, 0, 0, tileSize, tileSize, col * tileSize, row * tileSize)
        }
        return sheet
    }

    private fun drawOrganicFbm(pm: TileImage, spec: MaterialSpec, s: Long) {
        val freqScale = spec.scale * (32f / tileSize)
        val phaseShift = spec.frameIndex * 4f
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val nx = (px * freqScale + phaseShift).mod(tileSize.toFloat())
                val ny = (py * freqScale).mod(tileSize.toFloat())
                val n = TileNoise.wrappedFbm(nx, ny, period = tileSize, octaves = 3, seed = s)
                val micro = TileNoise.hash(px, py, s + 17L)
                val value = (n * 0.85f + micro * 0.15f * spec.detail).coerceIn(0f, 1f)
                var color = spec.palette.sample(value)
                if (spec.accentColor != null && spec.detail > 0.25f) {
                    val accentRoll = TileNoise.hash(px, py, s + 911L)
                    if (accentRoll > 1f - 0.025f * spec.detail) {
                        color = spec.accentColor
                    }
                }
                pm.setPixel(px, py, color)
            }
        }
    }

    private fun drawTuftedGround(pm: TileImage, spec: MaterialSpec, s: Long) {
        val colors = spec.palette.colors
        val last = colors.lastIndex
        fun tone(i: Int): Int = colors[i.coerceIn(0, last)]
        val mid = last / 2
        val size = tileSize
        // Coarse lattices (4 and 8 per tile) give big calm patches; wrappedFbm starts at 1 px and reads as static.
        val lattice = (4 * spec.scale).roundToInt().coerceIn(2, size / 4)
        for (py in 0 until size) {
            for (px in 0 until size) {
                val n = TileNoise.wrappedNoise2D(px * lattice / size.toFloat(), py * lattice / size.toFloat(), lattice, s) * 0.65f +
                    TileNoise.wrappedNoise2D(px * lattice * 2 / size.toFloat(), py * lattice * 2 / size.toFloat(), lattice * 2, s + 5L) * 0.35f
                val i = when {
                    n > 0.62f -> mid + 1
                    n < 0.36f -> mid - 1
                    else -> mid
                }
                pm.setPixel(px, py, tone(i))
            }
        }
        // Tufts: one candidate per 4 x 4 cell, drawn with wrap-around so the tile stays seamless.
        val cell = (4 * (size / 32f)).roundToInt().coerceAtLeast(3)
        val cells = size / cell
        val density = 0.30f + 0.45f * spec.detail
        fun put(x: Int, y: Int, color: Int) = pm.setPixel(x.mod(size), y.mod(size), color)
        for (cy in 0 until cells) {
            for (cx in 0 until cells) {
                if (TileNoise.hash(cx, cy, s + 301L) > density) continue
                val x = cx * cell + (TileNoise.hash(cx, cy, s + 302L) * (cell - 1)).toInt()
                val y = cy * cell + 1 + (TileNoise.hash(cx, cy, s + 303L) * (cell - 2)).toInt()
                val lean = if (TileNoise.hash(cx, cy, s + 304L) > 0.5f) 1 else -1
                put(x, y, tone(mid - 1))           // root shadow
                put(x, y - 1, tone(mid + 1))       // stem
                put(x + lean, y - 2, tone(last))   // lit tip leaning to one side
                if (TileNoise.hash(cx, cy, s + 305L) > 0.5f) put(x - lean, y - 1, tone(mid + 1))
            }
        }
        val accent = spec.accentColor
        if (accent != null) {
            for (cy in 0 until cells step 2) {
                for (cx in 0 until cells step 2) {
                    if (TileNoise.hash(cx, cy, s + 911L) < 0.82f) continue
                    val x = cx * cell + 1
                    val y = cy * cell + 1
                    put(x, y, accent)
                    put(x + 1, y, accent)
                    put(x, y + 1, tone(mid - 1))
                }
            }
        }
    }

    private fun drawRoundedStones(pm: TileImage, spec: MaterialSpec, s: Long) {
        val c = spec.palette.colors
        fun tone(i: Int) = c[i.coerceIn(0, c.lastIndex)]
        val cells = (3 * spec.scale).roundToInt().coerceIn(2, tileSize / 4)
        val cw = tileSize.toFloat() / cells
        val hi = c.lastIndex
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val x = px + 0.5f
                val y = py + 0.5f
                val gx = Math.floor((x / cw).toDouble()).toInt()
                val gy = Math.floor((y / cw).toDouble()).toInt()
                var d1 = Float.MAX_VALUE
                var d2 = Float.MAX_VALUE
                var hash = 0f
                var vx = 0f
                var vy = 0f
                for (oy in -1..1) {
                    for (ox in -1..1) {
                        val cx = Math.floorMod(gx + ox, cells)
                        val cy = Math.floorMod(gy + oy, cells)
                        val jx = TileNoise.hash(cx, cy, s) * 0.5f + 0.25f
                        val jy = TileNoise.hash(cx, cy, s + 997L) * 0.5f + 0.25f
                        val fx = (gx + ox + jx) * cw
                        val fy = (gy + oy + jy) * cw
                        // Stones are a little wider than tall.
                        val dx = (x - fx) / cw
                        val dy = (y - fy) / cw * 1.25f
                        val d = kotlin.math.hypot(dx, dy)
                        if (d < d1) {
                            d2 = d1
                            d1 = d
                            hash = TileNoise.hash(cx, cy, s + 3331L)
                            vx = dx
                            vy = dy
                        } else if (d < d2) {
                            d2 = d
                        }
                    }
                }
                val gap = d2 - d1
                val color = when {
                    gap < 0.09f -> tone(0)
                    gap < 0.17f -> tone(1)
                    else -> {
                        val r = (d1 / 0.5f).coerceIn(0f, 1.2f)
                        val light = -0.55f * vx / 0.5f - 0.65f * vy / 0.5f + 0.50f - r * 0.25f + (hash - 0.5f) * 0.30f
                        when {
                            light > 0.78f -> tone(hi)
                            light > 0.46f -> tone(hi - 1)
                            light > 0.14f -> tone(hi - 2)
                            else -> tone(1)
                        }
                    }
                }
                pm.setPixel(px, py, color)
            }
        }
        // Mossy speckle in the gaps so the wall does not read as clean tiles.
        val accent = spec.accentColor
        if (accent != null) {
            for (py in 0 until tileSize) {
                for (px in 0 until tileSize) {
                    if (pm.getPixel(px, py) == tone(0) && TileNoise.hash(px, py, s + 71L) > 0.7f) pm.setPixel(px, py, accent)
                }
            }
        }
    }

    private fun drawFlatPaver(pm: TileImage, spec: MaterialSpec, s: Long) {
        val c = spec.palette.colors
        fun tone(i: Int) = c[i.coerceIn(0, c.lastIndex)]
        val hi = c.lastIndex
        val cells = (3 * spec.scale).roundToInt().coerceIn(2, tileSize / 4)
        val cw = tileSize / cells
        val rows = tileSize / cw
        for (py in 0 until tileSize) {
            val row = py / cw
            val ly = py % cw
            // Running bond: every other row is shifted half a stone; the tile still wraps because the shift is half a cell.
            val shift = if (row % 2 == 1) cw / 2 else 0
            for (px in 0 until tileSize) {
                val sx = (px + shift) % tileSize
                val col = sx / cw
                val lx = sx % cw
                val tint = TileNoise.hash(col, row, s)
                val base = if (tint > 0.66f) hi - 2 else if (tint > 0.30f) hi - 3 else hi - 3
                val color = when {
                    lx == 0 || ly == 0 -> tone(0)
                    lx == 1 || ly == 1 -> tone(if (tint > 0.5f) hi else hi - 1)
                    lx == cw - 1 || ly == cw - 1 -> tone(1)
                    TileNoise.hash(px, py, s + 17L) > 0.965f -> tone(hi - 1)
                    TileNoise.hash(px / 2, py / 2, s + 29L) > 0.93f -> tone(base - 1)
                    else -> tone(base)
                }
                pm.setPixel(px, py, color)
            }
        }
        check(rows >= 1)
    }

    private fun drawFlatPlanks(pm: TileImage, spec: MaterialSpec, s: Long) {
        val c = spec.palette.colors
        fun tone(i: Int) = c[i.coerceIn(0, c.lastIndex)]
        val hi = c.lastIndex
        val boards = (4 * spec.scale).roundToInt().coerceIn(2, tileSize / 4)
        val bh = tileSize / boards
        for (py in 0 until tileSize) {
            val row = py / bh
            val ly = py % bh
            val cut = (TileNoise.hash(row, 3, s) * tileSize).toInt()
            for (px in 0 until tileSize) {
                val seg = if (((px - cut) % tileSize + tileSize) % tileSize < tileSize / 2) 0 else 1
                val tint = TileNoise.hash(row, seg, s + 5L)
                val base = if (tint > 0.62f) hi - 1 else if (tint > 0.28f) hi - 2 else hi - 3
                val joint = px == cut || px == (cut + tileSize / 2) % tileSize
                val color = when {
                    ly == 0 || joint -> tone(0)
                    ly == 1 -> tone(hi)
                    ly == bh - 1 -> tone(1)
                    TileNoise.hash(px / 3, py, s + 41L) > 0.91f -> tone(base - 1)
                    else -> tone(base)
                }
                pm.setPixel(px, py, color)
            }
        }
    }


    private fun drawAshlar(pm: TileImage, spec: MaterialSpec, s: Long) {
        val c = spec.palette.colors
        fun tone(i: Int) = c[i.coerceIn(0, c.lastIndex)]
        val hi = c.lastIndex
        val courses = (4 * spec.scale).roundToInt().coerceIn(2, tileSize / 4)
        val ch = tileSize / courses
        val bw = tileSize / 2
        for (py in 0 until tileSize) {
            val row = py / ch
            val ly = py % ch
            val shift = if (row % 2 == 0) 0 else bw / 2
            for (px in 0 until tileSize) {
                val x = (px + shift) % tileSize
                val col = x / bw
                val lx = x % bw
                val tint = TileNoise.hash(row, col, s + 7L)
                val base = if (tint > 0.7f) hi - 1 else if (tint > 0.3f) hi - 2 else hi - 3
                val grain = TileNoise.hash(px, py, s + 19L)
                val color = when {
                    ly == 0 || lx == 0 -> tone(0)
                    ly == 1 || lx == 1 -> tone(base + 1)
                    ly == ch - 1 || lx == bw - 1 -> tone(base - 1)
                    grain > 0.93f -> tone(base - 1)
                    grain < 0.05f -> tone(base + 1)
                    else -> tone(base)
                }
                pm.setPixel(px, py, color)
            }
        }
    }

    private fun drawFurrowedSoil(pm: TileImage, spec: MaterialSpec, s: Long) {
        val c = spec.palette.colors
        fun tone(i: Int) = c[i.coerceIn(0, c.lastIndex)]
        val period = (tileSize / 4).coerceAtLeast(4)
        for (py in 0 until tileSize) {
            val ly = py % period
            for (px in 0 until tileSize) {
                val grain = TileNoise.hash(px, py, s + 3L)
                val color = when {
                    ly == 0 -> tone(0)
                    ly == 1 -> tone(1)
                    ly < period / 2 + 1 -> if (grain > 0.8f) tone(3) else tone(2)
                    ly < period - 1 -> if (grain > 0.85f) tone(2) else if (grain < 0.1f) tone(0) else tone(1)
                    else -> tone(0)
                }
                pm.setPixel(px, py, color)
            }
        }
    }

    private fun drawFlatWater(pm: TileImage, spec: MaterialSpec, s: Long) {
        val c = spec.palette.colors
        fun tone(i: Int) = c[i.coerceIn(0, c.lastIndex)]
        val size = tileSize
        val mid = c.lastIndex / 2
        val lattice = (3 * spec.scale).roundToInt().coerceIn(2, size / 4)
        for (py in 0 until size) {
            for (px in 0 until size) {
                val n = TileNoise.wrappedNoise2D(px * lattice / size.toFloat(), py * lattice / size.toFloat(), lattice, s) * 0.6f +
                    TileNoise.wrappedNoise2D(px * lattice * 2 / size.toFloat(), py * lattice * 2 / size.toFloat(), lattice * 2, s + 3L) * 0.4f
                pm.setPixel(px, py, tone(if (n < 0.36f) mid - 1 else mid))
            }
        }
        // Light streaks drift one quarter of the tile per frame, so four frames loop exactly.
        val shift = spec.frameIndex * (size / 4)
        val rowStep = (size / 10).coerceAtLeast(3)
        val seg = (size / 5).coerceAtLeast(4)
        for (y in 1 until size step rowStep) {
            val row = y / rowStep
            for (k in 0 until size / seg) {
                if (TileNoise.hash(k, row, s + 17L) < 0.45f - 0.2f * spec.detail) continue
                val start = k * seg + (TileNoise.hash(k, row, s + 18L) * (seg / 2)).toInt()
                val len = (seg / 2) + (TileNoise.hash(k, row, s + 19L) * (seg / 2)).toInt()
                for (i in 0 until len) {
                    val x = (start + i + shift).mod(size)
                    pm.setPixel(x, y, tone(mid + 1))
                    if (i in 1 until len - 1 && TileNoise.hash(k, row, s + 20L) > 0.6f) pm.setPixel(x, y, tone(c.lastIndex))
                }
                // A darker trailing pixel under the streak gives it a little depth.
                val tail = (start + len + shift).mod(size)
                pm.setPixel(tail, (y + 1).coerceAtMost(size - 1), tone(mid - 1))
            }
        }
    }

    private fun drawCobblestone(pm: TileImage, spec: MaterialSpec, s: Long) {
        val cells = (4 * spec.scale).roundToInt().coerceIn(2, tileSize / 2)
        val mortar = spec.accentColor ?: spec.palette.shadow
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val (d1, d2, cellHash) = TileNoise.periodicVoronoi(px.toFloat(), py.toFloat(), period = tileSize, cells = cells, seed = s)
                val edgeDist = d2 - d1
                if (edgeDist < 0.12f) {
                    pm.setPixel(px, py, mortar)
                } else {
                    val fbm = TileNoise.wrappedFbm(px.toFloat(), py.toFloat(), period = tileSize, octaves = 2, seed = s + 44L)
                    val tone = (0.35f + cellHash * 0.35f + fbm * 0.25f * spec.detail).coerceIn(0f, 1f)
                    // Subtle top-left bevel highlight
                    val (_, d2Above, _) = TileNoise.periodicVoronoi(px.toFloat(), (py - 1).mod(tileSize).toFloat(), period = tileSize, cells = cells, seed = s)
                    val isTopHighlight = d2Above - d1 < 0.06f && edgeDist >= 0.12f
                    val finalTone = if (isTopHighlight) (tone + 0.25f).coerceAtMost(1f) else tone
                    pm.setPixel(px, py, spec.palette.sample(finalTone))
                }
            }
        }
    }

    private fun drawBrickMasonry(pm: TileImage, spec: MaterialSpec, s: Long) {
        val brickH = (tileSize / (4f * spec.scale)).roundToInt().coerceIn(4, tileSize / 2)
        val brickW = (brickH * 2).coerceAtMost(tileSize)
        val mortar = spec.accentColor ?: if (spec.style == PixelStyle.CLASSIC_RPG) spec.palette.colors[1] else spec.palette.shadow

        for (py in 0 until tileSize) {
            val rowIdx = py / brickH
            val yInBrick = py % brickH
            val rowOffset = if (rowIdx % 2 == 1) brickW / 2 else 0
            for (px in 0 until tileSize) {
                val shiftedX = (px + rowOffset) % tileSize
                val colIdx = shiftedX / brickW
                val xInBrick = shiftedX % brickW

                if (yInBrick == 0 || xInBrick == 0) {
                    pm.setPixel(px, py, mortar)
                } else {
                    val brickHash = TileNoise.hash(colIdx, rowIdx, s + 101L)
                    val weather = TileNoise.wrappedFbm(px.toFloat(), py.toFloat(), period = tileSize, octaves = 2, seed = s + 202L)
                    var t = 0.32f + brickHash * 0.32f + (weather - 0.5f) * 0.25f * spec.detail
                    if (yInBrick == 1 || xInBrick == 1) t += 0.2f // Top-left brick bevel
                    if (yInBrick == brickH - 1) t -= 0.15f // Bottom shadow
                    pm.setPixel(px, py, spec.palette.sample(t.coerceIn(0f, 1f)))
                }
            }
        }
    }

    private fun drawWoodPlanks(pm: TileImage, spec: MaterialSpec, s: Long) {
        val plankH = (tileSize / (4f * spec.scale)).roundToInt().coerceIn(4, tileSize / 2)
        val seamColor = spec.accentColor ?: spec.palette.shadow

        for (py in 0 until tileSize) {
            val plankIdx = py / plankH
            val yInPlank = py % plankH
            val jointX = ((TileNoise.hash(plankIdx, 0, s + 313L) * tileSize).toInt()).mod(tileSize)

            for (px in 0 until tileSize) {
                if (yInPlank == 0 || px == jointX) {
                    pm.setPixel(px, py, seamColor)
                } else {
                    val grain = TileNoise.wrappedNoise2D(px * 0.5f, py * 2.5f, period = tileSize, seed = s + plankIdx * 71L)
                    val plankTone = TileNoise.hash(plankIdx, 1, s + 419L) * 0.2f
                    var t = 0.35f + plankTone + (grain - 0.5f) * 0.45f * spec.detail.coerceAtLeast(0.25f)
                    if (yInPlank == 1) t += 0.18f
                    // Nail heads near plank joints
                    val isNail = (yInPlank == 2 || yInPlank == plankH - 2) && (px == (jointX + 2).mod(tileSize))
                    if (isNail) {
                        pm.setPixel(px, py, seamColor)
                    } else {
                        pm.setPixel(px, py, spec.palette.sample(t.coerceIn(0f, 1f)))
                    }
                }
            }
        }
    }

    private fun drawWaterCaustics(pm: TileImage, spec: MaterialSpec, s: Long) {
        val cells = (3 * spec.scale).roundToInt().coerceIn(2, tileSize / 2)
        val phase = spec.frameIndex * (2.0 * PI / 4.0)
        val dx = (cos(phase) * (tileSize * 0.125)).toFloat()
        val dy = (sin(phase) * (tileSize * 0.125)).toFloat()

        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val sx = (px + dx).mod(tileSize.toFloat())
                val sy = (py + dy).mod(tileSize.toFloat())
                val (d1, d2, _) = TileNoise.periodicVoronoi(sx, sy, period = tileSize, cells = cells, seed = s)
                val ridge = 1f - ((d2 - d1) * 3.5f).coerceIn(0f, 1f)
                val wave = TileNoise.wrappedFbm(sx, sy, period = tileSize, octaves = 2, seed = s + 55L)
                val t = (wave * 0.45f + ridge * 0.55f * spec.detail.coerceAtLeast(0.3f)).coerceIn(0f, 1f)
                pm.setPixel(px, py, spec.palette.sample(t))
            }
        }
    }

    private fun drawMetalPlating(pm: TileImage, spec: MaterialSpec, s: Long) {
        val panelSize = (tileSize / (2f * spec.scale)).roundToInt().coerceIn(8, tileSize)
        val seam = spec.accentColor ?: spec.palette.shadow

        for (py in 0 until tileSize) {
            val pyLocal = py % panelSize
            for (px in 0 until tileSize) {
                val pxLocal = px % panelSize
                val isSeam = pxLocal == 0 || pyLocal == 0
                val isRivet = (pxLocal == 2 || pxLocal == panelSize - 2) &&
                    (pyLocal == 2 || pyLocal == panelSize - 2)

                if (isSeam) {
                    pm.setPixel(px, py, seam)
                } else if (isRivet) {
                    pm.setPixel(px, py, spec.palette.highlight)
                } else {
                    val brush = TileNoise.wrappedNoise2D(px * 0.35f, py * 1.5f, period = tileSize, seed = s)
                    var t = 0.42f + (brush - 0.5f) * 0.25f * spec.detail
                    if (pxLocal == 1 || pyLocal == 1) t += 0.22f // Top-left inner bevel
                    if (pxLocal == panelSize - 1 || pyLocal == panelSize - 1) t -= 0.18f // Bottom-right bevel
                    pm.setPixel(px, py, spec.palette.sample(t.coerceIn(0f, 1f)))
                }
            }
        }
    }

    private fun drawDiagonalPaver(pm: TileImage, spec: MaterialSpec, s: Long) {
        val slabSize = (tileSize / (2f * spec.scale)).roundToInt().coerceIn(4, tileSize)
        val mortar = spec.accentColor ?: spec.palette.colors[1]

        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val u = px + py
                val v = (px - py + tileSize * 4)
                val rowIdx = (u / slabSize)
                val uIn = u % slabSize
                val vShifted = v + (if (rowIdx % 2 == 1) slabSize / 2 else 0)
                val colIdx = vShifted / slabSize
                val vIn = vShifted % slabSize

                if (uIn == 0 || vIn == 0) {
                    pm.setPixel(px, py, mortar)
                } else {
                    val slabHash = TileNoise.hash(colIdx % 8, rowIdx % 8, s + 601L)
                    var t = 0.56f + slabHash * 0.22f
                    if (uIn == 1 || vIn == 1) t += 0.14f
                    if (uIn == slabSize - 1) t -= 0.12f
                    pm.setPixel(px, py, spec.palette.sample(t.coerceIn(0.25f, 1f)))
                }
            }
        }
    }

    private fun drawGlazedRoofTile(pm: TileImage, spec: MaterialSpec, s: Long) {
        val tubeW = (tileSize / (4f * spec.scale)).roundToInt().coerceIn(3, tileSize / 2)
        val rowH = (tileSize / (3f * spec.scale)).roundToInt().coerceIn(4, tileSize / 2)
        val ridge = spec.accentColor ?: spec.palette.shadow
        for (py in 0 until tileSize) {
            val yInRow = py % rowH
            val rowIdx = py / rowH
            for (px in 0 until tileSize) {
                val xInTube = px % tubeW
                val colIdx = px / tubeW
                val archDrop = if (xInTube == 0 || xInTube == tubeW - 1) 0 else 1
                val isEaveLip = yInRow == (rowH - 1 - archDrop).coerceAtLeast(0)
                val isTubeRib = xInTube == 0
                if (isTubeRib) {
                    pm.setPixel(px, py, ridge)
                } else if (isEaveLip) {
                    pm.setPixel(px, py, spec.palette.highlight)
                } else {
                    val cylShade = sin((xInTube.toFloat() / tubeW) * PI.toFloat())
                    val tileHash = TileNoise.hash(colIdx, rowIdx, s + 709L) * 0.12f
                    val t = (0.30f + cylShade * 0.52f + tileHash).coerceIn(0f, 1f)
                    pm.setPixel(px, py, spec.palette.sample(t))
                }
            }
        }
    }

    private fun drawJadeCourtyard(pm: TileImage, spec: MaterialSpec, s: Long) {
        val half = tileSize / 2
        val trim = spec.accentColor ?: spec.palette.highlight
        val groove = spec.palette.shadow
        for (py in 0 until tileSize) {
            val ly = py % half
            for (px in 0 until tileSize) {
                val lx = px % half
                val isOuterSeam = lx == 0 || ly == 0
                val isCornerInlay = (lx in 1..2 && ly in 1..2) ||
                    (lx in (half - 2) until half && ly in 1..2) ||
                    (lx in 1..2 && ly in (half - 2) until half) ||
                    (lx in (half - 2) until half && ly in (half - 2) until half)
                val isInnerFrame = (lx == 2 || lx == half - 2 || ly == 2 || ly == half - 2) &&
                    lx in 2..(half - 2) && ly in 2..(half - 2)
                when {
                    isOuterSeam -> pm.setPixel(px, py, groove)
                    isCornerInlay -> pm.setPixel(px, py, trim)
                    isInnerFrame -> pm.setPixel(px, py, spec.palette.sample(0.78f))
                    else -> {
                        val fbm = TileNoise.wrappedFbm(px.toFloat(), py.toFloat(), period = tileSize, octaves = 2, seed = s + 811L)
                        val bevel = if (lx == 1 || ly == 1) 0.16f else 0f
                        val t = (0.48f + fbm * 0.26f * spec.detail + bevel).coerceIn(0.2f, 0.95f)
                        pm.setPixel(px, py, spec.palette.sample(t))
                    }
                }
            }
        }
    }

    private fun drawHerringboneCobble(pm: TileImage, spec: MaterialSpec, s: Long) {
        val unit = (tileSize / (4f * spec.scale)).roundToInt().coerceIn(2, tileSize / 2)
        val mortar = spec.accentColor ?: spec.palette.shadow
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val stepX = px / unit
                val stepY = py / unit
                val phase = (stepX + stepY) % 4
                val isVerticalBlock = phase < 2
                val isSeam = if (isVerticalBlock) {
                    (px % unit == 0) || (py % (unit * 2) == (stepX % 2) * unit)
                } else {
                    (py % unit == 0) || (px % (unit * 2) == (stepY % 2) * unit)
                }
                if (isSeam) {
                    pm.setPixel(px, py, mortar)
                } else {
                    val blockHash = TileNoise.hash(stepX, stepY, s + 919L)
                    val grain = TileNoise.wrappedFbm(px.toFloat(), py.toFloat(), period = tileSize, octaves = 2, seed = s + 929L)
                    val isTopLeft = (px % unit == 1) || (py % unit == 1)
                    var t = 0.34f + blockHash * 0.34f + (grain - 0.5f) * 0.22f * spec.detail
                    if (isTopLeft) t += 0.20f
                    pm.setPixel(px, py, spec.palette.sample(t.coerceIn(0.15f, 1f)))
                }
            }
        }
    }

    private fun drawObsidianCrag(pm: TileImage, spec: MaterialSpec, s: Long) {
        val cells = (3 * spec.scale).roundToInt().coerceIn(2, tileSize / 2)
        val veinColor = spec.accentColor ?: PaletteRamp.rgba(232, 48, 64)
        val phaseShift = spec.frameIndex * 0.25f
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val (d1, d2, cellHash) = TileNoise.periodicVoronoi(px.toFloat(), py.toFloat(), period = tileSize, cells = cells, seed = s + 997L)
                val edge = d2 - d1
                if (edge < 0.11f) {
                    val pulse = sin((px + py) * 0.5f + phaseShift * 2f * PI.toFloat())
                    pm.setPixel(px, py, if (edge < 0.05f && pulse >= -0.2f) veinColor else spec.palette.shadow)
                } else {
                    val facet = (1f - d1 * 1.4f).coerceIn(0f, 1f)
                    val t = (0.18f + cellHash * 0.35f + facet * 0.38f * spec.detail.coerceAtLeast(0.3f)).coerceIn(0f, 1f)
                    pm.setPixel(px, py, spec.palette.sample(t))
                }
            }
        }
    }

    private fun drawLeafCanopyCluster(pm: TileImage, spec: MaterialSpec, s: Long) {
        val cells = (4 * spec.scale).roundToInt().coerceIn(2, tileSize / 2)
        val phaseShift = spec.frameIndex * 0.5f
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val sx = (px + phaseShift).mod(tileSize.toFloat())
                val sy = py.toFloat()
                val (d1, d2, cellHash) = TileNoise.periodicVoronoi(sx, sy, period = tileSize, cells = cells, seed = s + 1103L)
                // Compare with neighbor slightly above-left to create scalloped top-left lit leaf clumps and bottom-right AO
                val (d1UpLeft, _, _) = TileNoise.periodicVoronoi(
                    (sx - 1.2f).mod(tileSize.toFloat()),
                    (sy - 1.4f).mod(tileSize.toFloat()),
                    period = tileSize,
                    cells = cells,
                    seed = s + 1103L
                )
                val edge = d2 - d1
                val directionalCrown = (d1UpLeft - d1).coerceIn(-0.35f, 0.35f)
                val clumpDome = (1f - d1 * 1.15f).coerceIn(0f, 1f)
                val t = when {
                    edge < 0.08f && directionalCrown < 0f -> 0.06f // Deep indigo-green crevice shadow under clump
                    edge < 0.09f -> 0.22f
                    else -> (0.34f + cellHash * 0.22f + clumpDome * 0.30f + directionalCrown * 0.65f * spec.detail.coerceAtLeast(0.35f))
                        .coerceIn(0.14f, 0.98f)
                }
                val col = if (spec.accentColor != null && cellHash > 0.86f && clumpDome > 0.68f) {
                    spec.accentColor
                } else {
                    spec.palette.sample(t)
                }
                pm.setPixel(px, py, col)
            }
        }
    }

    private fun drawHalfTimberPlaster(pm: TileImage, spec: MaterialSpec, s: Long) {
        val beamW = (tileSize / 8).coerceAtLeast(2)
        val half = tileSize / 2
        val timberColor = spec.accentColor ?: PalettePresets.WARM_TIMBER.colors[2]
        val timberShadow = PalettePresets.WARM_TIMBER.shadow
        val timberHighlight = PalettePresets.WARM_TIMBER.colors[3]

        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val isTopBeam = py < beamW
                val isMidBeam = py in (half - beamW / 2) until (half + (beamW + 1) / 2)
                val isLeftPost = px < beamW
                val isMidPost = px in (half - beamW / 2) until (half + (beamW + 1) / 2)

                // Diagonal cross-bracing in lower and upper bays
                val localX = px % half
                val localY = py % half
                val isBrace = if ((px / half + py / half) % 2 == 0) {
                    kotlin.math.abs(localX - localY) < (beamW / 2).coerceAtLeast(1)
                } else {
                    kotlin.math.abs((half - 1 - localX) - localY) < (beamW / 2).coerceAtLeast(1)
                }

                if (isTopBeam || isMidBeam || isLeftPost || isMidPost || isBrace) {
                    val isBeamEdge = (isTopBeam && py == beamW - 1) ||
                        (isLeftPost && px == beamW - 1) ||
                        (isMidBeam && py == half + (beamW + 1) / 2 - 1) ||
                        (isMidPost && px == half + (beamW + 1) / 2 - 1)
                    val isBeamLit = (isTopBeam && py == 0) || (isLeftPost && px == 0)
                    val grain = TileNoise.wrappedNoise2D(px * 0.7f, py * 1.8f, period = tileSize, seed = s + 1217L)
                    val col = when {
                        isBeamEdge -> timberShadow
                        isBeamLit || grain > 0.68f -> timberHighlight
                        else -> timberColor
                    }
                    pm.setPixel(px, py, col)
                } else {
                    // Warm cream plaster with recessed top-left beam shadow and subtle trowel texture
                    val underBeamShadow = py == beamW || py == half + (beamW + 1) / 2 ||
                        px == beamW || px == half + (beamW + 1) / 2
                    val fbm = TileNoise.wrappedFbm(px.toFloat(), py.toFloat(), period = tileSize, octaves = 2, seed = s + 1229L)
                    val t = if (underBeamShadow) {
                        0.42f
                    } else {
                        (0.72f + (fbm - 0.5f) * 0.22f * spec.detail).coerceIn(0.52f, 0.98f)
                    }
                    pm.setPixel(px, py, spec.palette.sample(t))
                }
            }
        }
    }

    private fun drawShingleThatchRoof(pm: TileImage, spec: MaterialSpec, s: Long) {
        val rowH = (tileSize / (4f * spec.scale)).roundToInt().coerceIn(4, tileSize / 2)
        val colW = (tileSize / (4f * spec.scale)).roundToInt().coerceIn(4, tileSize / 2)
        val seamColor = spec.accentColor ?: spec.palette.shadow

        for (py in 0 until tileSize) {
            val rowIdx = py / rowH
            val yInRow = py % rowH
            val xOffset = if (rowIdx % 2 == 1) colW / 2 else 0
            for (px in 0 until tileSize) {
                val sx = (px + xOffset) % tileSize
                val colIdx = sx / colW
                val xInCol = sx % colW
                val centerDist = kotlin.math.abs(xInCol - colW / 2f) / (colW / 2f).coerceAtLeast(1f)
                // Scalloped curved bottom lip of each shingle
                val lipCurve = (centerDist * 1.4f).toInt().coerceAtMost(rowH - 2)
                val eaveY = (rowH - 1 - lipCurve).coerceAtLeast(1)

                when {
                    yInRow == eaveY -> pm.setPixel(px, py, seamColor)
                    yInRow == (eaveY - 1).coerceAtLeast(0) -> pm.setPixel(px, py, spec.palette.highlight)
                    xInCol == 0 && yInRow < eaveY -> pm.setPixel(px, py, spec.palette.sample(0.24f))
                    else -> {
                        val shingleHash = TileNoise.hash(colIdx, rowIdx, s + 1301L)
                        val verticalGrad = yInRow.toFloat() / rowH.coerceAtLeast(1)
                        val underLapShadow = if (yInRow == 0) -0.22f else 0f
                        val t = (0.38f + verticalGrad * 0.36f + (shingleHash - 0.5f) * 0.22f * spec.detail + underLapShadow)
                            .coerceIn(0.12f, 0.92f)
                        pm.setPixel(px, py, spec.palette.sample(t))
                    }
                }
            }
        }
    }

    private fun drawSanctuaryCourtyard(pm: TileImage, spec: MaterialSpec, s: Long) {
        val half = tileSize / 2
        val goldInlay = spec.accentColor ?: PalettePresets.CREAM_MARBLE.highlight
        val groove = spec.palette.shadow

        for (py in 0 until tileSize) {
            val ly = py % half
            for (px in 0 until tileSize) {
                val lx = px % half
                val manhattanCenter = kotlin.math.abs(lx - half / 2) + kotlin.math.abs(ly - half / 2)
                val isOuterGroove = lx == 0 || ly == 0
                val isTopLeftBevel = lx == 1 || ly == 1
                val isBottomRightBevel = lx == half - 1 || ly == half - 1
                val isDiamondRing = manhattanCenter == (half / 3).coerceAtLeast(2)
                val isCenterMedallion = manhattanCenter <= 1

                when {
                    isOuterGroove -> pm.setPixel(px, py, groove)
                    isCenterMedallion -> pm.setPixel(px, py, goldInlay)
                    isDiamondRing -> pm.setPixel(px, py, spec.palette.sample(0.36f))
                    isTopLeftBevel -> pm.setPixel(px, py, spec.palette.highlight)
                    isBottomRightBevel -> pm.setPixel(px, py, spec.palette.sample(0.32f))
                    else -> {
                        val fbm = TileNoise.wrappedFbm(px.toFloat(), py.toFloat(), period = tileSize, octaves = 2, seed = s + 1409L)
                        val sunGrad = (1f - (lx + ly).toFloat() / (half * 2f)) * 0.18f
                        val t = (0.58f + sunGrad + (fbm - 0.5f) * 0.18f * spec.detail).coerceIn(0.35f, 0.96f)
                        pm.setPixel(px, py, spec.palette.sample(t))
                    }
                }
            }
        }
    }

    private fun drawAsphaltRoad(pm: TileImage, spec: MaterialSpec, s: Long) {
        val crackColor = spec.palette.shadow
        val puddleTint = spec.accentColor
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val fbm = TileNoise.wrappedFbm(px.toFloat(), py.toFloat(), period = tileSize, octaves = 3, seed = s + 1501L)
                val grit = TileNoise.hash(px, py, s + 1507L)
                val crackNoise = kotlin.math.abs(
                    TileNoise.wrappedFbm(px.toFloat() * 1.6f, py.toFloat() * 1.6f, period = tileSize, octaves = 2, seed = s + 1511L) - 0.5f
                )
                val isCrack = crackNoise < 0.022f && fbm > 0.48f
                when {
                    isCrack -> pm.setPixel(px, py, crackColor)
                    puddleTint != null && fbm < 0.28f -> {
                        val t = (0.32f + grit * 0.12f).coerceIn(0.15f, 0.55f)
                        pm.setPixel(px, py, lerpRgba(spec.palette.sample(t), puddleTint, 0.35f))
                    }
                    else -> {
                        val t = (0.28f + fbm * 0.32f + (grit - 0.5f) * 0.22f * spec.detail).coerceIn(0.12f, 0.72f)
                        pm.setPixel(px, py, spec.palette.sample(t))
                    }
                }
            }
        }
    }

    private fun drawHazardGrate(pm: TileImage, spec: MaterialSpec, s: Long) {
        val hazardYellow = spec.accentColor ?: PalettePresets.HAZARD_AMBER.mid
        val hazardBlack = spec.palette.shadow
        val borderBand = (tileSize / 8).coerceAtLeast(2)

        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val inBottomTrim = py >= tileSize - borderBand
                if (inBottomTrim) {
                    val stripe = ((px + py) / 3) % 2 == 0
                    pm.setPixel(px, py, if (stripe) hazardYellow else hazardBlack)
                } else {
                    val cell = (px % 4 == 0) || (py % 4 == 0)
                    val diamond = ((px + py) % 4 == 2) && ((px - py + tileSize) % 4 == 2)
                    val grime = (TileNoise.hash(px, py, s + 1601L) - 0.5f) * 0.14f * spec.detail
                    val baseT = when {
                        cell -> 0.20f
                        diamond -> 0.84f
                        else -> 0.50f
                    }
                    pm.setPixel(px, py, spec.palette.sample((baseT + grime).coerceIn(0.10f, 0.94f)))
                }
            }
        }
    }

    private fun drawCyberCircuit(pm: TileImage, spec: MaterialSpec, s: Long) {
        val traceGlow = spec.accentColor ?: PalettePresets.CRT_GREEN.highlight
        val traceMid = lerpRgba(spec.palette.mid, traceGlow, 0.65f)
        val half = tileSize / 2
        val q = (tileSize / 4).coerceAtLeast(2)

        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val isBorder = px == 0 || py == 0
                val isMainBus = (px == half && py in q..(tileSize - q)) || (py == q && px >= half) || (py == tileSize - q && px <= half)
                val isVia = (px == half && py == q) || (px == half && py == tileSize - q) || (px == q && py == q)
                when {
                    isVia -> pm.setPixel(px, py, traceGlow)
                    isMainBus -> pm.setPixel(px, py, traceMid)
                    isBorder -> pm.setPixel(px, py, spec.palette.shadow)
                    else -> {
                        val fbm = TileNoise.wrappedFbm(px.toFloat(), py.toFloat(), period = tileSize, octaves = 2, seed = s + 1709L)
                        val t = (0.24f + fbm * 0.24f * spec.detail).coerceIn(0.12f, 0.55f)
                        pm.setPixel(px, py, spec.palette.sample(t))
                    }
                }
            }
        }
    }

    private fun lerpRgba(a: Int, b: Int, t: Float): Int {
        val inv = 1f - t
        val r = (((a ushr 16) and 0xFF) * inv + ((b ushr 16) and 0xFF) * t).toInt().coerceIn(0, 255)
        val g = (((a ushr 8) and 0xFF) * inv + ((b ushr 8) and 0xFF) * t).toInt().coerceIn(0, 255)
        val bl = ((a and 0xFF) * inv + (b and 0xFF) * t).toInt().coerceIn(0, 255)
        val al = (((a ushr 24) and 0xFF) * inv + ((b ushr 24) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (al shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
