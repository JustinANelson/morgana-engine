package com.morgana.gameart.export

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PaletteRamp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Generates distinct 32x32 procedural RPG item icons for a game's `data/items` JSON files (`iconId = 100 + itemId`),
 * skill icons, mount icon, projectile, and a 5-frame sword-slash VFX strip.
 */
internal object ProceduralRpgIconArt {

    private val outline = PaletteRamp.rgba(22, 18, 28, 255)
    private val wood = PaletteRamp.fromBaseColor(PaletteRamp.rgba(146, 98, 56), steps = 5)
    private val darkWood = PaletteRamp.fromBaseColor(PaletteRamp.rgba(104, 68, 38), steps = 5)
    private val steel = PaletteRamp.fromBaseColor(PaletteRamp.rgba(186, 198, 214), steps = 5)
    private val iron = PaletteRamp.fromBaseColor(PaletteRamp.rgba(136, 146, 158), steps = 5)
    private val copper = PaletteRamp.fromBaseColor(PaletteRamp.rgba(204, 124, 82), steps = 5)
    private val bronze = PaletteRamp.fromBaseColor(PaletteRamp.rgba(188, 140, 74), steps = 5)
    private val silver = PaletteRamp.fromBaseColor(PaletteRamp.rgba(222, 232, 246), steps = 5)
    private val gold = PaletteRamp.fromBaseColor(PaletteRamp.rgba(232, 194, 86), steps = 5)
    private val crimson = PaletteRamp.fromBaseColor(PaletteRamp.rgba(206, 62, 66), steps = 5)
    private val sapphire = PaletteRamp.fromBaseColor(PaletteRamp.rgba(78, 134, 224), steps = 5)
    private val emerald = PaletteRamp.fromBaseColor(PaletteRamp.rgba(76, 178, 98), steps = 5)
    private val violet = PaletteRamp.fromBaseColor(PaletteRamp.rgba(142, 96, 206), steps = 5)
    private val linen = PaletteRamp.fromBaseColor(PaletteRamp.rgba(214, 206, 184), steps = 5)
    private val wool = PaletteRamp.fromBaseColor(PaletteRamp.rgba(134, 156, 194), steps = 5)
    private val leather = PaletteRamp.fromBaseColor(PaletteRamp.rgba(156, 112, 72), steps = 5)
    private val darkLeather = PaletteRamp.fromBaseColor(PaletteRamp.rgba(108, 76, 48), steps = 5)
    private val stone = PaletteRamp.fromBaseColor(PaletteRamp.rgba(146, 152, 162), steps = 5)

    fun swordSlashStrip(): TileImage {
        val frames = 5
        val strip = TileImage(32 * frames, 32)
        for (f in 0 until frames) {
            val cell = TileImage(32, 32)
            val progress = f / 4f
            val alpha = ((1f - progress * 0.65f) * 235f).roundToInt().coerceIn(60, 235)
            val core = PaletteRamp.rgba(248, 250, 255, alpha)
            val edge = PaletteRamp.rgba(176, 212, 248, (alpha * 0.85f).roundToInt())
            val goldGlow = PaletteRamp.rgba(245, 204, 108, (alpha * 0.7f).roundToInt())

            val rOuter = 11f + progress * 3.5f
            val rInner = 6.5f + progress * 4.5f
            val startAngle = -1.2f + progress * 0.7f
            val endAngle = 0.6f + progress * 1.1f

            for (y in 0 until 32) {
                for (x in 0 until 32) {
                    val dx = x - 14f
                    val dy = y - 16f
                    val dist = hypot(dx, dy)
                    if (dist in rInner..rOuter) {
                        val ang = kotlin.math.atan2(dy, dx)
                        if (ang in startAngle..endAngle) {
                            val mid = (rInner + rOuter) * 0.5f
                            val c = if (abs(dist - mid) < 1.2f) core else if (dist > mid) edge else goldGlow
                            cell.setPixel(x, y, c)
                        }
                    }
                }
            }
            strip.drawImage(cell, 0, 0, 32, 32, f * 32, 0)
        }
        return strip
    }

    fun generateAllItemIcons(gameDir: Path): Map<Int, TileImage> {
        val itemsDir = gameDir.resolve("data/items")
        if (!Files.isDirectory(itemsDir)) return emptyMap()
        val out = linkedMapOf<Int, TileImage>()

        Files.list(itemsDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") }
                .sorted()
                .forEach { path ->
                    val text = Files.readString(path)
                    val id = extractInt(text, "id") ?: return@forEach
                    val name = extractStr(text, "name") ?: ""
                    val type = extractStr(text, "type") ?: ""
                    val equipSlot = extractStr(text, "equipSlot") ?: ""
                    val weaponType = extractStr(text, "weaponType") ?: ""
                    val level = extractInt(text, "levelReq") ?: 1

                    out[100 + id] = renderItemIcon(name, type, equipSlot, weaponType, level)
                }
        }
        return out
    }

    private fun renderItemIcon(
        name: String,
        type: String,
        equipSlot: String,
        weaponType: String,
        level: Int
    ): TileImage {
        val n = name.lowercase()
        val mat = rampFor(n, level)
        val trim = trimFor(n, level)

        return when {
            type == "CURRENCY" -> coinsIcon(silver)
            type == "CONSUMABLE" -> potionIcon(if ("mana" in n) sapphire else crimson)
            type == "TOOL" -> if ("pick" in n) pickaxeIcon(iron, wood) else hatchetIcon(iron, wood)
            type == "MATERIAL" && anyOf(n, "ruby", "sapphire", "emerald", "topaz") -> {
                val g = when {
                    "ruby" in n -> crimson
                    "sapphire" in n -> sapphire
                    "emerald" in n -> emerald
                    else -> gold
                }
                gemIcon(g, isElite = "elite" in n, isLeg = "legendary" in n)
            }
            type == "MATERIAL" && anyOf(n, "ingot", "scrap", "ore") -> ingotIcon(mat)
            type == "MATERIAL" && anyOf(n, "plank", "timber", "wood", "oak", "yew", "ash") -> plankIcon(mat)
            type == "MATERIAL" && anyOf(n, "hide", "leather") -> hideIcon(mat)
            type == "MATERIAL" && anyOf(n, "linen", "wool", "silk", "flax", "thistle") -> clothIcon(mat)
            type == "MATERIAL" -> stoneIcon(stone)
            type == "AMULET" -> amuletIcon(mat, trim)
            type == "RING" -> ringIcon(mat, trim)
            type == "WEAPON" && weaponType == "bow" -> bowIcon(mat, trim)
            type == "WEAPON" && weaponType == "staff" -> staffIcon(wood, mat)
            type == "WEAPON" && weaponType == "wand" -> wandIcon(mat, trim)
            type == "WEAPON" && weaponType == "dagger" -> daggerIcon(mat, trim)
            type == "WEAPON" -> swordIcon(mat, trim, broad = level >= 15)
            type == "SHIELD" && "quiver" in n -> quiverIcon(mat, trim)
            type == "SHIELD" && anyOf(n, "totem", "tome", "grimoire", "codex", "focus", "orb") -> tomeIcon(mat, trim)
            type == "SHIELD" && anyOf(n, "dirk", "blade", "dagger", "gauche") -> daggerIcon(mat, trim)
            type == "SHIELD" -> shieldIcon(mat, trim, kite = "buckler" !in n)
            type == "ARMOR" && equipSlot == "CLOAK" -> cloakIcon(mat, trim)
            type == "ARMOR" && equipSlot == "GLOVES" -> glovesIcon(mat, trim, gauntlet = anyOf(n, "gauntlet", "plate", "iron", "steel", "bronze", "copper"))
            type == "ARMOR" -> armorIcon(mat, trim, robe = anyOf(n, "robe", "linen", "wool", "silk", "astral", "magister"))
            type == "HELMET" -> helmIcon(mat, trim, hat = anyOf(n, "hat", "cowl", "hood", "cap"), crown = anyOf(n, "crown", "circlet"))
            type == "BOOTS" -> bootsIcon(mat, trim)
            else -> stoneIcon(mat)
        }
    }

    // -------------------------------------------------------------------------
    // Icon Shapes (32x32 outlined pixel art)
    // -------------------------------------------------------------------------

    private fun drawOutlined(draw: (TileImage) -> Unit): TileImage {
        val raw = TileImage(32, 32)
        draw(raw)
        val out = TileImage(32, 32)
        for (y in 0 until 32) for (x in 0 until 32) {
            val c = raw.getPixel(x, y)
            if ((c and 0xFF) != 0) {
                out.setPixel(x, y, c)
            } else {
                val n = ((raw.getPixel(x - 1, y) and 0xFF) != 0) ||
                    ((raw.getPixel(x + 1, y) and 0xFF) != 0) ||
                    ((raw.getPixel(x, y - 1) and 0xFF) != 0) ||
                    ((raw.getPixel(x, y + 1) and 0xFF) != 0)
                if (n) out.setPixel(x, y, outline)
            }
        }
        return out
    }

    private fun box(img: TileImage, x0: Int, y0: Int, w: Int, h: Int, ramp: PaletteRamp) {
        for (ry in 0 until h) for (rx in 0 until w) {
            val t = (0.9f - (rx / w.coerceAtLeast(1).toFloat()) * 0.4f - (ry / h.coerceAtLeast(1).toFloat()) * 0.4f).coerceIn(0.1f, 0.95f)
            img.setPixel(x0 + rx, y0 + ry, ramp.sample(t))
        }
    }

    private fun circle(img: TileImage, cx: Int, cy: Int, r: Float, ramp: PaletteRamp) {
        val ri = r.roundToInt() + 1
        for (dy in -ri..ri) for (dx in -ri..ri) {
            if (dx * dx + dy * dy <= r * r) {
                val t = (0.9f - ((dx + r) / (2 * r)) * 0.4f - ((dy + r) / (2 * r)) * 0.4f).coerceIn(0.1f, 0.96f)
                img.setPixel(cx + dx, cy + dy, ramp.sample(t))
            }
        }
    }

    private fun coinsIcon(r: PaletteRamp) = drawOutlined { img ->
        circle(img, 14, 18, 6f, r)
        circle(img, 18, 14, 6f, r)
    }

    private fun potionIcon(r: PaletteRamp) = drawOutlined { img ->
        box(img, 13, 6, 6, 3, wood)
        box(img, 14, 9, 4, 4, steel)
        circle(img, 16, 19, 7f, r)
        img.setPixel(13, 16, PaletteRamp.rgba(255, 255, 255, 230))
    }

    private fun hatchetIcon(head: PaletteRamp, handle: PaletteRamp) = drawOutlined { img ->
        for (i in 0..16) box(img, 8 + i, 24 - i, 2, 2, handle)
        box(img, 17, 6, 8, 7, head)
        box(img, 23, 7, 2, 6, steel)
    }

    private fun pickaxeIcon(head: PaletteRamp, handle: PaletteRamp) = drawOutlined { img ->
        for (i in 0..16) box(img, 8 + i, 24 - i, 2, 2, handle)
        box(img, 13, 6, 12, 3, head)
        box(img, 11, 8, 3, 4, head)
        box(img, 24, 8, 3, 4, head)
    }

    private fun gemIcon(r: PaletteRamp, isElite: Boolean, isLeg: Boolean) = drawOutlined { img ->
        if (isLeg) circle(img, 16, 16, 9f, gold)
        else if (isElite) circle(img, 16, 16, 8f, silver)
        val rad = if (isLeg) 6.5f else 6f
        circle(img, 16, 16, rad, r)
        img.setPixel(14, 13, PaletteRamp.rgba(255, 255, 255, 240))
    }

    private fun ingotIcon(r: PaletteRamp) = drawOutlined { img ->
        box(img, 7, 14, 18, 8, r)
        box(img, 9, 11, 14, 4, r)
    }

    private fun plankIcon(r: PaletteRamp) = drawOutlined { img ->
        box(img, 6, 11, 20, 5, r)
        box(img, 7, 17, 18, 5, r)
    }

    private fun hideIcon(r: PaletteRamp) = drawOutlined { img ->
        box(img, 9, 8, 14, 16, r)
        box(img, 7, 9, 3, 4, r); box(img, 22, 9, 3, 4, r)
        box(img, 7, 19, 3, 4, r); box(img, 22, 19, 3, 4, r)
    }

    private fun clothIcon(r: PaletteRamp) = drawOutlined { img ->
        box(img, 8, 10, 16, 12, r)
        box(img, 9, 14, 14, 2, linen)
    }

    private fun stoneIcon(r: PaletteRamp) = drawOutlined { img ->
        box(img, 8, 10, 16, 13, r)
        box(img, 10, 8, 12, 3, r)
    }

    private fun swordIcon(blade: PaletteRamp, hilt: PaletteRamp, broad: Boolean) = drawOutlined { img ->
        val w = if (broad) 4 else 3
        for (i in 0..15) box(img, 22 - i, 6 + i, w, 2, blade)
        box(img, 7, 19, 7, 2, hilt)
        box(img, 6, 22, 3, 4, hilt)
    }

    private fun daggerIcon(blade: PaletteRamp, hilt: PaletteRamp) = drawOutlined { img ->
        for (i in 0..10) box(img, 20 - i, 10 + i, 2, 2, blade)
        box(img, 9, 19, 5, 2, hilt)
        box(img, 8, 21, 2, 3, hilt)
    }

    private fun bowIcon(limb: PaletteRamp, trim: PaletteRamp) = drawOutlined { img ->
        for (dy in -10..10) {
            val dx = (dy * dy) / 14
            box(img, 11 + dx, 16 + dy, 2, 2, limb)
        }
        for (y in 7..25) img.setPixel(11, y, trim.highlight)
    }

    private fun staffIcon(shaft: PaletteRamp, orb: PaletteRamp) = drawOutlined { img ->
        for (i in 0..15) box(img, 8 + i, 24 - i, 2, 2, shaft)
        circle(img, 23, 8, 4.2f, orb)
    }

    private fun wandIcon(shaft: PaletteRamp, tip: PaletteRamp) = drawOutlined { img ->
        for (i in 0..12) box(img, 9 + i, 22 - i, 2, 2, shaft)
        circle(img, 22, 9, 2.8f, tip)
    }

    private fun quiverIcon(body: PaletteRamp, fletch: PaletteRamp) = drawOutlined { img ->
        for (i in 0..12) box(img, 10 + i / 2, 10 + i, 6, 2, body)
        box(img, 10, 6, 2, 4, fletch)
        box(img, 13, 7, 2, 3, fletch)
    }

    private fun tomeIcon(cover: PaletteRamp, trim: PaletteRamp) = drawOutlined { img ->
        box(img, 8, 7, 15, 18, cover)
        box(img, 10, 9, 11, 14, trim)
        box(img, 8, 7, 3, 18, darkLeather)
    }

    private fun shieldIcon(body: PaletteRamp, trim: PaletteRamp, kite: Boolean) = drawOutlined { img ->
        if (!kite) {
            circle(img, 16, 16, 8f, body)
            circle(img, 16, 16, 3f, trim)
        } else {
            box(img, 8, 7, 16, 11, body)
            box(img, 10, 18, 12, 5, body)
            box(img, 12, 23, 8, 3, body)
            box(img, 15, 9, 2, 14, trim)
            box(img, 10, 13, 12, 2, trim)
        }
    }

    private fun cloakIcon(body: PaletteRamp, trim: PaletteRamp) = drawOutlined { img ->
        box(img, 10, 7, 12, 4, trim)
        box(img, 8, 11, 16, 14, body)
        box(img, 7, 21, 18, 5, body)
    }

    private fun glovesIcon(body: PaletteRamp, trim: PaletteRamp, gauntlet: Boolean) = drawOutlined { img ->
        box(img, 8, 10, 7, 11, body)
        box(img, 17, 10, 7, 11, body)
        if (gauntlet) {
            box(img, 8, 18, 7, 3, trim)
            box(img, 17, 18, 7, 3, trim)
        }
    }

    private fun armorIcon(body: PaletteRamp, trim: PaletteRamp, robe: Boolean) = drawOutlined { img ->
        box(img, 9, 8, 14, 16, body)
        box(img, 6, 9, 3, 7, body)
        box(img, 23, 9, 3, 7, body)
        box(img, 9, 18, 14, 2, trim)
        if (robe) box(img, 10, 22, 12, 4, trim)
    }

    private fun helmIcon(body: PaletteRamp, trim: PaletteRamp, hat: Boolean, crown: Boolean) = drawOutlined { img ->
        if (crown) {
            box(img, 8, 14, 16, 5, body)
            box(img, 9, 10, 3, 4, body)
            box(img, 15, 9, 3, 5, trim)
            box(img, 20, 10, 3, 4, body)
        } else if (hat) {
            box(img, 6, 18, 20, 3, body)
            box(img, 10, 12, 12, 6, body)
            box(img, 12, 7, 8, 5, body)
            box(img, 10, 16, 12, 2, trim)
        } else {
            circle(img, 16, 15, 8f, body)
            box(img, 8, 16, 16, 6, body)
            box(img, 15, 13, 2, 9, trim)
        }
    }

    private fun bootsIcon(body: PaletteRamp, trim: PaletteRamp) = drawOutlined { img ->
        box(img, 8, 10, 6, 12, body)
        box(img, 8, 19, 9, 4, body)
        box(img, 18, 10, 6, 12, body)
        box(img, 18, 19, 9, 4, body)
        box(img, 8, 10, 6, 2, trim)
        box(img, 18, 10, 6, 2, trim)
    }

    private fun amuletIcon(chain: PaletteRamp, gem: PaletteRamp) = drawOutlined { img ->
        for (dy in -6..2) {
            img.setPixel(16 - 5 + (dy + 6) / 2, 12 + dy, chain.mid)
            img.setPixel(16 + 5 - (dy + 6) / 2, 12 + dy, chain.mid)
        }
        circle(img, 16, 20, 4.5f, gem)
    }

    private fun ringIcon(band: PaletteRamp, gem: PaletteRamp) = drawOutlined { img ->
        circle(img, 16, 17, 6.5f, band)
        circle(img, 16, 11, 2.5f, gem)
    }

    // -------------------------------------------------------------------------
    // Dedicated Skill Icons (301..321) & Ember Arrow Projectile (5)
    // -------------------------------------------------------------------------

    private val fire = PaletteRamp.fromBaseColor(PaletteRamp.rgba(235, 112, 34), steps = 6)
    private val ice = PaletteRamp.fromBaseColor(PaletteRamp.rgba(116, 214, 248), steps = 6)
    private val lightning = PaletteRamp.fromBaseColor(PaletteRamp.rgba(250, 218, 72), steps = 6)
    private val electric = PaletteRamp.fromBaseColor(PaletteRamp.rgba(96, 184, 255), steps = 6)

    fun generateAllSkillIcons(): Map<Int, TileImage> = linkedMapOf(
        301 to slashSkillIcon(steel, false),                    // Keep Slash
        302 to crossSlashSkillIcon(steel),                      // Cross Cut
        303 to slashSkillIcon(gold, true),                      // Finishing Cut
        304 to shieldBashSkillIcon(),                           // Shield Bash
        305 to dashSkillIcon(linen, false),                     // Dodge Roll
        306 to cycloneSkillIcon(),                              // Cyclone
        307 to lightningSkillIcon(lightning, false),            // Lightning Strike
        308 to pullCrySkillIcon(),                              // Gathering Cry
        309 to lightningSkillIcon(electric, true),              // Storm Strike
        310 to arrowSkillIcon(leather, steel, 1, false),        // Quick Shot
        311 to arrowSkillIcon(fire, gold, 1, true),             // Piercing Arrow
        312 to dashSkillIcon(emerald, true),                    // Backstep
        313 to arrowRainSkillIcon(leather, steel, false),       // Scatter Shot
        314 to arrowSkillIcon(leather, steel, 3, false),        // Rapid Fire
        315 to arrowRainSkillIcon(electric, lightning, true),   // Thunder Volley
        316 to snareNetSkillIcon(),                             // Snare Trap
        317 to arcaneBoltSkillIcon(1),                          // Arcane Bolt
        318 to frostNovaSkillIcon(),                            // Frost Nova
        319 to meteorSkillIcon(),                               // Meteor
        320 to arcaneBoltSkillIcon(3),                          // Arcane Missiles
        321 to blinkSkillIcon()                                 // Blink
    )

    fun horseMountIcon(): TileImage = drawOutlined { img ->
        val bay = PaletteRamp.fromBaseColor(PaletteRamp.rgba(148, 86, 48), steps = 6)
        val mane = PaletteRamp.fromBaseColor(PaletteRamp.rgba(44, 30, 22), steps = 6)
        val blanket = PaletteRamp.fromBaseColor(PaletteRamp.rgba(154, 46, 44), steps = 6)
        // Profile horse head + neck + crimson saddle collar facing right
        box(img, 8, 17, 11, 10, bay)     // neck
        box(img, 10, 9, 10, 9, bay)      // head
        box(img, 18, 12, 8, 5, bay)      // muzzle
        box(img, 12, 5, 3, 5, bay)       // ear
        box(img, 6, 10, 4, 15, mane)     // flowing mane
        box(img, 9, 24, 10, 3, blanket)  // saddle/collar trim
        // Bridle + gold bit + eye
        box(img, 19, 12, 1, 5, leather)
        box(img, 14, 15, 7, 1, leather)
        img.setPixel(19, 16, gold.highlight)
        img.setPixel(16, 12, PaletteRamp.rgba(22, 18, 16, 255))
        img.setPixel(17, 12, PaletteRamp.rgba(244, 246, 252, 255))
    }

    fun emberArrowProjectile(): TileImage = TileImage(32, 32).also { img ->
        // Sleek fiery ember arrow pointing RIGHT
        for (x in 5..24) {
            img.setPixel(x, 16, fire.mid)
            img.setPixel(x, 15, fire.highlight)
        }
        // Bright molten arrowhead
        for (dx in 0..4) {
            val h = (4 - dx) / 2
            for (dy in -h..h) img.setPixel(23 + dx, 16 + dy, if (dx >= 2) lightning.highlight else fire.highlight)
        }
        // Glowing ember fletching
        img.setPixel(6, 14, fire.shadow); img.setPixel(7, 14, fire.mid)
        img.setPixel(6, 18, fire.shadow); img.setPixel(7, 18, fire.mid)
        img.setPixel(4, 15, fire.highlight); img.setPixel(4, 17, fire.highlight)
    }

    private fun slashSkillIcon(ramp: PaletteRamp, heavy: Boolean) = drawOutlined { img ->
        val w = if (heavy) 4 else 2
        for (i in 0..16) {
            val x = 7 + i
            val y = 24 - i - ((i - 8) * (i - 8)) / 18
            box(img, x, y, w, w, ramp)
        }
        if (heavy) {
            for (i in 2..14) img.setPixel(8 + i, 22 - i, lightning.highlight)
        }
    }

    private fun crossSlashSkillIcon(ramp: PaletteRamp) = drawOutlined { img ->
        for (i in 0..15) {
            box(img, 7 + i, 7 + i, 2, 2, ramp)
            box(img, 23 - i, 7 + i, 2, 2, ramp)
        }
    }

    private fun shieldBashSkillIcon() = drawOutlined { img ->
        // Kite shield + impact shock lines on right
        box(img, 6, 8, 13, 10, steel)
        box(img, 8, 18, 9, 5, steel)
        box(img, 10, 23, 5, 2, steel)
        box(img, 11, 10, 3, 11, gold)
        // Impact sparks
        for (y in 9..22 step 4) box(img, 21, y, 4, 2, lightning)
        box(img, 24, 13, 3, 5, fire)
    }

    private fun dashSkillIcon(ramp: PaletteRamp, back: Boolean) = drawOutlined { img ->
        val dir = if (back) -1 else 1
        for (row in listOf(10, 16, 22)) {
            box(img, if (back) 12 else 7, row, 12, 2, ramp)
        }
        val tipX = if (back) 8 else 23
        for (i in 0..4) {
            box(img, tipX - dir * i, 16 - i, 2, 2, ramp)
            box(img, tipX - dir * i, 16 + i, 2, 2, ramp)
        }
    }

    private fun cycloneSkillIcon() = drawOutlined { img ->
        // Swirling steel-blue vortex rings
        for (i in 0..24) {
            val ang = i * 0.48
            val r = 3.0 + i * 0.34
            val x = (16 + kotlin.math.cos(ang) * r).toInt()
            val y = (16 + kotlin.math.sin(ang) * r).toInt()
            box(img, x, y, 2, 2, if (i % 2 == 0) ice else steel)
        }
    }

    private fun lightningSkillIcon(ramp: PaletteRamp, storm: Boolean) = drawOutlined { img ->
        // Classic jagged lightning bolt
        for (i in 0..8) box(img, 19 - i / 2, 5 + i, 4, 2, ramp)
        box(img, 11, 13, 11, 3, ramp)
        for (i in 0..10) box(img, 19 - i / 2, 15 + i, 3, 2, ramp)
        if (storm) {
            for (i in 0..6) {
                box(img, 7 + i / 2, 8 + i, 2, 2, lightning)
                box(img, 24 - i / 2, 16 + i, 2, 2, lightning)
            }
        }
    }

    private fun pullCrySkillIcon() = drawOutlined { img ->
        // Inward-pulling amber rings + center burst
        circle(img, 16, 16, 4.5f, fire)
        for (i in 0 until 8) {
            val ang = i * (Math.PI / 4.0)
            for (r in 7..11 step 2) {
                val x = (16 + kotlin.math.cos(ang) * r).toInt()
                val y = (16 + kotlin.math.sin(ang) * r).toInt()
                box(img, x, y, 2, 2, lightning)
            }
        }
    }

    private fun arrowSkillIcon(shaft: PaletteRamp, head: PaletteRamp, count: Int, fiery: Boolean) = drawOutlined { img ->
        val offsets = when (count) {
            3 -> listOf(-4 to -4, 0 to 0, 4 to 4)
            else -> listOf(0 to 0)
        }
        for ((ox, oy) in offsets) {
            for (i in 0..12) box(img, 8 + ox + i, 22 + oy - i, 2, 2, shaft)
            box(img, 20 + ox, 8 + oy, 4, 4, head)
            if (fiery) {
                box(img, 6 + ox, 22 + oy, 3, 3, lightning)
                box(img, 18 + ox, 10 + oy, 3, 3, fire)
            }
        }
    }

    private fun arrowRainSkillIcon(shaft: PaletteRamp, head: PaletteRamp, thunder: Boolean) = drawOutlined { img ->
        for ((ax, ay) in listOf(9 to 7, 16 to 5, 22 to 8)) {
            box(img, ax, ay, 2, 13, shaft)
            box(img, ax - 1, ay + 12, 4, 3, head)
        }
        if (thunder) {
            for (i in 0..8) box(img, 14 + (i % 3) - 1, 10 + i * 2, 2, 2, lightning)
        }
    }

    private fun snareNetSkillIcon() = drawOutlined { img ->
        // Crosshatched rope net
        for (x in 8..22 step 5) box(img, x, 8, 2, 16, leather)
        for (y in 8..22 step 5) box(img, 8, y, 16, 2, leather)
        for (x in 8..22 step 5) for (y in 8..22 step 5) box(img, x, y, 2, 2, ice)
    }

    private fun arcaneBoltSkillIcon(count: Int) = drawOutlined { img ->
        if (count == 1) {
            for (i in 0..8) box(img, 7 + i, 22 - i, 3, 3, violet)
            circle(img, 19, 12, 5.2f, violet)
            circle(img, 20, 11, 2.5f, ice)
        } else {
            for ((cx, cy) in listOf(10 to 12, 19 to 9, 21 to 19)) {
                circle(img, cx, cy, 3.6f, violet)
                circle(img, cx, cy, 1.6f, ice)
            }
        }
    }

    private fun frostNovaSkillIcon() = drawOutlined { img ->
        // 8-point crystalline snowflake burst
        box(img, 15, 6, 2, 20, ice)
        box(img, 6, 15, 20, 2, ice)
        for (i in -7..7) {
            box(img, 15 + i, 15 + i, 2, 2, electric)
            box(img, 15 + i, 15 - i, 2, 2, electric)
        }
        circle(img, 16, 16, 3.2f, ice)
    }

    private fun meteorSkillIcon() = drawOutlined { img ->
        // Blazing meteor + fiery tail from top-right to bottom-left
        for (i in 0..10) {
            val w = 3 + (10 - i) / 3
            box(img, 14 + i, 14 - i, w, w, if (i % 2 == 0) fire else lightning)
        }
        circle(img, 12, 20, 6.2f, fire)
        circle(img, 11, 20, 3.4f, lightning)
    }

    private fun blinkSkillIcon() = drawOutlined { img ->
        // Violet/cyan teleport shimmer rings
        circle(img, 11, 19, 4.5f, violet)
        circle(img, 20, 12, 5.5f, ice)
        circle(img, 20, 12, 2.8f, violet)
        box(img, 13, 15, 4, 2, ice)
    }

    // -------------------------------------------------------------------------
    // Palette & JSON Helpers
    // -------------------------------------------------------------------------

    private fun rampFor(n: String, level: Int): PaletteRamp = when {
        anyOf(n, "silver", "mithril") -> silver
        anyOf(n, "gold", "pendragon", "royal") -> gold
        anyOf(n, "meteoric", "steel", "knight") -> steel
        "iron" in n -> iron
        "bronze" in n -> bronze
        "copper" in n -> copper
        anyOf(n, "astral", "archmage", "magister", "runic") -> violet
        anyOf(n, "silk", "spellweaver", "whispering") -> violet
        anyOf(n, "wool", "apprentice") -> wool
        "linen" in n -> linen
        anyOf(n, "sylvan", "yew", "flax", "emerald") -> emerald
        anyOf(n, "hardened", "marcher", "boar", "oak") -> darkLeather
        anyOf(n, "rawhide", "padded", "worn", "rough", "cracked", "ash", "birch", "gnarled", "tattered", "rusty") -> leather
        level >= 22 -> gold
        level >= 14 -> steel
        else -> leather
    }

    private fun trimFor(n: String, level: Int): PaletteRamp = when {
        anyOf(n, "astral", "archmage", "magister", "runic") -> sapphire
        anyOf(n, "sylvan") -> emerald
        level >= 20 -> gold
        level >= 10 -> silver
        else -> bronze
    }

    private fun anyOf(s: String, vararg keys: String): Boolean = keys.any { it in s }

    private fun extractInt(json: String, key: String): Int? =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractStr(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
}
