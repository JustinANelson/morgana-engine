package com.morgana.gameart.whiteriver

import com.morgana.tilegen.*
import com.morgana.tilegen.material.*
import com.morgana.tilegen.spec.*
import com.morgana.tilegen.sprite.*
import com.morgana.tilegen.structure.*
import com.morgana.tilegen.export.*
import com.morgana.tilegen.autotile.*
import com.morgana.gameart.sprite.*
import com.morgana.gameart.export.*

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelDither
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.sprite.FacingDirection
import com.morgana.tilegen.sprite.SpriteAnimationClip

/**
 * Shared deterministic pixel-grid rendering and scaling utilities for White River (Black Desert demake)
 * dark medieval high-fantasy 16x16-native sprites, items, and skill VFX.
 */
internal object WhiteRiverSpriteUtils {

    /** Deep abyssal obsidian outline (`#1b1620`) for gritty BDO silhouettes. */
    val OUTLINE: Int = PaletteRamp.rgba(27, 22, 32)
    val SKIN_SHADOW: Int = PaletteRamp.rgba(142, 92, 68)
    val SKIN_MID: Int = PaletteRamp.rgba(198, 146, 112)
    val SKIN_LIGHT: Int = PaletteRamp.rgba(232, 188, 152)
    val EYE_DARK: Int = PaletteRamp.rgba(22, 18, 28)
    val EYE_WHITE: Int = PaletteRamp.rgba(238, 234, 228)
    val EYE_GLOW: Int = PaletteRamp.rgba(248, 198, 72)
    val EYE_CYAN: Int = PaletteRamp.rgba(96, 214, 232)
    val EYE_RED: Int = PaletteRamp.rgba(238, 42, 56)
    val STEEL_DARK: Int = PaletteRamp.rgba(54, 60, 74)
    val STEEL_MID: Int = PaletteRamp.rgba(116, 126, 144)
    val STEEL_LIGHT: Int = PaletteRamp.rgba(188, 198, 214)
    val GOLD_DARK: Int = PaletteRamp.rgba(118, 78, 24)
    val GOLD_MID: Int = PaletteRamp.rgba(184, 138, 48)
    val GOLD_LIGHT: Int = PaletteRamp.rgba(234, 198, 104)
    val WOOD_DARK: Int = PaletteRamp.rgba(68, 38, 20)
    val WOOD_MID: Int = PaletteRamp.rgba(122, 76, 40)
    val WOOD_LIGHT: Int = PaletteRamp.rgba(168, 116, 68)
    val CRIMSON_DARK: Int = PaletteRamp.rgba(92, 16, 28)
    val CRIMSON_MID: Int = PaletteRamp.rgba(168, 32, 48)
    val CRIMSON_LIGHT: Int = PaletteRamp.rgba(236, 78, 92)
    val EMERALD_DARK: Int = PaletteRamp.rgba(24, 68, 44)
    val EMERALD_MID: Int = PaletteRamp.rgba(48, 132, 78)
    val EMERALD_LIGHT: Int = PaletteRamp.rgba(112, 198, 132)
    val AZURE_MID: Int = PaletteRamp.rgba(52, 108, 196)
    val AZURE_LIGHT: Int = PaletteRamp.rgba(136, 194, 242)
    val VIOLET_MID: Int = PaletteRamp.rgba(104, 48, 164)
    val VIOLET_LIGHT: Int = PaletteRamp.rgba(184, 118, 236)
    val WHITE_PURE: Int = PaletteRamp.rgba(242, 238, 232)
    val SHADOW_GROUND: Int = PaletteRamp.rgba(14, 12, 22, 118)

    fun renderGrid(grid: List<String>, primary: PaletteRamp, accent: PaletteRamp): TileImage {
        val img = TileImage(16, 16)
        for (y in 0 until 16) {
            val row = grid[y]
            for (x in 0 until 16) {
                val ch = row[x]
                if (ch == '.') continue
                val color = when (ch) {
                    'o' -> OUTLINE
                    'd' -> primary.shadow
                    'm' -> primary.mid
                    'h' -> primary.highlight
                    'l' -> primary.colors.getOrElse(primary.colors.lastIndex - 1) { primary.highlight }
                    'a' -> accent.mid
                    'A' -> accent.highlight
                    'b' -> accent.shadow
                    'f' -> SKIN_MID
                    'F' -> SKIN_LIGHT
                    'k' -> SKIN_SHADOW
                    'e' -> EYE_DARK
                    'W' -> EYE_WHITE
                    'E' -> EYE_GLOW
                    'c' -> EYE_CYAN
                    'R' -> EYE_RED
                    's' -> STEEL_MID
                    'S' -> STEEL_LIGHT
                    'z' -> STEEL_DARK
                    'g' -> GOLD_MID
                    'G' -> GOLD_LIGHT
                    'y' -> GOLD_DARK
                    'w' -> WOOD_MID
                    'U' -> WOOD_LIGHT
                    'u' -> WOOD_DARK
                    'r' -> CRIMSON_MID
                    'p' -> CRIMSON_LIGHT
                    'q' -> CRIMSON_DARK
                    'v' -> EMERALD_MID
                    'V' -> EMERALD_LIGHT
                    't' -> AZURE_MID
                    'T' -> AZURE_LIGHT
                    'P' -> VIOLET_MID
                    'Q' -> VIOLET_LIGHT
                    'x' -> WHITE_PURE
                    '_' -> SHADOW_GROUND
                    else -> primary.mid
                }
                img.setPixel(x, y, color)
            }
        }
        return img
    }

    fun scaleNearest(src: TileImage, targetSize: Int): TileImage {
        if (src.width == targetSize && src.height == targetSize) return src
        val out = TileImage(targetSize, targetSize)
        for (y in 0 until targetSize) {
            val sy = (y * src.height) / targetSize
            for (x in 0 until targetSize) {
                val sx = (x * src.width) / targetSize
                out.setPixel(x, y, src.getPixel(sx, sy))
            }
        }
        return out
    }

    fun finalizeSprite(base16: TileImage, tileSize: Int, style: PixelStyle, palette: PaletteRamp): TileImage {
        val scaled = if (tileSize == 16) base16 else scaleNearest(base16, tileSize)
        return if (style == PixelStyle.RETRO_8BIT) {
            PixelDither.applyStyle(scaled, style, palette.limitColors(4))
        } else {
            scaled
        }
    }

    fun mirrorGridX(grid: List<String>): List<String> = grid.map { it.reversed() }

    fun applyStepBob(grid: List<String>, bobDown: Boolean = true): List<String> {
        val out = MutableList(16) { "................" }
        for (y in 0 until 16) {
            val srcY = if (bobDown) (y - 1).coerceAtLeast(0) else (y + 1).coerceAtMost(15)
            val chars = CharArray(16) { x ->
                val baseCh = grid[y][x]
                val shiftedCh = grid[srcY][x]
                when {
                    baseCh == '_' -> '_'
                    shiftedCh == '_' -> '.'
                    else -> shiftedCh
                }
            }
            out[y] = String(chars)
        }
        return out
    }

    /**
     * Applies a deterministic multi-frame BDO dark-action animation pose to a 16x16 character grid
     * for [clip] and [frameIndex], keeping ground shadows (`_`) anchored.
     */
    fun applyHumanoidClipFrame(
        grid: List<String>,
        direction: FacingDirection,
        clip: SpriteAnimationClip,
        frameIndex: Int
    ): List<String> {
        if (frameIndex == 0 && (clip == SpriteAnimationClip.IDLE || clip == SpriteAnimationClip.WALK)) {
            return grid
        }
        val rows = grid.map { it.toCharArray() }.toMutableList()
        when (clip) {
            SpriteAnimationClip.IDLE -> {
                if (frameIndex % 2 == 1) {
                    for (y in 8 downTo 2) {
                        rows[y] = rows[y - 1].copyOf()
                    }
                    // Subtle Black Spirit crimson ember on odd idle frame
                    if (rows[3][13] == '.') rows[3][13] = 'R'
                }
            }
            SpriteAnimationClip.WALK -> {
                when (frameIndex % 4) {
                    1 -> {
                        val temp = rows[12].copyOf()
                        rows[12] = rows[13].copyOf()
                        rows[13] = temp
                        for (x in 0..7) {
                            if (rows[13][x] != '.' && rows[13][x] != '_') {
                                rows[12][x] = rows[13][x]
                                rows[13][x] = '.'
                            }
                        }
                    }
                    2 -> {
                        for (y in 7..10) {
                            for (x in 1..14) {
                                if (rows[y][x] == 'a' && rows[y][x + 1] == '.') {
                                    rows[y][x + 1] = 'A'
                                }
                            }
                        }
                    }
                    3 -> {
                        val temp = rows[12].copyOf()
                        rows[12] = rows[13].copyOf()
                        rows[13] = temp
                        for (x in 8..15) {
                            if (rows[13][x] != '.' && rows[13][x] != '_') {
                                rows[12][x] = rows[13][x]
                                rows[13][x] = '.'
                            }
                        }
                    }
                }
            }
            SpriteAnimationClip.ATTACK -> {
                val dirDx = when (direction) {
                    FacingDirection.LEFT -> -1
                    FacingDirection.RIGHT -> 1
                    else -> 0
                }
                when (frameIndex % 4) {
                    0 -> shiftRowsHorizontal(rows, 2..12, -dirDx)
                    1 -> {
                        shiftRowsHorizontal(rows, 2..12, dirDx)
                        drawSlashArc(rows, direction, bright = true)
                    }
                    2 -> drawSlashArc(rows, direction, bright = false)
                    3 -> {
                        val temp = rows[11].copyOf()
                        rows[11] = rows[10].copyOf()
                        rows[10] = temp
                    }
                }
            }
            SpriteAnimationClip.CAST -> {
                val phase = frameIndex % 4
                if (phase == 1 || phase == 2) {
                    for (y in 1..12) {
                        rows[y] = rows[y + 1].copyOf()
                    }
                    rows[13] = "................".toCharArray()
                }
                val runeChar = if (phase % 2 == 0) 'R' else 'Q'
                val positions = listOf(
                    Pair(2, 10 - phase * 2),
                    Pair(13, 9 - phase * 2),
                    Pair(4, 3 + (phase % 2)),
                    Pair(11, 3 + ((phase + 1) % 2))
                )
                for ((rx, ry) in positions) {
                    if (ry in 1..13 && rx in 0..15 && rows[ry][rx] == '.') {
                        rows[ry][rx] = runeChar
                    }
                }
            }
            SpriteAnimationClip.HURT -> {
                if (frameIndex % 2 == 0) {
                    shiftRowsHorizontal(rows, 1..13, if (direction == FacingDirection.LEFT) 1 else -1)
                } else {
                    for (y in 2..12) {
                        for (x in 0..15) {
                            if (rows[y][x] == 'm' || rows[y][x] == 'd') rows[y][x] = 'h'
                        }
                    }
                }
            }
            SpriteAnimationClip.KO -> {
                if (frameIndex % 2 == 0) {
                    for (y in 13 downTo 4) {
                        rows[y] = rows[y - 3].copyOf()
                    }
                    for (y in 0..3) rows[y] = "................".toCharArray()
                } else {
                    val prone = Array(16) { CharArray(16) { '.' } }
                    prone[14] = rows[14].copyOf()
                    for (y in 3..12) {
                        for (x in 2..13) {
                            val ch = rows[y][x]
                            if (ch != '.' && ch != '_') {
                                val px = (2 + (y - 3) * 11 / 10).coerceIn(1, 14)
                                val py = (10 + (x - 2) * 3 / 11).coerceIn(10, 13)
                                prone[py][px] = ch
                            }
                        }
                    }
                    for (y in 0..15) rows[y] = prone[y]
                }
            }
        }
        return rows.map { String(it) }
    }

    fun applyEnemyClipFrame(
        grid: List<String>,
        clip: SpriteAnimationClip,
        frameIndex: Int
    ): List<String> {
        if (frameIndex == 0 && (clip == SpriteAnimationClip.IDLE || clip == SpriteAnimationClip.WALK)) {
            return grid
        }
        return when (clip) {
            SpriteAnimationClip.IDLE, SpriteAnimationClip.WALK -> {
                when (frameIndex % 4) {
                    0 -> grid
                    1 -> applyStepBob(grid, bobDown = true)
                    2 -> {
                        val rows = grid.map { it.toCharArray() }.toMutableList()
                        for (y in 11..13) {
                            val left = rows[y][5]
                            rows[y][5] = rows[y][6]
                            rows[y][6] = left
                            val right = rows[y][10]
                            rows[y][10] = rows[y][9]
                            rows[y][9] = right
                        }
                        rows.map { String(it) }
                    }
                    else -> applyStepBob(grid, bobDown = false)
                }
            }
            else -> applyHumanoidClipFrame(grid, FacingDirection.DOWN, clip, frameIndex)
        }
    }

    fun applyLootBobAndGlint(grid: List<String>, frameIndex: Int): List<String> {
        if (frameIndex == 0) return grid
        val bobbed = if (frameIndex % 2 == 1) applyStepBob(grid, bobDown = false) else grid
        val rows = bobbed.map { it.toCharArray() }.toMutableList()
        val glintBand = (frameIndex * 4) % 16
        for (y in 2..13) {
            for (x in 2..13) {
                if ((x + y) % 14 == glintBand && (rows[y][x] == 'm' || rows[y][x] == 'h' || rows[y][x] == 'g' || rows[y][x] == 's')) {
                    rows[y][x] = 'R'
                }
            }
        }
        return rows.map { String(it) }
    }

    private fun shiftRowsHorizontal(rows: MutableList<CharArray>, yRange: IntRange, dx: Int) {
        if (dx == 0) return
        for (y in yRange) {
            val next = CharArray(16) { '.' }
            for (x in 0..15) {
                val srcX = x - dx
                if (srcX in 0..15) next[x] = rows[y][srcX]
            }
            rows[y] = next
        }
    }

    private fun drawSlashArc(rows: MutableList<CharArray>, direction: FacingDirection, bright: Boolean) {
        val chMain = if (bright) 'R' else 'r'
        val chTrail = if (bright) 'P' else 'q'
        when (direction) {
            FacingDirection.DOWN -> {
                rows[13][5] = chTrail; rows[14][6] = chMain; rows[14][7] = chMain
                rows[14][8] = chMain; rows[14][9] = chMain; rows[13][10] = chTrail
            }
            FacingDirection.UP -> {
                rows[1][5] = chTrail; rows[0][6] = chMain; rows[0][7] = chMain
                rows[0][8] = chMain; rows[0][9] = chMain; rows[1][10] = chTrail
            }
            FacingDirection.LEFT -> {
                rows[5][1] = chTrail; rows[6][0] = chMain; rows[7][0] = chMain
                rows[8][0] = chMain; rows[9][0] = chMain; rows[10][1] = chTrail
            }
            FacingDirection.RIGHT -> {
                rows[5][14] = chTrail; rows[6][15] = chMain; rows[7][15] = chMain
                rows[8][15] = chMain; rows[9][15] = chMain; rows[10][14] = chTrail
            }
        }
    }
}
