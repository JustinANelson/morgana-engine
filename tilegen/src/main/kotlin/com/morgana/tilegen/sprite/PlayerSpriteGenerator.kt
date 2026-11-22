package com.morgana.tilegen.sprite

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelDither
import com.morgana.tilegen.material.PixelStyle

/**
 * 8 classic fantasy RPG / MMORPG player classes.
 */
enum class PlayerClass {
    KNIGHT,
    PALADIN,
    MAGE,
    CLERIC,
    RANGER,
    ROGUE,
    BERSERKER,
    NECROMANCER
}

/**
 * 4-way RPG character facing directions.
 */
enum class FacingDirection {
    DOWN,
    LEFT,
    RIGHT,
    UP
}

/**
 * Deterministic generator for 16x16-native RMXP-style player sprites
 * with selective `#2b2632` outlines and 4-directional facing support.
 */
class PlayerSpriteGenerator(
    val seed: Long = 1337L,
    val tileSize: Int = 16
) {
    init {
        require(tileSize >= 16 && tileSize % 2 == 0) {
            "tileSize must be an even integer >= 16, got $tileSize"
        }
    }

    /**
     * Generates [frameCount] frames for [playerClass] facing [direction].
     */
    fun generatePlayerFrames(
        name: String,
        playerClass: PlayerClass,
        direction: FacingDirection = FacingDirection.DOWN,
        frameCount: Int = 1,
        style: PixelStyle = PixelStyle.CLASSIC_RPG
    ): List<NamedTile> {
        require(name.isNotBlank()) { "Player sprite name must not be blank" }
        require(frameCount >= 1) { "frameCount must be >= 1, got $frameCount" }
        return (0 until frameCount).map { frameIdx ->
            val img = generatePlayerFrame(playerClass, direction, frameIdx, style)
            val suffix = if (frameCount == 1) "" else "_f$frameIdx"
            NamedTile("${name}_${direction.name.lowercase()}$suffix", img)
        }
    }

    /**
     * Generates a single 16x16-native (or nearest-neighbor scaled) frame for [playerClass].
     */
    fun generatePlayerFrame(
        playerClass: PlayerClass,
        direction: FacingDirection = FacingDirection.DOWN,
        frameIndex: Int = 0,
        style: PixelStyle = PixelStyle.CLASSIC_RPG
    ): TileImage {
        val (primary, accent) = palettesFor(playerClass, style)
        val rawGrid = templateFor(playerClass, direction)
        val steppedGrid = if (frameIndex % 2 == 1) applyWalkStep(rawGrid, direction) else rawGrid
        val base16 = renderGrid(steppedGrid, primary, accent)
        val scaled = if (tileSize == 16) base16 else scaleNearest(base16, tileSize)
        return if (style == PixelStyle.RETRO_8BIT) {
            PixelDither.applyStyle(scaled, style, primary.limitColors(4))
        } else {
            scaled
        }
    }

    /**
     * Generates an 8-column sheet containing all 8 [PlayerClass]es.
     * When [includeDirections] is true, emits 4 rows (DOWN, LEFT, RIGHT, UP) per frame.
     */
    fun generateAllPlayersSheet(
        includeDirections: Boolean = true,
        frameCount: Int = 1,
        style: PixelStyle = PixelStyle.CLASSIC_RPG
    ): Pair<TileImage, List<NamedTile>> {
        val dirs = if (includeDirections) FacingDirection.entries else listOf(FacingDirection.DOWN)
        val tiles = mutableListOf<NamedTile>()
        for (dir in dirs) {
            for (frame in 0 until frameCount) {
                for (cls in PlayerClass.entries) {
                    val img = generatePlayerFrame(cls, dir, frame, style)
                    val frameSuffix = if (frameCount == 1) "" else "_f$frame"
                    val dirSuffix = if (includeDirections) "_${dir.name.lowercase()}" else ""
                    tiles += NamedTile("${cls.name.lowercase()}$dirSuffix$frameSuffix", img)
                }
            }
        }
        val columns = 8
        val rows = (tiles.size + columns - 1) / columns
        val sheet = TileImage(columns * tileSize, rows * tileSize)
        tiles.forEachIndexed { idx, nt ->
            val col = idx % columns
            val row = idx / columns
            sheet.drawImage(nt.image, 0, 0, tileSize, tileSize, col * tileSize, row * tileSize)
        }
        return sheet to tiles
    }

    private fun palettesFor(playerClass: PlayerClass, style: PixelStyle): Pair<PaletteRamp, PaletteRamp> {
        val pair = when (playerClass) {
            PlayerClass.KNIGHT -> PalettePresets.CLASSIC_RPG_STONE to PalettePresets.RUINS_BLUE
            PlayerClass.PALADIN -> PalettePresets.RUINS_MARBLE to PalettePresets.RUINS_GOLD
            PlayerClass.MAGE -> PalettePresets.RUINS_BLUE to PalettePresets.RUINS_GOLD
            PlayerClass.CLERIC -> PalettePresets.RUINS_MARBLE to PalettePresets.RUINS_BLUE
            PlayerClass.RANGER -> PalettePresets.CLASSIC_RPG_GRASS to PalettePresets.CLASSIC_RPG_EARTH
            PlayerClass.ROGUE -> PalettePresets.RUINS_SLATE to PalettePresets.RUINS_TERRA
            PlayerClass.BERSERKER -> PalettePresets.RUINS_TERRA to PalettePresets.RUINS_GOLD
            PlayerClass.NECROMANCER -> PaletteRamp(
                intArrayOf(c(43, 38, 50), c(64, 42, 92), c(96, 62, 138), c(138, 96, 184), c(186, 148, 224))
            ) to PaletteRamp(
                intArrayOf(c(28, 72, 48), c(48, 132, 82), c(88, 206, 128), c(158, 248, 178))
            )
        }
        return if (style == PixelStyle.RETRO_8BIT) {
            pair.first.limitColors(4) to pair.second.limitColors(4)
        } else {
            pair
        }
    }

    private fun templateFor(playerClass: PlayerClass, direction: FacingDirection): List<String> {
        val down = when (playerClass) {
            PlayerClass.KNIGHT -> listOf(
                "......aaaa......",
                ".....dhhhhd.....",
                "....dhhllhhd....",
                "....dmddddmd....",
                "....dmfeeefd....",
                "....dmmFFmmo....",
                "...ddmmmmmmdd...",
                "..aaomlgglmoaa..",
                "..aamdllllmdam..",
                "..samdmmmmmdbs..",
                "..sbommmmmmobB..",
                "...o.dmddmd.o...",
                ".....dm..mo.....",
                ".....dB..Bo.....",
                ".....oo..oo.....",
                "................"
            )
            PlayerClass.PALADIN -> listOf(
                ".....gggggg.....",
                ".....dhhhhd.....",
                "....dhhllhhd....",
                "....dmfmmfmd....",
                "....dmfeefmd....",
                "....dmfFFfmo....",
                "...ddhggggdd....",
                "..ggomlaalmo.s..",
                "..ggmdlaalmd.s..",
                "..ggmdlaalmdbs..",
                "..ggommmmmmobB..",
                "...o.dmddmd.o...",
                ".....dm..mo.....",
                ".....dB..Bo.....",
                ".....oo..oo.....",
                "................"
            )
            PlayerClass.MAGE -> listOf(
                ".......dd.......",
                "......dhhd......",
                ".....dhhmmd.....",
                "...ddmmmmmmdd...",
                "..dggggggggggo..",
                "....dfeffefo....",
                "....dffFFffo.c..",
                "...ddmggggmd.g..",
                "..dhhmllllmddB..",
                "..dmmmlgglmmmb..",
                "..dmomllllmomb..",
                "..do.dmmmmmo.b..",
                ".....dmmmmmo.o..",
                ".....dB..Bo.....",
                ".....oo..oo.....",
                "................"
            )
            PlayerClass.CLERIC -> listOf(
                ".....gggggg.....",
                "....dhhhhhhd....",
                "....dhhffhhd....",
                "....dfeffefo....",
                "....dffFFffo....",
                "...ddhggggdd.c..",
                "..dhhmaaaamd.g..",
                "..dhhmaggamd.b..",
                "..dmmmaaaammmb..",
                "..dmomllllmomb..",
                "..do.dmmmmmo.b..",
                ".....dmmmmmo.o..",
                ".....dmggmmo....",
                ".....dB..Bo.....",
                ".....oo..oo.....",
                "................"
            )
            PlayerClass.RANGER -> listOf(
                ".....dddddd.....",
                "....dhhhhhhd....",
                "....dhllllhd....",
                "....dfeffefo....",
                "....dffFFffo....",
                "...ddmbbbbdd..b.",
                "..dhhmbbbbmdd.b.",
                "..dmmmbggbmmo.s.",
                "..dmombBBbmob.b.",
                "..do.dmmmmmo..b.",
                ".....dmmmmmo....",
                ".....db..bo.....",
                ".....db..bo.....",
                ".....dB..Bo.....",
                ".....oo..oo.....",
                "................"
            )
            PlayerClass.ROGUE -> listOf(
                ".....dddddd.....",
                "....dmmmmmmmd...",
                "....dmmddmmmd...",
                "....dfeffefo....",
                "....daaaaaao....",
                "...ddmmAAmmdd...",
                "..dmmmdmmmdmmo..",
                "..smodmmmmoms...",
                "..B..dmmmmmo..B.",
                ".....dmbBbmmo...",
                ".....dm..mo.....",
                ".....dm..mo.....",
                ".....dB..Bo.....",
                ".....oo..oo.....",
                "................",
                "................"
            )
            PlayerClass.BERSERKER -> listOf(
                "...w........w...",
                "...wwddddddww...",
                "....dhhhhhhd....",
                "....dfeffefo....",
                "....dmbFFbmo....",
                "..wwdmbggbmdww..",
                "..dhhffffffhhd.s",
                "..dmmfFFFFfmmoSs",
                "..b..dmBBmd..obs",
                ".....dmBBmo...b.",
                ".....db..bo...b.",
                ".....db..bo.....",
                ".....dB..Bo.....",
                ".....oo..oo.....",
                "................",
                "................"
            )
            PlayerClass.NECROMANCER -> listOf(
                ".....ww..ww.....",
                "....dhhhhhhd....",
                "....dmmddmmd....",
                "....ddecceco....",
                "....dmmddmmo....",
                "...ddmccccmdd.c.",
                "..dhhmllllmmd.b.",
                "..dmmmccccmmmob.",
                "..dmomllllmomb..",
                "..do.dmmmmmo.b..",
                ".....dmmmmmo.o..",
                ".....dmmmmmo....",
                ".....dB..Bo.....",
                ".....oo..oo.....",
                "................",
                "................"
            )
        }

        return when (direction) {
            FacingDirection.DOWN -> down
            FacingDirection.RIGHT -> mirrorHorizontal(toSideProfile(down, playerClass))
            FacingDirection.LEFT -> toSideProfile(down, playerClass)
            FacingDirection.UP -> toBackView(down, playerClass)
        }
    }

    private fun toSideProfile(down: List<String>, playerClass: PlayerClass): List<String> {
        return down.mapIndexed { y, row ->
            val chars = CharArray(16) { '.' }
            // Shift slightly toward left-facing profile (columns 4..11)
            for (x in 3..12) {
                val src = row[x]
                if (src != '.') {
                    chars[x] = src
                }
            }
            // In left-facing profile, only the left eye is visible and right side is hair/helmet
            if (y in 3..5) {
                for (x in 8..11) {
                    if (chars[x] == 'f' || chars[x] == 'F' || chars[x] == 'e') {
                        chars[x] = if (playerClass == PlayerClass.BERSERKER || playerClass == PlayerClass.RANGER) 'b' else 'm'
                    }
                }
                if (y == 4 && chars[5] == 'f') chars[5] = 'e'
            }
            // Narrow stance in lower legs
            if (y in 12..14) {
                for (x in 9..11) chars[x] = '.'
            }
            String(chars)
        }
    }

    private fun toBackView(down: List<String>, playerClass: PlayerClass): List<String> {
        return down.mapIndexed { y, row ->
            val chars = row.toCharArray()
            if (y in 3..6) {
                for (x in chars.indices) {
                    if (chars[x] == 'f' || chars[x] == 'F' || chars[x] == 'e') {
                        chars[x] = when (playerClass) {
                            PlayerClass.KNIGHT, PlayerClass.PALADIN -> 'm'
                            PlayerClass.RANGER, PlayerClass.BERSERKER -> 'b'
                            else -> 'd'
                        }
                    }
                }
            }
            // Add back cloak/cape accent on torso rows
            if (y in 7..11) {
                for (x in 5..10) {
                    if (chars[x] != '.') {
                        chars[x] = when (playerClass) {
                            PlayerClass.KNIGHT, PlayerClass.PALADIN -> 'a'
                            PlayerClass.MAGE, PlayerClass.NECROMANCER -> 'm'
                            else -> 'd'
                        }
                    }
                }
            }
            String(chars)
        }
    }

    private fun mirrorHorizontal(grid: List<String>): List<String> =
        grid.map { it.reversed() }

    private fun applyWalkStep(grid: List<String>, direction: FacingDirection): List<String> {
        val out = grid.toMutableList()
        // Subtle 1px walk stride: shift bottom 3 rows alternating left/right
        for (y in 12 until out.size) {
            val row = out[y]
            out[y] = if (direction == FacingDirection.RIGHT) {
                row.drop(1) + "."
            } else {
                "." + row.dropLast(1)
            }
        }
        return out
    }

    private fun renderGrid(grid: List<String>, primary: PaletteRamp, accent: PaletteRamp): TileImage {
        val img = TileImage(16, 16)
        val outline = c(0x2B, 0x26, 0x32)
        val skinMid = c(0xE8, 0xB8, 0x96)
        val skinShadow = c(0xC4, 0x88, 0x68)
        val whiteLit = c(0xEC, 0xE8, 0xDC)
        val whiteDark = c(0xB8, 0xB4, 0xA8)
        val goldLit = c(0xE0, 0xB2, 0x48)
        val goldDark = c(0x9E, 0x6C, 0x1E)
        val leatherMid = c(0x7A, 0x4A, 0x28)
        val leatherDark = c(0x4A, 0x2A, 0x18)
        val steelLit = c(0xC6, 0xD0, 0xDC)
        val steelDark = c(0x6E, 0x7A, 0x8C)
        val primaryLit = primary.sample(0.75f)

        for (y in 0 until 16) {
            val row = grid[y]
            for (x in 0 until 16) {
                val color = when (row[x]) {
                    '.' -> 0
                    'o', 'e' -> outline
                    'f' -> skinMid
                    'F' -> skinShadow
                    'w' -> whiteLit
                    'W' -> whiteDark
                    'g' -> goldLit
                    'G' -> goldDark
                    'b' -> leatherMid
                    'B' -> leatherDark
                    's' -> steelLit
                    'S' -> steelDark
                    'd' -> primary.shadow
                    'm' -> primary.mid
                    'l' -> primaryLit
                    'h' -> primary.highlight
                    'a' -> accent.mid
                    'A' -> accent.shadow
                    'c' -> accent.highlight
                    else -> 0
                }
                if (color != 0) img.setPixel(x, y, color)
            }
        }
        return img
    }

    private fun scaleNearest(src: TileImage, targetSize: Int): TileImage {
        val out = TileImage(targetSize, targetSize)
        for (y in 0 until targetSize) {
            val sy = (y * src.height) / targetSize
            for (x in 0 until targetSize) {
                val sx = (x * src.width) / targetSize
                val px = src.getPixel(sx, sy)
                if ((px and 0xFF) != 0) out.setPixel(x, y, px)
            }
        }
        return out
    }

    private fun c(r: Int, g: Int, b: Int): Int = PaletteRamp.rgba(r, g, b)
}
