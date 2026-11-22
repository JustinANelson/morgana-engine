package com.morgana.tilegen.structure

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelDither
import com.morgana.tilegen.material.PixelStyle

/**
 * Generates modular 3/4-perspective RPG cliff and wall tile sets from a top-surface
 * [topMaterial] and a vertical [faceMaterial].
 *
 * Produces 6 core modular tiles:
 * - `<name>_cap_left`, `<name>_cap_center`, `<name>_cap_right`
 * - `<name>_face_left`, `<name>_face_center`, `<name>_face_right`
 */
class StructureGenerator(
    val tileSize: Int = 32
) {
    init {
        require(tileSize >= 8 && tileSize % 2 == 0) {
            "tileSize must be an even integer >= 8, got $tileSize"
        }
    }

    /**
     * Generates a 6-tile modular 3/4-perspective cliff/wall set.
     */
    fun generateCliffSet(
        name: String,
        topMaterial: TileImage,
        faceMaterial: TileImage,
        style: PixelStyle = PixelStyle.STANDARD,
        castShadow: Boolean = true,
        trimColor: Int? = null
    ): List<NamedTile> {
        require(name.isNotBlank()) { "Cliff/wall name must not be blank" }
        require(topMaterial.width == tileSize && topMaterial.height == tileSize) {
            "topMaterial must be ${tileSize}x${tileSize}"
        }
        require(faceMaterial.width == tileSize && faceMaterial.height == tileSize) {
            "faceMaterial must be ${tileSize}x${tileSize}"
        }

        val lipHeight = (tileSize / 16).coerceAtLeast(1)
        val sideBorder = (tileSize / 16).coerceAtLeast(1)
        val overhangDepth = (tileSize / 8).coerceAtLeast(2)
        val aoDepth = (tileSize / 8).coerceAtLeast(2)

        val capLeft = buildCapTile(topMaterial, lipHeight, sideBorder, leftEdge = true, rightEdge = false, style = style)
        val capCenter = buildCapTile(topMaterial, lipHeight, sideBorder, leftEdge = false, rightEdge = false, style = style)
        val capRight = buildCapTile(topMaterial, lipHeight, sideBorder, leftEdge = false, rightEdge = true, style = style)

        val faceLeft = buildFaceTile(faceMaterial, overhangDepth, aoDepth, sideBorder, leftEdge = true, rightEdge = false, castShadow = castShadow, trimColor = trimColor, style = style)
        val faceCenter = buildFaceTile(faceMaterial, overhangDepth, aoDepth, sideBorder, leftEdge = false, rightEdge = false, castShadow = castShadow, trimColor = trimColor, style = style)
        val faceRight = buildFaceTile(faceMaterial, overhangDepth, aoDepth, sideBorder, leftEdge = false, rightEdge = true, castShadow = castShadow, trimColor = trimColor, style = style)

        return listOf(
            NamedTile("${name}_cap_left", capLeft),
            NamedTile("${name}_cap_center", capCenter),
            NamedTile("${name}_cap_right", capRight),
            NamedTile("${name}_face_left", faceLeft),
            NamedTile("${name}_face_center", faceCenter),
            NamedTile("${name}_face_right", faceRight)
        )
    }

    /**
     * Generates a 9-tile RMXP/Time Fantasy A4-style wall set with solid `#2b2632` void top-caps
     * and 2-tile-tall masonry faces (upper cornice tile + lower fluted plinth tile).
     */
    fun generateA4WallSet(
        name: String,
        topMaterial: TileImage,
        faceMaterial: TileImage,
        style: PixelStyle = PixelStyle.TIME_FANTASY,
        castShadow: Boolean = true,
        trimColor: Int? = null
    ): List<NamedTile> {
        require(name.isNotBlank()) { "Wall name must not be blank" }
        require(topMaterial.width == tileSize && topMaterial.height == tileSize) {
            "topMaterial must be ${tileSize}x${tileSize}"
        }
        require(faceMaterial.width == tileSize && faceMaterial.height == tileSize) {
            "faceMaterial must be ${tileSize}x${tileSize}"
        }

        val unit = (tileSize / 16).coerceAtLeast(1)
        val voidColor = PaletteRamp.rgba(0x2B, 0x26, 0x32)

        val voidLeft = buildA4VoidCapTile(topMaterial, unit, voidColor, leftEdge = true, rightEdge = false, style = style)
        val voidCenter = buildA4VoidCapTile(topMaterial, unit, voidColor, leftEdge = false, rightEdge = false, style = style)
        val voidRight = buildA4VoidCapTile(topMaterial, unit, voidColor, leftEdge = false, rightEdge = true, style = style)

        val topLeft = buildA4WallTopTile(faceMaterial, unit, leftEdge = true, rightEdge = false, trimColor = trimColor, style = style)
        val topCenter = buildA4WallTopTile(faceMaterial, unit, leftEdge = false, rightEdge = false, trimColor = trimColor, style = style)
        val topRight = buildA4WallTopTile(faceMaterial, unit, leftEdge = false, rightEdge = true, trimColor = trimColor, style = style)

        val botLeft = buildA4WallBotTile(faceMaterial, unit, voidColor, leftEdge = true, rightEdge = false, castShadow = castShadow, trimColor = trimColor, style = style)
        val botCenter = buildA4WallBotTile(faceMaterial, unit, voidColor, leftEdge = false, rightEdge = false, castShadow = castShadow, trimColor = trimColor, style = style)
        val botRight = buildA4WallBotTile(faceMaterial, unit, voidColor, leftEdge = false, rightEdge = true, castShadow = castShadow, trimColor = trimColor, style = style)

        return listOf(
            NamedTile("${name}_void_left", voidLeft),
            NamedTile("${name}_void_center", voidCenter),
            NamedTile("${name}_void_right", voidRight),
            NamedTile("${name}_top_left", topLeft),
            NamedTile("${name}_top_center", topCenter),
            NamedTile("${name}_top_right", topRight),
            NamedTile("${name}_bot_left", botLeft),
            NamedTile("${name}_bot_center", botCenter),
            NamedTile("${name}_bot_right", botRight)
        )
    }

    /**
     * Generates a 9-tile modular 3/4-perspective JRPG town building facade set (Suikoden II / Ragnarok Online style):
     * - `<name>_roof_left`, `<name>_roof_center`, `<name>_roof_right`: Pitched roof eaves with ridge cap & bottom lip bevel
     * - `<name>_wall_left`, `<name>_wall_center`, `<name>_wall_right`: Upper story wall with overhanging eave drop shadow & warm paned window
     * - `<name>_base_left`, `<name>_base_center`, `<name>_base_right`: Lower story wall with stone foundation plinth & ground AO
     */
    fun generateTownFacadeSet(
        name: String,
        roofMaterial: TileImage,
        wallMaterial: TileImage,
        style: PixelStyle = PixelStyle.SUIKODEN_32BIT,
        windowGlow: Boolean = true,
        timberColor: Int = PaletteRamp.rgba(104, 58, 38)
    ): List<NamedTile> {
        require(name.isNotBlank()) { "Facade name must not be blank" }
        require(roofMaterial.width == tileSize && roofMaterial.height == tileSize) {
            "roofMaterial must be ${tileSize}x${tileSize}"
        }
        require(wallMaterial.width == tileSize && wallMaterial.height == tileSize) {
            "wallMaterial must be ${tileSize}x${tileSize}"
        }
        val unit = (tileSize / 16).coerceAtLeast(1)

        val roofLeft = buildTownRoofTile(roofMaterial, unit, timberColor, leftEdge = true, rightEdge = false, style = style)
        val roofCenter = buildTownRoofTile(roofMaterial, unit, timberColor, leftEdge = false, rightEdge = false, style = style)
        val roofRight = buildTownRoofTile(roofMaterial, unit, timberColor, leftEdge = false, rightEdge = true, style = style)

        val wallLeft = buildTownWallUpperTile(wallMaterial, unit, timberColor, leftEdge = true, rightEdge = false, hasWindow = false, windowGlow = windowGlow, style = style)
        val wallCenter = buildTownWallUpperTile(wallMaterial, unit, timberColor, leftEdge = false, rightEdge = false, hasWindow = true, windowGlow = windowGlow, style = style)
        val wallRight = buildTownWallUpperTile(wallMaterial, unit, timberColor, leftEdge = false, rightEdge = true, hasWindow = false, windowGlow = windowGlow, style = style)

        val baseLeft = buildTownWallBaseTile(wallMaterial, unit, timberColor, leftEdge = true, rightEdge = false, style = style)
        val baseCenter = buildTownWallBaseTile(wallMaterial, unit, timberColor, leftEdge = false, rightEdge = false, style = style)
        val baseRight = buildTownWallBaseTile(wallMaterial, unit, timberColor, leftEdge = false, rightEdge = true, style = style)

        return listOf(
            NamedTile("${name}_roof_left", roofLeft),
            NamedTile("${name}_roof_center", roofCenter),
            NamedTile("${name}_roof_right", roofRight),
            NamedTile("${name}_wall_left", wallLeft),
            NamedTile("${name}_wall_center", wallCenter),
            NamedTile("${name}_wall_right", wallRight),
            NamedTile("${name}_base_left", baseLeft),
            NamedTile("${name}_base_center", baseCenter),
            NamedTile("${name}_base_right", baseRight)
        )
    }

    private fun buildTownRoofTile(
        roof: TileImage,
        unit: Int,
        timberColor: Int,
        leftEdge: Boolean,
        rightEdge: Boolean,
        style: PixelStyle
    ): TileImage {
        val out = roof.copy()
        val ridgeH = 2 * unit
        val eaveLipH = 2 * unit
        for (y in 0 until tileSize) {
            for (x in 0 until tileSize) {
                var px = out.getPixel(x, y)
                when {
                    y < unit -> px = shadePixel(timberColor, 0.55f)
                    y < ridgeH -> px = shadePixel(timberColor, 1.25f)
                    y >= tileSize - unit -> px = shadePixel(timberColor, 0.48f)
                    y >= tileSize - eaveLipH -> px = shadePixel(px, 1.22f)
                }
                if (leftEdge && x < 2 * unit) {
                    px = if (x < unit) shadePixel(timberColor, 0.55f) else shadePixel(timberColor, 1.15f)
                } else if (rightEdge && x >= tileSize - 2 * unit) {
                    px = if (x >= tileSize - unit) shadePixel(timberColor, 0.45f) else timberColor
                }
                out.setPixel(x, y, px)
            }
        }
        return if (style == PixelStyle.RETRO_8BIT) PixelDither.applyStyle(out, style, extractRamp(out)) else out
    }

    private fun buildTownWallUpperTile(
        wall: TileImage,
        unit: Int,
        timberColor: Int,
        leftEdge: Boolean,
        rightEdge: Boolean,
        hasWindow: Boolean,
        windowGlow: Boolean,
        style: PixelStyle
    ): TileImage {
        val out = wall.copy()
        val eaveShadowDepth = 4 * unit
        for (y in 0 until tileSize) {
            for (x in 0 until tileSize) {
                var px = out.getPixel(x, y)
                if (y < eaveShadowDepth) {
                    val shade = 0.50f + 0.38f * (y.toFloat() / eaveShadowDepth.coerceAtLeast(1))
                    px = shadePixel(px, shade)
                }
                if (leftEdge && x < 2 * unit) {
                    px = if (x < unit) shadePixel(timberColor, 0.52f) else timberColor
                } else if (rightEdge && x >= tileSize - 2 * unit) {
                    px = if (x >= tileSize - unit) shadePixel(timberColor, 0.45f) else shadePixel(timberColor, 0.85f)
                }
                out.setPixel(x, y, px)
            }
        }
        if (hasWindow) {
            val wx0 = tileSize / 4
            val wx1 = tileSize - tileSize / 4 - 1
            val wy0 = tileSize / 4 + unit
            val wy1 = tileSize - tileSize / 5
            val midX = (wx0 + wx1) / 2
            val midY = (wy0 + wy1) / 2
            val frameDark = shadePixel(timberColor, 0.52f)
            val glassLit = if (windowGlow) PaletteRamp.rgba(255, 224, 122) else PaletteRamp.rgba(128, 186, 232)
            val glassMid = if (windowGlow) PaletteRamp.rgba(236, 168, 64) else PaletteRamp.rgba(82, 134, 188)
            for (y in wy0..wy1) {
                for (x in wx0..wx1) {
                    val isFrame = x == wx0 || x == wx1 || y == wy0 || y == wy1 || x == midX || y == midY
                    val isSill = y == wy1
                    val col = when {
                        isSill -> shadePixel(timberColor, 1.18f)
                        isFrame -> frameDark
                        (x + y) % 3 == 0 -> glassLit
                        else -> glassMid
                    }
                    out.setPixel(x, y, col)
                }
            }
        }
        return if (style == PixelStyle.RETRO_8BIT) PixelDither.applyStyle(out, style, extractRamp(out)) else out
    }

    private fun buildTownWallBaseTile(
        wall: TileImage,
        unit: Int,
        timberColor: Int,
        leftEdge: Boolean,
        rightEdge: Boolean,
        style: PixelStyle
    ): TileImage {
        val out = wall.copy()
        val plinthStart = tileSize - 5 * unit
        val stoneMid = PaletteRamp.rgba(116, 112, 124)
        val stoneLit = PaletteRamp.rgba(164, 158, 168)
        val stoneDark = PaletteRamp.rgba(52, 46, 62)

        for (y in 0 until tileSize) {
            for (x in 0 until tileSize) {
                var px = out.getPixel(x, y)
                if (y >= tileSize - unit) {
                    px = stoneDark
                } else if (y >= plinthStart) {
                    val isMortar = y == plinthStart || ((x / (3 * unit)) + (y / (2 * unit))) % 3 == 0 && (x % (3 * unit) == 0)
                    px = when {
                        y == plinthStart -> shadePixel(timberColor, 0.58f)
                        y == plinthStart + unit -> stoneLit
                        isMortar -> stoneDark
                        else -> stoneMid
                    }
                }
                if (leftEdge && x < 2 * unit) {
                    px = if (x < unit) stoneDark else timberColor
                } else if (rightEdge && x >= tileSize - 2 * unit) {
                    px = if (x >= tileSize - unit) stoneDark else shadePixel(timberColor, 0.82f)
                }
                out.setPixel(x, y, px)
            }
        }
        return if (style == PixelStyle.RETRO_8BIT) PixelDither.applyStyle(out, style, extractRamp(out)) else out
    }

    private fun buildA4VoidCapTile(
        top: TileImage,
        unit: Int,
        voidColor: Int,
        leftEdge: Boolean,
        rightEdge: Boolean,
        style: PixelStyle
    ): TileImage {
        val out = top.copy()
        val rim = 3 * unit
        for (y in 0 until tileSize) {
            for (x in 0 until tileSize) {
                val inBottomRim = y >= tileSize - rim
                val inLeftRim = leftEdge && x < rim
                val inRightRim = rightEdge && x >= tileSize - rim
                if (!inBottomRim && !inLeftRim && !inRightRim) {
                    out.setPixel(x, y, voidColor)
                    continue
                }
                val px = out.getPixel(x, y)
                val isOuterDark = (inBottomRim && y >= tileSize - unit) ||
                    (inLeftRim && x < unit) ||
                    (inRightRim && x >= tileSize - unit)
                val isInnerBevel = (!isOuterDark) && (
                    (y == tileSize - rim && (!leftEdge || x >= rim - unit) && (!rightEdge || x < tileSize - rim + unit)) ||
                    (inLeftRim && x in (rim - unit) until rim && y <= tileSize - rim) ||
                    (inRightRim && x in (tileSize - rim) until (tileSize - rim + unit) && y <= tileSize - rim)
                )
                val adjusted = when {
                    isOuterDark -> shadePixel(px, 0.52f)
                    isInnerBevel -> shadePixel(px, 1.22f)
                    else -> px
                }
                out.setPixel(x, y, adjusted)
            }
        }
        return if (style == PixelStyle.RETRO_8BIT) {
            PixelDither.applyStyle(out, style, extractRamp(out))
        } else {
            out
        }
    }

    private fun buildA4WallTopTile(
        face: TileImage,
        unit: Int,
        leftEdge: Boolean,
        rightEdge: Boolean,
        trimColor: Int?,
        style: PixelStyle
    ): TileImage {
        val out = face.copy()
        val corniceStart = unit
        val corniceEnd = 4 * unit
        val overhangEnd = 5 * unit
        for (y in 0 until tileSize) {
            for (x in 0 until tileSize) {
                var px = out.getPixel(x, y)
                if (y < corniceStart) {
                    px = shadePixel(px, 0.48f)
                } else if (y in corniceStart until corniceEnd) {
                    val col = trimColor ?: px
                    val isDentil = (y >= corniceEnd - unit) && ((x / unit) % 2 == 1)
                    px = when {
                        y < corniceStart + unit -> shadePixel(col, 1.15f)
                        isDentil -> shadePixel(col, 0.65f)
                        y >= corniceEnd - unit -> shadePixel(col, 0.75f)
                        else -> col
                    }
                } else if (y < overhangEnd) {
                    px = shadePixel(px, 0.70f)
                }
                if (leftEdge && x < unit) {
                    px = shadePixel(px, 1.14f)
                } else if (rightEdge && x >= tileSize - unit) {
                    px = shadePixel(px, 0.58f)
                }
                out.setPixel(x, y, px)
            }
        }
        return if (style == PixelStyle.RETRO_8BIT) {
            PixelDither.applyStyle(out, style, extractRamp(out))
        } else {
            out
        }
    }

    private fun buildA4WallBotTile(
        face: TileImage,
        unit: Int,
        voidColor: Int,
        leftEdge: Boolean,
        rightEdge: Boolean,
        castShadow: Boolean,
        trimColor: Int?,
        style: PixelStyle
    ): TileImage {
        val out = face.copy()
        val plinthTop = tileSize - 5 * unit
        val fluteStart = tileSize - 4 * unit
        val baseOutline = tileSize - unit
        for (y in 0 until tileSize) {
            for (x in 0 until tileSize) {
                var px = out.getPixel(x, y)
                if (y >= baseOutline) {
                    px = if (castShadow) voidColor else shadePixel(px, 0.45f)
                } else if (y >= fluteStart) {
                    val isRidge = (x / unit) % 2 == 0
                    px = if (isRidge) {
                        trimColor ?: shadePixel(px, 1.08f)
                    } else {
                        shadePixel(px, 0.78f)
                    }
                } else if (y >= plinthTop) {
                    px = shadePixel(trimColor ?: px, 1.12f)
                } else if (castShadow && y >= plinthTop - unit) {
                    px = shadePixel(px, 0.84f)
                }
                if (leftEdge && x < unit) {
                    px = shadePixel(px, 1.14f)
                } else if (rightEdge && x >= tileSize - unit) {
                    px = shadePixel(px, 0.58f)
                }
                out.setPixel(x, y, px)
            }
        }
        return if (style == PixelStyle.RETRO_8BIT) {
            PixelDither.applyStyle(out, style, extractRamp(out))
        } else {
            out
        }
    }

    private fun buildCapTile(
        top: TileImage,
        lipHeight: Int,
        sideBorder: Int,
        leftEdge: Boolean,
        rightEdge: Boolean,
        style: PixelStyle
    ): TileImage {
        val out = top.copy()
        for (y in 0 until tileSize) {
            for (x in 0 until tileSize) {
                val px = out.getPixel(x, y)
                val isBottomLip = y >= tileSize - lipHeight
                val isHighlightRow = y == tileSize - lipHeight - 1
                val isLeft = leftEdge && x < sideBorder
                val isRight = rightEdge && x >= tileSize - sideBorder

                val adjusted = when {
                    isBottomLip || isRight -> shadePixel(px, 0.55f)
                    isHighlightRow || isLeft -> shadePixel(px, 1.18f)
                    else -> px
                }
                if (adjusted != px) {
                    out.setPixel(x, y, adjusted)
                }
            }
        }
        return if (style == PixelStyle.RETRO_8BIT) {
            PixelDither.applyStyle(out, style, extractRamp(out))
        } else {
            out
        }
    }

    private fun buildFaceTile(
        face: TileImage,
        overhangDepth: Int,
        aoDepth: Int,
        sideBorder: Int,
        leftEdge: Boolean,
        rightEdge: Boolean,
        castShadow: Boolean,
        trimColor: Int?,
        style: PixelStyle
    ): TileImage {
        val out = face.copy()
        val corniceStart = (tileSize / 16).coerceAtLeast(1)
        val corniceEnd = corniceStart + (tileSize / 8).coerceAtLeast(2)
        val baseTrimStart = tileSize - (tileSize / 5).coerceAtLeast(3)

        for (y in 0 until tileSize) {
            for (x in 0 until tileSize) {
                var px = out.getPixel(x, y)
                if (trimColor != null && y in corniceStart until corniceEnd) {
                    px = if (y == corniceStart || y == corniceEnd - 1) shadePixel(trimColor, 0.72f) else trimColor
                } else if (trimColor != null && y >= baseTrimStart) {
                    px = if (y == tileSize - 1) shadePixel(trimColor, 0.55f)
                    else if (x % 2 == 0) trimColor
                    else shadePixel(px, 0.92f)
                } else {
                    // Top overhang shadow from the cliff lip above
                    if (y < overhangDepth) {
                        val factor = 0.55f + 0.35f * (y.toFloat() / overhangDepth)
                        px = shadePixel(px, factor)
                    }
                    // Subtle ambient occlusion shadow at the bottom base of the cliff
                    if (castShadow && y >= tileSize - aoDepth) {
                        val distFromBottom = (tileSize - 1 - y).toFloat() / aoDepth
                        val factor = 0.65f + 0.3f * distFromBottom
                        px = shadePixel(px, factor)
                    }
                }
                // Side bevels for left/right cliff corners
                if (leftEdge && x < sideBorder) {
                    px = shadePixel(px, 1.12f)
                } else if (rightEdge && x >= tileSize - sideBorder) {
                    px = shadePixel(px, 0.60f)
                }
                out.setPixel(x, y, px)
            }
        }
        return if (style == PixelStyle.RETRO_8BIT) {
            PixelDither.applyStyle(out, style, extractRamp(out))
        } else {
            out
        }
    }

    private fun shadePixel(rgba: Int, factor: Float): Int {
        val a = rgba and 0xFF
        if (a == 0) return 0
        val r = (((rgba ushr 24) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((rgba ushr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = (((rgba ushr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (r shl 24) or (g shl 16) or (b shl 8) or a
    }

    private fun extractRamp(img: TileImage): PaletteRamp {
        val sorted = img.pixels
            .filter { (it and 0xFF) != 0 }
            .distinct()
            .sortedBy {
                val r = (it ushr 24) and 0xFF
                val g = (it ushr 16) and 0xFF
                val b = (it ushr 8) and 0xFF
                r * 3 + g * 4 + b * 2
            }
        if (sorted.size < 2) {
            val c = sorted.firstOrNull() ?: PaletteRamp.rgba(128, 128, 128)
            return PaletteRamp(intArrayOf(shadePixel(c, 0.5f), c))
        }
        return PaletteRamp(sorted.toIntArray()).limitColors(4)
    }
}
