package com.morgana.tilegen.sprite

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
 * 8-way character facing directions for 32-bit JRPG and 2.5D movement.
 */
enum class Direction8(val isBack: Boolean, val isDiagonal: Boolean, val mirrorFrom: Direction8? = null) {
    S(isBack = false, isDiagonal = false),
    SW(isBack = false, isDiagonal = true),
    W(isBack = false, isDiagonal = false),
    NW(isBack = true, isDiagonal = true),
    N(isBack = true, isDiagonal = false),
    NE(isBack = true, isDiagonal = true, mirrorFrom = NW),
    E(isBack = false, isDiagonal = false, mirrorFrom = W),
    SE(isBack = false, isDiagonal = true, mirrorFrom = SW)
}

/**
 * 10 iconic JRPG and 2.5D character classes.
 */
enum class PaperDollJob {
    HERO,
    PRINCE,
    KNIGHT,
    PRIEST,
    WIZARD,
    ASSASSIN,
    HUNTER,
    SMITH,
    PALADIN,
    SHRINE_MAIDEN
}

/**
 * Modular hairstyles rendered as separate front-bang and back-crown paper-doll layers.
 */
enum class HairStyle {
    SPIKY_HERO,
    LONG_FLOWING,
    TWIN_TAILS,
    NOBLE_PARTED,
    SHORT_BOB,
    BRAIDED_CROWN
}

/**
 * Modular headgear sockets rendered atop the character's head/hair layer.
 */
enum class HeadgearKind {
    NONE,
    BANDANA,
    VALKYRIE_HELM,
    WIZARD_HAT,
    PRIEST_MITRE,
    SLIME_BERET,
    FEATHER_CAP,
    GOGGLES,
    SHRINE_RIBBON,
    IRON_HELMET,
    STEEL_HELMET,
    SKYRENDER_HELM
}

/**
 * Modular main-hand weapons rendered at the character's hand socket.
 */
enum class WeaponKind {
    NONE,
    DUAL_TONFA,
    KNIGHT_SWORD,
    ARCANE_STAFF,
    HOLY_ROD,
    DUAL_KATAR,
    COMPOSITE_BOW,
    HEAVY_AXE,
    HOLY_SPEAR,
    SINGLE_BLADE,
    KATANA,
    PICKAXE
}

/**
 * Modular off-hand shields, tomes, and dual-wield weapons.
 */
enum class ShieldKind {
    NONE,
    ROUND_BUCKLER,
    CRUSADER_CROSS_SHIELD,
    SPELLBOOK,
    TOWER_SHIELD,
    DAWNBRAND_SHIELD,
    OFFHAND_BLADE,
    OFFHAND_SWORD,
    OFFHAND_KATANA
}

/**
 * Iconic 32x32 cluster-shaded monsters in the JRPG and 2.5D style.
 */
enum class JrpgMonsterKind {
    SLIME_BLOB,
    MOON_HARE,
    LARVA,
    ORC_WARRIOR,
    HORNED_DEMON,
    HIGHLAND_BEAST,
    RUNE_GOLEM,
    SHIELD_WRAITH
}

/**
 * Composable equipment and appearance loadout for [PaperDollSpriteGenerator].
 */
/** How a robe skirt ends: a straight hem, a row of rounded scallops (water) or pointed tongues (flame). */
/** Blue-grey plate steel for armor and helmets: a mid-bright steel, not the near-white marble. */
internal val STEEL_ARMOR = PaletteRamp(intArrayOf(
    PaletteRamp.rgba(34, 42, 62), PaletteRamp.rgba(62, 76, 104), PaletteRamp.rgba(98, 116, 148),
    PaletteRamp.rgba(144, 162, 190), PaletteRamp.rgba(192, 206, 226), PaletteRamp.rgba(232, 240, 250)
))

/** Weapons drawn by the keyframed blade swing, which brings its own trailing arc. */
internal val SWINGING_BLADES = setOf(WeaponKind.KNIGHT_SWORD, WeaponKind.SINGLE_BLADE, WeaponKind.KATANA)

enum class RobeHem { STRAIGHT, SCALLOPED, JAGGED }

data class PaperDollLoadout(
    val job: PaperDollJob,
    val hairStyle: HairStyle = defaultHairFor(job),
    val headgear: HeadgearKind = defaultHeadgearFor(job),
    val weapon: WeaponKind = defaultWeaponFor(job),
    val shield: ShieldKind = defaultShieldFor(job),
    val theme: PaperDollTheme = PaperDollTheme.fromJob(job),
    val outfitPalette: PaletteRamp = theme.outfitPalette,
    val accentPalette: PaletteRamp = theme.accentPalette,
    val hairPalette: PaletteRamp = theme.hairPalette,
    val attachments: List<AttachmentKind> = defaultAttachmentsFor(job),
    val customAttachments: List<CustomPixelAttachment> = emptyList(),
    val includeCape: Boolean = defaultCapeFor(job),
    val legsStyle: LegsStyle = defaultLegsFor(job),
    val feetStyle: FeetStyle = defaultFeetFor(job),
    val torsoStyle: TorsoStyle = defaultTorsoFor(job),
    val robeHem: RobeHem = RobeHem.STRAIGHT
) {
    /**
     * Returns the effective [PaperDollTheme] with any explicit palette overrides applied.
     */
    fun effectiveTheme(): PaperDollTheme = theme.copy(
        outfitPalette = outfitPalette,
        accentPalette = accentPalette,
        hairPalette = hairPalette
    )

    companion object {
        fun forJob(job: PaperDollJob): PaperDollLoadout = PaperDollLoadout(job)

        fun forJobWithTheme(job: PaperDollJob, theme: PaperDollTheme): PaperDollLoadout =
            PaperDollLoadout(job = job, theme = theme)

        fun defaultCapeFor(job: PaperDollJob): Boolean = job in setOf(
            PaperDollJob.KNIGHT,
            PaperDollJob.WIZARD,
            PaperDollJob.PALADIN,
            PaperDollJob.PRINCE,
            PaperDollJob.HUNTER
        )

        fun defaultLegsFor(job: PaperDollJob): LegsStyle = when (job) {
            PaperDollJob.KNIGHT, PaperDollJob.PALADIN -> LegsStyle.GREAVES
            PaperDollJob.PRIEST, PaperDollJob.WIZARD, PaperDollJob.SHRINE_MAIDEN -> LegsStyle.ROBE_SKIRT
            else -> LegsStyle.TROUSERS
        }

        fun defaultFeetFor(job: PaperDollJob): FeetStyle = when (job) {
            PaperDollJob.KNIGHT, PaperDollJob.PALADIN -> FeetStyle.SABATONS
            PaperDollJob.SHRINE_MAIDEN -> FeetStyle.SANDALS
            else -> FeetStyle.BOOTS
        }

        fun defaultTorsoFor(job: PaperDollJob): TorsoStyle = when (job) {
            PaperDollJob.KNIGHT, PaperDollJob.PALADIN -> TorsoStyle.SKYRENDER
            PaperDollJob.SMITH, PaperDollJob.PRINCE -> TorsoStyle.BRIGANDINE
            else -> TorsoStyle.TRAVELER
        }

        fun defaultAttachmentsFor(job: PaperDollJob): List<AttachmentKind> = when (job) {
            PaperDollJob.KNIGHT, PaperDollJob.PALADIN -> listOf(AttachmentKind.KNIGHT_PAULDRONS)
            PaperDollJob.HUNTER -> listOf(AttachmentKind.QUIVER)
            PaperDollJob.SMITH -> listOf(AttachmentKind.UTILITY_BELT)
            PaperDollJob.PRIEST -> listOf(AttachmentKind.SILK_SASH)
            else -> emptyList()
        }

        fun defaultHairFor(job: PaperDollJob): HairStyle = when (job) {
            PaperDollJob.HERO -> HairStyle.SPIKY_HERO
            PaperDollJob.PRINCE -> HairStyle.NOBLE_PARTED
            PaperDollJob.KNIGHT -> HairStyle.SPIKY_HERO
            PaperDollJob.PRIEST -> HairStyle.LONG_FLOWING
            PaperDollJob.WIZARD -> HairStyle.LONG_FLOWING
            PaperDollJob.ASSASSIN -> HairStyle.SPIKY_HERO
            PaperDollJob.HUNTER -> HairStyle.SHORT_BOB
            PaperDollJob.SMITH -> HairStyle.SHORT_BOB
            PaperDollJob.PALADIN -> HairStyle.BRAIDED_CROWN
            PaperDollJob.SHRINE_MAIDEN -> HairStyle.TWIN_TAILS
        }

        fun defaultHeadgearFor(job: PaperDollJob): HeadgearKind = when (job) {
            PaperDollJob.HERO -> HeadgearKind.BANDANA
            PaperDollJob.PRINCE -> HeadgearKind.NONE
            PaperDollJob.KNIGHT -> HeadgearKind.VALKYRIE_HELM
            PaperDollJob.PRIEST -> HeadgearKind.PRIEST_MITRE
            PaperDollJob.WIZARD -> HeadgearKind.WIZARD_HAT
            PaperDollJob.ASSASSIN -> HeadgearKind.BANDANA
            PaperDollJob.HUNTER -> HeadgearKind.FEATHER_CAP
            PaperDollJob.SMITH -> HeadgearKind.GOGGLES
            PaperDollJob.PALADIN -> HeadgearKind.VALKYRIE_HELM
            PaperDollJob.SHRINE_MAIDEN -> HeadgearKind.SHRINE_RIBBON
        }

        fun defaultWeaponFor(job: PaperDollJob): WeaponKind = when (job) {
            PaperDollJob.HERO -> WeaponKind.DUAL_TONFA
            PaperDollJob.PRINCE -> WeaponKind.KNIGHT_SWORD
            PaperDollJob.KNIGHT -> WeaponKind.KNIGHT_SWORD
            PaperDollJob.PRIEST -> WeaponKind.HOLY_ROD
            PaperDollJob.WIZARD -> WeaponKind.ARCANE_STAFF
            PaperDollJob.ASSASSIN -> WeaponKind.DUAL_KATAR
            PaperDollJob.HUNTER -> WeaponKind.COMPOSITE_BOW
            PaperDollJob.SMITH -> WeaponKind.HEAVY_AXE
            PaperDollJob.PALADIN -> WeaponKind.HOLY_SPEAR
            PaperDollJob.SHRINE_MAIDEN -> WeaponKind.HOLY_ROD
        }

        fun defaultShieldFor(job: PaperDollJob): ShieldKind = when (job) {
            PaperDollJob.KNIGHT -> ShieldKind.CRUSADER_CROSS_SHIELD
            PaperDollJob.PRIEST -> ShieldKind.SPELLBOOK
            PaperDollJob.SMITH -> ShieldKind.ROUND_BUCKLER
            PaperDollJob.PALADIN -> ShieldKind.CRUSADER_CROSS_SHIELD
            else -> ShieldKind.NONE
        }

        fun defaultOutfitPalette(job: PaperDollJob): PaletteRamp = when (job) {
            PaperDollJob.HERO -> PalettePresets.SCARLET_ROOF
            PaperDollJob.PRINCE -> PalettePresets.CREAM_MARBLE
            PaperDollJob.KNIGHT -> STEEL_ARMOR
            PaperDollJob.PRIEST -> PalettePresets.CREAM_MARBLE
            PaperDollJob.WIZARD -> PalettePresets.ARCANE_VIOLET
            PaperDollJob.ASSASSIN -> PalettePresets.ABYSSAL_OBSIDIAN
            PaperDollJob.HUNTER -> PalettePresets.HIGHLAND_GRASS
            PaperDollJob.SMITH -> PalettePresets.WARM_TIMBER
            PaperDollJob.PALADIN -> PalettePresets.RUNE_GOLD
            PaperDollJob.SHRINE_MAIDEN -> PalettePresets.SCARLET_ROOF
        }

        fun defaultAccentPalette(job: PaperDollJob): PaletteRamp = when (job) {
            PaperDollJob.HERO -> PalettePresets.PALE_LIMESTONE
            PaperDollJob.PRINCE -> PalettePresets.RUNE_GOLD
            PaperDollJob.KNIGHT -> PalettePresets.SCARLET_ROOF
            PaperDollJob.PRIEST -> PalettePresets.RUNE_GOLD
            PaperDollJob.WIZARD -> PalettePresets.RUNE_GOLD
            PaperDollJob.ASSASSIN -> PalettePresets.SCARLET_ROOF
            PaperDollJob.HUNTER -> PalettePresets.WARM_TIMBER
            PaperDollJob.SMITH -> PalettePresets.RUNE_GOLD
            PaperDollJob.PALADIN -> PalettePresets.CREAM_MARBLE
            PaperDollJob.SHRINE_MAIDEN -> PalettePresets.CREAM_MARBLE
        }

        fun defaultHairPalette(job: PaperDollJob): PaletteRamp = when (job) {
            PaperDollJob.HERO -> PaletteRamp.fromBaseColor(PaletteRamp.rgba(52, 44, 58), steps = 5)
            PaperDollJob.PRINCE -> PalettePresets.RUNE_GOLD
            PaperDollJob.KNIGHT -> PalettePresets.SCARLET_ROOF
            PaperDollJob.PRIEST -> PalettePresets.PASTEL_PINK
            PaperDollJob.WIZARD -> PalettePresets.CREAM_MARBLE
            PaperDollJob.ASSASSIN -> PalettePresets.ARCANE_VIOLET
            PaperDollJob.HUNTER -> PalettePresets.WARM_TIMBER
            PaperDollJob.SMITH -> PalettePresets.SCARLET_ROOF
            PaperDollJob.PALADIN -> PalettePresets.RUNE_GOLD
            PaperDollJob.SHRINE_MAIDEN -> PaletteRamp.fromBaseColor(PaletteRamp.rgba(44, 38, 64), steps = 5)
        }
    }
}

/**
 * Deterministic 32x32-native (and scalable 48x48 / 64x64) tall-chibi character & monster generator
 * in the 32-bit JRPG and 2.5D paper-doll styles.
 *
 * Features:
 * - 3-head tall anatomical proportions (28px tall on a 32x32 canvas)
 * - 8-directional facing ([Direction8]), including genuine 3/4 diagonal views (`SW`, `SE`, `NW`, `NE`)
 * - Separately outlined & z-ordered paper-doll layers (Cape, Body, Head, Hair, Headgear, Weapon, Shield)
 * - Light-aware selective outlining ("sel-out") via [PixelDither.applySelectiveOutline]
 * - Multi-clip animations ([SpriteAnimationClip]: `IDLE`, `WALK`, `ATTACK`, `CAST`, `HURT`, `KO`)
 */
class PaperDollSpriteGenerator(
    val seed: Long = 1337L,
    val tileSize: Int = 32,
    val tileHeight: Int = tileSize
) {
    init {
        require(tileSize >= 16 && tileSize % 2 == 0) {
            "tileSize must be an even integer >= 16, got $tileSize"
        }
        require(tileHeight >= 16 && tileHeight % 2 == 0) {
            "tileHeight must be an even integer >= 16, got $tileHeight"
        }
    }

    /**
     * Generates a single paper-doll character frame for [loadout], [direction], [frameIndex], and [clip].
     */
    fun generateCharacterFrame(
        loadout: PaperDollLoadout,
        direction: Direction8 = Direction8.S,
        frameIndex: Int = 0,
        style: PixelStyle = PixelStyle.PAINTED_25D,
        clip: SpriteAnimationClip = SpriteAnimationClip.WALK
    ): TileImage = generateCharacterFrame(
        loadout = loadout,
        direction = direction,
        action = PaperDollAction.fromSpriteAnimationClip(clip),
        frameIndex = frameIndex,
        style = style
    )

    /**
     * Generates a single composited paper-doll character frame for [loadout], [direction], [action], and [frameIndex].
     */
    fun generateCharacterFrame(
        loadout: PaperDollLoadout,
        direction: Direction8 = Direction8.S,
        action: PaperDollAction = PaperDollAction.WALK,
        frameIndex: Int = 0,
        style: PixelStyle = loadout.effectiveTheme().style,
        includeShadow: Boolean = true
    ): TileImage {
        val (layers, _) = generateLayeredFrame(loadout, direction, action, frameIndex, style)
        return compositeLayers(layers, direction, action, includeShadow)
    }

    /**
     * Generates individual, aligned, selectively-outlined paper-doll layers keyed by [PaperDollSlot]
     * alongside the exact [PaperDollSockets] for [direction], [action], and [frameIndex].
     *
     * Every layer (except [PaperDollSlot.SHADOW]) has a clean transparent background and can be
     * toggled, re-ordered, or tinted independently by a game engine.
     */
    fun generateLayeredFrame(
        loadout: PaperDollLoadout,
        direction: Direction8 = Direction8.S,
        action: PaperDollAction = PaperDollAction.WALK,
        frameIndex: Int = 0,
        style: PixelStyle = loadout.effectiveTheme().style
    ): Pair<Map<PaperDollSlot, TileImage>, PaperDollSockets> {
        val baseDir = direction.mirrorFrom ?: direction
        val mirrored = direction.mirrorFrom != null
        val theme = loadout.effectiveTheme()
        val nativeH = if (tileHeight == 48 || theme.proportions == PaperDollProportions.RMXP_32X48) 48 else 32
        val (rawLayers, rawSockets) = renderPaperDollLayers32(loadout, baseDir, frameIndex, action, style, nativeH)

        val outLayers = linkedMapOf<PaperDollSlot, TileImage>()
        for ((slot, layer32) in rawLayers) {
            if (!hasVisiblePixels(layer32)) continue
            val oriented = if (mirrored) mirrorImageX(layer32) else layer32
            val scaled = if (tileSize == 32 && tileHeight == nativeH) oriented else scaleNearest(oriented, tileSize, tileHeight)
            val styled = if (slot == PaperDollSlot.SHADOW) {
                scaled
            } else {
                PixelDither.applyStyle(scaled, style, theme.outfitPalette)
            }
            outLayers[slot] = styled
        }

        val finalSockets = if (mirrored) {
            rawSockets.copy(
                pivotX = 32 - 1 - rawSockets.pivotX,
                headX = 32 - 1 - rawSockets.headX,
                faceX = 32 - 1 - rawSockets.faceX,
                backX = 32 - 1 - rawSockets.backX,
                shoulderX = 32 - 1 - rawSockets.shoulderX,
                waistX = 32 - 1 - rawSockets.waistX,
                mainHandX = 32 - 1 - rawSockets.mainHandX,
                offHandX = 32 - 1 - rawSockets.offHandX
            )
        } else {
            rawSockets
        }

        return outLayers to finalSockets.scaled(tileSize, tileHeight, nativeH)
    }

    /**
     * Composites [layers] into a single [TileImage] using the canonical direction-aware z-order
     * for [direction] and [action].
     */
    fun compositeLayers(
        layers: Map<PaperDollSlot, TileImage>,
        direction: Direction8,
        action: PaperDollAction = PaperDollAction.WALK,
        includeShadow: Boolean = true
    ): TileImage {
        val out = TileImage(tileSize, tileHeight)
        for (slot in PaperDollSlot.zOrderFor(direction, action)) {
            if (!includeShadow && slot == PaperDollSlot.SHADOW) continue
            val layer = layers[slot] ?: continue
            compositeOverlay(out, layer)
        }
        return out
    }

    /**
     * Generates a Universal LPC-style sprite sheet bundle for [loadout] across [actions] and [directions],
     * producing both the composited sheet and individual aligned layer sheets per [PaperDollSlot],
     * plus [AnimationSequence]s and per-frame [PaperDollSockets].
     */
    fun generateUniversalSheet(
        name: String,
        loadout: PaperDollLoadout,
        actions: List<PaperDollAction> = PaperDollAction.ALL,
        directions: List<Direction8> = Direction8.entries,
        style: PixelStyle = loadout.effectiveTheme().style,
        includeShadow: Boolean = true
    ): UniversalPaperDollSheet {
        require(name.isNotBlank()) { "Paper-doll sheet name must not be blank" }
        require(actions.isNotEmpty()) { "actions must not be empty" }
        require(directions.isNotEmpty()) { "directions must not be empty" }

        val columns = actions.maxOf { it.defaultFrames }
        val rows = actions.size * directions.size
        val compositeSheet = TileImage(columns * tileSize, rows * tileHeight)
        val layerBuffers = linkedMapOf<PaperDollSlot, TileImage>()
        val frameMetadata = mutableListOf<PaperDollFrameMeta>()
        val animations = mutableListOf<AnimationSequence>()

        var rowIdx = 0
        for (action in actions) {
            for (dir in directions) {
                val seqFrames = mutableListOf<NamedTile>()
                val seqMeta = mutableListOf<AnimationFrameMeta>()
                for (frameIdx in 0 until action.defaultFrames) {
                    val (layers, sockets) = generateLayeredFrame(loadout, dir, action, frameIdx, style)
                    val comp = compositeLayers(layers, dir, action, includeShadow)
                    val dstX = frameIdx * tileSize
                    val dstY = rowIdx * tileHeight
                    compositeSheet.drawImage(comp, 0, 0, tileSize, tileHeight, dstX, dstY)

                    for ((slot, layerImg) in layers) {
                        if (!includeShadow && slot == PaperDollSlot.SHADOW) continue
                        val buf = layerBuffers.getOrPut(slot) { TileImage(columns * tileSize, rows * tileHeight) }
                        buf.drawImage(layerImg, 0, 0, tileSize, tileHeight, dstX, dstY)
                    }

                    val tileName = "${name}_${action.name.lowercase()}_${dir.name.lowercase()}_f$frameIdx"
                    seqFrames += NamedTile(tileName, comp)
                    seqMeta += AnimationFrameMeta(
                        frameIndex = frameIdx,
                        tileName = tileName,
                        durationMs = action.defaultFrameDurationMs,
                        pivotX = sockets.pivotX,
                        pivotY = sockets.pivotY,
                        handSocketX = sockets.mainHandX,
                        handSocketY = sockets.mainHandY
                    )
                    frameMetadata += PaperDollFrameMeta(
                        action = action,
                        direction = dir,
                        frameIndex = frameIdx,
                        col = frameIdx,
                        row = rowIdx,
                        durationMs = action.defaultFrameDurationMs,
                        sockets = sockets
                    )
                }
                animations += AnimationSequence(
                    name = "${name}_${action.name.lowercase()}_${dir.name.lowercase()}",
                    clip = action.toSpriteAnimationClip(),
                    direction = dir.toFacingDirection(),
                    frames = seqFrames,
                    metadata = seqMeta
                )
                rowIdx++
            }
        }

        return UniversalPaperDollSheet(
            name = name,
            tileSize = tileSize,
            columns = columns,
            rows = rows,
            actions = actions,
            directions = directions,
            compositeSheet = compositeSheet,
            layerSheets = layerBuffers,
            frameMetadata = frameMetadata,
            animations = animations,
            theme = loadout.effectiveTheme(),
            cellWidth = tileSize,
            cellHeight = tileHeight
        )
    }

    /**
     * Generates a full mix-and-match equipment/appearance asset pack for [theme] across [actions] and [directions].
     * Returns a map of asset key (e.g. `hair_spiky_hero`, `headgear_wizard_hat`, `weapon_knight_sword`,
     * `attachment_angel_wings`) to its aligned sprite sheet and [NamedTile] list.
     */
    fun generateEquipmentPack(
        theme: PaperDollTheme = PaperDollThemes.HIGHLAND_HERO,
        actions: List<PaperDollAction> = listOf(PaperDollAction.IDLE, PaperDollAction.WALK, PaperDollAction.SLASH),
        directions: List<Direction8> = listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N),
        style: PixelStyle = theme.style
    ): Map<String, Pair<TileImage, List<NamedTile>>> {
        val pack = linkedMapOf<String, Pair<TileImage, List<NamedTile>>>()
        val baseLoadout = PaperDollLoadout(
            job = PaperDollJob.HERO,
            hairStyle = HairStyle.SPIKY_HERO,
            headgear = HeadgearKind.NONE,
            weapon = WeaponKind.NONE,
            shield = ShieldKind.NONE,
            theme = theme,
            attachments = emptyList(),
            includeCape = false
        )

        fun renderSlotPack(
            key: String,
            slot: PaperDollSlot,
            loadout: PaperDollLoadout
        ) {
            val cols = actions.maxOf { it.defaultFrames }
            val rows = actions.size * directions.size
            val sheet = TileImage(cols * tileSize, rows * tileHeight)
            val tiles = mutableListOf<NamedTile>()
            var r = 0
            for (action in actions) {
                for (dir in directions) {
                    for (f in 0 until action.defaultFrames) {
                        val (layers, _) = generateLayeredFrame(loadout, dir, action, f, style)
                        val img = layers[slot] ?: TileImage(tileSize, tileHeight)
                        sheet.drawImage(img, 0, 0, tileSize, tileHeight, f * tileSize, r * tileHeight)
                        tiles += NamedTile("${key}_${action.name.lowercase()}_${dir.name.lowercase()}_f$f", img)
                    }
                    for (c in action.defaultFrames until cols) {
                        tiles += NamedTile("${key}_${action.name.lowercase()}_${dir.name.lowercase()}_empty_c$c", TileImage(tileSize, tileHeight))
                    }
                    r++
                }
            }
            pack[key] = sheet to tiles
        }

        // Base body
        renderSlotPack("body_${theme.ancestry.name.lowercase()}", PaperDollSlot.BODY, baseLoadout)
        renderSlotPack("eyes_${theme.ancestry.name.lowercase()}", PaperDollSlot.EYES, baseLoadout)

        // Hairstyles (front + back composited into one hair pack per style)
        for (hair in HairStyle.entries) {
            val lo = baseLoadout.copy(hairStyle = hair)
            val cols = actions.maxOf { it.defaultFrames }
            val rows = actions.size * directions.size
            val sheet = TileImage(cols * tileSize, rows * tileHeight)
            val tiles = mutableListOf<NamedTile>()
            var r = 0
            for (action in actions) {
                for (dir in directions) {
                    for (f in 0 until action.defaultFrames) {
                        val (layers, _) = generateLayeredFrame(lo, dir, action, f, style)
                        val combined = TileImage(tileSize, tileHeight)
                        layers[PaperDollSlot.HAIR_BACK]?.let { compositeOverlay(combined, it) }
                        layers[PaperDollSlot.HAIR_FRONT]?.let { compositeOverlay(combined, it) }
                        sheet.drawImage(combined, 0, 0, tileSize, tileHeight, f * tileSize, r * tileHeight)
                        tiles += NamedTile("hair_${hair.name.lowercase()}_${action.name.lowercase()}_${dir.name.lowercase()}_f$f", combined)
                    }
                    for (c in action.defaultFrames until cols) {
                        tiles += NamedTile("hair_${hair.name.lowercase()}_${action.name.lowercase()}_${dir.name.lowercase()}_empty_c$c", TileImage(tileSize, tileHeight))
                    }
                    r++
                }
            }
            pack["hair_${hair.name.lowercase()}"] = sheet to tiles
        }

        for (torso in TorsoStyle.entries) {
            renderSlotPack("torso_${torso.name.lowercase()}", PaperDollSlot.TORSO, baseLoadout.copy(torsoStyle = torso))
        }
        for (legs in LegsStyle.entries) {
            renderSlotPack("legs_${legs.name.lowercase()}", PaperDollSlot.LEGS, baseLoadout.copy(legsStyle = legs))
        }
        for (feet in FeetStyle.entries) {
            if (feet != FeetStyle.BAREFOOT) {
                renderSlotPack("feet_${feet.name.lowercase()}", PaperDollSlot.FEET, baseLoadout.copy(feetStyle = feet))
            }
        }
        for (hg in HeadgearKind.entries.filter { it != HeadgearKind.NONE }) {
            renderSlotPack("headgear_${hg.name.lowercase()}", PaperDollSlot.HEADGEAR, baseLoadout.copy(headgear = hg))
        }
        for (wpn in WeaponKind.entries.filter { it != WeaponKind.NONE }) {
            renderSlotPack("weapon_${wpn.name.lowercase()}", PaperDollSlot.MAIN_HAND, baseLoadout.copy(weapon = wpn))
        }
        for (sh in ShieldKind.entries.filter { it != ShieldKind.NONE }) {
            renderSlotPack("shield_${sh.name.lowercase()}", PaperDollSlot.OFF_HAND, baseLoadout.copy(shield = sh))
        }
        for (att in AttachmentKind.entries) {
            val slot = if (att == AttachmentKind.CAPE) PaperDollSlot.CAPE else att.socket.defaultSlot()
            val lo = if (att == AttachmentKind.CAPE) {
                baseLoadout.copy(includeCape = true)
            } else {
                baseLoadout.copy(attachments = listOf(att))
            }
            renderSlotPack("attachment_${att.name.lowercase()}", slot, lo)
        }

        return pack
    }

    /**
     * Generates [frameCount] named frames for [loadout] facing [direction].
     */
    fun generateCharacterFrames(
        name: String,
        loadout: PaperDollLoadout,
        direction: Direction8 = Direction8.S,
        frameCount: Int = 1,
        style: PixelStyle = PixelStyle.PAINTED_25D,
        clip: SpriteAnimationClip = SpriteAnimationClip.WALK
    ): List<NamedTile> {
        require(name.isNotBlank()) { "Character sprite name must not be blank" }
        require(frameCount >= 1) { "frameCount must be >= 1, got $frameCount" }
        return (0 until frameCount).map { idx ->
            val img = generateCharacterFrame(loadout, direction, idx, style, clip)
            val suffix = if (frameCount == 1) "" else "_f$idx"
            NamedTile("${name}_${direction.name.lowercase()}$suffix", img)
        }
    }

    /**
     * Generates a bundled sprite sheet of all 10 [PaperDollJob]s across directions and frames.
     */
    fun generateAllJobsSheet(
        include8Directions: Boolean = true,
        frameCount: Int = 2,
        style: PixelStyle = PixelStyle.PAINTED_25D,
        clip: SpriteAnimationClip = SpriteAnimationClip.WALK
    ): Pair<TileImage, List<NamedTile>> {
        val dirs = if (include8Directions) Direction8.entries else listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N)
        val tiles = mutableListOf<NamedTile>()
        for (dir in dirs) {
            for (frame in 0 until frameCount) {
                for (job in PaperDollJob.entries) {
                    val loadout = PaperDollLoadout.forJob(job)
                    val img = generateCharacterFrame(loadout, dir, frame, style, clip)
                    val frameSuffix = if (frameCount == 1) "" else "_f$frame"
                    tiles += NamedTile("${job.name.lowercase()}_${dir.name.lowercase()}$frameSuffix", img)
                }
            }
        }
        val columns = 10
        val rows = (tiles.size + columns - 1) / columns
        val sheet = TileImage(columns * tileSize, rows * tileSize)
        tiles.forEachIndexed { idx, nt ->
            val col = idx % columns
            val row = idx / columns
            sheet.drawImage(nt.image, 0, 0, tileSize, tileSize, col * tileSize, row * tileSize)
        }
        return sheet to tiles
    }

    /**
     * Generates a single 32x32-native cluster-shaded monster frame for [kind].
     */
    fun generateMonsterFrame(
        kind: JrpgMonsterKind,
        frameIndex: Int = 0,
        style: PixelStyle = PixelStyle.PAINTED_25D
    ): TileImage {
        val pal = monsterPaletteFor(kind)
        val raw32 = renderMonster32(kind, frameIndex, pal)
        val outlined = PixelDither.applySelectiveOutline(raw32)
        val withShadow = TileImage(32, 32)
        drawGroundShadow32(withShadow, 16f, 28.5f, 8.5f, 2.2f)
        withShadow.drawImage(outlined, 0, 0, 32, 32, 0, 0)
        val scaled = if (tileSize == 32) withShadow else scaleNearest(withShadow, tileSize)
        return PixelDither.applyStyle(scaled, style, pal)
    }

    /**
     * Generates a sheet of all 8 [JrpgMonsterKind]s with [frameCount] frames each.
     */
    fun generateAllMonstersSheet(
        frameCount: Int = 2,
        style: PixelStyle = PixelStyle.PAINTED_25D
    ): Pair<TileImage, List<NamedTile>> {
        val tiles = mutableListOf<NamedTile>()
        for (frame in 0 until frameCount) {
            for (kind in JrpgMonsterKind.entries) {
                val img = generateMonsterFrame(kind, frame, style)
                val suffix = if (frameCount == 1) "" else "_f$frame"
                tiles += NamedTile("${kind.name.lowercase()}$suffix", img)
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

    private fun renderPaperDollLayers32(
        loadout: PaperDollLoadout,
        baseDir: Direction8,
        frameIndex: Int,
        action: PaperDollAction,
        style: PixelStyle,
        nativeH: Int = 32
    ): Pair<Map<PaperDollSlot, TileImage>, PaperDollSockets> {
        val theme = loadout.effectiveTheme()
        val isRmxp = nativeH == 48
        val chunky = isRmxp || theme.proportions == PaperDollProportions.CHUNKY
        val (pose, sockets) = PaperDollPose.compute(baseDir, action, frameIndex, theme.proportions, nativeH)
        val cx = 16 + pose.lungeX
        val headCy = (if (isRmxp) 17 else if (chunky) 9 else 10) + pose.bobY + (if (action == PaperDollAction.HARVEST && pose.phase in 1..2) 1 else 0)
        val torsoTop = (if (isRmxp) 23 else 14) + pose.bobY
        val legTop = (if (isRmxp) (if (pose.isSeated) 34 else 36) else (if (pose.isSeated) 21 else 22)) + pose.bobY
        val stride = pose.stride
        val phase = pose.phase

        val swing = Swing.forAction(action, phase)
        val trailLayer = TileImage(32, nativeH)
        val shadowLayer = TileImage(32, nativeH)
        if (style == PixelStyle.MORGANA) {
            SpriteFinish.softShadow(shadowLayer, 16f, if (isRmxp) 45.5f else 28.5f, pose.shadowRadiusX + 1f, 2.6f)
        } else {
            drawGroundShadow32(shadowLayer, 16f, if (isRmxp) 45.5f else 28.5f, pose.shadowRadiusX, 2.0f)
        }

        val auraBackLayer = TileImage(32, nativeH)
        val capeLayer = TileImage(32, nativeH)
        val backAttachmentLayer = TileImage(32, nativeH)
        val bodyLayer = TileImage(32, nativeH)
        val eyesLayer = TileImage(32, nativeH)
        val legsLayer = TileImage(32, nativeH)
        val feetLayer = TileImage(32, nativeH)
        val torsoLayer = TileImage(32, nativeH)
        val waistLayer = TileImage(32, nativeH)
        val shoulderLayer = TileImage(32, nativeH)
        val hairBackLayer = TileImage(32, nativeH)
        val hairFrontLayer = TileImage(32, nativeH)
        val faceAccessoryLayer = TileImage(32, nativeH)
        val headgearLayer = TileImage(32, nativeH)
        val weaponLayer = TileImage(32, nativeH)
        val shieldLayer = TileImage(32, nativeH)
        val auraFrontLayer = TileImage(32, nativeH)

        // 1. Cape / Mantle
        if (loadout.includeCape || AttachmentKind.CAPE in loadout.attachments) {
            drawCape32(capeLayer, cx, torsoTop, baseDir, stride, theme.accentPalette, chunky)
        }

        // 2. Back Attachments (wings, quiver, backpack, banner)
        for (att in loadout.attachments) {
            if (att.socket == AttachmentSocket.BACK && att != AttachmentKind.CAPE) {
                drawBackAttachment32(backAttachmentLayer, cx, torsoTop, baseDir, stride, att, theme)
            }
        }

        // 3. Body Base (Head/Ears/Arms/Skin) + Ancestry features
        drawHeadAndBody32(bodyLayer, eyesLayer, cx, headCy, torsoTop, baseDir, action, frameIndex, loadout, theme)

        // 4. Legs, Feet & Torso/Armor layers
        drawLegsAndFeet32(legsLayer, feetLayer, cx, legTop, baseDir, stride, pose, loadout, theme)
        drawTorso32(torsoLayer, cx, torsoTop, legTop, baseDir, stride, pose, loadout, theme, bodyLayer,
            swing = if (action == PaperDollAction.SLASH || action == PaperDollAction.HARVEST) swing else null)

        // 5. Waist & Shoulder Attachments
        for (att in loadout.attachments) {
            when (att.socket) {
                AttachmentSocket.WAIST -> drawWaistAttachment32(waistLayer, cx, legTop, baseDir, att, theme, chunky)
                AttachmentSocket.SHOULDER -> drawShoulderAttachment32(shoulderLayer, cx, torsoTop, baseDir, stride, att, theme, chunky)
                else -> Unit
            }
        }

        // 6. Hair Back & Front Bangs
        drawHair32(hairBackLayer, hairFrontLayer, cx, headCy, baseDir, stride, loadout.hairStyle, theme.hairPalette, chunky, isRmxp)

        // 7. Face Accessories & Headgear + Head Attachments
        for (att in loadout.attachments) {
            when (att.socket) {
                AttachmentSocket.FACE -> drawFaceAccessory32(faceAccessoryLayer, cx, headCy, baseDir, att, theme)
                AttachmentSocket.HEAD -> drawHeadAttachment32(headgearLayer, cx, headCy, baseDir, att, theme)
                else -> Unit
            }
        }
        if (loadout.headgear != HeadgearKind.NONE) {
            drawHeadgear32(headgearLayer, cx, headCy, baseDir, loadout.headgear, theme.accentPalette, theme.outfitPalette, chunky)
        }

        // 8. Weapon & Shield Hand Sockets + Hand Attachments
        for (att in loadout.attachments) {
            when (att.socket) {
                AttachmentSocket.MAIN_HAND -> drawMainHandAttachment32(weaponLayer, cx, torsoTop, baseDir, att, theme)
                AttachmentSocket.OFF_HAND -> drawOffHandAttachment32(shieldLayer, cx, torsoTop, baseDir, att, theme)
                else -> Unit
            }
        }
        if (loadout.weapon != WeaponKind.NONE) {
            drawWeapon32(weaponLayer, cx, torsoTop, baseDir, action, phase, loadout.weapon, theme.accentPalette, chunky, swing, trailLayer)
        }
        if (loadout.shield != ShieldKind.NONE) {
            drawShield32(shieldLayer, cx, torsoTop, baseDir, loadout.shield, theme.accentPalette, theme.outfitPalette, chunky)
        }

        // 9. Aura Attachments + Action VFX
        for (att in loadout.attachments) {
            if (att.socket == AttachmentSocket.AURA) {
                drawAuraAttachment32(auraBackLayer, auraFrontLayer, cx, torsoTop, frameIndex, att, theme.auraPalette)
            }
        }
        when {
            action == PaperDollAction.CAST && (phase == 1 || phase == 2) -> drawCastAura32(auraFrontLayer, cx, phase, theme.auraPalette)
            action == PaperDollAction.SLASH && phase == 1 && loadout.weapon !in SWINGING_BLADES -> drawAttackSlash32(auraFrontLayer, cx, torsoTop, baseDir)
            action == PaperDollAction.THRUST && phase == 1 -> drawThrustVfx32(auraFrontLayer, cx, torsoTop, baseDir)
            action == PaperDollAction.SHOOT && phase == 2 -> drawShootVfx32(auraFrontLayer, cx, torsoTop, baseDir)
            action == PaperDollAction.HARVEST && phase == 1 -> drawHarvestVfx32(auraFrontLayer, cx, torsoTop, baseDir)
        }

        // 10. Custom Pixel-Grid Attachments
        if (loadout.customAttachments.isNotEmpty()) {
            val slotMap = mapOf(
                AttachmentSocket.BACK to backAttachmentLayer,
                AttachmentSocket.HEAD to headgearLayer,
                AttachmentSocket.FACE to faceAccessoryLayer,
                AttachmentSocket.SHOULDER to shoulderLayer,
                AttachmentSocket.WAIST to waistLayer,
                AttachmentSocket.MAIN_HAND to weaponLayer,
                AttachmentSocket.OFF_HAND to shieldLayer,
                AttachmentSocket.AURA to auraFrontLayer
            )
            for (custom in loadout.customAttachments) {
                if (custom.hideWhenFacingBack && baseDir.isBack) continue
                if (custom.hideWhenFacingFront && !baseDir.isBack) continue
                val target = slotMap[custom.socket] ?: continue
                drawCustomAttachment32(target, custom, sockets, theme.accentPalette)
            }
        }

        val shadowAnchor = if (style == PixelStyle.JRPG_32BIT) {
            PaletteRamp.rgba(28, 22, 42)
        } else {
            PaletteRamp.rgba(34, 30, 54)
        }
        fun withTrail(trail: TileImage, weapon: TileImage): TileImage {
            if (!hasVisiblePixels(trail)) return weapon
            val merged = trail.copy()
            compositeOverlay(merged, weapon)
            return merged
        }
        fun outline(layer: TileImage): TileImage =
            if (style == PixelStyle.MORGANA) SpriteFinish.finish(layer) else PixelDither.applySelectiveOutline(layer, shadowAnchor)

        val rawMap = linkedMapOf(
            PaperDollSlot.SHADOW to shadowLayer,
            PaperDollSlot.AURA_BACK to auraBackLayer,
            PaperDollSlot.CAPE to outline(capeLayer),
            PaperDollSlot.BACK_ATTACHMENT to outline(backAttachmentLayer),
            PaperDollSlot.BODY to outline(bodyLayer),
            PaperDollSlot.EYES to eyesLayer,
            PaperDollSlot.HAIR_BACK to outline(hairBackLayer),
            PaperDollSlot.LEGS to outline(legsLayer),
            PaperDollSlot.FEET to outline(feetLayer),
            PaperDollSlot.TORSO to outline(torsoLayer),
            PaperDollSlot.WAIST_ATTACHMENT to outline(waistLayer),
            PaperDollSlot.SHOULDER_ATTACHMENT to outline(shoulderLayer),
            PaperDollSlot.HAIR_FRONT to outline(hairFrontLayer),
            PaperDollSlot.FACE_ACCESSORY to outline(faceAccessoryLayer),
            PaperDollSlot.HEADGEAR to outline(headgearLayer),
            PaperDollSlot.OFF_HAND to outline(shieldLayer),
            PaperDollSlot.MAIN_HAND to withTrail(trailLayer, outline(weaponLayer)),
            PaperDollSlot.AURA_FRONT to auraFrontLayer
        )
        return rawMap to sockets
    }

    private fun drawCape32(
        img: TileImage,
        cx: Int,
        torsoTop: Int,
        dir: Direction8,
        stride: Int,
        pal: PaletteRamp,
        chunky: Boolean = false
    ) {
        val flutter = stride / 2
        val topY = torsoTop + 1
        val botY = (torsoTop + (if (img.height == 48) 16 else 11)).coerceAtMost(img.height - 4)
        val baseHalfW = if (chunky) 6 else 4
        for (y in topY..botY) {
            val frac = (y - topY).toFloat() / (botY - topY).coerceAtLeast(1)
            val halfW = (baseHalfW + (frac * 3.5f).toInt())
            val shiftX = when (dir) {
                Direction8.W, Direction8.SW, Direction8.NW -> 2 + flutter
                else -> flutter
            }
            for (x in (cx - halfW + shiftX)..(cx + halfW + shiftX)) {
                if (x !in 2..29) continue
                val light = (0.72f - frac * 0.35f - abs(x - cx) * 0.04f).coerceIn(0.18f, 0.92f)
                img.setPixel(x, y, pal.sample(light))
            }
        }
    }

    private fun drawHeadAndBody32(
        bodyImg: TileImage,
        eyesImg: TileImage,
        cx: Int,
        headCy: Int,
        torsoTop: Int,
        dir: Direction8,
        action: PaperDollAction,
        frameIndex: Int,
        loadout: PaperDollLoadout,
        theme: PaperDollTheme
    ) {
        val chunky = theme.proportions == PaperDollProportions.CHUNKY
        val skin = theme.skinPalette
        val skinLight = skin.highlight
        val skinMid = skin.mid
        val skinShadow = skin.shadow

        // Neck & hands/arms base in skin tone so body layer is a true paper-doll base
        val neckW = if (chunky) 5 else 3
        fillRectShaded(bodyImg, (cx - neckW / 2).coerceIn(1, 29), (torsoTop - 1).coerceIn(1, 29), neckW, 2, skin, 0.55f)
        val handDx = if (chunky) 8 else 5
        if (dir != Direction8.W) {
            bodyImg.setPixel((cx - handDx).coerceIn(1, 30), (torsoTop + 5).coerceIn(1, 30), skinMid)
            bodyImg.setPixel((cx + handDx).coerceIn(1, 30), (torsoTop + 5).coerceIn(1, 30), skinShadow)
            if (chunky) {
                bodyImg.setPixel((cx - handDx - 1).coerceIn(1, 30), (torsoTop + 5).coerceIn(1, 30), skinLight)
                bodyImg.setPixel((cx + handDx + 1).coerceIn(1, 30), (torsoTop + 5).coerceIn(1, 30), skinMid)
            }
        } else {
            bodyImg.setPixel(cx.coerceIn(1, 30), (torsoTop + 6).coerceIn(1, 30), skinMid)
        }

        // Head ellipse
        val rmxp = bodyImg.height == 48
        val rx = when {
            rmxp && dir == Direction8.W -> 4.9f
            rmxp -> 5.6f
            chunky && dir == Direction8.W -> 5.8f
            chunky -> 6.8f
            dir == Direction8.W -> 4.4f
            else -> 5.2f
        }
        val ry = if (rmxp) 5.6f else if (chunky) 5.4f else 4.6f
        PixelForm.dome(bodyImg, cx + 0.0f, headCy + 0.5f, rx + 0.4f, ry + 0.5f, skin, bias = if (dir.isBack) -0.32f else 0.02f)
        if (theme.ancestry == BodyAncestry.CONSTRUCT && !dir.isBack) {
            for (x in (cx - 3)..(cx + 3)) bodyImg.setPixel(x.coerceIn(1, 30), headCy + 2, skinShadow)
        }
        if (!dir.isBack && dir != Direction8.W) {
            // Ear nubs on the lit and shaded sides so the head is not a bare ball.
            val earY = headCy + 1
            bodyImg.setPixel((cx - rx.toInt() - 1).coerceIn(1, 30), earY, skinMid)
            bodyImg.setPixel((cx + rx.toInt() + 1).coerceIn(1, 30), earY, skinShadow)
        }

        // Ancestry-specific ears / tusks / horns / construct seams on BODY layer
        when (theme.ancestry) {
            BodyAncestry.ELF -> {
                if (dir != Direction8.W) {
                    bodyImg.setPixel((cx - 6).coerceIn(1, 30), headCy, skinLight)
                    bodyImg.setPixel((cx - 7).coerceIn(1, 30), (headCy - 1).coerceIn(1, 30), skinMid)
                    bodyImg.setPixel((cx + 6).coerceIn(1, 30), headCy, skinMid)
                    bodyImg.setPixel((cx + 7).coerceIn(1, 30), (headCy - 1).coerceIn(1, 30), skinShadow)
                } else {
                    bodyImg.setPixel((cx + 4).coerceIn(1, 30), headCy, skinMid)
                    bodyImg.setPixel((cx + 5).coerceIn(1, 30), (headCy - 1).coerceIn(1, 30), skinShadow)
                }
            }
            BodyAncestry.ORC -> {
                if (!dir.isBack) {
                    val tusk = PaletteRamp.rgba(244, 240, 218)
                    val tuskY = (headCy + 3).coerceIn(1, 30)
                    if (dir == Direction8.W) {
                        bodyImg.setPixel((cx - 4).coerceIn(1, 30), tuskY, tusk)
                    } else {
                        bodyImg.setPixel((cx - 2).coerceIn(1, 30), tuskY, tusk)
                        bodyImg.setPixel((cx + 2).coerceIn(1, 30), tuskY, tusk)
                    }
                }
            }
            BodyAncestry.DEMON -> {
                val hornCol = theme.accentPalette.shadow
                bodyImg.setPixel((cx - 3).coerceIn(1, 30), (headCy - 5).coerceIn(1, 30), hornCol)
                bodyImg.setPixel((cx - 4).coerceIn(1, 30), (headCy - 6).coerceIn(1, 30), hornCol)
                bodyImg.setPixel((cx + 3).coerceIn(1, 30), (headCy - 5).coerceIn(1, 30), hornCol)
                bodyImg.setPixel((cx + 4).coerceIn(1, 30), (headCy - 6).coerceIn(1, 30), hornCol)
            }
            BodyAncestry.UNDEAD -> {
                if (!dir.isBack) {
                    val hollow = PaletteRamp.rgba(42, 38, 48)
                    bodyImg.setPixel(cx.coerceIn(1, 30), (headCy + 2).coerceIn(1, 30), hollow)
                }
            }
            BodyAncestry.CONSTRUCT -> {
                val trim = theme.accentPalette.highlight
                bodyImg.setPixel((cx - 4).coerceIn(1, 30), headCy, trim)
                if (dir != Direction8.W) bodyImg.setPixel((cx + 4).coerceIn(1, 30), headCy, trim)
            }
            BodyAncestry.HUMAN -> Unit
        }

        if (dir.isBack) return

        // Expressive 2-pixel tall anime eyes in dedicated EYES layer
        val eyeIris = theme.eyeColor
        val eyeHighlight = PaletteRamp.rgba(255, 255, 255)
        val eyeY = headCy + 1
        val blink = (action == PaperDollAction.HURT && frameIndex % 2 == 1) ||
            action == PaperDollAction.KO ||
            (action == PaperDollAction.EMOTE && frameIndex == 1)

        val eyeSep = if (chunky) 3 else 2
        if (rmxp) {
            // Wider-set, readable eyes (lash, iris, a white corner), a nose shadow, a small mouth and a little blush.
            val lash = PaletteRamp.rgba(44, 32, 40)
            val white = PaletteRamp.rgba(246, 244, 240)
            val rose = PaletteRamp.rgba(150, 84, 92)
            fun eyeAt(ex: Int, outward: Int) {
                if (blink) {
                    eyesImg.setPixel(ex, eyeY, lash)
                    eyesImg.setPixel(ex + outward, eyeY, lash)
                } else {
                    eyesImg.setPixel(ex, eyeY - 1, lash)
                    eyesImg.setPixel(ex + outward, eyeY - 1, lash)
                    eyesImg.setPixel(ex, eyeY, eyeIris)
                    eyesImg.setPixel(ex + outward, eyeY, white)
                }
            }
            when (dir) {
                Direction8.S -> {
                    eyeAt(cx - eyeSep, -1)
                    eyeAt(cx + eyeSep, 1)
                    eyesImg.setPixel(cx, headCy + 3, (skinShadow and 0xFFFFFF00.toInt()) or 150)
                    eyesImg.setPixel(cx, headCy + 4, rose)
                    eyesImg.setPixel(cx - 4, headCy + 3, PaletteRamp.rgba(238, 148, 140, 150))
                    eyesImg.setPixel(cx + 4, headCy + 3, PaletteRamp.rgba(238, 148, 140, 150))
                }
                Direction8.W -> {
                    eyeAt(cx - 3, 1)
                    eyesImg.setPixel(cx - 4, headCy + 3, (skinShadow and 0xFFFFFF00.toInt()) or 150)
                    eyesImg.setPixel(cx - 3, headCy + 4, rose)
                    eyesImg.setPixel(cx - 1, headCy + 3, PaletteRamp.rgba(238, 148, 140, 150))
                }
                else -> Unit
            }
            return
        }
        when (dir) {
            Direction8.S -> {
                drawAnimeEye(eyesImg, cx - eyeSep, eyeY, eyeIris, eyeHighlight, blink)
                drawAnimeEye(eyesImg, cx + eyeSep, eyeY, eyeIris, eyeHighlight, blink)
                if (chunky && !blink) {
                    drawAnimeEye(eyesImg, cx - eyeSep + 1, eyeY, eyeIris, eyeHighlight, false)
                    drawAnimeEye(eyesImg, cx + eyeSep - 1, eyeY, eyeIris, eyeHighlight, false)
                }
            }
            Direction8.SW -> {
                drawAnimeEye(eyesImg, cx - eyeSep - 1, eyeY, eyeIris, eyeHighlight, blink)
                drawAnimeEye(eyesImg, cx + 1, eyeY, eyeIris, eyeHighlight, blink)
            }
            Direction8.W -> {
                drawAnimeEye(eyesImg, cx - eyeSep - 1, eyeY, eyeIris, eyeHighlight, blink)
            }
            else -> Unit
        }
    }

    private fun drawLegsAndFeet32(
        legsImg: TileImage,
        feetImg: TileImage,
        cx: Int,
        legTop: Int,
        dir: Direction8,
        stride: Int,
        pose: PaperDollPose,
        loadout: PaperDollLoadout,
        theme: PaperDollTheme
    ) {
        val legsPal = when (loadout.legsStyle) {
            LegsStyle.GREAVES -> theme.accentPalette
            LegsStyle.ROBE_SKIRT -> theme.outfitPalette
            LegsStyle.TROUSERS, LegsStyle.SHORTS -> theme.secondaryPalette
        }
        val feetPal = when (loadout.feetStyle) {
            FeetStyle.SABATONS -> theme.accentPalette
            FeetStyle.BAREFOOT -> theme.skinPalette
            FeetStyle.BOOTS, FeetStyle.SANDALS -> theme.secondaryPalette
        }

        val maxY = legsImg.height - 3
        val standFootY = if (legsImg.height == 48) 44 else 27
        val baseFootY = when {
            pose.isSeated -> standFootY
            pose.isAirborne -> standFootY - 3
            pose.isCollapsed -> maxY
            else -> standFootY
        }
        val minFootY = (legTop + 1).coerceAtMost(maxY)
        val leftFootY = (baseFootY + stride / 2).coerceIn(minFootY, maxY)
        val rightFootY = (baseFootY - stride / 2).coerceIn(minFootY, maxY)

        val chunky = legsImg.height == 48 || theme.proportions == PaperDollProportions.CHUNKY
        if (loadout.legsStyle == LegsStyle.ROBE_SKIRT && !pose.isSeated && legsImg.height == 48) {
            val h = (leftFootY - legTop).coerceIn(4, 8)
            val trim = theme.accentPalette
            val sway = stride / 2
            for (yy in 0 until h) {
                val t = yy.toFloat() / (h - 1)
                val half = if (dir == Direction8.W) 4 + (t * 1.5f).toInt() else 5 + (t * 2.4f).toInt()
                val shift = if (dir == Direction8.W) -(t * 1.5f).toInt() + sway / 2 else 0
                for (x in (cx - half + shift)..(cx + half - 1 + shift)) {
                    val edge = x == cx - half + shift || x == cx + half - 1 + shift
                    val tone = (0.74f - t * 0.16f - (x - cx) * 0.012f - (if (edge) 0.12f else 0f)).coerceIn(0.2f, 0.9f)
                    val col = if (yy == h - 2) trim.sample((0.7f - (x - cx) * 0.01f).coerceIn(0.3f, 0.9f)) else legsPal.sample(tone)
                    // Hem shape on the last row: scallops leave every third pixel out; flames keep every third and reach lower.
                    if (yy == h - 1) {
                        when (loadout.robeHem) {
                            RobeHem.SCALLOPED -> if ((x - cx).mod(3) == 0) continue
                            RobeHem.JAGGED -> if ((x - cx).mod(3) != 0) continue
                            RobeHem.STRAIGHT -> Unit
                        }
                    }
                    legsImg.setPixel(x.coerceIn(1, 30), (legTop + yy).coerceIn(1, legsImg.height - 2), col)
                }
                if (yy == h - 1 && loadout.robeHem == RobeHem.JAGGED) {
                    for (x in (cx - half + shift)..(cx + half - 1 + shift)) {
                        if ((x - cx).mod(3) == 0) legsImg.setPixel(x.coerceIn(1, 30), (legTop + yy + 1).coerceIn(1, legsImg.height - 2), legsPal.sample(0.45f))
                    }
                }
            }
            fillRectShaded(feetImg, cx - 4, leftFootY, 3, 2, feetPal, 0.58f)
            fillRectShaded(feetImg, cx + 1, rightFootY, 3, 2, feetPal, 0.45f)
            return
        }
        if (loadout.legsStyle == LegsStyle.ROBE_SKIRT && !pose.isSeated) {
            val w = if (dir == Direction8.W) (if (chunky) 9 else 6) else (if (chunky) 13 else 8)
            fillRectShaded(legsImg, cx - w / 2, legTop, w, (leftFootY - legTop).coerceIn(2, 8), legsPal, 0.65f)
            val fw = if (chunky) 4 else 2
            fillRectShaded(feetImg, cx - (if (chunky) 5 else 3), leftFootY - 1, fw, 2, feetPal, 0.58f)
            fillRectShaded(feetImg, cx + 1, rightFootY - 1, fw, 2, feetPal, 0.45f)
            return
        }

        val legW = if (chunky) 4 else 3
        if (dir == Direction8.W) {
            val leg1X = cx - 1 + stride
            val leg2X = cx - 1 - stride
            val legH = if (pose.isSeated) 2 else (if (legsImg.height == 48) 6 else 3)
            fillRectShaded(legsImg, leg2X, legTop, legW, legH, legsPal, 0.42f)
            fillRectShaded(feetImg, leg2X - (if (pose.isSeated) 1 else 0), (legTop + legH).coerceAtMost(standFootY), legW, 2, feetPal, 0.38f)
            fillRectShaded(legsImg, leg1X, legTop, legW, legH, legsPal, 0.72f)
            fillRectShaded(feetImg, leg1X - (if (pose.isSeated) 1 else 0), (legTop + legH).coerceAtMost(standFootY), legW, 2, feetPal, 0.68f)
        } else {
            val lx = if (chunky) cx - 5 else cx - 4
            val rx = if (chunky) cx + 2 else if (dir.isDiagonal) cx + 1 else cx + 2
            fun leg(x0: Int, footY: Int, near: Boolean) {
                val shorts = loadout.legsStyle == LegsStyle.SHORTS
                val footTop = (footY - 2).coerceAtLeast(legTop)
                val legBottom = if (shorts) (legTop + 3).coerceAtMost(footTop) else footTop
                val legH = (legBottom - legTop).coerceAtLeast(1)
                PixelForm.column(legsImg, x0.coerceIn(1, 30), legTop, legW, legH, legsPal, if (near) 0.56f else 0.42f, round = false)
                // Boot: darker cuff line, then a column that is one pixel wider toward the toe.
                PixelForm.column(feetImg, x0.coerceIn(1, 30), footTop, legW, (footY - footTop + 1).coerceAtLeast(2), feetPal, if (near) 0.58f else 0.44f)
                feetImg.setPixel(x0.coerceIn(1, 30), (footY).coerceIn(1, 30), feetPal.sample(if (near) 0.25f else 0.18f))
            }
            leg(rx, rightFootY, near = false)
            leg(lx, leftFootY, near = true)
        }
    }

    private fun drawTorso32(
        img: TileImage,
        cx: Int,
        torsoTop: Int,
        legTop: Int,
        dir: Direction8,
        stride: Int,
        pose: PaperDollPose,
        loadout: PaperDollLoadout,
        theme: PaperDollTheme,
        handsImg: TileImage = img,
        swing: Swing? = null
    ) {
        val chunky = img.height == 48 || theme.proportions == PaperDollProportions.CHUNKY
        val outfit = theme.outfitPalette
        val accent = theme.accentPalette
        val boots = theme.secondaryPalette

        val rmxp = img.height == 48
        val torsoW = when {
            rmxp && dir == Direction8.W -> if (loadout.torsoStyle == TorsoStyle.SKYRENDER) 10 else 9
            rmxp && dir.isDiagonal -> 11
            rmxp -> if (loadout.torsoStyle == TorsoStyle.SKYRENDER) 13 else 12
            chunky && dir == Direction8.W -> if (loadout.torsoStyle == TorsoStyle.SKYRENDER) 11 else 10
            chunky && dir.isDiagonal -> 13
            chunky -> if (loadout.torsoStyle == TorsoStyle.SKYRENDER) 15 else 14
            dir == Direction8.W -> if (loadout.torsoStyle == TorsoStyle.SKYRENDER) 8 else 7
            dir.isDiagonal -> 9
            loadout.torsoStyle == TorsoStyle.SKYRENDER -> 11
            else -> 10
        }
        val tx0 = cx - torsoW / 2
        val tx1 = tx0 + torsoW - 1
        for (y in torsoTop..legTop) {
            val vy = (y - torsoTop).toFloat() / (legTop - torsoTop).coerceAtLeast(1)
            for (x in tx0..tx1) {
                if (rmxp && dir != Direction8.W) {
                    // Rounded shoulders and a waist one pixel narrower than the chest.
                    val corner = y == torsoTop && (x == tx0 || x == tx1)
                    val waist = vy > 0.5f && (x == tx0 || x == tx1)
                    if (corner || waist) continue
                }
                val isCollar = y <= torsoTop + 1 && abs(x - cx) <= 2
                val isBelt = y == legTop - 2
                val isBuckle = isBelt && abs(x - cx) <= 1 && !dir.isBack
                val isTabard = !dir.isBack && abs(x - cx) <= 1 && y in (torsoTop + 2) until (legTop - 2)
                val isStud = loadout.torsoStyle == TorsoStyle.BRIGANDINE && (x + y) % 3 == 0 && y in (torsoTop + 2) until (legTop - 2)
                val isChestTrim = loadout.torsoStyle == TorsoStyle.SKYRENDER && (y == torsoTop + 2 || abs(x - cx) == 2) && y < legTop - 1
                val col = when {
                    isBuckle -> PalettePresets.RUNE_GOLD.highlight
                    isBelt -> boots.shadow
                    isCollar -> accent.sample(0.80f)
                    isChestTrim -> accent.sample(if (x < cx) 0.70f else 0.46f)
                    isStud -> accent.mid
                    isTabard -> accent.sample(PixelForm.band(0.42f - vy * 0.22f - (if (x == cx + 1) 0.3f else 0f)))
                    else -> outfit.sample(PixelForm.band(PixelForm.boxLight(x - tx0, y - torsoTop, torsoW, legTop - torsoTop + 1)))
                }
                img.setPixel(x.coerceIn(1, 30), y.coerceIn(1, img.height - 2), col)
            }
        }

        // Sleeves / Arms (raised on CAST or EMOTE; the weapon arm follows the swing keyframes on a SLASH)
        val armSwing = stride / 2
        val sleeveW = if (rmxp) 2 else if (chunky) 3 else 2
        val raiseLeft = pose.action == PaperDollAction.CAST && (pose.phase == 1 || pose.phase == 2)
        val raiseRight = raiseLeft || (pose.action == PaperDollAction.EMOTE && pose.phase == 1)
        val armMaxY = img.height - 5
        val skin = theme.skinPalette
        val weaponSide = Swing.side(dir)
        fun swingArm(shoulderX: Int, tone: Float) {
            val (hx, hy) = swing!!.hand(dir, cx, torsoTop)
            SwingArt.drawArm(img, if (pose.action == PaperDollAction.HARVEST) img else handsImg, shoulderX, torsoTop + 1, hx.coerceIn(1, 29), hy.coerceIn(1, armMaxY), outfit, accent, skin, tone)
            if (pose.action == PaperDollAction.HARVEST && pose.phase >= 1) {
                // The thing being picked up: on the ground in the hand while grabbing, then held at the chest with a glint.
                val ix = hx.coerceIn(1, 28)
                val iy = if (pose.phase == 3) (hy - 2).coerceAtLeast(1) else (hy + 2).coerceAtMost(img.height - 3)
                img.setPixel(ix, iy, PalettePresets.RUNE_GOLD.sample(0.9f))
                img.setPixel(ix + 1, iy, PalettePresets.RUNE_GOLD.sample(0.62f))
                img.setPixel(ix, iy + 1, PalettePresets.RUNE_GOLD.sample(0.4f))
                if (pose.phase != 1) img.setPixel(ix, (iy - 1).coerceAtLeast(1), PaletteRamp.rgba(255, 250, 220))
            }
        }
        if (dir != Direction8.W) {
            val ly = if (raiseLeft) torsoTop - 2 else torsoTop + 1 - armSwing
            val ry = if (raiseRight) torsoTop - 3 else torsoTop + 1 + armSwing
            if (rmxp) {
                // Sleeve in the outfit colour, an accent cuff, then a skin hand.
                fun arm(sx: Int, sy: Int, tone: Float, hand: Int) {
                    val top = sy.coerceIn(1, armMaxY - 2)
                    fillRectShaded(img, sx.coerceIn(1, 29), top, sleeveW, 4, outfit, tone)
                    fillRectShaded(img, sx.coerceIn(1, 29), top + 4, sleeveW, 1, accent, tone)
                    // Hands go on the body layer so tinted torso gear does not tint them.
                    fillRectShaded(handsImg, sx.coerceIn(1, 29), (top + 5).coerceAtMost(armMaxY), sleeveW, 2, skin, 0.62f)
                    handsImg.setPixel(sx.coerceIn(1, 29), (top + 6).coerceAtMost(armMaxY), hand)
                }
                if (swing != null && weaponSide > 0) {
                    arm(tx0 - sleeveW, ly, 0.76f, skin.mid)
                    swingArm(tx1, 0.52f)
                } else if (swing != null) {
                    swingArm(tx0 + 1, 0.76f)
                    arm(tx1 + 1, ry, 0.52f, skin.shadow)
                } else {
                    arm(tx0 - sleeveW, ly, 0.76f, skin.mid)
                    arm(tx1 + 1, ry, 0.52f, skin.shadow)
                }
            } else {
                fillRectShaded(img, (tx0 - sleeveW).coerceIn(1, 29), ly.coerceIn(1, armMaxY), sleeveW, 5, accent, 0.78f)
                fillRectShaded(img, (tx1 + 1).coerceIn(1, 29), ry.coerceIn(1, armMaxY), sleeveW, 5, accent, 0.52f)
            }
        } else if (swing != null && rmxp) {
            swingArm(cx - 1, 0.7f)
        } else {
            val ay = if (raiseRight) torsoTop - 2 else torsoTop + 2
            fillRectShaded(img, (cx - 1 + armSwing).coerceIn(1, 28), ay.coerceIn(1, armMaxY), sleeveW + 1, 5, accent, 0.76f)
        }
    }

    private fun drawAnimeEye(img: TileImage, ex: Int, ey: Int, iris: Int, catchlight: Int, closed: Boolean) {
        if (closed) {
            img.setPixel(ex, ey, PaletteRamp.rgba(72, 46, 48))
            return
        }
        img.setPixel(ex, ey - 1, catchlight)
        img.setPixel(ex, ey, iris)
    }

    private fun drawHair32(
        backImg: TileImage,
        frontImg: TileImage,
        cx: Int,
        headCy: Int,
        dir: Direction8,
        stride: Int,
        style: HairStyle,
        pal: PaletteRamp,
        chunky: Boolean = false,
        rmxp: Boolean = false
    ) {
        val topY = (headCy - 6).coerceAtLeast(1)
        val hwPad = if (rmxp) 1 else if (chunky) 2 else 0
        val rx = if (rmxp) 6.5f else if (chunky) 7.4f else 5.8f
        val ry = if (rmxp) 5.9f else if (chunky) 5.8f else 5.2f
        // Crown & bangs on front hair layer: one shaded dome, with the face left open below the fringe.
        PixelForm.dome(
            frontImg, cx.toFloat(), headCy - 1.2f, rx + 0.3f, ry + 0.2f, pal,
            clip = { x, y ->
                x in 1..30 && y in 1..30 && (dir.isBack || y <= headCy - 2 ||
                    (abs(x - cx) >= 5 + hwPad && y <= headCy + 1) || (style == HairStyle.NOBLE_PARTED && y == headCy - 1 && abs(x - cx) in 2..3))
            }
        )
        if (!dir.isBack) {
            // Fringe: a few darker strands hanging into the forehead break the straight hairline.
            val strand = pal.sample(0.30f)
            for (sx in listOf(-2, 0, 2)) {
                if (style == HairStyle.NOBLE_PARTED && sx == 0) continue
                frontImg.setPixel((cx + sx).coerceIn(1, 30), headCy - 1, pal.sample(0.46f))
            }
            frontImg.setPixel((cx - 1).coerceIn(1, 30), headCy - 1, strand)
        }

        // Style-specific silhouettes (spikes, long locks, twin tails)
        when (style) {
            HairStyle.SPIKY_HERO -> {
                for (sx in listOf(-4, -2, 0, 2, 4)) {
                    val px = (cx + sx).coerceIn(2, 29)
                    val py = (topY - 1 + abs(sx) / 3).coerceIn(1, 29)
                    frontImg.setPixel(px, py, pal.highlight)
                }
            }
            HairStyle.LONG_FLOWING -> {
                val flowTarget = if (dir.isBack) frontImg else backImg
                for (y in headCy..(headCy + 9).coerceAtMost(28)) {
                    for (x in (cx - 5)..(cx + 5)) {
                        val t = (0.65f - (y - headCy) * 0.04f).coerceIn(0.15f, 0.85f)
                        flowTarget.setPixel(x.coerceIn(2, 29), y, pal.sample(t))
                    }
                }
            }
            HairStyle.TWIN_TAILS -> {
                val tailTarget = if (dir.isBack) frontImg else backImg
                val sway = stride / 2
                for (y in (headCy - 1)..(headCy + 7).coerceAtMost(28)) {
                    tailTarget.setPixel((cx - 6 - sway).coerceIn(2, 29), y, pal.mid)
                    tailTarget.setPixel((cx - 5 - sway).coerceIn(2, 29), y, pal.highlight)
                    if (dir != Direction8.W) {
                        tailTarget.setPixel((cx + 5 + sway).coerceIn(2, 29), y, pal.mid)
                        tailTarget.setPixel((cx + 6 + sway).coerceIn(2, 29), y, pal.shadow)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun drawHeadgear32(
        img: TileImage,
        cx: Int,
        headCy: Int,
        dir: Direction8,
        kind: HeadgearKind,
        accent: PaletteRamp,
        primary: PaletteRamp,
        chunky: Boolean = false
    ) {
        val hwPad = if (chunky) 2 else 0
        when (kind) {
            HeadgearKind.NONE -> Unit
            HeadgearKind.BANDANA -> {
                // A cloth band across the brow with a knot and two trailing tails.
                val by = headCy - 3
                val w = 11 + hwPad * 2
                PixelForm.column(img, (cx - w / 2).coerceIn(1, 30), by, w, 2, primary, 0.62f, round = false)
                val kx = cx + 5 + hwPad
                img.setPixel(kx.coerceIn(2, 29), by + 1, primary.sample(0.7f))
                img.setPixel((kx + 1).coerceIn(2, 29), by + 2, primary.sample(0.5f))
                img.setPixel((kx).coerceIn(2, 29), by + 3, primary.sample(0.36f))
                img.setPixel((kx + 1).coerceIn(2, 29), by + 4, primary.sample(0.26f))
            }
            HeadgearKind.VALKYRIE_HELM -> {
                val metal = STEEL_ARMOR
                // A round steel cap with a gold brow band, then a feathered wing swept up from each side.
                PixelForm.dome(img, cx.toFloat(), headCy - 2.2f, 5.6f + hwPad, 4.2f, metal) { _, y -> y <= headCy - 2 }
                PixelForm.column(img, (cx - 5 - hwPad).coerceIn(1, 30), headCy - 3, 11 + hwPad * 2, 1, accent, 0.62f, round = false)
                val wingRows = listOf(0 to 2, 1 to 3, 2 to 3, 3 to 2, 4 to 1)
                for ((i, len) in wingRows) {
                    val wy = (headCy - 3 - i).coerceAtLeast(1)
                    for (k in 0 until len) {
                        val tone = PixelForm.LIT - k * 0.16f + (if (i == 0) 0f else 0.06f)
                        img.setPixel((cx - 6 - hwPad - k - i / 2).coerceIn(1, 30), wy, metal.sample(tone))
                        if (dir != Direction8.W) img.setPixel((cx + 6 + hwPad + k + i / 2).coerceIn(1, 30), wy, metal.sample(tone - 0.2f))
                    }
                }
            }
            HeadgearKind.WIZARD_HAT -> {
                val brimY = headCy - 3
                val brimHalf = 7 + hwPad
                for (x in (cx - brimHalf)..(cx + brimHalf)) {
                    val edge = abs(x - cx) >= brimHalf - 1
                    img.setPixel(x.coerceIn(1, 30), brimY, primary.sample(if (x < cx) 0.66f else 0.40f))
                    img.setPixel(x.coerceIn(1, 30), brimY - 1, if (edge) primary.sample(0.5f) else accent.sample(if (x < cx) 0.84f else 0.62f))
                }
                // Cone: shaded by column, leaning right at the tip.
                for (i in 1..6) {
                    val y = (brimY - 1 - i).coerceAtLeast(1)
                    val hw = (5.4f - i * 0.8f).toInt().coerceAtLeast(1)
                    val bend = (i * i / 8)
                    for (x in (cx - hw + bend)..(cx + hw + bend)) {
                        val u = (x - (cx - hw + bend)).toFloat() / (2 * hw).coerceAtLeast(1)
                        val tone = if (u < 0.3f) 0.74f else if (u > 0.7f) 0.34f else 0.54f
                        img.setPixel(x.coerceIn(1, 30), y, primary.sample(tone))
                    }
                }
                img.setPixel((cx + 1).coerceIn(1, 30), (brimY - 3).coerceAtLeast(1), accent.highlight)
            }
            HeadgearKind.PRIEST_MITRE -> {
                for (y in (headCy - 8).coerceAtLeast(1)..(headCy - 3)) {
                    val hw = when (y - (headCy - 8)) { 0 -> 1; 1 -> 2; 2 -> 3; else -> 4 }
                    for (x in (cx - hw)..(cx + hw)) {
                        val u = (x - cx + hw).toFloat() / (2 * hw).coerceAtLeast(1)
                        img.setPixel(x.coerceIn(1, 30), y, primary.sample(if (u < 0.3f) 0.92f else if (u > 0.7f) 0.62f else 0.78f))
                    }
                }
                for (y in (headCy - 7).coerceAtLeast(1)..(headCy - 4)) img.setPixel(cx, y, accent.sample(0.8f))
                for (x in (cx - 2)..(cx + 2)) img.setPixel(x, (headCy - 6).coerceAtLeast(1), accent.sample(0.8f))
                for (x in (cx - 4)..(cx + 4)) img.setPixel(x, headCy - 3, accent.sample(if (x < cx) 0.9f else 0.6f))
            }
            HeadgearKind.SLIME_BERET -> {
                val blob = PalettePresets.PASTEL_PINK
                PixelForm.dome(img, cx + 0.5f, headCy - 3.4f, 5.6f, 3.6f, blob) { _, y -> y <= headCy - 2 }
                img.setPixel((cx + 1).coerceIn(1, 30), (headCy - 7).coerceAtLeast(1), blob.highlight)
            }
            HeadgearKind.FEATHER_CAP -> {
                PixelForm.dome(img, cx.toFloat(), headCy - 2.4f, 5.4f + hwPad, 3.8f, primary) { _, y -> y <= headCy - 2 }
                PixelForm.column(img, (cx - 5 - hwPad).coerceIn(1, 30), headCy - 3, 11 + hwPad * 2, 1, primary, 0.3f, round = false)
                val cream = PalettePresets.CREAM_MARBLE
                for (i in 0..5) {
                    val fx = (cx + 3 + i / 2 + (if (i > 3) 1 else 0)).coerceIn(2, 29)
                    val fy = (headCy - 5 - i).coerceAtLeast(1)
                    img.setPixel(fx, fy, cream.sample(0.94f - i * 0.04f))
                    if (i in 1..4) img.setPixel((fx + 1).coerceIn(2, 29), fy, cream.sample(0.5f))
                }
            }
            HeadgearKind.GOGGLES -> {
                val gy = headCy - 3
                for (x in (cx - 4)..(cx + 4)) img.setPixel(x, gy, PalettePresets.WARM_TIMBER.shadow)
                img.setPixel(cx - 2, gy, PaletteRamp.rgba(120, 224, 255))
                img.setPixel(cx + 2, gy, PaletteRamp.rgba(120, 224, 255))
            }
            HeadgearKind.SHRINE_RIBBON -> {
                val ry = headCy - 4
                for (dx in -4..4) {
                    if (abs(dx) >= 2) img.setPixel(cx + dx, ry, primary.highlight)
                }
                img.setPixel(cx, ry, accent.highlight)
            }
            HeadgearKind.IRON_HELMET, HeadgearKind.STEEL_HELMET, HeadgearKind.SKYRENDER_HELM -> {
                val helmPal = when (kind) {
                    HeadgearKind.SKYRENDER_HELM -> accent
                    HeadgearKind.STEEL_HELMET -> STEEL_ARMOR
                    else -> PalettePresets.DARK_IRON
                }
                // Rounded skull cap that comes down to the brow, with a rim line, then cheek plates.
                val rx = 5.6f + hwPad
                PixelForm.dome(img, cx.toFloat(), headCy - 1.4f, rx, 5.0f, helmPal) { _, y -> y <= headCy - 2 }
                val rimW = 11 + hwPad * 2
                PixelForm.column(img, (cx - rimW / 2).coerceIn(1, 30), headCy - 2, rimW, 1, helmPal, 0.34f, round = false)
                if (!dir.isBack) {
                    val half = 5 + hwPad
                    for (y in (headCy - 1)..(headCy + 1)) {
                        img.setPixel((cx - half).coerceIn(1, 30), y, helmPal.sample(if (y == headCy + 1) 0.4f else 0.66f))
                        img.setPixel((cx + half).coerceIn(1, 30), y, helmPal.sample(if (y == headCy + 1) 0.22f else 0.34f))
                    }
                    // Nasal bar down the brow.
                    for (y in (headCy - 2)..headCy) img.setPixel(cx.coerceIn(1, 30), y, helmPal.sample(0.78f))
                } else {
                    for (y in (headCy - 1)..(headCy + 2)) {
                        img.setPixel((cx - 5 - hwPad).coerceIn(1, 30), y, helmPal.sample(0.5f))
                        img.setPixel((cx + 5 + hwPad).coerceIn(1, 30), y, helmPal.sample(0.3f))
                    }
                    for (x in (cx - 4)..(cx + 4)) img.setPixel(x.coerceIn(1, 30), headCy + 2, helmPal.sample(0.3f))
                }
                if (kind == HeadgearKind.SKYRENDER_HELM) {
                    // Plume crest rolling back from the crown.
                    for (i in 1..4) {
                        img.setPixel(cx.coerceIn(1, 30), (headCy - 6 - i).coerceAtLeast(1), primary.sample(0.9f - i * 0.1f))
                        if (i in 2..3) img.setPixel((cx + 1).coerceIn(1, 30), (headCy - 6 - i).coerceAtLeast(1), primary.sample(0.4f))
                    }
                } else if (kind == HeadgearKind.IRON_HELMET) {
                    // A short crest ridge so the iron cap is not just a half ball.
                    for (x in (cx - 1)..(cx + 1)) img.setPixel(x.coerceIn(1, 30), (headCy - 6).coerceAtLeast(1), helmPal.sample(0.46f))
                }
            }
        }
    }

    private fun drawWeapon32(
        img: TileImage,
        cx: Int,
        torsoTop: Int,
        dir: Direction8,
        action: PaperDollAction,
        phase: Int,
        weapon: WeaponKind,
        accent: PaletteRamp,
        chunky: Boolean = false,
        swing: Swing = Swing.REST,
        trail: TileImage? = null
    ) {
        val steel = PalettePresets.CREAM_MARBLE
        val wood = PalettePresets.WARM_TIMBER
        val gold = PalettePresets.RUNE_GOLD

        // A bow plays the same arm path as a swing: raised, drawn at the chest, released, lowered.
        if (weapon == WeaponKind.COMPOSITE_BOW && action == PaperDollAction.SLASH) {
            val (hx, hy) = swing.hand(dir, cx, torsoTop)
            SwingArt.drawBow(
                img, dir, phase, hx.coerceIn(2, 29), hy.coerceIn(8, img.height - 9), cx, torsoTop,
                wood, steel, gold, if (img.height == 48) 7 else 5
            )
            return
        }

        // Swords and katanas follow the swing keyframes: a long blade from the (moving) hand, with a trailing arc.
        if (weapon == WeaponKind.KNIGHT_SWORD || weapon == WeaponKind.SINGLE_BLADE || weapon == WeaponKind.KATANA) {
            val (hx, hy) = swing.hand(dir, cx, torsoTop)
            val len = if (img.height == 48) (if (weapon == WeaponKind.KATANA) 15f else 13.5f) else 10f
            if (img.height == 48 || action != PaperDollAction.THRUST) {
                SwingArt.drawBlade(
                    img, trail, dir, swing, hx.coerceIn(2, 29), hy.coerceIn(2, img.height - 3), len, steel, gold, wood,
                    broad = weapon == WeaponKind.SINGLE_BLADE, curved = weapon != WeaponKind.KNIGHT_SWORD
                )
                return
            }
        }

        val dirSign = if (dir == Direction8.W || dir == Direction8.SW) -1 else 1
        val handX = cx + dirSign * (if (chunky) 8 else 5)
        val isSlash = action == PaperDollAction.SLASH && phase == 1
        val isThrust = action == PaperDollAction.THRUST && phase == 1
        val isShoot = action == PaperDollAction.SHOOT && (phase == 1 || phase == 2)
        val isCast = action == PaperDollAction.CAST && (phase == 1 || phase == 2)
        val isHarvest = action == PaperDollAction.HARVEST
        val handY = torsoTop + 4 + when {
            isSlash -> -2
            isCast -> -3
            isHarvest && phase == 0 -> -5
            isHarvest && phase == 1 -> 3
            action == PaperDollAction.EMOTE && phase == 1 -> -3
            else -> 0
        }

        if (isThrust && weapon != WeaponKind.NONE) {
            // Horizontal lunge thrust pose
            val y = (torsoTop + 4).coerceIn(2, 29)
            for (i in 0..9) {
                val tx = (handX + dirSign * i).coerceIn(1, 30)
                val col = if (i >= 7) steel.highlight else if (i <= 2) gold.highlight else wood.mid
                img.setPixel(tx, y, col)
                if (i >= 7 && y + 1 <= 29) img.setPixel(tx, y + 1, steel.mid)
            }
            return
        }

        when (weapon) {
            WeaponKind.NONE -> Unit
            WeaponKind.DUAL_TONFA -> {
                for (i in -2..4) {
                    img.setPixel((cx - 6).coerceIn(1, 30), (handY + i).coerceIn(1, 30), wood.highlight)
                    img.setPixel((cx + 6).coerceIn(1, 30), (handY + i).coerceIn(1, 30), wood.mid)
                }
            }
            WeaponKind.KNIGHT_SWORD -> {
                val tipX = handX + dirSign * (if (isSlash) 5 else 2)
                val tipY = if (isSlash) handY - 3 else handY - 10
                PixelForm.blade(img, handX, handY - 3, tipX.coerceIn(1, 30), tipY.coerceIn(1, 30), steel)
                swordHilt(img, handX, handY, gold, wood)
            }
            WeaponKind.ARCANE_STAFF, WeaponKind.HOLY_ROD -> {
                val lift = if (isCast) -2 else 0
                val top = (torsoTop - 3 + lift).coerceAtLeast(3)
                val shaft = if (weapon == WeaponKind.HOLY_ROD) gold else wood
                PixelForm.column(img, handX.coerceIn(1, 30), top, 1, ((torsoTop + 11 + lift).coerceAtMost(29) - top), shaft, 0.52f, round = false)
                img.setPixel((handX + 1).coerceIn(1, 30), top + 2, shaft.sample(0.3f))
                val gemPal = if (weapon == WeaponKind.ARCANE_STAFF) PalettePresets.CALM_WATER else gold
                // A claw of two prongs around a shaded orb.
                img.setPixel((handX - 1).coerceIn(1, 30), top, shaft.sample(0.7f))
                img.setPixel((handX + 1).coerceIn(1, 30), top, shaft.sample(0.4f))
                PixelForm.dome(img, handX + 0.5f, top - 1.6f, 2.2f, 2.2f, gemPal)
            }
            WeaponKind.DUAL_KATAR -> {
                for (i in 0..5) {
                    img.setPixel((cx - 6 - i / 2).coerceIn(1, 30), (handY + i / 2).coerceIn(1, 30), steel.highlight)
                    img.setPixel((cx + 6 + i / 2).coerceIn(1, 30), (handY + i / 2).coerceIn(1, 30), steel.mid)
                }
            }
            WeaponKind.COMPOSITE_BOW -> {
                val bowX = if (isShoot) handX + dirSign * 2 else handX
                for (dy in -5..5) {
                    val curve = dirSign * ((5 - abs(dy)) / 2)
                    val bx = (bowX + curve).coerceIn(1, 30)
                    val by = (handY + dy).coerceIn(1, 30)
                    img.setPixel(bx, by, wood.highlight)
                }
                if (isShoot) {
                    // Draw bowstring & nocked arrow
                    for (dy in -4..4) {
                        img.setPixel((bowX - dirSign).coerceIn(1, 30), (handY + dy).coerceIn(1, 30), steel.highlight)
                    }
                    for (i in -2..4) {
                        img.setPixel((bowX + dirSign * i).coerceIn(1, 30), handY.coerceIn(1, 30), gold.highlight)
                    }
                }
            }
            WeaponKind.HEAVY_AXE -> {
                val top = (torsoTop - 2).coerceAtLeast(2)
                PixelForm.column(img, handX.coerceIn(1, 30), top, 1, (torsoTop + 10).coerceAtMost(29) - top, wood, 0.5f, round = false)
                val headY = if (isSlash) torsoTop + 2 else top
                // A crescent bit: wide at the cutting edge, tapering back to the haft, with a bright edge line.
                val bx = handX.coerceIn(2, 28)
                for (row in 0 until 5) {
                    val reach = when (row) { 0, 4 -> 2; 1, 3 -> 3; else -> 3 }
                    for (k in 1..reach) {
                        val px = (bx + dirSign * k).coerceIn(1, 30)
                        val edge = k == reach
                        img.setPixel(px, (headY + row).coerceIn(1, 30), steel.sample(if (edge) 0.96f else if (row <= 1) 0.7f else 0.46f))
                    }
                }
                img.setPixel((bx - dirSign).coerceIn(1, 30), headY + 2, steel.sample(0.34f))
            }
            WeaponKind.HOLY_SPEAR -> {
                val top = (torsoTop - 5).coerceAtLeast(2)
                PixelForm.column(img, handX.coerceIn(1, 30), top + 3, 1, (torsoTop + 11).coerceAtMost(29) - top - 3, accent, 0.5f, round = false)
                // Leaf-shaped head.
                img.setPixel(handX.coerceIn(1, 30), top - 1, steel.highlight)
                img.setPixel(handX.coerceIn(1, 30), top, steel.sample(0.82f))
                img.setPixel(handX.coerceIn(1, 30), top + 1, steel.sample(0.82f))
                img.setPixel((handX - 1).coerceIn(1, 30), top + 1, steel.sample(0.72f))
                img.setPixel((handX + 1).coerceIn(1, 30), top + 1, steel.sample(0.4f))
                img.setPixel((handX - 1).coerceIn(1, 30), top + 2, steel.sample(0.6f))
                img.setPixel((handX + 1).coerceIn(1, 30), top + 2, steel.sample(0.32f))
                img.setPixel(handX.coerceIn(1, 30), top + 2, gold.sample(0.8f))
            }
            WeaponKind.SINGLE_BLADE -> {
                // Heavy single-edged blade: a wider, darker back edge along a bright cutting edge.
                val tipX = handX + dirSign * (if (isSlash) 5 else 2)
                val tipY = if (isSlash) handY - 2 else handY - 9
                PixelForm.blade(img, handX, handY - 3, tipX.coerceIn(1, 30), tipY.coerceIn(1, 30), steel)
                PixelForm.line(img, (handX + dirSign).coerceIn(1, 30), handY - 3, (tipX + dirSign).coerceIn(1, 30), tipY + 3) { steel.sample(0.28f) }
                swordHilt(img, handX, handY, gold, wood)
            }
            WeaponKind.KATANA -> {
                // Slender, slightly curved blade with a round guard.
                val tipX = handX + dirSign * (if (isSlash) 6 else 2)
                val tipY = if (isSlash) handY - 3 else handY - 10
                PixelForm.blade(img, handX, handY - 3, tipX.coerceIn(1, 30), tipY.coerceIn(1, 30), steel)
                img.setPixel((handX - 1).coerceIn(1, 30), handY - 2, gold.sample(0.82f))
                img.setPixel((handX + 1).coerceIn(1, 30), handY - 2, gold.sample(0.5f))
                img.setPixel(handX.coerceIn(1, 30), handY - 1, PaletteRamp.rgba(52, 40, 62))
                img.setPixel(handX.coerceIn(1, 30), handY, PaletteRamp.rgba(40, 30, 50))
            }
            WeaponKind.PICKAXE -> {
                // Miner's pickaxe with downward HARVEST swing
                if (isHarvest && phase == 1) {
                    for (i in 0..6) {
                        img.setPixel((handX + dirSign * i).coerceIn(1, 30), (handY + 1).coerceIn(1, 30), wood.mid)
                    }
                    val tipX = (handX + dirSign * 6).coerceIn(1, 30)
                    for (dy in -2..2) {
                        img.setPixel(tipX, (handY + 1 + dy).coerceIn(1, 30), steel.highlight)
                    }
                } else {
                    for (y in (handY - 6).coerceAtLeast(2)..(handY + 3).coerceAtMost(29)) {
                        img.setPixel(handX.coerceIn(1, 30), y, wood.mid)
                    }
                    for (dx in -2..2) {
                        img.setPixel((handX + dx).coerceIn(1, 30), (handY - 6).coerceAtLeast(2), steel.highlight)
                    }
                }
            }
        }
    }

    /** Grip, crossguard and pommel under a blade whose base is at ([x], [y] - 3). */
    private fun swordHilt(img: TileImage, x: Int, y: Int, gold: PaletteRamp, wood: PaletteRamp) {
        img.setPixel(x.coerceIn(1, 30), (y - 2).coerceIn(1, 30), gold.sample(0.86f))
        img.setPixel((x - 1).coerceIn(1, 30), (y - 2).coerceIn(1, 30), gold.sample(0.7f))
        img.setPixel((x + 1).coerceIn(1, 30), (y - 2).coerceIn(1, 30), gold.sample(0.42f))
        img.setPixel(x.coerceIn(1, 30), (y - 1).coerceIn(1, 30), wood.sample(0.55f))
        img.setPixel(x.coerceIn(1, 30), y.coerceIn(1, 30), wood.sample(0.32f))
        img.setPixel(x.coerceIn(1, 30), (y + 1).coerceIn(1, 30), gold.sample(0.7f))
    }

    private fun drawShield32(
        img: TileImage,
        cx: Int,
        torsoTop: Int,
        dir: Direction8,
        shield: ShieldKind,
        accent: PaletteRamp,
        primary: PaletteRamp,
        chunky: Boolean = false
    ) {
        val off = if (chunky) 9 else 6
        val sx = if (dir == Direction8.W || dir == Direction8.SW) cx + (if (chunky) 5 else 3) else cx - off
        val sy = torsoTop + 2
        when (shield) {
            ShieldKind.NONE -> Unit
            ShieldKind.ROUND_BUCKLER -> {
                PixelForm.dome(img, sx + 2.5f, sy + 3f, 3.4f, 3.8f, accent)
                img.setPixel((sx + 2).coerceIn(1, 30), sy + 3, accent.highlight)
            }
            ShieldKind.CRUSADER_CROSS_SHIELD -> {
                val x0 = sx.coerceIn(1, 25)
                heater(img, x0, sy, 5, 7, primary, accent, cross = true)
            }
            ShieldKind.SPELLBOOK -> {
                val x0 = sx.coerceIn(1, 26)
                PixelForm.column(img, x0, sy + 1, 4, 5, accent, 0.62f, round = false)
                // Pages along the right edge, a clasp and a rune on the cover.
                for (y in (sy + 2)..(sy + 4)) img.setPixel((x0 + 4).coerceIn(1, 30), y, PalettePresets.CREAM_MARBLE.sample(0.9f))
                img.setPixel(x0 + 1, sy + 3, PalettePresets.RUNE_GOLD.highlight)
                img.setPixel(x0 + 2, sy + 3, PalettePresets.RUNE_GOLD.sample(0.6f))
            }
            ShieldKind.TOWER_SHIELD, ShieldKind.DAWNBRAND_SHIELD -> {
                val x0 = sx.coerceIn(1, 24)
                val pal = if (shield == ShieldKind.DAWNBRAND_SHIELD) PalettePresets.RUNE_GOLD else accent
                heater(img, x0, sy, 5, 8, primary, pal, cross = false)
            }
            ShieldKind.OFFHAND_BLADE, ShieldKind.OFFHAND_SWORD, ShieldKind.OFFHAND_KATANA -> {
                val steel = PalettePresets.CREAM_MARBLE
                val gold = PalettePresets.RUNE_GOLD
                val hx = sx.coerceIn(2, 29)
                val hy = (torsoTop + 5).coerceIn(4, 28)
                val len = if (shield == ShieldKind.OFFHAND_KATANA) 8 else 7
                for (i in 0..len) {
                    val bx = (hx - i / 3).coerceIn(1, 30)
                    val by = (hy - i).coerceIn(1, 30)
                    img.setPixel(bx, by, if (i <= 1) gold.highlight else steel.highlight)
                    if (shield == ShieldKind.OFFHAND_BLADE && bx - 1 >= 1 && i in 2..6) {
                        img.setPixel(bx - 1, by, steel.shadow)
                    }
                }
            }
        }
    }

    /**
     * A heater-shaped shield: a [rim] ring around a shaded [field], tapering to a point at the bottom; [cross] adds a
     * cross in the rim colour, otherwise a boss at the centre.
     */
    private fun heater(img: TileImage, x0: Int, y0: Int, w: Int, h: Int, field: PaletteRamp, rim: PaletteRamp, cross: Boolean) {
        for (j in 0 until h) {
            val taper = if (j >= h - 2) 1 else 0
            val taper2 = if (j == h - 1) 1 else 0
            for (i in taper + taper2..(w - 1 - taper - taper2)) {
                val x = x0 + i
                val y = y0 + j
                if (x !in 1..30 || y !in 1..30) continue
                val onRim = i == taper + taper2 || i == w - 1 - taper - taper2 || j == 0 || j == h - 1
                val lit = i < w / 2
                val col = when {
                    onRim -> rim.sample(if (lit) 0.74f else 0.42f)
                    cross && (i == w / 2 || j == h / 3) -> rim.sample(if (lit) 0.82f else 0.58f)
                    !cross && i == w / 2 && j == h / 2 -> rim.sample(0.9f)
                    else -> field.sample(if (lit) 0.58f else 0.34f)
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawBackAttachment32(
        img: TileImage,
        cx: Int,
        torsoTop: Int,
        dir: Direction8,
        stride: Int,
        att: AttachmentKind,
        theme: PaperDollTheme
    ) {
        val pal = theme.accentPalette
        val wood = theme.secondaryPalette
        when (att) {
            AttachmentKind.QUIVER -> {
                val qx = if (dir == Direction8.W) cx + 3 else cx + 3
                fillRectShaded(img, qx.coerceIn(1, 28), (torsoTop + 1).coerceIn(1, 25), 3, 6, wood, 0.65f)
                img.setPixel((qx + 1).coerceIn(1, 30), torsoTop.coerceAtLeast(1), PalettePresets.CREAM_MARBLE.highlight)
            }
            AttachmentKind.ANGEL_WINGS, AttachmentKind.DEMON_WINGS, AttachmentKind.FAIRY_WINGS -> {
                val wingPal = when (att) {
                    AttachmentKind.ANGEL_WINGS -> PalettePresets.CREAM_MARBLE
                    AttachmentKind.DEMON_WINGS -> PalettePresets.ABYSSAL_OBSIDIAN
                    else -> theme.auraPalette
                }
                val flap = stride / 2
                val spread = if (att == AttachmentKind.FAIRY_WINGS) 6 else 8
                for (dy in 0..6) {
                    val w = spread - dy / 2
                    for (dx in 3..w) {
                        val y = (torsoTop + dy + flap).coerceIn(1, 29)
                        val tone = if (dy < 2) 0.88f else 0.58f
                        if (dir != Direction8.W) {
                            img.setPixel((cx - dx).coerceIn(1, 30), y, wingPal.sample(tone))
                            img.setPixel((cx + dx).coerceIn(1, 30), y, wingPal.sample(tone * 0.85f))
                        } else {
                            img.setPixel((cx + dx).coerceIn(1, 30), y, wingPal.sample(tone))
                        }
                    }
                }
            }
            AttachmentKind.BACKPACK -> {
                val bx = if (dir == Direction8.W) cx + 2 else cx - 3
                val bw = if (dir == Direction8.W) 4 else 6
                fillRectShaded(img, bx.coerceIn(1, 25), (torsoTop + 1).coerceIn(1, 24), bw, 6, wood, 0.7f)
                img.setPixel((bx + bw / 2).coerceIn(1, 30), (torsoTop + 2).coerceIn(1, 29), pal.highlight)
            }
            AttachmentKind.WAR_BANNER -> {
                val px = (cx + 4).coerceIn(1, 29)
                for (y in (torsoTop - 8).coerceAtLeast(1)..(torsoTop + 7).coerceAtMost(29)) {
                    img.setPixel(px, y, wood.mid)
                }
                fillRectShaded(img, (px + 1).coerceIn(1, 27), (torsoTop - 7).coerceAtLeast(1), 4, 5, pal, 0.82f)
            }
            AttachmentKind.SATCHEL -> {
                val sx = if (dir == Direction8.W) cx + 2 else cx - 5
                fillRectShaded(img, sx.coerceIn(1, 27), (torsoTop + 4).coerceIn(1, 25), 3, 4, wood, 0.72f)
                img.setPixel((sx + 1).coerceIn(1, 30), (torsoTop + 5).coerceIn(1, 29), pal.highlight)
            }
            AttachmentKind.SILK_GARMENT -> {
                val flutter = stride / 2
                for (y in (torsoTop + 1)..(torsoTop + 11).coerceAtMost(28)) {
                    val frac = (y - torsoTop).toFloat() / 11f
                    val hw = 5 + (frac * 3).toInt()
                    for (x in (cx - hw + flutter)..(cx + hw + flutter)) {
                        if (x in 1..30) img.setPixel(x, y, pal.sample((0.82f - frac * 0.25f).coerceIn(0.3f, 0.95f)))
                    }
                }
            }
            else -> Unit
        }
    }

    private fun drawFaceAccessory32(
        img: TileImage,
        cx: Int,
        headCy: Int,
        dir: Direction8,
        att: AttachmentKind,
        theme: PaperDollTheme
    ) {
        if (dir.isBack) return
        val eyeY = headCy + 1
        when (att) {
            AttachmentKind.EYE_PATCH -> {
                val px = if (dir == Direction8.W) cx - 3 else cx - 2
                img.setPixel(px.coerceIn(1, 30), eyeY, PaletteRamp.rgba(28, 24, 34))
                img.setPixel(px.coerceIn(1, 30), (eyeY - 1).coerceIn(1, 30), PaletteRamp.rgba(28, 24, 34))
            }
            AttachmentKind.GLASSES -> {
                val frame = theme.accentPalette.highlight
                val lens = PaletteRamp.rgba(164, 224, 255)
                if (dir == Direction8.W) {
                    img.setPixel((cx - 3).coerceIn(1, 30), eyeY, lens)
                    img.setPixel((cx - 2).coerceIn(1, 30), eyeY, frame)
                } else {
                    for (dx in -3..3) {
                        val c = if (abs(dx) == 2) lens else frame
                        img.setPixel((cx + dx).coerceIn(1, 30), eyeY, c)
                    }
                }
            }
            AttachmentKind.CYBER_VISOR -> {
                val glow = theme.auraPalette.highlight
                val startX = if (dir == Direction8.W) cx - 4 else cx - 3
                val endX = if (dir == Direction8.W) cx - 1 else cx + 3
                for (x in startX..endX) {
                    img.setPixel(x.coerceIn(1, 30), eyeY, glow)
                }
            }
            AttachmentKind.HALF_MASK -> {
                val maskPal = theme.secondaryPalette
                for (y in (headCy + 2)..(headCy + 4)) {
                    for (x in (cx - 3)..(cx + 3)) {
                        img.setPixel(x.coerceIn(1, 30), y.coerceIn(1, 30), maskPal.shadow)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun drawHeadAttachment32(
        img: TileImage,
        cx: Int,
        headCy: Int,
        dir: Direction8,
        att: AttachmentKind,
        theme: PaperDollTheme
    ) {
        val pal = theme.accentPalette
        when (att) {
            AttachmentKind.HORNS -> {
                val col = pal.shadow
                val hi = pal.highlight
                img.setPixel((cx - 3).coerceIn(1, 30), (headCy - 5).coerceAtLeast(1), col)
                img.setPixel((cx - 4).coerceIn(1, 30), (headCy - 6).coerceAtLeast(1), hi)
                if (dir != Direction8.W) {
                    img.setPixel((cx + 3).coerceIn(1, 30), (headCy - 5).coerceAtLeast(1), col)
                    img.setPixel((cx + 4).coerceIn(1, 30), (headCy - 6).coerceAtLeast(1), hi)
                }
            }
            AttachmentKind.CAT_EARS -> {
                val ear = theme.hairPalette.mid
                val inner = PalettePresets.PASTEL_PINK.highlight
                img.setPixel((cx - 3).coerceIn(1, 30), (headCy - 5).coerceAtLeast(1), ear)
                img.setPixel((cx - 3).coerceIn(1, 30), (headCy - 6).coerceAtLeast(1), ear)
                img.setPixel((cx - 2).coerceIn(1, 30), (headCy - 5).coerceAtLeast(1), inner)
                if (dir != Direction8.W) {
                    img.setPixel((cx + 3).coerceIn(1, 30), (headCy - 5).coerceAtLeast(1), ear)
                    img.setPixel((cx + 3).coerceIn(1, 30), (headCy - 6).coerceAtLeast(1), ear)
                    img.setPixel((cx + 2).coerceIn(1, 30), (headCy - 5).coerceAtLeast(1), inner)
                }
            }
            AttachmentKind.HALO -> {
                val gold = PalettePresets.RUNE_GOLD.highlight
                val hy = (headCy - 7).coerceAtLeast(1)
                for (dx in -3..3) {
                    if (abs(dx) >= 1) img.setPixel((cx + dx).coerceIn(1, 30), hy, gold)
                }
            }
            AttachmentKind.ROYAL_CROWN -> {
                val gold = PalettePresets.RUNE_GOLD
                val cy = (headCy - 5).coerceAtLeast(1)
                for (dx in -3..3) {
                    img.setPixel((cx + dx).coerceIn(1, 30), cy, gold.mid)
                    if (dx % 2 == 0) img.setPixel((cx + dx).coerceIn(1, 30), (cy - 1).coerceAtLeast(1), gold.highlight)
                }
            }
            else -> Unit
        }
    }

    private fun drawShoulderAttachment32(
        img: TileImage,
        cx: Int,
        torsoTop: Int,
        dir: Direction8,
        stride: Int,
        att: AttachmentKind,
        theme: PaperDollTheme,
        chunky: Boolean = false
    ) {
        if (img.height == 48) {
            drawShoulderRmxp(img, cx, torsoTop, dir, stride, att, theme)
            return
        }
        val pal = when (att) {
            AttachmentKind.FUR_MANTLE -> PalettePresets.CREAM_MARBLE
            else -> theme.accentPalette
        }
        val armSwing = stride / 2
        val padX = if (chunky) 2 else 0
        if (dir == Direction8.W) {
            fillRectShaded(img, (cx - 2 + armSwing).coerceIn(1, 27), torsoTop.coerceIn(1, 28), 4 + padX, 3, pal, 0.85f)
        } else {
            fillRectShaded(img, (cx - 7 - padX).coerceIn(1, 27), (torsoTop - armSwing / 2).coerceIn(1, 28), 3 + padX / 2, 3, pal, 0.85f)
            fillRectShaded(img, (cx + 5 + padX).coerceIn(1, 26), (torsoTop + armSwing / 2).coerceIn(1, 28), 3 + padX / 2, 3, pal, 0.72f)
            if (att == AttachmentKind.SPIKED_PAULDRONS) {
                img.setPixel((cx - 7 - padX).coerceIn(1, 30), (torsoTop - 1).coerceAtLeast(1), pal.highlight)
                img.setPixel((cx + 7 + padX).coerceIn(1, 30), (torsoTop - 1).coerceAtLeast(1), pal.highlight)
            }
        }
    }

    private val steelRamp = PaletteRamp.fromBaseColor(PaletteRamp.rgba(122, 130, 152), steps = 6, hueShift = 0.05f, valueSpread = 0.26f)

    /** Shoulder pieces for 32 x 48 dolls: domed plates (with lames and spikes) and a scalloped fur mantle. */
    private fun drawShoulderRmxp(
        img: TileImage,
        cx: Int,
        torsoTop: Int,
        dir: Direction8,
        stride: Int,
        att: AttachmentKind,
        theme: PaperDollTheme
    ) {
        val swing = stride / 2
        val trim = theme.accentPalette
        if (att == AttachmentKind.FUR_MANTLE) {
            val fur = PaletteRamp.fromBaseColor(theme.secondaryPalette.mid, steps = 6, hueShift = 0.04f, valueSpread = 0.30f)
            val ring = if (dir == Direction8.W) 5.4f else 8.6f
            val frontOpen = !dir.isBack && dir != Direction8.W
            // The mantle sits on the shoulders and collar; in front view the chest stays open so the torso (or the
            // bare body) shows through instead of a fur dome covering it.
            fillEllipseShaded(img, cx.toFloat(), torsoTop + 1.2f, ring - 1.6f, 1.8f, fur)
            if (frontOpen) {
                for (y in (torsoTop + 2)..(torsoTop + 4)) {
                    for (x in (cx - 2)..(cx + 2)) {
                        if (x in 0 until img.width && y in 0 until img.height) img.setPixel(x, y, 0)
                    }
                }
            }
            var x = (cx - ring).toInt() + 2
            while (x < cx + ring - 2) {
                if (!frontOpen || abs(x - cx) > 2) img.setPixel(x.coerceIn(1, 30), (torsoTop + 3).coerceIn(1, 46), fur.sample(0.42f))
                x += 2
            }
            return
        }
        val big = att == AttachmentKind.KNIGHT_PAULDRONS
        val rx = if (big) 4.4f else 3.8f
        val ry = if (big) 3.4f else 2.9f
        fun plate(px: Float, py: Float, tone: Float) {
            fillEllipseShaded(img, px, py + 1.4f, rx - 0.6f, ry - 0.4f, steelRamp)
            for (x in (px - rx + 0.6f).toInt()..(px + rx - 0.6f).toInt()) {
                img.setPixel(x.coerceIn(1, 30), (py + ry + 0.6f).toInt().coerceIn(1, 46), steelRamp.sample(0.32f))
            }
            fillEllipseShaded(img, px, py, rx, ry, steelRamp)
            for (x in (px - rx + 1f).toInt()..(px + rx - 1f).toInt()) {
                img.setPixel(x.coerceIn(1, 30), (py - ry + 0.4f).toInt().coerceIn(1, 46), trim.sample(tone))
            }
            img.setPixel((px - rx / 2f).toInt().coerceIn(1, 30), (py - ry / 2f).toInt().coerceIn(1, 46), steelRamp.highlight)
        }
        if (dir == Direction8.W) {
            plate(cx.toFloat() + swing / 2f, torsoTop + 1.2f, 0.8f)
            if (att == AttachmentKind.SPIKED_PAULDRONS) {
                img.setPixel(cx.coerceIn(1, 30), torsoTop - 2, steelRamp.highlight)
                img.setPixel(cx.coerceIn(1, 30), torsoTop - 3, steelRamp.highlight)
            }
        } else {
            val ly = torsoTop + 1.2f - swing / 2f
            val ryy = torsoTop + 1.2f + swing / 2f
            plate(cx - 7.2f, ly, 0.85f)
            plate(cx + 7.2f, ryy, 0.7f)
            if (att == AttachmentKind.SPIKED_PAULDRONS) {
                for (sgn in listOf(-1, 1)) {
                    val sx = cx + sgn * 8
                    val sy = (if (sgn < 0) ly else ryy).toInt() - 3
                    img.setPixel(sx.coerceIn(1, 30), sy.coerceAtLeast(1), steelRamp.highlight)
                    img.setPixel((sx + sgn).coerceIn(1, 30), (sy - 1).coerceAtLeast(1), steelRamp.highlight)
                    img.setPixel(sx.coerceIn(1, 30), (sy + 1).coerceAtLeast(1), steelRamp.mid)
                }
            }
        }
    }

    private fun drawWaistAttachment32(
        img: TileImage,
        cx: Int,
        legTop: Int,
        dir: Direction8,
        att: AttachmentKind,
        theme: PaperDollTheme,
        chunky: Boolean = false
    ) {
        val y = (legTop - 2).coerceIn(1, 29)
        val pal = when (att) {
            AttachmentKind.SILK_SASH -> theme.accentPalette
            else -> theme.secondaryPalette
        }
        val hw = if (chunky) 6 else 4
        for (dx in -hw..hw) {
            img.setPixel((cx + dx).coerceIn(1, 30), y, pal.mid)
        }
        if (att == AttachmentKind.POTION_POUCH && !dir.isBack) {
            fillRectShaded(img, (cx + 3).coerceIn(1, 28), y, 2, 3, PalettePresets.LACQUER_RED, 0.85f)
        } else if (att == AttachmentKind.AMULET && !dir.isBack) {
            img.setPixel(cx.coerceIn(1, 30), (legTop - 6).coerceIn(1, 29), PalettePresets.CELESTIAL_GOLD.highlight)
            img.setPixel(cx.coerceIn(1, 30), (legTop - 5).coerceIn(1, 29), PalettePresets.JADE_GREEN.highlight)
        }
    }

    private fun drawMainHandAttachment32(
        img: TileImage,
        cx: Int,
        torsoTop: Int,
        dir: Direction8,
        att: AttachmentKind,
        theme: PaperDollTheme
    ) {
        if (att == AttachmentKind.RIDING_CROP) {
            val hx = if (dir == Direction8.W || dir == Direction8.SW) cx - 5 else cx + 5
            val hy = torsoTop + 5
            for (i in 0..5) {
                img.setPixel((hx + i / 2).coerceIn(1, 30), (hy - i).coerceIn(1, 30), theme.secondaryPalette.mid)
            }
            img.setPixel((hx + 3).coerceIn(1, 30), (hy - 5).coerceIn(1, 30), theme.accentPalette.highlight)
        }
    }

    private fun drawOffHandAttachment32(
        img: TileImage,
        cx: Int,
        torsoTop: Int,
        dir: Direction8,
        att: AttachmentKind,
        theme: PaperDollTheme
    ) {
        val sx = if (dir == Direction8.W || dir == Direction8.SW) cx + 3 else cx - 6
        val sy = torsoTop + 3
        when (att) {
            AttachmentKind.ROSE_FAN -> {
                val rose = PalettePresets.PASTEL_PINK
                for (dx in -1..2) {
                    img.setPixel((sx + dx).coerceIn(1, 30), sy.coerceIn(1, 30), rose.highlight)
                    img.setPixel((sx + dx).coerceIn(1, 30), (sy + 1).coerceIn(1, 30), rose.mid)
                }
            }
            AttachmentKind.BASTION_TOWER -> {
                fillRectShaded(img, sx.coerceIn(1, 25), (sy - 1).coerceAtLeast(1), 5, 7, PalettePresets.CREAM_MARBLE, 0.78f)
            }
            else -> Unit
        }
    }

    private fun drawAuraAttachment32(
        backImg: TileImage,
        frontImg: TileImage,
        cx: Int,
        torsoTop: Int,
        frameIndex: Int,
        att: AttachmentKind,
        auraPal: PaletteRamp
    ) {
        val phase = frameIndex % 4
        when (att) {
            AttachmentKind.RUNE_CIRCLE -> {
                for (dx in -8..8 step 2) {
                    val col = if ((dx + phase) % 4 == 0) auraPal.highlight else auraPal.mid
                    backImg.setPixel((cx + dx).coerceIn(1, 30), 28, col)
                }
            }
            AttachmentKind.FLAME_AURA -> {
                val fire = PalettePresets.SCARLET_ROOF
                for (i in -1..1 step 2) {
                    val fx = (cx + i * (7 - (phase % 2))).coerceIn(1, 30)
                    val fy = (torsoTop + 4 - phase * 2).coerceIn(2, 29)
                    frontImg.setPixel(fx, fy, fire.highlight)
                    frontImg.setPixel(fx, (fy + 1).coerceIn(1, 30), fire.mid)
                }
            }
            AttachmentKind.HOLY_GLOW -> {
                val gold = PalettePresets.RUNE_GOLD.highlight
                frontImg.setPixel((cx - 6).coerceIn(1, 30), (torsoTop - phase).coerceIn(2, 29), gold)
                frontImg.setPixel((cx + 6).coerceIn(1, 30), (torsoTop + 2 - phase).coerceIn(2, 29), gold)
            }
            AttachmentKind.SHADOW_MIST -> {
                val dark = PalettePresets.ABYSSAL_OBSIDIAN.mid
                for (dx in -6..6 step 3) {
                    backImg.setPixel((cx + dx).coerceIn(1, 30), (26 - phase).coerceIn(1, 30), dark)
                }
            }
            else -> Unit
        }
    }

    private fun drawCustomAttachment32(
        img: TileImage,
        custom: CustomPixelAttachment,
        sockets: PaperDollSockets,
        defaultPalette: PaletteRamp
    ) {
        val pal = custom.palette ?: defaultPalette
        val (sx, sy) = sockets.forSocket(custom.socket)
        val startX = sx + custom.offsetX - custom.width / 2
        val startY = sy + custom.offsetY - custom.height / 2
        custom.rows.forEachIndexed { ry, row ->
            row.forEachIndexed { rx, ch ->
                val idx = when (ch) {
                    in '0'..'9' -> ch - '0'
                    in 'a'..'f' -> 10 + (ch - 'a')
                    in 'A'..'F' -> 10 + (ch - 'A')
                    else -> -1
                }
                if (idx >= 0) {
                    val px = startX + rx
                    val py = startY + ry
                    if (px in 0..31 && py in 0..31) {
                        val color = pal.colors[idx.coerceIn(0, pal.colors.lastIndex)]
                        img.setPixel(px, py, color)
                    }
                }
            }
        }
    }

    private fun drawCastAura32(img: TileImage, cx: Int, phase: Int, auraPal: PaletteRamp) {
        val runeGlow = auraPal.highlight
        val goldGlow = PaletteRamp.rgba(255, 236, 132)
        val ringY = img.height - 5
        for (dx in -7..7 step 2) {
            val px = (cx + dx).coerceIn(1, 30)
            img.setPixel(px, ringY, if ((dx + phase) % 4 == 0) runeGlow else goldGlow)
        }
        val moteBase = if (img.height == 48) 25 else 16
        img.setPixel((cx - 7).coerceIn(1, 30), (moteBase - phase * 2).coerceIn(1, img.height - 2), runeGlow)
        img.setPixel((cx + 7).coerceIn(1, 30), (moteBase - 2 - phase * 2).coerceIn(1, img.height - 2), goldGlow)
    }

    private fun drawAttackSlash32(img: TileImage, cx: Int, torsoTop: Int, dir: Direction8) {
        val arcLit = PaletteRamp.rgba(255, 255, 255)
        val arcTrail = PaletteRamp.rgba(148, 226, 255)
        val sign = if (dir == Direction8.W || dir == Direction8.SW) -1 else 1
        for (i in -4..5) {
            val sx = (cx + sign * (7 - abs(i) / 2)).coerceIn(1, 30)
            val sy = (torsoTop + 4 + i).coerceIn(1, 30)
            img.setPixel(sx, sy, arcLit)
            if ((sx - sign) in 1..30) img.setPixel(sx - sign, sy, arcTrail)
        }
    }

    private fun drawThrustVfx32(img: TileImage, cx: Int, torsoTop: Int, dir: Direction8) {
        val tip = PaletteRamp.rgba(255, 255, 255)
        val trail = PaletteRamp.rgba(255, 220, 128)
        val sign = if (dir == Direction8.W || dir == Direction8.SW) -1 else 1
        val y = (torsoTop + 4).coerceIn(1, 30)
        for (i in 6..12) {
            val sx = (cx + sign * i).coerceIn(1, 30)
            img.setPixel(sx, y, if (i >= 11) tip else trail)
        }
    }

    private fun drawShootVfx32(img: TileImage, cx: Int, torsoTop: Int, dir: Direction8) {
        val arrowTip = PaletteRamp.rgba(255, 255, 255)
        val streak = PaletteRamp.rgba(180, 244, 160)
        val sign = if (dir == Direction8.W || dir == Direction8.SW) -1 else 1
        val y = (torsoTop + 4).coerceIn(1, 30)
        for (i in 7..13) {
            val sx = (cx + sign * i).coerceIn(1, 30)
            img.setPixel(sx, y, if (i == 13) arrowTip else streak)
        }
    }

    private fun drawHarvestVfx32(img: TileImage, cx: Int, torsoTop: Int, dir: Direction8) {
        val spark = PaletteRamp.rgba(255, 236, 132)
        val dust = PaletteRamp.rgba(210, 190, 150)
        val sign = if (dir == Direction8.W || dir == Direction8.SW) -1 else 1
        val sx = (cx + sign * 7).coerceIn(2, 29)
        val sy = (torsoTop + 9).coerceIn(2, 29)
        img.setPixel(sx, sy, spark)
        img.setPixel((sx - 1).coerceIn(1, 30), (sy - 1).coerceIn(1, 30), dust)
        img.setPixel((sx + 1).coerceIn(1, 30), (sy - 1).coerceIn(1, 30), dust)
    }

    private fun hasVisiblePixels(img: TileImage): Boolean {
        for (p in img.pixels) {
            if ((p and 0xFF) != 0) return true
        }
        return false
    }

    private fun Direction8.toFacingDirection(): FacingDirection = when (this) {
        Direction8.N, Direction8.NE, Direction8.NW -> FacingDirection.UP
        Direction8.S, Direction8.SE, Direction8.SW -> FacingDirection.DOWN
        Direction8.W -> FacingDirection.LEFT
        Direction8.E -> FacingDirection.RIGHT
    }

    private fun monsterPaletteFor(kind: JrpgMonsterKind): PaletteRamp = when (kind) {
        JrpgMonsterKind.SLIME_BLOB -> PalettePresets.PASTEL_PINK
        JrpgMonsterKind.MOON_HARE -> PalettePresets.CREAM_MARBLE
        JrpgMonsterKind.LARVA -> PalettePresets.FOREST_JADE
        JrpgMonsterKind.ORC_WARRIOR -> PalettePresets.HIGHLAND_GRASS
        JrpgMonsterKind.HORNED_DEMON -> PalettePresets.ARCANE_VIOLET
        JrpgMonsterKind.HIGHLAND_BEAST -> PalettePresets.WARM_TIMBER
        JrpgMonsterKind.RUNE_GOLEM -> PalettePresets.PALE_LIMESTONE
        JrpgMonsterKind.SHIELD_WRAITH -> PalettePresets.CREAM_MARBLE
    }

    private fun renderMonster32(kind: JrpgMonsterKind, frameIndex: Int, pal: PaletteRamp): TileImage {
        val img = TileImage(32, 32)
        val bob = if (frameIndex % 2 == 1) -1 else 0
        when (kind) {
            JrpgMonsterKind.SLIME_BLOB -> {
                // Translucent pink droplet slime with apple stem & glossy specular
                val cy = 20f + bob
                for (y in 10..27) {
                    for (x in 6..25) {
                        val dx = (x - 16f) / 8.8f
                        val dy = (y - cy) / 7.2f
                        val teardrop = if (y < cy) abs(dx) * (cy - y) * 0.06f else 0f
                        val d = hypot(dx, dy) + teardrop
                        if (d <= 1f) {
                            val light = (0.82f - dx * 0.25f - dy * 0.30f).coerceIn(0.20f, 0.98f)
                            img.setPixel(x, y, pal.sample(light))
                        }
                    }
                }
                // Glossy white window highlight, cute eyes, and top apple leaf
                img.setPixel(12, 16 + bob, PaletteRamp.rgba(255, 255, 255))
                img.setPixel(13, 16 + bob, PaletteRamp.rgba(255, 255, 255))
                img.setPixel(12, 17 + bob, PaletteRamp.rgba(255, 255, 255))
                img.setPixel(13, 21 + bob, pal.shadow)
                img.setPixel(18, 21 + bob, pal.shadow)
                img.setPixel(16, 11 + bob, PalettePresets.FOREST_JADE.mid)
                img.setPixel(17, 10 + bob, PalettePresets.FOREST_JADE.highlight)
            }
            JrpgMonsterKind.MOON_HARE -> {
                // Fluffy clover-ear rabbit monster
                for (earX in listOf(12, 19)) {
                    for (y in (7 + bob)..(15 + bob)) {
                        img.setPixel(earX, y, pal.highlight)
                        img.setPixel(earX + 1, y, pal.mid)
                    }
                }
                fillEllipseShaded(img, 16f, 20f + bob, 7.2f, 6.2f, pal)
                img.setPixel(13, 19 + bob, PaletteRamp.rgba(238, 52, 68))
                img.setPixel(18, 19 + bob, PaletteRamp.rgba(238, 52, 68))
            }
            JrpgMonsterKind.LARVA -> {
                // Segmented green larva
                for (seg in 0..3) {
                    val sx = 11f + seg * 3.2f
                    val sy = 22f - (if (seg == 0) 3f else 0f) + bob
                    fillEllipseShaded(img, sx, sy, 3.6f, 3.4f, pal)
                }
                img.setPixel(9, 16 + bob, PalettePresets.RUNE_GOLD.highlight)
                img.setPixel(11, 16 + bob, PalettePresets.RUNE_GOLD.highlight)
            }
            else -> {
                // Tall humanoid/beast boss monster silhouette with horns/weapons
                fillEllipseShaded(img, 16f, 18f + bob, 6.5f, 7.5f, pal)
                fillEllipseShaded(img, 16f, 9.5f + bob, 4.8f, 4.5f, pal)
                fillRectShaded(img, 11, 23, 3, 5, pal, 0.45f)
                fillRectShaded(img, 18, 23, 3, 5, pal, 0.38f)
                // Horns & glowing eyes
                for (i in 1..4) {
                    img.setPixel(11 - i / 2, 7 + bob - i, PalettePresets.RUNE_GOLD.highlight)
                    img.setPixel(20 + i / 2, 7 + bob - i, PalettePresets.RUNE_GOLD.highlight)
                }
                img.setPixel(14, 9 + bob, PaletteRamp.rgba(255, 224, 92))
                img.setPixel(17, 9 + bob, PaletteRamp.rgba(255, 224, 92))
                // Crescent Scythe / Battle Weapon on right
                for (y in (5 + bob)..(25 + bob)) {
                    img.setPixel(25, y.coerceIn(1, 30), PalettePresets.CREAM_MARBLE.highlight)
                }
            }
        }
        return img
    }

    private fun fillRectShaded(img: TileImage, x0: Int, y0: Int, w: Int, h: Int, pal: PaletteRamp, baseTone: Float) {
        // Banded column form (lit left, shaded right, dark underside) instead of a diagonal gradient.
        PixelForm.column(img, x0, y0, w, h, pal, (baseTone - 0.15f).coerceIn(0.2f, 0.9f), round = w >= 3 && h >= 3)
    }

    private fun fillEllipseShaded(img: TileImage, cx: Float, cy: Float, rx: Float, ry: Float, pal: PaletteRamp) {
        PixelForm.dome(img, cx, cy, rx, ry, pal)
    }

    private fun drawGroundShadow32(img: TileImage, cx: Float, cy: Float, rx: Float, ry: Float) {
        val shadow = PaletteRamp.rgba(16, 14, 28, 110)
        for (y in (cy - ry).toInt().coerceAtLeast(0)..(cy + ry).toInt().coerceAtMost(img.height - 1)) {
            for (x in (cx - rx).toInt().coerceAtLeast(0)..(cx + rx).toInt().coerceAtMost(img.width - 1)) {
                val d = hypot((x + 0.5f - cx) / rx, (y + 0.5f - cy) / ry)
                if (d <= 1f) img.drawPixel(x, y, shadow)
            }
        }
    }

    private fun compositeOverlay(dst: TileImage, src: TileImage) {
        for (y in 0 until dst.height) {
            for (x in 0 until dst.width) {
                val px = src.getPixel(x, y)
                if ((px and 0xFF) != 0) {
                    dst.drawPixel(x, y, px)
                }
            }
        }
    }

    private fun mirrorImageX(src: TileImage): TileImage {
        val out = TileImage(src.width, src.height)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                out.setPixel(src.width - 1 - x, y, src.getPixel(x, y))
            }
        }
        return out
    }

    private fun scaleNearest(src: TileImage, targetWidth: Int, targetHeight: Int = targetWidth): TileImage {
        val out = TileImage(targetWidth, targetHeight)
        for (y in 0 until targetHeight) {
            val sy = (y * src.height) / targetHeight
            for (x in 0 until targetWidth) {
                val sx = (x * src.width) / targetWidth
                out.setPixel(x, y, src.getPixel(sx, sy))
            }
        }
        return out
    }
}
