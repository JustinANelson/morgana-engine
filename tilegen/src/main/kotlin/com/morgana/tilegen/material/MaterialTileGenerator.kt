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

    /** 45-degree diagonal staggered stone pavers with soft mortar and cel-shaded bevels (Time Fantasy style). */
    DIAGONAL_PAVER,

    /** Curved Oriental Wuxia glazed clay roof tiles with bamboo/gold ridge caps (Conquer Online style). */
    GLAZED_ROOF_TILE,

    /** Imperial carved jade/marble courtyard flagstones with inlaid octagonal border filigree (Conquer Online style). */
    JADE_COURTYARD,

    /** Heavy medieval European herringbone masonry cobbles with deep bevels and moss crevices (White River / BDO style). */
    HERRINGBONE_COBBLE,

    /** Dark jagged obsidian and Black Spirit rift stone with glowing abyssal fissure veins (White River / BDO style). */
    OBSIDIAN_CRAG,

    /** Overlapping scalloped leaf-clump clusters with sunlit top-left crowns and deep under-clump AO (Suikoden II / Ragnarok Online style). */
    LEAF_CANOPY_CLUSTER,

    /** Cream plaster wall panels framed by weathered horizontal, vertical, and diagonal cross-braced timber beams (Suikoden II / Ragnarok town style). */
    HALF_TIMBER_PLASTER,

    /** Overlapping scalloped fish-scale/terracotta roof shingles with eave bevels and cast drop shadows (Suikoden II / Ragnarok roof style). */
    SHINGLE_THATCH_ROOF,

    /** Grand 2.5D beveled sanctuary flagstones with diamond inlays and corner rune medallions (Ragnarok Online Prontera / Suikoden castle style). */
    PRONTERA_COURTYARD,

    /** Cracked wet urban asphalt with fine aggregate speckle and optional lane markings (RoboCop Old Detroit style). */
    ASPHALT_ROAD,

    /** Industrial steel floor grating with diamond-plate ribs and diagonal yellow-black hazard trim (RoboCop OCP style). */
    HAZARD_GRATE,

    /** Illuminated cybernetic circuit-board mainframe floor with glowing trace lines and node vias (OCP Tower style). */
    CYBER_CIRCUIT
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
            MaterialPattern.PRONTERA_COURTYARD -> drawPronteraCourtyard(out, effectiveSpec, s)
            MaterialPattern.ASPHALT_ROAD -> drawAsphaltRoad(out, effectiveSpec, s)
            MaterialPattern.HAZARD_GRATE -> drawHazardGrate(out, effectiveSpec, s)
            MaterialPattern.CYBER_CIRCUIT -> drawCyberCircuit(out, effectiveSpec, s)
        }
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
        val mortar = spec.accentColor ?: if (spec.style == PixelStyle.TIME_FANTASY) spec.palette.colors[1] else spec.palette.shadow

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
        val timberColor = spec.accentColor ?: PalettePresets.SK_KYARO_TIMBER.colors[2]
        val timberShadow = PalettePresets.SK_KYARO_TIMBER.shadow
        val timberHighlight = PalettePresets.SK_KYARO_TIMBER.colors[3]

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

    private fun drawPronteraCourtyard(pm: TileImage, spec: MaterialSpec, s: Long) {
        val half = tileSize / 2
        val goldInlay = spec.accentColor ?: PalettePresets.RO_PRONTERA_MARBLE.highlight
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
        val hazardYellow = spec.accentColor ?: PalettePresets.RC_HAZARD_AMBER.mid
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
        val traceGlow = spec.accentColor ?: PalettePresets.RC_CRT_GREEN.highlight
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
