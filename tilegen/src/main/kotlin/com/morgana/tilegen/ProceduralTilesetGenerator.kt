package com.morgana.tilegen

import kotlin.math.*

/**
 * Deterministic procedural generator for 32x32 retro pixel-art map tilesets.
 *
 * Uses periodic (toroidal) value noise, fractional Brownian motion (fBm), periodic Voronoi cellular
 * structures, and curated color palettes to synthesize crisp, seamless textures for RPG map tiles.
 *
 * All coordinates wrap seamlessly at 32 pixels so terrain tiles repeat cleanly across large maps.
 */
class ProceduralTilesetGenerator(val seed: Long = 1337L) {

    val tileSize: Int = 32

    // ---- Color Palette Helpers (packed RGBA8888) ----
    private fun rgba(r: Int, g: Int, b: Int, a: Int = 255): Int =
        ((r and 0xFF) shl 24) or ((g and 0xFF) shl 16) or ((b and 0xFF) shl 8) or (a and 0xFF)

    // Meadow & Foliage Palette
    val grassShadow = rgba(25, 64, 36)
    val grassBase = rgba(46, 111, 64)
    val grassMid = rgba(59, 133, 80)
    val grassHighlight = rgba(88, 175, 110)
    val grassSunlit = rgba(118, 204, 138)

    // Dirt & Soil Palette
    val dirtDeep = rgba(61, 38, 21)
    val dirtShade = rgba(95, 62, 36)
    val dirtBase = rgba(139, 109, 75)
    val dirtLight = rgba(163, 131, 94)
    val dirtPebble = rgba(189, 156, 118)

    // Cobblestone & Masonry Palette
    val mortarCrevice = rgba(36, 32, 30)
    val stoneDark = rgba(65, 68, 76)
    val stoneBase = rgba(100, 106, 118)
    val stoneLight = rgba(136, 143, 158)
    val stoneBevel = rgba(175, 182, 198)

    // Water & Caustics Palette
    val waterAbyss = rgba(20, 48, 120)
    val waterDeep = rgba(29, 78, 216)
    val waterMid = rgba(59, 130, 246)
    val waterCrest = rgba(96, 165, 250)
    val waterFoam = rgba(191, 219, 254)

    // Sand Palette
    val sandShadow = rgba(184, 153, 94)
    val sandBase = rgba(212, 184, 124)
    val sandLight = rgba(227, 204, 148)
    val sandGleam = rgba(245, 230, 185)

    // Wood & Bark Palette
    val woodDark = rgba(69, 26, 3)
    val woodBase = rgba(120, 53, 15)
    val woodLight = rgba(154, 107, 58)
    val woodHighlight = rgba(180, 130, 80)

    // Snow & Ice Palette
    val snowShadow = rgba(186, 205, 224)
    val snowBase = rgba(232, 241, 248)
    val snowLight = rgba(250, 253, 255)
    val iceDark = rgba(91, 181, 214)
    val iceBase = rgba(154, 219, 242)
    val iceGlint = rgba(217, 244, 255)

    // ---- Periodic Hash & Noise Math ----

    private fun hash(x: Int, y: Int, s: Long): Float {
        var h = s xor (x.toLong() * 374761393L) xor (y.toLong() * 668265263L)
        h = (h xor (h ushr 16)) * 0x85ebca6bL
        h = (h xor (h ushr 13)) * 0xc2b2ae35L
        return ((h xor (h ushr 16)) and 0x7FFFFFFFL).toFloat() / 0x7FFFFFFFL.toFloat()
    }

    /**
     * Seamless 2D periodic value noise that repeats exactly every [period] pixels.
     */
    fun wrappedNoise2D(x: Float, y: Float, period: Int, s: Long = seed): Float {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val fx = x - ix
        val fy = y - iy

        val x0 = ((ix % period) + period) % period
        val y0 = ((iy % period) + period) % period
        val x1 = (x0 + 1) % period
        val y1 = (y0 + 1) % period

        val u = fx * fx * (3f - 2f * fx)
        val v = fy * fy * (3f - 2f * fy)

        val h00 = hash(x0, y0, s)
        val h10 = hash(x1, y0, s)
        val h01 = hash(x0, y1, s)
        val h11 = hash(x1, y1, s)

        val top = h00 + u * (h10 - h00)
        val bottom = h01 + u * (h11 - h01)
        return top + v * (bottom - top)
    }

    /**
     * Fractional Brownian motion with wrapping period of 32 pixels.
     */
    fun wrappedFbm(x: Float, y: Float, octaves: Int = 3, persistence: Float = 0.5f, s: Long = seed): Float {
        var total = 0f
        var frequency = 1f
        var amplitude = 1f
        var maxValue = 0f
        for (i in 0 until octaves) {
            val period = (32 * frequency).toInt()
            total += wrappedNoise2D(x * frequency, y * frequency, period, s + i * 31L) * amplitude
            maxValue += amplitude
            amplitude *= persistence
            frequency *= 2f
        }
        return total / maxValue
    }

    /**
     * Periodic Voronoi cellular distance calculation for stones/cobble.
     * Returns Triple(d1 = nearest distance, d2 = 2nd nearest distance, cellHash = float in 0..1).
     */
    fun periodicVoronoi(x: Float, y: Float, cells: Int = 4, s: Long = seed): Triple<Float, Float, Float> {
        val cellSize = 32f / cells
        val cellX = floor(x / cellSize).toInt()
        val cellY = floor(y / cellSize).toInt()

        var d1 = 999f
        var d2 = 999f
        var bestHash = 0f

        for (oy in -1..1) {
            for (ox in -1..1) {
                val cx = (cellX + ox + cells) % cells
                val cy = (cellY + oy + cells) % cells

                val jx = (hash(cx, cy, s) - 0.5f) * 0.6f
                val jy = (hash(cx, cy, s + 999L) - 0.5f) * 0.6f

                // Center of the feature in tile space
                val fx = (cellX + ox + 0.5f + jx) * cellSize
                val fy = (cellY + oy + 0.5f + jy) * cellSize

                val dx = x - fx
                val dy = y - fy
                val dist = sqrt(dx * dx + dy * dy)

                if (dist < d1) {
                    d2 = d1
                    d1 = dist
                    bestHash = hash(cx, cy, s + 12345L)
                } else if (dist < d2) {
                    d2 = dist
                }
            }
        }
        return Triple(d1 / cellSize, d2 / cellSize, bestHash)
    }

    // ---- Tile Drawing Routines ----

    /**
     * Seamless Meadow Grass with organic micro-texture.
     */
    fun drawGrassTile(pm: TileImage, ox: Int, oy: Int, variant: GrassVariant = GrassVariant.BASE) {
        val s = seed + variant.ordinal * 1000L
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val n = wrappedFbm(px.toFloat(), py.toFloat(), octaves = 3, s = s)
                val c = when {
                    n < 0.35f -> grassShadow
                    n < 0.62f -> grassBase
                    n < 0.85f -> grassMid
                    else -> grassHighlight
                }
                pm.drawPixel(ox + px, oy + py, c)
            }
        }

        // Add detailed pixel art accents based on variant
        when (variant) {
            GrassVariant.BASE -> {
                // Subtle natural grass blades
                for (i in 0 until 14) {
                    val bx = ((i * 7 + 3) % 28) + 2
                    val by = ((i * 11 + 5) % 28) + 2
                    pm.drawPixel(ox + bx, oy + by, grassHighlight)
                    pm.drawPixel(ox + bx, oy + by - 1, grassSunlit)
                }
            }
            GrassVariant.TUFTS -> {
                // Dense vertical grass tufts
                val tuftLocations = listOf(
                    4 to 8, 12 to 6, 20 to 10, 26 to 5,
                    7 to 20, 16 to 18, 23 to 22, 10 to 27
                )
                for ((tx, ty) in tuftLocations) {
                    pm.drawPixel(ox + tx, oy + ty, grassSunlit)
                    pm.drawPixel(ox + tx, oy + ty + 1, grassHighlight)
                    pm.drawPixel(ox + tx - 1, oy + ty + 1, grassHighlight)
                    pm.drawPixel(ox + tx + 1, oy + ty + 1, grassMid)
                }
            }
            GrassVariant.WILDFLOWERS -> {
                // Wildflowers: yellow buttercups and red poppies
                val flowers = listOf(
                    Triple(6, 10, rgba(250, 204, 21)),
                    Triple(18, 7, rgba(244, 114, 182)),
                    Triple(24, 16, rgba(239, 68, 68)),
                    Triple(11, 22, rgba(250, 204, 21)),
                    Triple(22, 25, rgba(255, 255, 255))
                )
                for ((fx, fy, flowerCol) in flowers) {
                    pm.drawPixel(ox + fx, oy + fy, flowerCol)
                    pm.drawPixel(ox + fx, oy + fy + 1, grassShadow)
                    pm.drawPixel(ox + fx + 1, oy + fy, flowerCol)
                }
            }
            GrassVariant.CLOVER -> {
                // Clover / leafy patches
                val clovers = listOf(8 to 12, 21 to 14, 14 to 24)
                for ((cx, cy) in clovers) {
                    pm.drawPixel(ox + cx, oy + cy, grassSunlit)
                    pm.drawPixel(ox + cx - 1, oy + cy, grassSunlit)
                    pm.drawPixel(ox + cx + 1, oy + cy, grassSunlit)
                    pm.drawPixel(ox + cx, oy + cy - 1, grassHighlight)
                    pm.drawPixel(ox + cx, oy + cy + 1, grassShadow)
                }
            }
        }
    }

    /**
     * Seamless Packed Dirt Road / Earth.
     */
    fun drawDirtTile(pm: TileImage, ox: Int, oy: Int, withPebbles: Boolean = false) {
        val s = seed + 2000L
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val n = wrappedFbm(px.toFloat(), py.toFloat(), octaves = 3, s = s)
                val c = when {
                    n < 0.30f -> dirtDeep
                    n < 0.58f -> dirtShade
                    n < 0.82f -> dirtBase
                    else -> dirtLight
                }
                pm.drawPixel(ox + px, oy + py, c)
            }
        }
        if (withPebbles) {
            val pebbles = listOf(
                Pair(5, 7), Pair(18, 9), Pair(10, 19), Pair(25, 21), Pair(14, 26)
            )
            for ((px, py) in pebbles) {
                pm.drawPixel(ox + px, oy + py, dirtPebble)
                pm.drawPixel(ox + px + 1, oy + py, dirtLight)
                pm.drawPixel(ox + px, oy + py + 1, dirtDeep)
            }
        }
    }

    /**
     * Seamless Cobblestone Road using periodic Voronoi cells with bevels and mortar crevices.
     */
    fun drawCobbleRoadTile(pm: TileImage, ox: Int, oy: Int) {
        val s = seed + 3000L
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val (d1, d2, cellHash) = periodicVoronoi(px.toFloat(), py.toFloat(), cells = 4, s = s)
                val mortarDist = d2 - d1

                val c = if (mortarDist < 0.16f) {
                    mortarCrevice
                } else {
                    // Shading within the stone: bevel near edges, lighter at center
                    val bevel = (d1 * 2.2f).coerceIn(0f, 1f)
                    val tint = (cellHash - 0.5f) * 0.15f
                    val brightness = 1.0f - bevel * 0.6f + tint

                    when {
                        brightness > 0.85f -> stoneBevel
                        brightness > 0.65f -> stoneLight
                        brightness > 0.45f -> stoneBase
                        else -> stoneDark
                    }
                }
                pm.drawPixel(ox + px, oy + py, c)
            }
        }
    }

    /**
     * Animated Seamless Water (3 cyclic frames: frameIndex in 0..2).
     */
    fun drawWaterTile(pm: TileImage, ox: Int, oy: Int, frameIndex: Int = 0) {
        val s = seed + 4000L
        val phase = (frameIndex * 2f * PI.toFloat() / 3f)
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                // Periodic wave oscillation with wrapping caustic noise
                val wave1 = sin(px.toFloat() * 2f * PI.toFloat() / 16f + phase) * 0.25f
                val wave2 = cos(py.toFloat() * 2f * PI.toFloat() / 16f - phase) * 0.25f
                val caustic = wrappedFbm(px.toFloat() + frameIndex * 4f, py.toFloat(), octaves = 2, s = s) * 0.5f
                val v = (wave1 + wave2 + caustic + 0.5f).coerceIn(0f, 1f)

                val c = when {
                    v < 0.32f -> waterAbyss
                    v < 0.65f -> waterDeep
                    v < 0.82f -> waterMid
                    v < 0.94f -> waterCrest
                    else -> waterFoam
                }
                pm.drawPixel(ox + px, oy + py, c)
            }
        }
    }

    /**
     * Seamless Beach Sand with soft wind ripples.
     */
    fun drawSandTile(pm: TileImage, ox: Int, oy: Int) {
        val s = seed + 5000L
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val ripple = sin((px * 0.6f + py * 0.4f) * 2f * PI.toFloat() / 10.66f) * 0.2f
                val n = wrappedFbm(px.toFloat(), py.toFloat(), octaves = 3, s = s) * 0.8f + ripple + 0.1f
                val c = when {
                    n < 0.35f -> sandShadow
                    n < 0.65f -> sandBase
                    n < 0.88f -> sandLight
                    else -> sandGleam
                }
                pm.drawPixel(ox + px, oy + py, c)
            }
        }
    }

    /**
     * Crypt / Dungeon Stone Floor Tile.
     */
    fun drawCryptFloorTile(pm: TileImage, ox: Int, oy: Int) {
        val s = seed + 6000L
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val n = wrappedFbm(px.toFloat(), py.toFloat(), octaves = 2, s = s)
                val isSeam = (px == 0 || px == 31 || py == 0 || py == 31 || px == 16 || py == 16)
                val c = if (isSeam) {
                    mortarCrevice
                } else {
                    when {
                        n < 0.4f -> stoneDark
                        n < 0.75f -> stoneBase
                        else -> stoneLight
                    }
                }
                pm.drawPixel(ox + px, oy + py, c)
            }
        }
    }

    /**
     * Dungeon Brick Wall Tile.
     */
    fun drawDungeonWallTile(pm: TileImage, ox: Int, oy: Int) {
        val wallDark = rgba(24, 32, 47)
        val wallBase = rgba(40, 52, 75)
        val wallLight = rgba(71, 85, 105)
        val mortar = rgba(15, 23, 42)

        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val row = py / 8
                val col = if (row % 2 == 0) px / 16 else (px + 8) / 16
                val isMortar = (py % 8 == 0) || (if (row % 2 == 0) px % 16 == 0 else (px + 8) % 16 == 0)
                val c = if (isMortar) {
                    mortar
                } else {
                    val n = hash(px, py, seed + 7000L)
                    when {
                        py % 8 == 1 -> wallLight
                        py % 8 == 7 -> wallDark
                        n > 0.6f -> wallBase
                        else -> wallDark
                    }
                }
                pm.drawPixel(ox + px, oy + py, c)
            }
        }
    }

    /**
     * Ancient Oak Tree on transparent background (for MASK / FRINGE layer).
     */
    fun drawAncientOakTree(pm: TileImage, ox: Int, oy: Int) {
        // Trunk
        for (ty in 18 until 30) {
            for (tx in 12 until 20) {
                val wood = if (tx == 12 || tx == 19) woodDark else woodBase
                pm.drawPixel(ox + tx, oy + ty, wood)
            }
        }
        // Roots
        pm.drawPixel(ox + 11, oy + 29, woodDark)
        pm.drawPixel(ox + 20, oy + 29, woodDark)

        // Leafy Canopy (spherical procedural foliage)
        val canopyCenter = 16f to 13f
        val radius = 12f
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val dx = px - canopyCenter.first
                val dy = py - canopyCenter.second
                val dist = sqrt(dx * dx + dy * dy)
                if (dist <= radius) {
                    val n = hash(px, py, seed + 8000L)
                    val shade = (1f - dist / radius) * 0.6f + (n * 0.4f)
                    val c = when {
                        shade > 0.72f -> grassSunlit
                        shade > 0.52f -> grassHighlight
                        shade > 0.32f -> grassMid
                        else -> grassBase
                    }
                    pm.drawPixel(ox + px, oy + py, c)
                }
            }
        }
    }

    /**
     * Mossy Natural Boulder on transparent background.
     */
    fun drawBoulderTile(pm: TileImage, ox: Int, oy: Int) {
        val cx = 16f
        val cy = 18f
        val rx = 12f
        val ry = 10f
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val dx = (px - cx) / rx
                val dy = (py - cy) / ry
                val d2 = dx * dx + dy * dy
                if (d2 <= 1.0f) {
                    val n = hash(px, py, seed + 9000L)
                    val topBevel = (py - (cy - ry)) / (ry * 2f)
                    val c = when {
                        topBevel < 0.25f && n > 0.4f -> grassHighlight // Moss crest
                        topBevel < 0.35f -> stoneBevel
                        topBevel < 0.65f -> stoneLight
                        topBevel < 0.85f -> stoneBase
                        else -> stoneDark
                    }
                    pm.drawPixel(ox + px, oy + py, c)
                }
            }
        }
    }

    /**
     * Tree Stump with concentric growth rings on transparent background.
     */
    fun drawTreeStumpTile(pm: TileImage, ox: Int, oy: Int) {
        val cx = 16f
        val cy = 18f
        val radius = 10f
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val dx = px - cx
                val dy = py - cy
                val d = sqrt(dx * dx + dy * dy)
                if (d <= radius) {
                    val ring = (d % 3.0f) < 1.0f
                    val c = when {
                        d > radius - 1.5f -> woodDark // Outer bark
                        ring -> woodBase
                        else -> woodLight
                    }
                    pm.drawPixel(ox + px, oy + py, c)
                }
            }
        }
    }

    /**
     * Fallen Mossy Log on transparent background.
     */
    fun drawFallenLogTile(pm: TileImage, ox: Int, oy: Int) {
        for (py in 12 until 22) {
            for (px in 4 until 28) {
                val n = hash(px, py, seed + 9500L)
                val c = when {
                    py == 12 && n > 0.3f -> grassMid // moss on top
                    py == 12 || py == 21 -> woodDark
                    py < 16 -> woodLight
                    else -> woodBase
                }
                pm.drawPixel(ox + px, oy + py, c)
            }
        }
    }

    /**
     * Wooden Bridge Planks.
     */
    fun drawWoodenBridgeTile(pm: TileImage, ox: Int, oy: Int) {
        for (py in 0 until tileSize) {
            for (px in 2 until 30) {
                val isSeam = (py % 8 == 0)
                val isRail = (px == 2 || px == 3 || px == 28 || px == 29)
                val c = when {
                    isRail -> woodDark
                    isSeam -> woodDark
                    (py % 8) < 3 -> woodLight
                    else -> woodBase
                }
                pm.drawPixel(ox + px, oy + py, c)
            }
        }
    }

    /**
     * Roof Shingles.
     */
    fun drawRoofShinglesTile(pm: TileImage, ox: Int, oy: Int) {
        val shingleDark = rgba(124, 45, 18)
        val shingleBase = rgba(154, 52, 18)
        val shingleLight = rgba(194, 65, 12)
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val row = py / 8
                val isLip = py % 8 == 7
                val c = when {
                    isLip -> shingleDark
                    py % 8 < 2 -> shingleLight
                    else -> shingleBase
                }
                pm.drawPixel(ox + px, oy + py, c)
            }
        }
    }

    /**
     * Wooden Planks (Flooring).
     */
    fun drawWoodenPlanksTile(pm: TileImage, ox: Int, oy: Int) {
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val isSeam = (py % 8 == 0)
                val isNail = (px % 16 == 4 && (py % 8 == 2))
                val c = when {
                    isNail -> woodDark
                    isSeam -> woodDark
                    (py % 8) < 3 -> woodLight
                    else -> woodBase
                }
                pm.drawPixel(ox + px, oy + py, c)
            }
        }
    }

    /**
     * Tilled Farm Soil.
     */
    fun drawTilledSoilTile(pm: TileImage, ox: Int, oy: Int) {
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val isRidge = (py % 8 in 2..5)
                val c = if (isRidge) dirtShade else dirtDeep
                pm.drawPixel(ox + px, oy + py, c)
            }
        }
    }

    /**
     * Snow Tile.
     */
    fun drawSnowTile(pm: TileImage, ox: Int, oy: Int) {
        val s = seed + 10000L
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val n = wrappedFbm(px.toFloat(), py.toFloat(), octaves = 2, s = s)
                val c = when {
                    n < 0.35f -> snowShadow
                    n < 0.85f -> snowBase
                    else -> snowLight
                }
                pm.drawPixel(ox + px, oy + py, c)
            }
        }
    }

    /**
     * Ice Tile (Slippery with fractures).
     */
    fun drawIceTile(pm: TileImage, ox: Int, oy: Int) {
        val s = seed + 11000L
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val n = wrappedFbm(px.toFloat(), py.toFloat(), octaves = 2, s = s)
                val isCrack = (px + py == 20) || (px - py == 12)
                val c = when {
                    isCrack -> iceGlint
                    n < 0.3f -> iceDark
                    else -> iceBase
                }
                pm.drawPixel(ox + px, oy + py, c)
            }
        }
    }

    /**
     * Cliff Rock Face Wall (Top and Base).
     */
    fun drawCliffWallTile(pm: TileImage, ox: Int, oy: Int, isTop: Boolean) {
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val n = hash(px, py, seed + 12000L)
                val c = if (isTop && py < 6) {
                    grassBase // Grass crest overhang
                } else {
                    val stratum = (py + (n * 3).toInt()) % 6 == 0
                    when {
                        stratum -> stoneDark
                        n > 0.6f -> stoneLight
                        else -> stoneBase
                    }
                }
                pm.drawPixel(ox + px, oy + py, c)
            }
        }
    }

    /**
     * Autotile / Transition Edge Tile between Grass and Dirt.
     * Cardinal and corner blends: North (0), South (1), West (2), East (3), NW (4), NE (5), SW (6), SE (7).
     */
    fun drawGrassToDirtTransition(pm: TileImage, ox: Int, oy: Int, direction: Int) {
        // Draw underlying dirt
        drawDirtTile(pm, ox, oy, withPebbles = false)

        val s = seed + 13000L + direction * 50L
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val jitter = (hash(px, py, s) - 0.5f) * 4f
                val isGrass = when (direction) {
                    0 -> (py + jitter) > 16 // North dirt, south grass
                    1 -> (py + jitter) < 16 // South dirt, north grass
                    2 -> (px + jitter) > 16 // West dirt, east grass
                    3 -> (px + jitter) < 16 // East dirt, west grass
                    4 -> (px + jitter > 16) && (py + jitter > 16) // NW dirt
                    5 -> (px + jitter < 16) && (py + jitter > 16) // NE dirt
                    6 -> (px + jitter > 16) && (py + jitter < 16) // SW dirt
                    7 -> (px + jitter < 16) && (py + jitter < 16) // SE dirt
                    else -> false
                }
                if (isGrass) {
                    val n = wrappedFbm(px.toFloat(), py.toFloat(), octaves = 2, s = s)
                    val c = when {
                        n < 0.4f -> grassShadow
                        n < 0.7f -> grassBase
                        else -> grassHighlight
                    }
                    pm.drawPixel(ox + px, oy + py, c)
                }
            }
        }
    }

    /**
     * Builds the complete Starter / Overworld tileset image (16 columns x rows of 32x32).
     */
    fun generateTileset(columns: Int = 16, rows: Int = 3): TileImage {
        val pm = TileImage(columns * tileSize, rows * tileSize)

        fun tile(col: Int, row: Int, draw: (ox: Int, oy: Int) -> Unit) {
            if (col < columns && row < rows) {
                draw(col * tileSize, row * tileSize)
            }
        }

        // ================= ROW 0: Core Overworld & RPG Basics =================
        tile(0, 0) { ox, oy -> drawGrassTile(pm, ox, oy, GrassVariant.BASE) }
        tile(1, 0) { ox, oy -> drawCryptFloorTile(pm, ox, oy) }
        tile(2, 0) { ox, oy -> drawCobbleRoadTile(pm, ox, oy) }
        tile(3, 0) { ox, oy -> drawWaterTile(pm, ox, oy, frameIndex = 0) }
        tile(4, 0) { ox, oy -> drawAncientOakTree(pm, ox, oy) }
        tile(5, 0) { ox, oy -> // Copper ore
            drawBoulderTile(pm, ox, oy)
            val copper = rgba(249, 115, 22)
            pm.drawPixel(ox + 13, oy + 15, copper)
            pm.drawPixel(ox + 14, oy + 15, copper)
            pm.drawPixel(ox + 18, oy + 18, copper)
            pm.drawPixel(ox + 19, oy + 18, copper)
        }
        tile(6, 0) { ox, oy -> // Anvil
            val iron = rgba(113, 113, 122)
            val gold = rgba(250, 204, 21)
            for (y in 12 until 20) for (x in 6 until 26) pm.drawPixel(ox + x, oy + y, iron)
            for (y in 20 until 28) for (x in 10 until 22) pm.drawPixel(ox + x, oy + y, iron)
            for (x in 4 until 28) {
                pm.drawPixel(ox + x, oy + 4, gold)
                pm.drawPixel(ox + x, oy + 27, gold)
            }
        }
        tile(7, 0) { ox, oy -> // Chest
            val gold = rgba(250, 204, 21)
            for (y in 10 until 26) for (x in 5 until 27) pm.drawPixel(ox + x, oy + y, woodBase)
            for (x in 5 until 27) {
                pm.drawPixel(ox + x, oy + 10, gold)
                pm.drawPixel(ox + x, oy + 25, gold)
            }
            for (y in 10 until 26) {
                pm.drawPixel(ox + 5, oy + y, gold)
                pm.drawPixel(ox + 26, oy + y, gold)
            }
            for (y in 15 until 20) for (x in 14 until 18) pm.drawPixel(ox + x, oy + y, gold)
        }
        tile(8, 0) { ox, oy -> // Fountain
            val basin = rgba(148, 163, 184)
            val aqua = rgba(56, 189, 248)
            for (py in 0 until tileSize) for (px in 0 until tileSize) {
                val dx = px - 16
                val dy = py - 16
                val d = sqrt((dx * dx + dy * dy).toFloat())
                if (d <= 12f) pm.drawPixel(ox + px, oy + py, basin)
                if (d <= 8f) pm.drawPixel(ox + px, oy + py, aqua)
            }
        }
        tile(9, 0) { ox, oy -> // Brazier
            val iron = rgba(82, 82, 91)
            val flame = rgba(249, 115, 22)
            val core = rgba(253, 224, 71)
            for (y in 16 until 28) for (x in 13 until 19) pm.drawPixel(ox + x, oy + y, iron)
            for (py in 6 until 16) for (px in 10 until 22) {
                val dx = px - 16
                val dy = py - 12
                if (dx * dx + dy * dy <= 25) pm.drawPixel(ox + px, oy + py, flame)
                if (dx * dx + dy * dy <= 9) pm.drawPixel(ox + px, oy + py, core)
            }
        }
        tile(10, 0) { ox, oy -> // Portal
            val purple = rgba(126, 34, 206)
            val ring = rgba(192, 132, 252)
            val center = rgba(243, 232, 255)
            for (py in 0 until tileSize) for (px in 0 until tileSize) {
                val dx = px - 16
                val dy = py - 16
                val d = sqrt((dx * dx + dy * dy).toFloat())
                if (d <= 12f) pm.drawPixel(ox + px, oy + py, purple)
                if (d in 8f..10f) pm.drawPixel(ox + px, oy + py, ring)
                if (d <= 4f) pm.drawPixel(ox + px, oy + py, center)
            }
        }
        tile(11, 0) { ox, oy -> drawDungeonWallTile(pm, ox, oy) }
        tile(12, 0) { ox, oy -> // Locked Gate
            for (py in 2 until 30) for (px in 2 until 30) pm.drawPixel(ox + px, oy + py, woodDark)
            val gold = rgba(250, 204, 21)
            for (x in 2 until 30) {
                pm.drawPixel(ox + x, oy + 2, gold)
                pm.drawPixel(ox + x, oy + 29, gold)
            }
            for (py in 14 until 18) for (px in 14 until 18) pm.drawPixel(ox + px, oy + py, gold)
        }
        tile(13, 0) { ox, oy -> drawRoofShinglesTile(pm, ox, oy) }
        tile(14, 0) { ox, oy -> drawWoodenBridgeTile(pm, ox, oy) }
        tile(15, 0) { ox, oy -> // Shrub / Wildflower Bush
            drawGrassTile(pm, ox, oy, GrassVariant.WILDFLOWERS)
        }

        // ================= ROW 1: Animations, Winter, Biomes =================
        tile(0, 1) { ox, oy -> drawWaterTile(pm, ox, oy, frameIndex = 1) }
        tile(1, 1) { ox, oy -> drawWaterTile(pm, ox, oy, frameIndex = 2) }
        tile(2, 1) { ox, oy -> drawSnowTile(pm, ox, oy) }
        tile(3, 1) { ox, oy -> drawIceTile(pm, ox, oy) }
        tile(4, 1) { ox, oy -> // Snowy Pine
            drawSnowTile(pm, ox, oy)
            for (py in 4 until 26) {
                val w = (py - 4) / 2
                for (px in (16 - w)..(16 + w)) {
                    val c = if (py % 4 == 0) snowLight else grassShadow
                    pm.drawPixel(ox + px, oy + py, c)
                }
            }
        }
        tile(5, 1) { ox, oy -> drawSandTile(pm, ox, oy) }
        tile(6, 1) { ox, oy -> drawWoodenPlanksTile(pm, ox, oy) }
        tile(7, 1) { ox, oy -> // Arena Sand
            drawSandTile(pm, ox, oy)
            val border = rgba(127, 29, 29)
            for (i in 0 until 32) {
                pm.drawPixel(ox + i, oy, border)
                pm.drawPixel(ox + i, oy + 31, border)
                pm.drawPixel(ox, oy + i, border)
                pm.drawPixel(ox + 31, oy + i, border)
            }
        }
        tile(8, 1) { ox, oy -> // Cooking Fire
            val stone = rgba(82, 82, 91)
            val flame = rgba(234, 88, 12)
            for (py in 14 until 28) for (px in 8 until 24) {
                if ((px - 16) * (px - 16) + (py - 22) * (py - 22) <= 64) pm.drawPixel(ox + px, oy + py, stone)
            }
            for (py in 10 until 22) for (px in 12 until 20) pm.drawPixel(ox + px, oy + py, flame)
        }
        tile(9, 1) { ox, oy -> // Workbench
            for (y in 10 until 16) for (x in 3 until 29) pm.drawPixel(ox + x, oy + y, woodBase)
            for (y in 16 until 28) {
                for (x in 5 until 8) pm.drawPixel(ox + x, oy + y, woodDark)
                for (x in 24 until 27) pm.drawPixel(ox + x, oy + y, woodDark)
            }
        }
        tile(10, 1) { ox, oy -> // Fishing Spot
            drawWaterTile(pm, ox, oy, frameIndex = 0)
            val red = rgba(220, 38, 38)
            val white = rgba(255, 255, 255)
            pm.drawPixel(ox + 16, oy + 16, red)
            pm.drawPixel(ox + 16, oy + 15, white)
        }
        tile(11, 1) { ox, oy -> // Frost Crystal
            val cyan = rgba(56, 189, 248)
            val white = rgba(186, 230, 253)
            for (py in 4 until 26) {
                val half = (16 - abs(py - 15)).coerceAtLeast(0) / 2
                for (px in (16 - half)..(16 + half)) {
                    pm.drawPixel(ox + px, oy + py, if (px < 16) cyan else white)
                }
            }
        }
        tile(12, 1) { ox, oy -> // Wooden Fence
            for (x in 2 until 30) {
                pm.drawPixel(ox + x, oy + 12, woodBase)
                pm.drawPixel(ox + x, oy + 13, woodLight)
                pm.drawPixel(ox + x, oy + 20, woodBase)
                pm.drawPixel(ox + x, oy + 21, woodLight)
            }
            for (y in 8 until 28) {
                for (x in 4 until 8) pm.drawPixel(ox + x, oy + y, woodDark)
                for (x in 24 until 28) pm.drawPixel(ox + x, oy + y, woodDark)
            }
        }
        tile(13, 1) { ox, oy -> // Hot Spring
            drawCobbleRoadTile(pm, ox, oy)
            val steam = rgba(204, 251, 241)
            val pool = rgba(94, 234, 212)
            for (py in 4 until 28) for (px in 4 until 28) {
                val d2 = (px - 16) * (px - 16) + (py - 16) * (py - 16)
                if (d2 <= 100) pm.drawPixel(ox + px, oy + py, pool)
                if (d2 in 64..81 && px % 2 == 0) pm.drawPixel(ox + px, oy + py, steam)
            }
        }
        tile(14, 1) { ox, oy -> drawTilledSoilTile(pm, ox, oy) }
        tile(15, 1) { ox, oy -> // Wheat
            val stalk = rgba(202, 138, 4)
            val head = rgba(250, 204, 21)
            for (i in 0 until 5) {
                val x = 4 + i * 6
                for (y in 12 until 28) pm.drawPixel(ox + x, oy + y, stalk)
                for (y in 6 until 12) for (hx in (x - 1)..(x + 1)) pm.drawPixel(ox + hx, oy + y, head)
            }
        }

        // ================= ROW 2: Crops & Village Life =================
        tile(0, 2) { ox, oy -> // Turnip
            val leaf = rgba(34, 197, 94)
            val bulb = rgba(245, 243, 255)
            val purple = rgba(168, 85, 247)
            for (y in 8 until 16) {
                pm.drawPixel(ox + 10, oy + y, leaf)
                pm.drawPixel(ox + 20, oy + y, leaf)
            }
            for (py in 16 until 26) for (px in 8 until 24) {
                val d2 = (px - 16) * (px - 16) + (py - 20) * (py - 20)
                if (d2 <= 25) pm.drawPixel(ox + px, oy + py, if (py < 19) purple else bulb)
            }
        }
        tile(1, 2) { ox, oy -> // Pumpkin
            val orange = rgba(234, 88, 12)
            val darkOrange = rgba(194, 65, 12)
            val stem = rgba(21, 128, 61)
            for (y in 6 until 12) pm.drawPixel(ox + 16, oy + y, stem)
            for (py in 12 until 28) for (px in 8 until 25) {
                val d2 = (px - 16) * (px - 16) + (py - 20) * (py - 20)
                if (d2 <= 50) pm.drawPixel(ox + px, oy + py, if (px % 4 == 0) darkOrange else orange)
            }
        }
        tile(2, 2) { ox, oy -> // Tomato Vines
            val vine = rgba(22, 101, 52)
            val tomato = rgba(220, 38, 38)
            for (y in 4 until 28) pm.drawPixel(ox + 15, oy + y, vine)
            for (x in 8 until 24) pm.drawPixel(ox + x, oy + 14, vine)
            for (py in 16 until 22) for (px in 10 until 16) pm.drawPixel(ox + px, oy + py, tomato)
            for (py in 20 until 26) for (px in 18 until 24) pm.drawPixel(ox + px, oy + py, tomato)
        }
        tile(3, 2) { ox, oy -> // Berry Bush
            drawGrassTile(pm, ox, oy, GrassVariant.TUFTS)
            val berry = rgba(190, 24, 93)
            val berrySpots = listOf(10 to 12, 20 to 14, 14 to 20, 22 to 22, 8 to 22)
            for ((bx, by) in berrySpots) {
                pm.drawPixel(ox + bx, oy + by, berry)
                pm.drawPixel(ox + bx + 1, oy + by, berry)
            }
        }
        tile(4, 2) { ox, oy -> // Apple Tree
            drawAncientOakTree(pm, ox, oy)
            val red = rgba(220, 38, 38)
            val apples = listOf(12 to 10, 19 to 8, 16 to 14, 10 to 15, 21 to 14)
            for ((ax, ay) in apples) pm.drawPixel(ox + ax, oy + ay, red)
        }
        tile(5, 2) { ox, oy -> // Beehive
            val amber = rgba(245, 158, 11)
            val darkAmber = rgba(180, 83, 9)
            for (y in 10 until 24) for (x in 10 until 22) {
                pm.drawPixel(ox + x, oy + y, if (y % 4 == 0) darkAmber else amber)
            }
            for (y in 18 until 22) for (x in 14 until 18) pm.drawPixel(ox + x, oy + y, woodDark)
        }
        tile(6, 2) { ox, oy -> // Scarecrow
            val straw = rgba(250, 204, 21)
            for (y in 6 until 28) pm.drawPixel(ox + 16, oy + y, woodBase)
            for (x in 6 until 26) pm.drawPixel(ox + x, oy + 12, woodBase)
            for (y in 8 until 12) for (x in 14 until 18) pm.drawPixel(ox + x, oy + y, straw)
        }
        tile(7, 2) { ox, oy -> // Stone Well
            val stone = rgba(120, 113, 108)
            val water = rgba(29, 78, 216)
            for (py in 8 until 28) for (px in 6 until 26) {
                val d2 = (px - 16) * (px - 16) + (py - 18) * (py - 18)
                if (d2 <= 80) pm.drawPixel(ox + px, oy + py, stone)
                if (d2 <= 40) pm.drawPixel(ox + px, oy + py, water)
            }
            for (y in 4 until 18) {
                pm.drawPixel(ox + 7, oy + y, woodDark)
                pm.drawPixel(ox + 25, oy + y, woodDark)
            }
            for (x in 7 until 26) pm.drawPixel(ox + x, oy + 4, woodDark)
        }
        tile(8, 2) { ox, oy -> // Plank Wall
            for (py in 0 until tileSize) for (px in 0 until tileSize) {
                val isSeam = (px % 8 == 0)
                pm.drawPixel(ox + px, oy + py, if (isSeam) woodDark else woodBase)
            }
        }
        tile(9, 2) { ox, oy -> // Thatched Roof
            val straw = rgba(202, 138, 4)
            val strawLight = rgba(253, 224, 71)
            for (py in 0 until tileSize) for (px in 0 until tileSize) {
                val isStripe = (py % 6 == 0)
                pm.drawPixel(ox + px, oy + py, if (isStripe) strawLight else straw)
            }
        }
        tile(10, 2) { ox, oy -> // Hay Bale
            val hay = rgba(234, 179, 8)
            val cord = rgba(161, 98, 7)
            for (py in 8 until 26) for (px in 4 until 28) {
                val isCord = (px == 10 || px == 22)
                pm.drawPixel(ox + px, oy + py, if (isCord) cord else hay)
            }
        }
        tile(11, 2) { ox, oy -> // Bed
            val sheet = rgba(185, 28, 28)
            val pillow = rgba(254, 243, 199)
            for (py in 6 until 28) for (px in 5 until 27) {
                val c = when {
                    py < 12 -> pillow
                    else -> sheet
                }
                pm.drawPixel(ox + px, oy + py, c)
            }
        }
        tile(12, 2) { ox, oy -> // Baker's Oven
            val brick = rgba(124, 45, 18)
            val glow = rgba(249, 115, 22)
            for (py in 6 until 28) for (px in 4 until 28) pm.drawPixel(ox + px, oy + py, brick)
            for (py in 16 until 26) for (px in 10 until 22) pm.drawPixel(ox + px, oy + py, glow)
        }
        tile(13, 2) { ox, oy -> // Millstone
            val stone = rgba(120, 113, 108)
            for (py in 6 until 28) for (px in 4 until 28) {
                val d2 = (px - 16) * (px - 16) + (py - 17) * (py - 17)
                if (d2 <= 100) pm.drawPixel(ox + px, oy + py, stone)
            }
        }
        tile(14, 2) { ox, oy -> // Hen Coop
            for (py in 10 until 26) for (px in 6 until 26) pm.drawPixel(ox + px, oy + py, woodBase)
            val straw = rgba(234, 179, 8)
            for (px in 8 until 24) pm.drawPixel(ox + px, oy + 22, straw)
        }
        tile(15, 2) { ox, oy -> // Milking Trough
            for (py in 12 until 24) for (px in 4 until 28) pm.drawPixel(ox + px, oy + py, woodBase)
            val water = rgba(248, 250, 252)
            for (py in 14 until 20) for (px in 6 until 26) pm.drawPixel(ox + px, oy + py, water)
        }

        // ================= ROWS 3..5: Expanded Overworld Variations =================
        if (rows > 3) {
            // Row 3: Terrain variations and nature decals
            tile(0, 3) { ox, oy -> drawGrassTile(pm, ox, oy, GrassVariant.TUFTS) }
            tile(1, 3) { ox, oy -> drawGrassTile(pm, ox, oy, GrassVariant.WILDFLOWERS) }
            tile(2, 3) { ox, oy -> drawGrassTile(pm, ox, oy, GrassVariant.CLOVER) }
            tile(3, 3) { ox, oy -> drawDirtTile(pm, ox, oy, withPebbles = true) }
            tile(4, 3) { ox, oy -> drawTreeStumpTile(pm, ox, oy) }
            tile(5, 3) { ox, oy -> drawFallenLogTile(pm, ox, oy) }
            tile(6, 3) { ox, oy -> drawBoulderTile(pm, ox, oy) }
            tile(7, 3) { ox, oy -> // Small rock cluster
                drawGrassTile(pm, ox, oy, GrassVariant.BASE)
                val rock = rgba(100, 106, 118)
                val rockLight = rgba(145, 152, 168)
                listOf(8 to 12, 14 to 10, 22 to 18, 10 to 22).forEach { (rx, ry) ->
                    pm.drawPixel(ox + rx, oy + ry, rockLight)
                    pm.drawPixel(ox + rx + 1, oy + ry, rock)
                    pm.drawPixel(ox + rx, oy + ry + 1, rock)
                }
            }
            tile(8, 3) { ox, oy -> drawCliffWallTile(pm, ox, oy, isTop = true) }
            tile(9, 3) { ox, oy -> drawCliffWallTile(pm, ox, oy, isTop = false) }
            tile(10, 3) { ox, oy -> // Water with Lilypad
                drawWaterTile(pm, ox, oy, frameIndex = 0)
                val lily = rgba(34, 197, 94)
                val flower = rgba(244, 114, 182)
                for (py in 12 until 20) for (px in 12 until 20) {
                    if ((px - 16) * (px - 16) + (py - 16) * (py - 16) <= 12) pm.drawPixel(ox + px, oy + py, lily)
                }
                pm.drawPixel(ox + 16, oy + 16, flower)
            }
            tile(11, 3) { ox, oy -> // Water with Reeds
                drawWaterTile(pm, ox, oy, frameIndex = 0)
                val reed = rgba(21, 128, 61)
                val tip = rgba(133, 77, 14)
                for (i in 0 until 3) {
                    val rx = 8 + i * 7
                    for (ry in 12 until 28) pm.drawPixel(ox + rx, oy + ry, reed)
                    for (ry in 8 until 12) pm.drawPixel(ox + rx, oy + ry, tip)
                }
            }
            tile(12, 3) { ox, oy -> // Sand with Shells
                drawSandTile(pm, ox, oy)
                val shell = rgba(254, 243, 199)
                pm.drawPixel(ox + 8, oy + 12, shell)
                pm.drawPixel(ox + 20, oy + 18, shell)
            }
            tile(13, 3) { ox, oy -> // Campfire pit
                drawGrassTile(pm, ox, oy, GrassVariant.BASE)
                val stone = rgba(100, 106, 118)
                val ember = rgba(234, 88, 12)
                for (py in 10 until 24) for (px in 10 until 24) {
                    val d2 = (px - 17) * (px - 17) + (py - 17) * (py - 17)
                    if (d2 in 20..42) pm.drawPixel(ox + px, oy + py, stone)
                    if (d2 < 20) pm.drawPixel(ox + px, oy + py, ember)
                }
            }
            tile(14, 3) { ox, oy -> // Wooden Signpost
                drawGrassTile(pm, ox, oy, GrassVariant.BASE)
                for (y in 10 until 28) pm.drawPixel(ox + 16, oy + y, woodDark)
                for (y in 8 until 15) for (x in 10 until 24) pm.drawPixel(ox + x, oy + y, woodBase)
            }
            tile(15, 3) { ox, oy -> // Dense Hedge / Shrub
                for (py in 4 until 28) for (px in 4 until 28) {
                    val n = hash(px, py, seed + 14000L)
                    val c = when {
                        n < 0.3f -> grassShadow
                        n < 0.65f -> grassBase
                        n < 0.85f -> grassMid
                        else -> grassHighlight
                    }
                    pm.drawPixel(ox + px, oy + py, c)
                }
            }
        }

        if (rows > 4) {
            // Row 4: Grass-to-Dirt autotile transitions (8 directions: N, S, W, E, NW, NE, SW, SE)
            for (dir in 0 until 8) {
                tile(dir, 4) { ox, oy -> drawGrassToDirtTransition(pm, ox, oy, direction = dir) }
            }
        }

        return pm
    }

    enum class GrassVariant {
        BASE,
        TUFTS,
        WILDFLOWERS,
        CLOVER
    }
}

