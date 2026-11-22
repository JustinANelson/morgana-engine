package com.morgana.tilegen.whiteriver

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Iconic Black Desert Online active, awakening, and Black Spirit Rage skills
 * for the White River 2D Time Fantasy demake.
 */
enum class WhiteRiverSkillKind {
    SPINNING_SLASH,
    SOLAR_FLARE,
    SHOTGUN_ARROW,
    DESCENDING_CURRENT,
    DARK_FLAME,
    VIOLATION,
    BEASTLY_WIND_SLASH,
    ANCIENT_WAVE,
    CELESTIAL_SPEAR,
    SACRED_ENSLAVEMENT,
    METEOR_SHOWER,
    BLIZZARD,
    CHAIN_LIGHTNING,
    BLOOMING,
    RISING_STORM,
    WHEEL_OF_FORTUNE,
    SPIRIT_LEGACY,
    TWIRL_THREE,
    SUN_MOON_STARS,
    BLACK_SPIRIT_RAGE,
    EMERGENCY_ESCAPE,
    HEALING_AURA,
    FROST_PILLAR,
    MAGMA_ERUPTION
}

/**
 * Deterministic generator for 16x16-native Time Fantasy skill VFX frames and icons
 * for the White River (Black Desert Online demake).
 */
class WhiteRiverSkillGenerator(
    val seed: Long = 1337L,
    val tileSize: Int = 16
) {
    init {
        require(tileSize >= 16 && tileSize % 2 == 0) {
            "tileSize must be an even integer >= 16, got $tileSize"
        }
    }

    fun generateSkillFrames(
        name: String,
        kind: WhiteRiverSkillKind,
        frameCount: Int = 4,
        style: PixelStyle = PixelStyle.TIME_FANTASY
    ): List<NamedTile> {
        require(name.isNotBlank()) { "Skill name must not be blank" }
        require(frameCount >= 1) { "frameCount must be >= 1, got $frameCount" }
        return (0 until frameCount).map { frameIdx ->
            val img = generateSkillFrame(kind, frameIdx, frameCount, style)
            val tileName = if (frameCount == 1) name else "${name}_f$frameIdx"
            NamedTile(tileName, img)
        }
    }

    fun generateSkillFrame(
        kind: WhiteRiverSkillKind,
        frameIndex: Int = 0,
        totalFrames: Int = 4,
        style: PixelStyle = PixelStyle.TIME_FANTASY
    ): TileImage {
        val pal = paletteFor(kind, style)
        val t = if (totalFrames <= 1) 0.5f else frameIndex.toFloat() / (totalFrames - 1).toFloat()
        val base16 = renderSkill16(kind, t, frameIndex, pal)
        return WhiteRiverSpriteUtils.finalizeSprite(base16, tileSize, style, pal)
    }

    fun generateAllSkillsSheet(
        frameCount: Int = 4,
        style: PixelStyle = PixelStyle.TIME_FANTASY
    ): Pair<TileImage, List<NamedTile>> {
        val tiles = WhiteRiverSkillKind.entries.flatMap { kind ->
            generateSkillFrames(kind.name.lowercase(), kind, frameCount, style)
        }
        val columns = frameCount.coerceAtLeast(1).coerceAtMost(8)
        val rows = (tiles.size + columns - 1) / columns
        val sheet = TileImage(columns * tileSize, rows * tileSize)
        tiles.forEachIndexed { idx, nt ->
            val col = idx % columns
            val row = idx / columns
            sheet.drawImage(nt.image, 0, 0, tileSize, tileSize, col * tileSize, row * tileSize)
        }
        return sheet to tiles
    }

    private fun paletteFor(kind: WhiteRiverSkillKind, style: PixelStyle): PaletteRamp {
        val base = when (kind) {
            WhiteRiverSkillKind.SPINNING_SLASH,
            WhiteRiverSkillKind.BLOOMING -> PalettePresets.WR_BLACK_SPIRIT
            WhiteRiverSkillKind.SOLAR_FLARE,
            WhiteRiverSkillKind.METEOR_SHOWER,
            WhiteRiverSkillKind.MAGMA_ERUPTION -> PalettePresets.LAVA_MAGMA
            WhiteRiverSkillKind.SHOTGUN_ARROW,
            WhiteRiverSkillKind.DESCENDING_CURRENT,
            WhiteRiverSkillKind.HEALING_AURA -> PalettePresets.WR_KAMASYLVE_LEAF
            WhiteRiverSkillKind.DARK_FLAME,
            WhiteRiverSkillKind.VIOLATION,
            WhiteRiverSkillKind.WHEEL_OF_FORTUNE,
            WhiteRiverSkillKind.SPIRIT_LEGACY,
            WhiteRiverSkillKind.EMERGENCY_ESCAPE -> PalettePresets.WR_ABYSSAL_OBSIDIAN
            WhiteRiverSkillKind.BEASTLY_WIND_SLASH,
            WhiteRiverSkillKind.CELESTIAL_SPEAR,
            WhiteRiverSkillKind.SACRED_ENSLAVEMENT,
            WhiteRiverSkillKind.SUN_MOON_STARS -> PalettePresets.WR_VALENCIA_AMBER
            WhiteRiverSkillKind.ANCIENT_WAVE,
            WhiteRiverSkillKind.CHAIN_LIGHTNING,
            WhiteRiverSkillKind.RISING_STORM -> PalettePresets.WR_SERENDIA_IRON
            WhiteRiverSkillKind.BLIZZARD,
            WhiteRiverSkillKind.FROST_PILLAR -> PalettePresets.FROST_SNOW
            WhiteRiverSkillKind.TWIRL_THREE -> PalettePresets.WR_KAMASYLVE_LEAF
            WhiteRiverSkillKind.BLACK_SPIRIT_RAGE -> PalettePresets.WR_BLACK_SPIRIT
        }
        return if (style == PixelStyle.RETRO_8BIT) base.limitColors(4) else base
    }

    private fun renderSkill16(kind: WhiteRiverSkillKind, t: Float, frameIdx: Int, pal: PaletteRamp): TileImage {
        val img = TileImage(16, 16)
        val cx = 7.5f
        val cy = 7.5f
        val coreWhite = WhiteRiverSpriteUtils.WHITE_PURE
        val spiritRed = WhiteRiverSpriteUtils.EYE_RED
        val spiritViolet = WhiteRiverSpriteUtils.VIOLET_LIGHT
        val outline = WhiteRiverSpriteUtils.TF_OUTLINE

        when (kind) {
            WhiteRiverSkillKind.SPINNING_SLASH,
            WhiteRiverSkillKind.BLOOMING,
            WhiteRiverSkillKind.WHEEL_OF_FORTUNE -> {
                // Heavy BDO Awakening Greatsword / Kriegsmesser crescent cleave arc with crimson-obsidian sparks
                val rot = (t - 0.5f) * 2.2f
                for (y in 0 until 16) {
                    for (x in 0 until 16) {
                        val dx = x - cx
                        val dy = y - cy
                        val r = hypot(dx, dy)
                        val ang = kotlin.math.atan2(dy, dx)
                        val inArc = r in 3.2f..7.2f && abs(ang - rot) <= 1.35f
                        val crossSlash = kind == WhiteRiverSkillKind.WHEEL_OF_FORTUNE && abs(dx + dy) <= 1.1f && r <= 6.5f
                        if (inArc || crossSlash) {
                            img.setPixel(x, y, when {
                                r > 5.8f -> coreWhite
                                r > 4.4f -> pal.highlight
                                else -> spiritRed
                            })
                        }
                    }
                }
            }
            WhiteRiverSkillKind.SOLAR_FLARE,
            WhiteRiverSkillKind.BEASTLY_WIND_SLASH -> {
                // Directional ground-shatter shockwave cone with flying debris
                val reach = 4f + t * 9f
                for (y in 2..13) {
                    for (x in 2..14) {
                        val coneHalfH = 1.0f + (x - 2) * 0.45f
                        val dy = abs(y - cy)
                        if (x <= reach && dy <= coneHalfH) {
                            val nearFront = abs(x - reach) <= 1.6f
                            img.setPixel(x, y, when {
                                nearFront -> coreWhite
                                (x + y + frameIdx) % 3 == 0 -> pal.highlight
                                else -> pal.mid
                            })
                        }
                    }
                }
            }
            WhiteRiverSkillKind.BLACK_SPIRIT_RAGE,
            WhiteRiverSkillKind.DARK_FLAME,
            WhiteRiverSkillKind.EMERGENCY_ESCAPE -> {
                // Black Spirit abyssal vortex + glowing red eyes + 3 diagonal claw slashes
                val pulseR = 4.5f + (frameIdx % 2) * 0.8f
                for (y in 0 until 16) {
                    for (x in 0 until 16) {
                        val dx = x - cx
                        val dy = y - cy
                        val r = hypot(dx, dy)
                        val isClaw = abs((x - y) - 4) <= 0 || abs(x - y) <= 0 || abs((x - y) + 4) <= 0
                        if (r <= pulseR) {
                            val isEye = y == 6 && (x == 5 || x == 10)
                            val col = when {
                                isEye -> coreWhite
                                isClaw && r <= 6.2f -> spiritRed
                                (x + y + frameIdx) % 2 == 0 -> pal.highlight
                                else -> pal.shadow
                            }
                            img.setPixel(x, y, col)
                        }
                    }
                }
            }
            WhiteRiverSkillKind.VIOLATION,
            WhiteRiverSkillKind.RISING_STORM,
            WhiteRiverSkillKind.TWIRL_THREE,
            WhiteRiverSkillKind.BLIZZARD -> {
                // Scythe / Florang / Lightning 3-arm dark-action reaper spin
                val angleShift = frameIdx * (PI.toFloat() / 2f)
                for (y in 0 until 16) {
                    for (x in 0 until 16) {
                        val dx = x - cx
                        val dy = y - cy
                        val r = hypot(dx, dy)
                        if (r in 1.4f..7.2f) {
                            val ang = kotlin.math.atan2(dy, dx) + r * 0.55f + angleShift
                            val wave = sin(ang * 3f)
                            if (wave > 0.22f) {
                                img.setPixel(x, y, if (wave > 0.72f) coreWhite else if (kind == WhiteRiverSkillKind.VIOLATION) spiritViolet else pal.highlight)
                            }
                        }
                    }
                }
            }
            WhiteRiverSkillKind.SHOTGUN_ARROW,
            WhiteRiverSkillKind.DESCENDING_CURRENT,
            WhiteRiverSkillKind.SPIRIT_LEGACY -> {
                // Kamasylvia sylvan wind-volley: converging/diverging emerald arrows + wind ring
                val shift = (frameIdx % 4)
                val rows = listOf(2, 5, 8, 11, 13)
                for (ry in rows) {
                    for (dx in 0..5) {
                        val px = (2 + dx + shift).coerceIn(0, 15)
                        img.setPixel(px, ry, if (dx >= 4) coreWhite else pal.highlight)
                    }
                }
            }
            WhiteRiverSkillKind.METEOR_SHOWER,
            WhiteRiverSkillKind.MAGMA_ERUPTION,
            WhiteRiverSkillKind.FROST_PILLAR,
            WhiteRiverSkillKind.ANCIENT_WAVE -> {
                // Jagged ground-pillar eruption with crater rim
                val height = 4 + (frameIdx % 4) * 2
                for (y in (14 - height)..13) {
                    val frac = (13 - y).toFloat() / height.coerceAtLeast(1)
                    val halfW = ((1f - frac * 0.55f) * 4.5f).toInt().coerceAtLeast(1)
                    for (x in (8 - halfW)..(7 + halfW)) {
                        if (x in 0..15 && y in 0..15) {
                            img.setPixel(x, y, when {
                                abs(x - 7) <= 1 -> coreWhite
                                y == 13 -> pal.mid
                                else -> pal.highlight
                            })
                        }
                    }
                }
            }
            WhiteRiverSkillKind.CHAIN_LIGHTNING,
            WhiteRiverSkillKind.CELESTIAL_SPEAR -> {
                // Descending Elionian golden spear pillar or branching chain lightning
                if (kind == WhiteRiverSkillKind.CELESTIAL_SPEAR) {
                    val tipY = (3 + (frameIdx % 4) * 3).coerceIn(3, 13)
                    for (y in 1..tipY) {
                        img.setPixel(7, y, coreWhite)
                        img.setPixel(8, y, coreWhite)
                        img.setPixel(6, y, pal.highlight)
                        img.setPixel(9, y, pal.highlight)
                    }
                    // Impact halo at spear tip
                    for (dx in -4..4) {
                        img.setPixel((7 + dx).coerceIn(0, 15), tipY, if (abs(dx) <= 1) coreWhite else pal.highlight)
                    }
                } else {
                    val jitter = if (frameIdx % 2 == 0) -1 else 1
                    for (x in 1..14) {
                        val by = 7 + ((x / 3) % 2) * 2 - 1 + jitter
                        img.setPixel(x, by.coerceIn(0, 15), coreWhite)
                        img.setPixel(x, (by - 1).coerceIn(0, 15), pal.highlight)
                        if (x % 4 == 0) img.setPixel(x, (by + 2).coerceIn(0, 15), spiritViolet)
                    }
                }
            }
            WhiteRiverSkillKind.SACRED_ENSLAVEMENT,
            WhiteRiverSkillKind.SUN_MOON_STARS,
            WhiteRiverSkillKind.HEALING_AURA -> {
                // Diamond heraldic crest / celestial seal with corner pillars
                val span = 4 + (frameIdx % 2)
                for (y in 0 until 16) {
                    for (x in 0 until 16) {
                        val manhattan = abs(x - 7) + abs(y - 7)
                        if (manhattan == span || manhattan == span + 1) {
                            img.setPixel(x, y, if (manhattan == span) coreWhite else pal.highlight)
                        } else if (manhattan <= 2) {
                            img.setPixel(x, y, pal.mid)
                        }
                    }
                }
            }
        }

        val out = TileImage(16, 16)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val p = img.getPixel(x, y)
                if ((p and 0xFF) != 0) {
                    out.setPixel(x, y, p)
                } else {
                    val hasNeighbor = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).any { (dx, dy) ->
                        val nx = x + dx
                        val ny = y + dy
                        nx in 0 until 16 && ny in 0 until 16 && (img.getPixel(nx, ny) and 0xFF) != 0
                    }
                    if (hasNeighbor) out.setPixel(x, y, outline)
                }
            }
        }
        return out
    }
}
