package com.morgana.gameart.sprite

import com.morgana.tilegen.*
import com.morgana.tilegen.material.*
import com.morgana.tilegen.spec.*
import com.morgana.tilegen.sprite.*
import com.morgana.tilegen.structure.*
import com.morgana.tilegen.export.*
import com.morgana.tilegen.autotile.*
import com.morgana.gameart.export.*
import com.morgana.gameart.whiteriver.*

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelDither
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.material.SpriteFinish
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Trevana Realms town NPC definitions mapped to their Morgana Engine sprite IDs (`10..21`).
 */
enum class TrevanaNpcKind(val spriteId: Int, val displayName: String) {
    CAPTAIN(10, "General / Captain"),
    PHARMACIST(11, "Pharmacist"),
    BLACKSMITH(12, "Blacksmith / Armorer"),
    GEMWRIGHT(13, "Gemwright"),
    WAREHOUSEMAN(14, "Warehouseman"),
    WAYMASTER(15, "Waymaster"),
    GUILD_DIRECTOR(16, "Guild Director"),
    ASHGATE_WARDEN(17, "Ashgate Warden"),
    SALTMERE_ENVOY(18, "Saltmere Envoy"),
    CLASS_MASTER(19, "Ascension Master"),
    SKERRY_SCOUT(20, "Skerry Isle Scout"),
    TOWN_GUARD(21, "Town Guard")
}

/**
 * Trevana Realms monster & boss definitions mapped to their Morgana Engine sprite IDs (`101..122`).
 */
enum class TrevanaEnemyKind(val spriteId: Int, val key: String, val displayName: String, val isBoss: Boolean = false) {
    PHEASANT(101, "pheasant", "Stubblehen"),
    TURTLEDOVE(102, "turtledove", "Gleaning Dove"),
    ROBIN(103, "robin", "Thistlefinch"),
    APPARITION(104, "apparition", "Ghostlight"),
    POLTERGEIST(105, "poltergeist", "Lantern Imp"),
    WINGED_SNAKE(106, "winged_snake", "Gale Serpent"),
    BANDIT(107, "bandit", "Bandit"),
    RATLING(108, "ratling", "Tunnel Gnasher"),
    FIRE_SPIRIT(109, "fire_spirit", "Ember Wisp"),
    MACAQUE(110, "macaque", "Scree Scrambler"),
    GIANT_APE(111, "giant_ape", "Stonehide"),
    THUNDER_APE(112, "thunder_ape", "Thunderhide"),
    ELF_APE(113, "elf_ape", "Mistfur Stalker"),
    SAND_MONSTER(114, "sand_monster", "Glass Burrower"),
    HILL_MONSTER(115, "hill_monster", "Slag Hulk"),
    SCORPION(116, "scorpion", "Shardtail"),
    BLADE_GHOST(117, "blade_ghost", "Mirage Reaper"),
    BIRDMAN(118, "birdman", "Skerry Harpy"),
    HAWKING(119, "hawking", "Gale Raptor"),
    TERATO_DRAGON(120, "terato_dragon", "Terato Dragon", isBoss = true),
    SNOW_BANSHEE(121, "snow_banshee", "Mournwail", isBoss = true),
    SWORD_MASTER(122, "sword_master", "Hollow Knight", isBoss = true)
}

/**
 * Generates composited 32x48 RMXP-style NPC and enemy sprite sheets for Trevana Realms
 * matching the `4 cols x 36 rows` (`TREVANA` 9-action x 4-direction) layout and selective-outline style.
 */
class TrevanaSpriteGenerator(
    val seed: Long = 1337L,
    val cellWidth: Int = 32,
    val cellHeight: Int = 48
) {
    private val dollGen = PaperDollSpriteGenerator(seed = seed, tileSize = cellWidth, tileHeight = cellHeight)

    /** [npcBaseLoadout] with its own hair and skin, so a town does not look like one person in twelve coats. */
    fun npcLoadout(kind: TrevanaNpcKind): PaperDollLoadout {
        val base = npcBaseLoadout(kind)
        val (hair, skin) = when (kind) {
            TrevanaNpcKind.CAPTAIN -> natural(122, 82, 48) to natural(226, 176, 140)
            TrevanaNpcKind.PHARMACIST -> natural(196, 166, 96) to natural(240, 200, 168)
            TrevanaNpcKind.BLACKSMITH -> natural(74, 48, 34) to natural(206, 150, 112)
            TrevanaNpcKind.GEMWRIGHT -> natural(168, 170, 188) to natural(232, 190, 158)
            TrevanaNpcKind.WAREHOUSEMAN -> natural(110, 70, 44) to natural(214, 160, 120)
            TrevanaNpcKind.WAYMASTER -> natural(206, 206, 210) to natural(222, 172, 138)
            TrevanaNpcKind.GUILD_DIRECTOR -> natural(40, 34, 44) to natural(196, 140, 106)
            TrevanaNpcKind.ASHGATE_WARDEN -> natural(150, 62, 44) to natural(228, 180, 146)
            TrevanaNpcKind.SALTMERE_ENVOY -> natural(60, 78, 112) to natural(176, 124, 94)
            TrevanaNpcKind.CLASS_MASTER -> natural(228, 222, 206) to natural(238, 196, 162)
            TrevanaNpcKind.SKERRY_SCOUT -> natural(172, 120, 64) to natural(204, 150, 112)
            TrevanaNpcKind.TOWN_GUARD -> natural(96, 82, 70) to natural(222, 172, 136)
        }
        return base.copy(hairPalette = hair, theme = base.theme.copy(hairPalette = hair, skinPalette = skin))
    }

    private fun npcBaseLoadout(kind: TrevanaNpcKind): PaperDollLoadout {
        val baseTheme = PaperDollTheme(
            name = kind.name.lowercase(),
            ancestry = BodyAncestry.HUMAN,
            style = PixelStyle.MORGANA,
            proportions = PaperDollProportions.RMXP_32X48
        )
        return when (kind) {
            TrevanaNpcKind.CAPTAIN -> PaperDollLoadout(
                job = PaperDollJob.KNIGHT,
                hairStyle = HairStyle.NOBLE_PARTED,
                headgear = HeadgearKind.STEEL_HELMET,
                weapon = WeaponKind.KNIGHT_SWORD,
                shield = ShieldKind.CRUSADER_CROSS_SHIELD,
                torsoStyle = TorsoStyle.SKYRENDER,
                legsStyle = LegsStyle.GREAVES,
                feetStyle = FeetStyle.SABATONS,
                includeCape = true,
                attachments = listOf(AttachmentKind.CAPE, AttachmentKind.KNIGHT_PAULDRONS),
                theme = baseTheme.copy(
                    outfitPalette = PalettePresets.LACQUER_RED,
                    accentPalette = beakRamp,
                    secondaryPalette = PalettePresets.DARK_IRON
                )
            )
            TrevanaNpcKind.PHARMACIST -> PaperDollLoadout(
                job = PaperDollJob.PRIEST,
                hairStyle = HairStyle.LONG_FLOWING,
                headgear = HeadgearKind.NONE,
                weapon = WeaponKind.NONE,
                shield = ShieldKind.SPELLBOOK,
                torsoStyle = TorsoStyle.TRAVELER,
                legsStyle = LegsStyle.ROBE_SKIRT,
                feetStyle = FeetStyle.SANDALS,
                includeCape = false,
                attachments = listOf(AttachmentKind.POTION_POUCH, AttachmentKind.SILK_SASH),
                theme = baseTheme.copy(
                    outfitPalette = PalettePresets.JADE_GREEN,
                    accentPalette = PalettePresets.CREAM_MARBLE,
                    secondaryPalette = PalettePresets.WARM_TIMBER
                )
            )
            TrevanaNpcKind.BLACKSMITH -> PaperDollLoadout(
                job = PaperDollJob.SMITH,
                hairStyle = HairStyle.SHORT_BOB,
                headgear = HeadgearKind.GOGGLES,
                weapon = WeaponKind.HEAVY_AXE,
                shield = ShieldKind.NONE,
                torsoStyle = TorsoStyle.BRIGANDINE,
                legsStyle = LegsStyle.TROUSERS,
                feetStyle = FeetStyle.BOOTS,
                includeCape = false,
                attachments = listOf(AttachmentKind.UTILITY_BELT, AttachmentKind.SPIKED_PAULDRONS),
                theme = baseTheme.copy(
                    outfitPalette = PalettePresets.WARM_TIMBER,
                    accentPalette = PalettePresets.DARK_IRON,
                    secondaryPalette = PalettePresets.DRAGON_BRONZE
                )
            )
            TrevanaNpcKind.GEMWRIGHT -> PaperDollLoadout(
                job = PaperDollJob.WIZARD,
                hairStyle = HairStyle.NOBLE_PARTED,
                headgear = HeadgearKind.NONE,
                weapon = WeaponKind.HOLY_ROD,
                shield = ShieldKind.NONE,
                torsoStyle = TorsoStyle.TRAVELER,
                legsStyle = LegsStyle.ROBE_SKIRT,
                feetStyle = FeetStyle.BOOTS,
                includeCape = false,
                attachments = listOf(AttachmentKind.GLASSES, AttachmentKind.AMULET, AttachmentKind.SILK_SASH),
                theme = baseTheme.copy(
                    outfitPalette = PalettePresets.ARCANE_VIOLET,
                    accentPalette = beakRamp,
                    secondaryPalette = boneRamp
                )
            )
            TrevanaNpcKind.WAREHOUSEMAN -> PaperDollLoadout(
                job = PaperDollJob.SMITH,
                hairStyle = HairStyle.SHORT_BOB,
                headgear = HeadgearKind.BANDANA,
                weapon = WeaponKind.NONE,
                shield = ShieldKind.NONE,
                torsoStyle = TorsoStyle.TRAVELER,
                legsStyle = LegsStyle.TROUSERS,
                feetStyle = FeetStyle.BOOTS,
                includeCape = false,
                attachments = listOf(AttachmentKind.BACKPACK, AttachmentKind.SATCHEL, AttachmentKind.UTILITY_BELT),
                theme = baseTheme.copy(
                    outfitPalette = PalettePresets.DUNE_SANDSTONE,
                    accentPalette = PalettePresets.DRAGON_BRONZE,
                    secondaryPalette = PalettePresets.WARM_TIMBER
                )
            )
            TrevanaNpcKind.WAYMASTER -> PaperDollLoadout(
                job = PaperDollJob.PRIEST,
                hairStyle = HairStyle.BRAIDED_CROWN,
                headgear = HeadgearKind.PRIEST_MITRE,
                weapon = WeaponKind.ARCANE_STAFF,
                shield = ShieldKind.NONE,
                torsoStyle = TorsoStyle.TRAVELER,
                legsStyle = LegsStyle.ROBE_SKIRT,
                feetStyle = FeetStyle.SANDALS,
                includeCape = true,
                attachments = listOf(AttachmentKind.SILK_GARMENT, AttachmentKind.HALO),
                theme = baseTheme.copy(
                    outfitPalette = boneRamp,
                    accentPalette = PalettePresets.CREAM_MARBLE,
                    secondaryPalette = PalettePresets.JADE_GREEN
                )
            )
            TrevanaNpcKind.GUILD_DIRECTOR -> PaperDollLoadout(
                job = PaperDollJob.PRINCE,
                hairStyle = HairStyle.NOBLE_PARTED,
                headgear = HeadgearKind.NONE,
                weapon = WeaponKind.KATANA,
                shield = ShieldKind.NONE,
                torsoStyle = TorsoStyle.BRIGANDINE,
                legsStyle = LegsStyle.TROUSERS,
                feetStyle = FeetStyle.BOOTS,
                includeCape = true,
                attachments = listOf(AttachmentKind.ROYAL_CROWN, AttachmentKind.CAPE, AttachmentKind.SILK_SASH),
                theme = baseTheme.copy(
                    outfitPalette = PalettePresets.ABYSSAL_OBSIDIAN,
                    accentPalette = beakRamp,
                    secondaryPalette = PalettePresets.LACQUER_RED
                )
            )
            TrevanaNpcKind.ASHGATE_WARDEN -> PaperDollLoadout(
                job = PaperDollJob.PALADIN,
                hairStyle = HairStyle.SPIKY_HERO,
                headgear = HeadgearKind.BANDANA,
                weapon = WeaponKind.HOLY_SPEAR,
                shield = ShieldKind.ROUND_BUCKLER,
                torsoStyle = TorsoStyle.BRIGANDINE,
                legsStyle = LegsStyle.GREAVES,
                feetStyle = FeetStyle.BOOTS,
                includeCape = true,
                attachments = listOf(AttachmentKind.CAPE, AttachmentKind.KNIGHT_PAULDRONS),
                theme = baseTheme.copy(
                    outfitPalette = PalettePresets.HIGHLAND_GRASS,
                    accentPalette = PalettePresets.DARK_IRON,
                    secondaryPalette = PalettePresets.WARM_TIMBER
                )
            )
            TrevanaNpcKind.SALTMERE_ENVOY -> PaperDollLoadout(
                job = PaperDollJob.SHRINE_MAIDEN,
                hairStyle = HairStyle.TWIN_TAILS,
                headgear = HeadgearKind.SHRINE_RIBBON,
                weapon = WeaponKind.NONE,
                shield = ShieldKind.NONE,
                torsoStyle = TorsoStyle.TRAVELER,
                legsStyle = LegsStyle.ROBE_SKIRT,
                feetStyle = FeetStyle.SANDALS,
                includeCape = false,
                attachments = listOf(AttachmentKind.ROSE_FAN, AttachmentKind.SILK_SASH),
                theme = baseTheme.copy(
                    outfitPalette = beakRamp,
                    accentPalette = beakRamp,
                    secondaryPalette = PalettePresets.CREAM_MARBLE
                )
            )
            TrevanaNpcKind.CLASS_MASTER -> PaperDollLoadout(
                job = PaperDollJob.HERO,
                hairStyle = HairStyle.SPIKY_HERO,
                headgear = HeadgearKind.SKYRENDER_HELM,
                weapon = WeaponKind.KNIGHT_SWORD,
                shield = ShieldKind.NONE,
                torsoStyle = TorsoStyle.SKYRENDER,
                legsStyle = LegsStyle.GREAVES,
                feetStyle = FeetStyle.SABATONS,
                includeCape = true,
                attachments = listOf(AttachmentKind.CAPE, AttachmentKind.RUNE_CIRCLE),
                theme = baseTheme.copy(
                    outfitPalette = beakRamp,
                    accentPalette = PalettePresets.LACQUER_RED,
                    secondaryPalette = PalettePresets.ABYSSAL_OBSIDIAN
                )
            )
            TrevanaNpcKind.SKERRY_SCOUT -> PaperDollLoadout(
                job = PaperDollJob.HUNTER,
                hairStyle = HairStyle.SHORT_BOB,
                headgear = HeadgearKind.FEATHER_CAP,
                weapon = WeaponKind.COMPOSITE_BOW,
                shield = ShieldKind.NONE,
                torsoStyle = TorsoStyle.TRAVELER,
                legsStyle = LegsStyle.TROUSERS,
                feetStyle = FeetStyle.BOOTS,
                includeCape = true,
                attachments = listOf(AttachmentKind.CAPE, AttachmentKind.QUIVER),
                theme = baseTheme.copy(
                    outfitPalette = PalettePresets.FOREST_JADE,
                    accentPalette = PalettePresets.WARM_TIMBER,
                    secondaryPalette = PalettePresets.DRAGON_BRONZE
                )
            )
            TrevanaNpcKind.TOWN_GUARD -> PaperDollLoadout(
                job = PaperDollJob.KNIGHT,
                hairStyle = HairStyle.SHORT_BOB,
                headgear = HeadgearKind.IRON_HELMET,
                weapon = WeaponKind.HOLY_SPEAR,
                shield = ShieldKind.TOWER_SHIELD,
                torsoStyle = TorsoStyle.BRIGANDINE,
                legsStyle = LegsStyle.GREAVES,
                feetStyle = FeetStyle.SABATONS,
                includeCape = false,
                attachments = listOf(AttachmentKind.KNIGHT_PAULDRONS),
                theme = baseTheme.copy(
                    outfitPalette = PalettePresets.DARK_IRON,
                    accentPalette = PalettePresets.CREAM_MARBLE,
                    secondaryPalette = PalettePresets.WARM_TIMBER
                )
            )
        }
    }

    /**
     * Generates a composited `4 cols x 36 rows` (`128x1728`) RMXP sprite sheet for [kind].
     */
    fun generateNpcSheet(
        kind: TrevanaNpcKind,
        actions: List<PaperDollAction> = PaperDollAction.EXTENDED,
        directions: List<Direction8> = listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N)
    ): Pair<TileImage, List<NamedTile>> {
        val loadout = npcLoadout(kind)
        val uni = dollGen.generateUniversalSheet(
            name = "npc_${kind.spriteId}",
            loadout = loadout,
            actions = actions,
            directions = directions,
            style = PixelStyle.MORGANA,
            includeShadow = true
        )
        val tiles = buildGridTiles("${kind.spriteId}", actions, directions, uni.compositeSheet)
        return uni.compositeSheet to tiles
    }

    /**
     * Generates a composited `4 cols x 36 rows` (`128x1728`) RMXP sprite sheet for [kind].
     */
    fun generateEnemySheet(
        kind: TrevanaEnemyKind,
        actions: List<PaperDollAction> = PaperDollAction.EXTENDED,
        directions: List<Direction8> = listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N)
    ): Pair<TileImage, List<NamedTile>> {
        val cols = actions.maxOf { it.defaultFrames }
        val rows = actions.size * directions.size
        val sheet = TileImage(cols * cellWidth, rows * cellHeight)
        val tiles = mutableListOf<NamedTile>()
        var r = 0
        for (action in actions) {
            for (dir in directions) {
                for (f in 0 until action.defaultFrames) {
                    val frame = generateEnemyFrame(kind, dir, action, f)
                    sheet.drawImage(frame, 0, 0, cellWidth, cellHeight, f * cellWidth, r * cellHeight)
                    tiles += NamedTile("${kind.spriteId}_${action.name.lowercase()}_${dir.name.lowercase()}_f$f", frame)
                }
                for (c in action.defaultFrames until cols) {
                    tiles += NamedTile("${kind.spriteId}_${action.name.lowercase()}_${dir.name.lowercase()}_empty_c$c", TileImage(cellWidth, cellHeight))
                }
                r++
            }
        }
        return sheet to tiles
    }

    /** One composited NPC frame (with its ground shadow) in the house style, for sheets laid out by the caller. */
    fun generateNpcFrame(
        kind: TrevanaNpcKind,
        direction: Direction8 = Direction8.S,
        action: PaperDollAction = PaperDollAction.IDLE,
        frameIndex: Int = 0
    ): TileImage = dollGen.generateCharacterFrame(
        npcLoadout(kind), direction, action, frameIndex, style = PixelStyle.MORGANA, includeShadow = true
    )

    fun generateEnemyFrame(
        kind: TrevanaEnemyKind,
        direction: Direction8 = Direction8.S,
        action: PaperDollAction = PaperDollAction.IDLE,
        frameIndex: Int = 0
    ): TileImage {
        val baseDir = direction.mirrorFrom ?: direction
        val mirrored = direction.mirrorFrom != null
        val pal = enemyPalette(kind)
        val raw = renderEnemy32x48(kind, baseDir, action, frameIndex, pal)
        val outlined = SpriteFinish.finish(raw)
        val oriented = if (mirrored) mirrorX(outlined) else outlined

        val final32x48 = TileImage(32, 48)
        val phase = frameIndex % action.defaultFrames.coerceAtLeast(1)
        val shadowRx = if (action == PaperDollAction.JUMP && phase == 1) 7.0f else if (kind.isBoss) 11.0f else 8.5f
        SpriteFinish.softShadow(final32x48, 16f, 45.5f, shadowRx + 1f, 2.8f)
        composite(final32x48, oriented)

        val scaled = if (cellWidth == 32 && cellHeight == 48) final32x48 else scaleNearest(final32x48, cellWidth, cellHeight)
        return PixelDither.applyStyle(scaled, PixelStyle.MORGANA, pal)
    }

    private fun buildGridTiles(
        prefix: String,
        actions: List<PaperDollAction>,
        directions: List<Direction8>,
        sheet: TileImage
    ): List<NamedTile> {
        val cols = actions.maxOf { it.defaultFrames }
        val tiles = mutableListOf<NamedTile>()
        var r = 0
        for (action in actions) {
            for (dir in directions) {
                for (f in 0 until action.defaultFrames) {
                    val cell = sheet.crop(f * cellWidth, r * cellHeight, cellWidth, cellHeight)
                    tiles += NamedTile("${prefix}_${action.name.lowercase()}_${dir.name.lowercase()}_f$f", cell)
                }
                for (c in action.defaultFrames until cols) {
                    tiles += NamedTile("${prefix}_${action.name.lowercase()}_${dir.name.lowercase()}_empty_c$c", TileImage(cellWidth, cellHeight))
                }
                r++
            }
        }
        return tiles
    }

    /** A hue-shifted five-step ramp around a muted natural colour, the same construction as the meadow and tree ramps. */
    private fun natural(r: Int, g: Int, b: Int): PaletteRamp =
        PaletteRamp.fromBaseColor(PaletteRamp.rgba(r, g, b), steps = 6, hueShift = 0.05f, valueSpread = 0.30f)

    private val beakRamp = natural(214, 164, 62)
    private val boneRamp = natural(214, 206, 188)

    private fun enemyPalette(kind: TrevanaEnemyKind): PaletteRamp = when (kind) {
        TrevanaEnemyKind.PHEASANT -> natural(158, 106, 62)
        TrevanaEnemyKind.TURTLEDOVE -> natural(196, 196, 204)
        TrevanaEnemyKind.ROBIN -> natural(186, 96, 72)
        TrevanaEnemyKind.APPARITION -> natural(136, 126, 196)
        TrevanaEnemyKind.POLTERGEIST -> natural(210, 160, 66)
        TrevanaEnemyKind.WINGED_SNAKE -> natural(92, 156, 108)
        TrevanaEnemyKind.BANDIT -> natural(78, 70, 104)
        TrevanaEnemyKind.RATLING -> natural(124, 116, 120)
        TrevanaEnemyKind.FIRE_SPIRIT -> natural(204, 92, 56)
        TrevanaEnemyKind.MACAQUE -> natural(136, 94, 60)
        TrevanaEnemyKind.GIANT_APE -> natural(150, 142, 132)
        TrevanaEnemyKind.THUNDER_APE -> natural(196, 164, 74)
        TrevanaEnemyKind.ELF_APE -> natural(108, 156, 112)
        TrevanaEnemyKind.SAND_MONSTER -> natural(194, 166, 118)
        TrevanaEnemyKind.HILL_MONSTER -> natural(152, 106, 70)
        TrevanaEnemyKind.SCORPION -> natural(150, 54, 62)
        TrevanaEnemyKind.BLADE_GHOST -> natural(122, 142, 202)
        TrevanaEnemyKind.BIRDMAN -> natural(132, 160, 92)
        TrevanaEnemyKind.HAWKING -> natural(76, 150, 140)
        TrevanaEnemyKind.TERATO_DRAGON -> natural(188, 66, 52)
        TrevanaEnemyKind.SNOW_BANSHEE -> natural(204, 222, 238)
        TrevanaEnemyKind.SWORD_MASTER -> natural(62, 62, 88)
    }

    private fun renderEnemy32x48(
        kind: TrevanaEnemyKind,
        dir: Direction8,
        action: PaperDollAction,
        frameIndex: Int,
        pal: PaletteRamp
    ): TileImage {
        val img = TileImage(32, 48)
        val phase = frameIndex % action.defaultFrames.coerceAtLeast(1)
        val bobY = when (action) {
            PaperDollAction.IDLE -> if (phase % 2 == 1) 1 else 0
            PaperDollAction.WALK -> if (phase == 1 || phase == 3) -2 else 0
            PaperDollAction.SLASH, PaperDollAction.THRUST, PaperDollAction.SHOOT, PaperDollAction.HARVEST -> 0 // posed by poseTransform
            PaperDollAction.CAST -> if (phase == 1 || phase == 2) -3 else 0
            PaperDollAction.JUMP -> if (phase == 1) -5 else 1
            PaperDollAction.HURT -> 0 // posed by poseTransform
            else -> 0
        }
        val cx = 16
        val eyeCol = if (action == PaperDollAction.HURT) PaletteRamp.rgba(255, 255, 255) else PaletteRamp.rgba(255, 224, 96)

        val view = when {
            dir.isBack -> MonsterArt.View.BACK
            dir == Direction8.W -> MonsterArt.View.SIDE
            else -> MonsterArt.View.FRONT
        }
        val stride = if (action == PaperDollAction.WALK) (when (phase) { 0 -> 2; 2 -> -2; else -> 0 }) else 0
        val flapLift = if (action == PaperDollAction.WALK || action == PaperDollAction.JUMP || action == PaperDollAction.IDLE) phase % 2 * 2 else (if (phase == 1) 3 else 0)
        val pose = MonsterArt.Pose(bobY, phase, stride, flapLift, if (action == PaperDollAction.HURT) 1 else 0)
        val handled = MonsterArt(img).draw(kind, pal, view, cx, pose, eyeCol)

        when (if (handled) null else kind) {
            null -> Unit
            TrevanaEnemyKind.PHEASANT, TrevanaEnemyKind.TURTLEDOVE, TrevanaEnemyKind.ROBIN -> {
                // Plump avian body + beak + crest + flapping wings
                val cy = 36f + bobY
                fillEllipse(img, cx.toFloat(), cy, 6.5f, 5.5f, pal)
                fillEllipse(img, (cx - if (dir == Direction8.W) 3 else 0).toFloat(), cy - 6f, 4.2f, 3.8f, pal)
                val flap = if (action == PaperDollAction.WALK || action == PaperDollAction.JUMP) (phase % 2) * 2 else 0
                fillRect(img, cx - 9, cy.toInt() - 3 - flap, 3, 4, pal, 0.78f)
                fillRect(img, cx + 6, cy.toInt() - 3 - flap, 3, 4, pal, 0.58f)
                // Legs anchored at y=42..45
                fillRect(img, cx - 3, 42, 2, 4, beakRamp, 0.7f)
                fillRect(img, cx + 1, 42, 2, 4, beakRamp, 0.55f)
                if (!dir.isBack) {
                    val bx = if (dir == Direction8.W) cx - 7 else cx
                    img.setPixel(bx, cy.toInt() - 6, beakRamp.highlight)
                    img.setPixel(cx - 2, cy.toInt() - 7, eyeCol)
                }
            }
            TrevanaEnemyKind.APPARITION, TrevanaEnemyKind.POLTERGEIST, TrevanaEnemyKind.FIRE_SPIRIT -> {
                // Floating spectral/elemental body with wisps & glowing eyes
                val cy = 28f + bobY
                fillEllipse(img, cx.toFloat(), cy, 7.5f, 9.0f, pal)
                for (dx in -5..5 step 3) {
                    fillRect(img, cx + dx, cy.toInt() + 8, 2, 4 + ((phase + abs(dx)) % 3), pal, 0.45f)
                }
                if (kind == TrevanaEnemyKind.POLTERGEIST) {
                    fillRect(img, cx - 3, cy.toInt() - 11, 6, 3, natural(120, 118, 130), 0.8f)
                }
                if (!dir.isBack) {
                    img.setPixel(cx - 3, cy.toInt() - 1, eyeCol)
                    if (dir != Direction8.W) img.setPixel(cx + 3, cy.toInt() - 1, eyeCol)
                }
            }
            TrevanaEnemyKind.WINGED_SNAKE -> {
                val cy = 32f + bobY
                for (i in 0..8) {
                    val sx = cx + (if (i % 2 == 0) -1 else 1) * 2
                    fillEllipse(img, sx.toFloat(), cy - 6f + i * 1.6f, 4.0f - i * 0.2f, 2.5f, pal)
                }
                val flap = if (phase % 2 == 1) -2 else 0
                fillRect(img, cx - 10, cy.toInt() - 4 + flap, 5, 4, boneRamp, 0.82f)
                fillRect(img, cx + 5, cy.toInt() - 4 + flap, 5, 4, boneRamp, 0.65f)
                if (!dir.isBack) img.setPixel(cx - 2, cy.toInt() - 6, eyeCol)
            }
            TrevanaEnemyKind.BANDIT -> {
                // Humanoid brigand with bandana, blade, and boots
                val body = dollGen.generateCharacterFrame(
                    PaperDollLoadout(
                        job = PaperDollJob.ASSASSIN,
                        headgear = HeadgearKind.BANDANA,
                        weapon = WeaponKind.SINGLE_BLADE,
                        theme = PaperDollTheme(
                            ancestry = BodyAncestry.HUMAN,
                            outfitPalette = pal,
                            accentPalette = PalettePresets.LACQUER_RED,
                            secondaryPalette = PalettePresets.WARM_TIMBER,
                            proportions = PaperDollProportions.RMXP_32X48,
                            style = PixelStyle.MORGANA
                        )
                    ),
                    dir, action, frameIndex, includeShadow = false
                )
                return body
            }
            TrevanaEnemyKind.RATLING -> {
                val cy = 34f + bobY
                fillEllipse(img, cx.toFloat(), cy, 6.5f, 7.0f, pal)
                fillEllipse(img, cx.toFloat(), cy - 8f, 4.5f, 4.0f, pal)
                // Big ears & tail
                fillRect(img, cx - 6, cy.toInt() - 13, 3, 3, beakRamp, 0.7f)
                fillRect(img, cx + 3, cy.toInt() - 13, 3, 3, beakRamp, 0.55f)
                fillRect(img, cx + 5, 38, 4, 2, beakRamp, 0.5f)
                fillRect(img, cx - 4, 41, 3, 5, pal, 0.5f)
                fillRect(img, cx + 1, 41, 3, 5, pal, 0.4f)
                if (!dir.isBack) img.setPixel(cx - 2, cy.toInt() - 8, PaletteRamp.rgba(255, 80, 80))
            }
            TrevanaEnemyKind.MACAQUE, TrevanaEnemyKind.GIANT_APE, TrevanaEnemyKind.THUNDER_APE, TrevanaEnemyKind.ELF_APE -> {
                val isLarge = kind != TrevanaEnemyKind.MACAQUE
                val rw = if (isLarge) 9.5f else 7.0f
                val rh = if (isLarge) 10.5f else 8.0f
                val cy = (if (isLarge) 30f else 33f) + bobY
                fillEllipse(img, cx.toFloat(), cy, rw, rh, pal)
                fillEllipse(img, cx.toFloat(), cy - rh + 1f, rw * 0.65f, 5.2f, pal)
                val armSwing = if (action == PaperDollAction.WALK) (if (phase % 2 == 0) 2 else -2) else 0
                fillRect(img, cx - rw.toInt() - 2, cy.toInt() - 5 + armSwing, 3, 10, pal, 0.75f)
                fillRect(img, cx + rw.toInt(), cy.toInt() - 5 - armSwing, 3, 10, pal, 0.55f)
                fillRect(img, cx - 5, 40, 4, 6, pal, 0.55f)
                fillRect(img, cx + 2, 40, 4, 6, pal, 0.42f)
                if (!dir.isBack) {
                    img.setPixel(cx - 3, (cy - rh).toInt(), eyeCol)
                    if (dir != Direction8.W) img.setPixel(cx + 3, (cy - rh).toInt(), eyeCol)
                }
            }
            TrevanaEnemyKind.SAND_MONSTER, TrevanaEnemyKind.HILL_MONSTER -> {
                val cy = 32f + bobY
                fillEllipse(img, cx.toFloat(), cy, 10.0f, 11.0f, pal)
                // Rocky crag shoulders
                fillRect(img, cx - 10, cy.toInt() - 9, 5, 6, pal, 0.85f)
                fillRect(img, cx + 5, cy.toInt() - 9, 5, 6, pal, 0.62f)
                fillRect(img, cx - 6, 40, 4, 6, pal, 0.45f)
                fillRect(img, cx + 2, 40, 4, 6, pal, 0.35f)
                if (!dir.isBack) {
                    img.setPixel(cx - 3, cy.toInt() - 5, eyeCol)
                    if (dir != Direction8.W) img.setPixel(cx + 3, cy.toInt() - 5, eyeCol)
                }
            }
            TrevanaEnemyKind.SCORPION -> {
                val cy = 36f + bobY
                fillEllipse(img, cx.toFloat(), cy, 8.5f, 5.5f, pal)
                // Claws + curved stinger tail
                fillRect(img, cx - 11, cy.toInt() - 3, 4, 4, pal, 0.82f)
                fillRect(img, cx + 7, cy.toInt() - 3, 4, 4, pal, 0.68f)
                for (i in 0..6) {
                    img.setPixel((cx + (if (i > 3) -1 else 1)).coerceIn(2, 29), (cy.toInt() - 5 - i * 2).coerceAtLeast(10), pal.highlight)
                }
                fillRect(img, cx - 7, 42, 3, 4, pal, 0.5f)
                fillRect(img, cx + 4, 42, 3, 4, pal, 0.4f)
            }
            TrevanaEnemyKind.BLADE_GHOST -> {
                val cy = 29f + bobY
                fillEllipse(img, cx.toFloat(), cy, 6.5f, 10.0f, pal)
                // Twin spectral blades
                fillRect(img, cx - 10, cy.toInt() - 6, 3, 12, boneRamp, 0.92f)
                fillRect(img, cx + 7, cy.toInt() - 6, 3, 12, boneRamp, 0.78f)
                if (!dir.isBack) {
                    img.setPixel(cx - 2, cy.toInt() - 4, PaletteRamp.rgba(140, 240, 255))
                    if (dir != Direction8.W) img.setPixel(cx + 2, cy.toInt() - 4, PaletteRamp.rgba(140, 240, 255))
                }
            }
            TrevanaEnemyKind.BIRDMAN, TrevanaEnemyKind.HAWKING -> {
                val cy = 30f + bobY
                fillEllipse(img, cx.toFloat(), cy, 6.5f, 9.0f, pal)
                fillEllipse(img, cx.toFloat(), cy - 9f, 4.5f, 4.5f, pal)
                val flap = if (phase % 2 == 1) -3 else 0
                fillRect(img, cx - 12, cy.toInt() - 6 + flap, 6, 8, pal, 0.85f)
                fillRect(img, cx + 6, cy.toInt() - 6 + flap, 6, 8, pal, 0.65f)
                fillRect(img, cx - 4, 39, 3, 7, beakRamp, 0.7f)
                fillRect(img, cx + 1, 39, 3, 7, beakRamp, 0.55f)
                if (!dir.isBack) img.setPixel(cx - 2, cy.toInt() - 10, eyeCol)
            }
            TrevanaEnemyKind.TERATO_DRAGON -> {
                // Towering boss dragon with horns, wide wings, and armored chest
                val cy = 28f + bobY
                fillEllipse(img, cx.toFloat(), cy, 9.5f, 11.5f, pal)
                fillEllipse(img, cx.toFloat(), cy - 12f, 6.0f, 5.5f, pal)
                // Horns
                fillRect(img, cx - 6, cy.toInt() - 20, 2, 4, beakRamp, 0.9f)
                fillRect(img, cx + 4, cy.toInt() - 20, 2, 4, beakRamp, 0.8f)
                // Wings
                val flap = if (phase % 2 == 1) -2 else 0
                fillRect(img, cx - 14, cy.toInt() - 10 + flap, 5, 12, natural(70, 62, 96), 0.75f)
                fillRect(img, cx + 9, cy.toInt() - 10 + flap, 5, 12, natural(70, 62, 96), 0.55f)
                fillRect(img, cx - 6, 39, 4, 7, pal, 0.6f)
                fillRect(img, cx + 2, 39, 4, 7, pal, 0.45f)
                if (!dir.isBack) {
                    img.setPixel(cx - 3, cy.toInt() - 13, eyeCol)
                    if (dir != Direction8.W) img.setPixel(cx + 3, cy.toInt() - 13, eyeCol)
                }
            }
            TrevanaEnemyKind.SNOW_BANSHEE -> {
                val cy = 27f + bobY
                fillEllipse(img, cx.toFloat(), cy, 7.0f, 12.0f, pal)
                fillEllipse(img, cx.toFloat(), cy - 12f, 5.0f, 5.0f, pal)
                // Ice crown & flowing frost veil
                fillRect(img, cx - 5, cy.toInt() - 19, 10, 3, boneRamp, 0.95f)
                fillRect(img, cx - 9, cy.toInt() - 4, 3, 14, boneRamp, 0.75f)
                fillRect(img, cx + 6, cy.toInt() - 4, 3, 14, boneRamp, 0.60f)
                if (!dir.isBack) {
                    img.setPixel(cx - 2, cy.toInt() - 12, PaletteRamp.rgba(120, 232, 255))
                    if (dir != Direction8.W) img.setPixel(cx + 2, cy.toInt() - 12, PaletteRamp.rgba(120, 232, 255))
                }
            }
            TrevanaEnemyKind.SWORD_MASTER -> {
                // Boss Hollow Knight: dark plate, horned greathelm, crimson cloak, greatsword
                val body = dollGen.generateCharacterFrame(
                    PaperDollLoadout(
                        job = PaperDollJob.KNIGHT,
                        headgear = HeadgearKind.SKYRENDER_HELM,
                        weapon = WeaponKind.KNIGHT_SWORD,
                        shield = ShieldKind.DAWNBRAND_SHIELD,
                        torsoStyle = TorsoStyle.SKYRENDER,
                        legsStyle = LegsStyle.GREAVES,
                        feetStyle = FeetStyle.SABATONS,
                        includeCape = true,
                        attachments = listOf(AttachmentKind.CAPE, AttachmentKind.HORNS, AttachmentKind.SPIKED_PAULDRONS, AttachmentKind.SHADOW_MIST),
                        theme = PaperDollTheme(
                            ancestry = BodyAncestry.UNDEAD,
                            outfitPalette = PalettePresets.ABYSSAL_OBSIDIAN,
                            accentPalette = PalettePresets.LACQUER_RED,
                            secondaryPalette = PalettePresets.DARK_IRON,
                            auraPalette = PalettePresets.RIFT_CRIMSON,
                            proportions = PaperDollProportions.RMXP_32X48,
                            style = PixelStyle.MORGANA
                        )
                    ),
                    dir, action, frameIndex, includeShadow = false
                )
                return body
            }
        }

        return applyActionPose(img, view, action, phase, pal)
    }

    /**
     * Gives every monster the same readable action poses without redrawing it: an attack winds back and squashes (frame 0),
     * strikes forward with a stretch and a slash arc (frame 1), follows through (2) and recovers (3); a hurt frame recoils
     * and squashes; a cast rises with a ground ring and orbiting sparkles. "Forward" is toward the camera, away from it
     * (back view) or to the left (side view, mirrored for right).
     */
    private fun applyActionPose(src: TileImage, view: MonsterArt.View, action: PaperDollAction, phase: Int, pal: PaletteRamp): TileImage {
        val fx = if (view == MonsterArt.View.SIDE) -1 else 0
        val fy = when (view) { MonsterArt.View.FRONT -> 1; MonsterArt.View.BACK -> -1; else -> 0 }
        var step = 0
        var stretch = 1f
        when (action) {
            PaperDollAction.SLASH, PaperDollAction.THRUST, PaperDollAction.SHOOT, PaperDollAction.HARVEST -> when (phase) {
                0 -> { step = -2; stretch = 0.93f }
                1 -> { step = 4; stretch = 1.07f }
                2 -> { step = 2 }
                else -> Unit
            }
            PaperDollAction.HURT -> if (phase == 0) { step = -3; stretch = 0.92f } else { step = -1 }
            else -> Unit
        }
        val out = if (step == 0 && stretch == 1f) src else poseTransform(src, fx * step, (fy * step) / 2, stretch)
        val striking = action == PaperDollAction.SLASH || action == PaperDollAction.THRUST || action == PaperDollAction.HARVEST
        if (striking && (phase == 1 || phase == 2)) {
            val (a0, a1) = when (view) {
                MonsterArt.View.FRONT -> 20.0 to 160.0
                MonsterArt.View.BACK -> 200.0 to 340.0
                MonsterArt.View.SIDE -> 110.0 to 250.0
            }
            val white = PaletteRamp.rgba(255, 255, 255)
            drawArc(out, 16f, 30f, if (phase == 1) 14f else 12f, a0, a1, if (phase == 1) white else pal.highlight)
            if (phase == 1) drawArc(out, 16f, 30f, 12.5f, a0 + 10, a1 - 10, pal.highlight)
        } else if (action == PaperDollAction.CAST && (phase == 1 || phase == 2)) {
            val glow = pal.highlight
            val ring = (glow and 0xFFFFFF00.toInt()) or 150
            for (i in 0 until 24) {
                val a = Math.PI * 2 * i / 24
                out.drawPixel((16 + kotlin.math.cos(a) * 11).toInt().coerceIn(0, 31), (44 + kotlin.math.sin(a) * 2.6).toInt().coerceIn(0, 47), ring)
            }
            for (i in 0 until 6) {
                val a = Math.PI * 2 * i / 6 + phase * 0.5
                val sx = (16 + kotlin.math.cos(a) * 13).toInt().coerceIn(2, 29)
                val sy = (26 + kotlin.math.sin(a) * 15).toInt().coerceIn(2, 45)
                out.setPixel(sx, sy, PaletteRamp.rgba(255, 255, 255))
                out.drawPixel(sx - 1, sy, ring); out.drawPixel(sx + 1, sy, ring)
                out.drawPixel(sx, sy - 1, ring); out.drawPixel(sx, sy + 1, ring)
            }
        }
        return out
    }

    /** Moves [src] by ([dx], [dy]) and stretches it vertically by [sy] about the ground line (y = 44). */
    private fun poseTransform(src: TileImage, dx: Int, dy: Int, sy: Float): TileImage {
        val out = TileImage(src.width, src.height)
        for (y in 0 until src.height) {
            val srcY = Math.round(44f + (y - dy - 44f) / sy)
            if (srcY !in 0 until src.height) continue
            for (x in 0 until src.width) {
                val srcX = x - dx
                if (srcX in 0 until src.width) out.setPixel(x, y, src.getPixel(srcX, srcY))
            }
        }
        return out
    }

    private fun drawArc(img: TileImage, cx: Float, cy: Float, r: Float, fromDeg: Double, toDeg: Double, color: Int) {
        var d = fromDeg
        while (d <= toDeg) {
            val a = Math.toRadians(d)
            val x = (cx + kotlin.math.cos(a) * r).toInt()
            val y = (cy + kotlin.math.sin(a) * r).toInt()
            if (x in 1..30 && y in 1..46) img.setPixel(x, y, color)
            d += 4.0
        }
    }

    /** A rounded limb or plate: lit on the left, corners clipped, dithered like the tree canopy. */
    private fun fillRect(img: TileImage, x0: Int, y0: Int, w: Int, h: Int, pal: PaletteRamp, tone: Float) {
        for (y in y0 until y0 + h) {
            for (x in x0 until x0 + w) {
                val cornerX = x == x0 || x == x0 + w - 1
                val cornerY = y == y0 || y == y0 + h - 1
                if (w >= 3 && h >= 3 && cornerX && cornerY) continue
                val nx = if (w <= 1) 0f else ((x - x0).toFloat() / (w - 1)) * 2f - 1f
                val ny = if (h <= 1) 0f else ((y - y0).toFloat() / (h - 1)) * 2f - 1f
                val t = (tone + 0.12f - nx * 0.16f - ny * 0.08f).coerceIn(0.08f, 0.98f)
                img.setPixel(x.coerceIn(1, 30), y.coerceIn(1, 46), PixelDither.sampleDithered(pal, t, x, y, 4, 0.14f))
            }
        }
    }

    /** A rounded volume lit from the upper left: sphere shading, dithered, so it reads as a body and not a flat disc. */
    private fun fillEllipse(img: TileImage, cx: Float, cy: Float, rx: Float, ry: Float, pal: PaletteRamp) {
        for (y in (cy - ry).toInt().coerceAtLeast(1)..(cy + ry).toInt().coerceAtMost(46)) {
            for (x in (cx - rx).toInt().coerceAtLeast(1)..(cx + rx).toInt().coerceAtMost(30)) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                val d2 = dx * dx + dy * dy
                if (d2 <= 1f) {
                    val z = kotlin.math.sqrt(1f - d2)
                    val light = (-dx * 0.42f - dy * 0.52f + z * 0.74f).coerceIn(0f, 1f)
                    val t = (0.16f + light * 0.80f).coerceIn(0.08f, 0.98f)
                    img.setPixel(x, y, PixelDither.sampleDithered(pal, t, x, y, 4, 0.16f))
                }
            }
        }
    }

    private fun drawShadow(img: TileImage, cx: Float, cy: Float, rx: Float, ry: Float) {
        val shadow = PaletteRamp.rgba(16, 14, 28, 110)
        for (y in (cy - ry).toInt().coerceAtLeast(0)..(cy + ry).toInt().coerceAtMost(img.height - 1)) {
            for (x in (cx - rx).toInt().coerceAtLeast(0)..(cx + rx).toInt().coerceAtMost(img.width - 1)) {
                val d = hypot((x + 0.5f - cx) / rx, (y + 0.5f - cy) / ry)
                if (d <= 1f) img.drawPixel(x, y, shadow)
            }
        }
    }

    private fun composite(dst: TileImage, src: TileImage) {
        for (y in 0 until dst.height) {
            for (x in 0 until dst.width) {
                val px = src.getPixel(x, y)
                if ((px and 0xFF) != 0) dst.drawPixel(x, y, px)
            }
        }
    }

    private fun mirrorX(src: TileImage): TileImage {
        val out = TileImage(src.width, src.height)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                out.setPixel(src.width - 1 - x, y, src.getPixel(x, y))
            }
        }
        return out
    }

    private fun scaleNearest(src: TileImage, targetW: Int, targetH: Int): TileImage {
        val out = TileImage(targetW, targetH)
        for (y in 0 until targetH) {
            val sy = (y * src.height) / targetH
            for (x in 0 until targetW) {
                val sx = (x * src.width) / targetW
                out.setPixel(x, y, src.getPixel(sx, sy))
            }
        }
        return out
    }
}
