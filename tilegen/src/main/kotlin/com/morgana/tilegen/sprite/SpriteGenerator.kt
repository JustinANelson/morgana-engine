package com.morgana.tilegen.sprite

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelDither
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.material.TileNoise
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 16 dark-fantasy / RPG enemy archetypes with distinct silhouettes and body plans.
 */
enum class EnemyKind {
    SKELETON,
    FALLEN_IMP,
    ZOMBIE,
    GOATMAN,
    CULTIST,
    GARGOYLE,
    BLOOD_GOLEM,
    DEMON_LORD,
    GIANT_SPIDER,
    SLIME,
    GIANT_BAT,
    WRAITH,
    MIMIC,
    DEATH_KNIGHT,
    DIRE_WOLF,
    LICH
}

/**
 * Deterministic enemy sprite generator supporting native 16x16 retro templates
 * and native 32x32+ 3/4-perspective RPG Maker / Time Fantasy sprites with auto-outlining.
 */
class SpriteGenerator(
    val seed: Long = 1337L,
    val tileSize: Int = 16
) {
    init {
        require(tileSize >= 16 && tileSize % 2 == 0) {
            "tileSize must be an even integer >= 16, got $tileSize"
        }
    }

    /**
     * Generates [frameCount] frames for [kind] as [NamedTile]s.
     * When [frameCount] is 1, names the tile [name]; otherwise `<name>_f0`, `<name>_f1`, ...
     */
    fun generateEnemyFrames(
        name: String,
        kind: EnemyKind,
        frameCount: Int = 1,
        style: PixelStyle = PixelStyle.RETRO_8BIT,
        paletteOverride: PaletteRamp? = null
    ): List<NamedTile> {
        require(name.isNotBlank()) { "Enemy name must not be blank" }
        require(frameCount >= 1) { "frameCount must be >= 1, got $frameCount" }
        return (0 until frameCount).map { frameIdx ->
            val img = generateEnemyFrame(kind, frameIdx, style, paletteOverride)
            val tileName = if (frameCount == 1) name else "${name}_f$frameIdx"
            NamedTile(tileName, img)
        }
    }

    /**
     * Generates a single sprite frame for [kind].
     */
    fun generateEnemyFrame(
        kind: EnemyKind,
        frameIndex: Int = 0,
        style: PixelStyle = PixelStyle.RETRO_8BIT,
        paletteOverride: PaletteRamp? = null
    ): TileImage {
        val palette = paletteOverride ?: defaultPaletteFor(kind, style)
        val raw = if (tileSize >= 32 && style != PixelStyle.RETRO_8BIT) {
            val native32 = renderHiRes32(kind, frameIndex % 2, palette, style)
            if (tileSize == 32) native32 else scaleNearest(native32, tileSize)
        } else {
            val grid = templateFor(kind, frameIndex % 2)
            val base16 = renderTemplate(grid, palette)
            if (tileSize == 16) base16 else scaleNearest(base16, tileSize)
        }
        return if (style == PixelStyle.STANDARD || (tileSize >= 32 && style == PixelStyle.TIME_FANTASY)) {
            raw
        } else {
            PixelDither.applyStyle(raw, style, palette)
        }
    }

    /**
     * Generates a sheet containing all [EnemyKind]s, 8 tiles wide.
     */
    fun generateAllEnemiesSheet(
        frameCount: Int = 1,
        style: PixelStyle = PixelStyle.RETRO_8BIT
    ): Pair<TileImage, List<NamedTile>> {
        val tiles = EnemyKind.entries.flatMap { kind ->
            generateEnemyFrames(kind.name.lowercase(), kind, frameCount, style)
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

    private fun defaultPaletteFor(kind: EnemyKind, style: PixelStyle): PaletteRamp {
        val base = when (kind) {
            EnemyKind.SKELETON -> PaletteRamp(intArrayOf(c(18, 18, 28), c(72, 76, 94), c(138, 142, 152), c(198, 194, 178), c(242, 238, 222)))
            EnemyKind.FALLEN_IMP -> PaletteRamp(intArrayOf(c(24, 10, 14), c(92, 22, 26), c(158, 42, 36), c(212, 78, 52), c(248, 182, 88)))
            EnemyKind.ZOMBIE -> PaletteRamp(intArrayOf(c(16, 20, 18), c(48, 72, 54), c(84, 118, 82), c(132, 162, 110), c(182, 204, 154)))
            EnemyKind.GOATMAN -> PaletteRamp(intArrayOf(c(22, 14, 12), c(78, 46, 34), c(128, 82, 56), c(174, 124, 86), c(226, 196, 148)))
            EnemyKind.CULTIST -> PaletteRamp(intArrayOf(c(16, 12, 26), c(48, 28, 78), c(88, 48, 132), c(132, 82, 182), c(124, 214, 248)))
            EnemyKind.GARGOYLE -> PalettePresets.TIME_FANTASY_STONE
            EnemyKind.BLOOD_GOLEM -> PaletteRamp(intArrayOf(c(26, 10, 12), c(84, 20, 24), c(142, 34, 34), c(196, 68, 52), c(238, 128, 92)))
            EnemyKind.DEMON_LORD -> PaletteRamp(intArrayOf(c(20, 8, 10), c(92, 16, 22), c(162, 28, 28), c(218, 62, 42), c(252, 216, 78)))
            EnemyKind.GIANT_SPIDER -> PaletteRamp(intArrayOf(c(14, 12, 22), c(42, 34, 58), c(74, 58, 92), c(116, 94, 132), c(232, 64, 54)))
            EnemyKind.SLIME -> PaletteRamp(intArrayOf(c(14, 38, 28), c(32, 92, 56), c(56, 152, 82), c(96, 206, 112), c(198, 248, 186)))
            EnemyKind.GIANT_BAT -> PaletteRamp(intArrayOf(c(18, 14, 24), c(54, 42, 68), c(92, 72, 110), c(138, 112, 156), c(224, 196, 232)))
            EnemyKind.WRAITH -> PaletteRamp(intArrayOf(c(14, 18, 32), c(36, 56, 92), c(68, 108, 158), c(118, 174, 224), c(206, 240, 255)))
            EnemyKind.MIMIC -> PalettePresets.TIMBER_WOOD
            EnemyKind.DEATH_KNIGHT -> PalettePresets.VOID_METAL
            EnemyKind.DIRE_WOLF -> PaletteRamp(intArrayOf(c(20, 22, 28), c(58, 62, 74), c(98, 104, 118), c(148, 154, 168), c(212, 216, 224)))
            EnemyKind.LICH -> PaletteRamp(intArrayOf(c(18, 12, 28), c(52, 32, 84), c(94, 58, 138), c(148, 108, 192), c(142, 242, 196)))
        }
        return if (style == PixelStyle.RETRO_8BIT) base.limitColors(4) else base
    }

    private fun renderHiRes32(kind: EnemyKind, frame: Int, pal: PaletteRamp, style: PixelStyle): TileImage {
        val body = when (kind) {
            EnemyKind.GIANT_SPIDER -> renderSpider32(pal, style)
            EnemyKind.SLIME -> renderSlime32(pal, style)
            EnemyKind.GIANT_BAT -> renderBat32(pal, style)
            EnemyKind.WRAITH -> renderWraith32(pal, style)
            EnemyKind.MIMIC -> renderMimic32(pal, style)
            EnemyKind.DIRE_WOLF -> renderWolf32(pal, style)
            else -> renderHumanoid32(kind, frame, pal, style)
        }

        val outlined = applyOutline(body, pal.shadow)
        val finalImg = TileImage(32, 32)
        val shadowRgba = PaletteRamp.rgba(12, 14, 22, 115)
        for (y in 26..30) {
            for (x in 7..24) {
                val d = hypot((x + 0.5f - 16f) / 8.0f, (y + 0.5f - 28.5f) / 2.2f)
                if (d <= 1f) finalImg.drawPixel(x, y, shadowRgba)
            }
        }
        finalImg.drawImage(outlined, 0, 0, 32, 32, 0, 0)
        return finalImg
    }

    private fun renderSpider32(pal: PaletteRamp, style: PixelStyle): TileImage {
        val img = TileImage(32, 32)
        // 8 segmented legs
        for (i in 0 until 4) {
            val ly = 14 + i * 3
            drawLine(img, 12, ly, 4 + i, ly - 3, pal.mid)
            drawLine(img, 4 + i, ly - 3, 3 + i, ly + 4, pal.shadow)
            drawLine(img, 19, ly, 27 - i, ly - 3, pal.mid)
            drawLine(img, 27 - i, ly - 3, 28 - i, ly + 4, pal.shadow)
        }
        // Abdomen & cephalothorax
        fillShadedEllipse(img, 16f, 13f, 6.5f, 6.0f, pal, style)
        fillShadedEllipse(img, 16f, 21f, 4.2f, 3.6f, pal, style)
        // Cluster of red eyes
        val eye = PaletteRamp.rgba(248, 52, 42)
        img.setPixel(14, 22, eye)
        img.setPixel(17, 22, eye)
        img.setPixel(15, 23, eye)
        img.setPixel(16, 23, eye)
        return img
    }

    private fun renderSlime32(pal: PaletteRamp, style: PixelStyle): TileImage {
        val img = TileImage(32, 32)
        for (y in 10..27) {
            for (x in 5..26) {
                val dx = (x + 0.5f - 16f) / 10.5f
                val dy = (y + 0.5f - 21f) / 7.5f
                val wobble = if (y < 21) (dy * dy * 0.35f) else 0f
                val d = hypot(dx, dy) + wobble
                if (d <= 1f) {
                    val light = (0.78f - dx * 0.25f - dy * 0.35f).coerceIn(0.15f, 1f)
                    val col = if (style == PixelStyle.TIME_FANTASY) PixelDither.sampleDithered(pal, light, x, y, 4, 0.14f) else pal.sample(light)
                    img.setPixel(x, y, col)
                }
            }
        }
        // Glossy specular highlight + cute/creepy eyes
        img.setPixel(11, 16, pal.highlight)
        img.setPixel(12, 16, pal.highlight)
        img.setPixel(11, 17, pal.highlight)
        img.setPixel(13, 21, pal.shadow)
        img.setPixel(18, 21, pal.shadow)
        return img
    }

    private fun renderBat32(pal: PaletteRamp, style: PixelStyle): TileImage {
        val img = TileImage(32, 32)
        drawWing(img, 12, 14, dir = -1, pal = pal, style = style)
        drawWing(img, 19, 14, dir = 1, pal = pal, style = style)
        fillShadedEllipse(img, 16f, 15f, 4.2f, 5.2f, pal, style)
        // Pointed ears
        for (i in 0..3) {
            img.setPixel(13, 10 - i, pal.mid)
            img.setPixel(18, 10 - i, pal.mid)
        }
        // Eyes & fangs
        img.setPixel(14, 14, PaletteRamp.rgba(252, 72, 62))
        img.setPixel(17, 14, PaletteRamp.rgba(252, 72, 62))
        img.setPixel(14, 17, PaletteRamp.rgba(245, 240, 230))
        img.setPixel(17, 17, PaletteRamp.rgba(245, 240, 230))
        return img
    }

    private fun renderWraith32(pal: PaletteRamp, style: PixelStyle): TileImage {
        val img = TileImage(32, 32)
        for (y in 6..27) {
            val frac = (y - 6f) / 21f
            val halfW = (4f + frac * 6f - if (y > 22) (y - 22) * 0.8f else 0f).toInt().coerceAtLeast(1)
            for (x in (16 - halfW)..(16 + halfW)) {
                if (y > 23 && (x + y) % 3 == 0) continue // wispy tattered bottom
                val light = (0.75f - abs(x - 16) / 12f - frac * 0.25f).coerceIn(0.15f, 1f)
                val col = if (style == PixelStyle.TIME_FANTASY) PixelDither.sampleDithered(pal, light, x, y, 4, 0.15f) else pal.sample(light)
                img.setPixel(x, y, col)
            }
        }
        // Dark hood interior + glowing eyes
        fillShadedEllipse(img, 16f, 11f, 2.5f, 2.5f, PaletteRamp(intArrayOf(pal.shadow, pal.shadow)), style)
        img.setPixel(15, 11, pal.highlight)
        img.setPixel(17, 11, pal.highlight)
        return img
    }

    private fun renderMimic32(pal: PaletteRamp, style: PixelStyle): TileImage {
        val img = TileImage(32, 32)
        val steel = PalettePresets.SLATE_STONE
        val tooth = PaletteRamp.rgba(244, 238, 222)
        val tongue = PaletteRamp.rgba(218, 56, 78)
        // Upper lid
        fillShadedRect(img, 7, 8, 18, 8, pal, style, 0.72f)
        // Lower chest base
        fillShadedRect(img, 7, 19, 18, 9, pal, style, 0.55f)
        // Metal bands
        fillShadedRect(img, 9, 8, 2, 20, steel, style, 0.65f)
        fillShadedRect(img, 21, 8, 2, 20, steel, style, 0.65f)
        // Dark mouth + teeth + tongue
        for (y in 15..19) for (x in 8..23) img.setPixel(x, y, PaletteRamp.rgba(22, 12, 16))
        for (x in 9..22 step 3) {
            img.setPixel(x, 16, tooth)
            img.setPixel(x + 1, 19, tooth)
        }
        fillShadedEllipse(img, 16f, 19f, 3.0f, 2.2f, PaletteRamp(intArrayOf(tongue, tongue)), style)
        // Eyes on lid
        img.setPixel(12, 12, PaletteRamp.rgba(255, 220, 64))
        img.setPixel(19, 12, PaletteRamp.rgba(255, 220, 64))
        return img
    }

    private fun renderWolf32(pal: PaletteRamp, style: PixelStyle): TileImage {
        val img = TileImage(32, 32)
        // Quadruped body & 4 legs
        fillShadedEllipse(img, 17f, 19f, 7.5f, 4.2f, pal, style)
        fillShadedRect(img, 10, 21, 2, 7, pal, style, 0.55f)
        fillShadedRect(img, 13, 21, 2, 7, pal, style, 0.45f)
        fillShadedRect(img, 20, 21, 2, 7, pal, style, 0.55f)
        fillShadedRect(img, 23, 21, 2, 7, pal, style, 0.45f)
        // Tail
        drawLine(img, 24, 17, 28, 13, pal.mid)
        drawLine(img, 24, 18, 28, 14, pal.shadow)
        // Head & snout facing left
        fillShadedEllipse(img, 9.5f, 14.5f, 4.2f, 3.6f, pal, style)
        fillShadedRect(img, 4, 14, 4, 3, pal, style, 0.6f)
        // Ears & red eye
        img.setPixel(10, 10, pal.mid)
        img.setPixel(12, 10, pal.mid)
        img.setPixel(8, 14, PaletteRamp.rgba(252, 68, 52))
        return img
    }

    private fun renderHumanoid32(kind: EnemyKind, frame: Int, pal: PaletteRamp, style: PixelStyle): TileImage {
        val body = TileImage(32, 32)
        val bob = if (frame == 1) 1 else 0
        val stride = if (frame == 1) 2 else 0
        val steel = PalettePresets.SLATE_STONE
        val gold = PaletteRamp(intArrayOf(c(84, 56, 14), c(164, 118, 28), c(228, 182, 52), c(252, 236, 132)))
        val cloak = PaletteRamp(intArrayOf(c(24, 18, 32), c(56, 40, 68), c(96, 68, 112), c(138, 104, 156)))

        // Legs
        val legY0 = 21 + bob
        val legY1 = 28
        fillShadedRect(body, 11, legY0 - stride / 2, 4, legY1 - legY0, pal, style, 0.45f)
        fillShadedRect(body, 17, legY0 + stride / 2, 4, legY1 - legY0, pal, style, 0.35f)

        // Wings
        if (kind == EnemyKind.GARGOYLE || kind == EnemyKind.DEMON_LORD) {
            val wingLift = if (frame == 1) -2 else 0
            drawWing(body, 10, 13 + bob + wingLift, dir = -1, pal = pal, style = style)
            drawWing(body, 21, 13 + bob + wingLift, dir = 1, pal = pal, style = style)
        }

        // Torso
        val torsoW = when (kind) {
            EnemyKind.BLOOD_GOLEM, EnemyKind.DEMON_LORD, EnemyKind.DEATH_KNIGHT -> 14
            EnemyKind.CULTIST, EnemyKind.LICH -> 12
            else -> 10
        }
        val torsoH = if (kind == EnemyKind.CULTIST || kind == EnemyKind.LICH) 14 else 10
        val tx0 = 16 - torsoW / 2
        val ty0 = 12 + bob
        val torsoPal = if (kind == EnemyKind.ZOMBIE || kind == EnemyKind.CULTIST || kind == EnemyKind.LICH) cloak else pal
        fillShadedEllipse(body, 16f, ty0 + torsoH * 0.5f, torsoW * 0.52f, torsoH * 0.55f, torsoPal, style)

        if (kind == EnemyKind.SKELETON) {
            for (ry in (ty0 + 2)..(ty0 + 7) step 2) {
                for (rx in 13..18) body.setPixel(rx, ry, pal.highlight)
            }
        } else if (kind in setOf(EnemyKind.DEMON_LORD, EnemyKind.GOATMAN, EnemyKind.DEATH_KNIGHT)) {
            for (bx in tx0 + 1 until tx0 + torsoW - 1) {
                body.setPixel(bx, ty0 + torsoH - 2, gold.mid)
            }
        }

        // Arms & Weapons
        val armY = 14 + bob
        fillShadedEllipse(body, (tx0 - 1).toFloat(), (armY + 2).toFloat(), 2.2f, 3.8f, pal, style)
        fillShadedEllipse(body, (tx0 + torsoW + 1).toFloat(), (armY + 2 - stride).toFloat(), 2.2f, 3.8f, pal, style)

        when (kind) {
            EnemyKind.SKELETON, EnemyKind.DEATH_KNIGHT -> {
                fillShadedRect(body, 4, 12 + bob, 5, 9, steel, style, 0.72f)
                for (sy in (6 + bob - stride)..(15 + bob - stride)) {
                    body.setPixel(26, sy, steel.highlight)
                    body.setPixel(27, sy, steel.mid)
                }
                for (gx in 25..28) body.setPixel(gx, 15 + bob - stride, gold.mid)
            }
            EnemyKind.FALLEN_IMP, EnemyKind.ZOMBIE -> {
                for (sy in (11 + bob - stride)..(16 + bob - stride)) {
                    body.setPixel(24, sy, steel.highlight)
                }
            }
            EnemyKind.GOATMAN -> {
                for (sy in (4 + bob)..27) body.setPixel(7, sy, PalettePresets.TIMBER_WOOD.mid)
                fillShadedRect(body, 4, 4 + bob, 4, 5, steel, style, 0.85f)
            }
            EnemyKind.CULTIST, EnemyKind.LICH -> {
                for (sy in (6 + bob)..27) body.setPixel(24, sy, PalettePresets.TIMBER_WOOD.mid)
                fillShadedEllipse(body, 24.5f, (5 + bob).toFloat(), 2.2f, 2.2f, PalettePresets.OCEAN_WATER, style)
            }
            else -> Unit
        }

        // Head
        val headR = if (kind == EnemyKind.BLOOD_GOLEM || kind == EnemyKind.DEMON_LORD) 5.2f else 4.6f
        val headCy = (8 + bob).toFloat()
        fillShadedEllipse(body, 16f, headCy, headR, headR * 0.95f, pal, style)

        if (kind == EnemyKind.CULTIST) {
            fillShadedEllipse(body, 16f, headCy - 0.5f, 5.2f, 5.0f, cloak, style)
            for (y in 6 + bob..10 + bob) for (x in 13..18) body.setPixel(x, y, pal.shadow)
        } else if (kind == EnemyKind.LICH) {
            // Golden crown
            for (cx in 12..19 step 2) body.setPixel(cx, 3 + bob, gold.highlight)
            for (cx in 12..19) body.setPixel(cx, 4 + bob, gold.mid)
        }

        if (kind in setOf(EnemyKind.DEMON_LORD, EnemyKind.GOATMAN, EnemyKind.FALLEN_IMP, EnemyKind.GARGOYLE, EnemyKind.DEATH_KNIGHT)) {
            val hornH = if (kind == EnemyKind.DEMON_LORD || kind == EnemyKind.GOATMAN || kind == EnemyKind.DEATH_KNIGHT) 4 else 3
            val hornColor = if (kind == EnemyKind.DEMON_LORD || kind == EnemyKind.DEATH_KNIGHT) gold.highlight else pal.highlight
            for (i in 0 until hornH) {
                body.setPixel(11 - i / 2, 5 + bob - i, hornColor)
                body.setPixel(20 + i / 2, 5 + bob - i, hornColor)
            }
        }

        // Glowing Eyes
        val eyeColor = when (kind) {
            EnemyKind.CULTIST, EnemyKind.DEATH_KNIGHT -> PaletteRamp.rgba(110, 220, 255)
            EnemyKind.ZOMBIE, EnemyKind.LICH -> PaletteRamp.rgba(186, 242, 118)
            EnemyKind.SKELETON -> PaletteRamp.rgba(255, 220, 120)
            else -> PaletteRamp.rgba(255, 86, 52)
        }
        val eyeY = 8 + bob
        body.setPixel(14, eyeY, eyeColor)
        body.setPixel(17, eyeY, eyeColor)
        return body
    }

    private fun drawLine(img: TileImage, x0: Int, y0: Int, x1: Int, y1: Int, rgba: Int) {
        val steps = maxOf(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(1)
        for (i in 0..steps) {
            val x = (x0 + (x1 - x0) * i / steps).coerceIn(1, img.width - 2)
            val y = (y0 + (y1 - y0) * i / steps).coerceIn(1, img.height - 2)
            img.setPixel(x, y, rgba)
        }
    }

    private fun drawWing(img: TileImage, rootX: Int, rootY: Int, dir: Int, pal: PaletteRamp, style: PixelStyle) {
        for (i in 0..7) {
            val wx = (rootX + dir * i).coerceIn(1, 30)
            val topY = (rootY - 4 + i / 2).coerceIn(1, 30)
            val botY = (rootY + 3 - i / 3).coerceIn(topY, 30)
            for (y in topY..botY) {
                val t = 0.35f + 0.45f * (1f - (y - topY).toFloat() / (botY - topY).coerceAtLeast(1))
                val col = if (style == PixelStyle.TIME_FANTASY) PixelDither.sampleDithered(pal, t, wx, y, 4, 0.14f) else pal.sample(t)
                img.setPixel(wx, y, col)
            }
        }
    }

    private fun fillShadedRect(img: TileImage, x0: Int, y0: Int, w: Int, h: Int, pal: PaletteRamp, style: PixelStyle, baseLight: Float) {
        for (y in y0 until (y0 + h).coerceAtMost(img.height)) {
            for (x in x0 until (x0 + w).coerceAtMost(img.width)) {
                if (x < 0 || y < 0) continue
                val nx = (x - x0).toFloat() / w.coerceAtLeast(1)
                val ny = (y - y0).toFloat() / h.coerceAtLeast(1)
                val t = (baseLight + (1f - nx) * 0.25f - ny * 0.2f).coerceIn(0.15f, 1f)
                val col = if (style == PixelStyle.TIME_FANTASY) PixelDither.sampleDithered(pal, t, x, y, 4, 0.15f) else pal.sample(t)
                img.setPixel(x, y, col)
            }
        }
    }

    private fun fillShadedEllipse(img: TileImage, cx: Float, cy: Float, rx: Float, ry: Float, pal: PaletteRamp, style: PixelStyle) {
        val x0 = (cx - rx).toInt().coerceAtLeast(1)
        val x1 = (cx + rx).toInt().coerceAtMost(img.width - 2)
        val y0 = (cy - ry).toInt().coerceAtLeast(1)
        val y1 = (cy + ry).toInt().coerceAtMost(img.height - 2)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                val d = hypot(dx, dy)
                if (d <= 1f) {
                    val light = (0.72f - dx * 0.28f - dy * 0.30f + TileNoise.hash(x, y, seed) * 0.06f).coerceIn(0.15f, 1f)
                    val col = if (style == PixelStyle.TIME_FANTASY) {
                        PixelDither.sampleDithered(pal, light, x, y, 4, 0.15f)
                    } else {
                        pal.sample(light)
                    }
                    img.setPixel(x, y, col)
                }
            }
        }
    }

    private fun applyOutline(src: TileImage, outlineColor: Int): TileImage {
        val out = src.copy()
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                if ((src.getPixel(x, y) and 0xFF) != 0) continue
                val hasNeighbor = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).any { (dx, dy) ->
                    val nx = x + dx
                    val ny = y + dy
                    nx in 0 until src.width && ny in 0 until src.height && (src.getPixel(nx, ny) and 0xFF) != 0
                }
                if (hasNeighbor) {
                    out.setPixel(x, y, outlineColor)
                }
            }
        }
        return out
    }

    private fun renderTemplate(rows: List<String>, palette: PaletteRamp): TileImage {
        val img = TileImage(16, 16)
        val p0 = palette.colors[0]
        val p1 = palette.colors[1.coerceAtMost(palette.colors.lastIndex)]
        val p2 = palette.colors[2.coerceAtMost(palette.colors.lastIndex)]
        val p3 = palette.colors.last()

        for (y in 0 until 16) {
            val row = rows[y]
            for (x in 0 until 16) {
                val rgba = when (row[x]) {
                    '0' -> p0
                    '1' -> p1
                    '2' -> p2
                    '3' -> p3
                    else -> 0
                }
                if (rgba != 0) img.setPixel(x, y, rgba)
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
                out.setPixel(x, y, src.getPixel(sx, sy))
            }
        }
        return out
    }

    private fun c(r: Int, g: Int, b: Int) = PaletteRamp.rgba(r, g, b)

    private fun templateFor(kind: EnemyKind, frame: Int): List<String> = when (kind) {
        EnemyKind.SKELETON -> if (frame == 0) listOf(
            "................",
            "......0000......",
            ".....033330.....",
            ".....030300.....",
            ".....022220.....",
            "......0220......",
            "...0..0330..3...",
            "..01002112003...",
            "..02003333020...",
            "..010.0220.0....",
            "...0..0220......",
            ".....020020.....",
            ".....020020.....",
            ".....010010.....",
            "....000..000....",
            "................"
        ) else listOf(
            "................",
            "................",
            "......0000......",
            ".....033330.....",
            ".....030300.....",
            ".....022220.....",
            "......0220..3...",
            "...0..0330.03...",
            "..01002112020...",
            "..0200333300....",
            "..010.0220......",
            "...0..0220......",
            ".....020020.....",
            "....010..010....",
            "....00....00....",
            "................"
        )

        EnemyKind.FALLEN_IMP -> if (frame == 0) listOf(
            "................",
            "....0......0....",
            "....03....30....",
            ".....022220.....",
            "....02300320....",
            "....02222220....",
            ".....012210.....",
            "....02222220..3.",
            "...02022220203..",
            "...00.0110.00...",
            "......0220......",
            ".....020020.....",
            ".....010010.....",
            "....010..010....",
            "....00....00....",
            "................"
        ) else listOf(
            "................",
            "................",
            "....0......0....",
            "....03....30....",
            ".....022220.....",
            "....02300320....",
            "....02222220....",
            ".....012210...3.",
            "....02222220.3..",
            "...0201111020...",
            "......0220......",
            ".....020020.....",
            "....010..010....",
            "....00....00....",
            "................",
            "................"
        )

        EnemyKind.ZOMBIE -> if (frame == 0) listOf(
            "................",
            "......0000......",
            ".....023220.....",
            ".....020200.....",
            ".....012210.....",
            "......0110......",
            "....00222200....",
            "...0221221220...",
            "..0220122100....",
            "..010.0110......",
            "......0110......",
            ".....020010.....",
            ".....020010.....",
            ".....010.010....",
            "....000...00....",
            "................"
        ) else listOf(
            "................",
            "................",
            "......0000......",
            ".....023220.....",
            ".....020200.....",
            ".....012210.....",
            "....00111100....",
            "...02222220.....",
            "..0220122120....",
            "......01100.....",
            ".....020010.....",
            ".....010020.....",
            "....010..010....",
            "....00....00....",
            "................",
            "................"
        )

        EnemyKind.GOATMAN -> listOf(
            "....00....00....",
            "...030....030...",
            "....00222200....",
            ".....023320.....",
            ".....020020.....",
            "....01222210....",
            "..3.02211220....",
            "..3002222220....",
            "..3.01222210....",
            "..0...0110......",
            "..0..020020.....",
            "..0..020020.....",
            "..0..010010.....",
            ".....00..00.....",
            "................",
            "................"
        )

        EnemyKind.CULTIST -> listOf(
            "................",
            "......0000......",
            ".....022220.....",
            "....02033020....",
            "....02000020....",
            ".....012210.....",
            "....02222220..3.",
            "...0212222120.2.",
            "...0212222120.1.",
            "...0112222110.1.",
            "....01122110..1.",
            "....01122110..1.",
            "....01111110..1.",
            "....00000000..0.",
            "................",
            "................"
        )

        EnemyKind.GARGOYLE -> listOf(
            "..0..........0..",
            ".020..0000..020.",
            ".02200222200220.",
            ".01222300322210.",
            "..012222222210..",
            "...0102222010...",
            "....00122100....",
            ".....022220.....",
            "....02211220....",
            "....01022010....",
            ".....020020.....",
            ".....010010.....",
            "....010..010....",
            "....00....00....",
            "................",
            "................"
        )

        EnemyKind.BLOOD_GOLEM -> listOf(
            "................",
            ".....000000.....",
            "....02222220....",
            "....02300320....",
            "....01222210....",
            "..000222222000..",
            ".02222322322220.",
            ".02222222222220.",
            ".01202222220210.",
            ".010.012210.010.",
            ".....022220.....",
            "....02200220....",
            "....01100110....",
            "...0000..0000...",
            "................",
            "................"
        )

        EnemyKind.DEMON_LORD -> listOf(
            ".00..........00.",
            ".030..0000..030.",
            "..020022220020..",
            "...0223003220...",
            "....02222220....",
            "..000123321000..",
            ".03222222222230.",
            ".02202211220220.",
            ".030.022220.030.",
            ".....022220.....",
            "....02200220....",
            "....01100110....",
            "...0110..0110...",
            "...000....000...",
            "................",
            "................"
        )

        EnemyKind.GIANT_SPIDER -> listOf(
            "................",
            "................",
            "....00000000....",
            "...0222222220...",
            "..012222222210..",
            ".01002222220010.",
            "010.01222210.010",
            "00.0102332010.00",
            "..010030030010..",
            ".010..0000..010.",
            ".00..........00.",
            "................",
            "................",
            "................",
            "................",
            "................"
        )

        EnemyKind.SLIME -> listOf(
            "................",
            "................",
            "................",
            "................",
            "......0000......",
            "....00233200....",
            "...0233222220...",
            "..022222222220..",
            "..022022220220..",
            ".01222222222210.",
            ".01122222222110.",
            "..011111111110..",
            "...0000000000...",
            "................",
            "................",
            "................"
        )

        EnemyKind.GIANT_BAT -> listOf(
            "................",
            ".0............0.",
            "020..0....0..020",
            "0220020000200220",
            "0122223003222210",
            ".01222222222210.",
            "..011230032110..",
            "...0012222100...",
            ".....011110.....",
            "......0000......",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )

        EnemyKind.WRAITH -> listOf(
            "................",
            "......0000......",
            ".....022220.....",
            "....02033020....",
            "....02000020....",
            ".....022220.....",
            "....02222220....",
            "...0221221220...",
            "...0212222120...",
            "....01222210....",
            "....01211210....",
            ".....010010.....",
            ".....00..00.....",
            "................",
            "................",
            "................"
        )

        EnemyKind.MIMIC -> listOf(
            "................",
            "................",
            "...0000000000...",
            "..022222222220..",
            "..023022220320..",
            "..000000000000..",
            "..030303030300..",
            "..002222222200..",
            "..030303030300..",
            "..012222222210..",
            "..011111111110..",
            "...0000000000...",
            "................",
            "................",
            "................",
            "................"
        )

        EnemyKind.DEATH_KNIGHT -> listOf(
            "..0..........0..",
            "..03.000000.30..",
            "...0022222200...",
            "....02300320....",
            "....01222210....",
            "..000222222000..",
            ".02220233202223.",
            ".02120222202203.",
            ".021201221000.3.",
            "..000.0110....0.",
            ".....020020.....",
            ".....020020.....",
            "....010..010....",
            "....00....00....",
            "................",
            "................"
        )

        EnemyKind.DIRE_WOLF -> listOf(
            "................",
            "................",
            "....0.0.........",
            "...02020........",
            "..02322200000...",
            ".02222222222200.",
            "..00122222221020",
            "....01222222100.",
            "....020000200...",
            "....020..020....",
            "....010..010....",
            "....00....00....",
            "................",
            "................",
            "................",
            "................"
        )

        EnemyKind.LICH -> listOf(
            "....0.00.0......",
            "....033330......",
            ".....02220......",
            "....0230320.....",
            ".....02220......",
            "....0122210...3.",
            "...022222220..2.",
            "...0212222120.1.",
            "....01222210..1.",
            "....01122110..1.",
            "....01122110..1.",
            "....01111110..0.",
            "....00000000....",
            "................",
            "................",
            "................"
        )
    }
}
