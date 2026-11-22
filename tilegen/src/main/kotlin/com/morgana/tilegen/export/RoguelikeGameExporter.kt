package com.morgana.tilegen.export

import com.morgana.tilegen.ThemedTilesetGenerator
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.DecalKind
import com.morgana.tilegen.material.MaterialPattern
import com.morgana.tilegen.material.MaterialSpec
import com.morgana.tilegen.material.MaterialTileGenerator
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelDither
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.material.TileDecals
import com.morgana.tilegen.sprite.AttachmentKind
import com.morgana.tilegen.sprite.BodyAncestry
import com.morgana.tilegen.sprite.Direction8
import com.morgana.tilegen.sprite.FeetStyle
import com.morgana.tilegen.sprite.HairStyle
import com.morgana.tilegen.sprite.HalcyonEnemyKind
import com.morgana.tilegen.sprite.HalcyonNpcKind
import com.morgana.tilegen.sprite.HalcyonSpriteGenerator
import com.morgana.tilegen.sprite.HeadgearKind
import com.morgana.tilegen.sprite.LegsStyle
import com.morgana.tilegen.sprite.PaperDollAction
import com.morgana.tilegen.sprite.PaperDollJob
import com.morgana.tilegen.sprite.PaperDollLoadout
import com.morgana.tilegen.sprite.PaperDollProportions
import com.morgana.tilegen.sprite.PaperDollSlot
import com.morgana.tilegen.sprite.PaperDollSpriteGenerator
import com.morgana.tilegen.sprite.PaperDollTheme
import com.morgana.tilegen.sprite.PaperDollThemes
import com.morgana.tilegen.sprite.ShieldKind
import com.morgana.tilegen.sprite.TorsoStyle
import com.morgana.tilegen.sprite.WeaponKind
import com.morgana.tilegen.structure.BuildingGenerator
import com.morgana.tilegen.structure.BuildingKind
import com.morgana.tilegen.structure.BuildingRoof
import com.morgana.tilegen.structure.BuildingTheme
import com.morgana.tilegen.structure.GroundDetailGenerator
import com.morgana.tilegen.structure.PavingGenerator
import com.morgana.tilegen.structure.PavingKind
import com.morgana.tilegen.structure.PlazaGenerator
import com.morgana.tilegen.structure.PropGenerator
import com.morgana.tilegen.structure.PropKind
import java.nio.file.Files
import java.nio.file.Path

/**
 * Exports all game-owned art for the reference dungeon crawler / roguelike (*Ashen Keep*, `games/roguelike`)
 * using the Halcyon Reach `:tilegen` workflow:
 * - **Tilesets (`tilesets/1..7.png`)**: `PavingGenerator`, `BuildingGenerator`, `PlazaGenerator`, `GroundDetailGenerator`,
 *   `PropGenerator`, and a soft-shaded `MORGANA` Sunken Crypt dungeon sheet.
 * - **Sprites (`sprites/1..3, 10..17, 101..106.png`)**: 32x48 RMXP-proportioned, 23-column x 4-direction sheets in
 *   `PixelStyle.MORGANA` for the 3 playable classes, 6 hub NPCs, 2 dungeon features, 5 crypt monsters, and 1 Floor 1 boss.
 * - **Paperdolls (`paperdolls/1..16.png`)**: directional worn-gear overlays matching the 32x48 layout.
 * - **Icons, Projectiles & Animations**: 32x32 soft-shaded icons (`icons/1..41.png`), projectiles (`projectiles/1..4.png`),
 *   and 5-frame 160x32 combat/spell effect strips (`animations/1..6.png`).
 *
 * Only writes the asset IDs it owns inside `<gameDir>/assets/`; never touches `data/` or `game.json`.
 */
object RoguelikeGameExporter {
    const val CELL_W = 32
    const val CELL_H = 48

    private data class State(val name: String, val action: PaperDollAction, val frames: Int)

    private val STATES = listOf(
        State("idle", PaperDollAction.IDLE, 2),
        State("walk", PaperDollAction.WALK, 4),
        State("attack", PaperDollAction.SLASH, 4),
        State("cast", PaperDollAction.CAST, 4),
        State("harvest", PaperDollAction.HARVEST, 4),
        State("jump", PaperDollAction.JUMP, 3),
        State("hurt", PaperDollAction.HURT, 2)
    )
    val COLUMNS: Int = STATES.sumOf { it.frames }
    private val DIRECTIONS = listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N)

    private val GEAR_LAYERS = setOf(
        PaperDollSlot.HEADGEAR, PaperDollSlot.MAIN_HAND, PaperDollSlot.OFF_HAND, PaperDollSlot.AURA_FRONT,
        PaperDollSlot.AURA_BACK, PaperDollSlot.SHADOW, PaperDollSlot.BACK_ATTACHMENT, PaperDollSlot.CAPE
    )

    class Gear(val id: Int, val slot: PaperDollSlot, val shoot: Boolean = false, val wear: (PaperDollLoadout) -> PaperDollLoadout)

    val GEAR: List<Gear> = listOf(
        Gear(1, PaperDollSlot.MAIN_HAND, shoot = true) { it.copy(weapon = WeaponKind.COMPOSITE_BOW) },
        Gear(2, PaperDollSlot.BACK_ATTACHMENT, shoot = true) { it.copy(attachments = listOf(AttachmentKind.QUIVER)) },
        Gear(3, PaperDollSlot.MAIN_HAND) { it.copy(weapon = WeaponKind.SINGLE_BLADE) },
        Gear(4, PaperDollSlot.MAIN_HAND) { it.copy(weapon = WeaponKind.KNIGHT_SWORD) },
        Gear(5, PaperDollSlot.MAIN_HAND) { it.copy(weapon = WeaponKind.KATANA) },
        Gear(6, PaperDollSlot.MAIN_HAND) { it.copy(weapon = WeaponKind.ARCANE_STAFF) },
        Gear(7, PaperDollSlot.OFF_HAND) { it.copy(shield = ShieldKind.ROUND_BUCKLER) },
        Gear(8, PaperDollSlot.OFF_HAND) { it.copy(shield = ShieldKind.TOWER_SHIELD) },
        Gear(9, PaperDollSlot.HEADGEAR) { it.copy(headgear = HeadgearKind.IRON_HELMET) },
        Gear(10, PaperDollSlot.HEADGEAR) { it.copy(headgear = HeadgearKind.STEEL_HELMET) },
        Gear(11, PaperDollSlot.HEADGEAR) { it.copy(headgear = HeadgearKind.SKYRENDER_HELM) },
        Gear(12, PaperDollSlot.TORSO) { it.copy(torsoStyle = TorsoStyle.TRAVELER) },
        Gear(13, PaperDollSlot.TORSO) { it.copy(torsoStyle = TorsoStyle.BRIGANDINE) },
        Gear(14, PaperDollSlot.TORSO) { it.copy(torsoStyle = TorsoStyle.SKYRENDER) },
        Gear(15, PaperDollSlot.FEET) { it.copy(feetStyle = FeetStyle.BOOTS) },
        Gear(16, PaperDollSlot.FEET) { it.copy(feetStyle = FeetStyle.SABATONS) }
    )

    /** Hub & feature NPC sprite ID -> HalcyonNpcKind preset. */
    private val NPC_MAP: List<Pair<Int, HalcyonNpcKind>> = listOf(
        10 to HalcyonNpcKind.CAPTAIN,        // Commander Vael (Quest Giver)
        11 to HalcyonNpcKind.ASHGATE_WARDEN, // Delve Warden Orin (Difficulty)
        12 to HalcyonNpcKind.PHARMACIST,     // Apothecary Lyra (Supplies)
        13 to HalcyonNpcKind.BLACKSMITH,     // Forgemaster Torin (Armory)
        14 to HalcyonNpcKind.GEMWRIGHT,      // Relic Broker Vex (Curios)
        15 to HalcyonNpcKind.WAREHOUSEMAN,   // Vaultkeeper Maren (Banker)
        16 to HalcyonNpcKind.CLASS_MASTER,   // Wayside Shrine Keeper (Room Feature)
        17 to HalcyonNpcKind.SALTMERE_ENVOY  // Wandering Peddler (Room Feature)
    )

    /** Dungeon enemy & boss sprite ID -> HalcyonEnemyKind preset. */
    private val ENEMY_MAP: List<Pair<Int, HalcyonEnemyKind>> = listOf(
        101 to HalcyonEnemyKind.RATLING,       // Crypt Gnasher
        102 to HalcyonEnemyKind.APPARITION,    // Crypt Wisp
        103 to HalcyonEnemyKind.BANDIT,        // Risen Marauder
        104 to HalcyonEnemyKind.BLADE_GHOST,   // Hollow Reaver
        105 to HalcyonEnemyKind.HILL_MONSTER,  // Grave Golem
        106 to HalcyonEnemyKind.SWORD_MASTER   // Malakor, the Ashen Warden (Floor 1 Boss)
    )

    enum class Part { TILESETS, BODIES, GEAR, NPCS, ENEMIES, ICONS }

    class Result(val written: List<Path>, val uiHeights: Map<Int, Int>)

    fun export(gameDir: Path, parts: Set<Part> = Part.entries.toSet(), seed: Long = 4040L): Result {
        val assets = gameDir.resolve("assets")
        val written = ArrayList<Path>()
        val heights = linkedMapOf<Int, Int>()

        fun writePng(sub: String, id: Int, img: TileImage) {
            val dir = assets.resolve(sub)
            Files.createDirectories(dir)
            val path = dir.resolve("$id.png")
            PngEncoder.writeToFile(img, path)
            written.add(path)
        }

        fun writeSprite(id: Int, sheet: TileImage) {
            writePng("sprites", id, sheet)
            visibleHeight(sheet)?.let { heights[id] = it }
        }

        if (Part.TILESETS in parts) {
            exportTilesets(seed).forEach { (id, img) -> writePng("tilesets", id, img) }
        }

        if (Part.BODIES in parts) {
            val doll = PaperDollSpriteGenerator(seed = seed, tileSize = CELL_W, tileHeight = CELL_H)
            for ((id, shoot, loadout) in classBodies()) {
                val img = sheet(shoot) { dir, action, f ->
                    val (layers, _) = doll.generateLayeredFrame(loadout, dir, action, f, PixelStyle.MORGANA)
                    doll.compositeLayers(layers.filterKeys { it !in GEAR_LAYERS }, dir, action, includeShadow = false)
                }
                writeSprite(id, img)
            }
        }

        if (Part.NPCS in parts) {
            val gen = HalcyonSpriteGenerator(seed = seed, cellWidth = CELL_W, cellHeight = CELL_H)
            for ((id, kind) in NPC_MAP) {
                writeSprite(id, sheet(shoot = false) { dir, action, f -> gen.generateNpcFrame(kind, dir, action, f) })
            }
        }

        if (Part.ENEMIES in parts) {
            val gen = HalcyonSpriteGenerator(seed = seed, cellWidth = CELL_W, cellHeight = CELL_H)
            for ((id, kind) in ENEMY_MAP) {
                writeSprite(id, sheet(shoot = false) { dir, action, f -> gen.generateEnemyFrame(kind, dir, action, f) })
            }
        }

        if (Part.GEAR in parts) {
            val doll = PaperDollSpriteGenerator(seed = seed, tileSize = CELL_W, tileHeight = CELL_H)
            for (gear in GEAR) {
                val loadout = gear.wear(neutralLoadout())
                val img = sheet(gear.shoot) { dir, action, f ->
                    val (layers, _) = doll.generateLayeredFrame(loadout, dir, action, f, PixelStyle.MORGANA)
                    layers[gear.slot] ?: TileImage(CELL_W, CELL_H)
                }
                writePng("paperdolls", gear.id, img)
            }
        }

        if (Part.ICONS in parts) {
            RoguelikeIconArt.allIcons().forEach { (id, img) -> writePng("icons", id, img) }
            RoguelikeIconArt.allProjectiles().forEach { (id, img) -> writePng("projectiles", id, img) }
            RoguelikeIconArt.allAnimations().forEach { (id, img) -> writePng("animations", id, img) }
        }

        return Result(written, heights)
    }

    private fun exportTilesets(seed: Long): Map<Int, TileImage> {
        val out = linkedMapOf<Int, TileImage>()

        // 1.png: 4x4 town paving (pale cobble, warm flagstone, road cobble, ember cobble)
        val pavingGen = PavingGenerator(seed = seed)
        val pavingTiles = PavingKind.entries.flatMap { pavingGen.generate(it, 4) }
        out[1] = ThemedTilesetGenerator(seed).bundleTiles(pavingTiles.map { it.image }, columns = 4)

        // 2.png: Forgemaster's Smithy (4x3, ember walls + slate roof)
        out[2] = BuildingGenerator(seed = seed + 1, theme = BuildingTheme.EMBER)
            .generate(BuildingKind.SMITHY, "smithy", BuildingRoof.SLATE).image

        // 3.png: Apothecary & Curio Shop (4x3, default plaster + red roof)
        out[3] = BuildingGenerator(seed = seed + 2, theme = BuildingTheme.DEFAULT)
            .generate(BuildingKind.SHOP, "shop", BuildingRoof.RED).image

        // 4.png: Ashen Keep Hall / Inn (5x4, ember walls + brown roof)
        out[4] = BuildingGenerator(seed = seed + 3, theme = BuildingTheme.EMBER)
            .generate(BuildingKind.INN, "keep_hall", BuildingRoof.BROWN).image

        // 5.png: Hub Plaza Props (7x4: fountain 4x4, well 2x2, lamp 1x2, bench 2x1, planter 1x1)
        out[5] = PlazaGenerator(seed = seed + 4).sheet().first

        // 6.png: Ground details (left 4x4) + Dungeon archway, pine tree, oak tree, grass & keep wall (right 4x4) -> 8x4
        val propsSheet = TileImage(8 * 32, 4 * 32)
        val details = GroundDetailGenerator(seed = seed + 5).sheet()
        propsSheet.drawImage(details, 0, 0, details.width, details.height, 0, 0)
        val propGen = PropGenerator(seed = seed + 6, tileSize = 32)
        val arch = propGen.generateProp("dungeon_arch", PropKind.STONE_ARCHWAY, style = PixelStyle.MORGANA)
        propsSheet.drawImage(arch.fullImage, 0, 0, arch.fullImage.width, arch.fullImage.height, 4 * 32, 0)
        val pine = propGen.generateProp("keep_pine", PropKind.PINE_TREE, style = PixelStyle.MORGANA)
        propsSheet.drawImage(pine.fullImage, 0, 0, pine.fullImage.width, pine.fullImage.height, 7 * 32, 0)
        val oak = propGen.generateProp("keep_oak", PropKind.OAK_TREE, style = PixelStyle.MORGANA)
        propsSheet.drawImage(oak.fullImage, 0, 0, oak.fullImage.width, oak.fullImage.height, 4 * 32, 2 * 32)
        val matGen = MaterialTileGenerator(seed = seed + 7, tileSize = 32)
        val grass = matGen.generateTile(MaterialSpec("grass", MaterialPattern.ORGANIC_FBM, PalettePresets.MEADOW_GRASS, style = PixelStyle.MORGANA))
        propsSheet.drawImage(grass, 0, 0, 32, 32, 7 * 32, 2 * 32)
        val keepWall = matGen.generateTile(MaterialSpec("keep_wall", MaterialPattern.BRICK_MASONRY, PalettePresets.SLATE_STONE, style = PixelStyle.MORGANA))
        propsSheet.drawImage(keepWall, 0, 0, 32, 32, 7 * 32, 3 * 32)
        out[6] = propsSheet

        // 7.png: Sunken Crypt Dungeon Tileset (8x2 = 256x64)
        out[7] = buildCryptTileset(seed + 10)
        return out
    }

    private fun buildCryptTileset(seed: Long): TileImage {
        val sheet = TileImage(8 * 32, 2 * 32)
        val mat = MaterialTileGenerator(seed = seed, tileSize = 32)
        val prop = PropGenerator(seed = seed + 1, tileSize = 32)

        val cryptSlate = PaletteRamp.fromBaseColor(PaletteRamp.rgba(72, 78, 92), steps = 6, hueShift = 0.05f, valueSpread = 0.30f)
        val cryptWallTop = PaletteRamp.fromBaseColor(PaletteRamp.rgba(34, 36, 46), steps = 6, hueShift = 0.04f, valueSpread = 0.24f)
        val cryptBrick = PaletteRamp.fromBaseColor(PaletteRamp.rgba(88, 92, 108), steps = 6, hueShift = 0.05f, valueSpread = 0.32f)

        fun put(col: Int, row: Int, img: TileImage) {
            sheet.drawImage(img, 0, 0, 32, 32, col * 32, row * 32)
        }

        // Row 0: floor, mossy floor, wall top, wall front face, stairs/exit, skulls, urn, pillar
        val baseFloor = mat.generateTile(MaterialSpec("crypt_floor", MaterialPattern.DIAGONAL_PAVER, cryptSlate, detail = 0.28f, style = PixelStyle.MORGANA))
        put(0, 0, baseFloor)
        val mossyFloor = mat.generateTile(MaterialSpec("crypt_floor_moss", MaterialPattern.DIAGONAL_PAVER, cryptSlate, detail = 0.32f, style = PixelStyle.MORGANA))
        TileDecals.applyDecal(mossyFloor, DecalKind.VINES, seed + 21)
        put(1, 0, mossyFloor)
        put(2, 0, mat.generateTile(MaterialSpec("crypt_wall_top", MaterialPattern.COBBLESTONE, cryptWallTop, detail = 0.20f, style = PixelStyle.MORGANA)))
        put(3, 0, mat.generateTile(MaterialSpec("crypt_wall_face", MaterialPattern.BRICK_MASONRY, cryptBrick, detail = 0.32f, style = PixelStyle.MORGANA)))
        put(4, 0, prop.generateProp("stairs_down", PropKind.STAIRS_DOWN, style = PixelStyle.MORGANA).fullImage)
        put(5, 0, prop.generateProp("skulls", PropKind.SKULL_PILE, style = PixelStyle.MORGANA).fullImage)
        put(6, 0, prop.generateProp("urn", PropKind.URN, style = PixelStyle.MORGANA).fullImage)
        put(7, 0, prop.generateProp("pillar", PropKind.PILLAR, style = PixelStyle.MORGANA).fullImage)

        // Row 1: bone floor, blood floor, glowing brazier/rune, wall chains, sarcophagus, shrine, dark void, boss floor
        val boneFloor = mat.generateTile(MaterialSpec("crypt_bones", MaterialPattern.DIAGONAL_PAVER, cryptSlate, style = PixelStyle.MORGANA))
        TileDecals.applyDecal(boneFloor, DecalKind.BONES, seed + 22)
        put(0, 1, boneFloor)
        val bloodFloor = mat.generateTile(MaterialSpec("crypt_blood", MaterialPattern.COBBLESTONE, cryptSlate, style = PixelStyle.MORGANA))
        TileDecals.applyDecal(bloodFloor, DecalKind.BLOOD_SPLATTER, seed + 23)
        put(1, 1, bloodFloor)
        put(2, 1, drawGlowingRuneTile(cryptSlate))
        put(3, 1, prop.generateProp("chains", PropKind.WALL_CHAINS, style = PixelStyle.MORGANA).fullImage)
        put(4, 1, prop.generateProp("sarcophagus", PropKind.SARCOPHAGUS, style = PixelStyle.MORGANA).fullImage)
        put(5, 1, prop.generateProp("shrine", PropKind.SHRINE, style = PixelStyle.MORGANA).fullImage)
        val voidTile = TileImage(32, 32).also { img ->
            val dark = PaletteRamp.rgba(16, 15, 22)
            for (y in 0 until 32) for (x in 0 until 32) img.setPixel(x, y, dark)
        }
        put(6, 1, voidTile)
        put(7, 1, mat.generateTile(MaterialSpec("boss_floor", MaterialPattern.DIAGONAL_PAVER, PalettePresets.CO_DYNASTY_LACQUER, detail = 0.25f, style = PixelStyle.MORGANA)))
        return sheet
    }

    private fun drawGlowingRuneTile(basePal: PaletteRamp): TileImage {
        val img = TileImage(32, 32)
        val ember = PaletteRamp.fromBaseColor(PaletteRamp.rgba(240, 146, 62), steps = 6, hueShift = 0.06f, valueSpread = 0.34f)
        for (y in 6..25) for (x in 6..25) {
            val dx = x - 15.5f
            val dy = y - 15.5f
            val d = kotlin.math.hypot(dx, dy)
            if (d in 6.5f..9.2f || d <= 2.4f) {
                img.setPixel(x, y, PixelDither.sampleDithered(ember, (0.85f - d * 0.04f).coerceIn(0.2f, 0.96f), x, y, 4, 0.1f))
            } else if (d < 6.5f) {
                img.setPixel(x, y, PixelDither.sampleDithered(basePal, 0.25f, x, y, 4, 0.1f))
            }
        }
        return img
    }

    private fun classBodies(): List<Triple<Int, Boolean, PaperDollLoadout>> {
        fun baseTheme(name: String, outfit: PaletteRamp, accent: PaletteRamp, hair: PaletteRamp, skin: PaletteRamp) =
            PaperDollTheme(
                name = name,
                ancestry = BodyAncestry.HUMAN,
                skinPalette = skin,
                hairPalette = hair,
                outfitPalette = outfit,
                accentPalette = accent,
                secondaryPalette = PalettePresets.WR_SERENDIA_IRON,
                style = PixelStyle.MORGANA,
                proportions = PaperDollProportions.RMXP_32X48
            )

        val naturalSkinA = PaletteRamp.fromBaseColor(PaletteRamp.rgba(226, 178, 144), steps = 6, hueShift = 0.05f, valueSpread = 0.28f)
        val naturalSkinB = PaletteRamp.fromBaseColor(PaletteRamp.rgba(210, 156, 118), steps = 6, hueShift = 0.05f, valueSpread = 0.28f)
        val naturalSkinC = PaletteRamp.fromBaseColor(PaletteRamp.rgba(236, 194, 162), steps = 6, hueShift = 0.05f, valueSpread = 0.28f)
        val darkHair = PaletteRamp.fromBaseColor(PaletteRamp.rgba(56, 46, 62), steps = 6, hueShift = 0.05f, valueSpread = 0.30f)
        val chestnutHair = PaletteRamp.fromBaseColor(PaletteRamp.rgba(134, 82, 46), steps = 6, hueShift = 0.05f, valueSpread = 0.30f)
        val emberHair = PaletteRamp.fromBaseColor(PaletteRamp.rgba(196, 86, 52), steps = 6, hueShift = 0.06f, valueSpread = 0.30f)

        val vanguard = PaperDollLoadout(
            job = PaperDollJob.LORD_KNIGHT,
            hairStyle = HairStyle.SPIKY_HERO,
            headgear = HeadgearKind.NONE,
            weapon = WeaponKind.NONE,
            shield = ShieldKind.NONE,
            torsoStyle = TorsoStyle.BRIGANDINE,
            legsStyle = LegsStyle.GREAVES,
            feetStyle = FeetStyle.BOOTS,
            includeCape = false,
            attachments = listOf(AttachmentKind.UTILITY_BELT),
            theme = baseTheme("vanguard", PalettePresets.WR_SERENDIA_IRON, PalettePresets.CO_CELESTIAL_GOLD, darkHair, naturalSkinA)
        )
        val ranger = PaperDollLoadout(
            job = PaperDollJob.SNIPER_HUNTER,
            hairStyle = HairStyle.SHORT_BOB,
            headgear = HeadgearKind.NONE,
            weapon = WeaponKind.NONE,
            shield = ShieldKind.NONE,
            torsoStyle = TorsoStyle.TRAVELER,
            legsStyle = LegsStyle.TROUSERS,
            feetStyle = FeetStyle.BOOTS,
            includeCape = false,
            attachments = listOf(AttachmentKind.UTILITY_BELT),
            theme = baseTheme("ranger", PalettePresets.CO_IMPERIAL_JADE, PalettePresets.CO_DRAGON_BRONZE, chestnutHair, naturalSkinB)
        )
        val pyromancer = PaperDollLoadout(
            job = PaperDollJob.HIGH_WIZARD,
            hairStyle = HairStyle.LONG_FLOWING,
            headgear = HeadgearKind.NONE,
            weapon = WeaponKind.NONE,
            shield = ShieldKind.NONE,
            torsoStyle = TorsoStyle.TRAVELER,
            legsStyle = LegsStyle.ROBE_SKIRT,
            feetStyle = FeetStyle.SANDALS,
            includeCape = false,
            attachments = listOf(AttachmentKind.SILK_SASH),
            theme = baseTheme("pyromancer", PalettePresets.CO_DYNASTY_LACQUER, PalettePresets.CO_CELESTIAL_GOLD, emberHair, naturalSkinC)
        )
        return listOf(
            Triple(1, false, vanguard),
            Triple(2, true, ranger),
            Triple(3, false, pyromancer)
        )
    }

    private fun neutralLoadout(): PaperDollLoadout = PaperDollLoadout(
        job = PaperDollJob.RUNE_HERO,
        hairStyle = HairStyle.SPIKY_HERO,
        headgear = HeadgearKind.NONE,
        weapon = WeaponKind.NONE,
        shield = ShieldKind.NONE,
        theme = PaperDollThemes.HALCYON_NEUTRAL.copy(style = PixelStyle.MORGANA, proportions = PaperDollProportions.RMXP_32X48),
        attachments = emptyList(),
        includeCape = false
    )

    private fun sheet(shoot: Boolean, frame: (Direction8, PaperDollAction, Int) -> TileImage): TileImage {
        val img = TileImage(CELL_W * COLUMNS, CELL_H * DIRECTIONS.size)
        var col = 0
        for (state in STATES) {
            val action = if (state.name == "attack" && shoot) PaperDollAction.SHOOT else state.action
            for (f in 0 until state.frames) {
                for ((row, dir) in DIRECTIONS.withIndex()) {
                    img.drawImage(frame(dir, action, f), 0, 0, CELL_W, CELL_H, col * CELL_W, row * CELL_H)
                }
                col++
            }
        }
        return img
    }

    private fun visibleHeight(sheet: TileImage): Int? {
        var top = CELL_H
        for (y in 0 until sheet.height) {
            val inRow = y % CELL_H
            if (inRow >= top) continue
            for (x in 0 until CELL_W * 6) {
                if ((sheet.getPixel(x, y) and 0xFF) >= 160) {
                    top = inRow
                    break
                }
            }
        }
        return if (top >= CELL_H) null else CELL_H - top
    }
}
