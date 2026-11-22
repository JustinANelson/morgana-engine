package com.morgana.tilegen.export

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelDither
import com.morgana.tilegen.material.SpriteFinish
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Generates crisp 32x32 soft-shaded pixel-art icons, 32x32 projectiles, and 5-frame 160x32 spell/combat effect strips
 * for the reference roguelike game (*Ashen Keep*) in the `MORGANA` house style.
 */
object RoguelikeIconArt {

    private fun ramp(r: Int, g: Int, b: Int, spread: Float = 0.34f) =
        PaletteRamp.fromBaseColor(PaletteRamp.rgba(r, g, b), steps = 6, hueShift = 0.06f, valueSpread = spread)

    private val gold = ramp(236, 188, 66)
    private val steel = ramp(164, 174, 192)
    private val iron = ramp(112, 118, 132)
    private val ember = ramp(228, 102, 54)
    private val crimson = ramp(196, 56, 62)
    private val sapphire = ramp(66, 128, 224)
    private val emerald = ramp(68, 176, 116)
    private val violet = ramp(158, 92, 214)
    private val frost = ramp(122, 204, 240)
    private val wood = ramp(136, 92, 58)
    private val leather = ramp(142, 98, 66)
    private val bone = ramp(224, 216, 194)
    private val parchment = ramp(228, 206, 158)
    private val ash = ramp(88, 84, 96)

    private fun tone(p: PaletteRamp, t: Float, x: Int, y: Int): Int =
        PixelDither.sampleDithered(p, t.coerceIn(0.05f, 0.98f), x, y, 4, 0.10f)

    private fun drawIcon(draw: (TileImage) -> Unit): TileImage {
        val raw = TileImage(32, 32)
        draw(raw)
        return SpriteFinish.finish(raw)
    }

    private fun fillCircle(img: TileImage, cx: Float, cy: Float, r: Float, pal: PaletteRamp) {
        val x0 = (cx - r - 1).toInt().coerceAtLeast(1)
        val x1 = (cx + r + 1).toInt().coerceAtMost(img.width - 2)
        val y0 = (cy - r - 1).toInt().coerceAtLeast(1)
        val y1 = (cy + r + 1).toInt().coerceAtMost(img.height - 2)
        for (y in y0..y1) for (x in x0..x1) {
            val dx = (x - cx) / r
            val dy = (y - cy) / r
            val d2 = dx * dx + dy * dy
            if (d2 <= 1f) {
                val lit = (0.72f - dx * 0.28f - dy * 0.30f - d2 * 0.14f).coerceIn(0.08f, 0.96f)
                img.setPixel(x, y, tone(pal, lit, x, y))
            }
        }
    }

    private fun fillRect(img: TileImage, x0: Int, y0: Int, x1: Int, y1: Int, pal: PaletteRamp) {
        val w = (x1 - x0).coerceAtLeast(1).toFloat()
        val h = (y1 - y0).coerceAtLeast(1).toFloat()
        for (y in y0.coerceAtLeast(1)..y1.coerceAtMost(img.height - 2)) {
            for (x in x0.coerceAtLeast(1)..x1.coerceAtMost(img.width - 2)) {
                val tx = (x - x0) / w
                val ty = (y - y0) / h
                val lit = (0.78f - tx * 0.25f - ty * 0.28f).coerceIn(0.10f, 0.95f)
                img.setPixel(x, y, tone(pal, lit, x, y))
            }
        }
    }

    private fun drawLine(img: TileImage, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, pal: PaletteRamp) {
        val steps = (hypot(x1 - x0, y1 - y0) * 2f).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val px = x0 + (x1 - x0) * t
            val py = y0 + (y1 - y0) * t
            fillCircle(img, px, py, radius, pal)
        }
    }

    // ---- Icon shapes ----

    private fun coins(): TileImage = drawIcon { img ->
        fillCircle(img, 12f, 20f, 6.5f, gold)
        fillCircle(img, 20f, 18f, 6.5f, gold)
        fillCircle(img, 16f, 12f, 6.5f, gold)
    }

    private fun sword(bladePal: PaletteRamp, hiltPal: PaletteRamp, broad: Boolean = false): TileImage = drawIcon { img ->
        val w = if (broad) 2.6f else 1.8f
        drawLine(img, 25f, 6f, 11f, 20f, w, bladePal)
        drawLine(img, 8f, 15f, 17f, 24f, 1.5f, hiltPal)
        drawLine(img, 11f, 20f, 6f, 25f, 1.4f, wood)
        fillCircle(img, 5.5f, 25.5f, 2.0f, hiltPal)
    }

    private fun bow(woodPal: PaletteRamp, trimPal: PaletteRamp): TileImage = drawIcon { img ->
        for (i in 0..24) {
            val a = -1.25f + i * (2.5f / 24f)
            val bx = 11f + cos(a) * 11f
            val by = 16f + sin(a) * 11f
            fillCircle(img, bx, by, 1.5f, woodPal)
        }
        drawLine(img, 14f, 6f, 14f, 26f, 0.7f, bone)
        fillCircle(img, 22f, 16f, 2.0f, trimPal)
    }

    private fun staff(shaftPal: PaletteRamp, orbPal: PaletteRamp): TileImage = drawIcon { img ->
        drawLine(img, 7f, 26f, 21f, 12f, 1.5f, shaftPal)
        fillCircle(img, 23f, 9f, 5.0f, orbPal)
        fillCircle(img, 21.5f, 7.5f, 1.8f, bone)
    }

    private fun arrows(tipPal: PaletteRamp, fletchPal: PaletteRamp): TileImage = drawIcon { img ->
        for (o in listOf(-3, 0, 3)) {
            drawLine(img, 8f + o, 24f, 22f + o, 10f, 0.9f, wood)
            fillCircle(img, 23f + o, 9f, 1.8f, tipPal)
            fillCircle(img, 7f + o, 25f, 1.8f, fletchPal)
        }
    }

    private fun shield(bodyPal: PaletteRamp, rimPal: PaletteRamp): TileImage = drawIcon { img ->
        for (y in 6..26) for (x in 7..25) {
            val dx = (x - 16f) / 9f
            val dy = (y - 12f) / 14f
            val taper = if (y > 15) 1f - ((y - 15f) / 12f) * 0.85f else 1f
            if (abs(dx) <= taper && y in 6..26) {
                val edge = abs(dx) > taper - 0.22f || y <= 7 || y >= 25
                val pal = if (edge) rimPal else bodyPal
                val lit = (0.74f - dx * 0.22f - dy * 0.22f).coerceIn(0.10f, 0.95f)
                img.setPixel(x, y, tone(pal, lit, x, y))
            }
        }
        fillCircle(img, 16f, 15f, 2.6f, rimPal)
    }

    private fun helmet(pal: PaletteRamp, trimPal: PaletteRamp): TileImage = drawIcon { img ->
        fillCircle(img, 16f, 15f, 8.5f, pal)
        fillRect(img, 8, 16, 24, 23, pal)
        fillRect(img, 11, 17, 21, 20, ash)
        fillRect(img, 15, 7, 17, 22, trimPal)
    }

    private fun armor(pal: PaletteRamp, trimPal: PaletteRamp): TileImage = drawIcon { img ->
        fillRect(img, 9, 8, 23, 24, pal)
        fillRect(img, 6, 9, 10, 16, pal)
        fillRect(img, 22, 9, 26, 16, pal)
        fillRect(img, 13, 8, 19, 11, ash)
        fillRect(img, 9, 19, 23, 21, trimPal)
    }

    private fun boots(pal: PaletteRamp, trimPal: PaletteRamp): TileImage = drawIcon { img ->
        fillRect(img, 8, 9, 15, 22, pal)
        fillRect(img, 7, 20, 17, 25, pal)
        fillRect(img, 17, 11, 24, 22, pal)
        fillRect(img, 16, 20, 26, 25, pal)
        fillRect(img, 8, 9, 15, 11, trimPal)
        fillRect(img, 17, 11, 24, 13, trimPal)
    }

    private fun ring(bandPal: PaletteRamp, gemPal: PaletteRamp): TileImage = drawIcon { img ->
        for (y in 8..25) for (x in 8..24) {
            val d = hypot(x - 16f, y - 17f)
            if (d in 5.0f..8.2f) {
                val lit = (0.72f - (x - 16f) * 0.03f - (y - 17f) * 0.03f).coerceIn(0.12f, 0.95f)
                img.setPixel(x, y, tone(bandPal, lit, x, y))
            }
        }
        fillCircle(img, 16f, 10f, 3.6f, gemPal)
    }

    private fun amulet(chainPal: PaletteRamp, gemPal: PaletteRamp): TileImage = drawIcon { img ->
        drawLine(img, 10f, 7f, 16f, 15f, 1.0f, chainPal)
        drawLine(img, 22f, 7f, 16f, 15f, 1.0f, chainPal)
        fillCircle(img, 16f, 20f, 5.5f, chainPal)
        fillCircle(img, 16f, 20f, 3.4f, gemPal)
    }

    private fun potion(liquidPal: PaletteRamp): TileImage = drawIcon { img ->
        fillRect(img, 13, 6, 19, 9, wood)
        fillRect(img, 14, 9, 18, 13, steel)
        fillCircle(img, 16f, 19f, 7.2f, liquidPal)
        fillCircle(img, 13.5f, 16.5f, 2.0f, bone)
    }

    private fun scroll(sealPal: PaletteRamp): TileImage = drawIcon { img ->
        fillRect(img, 9, 7, 23, 24, parchment)
        fillRect(img, 7, 6, 25, 9, wood)
        fillRect(img, 7, 23, 25, 26, wood)
        fillCircle(img, 16f, 16f, 3.2f, sealPal)
    }

    private fun materialBone(): TileImage = drawIcon { img ->
        drawLine(img, 9f, 23f, 23f, 9f, 2.2f, bone)
        fillCircle(img, 8f, 21f, 2.4f, bone)
        fillCircle(img, 11f, 24f, 2.4f, bone)
        fillCircle(img, 21f, 8f, 2.4f, bone)
        fillCircle(img, 24f, 11f, 2.4f, bone)
    }

    private fun materialEmber(): TileImage = drawIcon { img ->
        fillCircle(img, 16f, 18f, 7.0f, ember)
        fillCircle(img, 16f, 14f, 5.0f, gold)
        fillCircle(img, 16f, 11f, 2.8f, bone)
    }

    private fun materialShard(): TileImage = drawIcon { img ->
        drawLine(img, 16f, 6f, 16f, 26f, 3.8f, violet)
        drawLine(img, 16f, 7f, 16f, 24f, 1.8f, frost)
    }

    private fun crownSeal(): TileImage = drawIcon { img ->
        fillRect(img, 7, 17, 25, 24, gold)
        drawLine(img, 8f, 17f, 8f, 10f, 1.8f, gold)
        drawLine(img, 16f, 17f, 16f, 8f, 2.0f, gold)
        drawLine(img, 24f, 17f, 24f, 10f, 1.8f, gold)
        fillCircle(img, 16f, 20f, 2.2f, crimson)
    }

    /** Icon ID -> 32x32 PNG for every item in Ashen Keep (1..34). */
    fun allIcons(): Map<Int, TileImage> = linkedMapOf(
        1 to coins(),                              // Gold
        2 to sword(iron, wood),                    // Tarnished Blade
        3 to sword(steel, gold),                   // Iron Broadsword
        4 to sword(ember, gold, broad = true),     // Ashen Greatsword
        5 to sword(bone, crimson, broad = true),   // Bone Cleaver (crafted)
        6 to bow(wood, leather),                   // Hunter's Bow
        7 to bow(ash, gold),                       // Recurve Warbow
        8 to bow(frost, steel),                    // Cryptstalker Bow (crafted)
        9 to staff(wood, ember),                   // Ember Rod
        10 to staff(ash, violet),                  // Cryptfire Staff
        11 to staff(gold, frost),                  // Warden's Scepter (crafted)
        12 to arrows(iron, bone),                  // Iron Arrow
        13 to arrows(ember, crimson),              // Ember Arrow
        14 to shield(wood, iron),                  // Wooden Buckler
        15 to shield(steel, gold),                 // Keep Tower Shield
        16 to shield(bone, ember),                 // Bone Bulwark (crafted)
        17 to helmet(leather, wood),               // Leather Cap
        18 to helmet(iron, steel),                 // Iron Helm
        19 to helmet(ash, ember),                  // Ashen Crown (Boss Unique)
        20 to armor(leather, wood),                // Vanguard Jerkin
        21 to armor(emerald, leather),             // Ranger's Cloak
        22 to armor(crimson, gold),                // Pyromancer Vestments
        23 to armor(steel, gold),                  // Keep Plate
        24 to armor(ember, gold),                  // Ashen Mail (crafted)
        25 to boots(leather, wood),                // Leather Boots
        26 to boots(steel, gold),                  // Iron Sabatons
        27 to ring(iron, crimson),                 // Ring of Vigor
        28 to ring(steel, sapphire),               // Ring of Focus
        29 to ring(gold, ember),                   // Ring of the Ashen
        30 to amulet(gold, emerald),               // Talisman of Ward
        31 to amulet(gold, ember),                 // Malakor's Emberheart (Boss Unique)
        32 to potion(crimson),                     // Healing Draught
        33 to potion(sapphire),                    // Mana Draught
        34 to potion(gold),                        // Greater Vigor Elixir
        35 to scroll(ember),                       // Scroll of Fireball
        36 to scroll(frost),                       // Scroll of Frost Nova
        37 to scroll(gold),                        // Scroll of Mending
        38 to materialBone(),                      // Crypt Bone
        39 to materialEmber(),                     // Ashen Ember
        40 to materialShard(),                     // Hollow Shard
        41 to crownSeal()                          // Warden's Signet (Quest Item)
    )

    /** 32x32 projectiles (1..4). */
    fun allProjectiles(): Map<Int, TileImage> = linkedMapOf(
        1 to drawIcon { img -> // Arrow
            drawLine(img, 6f, 16f, 25f, 16f, 1.1f, wood)
            fillCircle(img, 26f, 16f, 2.0f, steel)
            fillCircle(img, 6f, 16f, 2.0f, bone)
        },
        2 to drawIcon { img -> // Fireball bolt
            fillCircle(img, 18f, 16f, 6.2f, ember)
            fillCircle(img, 19f, 16f, 3.5f, gold)
            fillCircle(img, 11f, 16f, 3.0f, crimson)
        },
        3 to drawIcon { img -> // Shadow bolt
            fillCircle(img, 18f, 16f, 6.0f, violet)
            fillCircle(img, 19f, 16f, 3.0f, bone)
            fillCircle(img, 10f, 16f, 2.8f, ash)
        },
        4 to drawIcon { img -> // Frost shard
            drawLine(img, 7f, 16f, 26f, 16f, 2.6f, frost)
            drawLine(img, 11f, 16f, 24f, 16f, 1.2f, bone)
        }
    )

    /** 5-frame 160x32 horizontal animation strips (1..6). */
    fun allAnimations(): Map<Int, TileImage> = linkedMapOf(
        1 to burstStrip(ember, gold),     // Fireball burst
        2 to ringStrip(emerald, gold),    // Mending Light heal
        3 to ringStrip(frost, sapphire),  // Frost Nova
        4 to pillarStrip(gold, bone),     // Rekindle revive
        5 to burstStrip(violet, ash),     // Shadow Bolt / Crypt Nova
        6 to ringStrip(crimson, ember)    // Ashen Slam (Boss telegraph / impact)
    )

    private fun burstStrip(outer: PaletteRamp, core: PaletteRamp): TileImage {
        val strip = TileImage(32 * 5, 32)
        for (f in 0 until 5) {
            val frame = TileImage(32, 32)
            val progress = (f + 1) / 5f
            val r = 4f + progress * 9f
            fillCircle(frame, 16f, 16f, r, outer)
            if (f < 4) fillCircle(frame, 16f, 16f, r * 0.55f, core)
            strip.drawImage(SpriteFinish.finish(frame), 0, 0, 32, 32, f * 32, 0)
        }
        return strip
    }

    private fun ringStrip(outer: PaletteRamp, inner: PaletteRamp): TileImage {
        val strip = TileImage(32 * 5, 32)
        for (f in 0 until 5) {
            val frame = TileImage(32, 32)
            val rad = 4f + f * 2.5f
            for (y in 2..29) for (x in 2..29) {
                val d = hypot(x - 16f, y - 16f)
                if (abs(d - rad) <= 2.2f) {
                    val pal = if (abs(d - rad) <= 0.9f) inner else outer
                    frame.setPixel(x, y, tone(pal, 0.78f, x, y))
                }
            }
            strip.drawImage(SpriteFinish.finish(frame), 0, 0, 32, 32, f * 32, 0)
        }
        return strip
    }

    private fun pillarStrip(outer: PaletteRamp, core: PaletteRamp): TileImage {
        val strip = TileImage(32 * 5, 32)
        for (f in 0 until 5) {
            val frame = TileImage(32, 32)
            val halfW = (2 + (2 - abs(f - 2)) * 2).coerceAtLeast(2)
            fillRect(frame, 16 - halfW, 3, 16 + halfW, 28, outer)
            fillRect(frame, 16 - (halfW / 2).coerceAtLeast(1), 4, 16 + (halfW / 2).coerceAtLeast(1), 27, core)
            strip.drawImage(SpriteFinish.finish(frame), 0, 0, 32, 32, f * 32, 0)
        }
        return strip
    }
}
