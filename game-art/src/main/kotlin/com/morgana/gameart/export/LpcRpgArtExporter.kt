package com.morgana.gameart.export

import com.morgana.gameart.sprite.RpgEnemyKind
import com.morgana.gameart.sprite.RpgNpcKind
import com.morgana.gameart.sprite.RpgSpriteGenerator
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.export.PngEncoder
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.sprite.AttachmentKind
import com.morgana.tilegen.sprite.BodyAncestry
import com.morgana.tilegen.sprite.Direction8
import com.morgana.tilegen.sprite.FeetStyle
import com.morgana.tilegen.sprite.GloveStyle
import com.morgana.tilegen.sprite.HairStyle
import com.morgana.tilegen.sprite.HeadgearKind
import com.morgana.tilegen.sprite.LegsStyle
import com.morgana.tilegen.sprite.LpcMountRenderer
import com.morgana.tilegen.sprite.MountPalette
import com.morgana.tilegen.sprite.PaperDollAction
import com.morgana.tilegen.sprite.PaperDollJob
import com.morgana.tilegen.sprite.PaperDollLoadout
import com.morgana.tilegen.sprite.PaperDollProportions
import com.morgana.tilegen.sprite.PaperDollSlot
import com.morgana.tilegen.sprite.PaperDollTheme
import com.morgana.tilegen.sprite.PaperDollThemes
import com.morgana.tilegen.sprite.PaperDollSpriteGenerator
import com.morgana.tilegen.sprite.ShieldKind
import com.morgana.tilegen.sprite.TorsoStyle
import com.morgana.tilegen.sprite.WeaponKind
import java.nio.file.Files
import java.nio.file.Path

/**
 * Exports native 64x64 LPC-style player/NPC sprites, modular paper-doll gear layers,
 * and procedural RPG icons into a game's `<gameDir>/assets/` directory (`sprites/`, `paperdolls/`, `icons/`).
 */
object LpcRpgArtExporter {
    const val CELL_W = 64
    const val CELL_H = 64

    private data class State(val name: String, val action: PaperDollAction, val frames: Int)

    private val STATES = listOf(
        State("idle", PaperDollAction.IDLE, 2),
        State("walk", PaperDollAction.WALK, 4),
        State("attack", PaperDollAction.SLASH, 6),
        State("cast", PaperDollAction.CAST, 4),
        State("harvest", PaperDollAction.HARVEST, 4),
        State("jump", PaperDollAction.JUMP, 3),
        State("hurt", PaperDollAction.HURT, 2),
        State("pickup", PaperDollAction.PICKUP, 4),
        State("ride", PaperDollAction.RIDE, 4)
    )
    val COLUMNS: Int = STATES.sumOf { it.frames }
    private val DIRECTIONS = listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N)

    private val GEAR_LAYERS = setOf(
        PaperDollSlot.HEADGEAR,
        PaperDollSlot.MAIN_HAND,
        PaperDollSlot.OFF_HAND,
        PaperDollSlot.AURA_FRONT,
        PaperDollSlot.AURA_BACK,
        PaperDollSlot.SHADOW,
        PaperDollSlot.BACK_ATTACHMENT,
        PaperDollSlot.CAPE,
        PaperDollSlot.HANDS,
        PaperDollSlot.TORSO,
        PaperDollSlot.FEET
    )

    class Gear(
        val id: Int,
        val slot: PaperDollSlot,
        val shoot: Boolean = false,
        val extraSlot: PaperDollSlot? = null,
        val wear: (PaperDollLoadout) -> PaperDollLoadout
    )

    val GEAR: List<Gear> = listOf(
        Gear(1, PaperDollSlot.MAIN_HAND, shoot = true) { it.copy(weapon = WeaponKind.COMPOSITE_BOW) },
        Gear(2, PaperDollSlot.BACK_ATTACHMENT, shoot = true) { it.copy(attachments = listOf(AttachmentKind.QUIVER)) },
        Gear(3, PaperDollSlot.MAIN_HAND) { it.copy(weapon = WeaponKind.SINGLE_BLADE) },
        Gear(4, PaperDollSlot.MAIN_HAND) { it.copy(weapon = WeaponKind.KNIGHT_SWORD) },
        Gear(5, PaperDollSlot.MAIN_HAND) { it.copy(weapon = WeaponKind.ARCANE_STAFF) },
        Gear(6, PaperDollSlot.MAIN_HAND) { it.copy(weapon = WeaponKind.HOLY_ROD) },
        Gear(7, PaperDollSlot.OFF_HAND) { it.copy(shield = ShieldKind.ROUND_BUCKLER) },
        Gear(8, PaperDollSlot.OFF_HAND) { it.copy(shield = ShieldKind.CRUSADER_CROSS_SHIELD) },
        Gear(9, PaperDollSlot.OFF_HAND) { it.copy(shield = ShieldKind.OFFHAND_BLADE) },
        Gear(10, PaperDollSlot.OFF_HAND) { it.copy(shield = ShieldKind.SPELLBOOK) },
        Gear(11, PaperDollSlot.TORSO) { it.copy(torsoStyle = TorsoStyle.BRIGANDINE) },
        Gear(12, PaperDollSlot.TORSO, shoot = true) { it.copy(torsoStyle = TorsoStyle.TRAVELER) },
        Gear(13, PaperDollSlot.TORSO) { it.copy(torsoStyle = TorsoStyle.SKYRENDER) },
        Gear(14, PaperDollSlot.HEADGEAR) { it.copy(headgear = HeadgearKind.IRON_HELMET) },
        Gear(15, PaperDollSlot.HEADGEAR) { it.copy(headgear = HeadgearKind.FEATHER_CAP) },
        Gear(16, PaperDollSlot.HEADGEAR) { it.copy(headgear = HeadgearKind.WIZARD_HAT) },
        Gear(17, PaperDollSlot.HEADGEAR) { it.copy(headgear = HeadgearKind.PRIEST_MITRE) },
        Gear(18, PaperDollSlot.HANDS) { it.copy(gloves = GloveStyle.GAUNTLET) },
        Gear(19, PaperDollSlot.HANDS, shoot = true) { it.copy(gloves = GloveStyle.LEATHER) },
        Gear(20, PaperDollSlot.HANDS) { it.copy(gloves = GloveStyle.WRAPS) },
        Gear(21, PaperDollSlot.LEGS, extraSlot = PaperDollSlot.FEET) { it.copy(legsStyle = LegsStyle.GREAVES, feetStyle = FeetStyle.SABATONS) },
        Gear(22, PaperDollSlot.LEGS, extraSlot = PaperDollSlot.FEET) { it.copy(legsStyle = LegsStyle.TROUSERS, feetStyle = FeetStyle.BOOTS) },
        Gear(23, PaperDollSlot.LEGS, extraSlot = PaperDollSlot.FEET) { it.copy(legsStyle = LegsStyle.ROBE_SKIRT, feetStyle = FeetStyle.SANDALS) },
        Gear(24, PaperDollSlot.CAPE) { it.copy(includeCape = true) }
    )

    fun exportAll(gameDir: Path, seed: Long = 1337L): List<Path> {
        val assetsDir = gameDir.resolve("assets")
        val written = mutableListOf<Path>()

        fun writePng(sub: String, id: Int, img: TileImage) {
            val dir = assetsDir.resolve(sub)
            Files.createDirectories(dir)
            val path = dir.resolve("$id.png")
            PngEncoder.writeToFile(img, path)
            written.add(path)
        }

        val doll = PaperDollSpriteGenerator(seed = seed, tileSize = CELL_W, tileHeight = CELL_H)

        // 1.png: Player base body (underclothes + skin + hair, no gear layers)
        val naturalSkin = PaletteRamp.fromBaseColor(
            PaletteRamp.rgba(226, 178, 144),
            steps = 6,
            hueShift = 0.05f,
            valueSpread = 0.28f
        )
        val darkHair = PaletteRamp.fromBaseColor(
            PaletteRamp.rgba(56, 46, 62),
            steps = 6,
            hueShift = 0.05f,
            valueSpread = 0.30f
        )
        val undercloth = PaletteRamp.fromBaseColor(PaletteRamp.rgba(164, 158, 148), steps = 6)
        val baseTheme = PaperDollTheme(
            name = "lpc_player_base",
            ancestry = BodyAncestry.HUMAN,
            skinPalette = naturalSkin,
            hairPalette = darkHair,
            outfitPalette = undercloth,
            accentPalette = PalettePresets.DARK_IRON,
            secondaryPalette = undercloth,
            style = PixelStyle.MORGANA,
            proportions = PaperDollProportions.LPC_64X64
        )
        val playerBase = PaperDollLoadout(
            job = PaperDollJob.HERO,
            hairStyle = HairStyle.NOBLE_PARTED,
            headgear = HeadgearKind.NONE,
            weapon = WeaponKind.NONE,
            shield = ShieldKind.NONE,
            torsoStyle = TorsoStyle.TRAVELER,
            legsStyle = LegsStyle.TROUSERS,
            feetStyle = FeetStyle.BAREFOOT,
            gloves = GloveStyle.NONE,
            includeCape = false,
            attachments = emptyList(),
            theme = baseTheme
        )
        val playerSheet = sheet(shoot = false) { dir, action, f ->
            val (layers, _) = doll.generateLayeredFrame(playerBase, dir, action, f, PixelStyle.MORGANA)
            doll.compositeLayers(layers.filterKeys { it !in GEAR_LAYERS }, dir, action, includeShadow = false)
        }
        writePng("sprites", 1, playerSheet)

        // 11.png: Ranger player base body (uses SHOOT pose on attack frames to sync with bows)
        val rangerSheet = sheet(shoot = true) { dir, action, f ->
            val (layers, _) = doll.generateLayeredFrame(playerBase, dir, action, f, PixelStyle.MORGANA)
            doll.compositeLayers(layers.filterKeys { it !in GEAR_LAYERS }, dir, action, includeShadow = false)
        }
        writePng("sprites", 11, rangerSheet)

        // 2.png..8.png: LPC 64x64 NPCs & humanoid enemies
        val npcSpecs: List<Pair<Int, PaperDollLoadout>> = listOf(
            // 2: Town Guide
            2 to playerBase.copy(
                job = PaperDollJob.SMITH,
                torsoStyle = TorsoStyle.TRAVELER,
                feetStyle = FeetStyle.BOOTS,
                includeCape = true,
                theme = baseTheme.copy(
                    outfitPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(68, 108, 156), steps = 6),
                    accentPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(196, 162, 82), steps = 6)
                )
            ),
            // 3: Apothecary
            3 to playerBase.copy(
                job = PaperDollJob.PRIEST,
                torsoStyle = TorsoStyle.SKYRENDER,
                headgear = HeadgearKind.FEATHER_CAP,
                feetStyle = FeetStyle.SANDALS,
                theme = baseTheme.copy(
                    outfitPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(86, 134, 102), steps = 6),
                    accentPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(164, 118, 184), steps = 6)
                )
            ),
            // 4: Blacksmith
            4 to playerBase.copy(
                job = PaperDollJob.SMITH,
                torsoStyle = TorsoStyle.BRIGANDINE,
                gloves = GloveStyle.GAUNTLET,
                feetStyle = FeetStyle.BOOTS,
                theme = baseTheme.copy(
                    outfitPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(112, 82, 62), steps = 6),
                    accentPalette = PalettePresets.DARK_IRON
                )
            ),
            // 5: Carpenter & Tailor
            5 to playerBase.copy(
                job = PaperDollJob.HUNTER,
                torsoStyle = TorsoStyle.TRAVELER,
                feetStyle = FeetStyle.BOOTS,
                theme = baseTheme.copy(
                    outfitPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(148, 112, 74), steps = 6),
                    accentPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(182, 148, 92), steps = 6)
                )
            ),
            // 6: Bandit
            6 to playerBase.copy(
                job = PaperDollJob.ASSASSIN,
                torsoStyle = TorsoStyle.TRAVELER,
                headgear = HeadgearKind.BANDANA,
                weapon = WeaponKind.SINGLE_BLADE,
                feetStyle = FeetStyle.BOOTS,
                theme = baseTheme.copy(
                    outfitPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(96, 72, 66), steps = 6),
                    accentPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(156, 62, 58), steps = 6)
                )
            ),
            // 7: Corrupted Marauder
            7 to playerBase.copy(
                job = PaperDollJob.KNIGHT,
                torsoStyle = TorsoStyle.BRIGANDINE,
                headgear = HeadgearKind.IRON_HELMET,
                weapon = WeaponKind.KNIGHT_SWORD,
                shield = ShieldKind.ROUND_BUCKLER,
                feetStyle = FeetStyle.SABATONS,
                theme = baseTheme.copy(
                    outfitPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(82, 76, 94), steps = 6),
                    accentPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(148, 56, 78), steps = 6)
                )
            ),
            // 8: Brute
            8 to playerBase.copy(
                job = PaperDollJob.PALADIN,
                torsoStyle = TorsoStyle.BRIGANDINE,
                headgear = HeadgearKind.STEEL_HELMET,
                weapon = WeaponKind.KNIGHT_SWORD,
                gloves = GloveStyle.GAUNTLET,
                feetStyle = FeetStyle.SABATONS,
                theme = baseTheme.copy(
                    outfitPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(124, 58, 52), steps = 6),
                    accentPalette = PalettePresets.DARK_IRON
                )
            )
        )
        for ((id, loadout) in npcSpecs) {
            val npcSheet = sheet(shoot = false) { dir, action, f ->
                val (layers, _) = doll.generateLayeredFrame(loadout, dir, action, f, PixelStyle.MORGANA)
                doll.compositeLayers(layers, dir, action, includeShadow = false)
            }
            writePng("sprites", id, npcSheet)
        }

        // 9.png (Forest Wolf) & 10.png (Training Dummy) at native 64x64
        writePng("sprites", 9, sheet(shoot = false) { dir, action, f ->
            drawWolfFrame(dir, action, f)
        })
        writePng("sprites", 10, sheet(shoot = false) { dir, action, f ->
            drawDummyFrame(dir, action, f)
        })

        // Icons, Projectiles, and Skill/Spell Animations
        RoguelikeIconArt.allIcons().forEach { (id, img) -> writePng("icons", id, img) }
        ProceduralRpgIconArt.generateAllItemIcons(gameDir).forEach { (id, img) -> writePng("icons", id, img) }
        ProceduralRpgIconArt.generateAllSkillIcons().forEach { (id, img) -> writePng("icons", id, img) }
        writePng("icons", 340, ProceduralRpgIconArt.horseMountIcon())
        RoguelikeIconArt.allProjectiles().forEach { (id, img) -> writePng("projectiles", id, img) }
        writePng("projectiles", 5, ProceduralRpgIconArt.emberArrowProjectile())
        RoguelikeIconArt.allAnimations().forEach { (id, img) -> writePng("animations", id, img) }
        writePng("animations", 7, ProceduralRpgIconArt.swordSlashStrip())

        // Paperdoll gear layers (neutral white/greyscale so paperdollTintHex tints cleanly)
        val neutralBase = neutralLoadout()
        for (gear in GEAR) {
            val loadout = gear.wear(neutralBase)
            val img = sheet(gear.shoot) { dir, action, f ->
                val (layers, _) = doll.generateLayeredFrame(loadout, dir, action, f, PixelStyle.MORGANA)
                val primary = layers[gear.slot] ?: TileImage(CELL_W, CELL_H)
                val extra = gear.extraSlot?.let { layers[it] }
                if (extra == null) {
                    primary
                } else {
                    doll.compositeLayers(
                        mapOf(gear.slot to primary, gear.extraSlot to extra),
                        dir,
                        action,
                        includeShadow = false
                    )
                }
            }
            writePng("paperdolls", gear.id, img)
        }

        // Mount paperdoll sheets (drawn underneath the seated rider)
        writePng("paperdolls", 25, sheet(shoot = false) { dir, action, f ->
            LpcMountRenderer.renderHorseFrame(MountPalette.BAY_HORSE, dir, f, action)
        })

        return written
    }

    private fun neutralLoadout(): PaperDollLoadout = PaperDollLoadout(
        job = PaperDollJob.HERO,
        hairStyle = HairStyle.NOBLE_PARTED,
        headgear = HeadgearKind.NONE,
        weapon = WeaponKind.NONE,
        shield = ShieldKind.NONE,
        torsoStyle = TorsoStyle.TRAVELER,
        legsStyle = LegsStyle.TROUSERS,
        feetStyle = FeetStyle.BOOTS,
        gloves = GloveStyle.NONE,
        includeCape = false,
        attachments = emptyList(),
        theme = PaperDollThemes.NEUTRAL_THEME.copy(
            style = PixelStyle.MORGANA,
            proportions = PaperDollProportions.LPC_64X64
        )
    )

    private inline fun sheet(
        shoot: Boolean,
        renderCell: (Direction8, PaperDollAction, Int) -> TileImage
    ): TileImage {
        val out = TileImage(COLUMNS * CELL_W, DIRECTIONS.size * CELL_H)
        for ((row, dir) in DIRECTIONS.withIndex()) {
            var col = 0
            for (state in STATES) {
                val action = if (shoot && state.action == PaperDollAction.SLASH) PaperDollAction.SHOOT else state.action
                for (f in 0 until state.frames) {
                    val cell = renderCell(dir, action, f)
                    out.drawImage(cell, 0, 0, CELL_W, CELL_H, col * CELL_W, row * CELL_H)
                    col++
                }
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Native 64x64 Forest Wolf & Training Dummy
    // -------------------------------------------------------------------------

    private fun drawWolfFrame(dir: Direction8, action: PaperDollAction, frame: Int): TileImage {
        val raw = TileImage(CELL_W, CELL_H)
        val fur = PaletteRamp.fromBaseColor(PaletteRamp.rgba(116, 118, 128), steps = 6)
        val belly = PaletteRamp.fromBaseColor(PaletteRamp.rgba(182, 184, 192), steps = 5)
        val outline = PaletteRamp.rgba(28, 28, 36, 255)
        val eye = PaletteRamp.rgba(235, 192, 62, 255)
        val nose = PaletteRamp.rgba(32, 30, 38, 255)

        val lunge = if (action == PaperDollAction.SLASH && frame in 1..2) -4 else 0
        val bob = if (action == PaperDollAction.WALK && frame % 2 == 1) -1 else if (action == PaperDollAction.IDLE && frame % 2 == 1) 1 else 0
        val stride = if (action == PaperDollAction.WALK) when (frame % 4) { 0 -> 3; 2 -> -3; else -> 0 } else 0

        fun box(x: Int, y: Int, w: Int, h: Int, ramp: PaletteRamp, bias: Float = 0f) {
            for (ry in 0 until h) for (rx in 0 until w) {
                val t = (0.85f - (rx / w.coerceAtLeast(1).toFloat()) * 0.35f - (ry / h.coerceAtLeast(1).toFloat()) * 0.4f - bias).coerceIn(0.1f, 0.95f)
                raw.setPixel(x + rx, y + ry, ramp.sample(t))
            }
        }

        when (dir) {
            Direction8.S -> {
                val cy = 40 + bob
                // Back legs
                box(24, cy + 8, 4, 10, fur, 0.25f)
                box(36, cy + 8, 4, 10, fur, 0.25f)
                // Body
                box(25, cy, 14, 11, fur)
                box(28, cy + 6, 8, 5, belly)
                // Front legs
                box(26, cy + 9 - (if (stride > 0) 2 else 0), 3, 10, fur)
                box(35, cy + 9 - (if (stride < 0) 2 else 0), 3, 10, fur)
                // Head & ears
                box(26, cy - 8, 12, 10, fur)
                box(26, cy - 12, 3, 4, fur)
                box(35, cy - 12, 3, 4, fur)
                // Snout & eyes
                box(29, cy - 2, 6, 4, belly)
                raw.setPixel(31, cy, nose); raw.setPixel(32, cy, nose)
                raw.setPixel(28, cy - 4, eye); raw.setPixel(35, cy - 4, eye)
            }
            Direction8.N -> {
                val cy = 40 + bob
                box(25, cy, 14, 12, fur, 0.1f)
                box(26, cy + 10, 3, 9, fur)
                box(35, cy + 10, 3, 9, fur)
                box(26, cy - 8, 12, 10, fur, 0.1f)
                box(26, cy - 12, 3, 4, fur)
                box(35, cy - 12, 3, 4, fur)
                // Tail
                box(30, cy + 6, 4, 9, fur, 0.2f)
            }
            else -> {
                // Side view facing W (mirror for E)
                val cx = 32 + lunge
                val cy = 42 + bob
                // Far legs
                box(cx - 8 - stride, cy + 7, 3, 10, fur, 0.25f)
                box(cx + 6 + stride, cy + 7, 3, 10, fur, 0.25f)
                // Torso + chest ruff
                box(cx - 10, cy, 20, 9, fur)
                box(cx - 9, cy + 5, 16, 4, belly)
                // Tail
                box(cx + 9, cy - 2, 7, 4, fur, 0.1f)
                // Near legs
                box(cx - 9 + stride, cy + 7, 3, 11, fur)
                box(cx + 5 - stride, cy + 7, 3, 11, fur)
                // Head, ears, snout
                box(cx - 16, cy - 7, 10, 9, fur)
                box(cx - 10, cy - 11, 3, 4, fur)
                box(cx - 21, cy - 3, 6, 4, fur)
                raw.setPixel(cx - 21, cy - 3, nose)
                raw.setPixel(cx - 14, cy - 4, eye)
            }
        }

        val shaped = if (dir == Direction8.E) mirrorHorizontal(raw) else raw
        return outline64(shaped, outline)
    }

    private fun drawDummyFrame(dir: Direction8, action: PaperDollAction, frame: Int): TileImage {
        val raw = TileImage(CELL_W, CELL_H)
        val wood = PaletteRamp.fromBaseColor(PaletteRamp.rgba(134, 94, 58), steps = 5)
        val straw = PaletteRamp.fromBaseColor(PaletteRamp.rgba(212, 182, 104), steps = 5)
        val target = PaletteRamp.fromBaseColor(PaletteRamp.rgba(196, 68, 58), steps = 4)
        val outline = PaletteRamp.rgba(34, 24, 18, 255)

        val wobble = if (action == PaperDollAction.HURT) (if (frame % 2 == 0) -2 else 2) else 0
        val cx = 32 + wobble

        fun box(x: Int, y: Int, w: Int, h: Int, ramp: PaletteRamp) {
            for (ry in 0 until h) for (rx in 0 until w) {
                val t = (0.85f - (rx / w.coerceAtLeast(1).toFloat()) * 0.35f - (ry / h.coerceAtLeast(1).toFloat()) * 0.4f).coerceIn(0.12f, 0.95f)
                raw.setPixel(x + rx, y + ry, ramp.sample(t))
            }
        }

        // Wooden base & post
        box(26, 56, 12, 4, wood)
        box(30, 38, 4, 20, wood)
        // Crossbar arms
        box(cx - 11, 31, 22, 3, wood)
        // Straw torso
        box(cx - 6, 28, 12, 14, straw)
        // Painted target on front/side
        if (dir != Direction8.N) {
            box(cx - 3, 32, 6, 6, target)
            raw.setPixel(cx - 1, 34, straw.highlight); raw.setPixel(cx, 34, straw.highlight)
            raw.setPixel(cx - 1, 35, straw.highlight); raw.setPixel(cx, 35, straw.highlight)
        }
        // Straw head
        box(cx - 5, 16, 10, 11, straw)
        box(cx - 2, 26, 4, 3, wood)

        return outline64(raw, outline)
    }

    private fun mirrorHorizontal(src: TileImage): TileImage {
        val out = TileImage(CELL_W, CELL_H)
        for (y in 0 until CELL_H) for (x in 0 until CELL_W) {
            out.setPixel(CELL_W - 1 - x, y, src.getPixel(x, y))
        }
        return out
    }

    private fun outline64(src: TileImage, outlineRgba: Int): TileImage {
        val dst = TileImage(CELL_W, CELL_H)
        for (y in 0 until CELL_H) for (x in 0 until CELL_W) {
            val c = src.getPixel(x, y)
            if ((c and 0xFF) != 0) {
                dst.setPixel(x, y, c)
            } else {
                val hasNeighbor =
                    ((src.getPixel(x - 1, y) and 0xFF) != 0) ||
                        ((src.getPixel(x + 1, y) and 0xFF) != 0) ||
                        ((src.getPixel(x, y - 1) and 0xFF) != 0) ||
                        ((src.getPixel(x, y + 1) and 0xFF) != 0)
                if (hasNeighbor) dst.setPixel(x, y, outlineRgba)
            }
        }
        return dst
    }
}
