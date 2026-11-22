package com.morgana.tilegen.sprite

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelStyle

/**
 * Individual compositable paper-doll layers, modeled after Universal LPC sprite sheets.
 * Every layer is rendered into an aligned transparent canvas (except [SHADOW]) so game engines
 * can mix, match, tint, and toggle equipment dynamically at runtime.
 */
enum class PaperDollSlot(val layerName: String) {
    SHADOW("shadow"),
    AURA_BACK("aura_back"),
    CAPE("cape"),
    BACK_ATTACHMENT("back_attachment"),
    BODY("body"),
    EYES("eyes"),
    HAIR_BACK("hair_back"),
    LEGS("legs"),
    FEET("feet"),
    TORSO("torso"),
    WAIST_ATTACHMENT("waist_attachment"),
    SHOULDER_ATTACHMENT("shoulder_attachment"),
    HAIR_FRONT("hair_front"),
    FACE_ACCESSORY("face_accessory"),
    HEADGEAR("headgear"),
    OFF_HAND("off_hand"),
    MAIN_HAND("main_hand"),
    AURA_FRONT("aura_front");

    companion object {
        /**
         * Returns the canonical compositing z-order (back-to-front) for [direction] and [action].
         */
        fun zOrderFor(direction: Direction8, action: PaperDollAction = PaperDollAction.WALK): List<PaperDollSlot> {
            val isBack = direction.isBack
            val isLeftProfile = direction == Direction8.W
            val isRightProfile = direction == Direction8.E
            val weaponInFrontOfBack = action == PaperDollAction.SLASH ||
                action == PaperDollAction.THRUST ||
                action == PaperDollAction.SHOOT ||
                action == PaperDollAction.HARVEST

            return when {
                isBack -> buildList {
                    add(SHADOW)
                    add(AURA_BACK)
                    if (!weaponInFrontOfBack) {
                        add(MAIN_HAND)
                        add(OFF_HAND)
                    }
                    add(BODY)
                    add(LEGS)
                    add(FEET)
                    add(TORSO)
                    add(WAIST_ATTACHMENT)
                    add(SHOULDER_ATTACHMENT)
                    add(EYES)
                    add(HAIR_FRONT)
                    add(FACE_ACCESSORY)
                    add(HAIR_BACK)
                    add(HEADGEAR)
                    add(CAPE)
                    add(BACK_ATTACHMENT)
                    if (weaponInFrontOfBack) {
                        add(OFF_HAND)
                        add(MAIN_HAND)
                    }
                    add(AURA_FRONT)
                }
                isLeftProfile -> listOf(
                    SHADOW,
                    AURA_BACK,
                    CAPE,
                    BACK_ATTACHMENT,
                    HAIR_BACK,
                    MAIN_HAND,
                    BODY,
                    LEGS,
                    FEET,
                    TORSO,
                    WAIST_ATTACHMENT,
                    SHOULDER_ATTACHMENT,
                    EYES,
                    HAIR_FRONT,
                    FACE_ACCESSORY,
                    HEADGEAR,
                    OFF_HAND,
                    AURA_FRONT
                )
                isRightProfile -> listOf(
                    SHADOW,
                    AURA_BACK,
                    CAPE,
                    BACK_ATTACHMENT,
                    HAIR_BACK,
                    OFF_HAND,
                    BODY,
                    LEGS,
                    FEET,
                    TORSO,
                    WAIST_ATTACHMENT,
                    SHOULDER_ATTACHMENT,
                    EYES,
                    HAIR_FRONT,
                    FACE_ACCESSORY,
                    HEADGEAR,
                    MAIN_HAND,
                    AURA_FRONT
                )
                else -> listOf(
                    SHADOW,
                    AURA_BACK,
                    CAPE,
                    BACK_ATTACHMENT,
                    HAIR_BACK,
                    BODY,
                    LEGS,
                    FEET,
                    TORSO,
                    WAIST_ATTACHMENT,
                    SHOULDER_ATTACHMENT,
                    EYES,
                    HAIR_FRONT,
                    FACE_ACCESSORY,
                    HEADGEAR,
                    OFF_HAND,
                    MAIN_HAND,
                    AURA_FRONT
                )
            }
        }
    }
}

/**
 * Universal LPC-inspired action set for modular paper-doll characters.
 */
enum class PaperDollAction(
    val defaultFrames: Int,
    val defaultFrameDurationMs: Int,
    val looping: Boolean
) {
    IDLE(defaultFrames = 2, defaultFrameDurationMs = 220, looping = true),
    WALK(defaultFrames = 4, defaultFrameDurationMs = 135, looping = true),
    RUN(defaultFrames = 4, defaultFrameDurationMs = 95, looping = true),
    SLASH(defaultFrames = 4, defaultFrameDurationMs = 90, looping = false),
    THRUST(defaultFrames = 4, defaultFrameDurationMs = 90, looping = false),
    SHOOT(defaultFrames = 4, defaultFrameDurationMs = 100, looping = false),
    CAST(defaultFrames = 4, defaultFrameDurationMs = 115, looping = false),
    HARVEST(defaultFrames = 4, defaultFrameDurationMs = 115, looping = false),
    JUMP(defaultFrames = 3, defaultFrameDurationMs = 110, looping = false),
    SIT(defaultFrames = 2, defaultFrameDurationMs = 250, looping = true),
    EMOTE(defaultFrames = 3, defaultFrameDurationMs = 160, looping = false),
    HURT(defaultFrames = 2, defaultFrameDurationMs = 90, looping = false),
    KO(defaultFrames = 2, defaultFrameDurationMs = 160, looping = false);

    /**
     * Maps this action to the closest legacy [SpriteAnimationClip].
     */
    fun toSpriteAnimationClip(): SpriteAnimationClip = when (this) {
        IDLE, SIT, EMOTE -> SpriteAnimationClip.IDLE
        WALK, RUN, JUMP -> SpriteAnimationClip.WALK
        SLASH, THRUST, SHOOT, HARVEST -> SpriteAnimationClip.ATTACK
        CAST -> SpriteAnimationClip.CAST
        HURT -> SpriteAnimationClip.HURT
        KO -> SpriteAnimationClip.KO
    }

    companion object {
        val LPC_STANDARD: List<PaperDollAction> = listOf(CAST, THRUST, WALK, SLASH, SHOOT, HURT)
        val TREVANA: List<PaperDollAction> = listOf(IDLE, WALK, SLASH, THRUST, SHOOT, CAST, HARVEST, JUMP, HURT)
        val ALL: List<PaperDollAction> = entries.toList()

        fun fromSpriteAnimationClip(clip: SpriteAnimationClip): PaperDollAction = when (clip) {
            SpriteAnimationClip.IDLE -> IDLE
            SpriteAnimationClip.WALK -> WALK
            SpriteAnimationClip.ATTACK -> SLASH
            SpriteAnimationClip.CAST -> CAST
            SpriteAnimationClip.HURT -> HURT
            SpriteAnimationClip.KO -> KO
        }
    }
}

/**
 * Character ancestry / body base affecting ear geometry, facial features, and default skin tones.
 */
enum class BodyAncestry {
    HUMAN,
    ELF,
    ORC,
    UNDEAD,
    DEMON,
    CONSTRUCT
}

/**
 * Silhouette proportion profile inside the native 32x32 paper-doll canvas:
 * - [STANDARD]: 3-head-tall slim JRPG silhouette (~17x28 px).
 * - [CHUNKY]: Heroic wide-silhouette (~22-26 px wide x 28-30 px tall) matching 2x-scaled 16x16 NPCs/enemies.
 */
enum class PaperDollProportions {
    STANDARD,
    CHUNKY,
    RMXP_32X48
}

/**
 * Upper-body armor/tunic style for the [PaperDollSlot.TORSO] layer.
 */
enum class TorsoStyle {
    TRAVELER,
    BRIGANDINE,
    SKYRENDER
}

/**
 * Lower-body garment style for the [PaperDollSlot.LEGS] layer.
 */
enum class LegsStyle {
    TROUSERS,
    GREAVES,
    ROBE_SKIRT,
    SHORTS
}

/**
 * Footwear style for the [PaperDollSlot.FEET] layer.
 */
enum class FeetStyle {
    BOOTS,
    SABATONS,
    SANDALS,
    BAREFOOT
}

/**
 * Attachment socket anchor points on a paper-doll character.
 */
enum class AttachmentSocket {
    BACK,
    HEAD,
    FACE,
    SHOULDER,
    WAIST,
    MAIN_HAND,
    OFF_HAND,
    AURA;

    fun defaultSlot(): PaperDollSlot = when (this) {
        BACK -> PaperDollSlot.BACK_ATTACHMENT
        HEAD -> PaperDollSlot.HEADGEAR
        FACE -> PaperDollSlot.FACE_ACCESSORY
        SHOULDER -> PaperDollSlot.SHOULDER_ATTACHMENT
        WAIST -> PaperDollSlot.WAIST_ATTACHMENT
        MAIN_HAND -> PaperDollSlot.MAIN_HAND
        OFF_HAND -> PaperDollSlot.OFF_HAND
        AURA -> PaperDollSlot.AURA_FRONT
    }
}

/**
 * Built-in modular attachments across back, head, face, shoulder, waist, and aura sockets.
 */
enum class AttachmentKind(val socket: AttachmentSocket) {
    CAPE(AttachmentSocket.BACK),
    QUIVER(AttachmentSocket.BACK),
    ANGEL_WINGS(AttachmentSocket.BACK),
    DEMON_WINGS(AttachmentSocket.BACK),
    FAIRY_WINGS(AttachmentSocket.BACK),
    BACKPACK(AttachmentSocket.BACK),
    WAR_BANNER(AttachmentSocket.BACK),
    SATCHEL(AttachmentSocket.BACK),
    SILK_GARMENT(AttachmentSocket.BACK),

    HORNS(AttachmentSocket.HEAD),
    CAT_EARS(AttachmentSocket.HEAD),
    HALO(AttachmentSocket.HEAD),
    ROYAL_CROWN(AttachmentSocket.HEAD),

    EYE_PATCH(AttachmentSocket.FACE),
    GLASSES(AttachmentSocket.FACE),
    CYBER_VISOR(AttachmentSocket.FACE),
    HALF_MASK(AttachmentSocket.FACE),

    SPIKED_PAULDRONS(AttachmentSocket.SHOULDER),
    KNIGHT_PAULDRONS(AttachmentSocket.SHOULDER),
    FUR_MANTLE(AttachmentSocket.SHOULDER),

    UTILITY_BELT(AttachmentSocket.WAIST),
    SILK_SASH(AttachmentSocket.WAIST),
    POTION_POUCH(AttachmentSocket.WAIST),
    AMULET(AttachmentSocket.WAIST),

    ROSE_FAN(AttachmentSocket.OFF_HAND),
    RIDING_CROP(AttachmentSocket.MAIN_HAND),
    BASTION_TOWER(AttachmentSocket.OFF_HAND),

    RUNE_CIRCLE(AttachmentSocket.AURA),
    FLAME_AURA(AttachmentSocket.AURA),
    HOLY_GLOW(AttachmentSocket.AURA),
    SHADOW_MIST(AttachmentSocket.AURA)
}

/**
 * Data-driven custom pixel-grid attachment anchored to any [AttachmentSocket].
 *
 * Each string in [rows] encodes one horizontal row of pixels:
 * - `.` or ` ` -> transparent
 * - `0`..`5` -> index into [palette] (or the theme's accent palette if [palette] is null)
 * - `a`..`f` -> alternative tone indices if needed (mapped modulo palette size)
 */
data class CustomPixelAttachment(
    val name: String,
    val socket: AttachmentSocket,
    val rows: List<String>,
    val palette: PaletteRamp? = null,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val hideWhenFacingBack: Boolean = false,
    val hideWhenFacingFront: Boolean = false
) {
    init {
        require(name.isNotBlank()) { "Custom attachment name must not be blank" }
        require(rows.isNotEmpty()) { "Custom attachment '$name' must have at least one row" }
    }

    val width: Int get() = rows.maxOf { it.length }
    val height: Int get() = rows.size
}

/**
 * Complete visual theme for a paper-doll character, controlling ancestry, skin, eyes,
 * hair, primary/secondary/accent equipment ramps, magic aura ramp, and [PixelStyle].
 */
data class PaperDollTheme(
    val name: String = "custom",
    val ancestry: BodyAncestry = BodyAncestry.HUMAN,
    val skinPalette: PaletteRamp = defaultSkinFor(ancestry),
    val eyeColor: Int = defaultEyeFor(ancestry),
    val hairPalette: PaletteRamp = PaletteRamp.fromBaseColor(PaletteRamp.rgba(52, 44, 58), steps = 5),
    val outfitPalette: PaletteRamp = PalettePresets.SK_SCARLET_ROOF,
    val accentPalette: PaletteRamp = PalettePresets.SK_TRUE_RUNE_GOLD,
    val secondaryPalette: PaletteRamp = PalettePresets.SK_KYARO_TIMBER,
    val auraPalette: PaletteRamp = PalettePresets.RO_GEFFEN_ARCANE,
    val style: PixelStyle = PixelStyle.RAGNAROK_25D,
    val proportions: PaperDollProportions = PaperDollProportions.STANDARD
) {
    companion object {
        fun defaultSkinFor(ancestry: BodyAncestry): PaletteRamp = when (ancestry) {
            BodyAncestry.HUMAN -> PaletteRamp.fromBaseColor(PaletteRamp.rgba(232, 186, 152), steps = 5)
            BodyAncestry.ELF -> PaletteRamp.fromBaseColor(PaletteRamp.rgba(244, 212, 186), steps = 5)
            BodyAncestry.ORC -> PaletteRamp.fromBaseColor(PaletteRamp.rgba(116, 158, 92), steps = 5)
            BodyAncestry.UNDEAD -> PaletteRamp.fromBaseColor(PaletteRamp.rgba(184, 192, 178), steps = 5)
            BodyAncestry.DEMON -> PaletteRamp.fromBaseColor(PaletteRamp.rgba(174, 86, 110), steps = 5)
            BodyAncestry.CONSTRUCT -> PaletteRamp.fromBaseColor(PaletteRamp.rgba(156, 168, 184), steps = 5)
        }

        fun defaultEyeFor(ancestry: BodyAncestry): Int = when (ancestry) {
            BodyAncestry.HUMAN -> PaletteRamp.rgba(46, 84, 148)
            BodyAncestry.ELF -> PaletteRamp.rgba(64, 168, 128)
            BodyAncestry.ORC -> PaletteRamp.rgba(196, 62, 44)
            BodyAncestry.UNDEAD -> PaletteRamp.rgba(128, 232, 240)
            BodyAncestry.DEMON -> PaletteRamp.rgba(236, 184, 48)
            BodyAncestry.CONSTRUCT -> PaletteRamp.rgba(80, 224, 255)
        }

        fun fromJob(job: PaperDollJob): PaperDollTheme = PaperDollTheme(
            name = job.name.lowercase(),
            ancestry = BodyAncestry.HUMAN,
            skinPalette = defaultSkinFor(BodyAncestry.HUMAN),
            eyeColor = defaultEyeFor(BodyAncestry.HUMAN),
            hairPalette = PaperDollLoadout.defaultHairPalette(job),
            outfitPalette = PaperDollLoadout.defaultOutfitPalette(job),
            accentPalette = PaperDollLoadout.defaultAccentPalette(job),
            secondaryPalette = PalettePresets.SK_KYARO_TIMBER,
            auraPalette = PalettePresets.RO_GEFFEN_ARCANE,
            style = PixelStyle.RAGNAROK_25D
        )
    }
}

/**
 * Curated preset themes for rapid paper-doll creation across fantasy, dark-fantasy, and sci-fi settings.
 */
object PaperDollThemes {
    val HIGHLAND_HERO = PaperDollTheme(
        name = "highland_hero",
        ancestry = BodyAncestry.HUMAN,
        outfitPalette = PalettePresets.SK_SCARLET_ROOF,
        accentPalette = PalettePresets.SK_TRUE_RUNE_GOLD,
        secondaryPalette = PalettePresets.SK_KYARO_TIMBER,
        hairPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(52, 44, 58), steps = 5),
        auraPalette = PalettePresets.SK_TRUE_RUNE_GOLD,
        style = PixelStyle.SUIKODEN_32BIT
    )

    val PRONTERA_KNIGHT = PaperDollTheme(
        name = "prontera_knight",
        ancestry = BodyAncestry.HUMAN,
        outfitPalette = PalettePresets.RO_PRONTERA_MARBLE,
        accentPalette = PalettePresets.SK_SCARLET_ROOF,
        secondaryPalette = PalettePresets.SK_KYARO_TIMBER,
        hairPalette = PalettePresets.SK_TRUE_RUNE_GOLD,
        auraPalette = PalettePresets.RO_PRONTERA_MARBLE,
        style = PixelStyle.RAGNAROK_25D
    )

    val ELVEN_RANGER = PaperDollTheme(
        name = "elven_ranger",
        ancestry = BodyAncestry.ELF,
        outfitPalette = PalettePresets.SK_HIGHLAND_GRASS,
        accentPalette = PalettePresets.SK_KYARO_TIMBER,
        secondaryPalette = PalettePresets.RO_PAYON_JADE,
        hairPalette = PalettePresets.SK_TRUE_RUNE_GOLD,
        auraPalette = PalettePresets.RO_PAYON_JADE,
        style = PixelStyle.RAGNAROK_25D
    )

    val GEFFEN_ARCHMAGE = PaperDollTheme(
        name = "geffen_archmage",
        ancestry = BodyAncestry.HUMAN,
        outfitPalette = PalettePresets.RO_GEFFEN_ARCANE,
        accentPalette = PalettePresets.SK_TRUE_RUNE_GOLD,
        secondaryPalette = PalettePresets.WR_ABYSSAL_OBSIDIAN,
        hairPalette = PalettePresets.RO_PORING_PASTEL,
        auraPalette = PalettePresets.RO_GEFFEN_ARCANE,
        style = PixelStyle.RAGNAROK_25D
    )

    val ORC_WARLORD = PaperDollTheme(
        name = "orc_warlord",
        ancestry = BodyAncestry.ORC,
        outfitPalette = PalettePresets.WR_SERENDIA_IRON,
        accentPalette = PalettePresets.CO_DYNASTY_LACQUER,
        secondaryPalette = PalettePresets.SK_KYARO_TIMBER,
        hairPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(36, 32, 34), steps = 5),
        auraPalette = PalettePresets.CO_DYNASTY_LACQUER,
        style = PixelStyle.RAGNAROK_25D
    )

    val DEMON_REAPER = PaperDollTheme(
        name = "demon_reaper",
        ancestry = BodyAncestry.DEMON,
        outfitPalette = PalettePresets.WR_ABYSSAL_OBSIDIAN,
        accentPalette = PalettePresets.WR_BLACK_SPIRIT,
        secondaryPalette = PalettePresets.WR_SERENDIA_IRON,
        hairPalette = PalettePresets.RO_PRONTERA_MARBLE,
        auraPalette = PalettePresets.WR_BLACK_SPIRIT,
        style = PixelStyle.RAGNAROK_25D
    )

    val UNDEAD_LICH = PaperDollTheme(
        name = "undead_lich",
        ancestry = BodyAncestry.UNDEAD,
        outfitPalette = PalettePresets.WR_ABYSSAL_OBSIDIAN,
        accentPalette = PalettePresets.RO_GEFFEN_ARCANE,
        secondaryPalette = PalettePresets.WR_SERENDIA_IRON,
        hairPalette = PalettePresets.RO_PRONTERA_MARBLE,
        auraPalette = PalettePresets.RO_GEFFEN_ARCANE,
        style = PixelStyle.RAGNAROK_25D
    )

    val CYBER_SENTINEL = PaperDollTheme(
        name = "cyber_sentinel",
        ancestry = BodyAncestry.CONSTRUCT,
        outfitPalette = PalettePresets.WR_SERENDIA_IRON,
        accentPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(60, 200, 240), steps = 5),
        secondaryPalette = PalettePresets.WR_ABYSSAL_OBSIDIAN,
        hairPalette = PalettePresets.RO_PRONTERA_MARBLE,
        auraPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(60, 200, 240), steps = 5),
        style = PixelStyle.STANDARD
    )

    val SHRINE_PRIESTESS = PaperDollTheme(
        name = "shrine_priestess",
        ancestry = BodyAncestry.HUMAN,
        outfitPalette = PalettePresets.RO_PORING_PASTEL,
        accentPalette = PalettePresets.SK_SCARLET_ROOF,
        secondaryPalette = PalettePresets.RO_PRONTERA_MARBLE,
        hairPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(44, 38, 64), steps = 5),
        auraPalette = PalettePresets.SK_TRUE_RUNE_GOLD,
        style = PixelStyle.SUIKODEN_32BIT
    )

    val DESERT_NOMAD = PaperDollTheme(
        name = "desert_nomad",
        ancestry = BodyAncestry.HUMAN,
        skinPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(198, 146, 108), steps = 5),
        outfitPalette = PalettePresets.RO_MORROC_SANDSTONE,
        accentPalette = PalettePresets.CO_CELESTIAL_GOLD,
        secondaryPalette = PalettePresets.SK_KYARO_TIMBER,
        hairPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(42, 34, 32), steps = 5),
        auraPalette = PalettePresets.CO_CELESTIAL_GOLD,
        style = PixelStyle.RAGNAROK_25D
    )

    val TREVANA_NEUTRAL = PaperDollTheme(
        name = "trevana_neutral",
        ancestry = BodyAncestry.HUMAN,
        outfitPalette = PalettePresets.RO_PRONTERA_MARBLE,
        accentPalette = PalettePresets.SK_MUSE_LIMESTONE,
        secondaryPalette = PalettePresets.WR_SERENDIA_IRON,
        hairPalette = PaletteRamp.fromBaseColor(PaletteRamp.rgba(72, 64, 78), steps = 5),
        auraPalette = PalettePresets.RO_PRONTERA_MARBLE,
        style = PixelStyle.RAGNAROK_25D,
        proportions = PaperDollProportions.CHUNKY
    )

    val presets: Map<String, PaperDollTheme> = listOf(
        HIGHLAND_HERO,
        PRONTERA_KNIGHT,
        ELVEN_RANGER,
        GEFFEN_ARCHMAGE,
        ORC_WARLORD,
        DEMON_REAPER,
        UNDEAD_LICH,
        CYBER_SENTINEL,
        SHRINE_PRIESTESS,
        DESERT_NOMAD,
        TREVANA_NEUTRAL
    ).associateBy { it.name }

    fun resolve(name: String): PaperDollTheme? = presets[name.lowercase().trim()]
}

/**
 * Explicit socket coordinates (in native 32x32 space, scaled to [tileSize] on export) for attaching
 * external sprites, particles, or custom pixel attachments.
 */
data class PaperDollSockets(
    val pivotX: Int,
    val pivotY: Int,
    val headX: Int,
    val headY: Int,
    val faceX: Int,
    val faceY: Int,
    val backX: Int,
    val backY: Int,
    val shoulderX: Int,
    val shoulderY: Int,
    val waistX: Int,
    val waistY: Int,
    val mainHandX: Int,
    val mainHandY: Int,
    val offHandX: Int,
    val offHandY: Int
) {
    fun forSocket(socket: AttachmentSocket): Pair<Int, Int> = when (socket) {
        AttachmentSocket.BACK -> backX to backY
        AttachmentSocket.HEAD -> headX to headY
        AttachmentSocket.FACE -> faceX to faceY
        AttachmentSocket.SHOULDER -> shoulderX to shoulderY
        AttachmentSocket.WAIST -> waistX to waistY
        AttachmentSocket.MAIN_HAND -> mainHandX to mainHandY
        AttachmentSocket.OFF_HAND -> offHandX to offHandY
        AttachmentSocket.AURA -> pivotX to pivotY
    }

    fun scaled(cellWidth: Int, cellHeight: Int = cellWidth, nativeHeight: Int = 32): PaperDollSockets {
        if (cellWidth == 32 && cellHeight == nativeHeight) return this
        val sx = cellWidth / 32f
        val sy = cellHeight / nativeHeight.toFloat()
        fun scX(v: Int) = (v * sx).toInt()
        fun scY(v: Int) = (v * sy).toInt()
        return PaperDollSockets(
            pivotX = scX(pivotX),
            pivotY = scY(pivotY),
            headX = scX(headX),
            headY = scY(headY),
            faceX = scX(faceX),
            faceY = scY(faceY),
            backX = scX(backX),
            backY = scY(backY),
            shoulderX = scX(shoulderX),
            shoulderY = scY(shoulderY),
            waistX = scX(waistX),
            waistY = scY(waistY),
            mainHandX = scX(mainHandX),
            mainHandY = scY(mainHandY),
            offHandX = scX(offHandX),
            offHandY = scY(offHandY)
        )
    }
}

/**
 * Computed kinematic pose for a single frame of a [PaperDollAction] in a given [Direction8].
 */
data class PaperDollPose(
    val action: PaperDollAction,
    val frameIndex: Int,
    val phase: Int,
    val bobY: Int,
    val stride: Int,
    val lungeX: Int,
    val isSeated: Boolean = false,
    val isAirborne: Boolean = false,
    val isCollapsed: Boolean = false,
    val shadowRadiusX: Float = 7.5f
) {
    companion object {
        fun compute(
            baseDir: Direction8,
            action: PaperDollAction,
            frameIndex: Int,
            proportions: PaperDollProportions = PaperDollProportions.STANDARD,
            nativeHeight: Int = 32
        ): Pair<PaperDollPose, PaperDollSockets> {
            val phase = frameIndex % action.defaultFrames.coerceAtLeast(1)
            val bobY = when (action) {
                PaperDollAction.IDLE -> if (phase % 2 == 1) 1 else 0
                PaperDollAction.WALK -> if (phase == 1 || phase == 3) -1 else 0
                PaperDollAction.RUN -> if (phase == 1 || phase == 3) -2 else 0
                PaperDollAction.SLASH -> if (phase == 1) 1 else 0
                PaperDollAction.THRUST -> if (phase == 1) 1 else 0
                PaperDollAction.SHOOT -> if (phase == 1) -1 else 0
                PaperDollAction.CAST -> if (phase == 1 || phase == 2) -2 else 0
                PaperDollAction.HARVEST -> when (phase) {
                    0 -> -1 // windup overhead
                    1 -> 2  // downward swing
                    2 -> 1  // impact follow-through
                    else -> 0
                }
                PaperDollAction.JUMP -> when (phase) {
                    0 -> 2   // crouch anticipation
                    1 -> -4  // airborne peak
                    else -> 1 // landing absorb
                }
                PaperDollAction.SIT -> if (phase % 2 == 1) 5 else 4
                PaperDollAction.EMOTE -> if (phase == 1) -1 else 0
                PaperDollAction.HURT -> if (phase % 2 == 1) 1 else 0
                PaperDollAction.KO -> if (phase % 2 == 0) 4 else 8
            }

            val stride = when (action) {
                PaperDollAction.WALK -> when (phase) {
                    1 -> 2
                    3 -> -2
                    else -> 0
                }
                PaperDollAction.RUN -> when (phase) {
                    1 -> 3
                    3 -> -3
                    else -> 0
                }
                PaperDollAction.SLASH -> if (phase == 1) 2 else if (phase == 0) -1 else 0
                PaperDollAction.THRUST -> if (phase == 1) 3 else if (phase == 0) -1 else 0
                PaperDollAction.SHOOT -> if (phase == 1) -1 else 0
                else -> 0
            }

            val dirSignX = when (baseDir) {
                Direction8.W, Direction8.SW, Direction8.NW -> -1
                Direction8.E, Direction8.SE, Direction8.NE -> 1
                else -> 0
            }

            val lungeX = when (action) {
                PaperDollAction.SLASH -> if (phase == 1) dirSignX * 2 else 0
                PaperDollAction.THRUST -> if (phase == 1) dirSignX * 3 else 0
                PaperDollAction.RUN -> if (phase == 1 || phase == 3) dirSignX else 0
                PaperDollAction.HURT -> if (phase == 0) -(dirSignX.takeIf { it != 0 } ?: -1) else 0
                else -> 0
            }

            val isSeated = action == PaperDollAction.SIT
            val isAirborne = action == PaperDollAction.JUMP && phase == 1
            val isCollapsed = action == PaperDollAction.KO && phase > 0
            val isRmxp = nativeHeight == 48 || proportions == PaperDollProportions.RMXP_32X48
            val isWide = proportions == PaperDollProportions.CHUNKY || isRmxp
            val shadowRx = when {
                isAirborne -> if (isWide) 7.0f else 5.5f
                isCollapsed -> if (isWide) 10.5f else 9.0f
                else -> if (isWide) 9.0f else 7.5f
            }

            val cx = 16 + lungeX
            val headCy = (if (isRmxp) 17 else if (proportions == PaperDollProportions.CHUNKY) 9 else 10) + bobY
            val torsoTop = (if (isRmxp) 23 else 14) + bobY
            val handReach = if (isWide) 8 else 5
            val mainHandX = when (baseDir) {
                Direction8.W, Direction8.SW, Direction8.NW -> cx - handReach + if (action == PaperDollAction.THRUST && phase == 1) -3 else 0
                else -> cx + handReach
            }
            val offHandX = when (baseDir) {
                Direction8.W, Direction8.SW, Direction8.NW -> cx + (handReach - 1)
                else -> cx - handReach
            }
            val handY = when {
                action == PaperDollAction.CAST && (phase == 1 || phase == 2) -> torsoTop - 3
                action == PaperDollAction.EMOTE && phase == 1 -> torsoTop - 4
                action == PaperDollAction.HARVEST && phase == 0 -> torsoTop - 4
                action == PaperDollAction.HARVEST && phase == 1 -> torsoTop + 6
                else -> torsoTop + 3
            }

            val pose = PaperDollPose(
                action = action,
                frameIndex = frameIndex,
                phase = phase,
                bobY = bobY,
                stride = stride,
                lungeX = lungeX,
                isSeated = isSeated,
                isAirborne = isAirborne,
                isCollapsed = isCollapsed,
                shadowRadiusX = shadowRx
            )

            val sockets = PaperDollSockets(
                pivotX = 16,
                pivotY = if (isRmxp) 45 else 28,
                headX = cx,
                headY = headCy - (if (isRmxp) 6 else 4),
                faceX = cx,
                faceY = headCy + 1,
                backX = cx,
                backY = torsoTop + 4,
                shoulderX = cx,
                shoulderY = torsoTop + 1,
                waistX = cx,
                waistY = torsoTop + (if (isRmxp) 11 else 7),
                mainHandX = mainHandX,
                mainHandY = handY,
                offHandX = offHandX,
                offHandY = torsoTop + 3
            )
            return pose to sockets
        }
    }
}

/**
 * Per-frame metadata for an exported universal paper-doll sheet.
 */
data class PaperDollFrameMeta(
    val action: PaperDollAction,
    val direction: Direction8,
    val frameIndex: Int,
    val col: Int,
    val row: Int,
    val durationMs: Int,
    val sockets: PaperDollSockets
)

/**
 * Complete Universal LPC-style paper-doll bundle containing the composited character sheet,
 * individual aligned layer sheets per [PaperDollSlot], animation sequences, and socket metadata.
 */
data class UniversalPaperDollSheet(
    val name: String,
    val tileSize: Int,
    val columns: Int,
    val rows: Int,
    val actions: List<PaperDollAction>,
    val directions: List<Direction8>,
    val compositeSheet: TileImage,
    val layerSheets: Map<PaperDollSlot, TileImage>,
    val frameMetadata: List<PaperDollFrameMeta>,
    val animations: List<AnimationSequence>,
    val theme: PaperDollTheme,
    val cellWidth: Int = tileSize,
    val cellHeight: Int = tileSize
)
