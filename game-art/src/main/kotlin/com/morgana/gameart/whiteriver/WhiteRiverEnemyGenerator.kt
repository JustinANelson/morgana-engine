package com.morgana.gameart.whiteriver

import com.morgana.tilegen.*
import com.morgana.tilegen.material.*
import com.morgana.tilegen.spec.*
import com.morgana.tilegen.sprite.*
import com.morgana.tilegen.structure.*
import com.morgana.tilegen.export.*
import com.morgana.tilegen.autotile.*
import com.morgana.gameart.sprite.*
import com.morgana.gameart.export.*

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.sprite.AnimationFrameMeta
import com.morgana.tilegen.sprite.AnimationSequence
import com.morgana.tilegen.sprite.FacingDirection
import com.morgana.tilegen.sprite.SpriteAnimationClip

/**
 * Iconic monsters and World Bosses from Black Desert Online for the White River demake.
 */
enum class WhiteRiverEnemyKind {
    GRASS_BEETLE,
    WEASEL,
    IMP,
    RED_NOSE,
    ALTAR_IMP,
    SCARECROW,
    FOGAN,
    NAGA,
    ORC,
    SKELETON_WARRIOR,
    HEXE_MARIE,
    CATFISHMAN,
    TREANT,
    CYCLOPS,
    SAUSAN_GUARD,
    HELMS_GOLEM,
    CENTAUR,
    CRESCENT_GUARDIAN,
    FOGAN_DESERT,
    KZARKA,
    KARANDA,
    KUTUM,
    NOUVER,
    VELL,
    GARMOTH,
    OFFIN,
    SHADOW_KNIGHT,
    BANDIT_GAHAZ
}

/**
 * Deterministic generator for 16x16-native Time Fantasy enemy & world-boss sprites
 * for the White River (Black Desert Online demake).
 */
class WhiteRiverEnemyGenerator(
    val seed: Long = 1337L,
    val tileSize: Int = 16
) {
    init {
        require(tileSize >= 16 && tileSize % 2 == 0) {
            "tileSize must be an even integer >= 16, got $tileSize"
        }
    }

    fun generateEnemyFrames(
        name: String,
        kind: WhiteRiverEnemyKind,
        frameCount: Int = 1,
        style: PixelStyle = PixelStyle.CLASSIC_RPG,
        clip: SpriteAnimationClip = SpriteAnimationClip.WALK
    ): List<NamedTile> {
        require(name.isNotBlank()) { "Enemy name must not be blank" }
        require(frameCount >= 1) { "frameCount must be >= 1, got $frameCount" }
        return (0 until frameCount).map { frameIdx ->
            val img = generateEnemyFrame(kind, frameIdx, style, clip)
            val tileName = if (frameCount == 1) name else "${name}_f$frameIdx"
            NamedTile(tileName, img)
        }
    }

    fun generateEnemyFrame(
        kind: WhiteRiverEnemyKind,
        frameIndex: Int = 0,
        style: PixelStyle = PixelStyle.CLASSIC_RPG,
        clip: SpriteAnimationClip = SpriteAnimationClip.WALK
    ): TileImage {
        val (primary, accent) = palettesFor(kind, style)
        val raw = templateFor(kind)
        val grid = WhiteRiverSpriteUtils.applyEnemyClipFrame(raw, clip, frameIndex)
        val base16 = WhiteRiverSpriteUtils.renderGrid(grid, primary, accent)
        return WhiteRiverSpriteUtils.finalizeSprite(base16, tileSize, style, primary)
    }

    fun generateAnimationSequence(
        name: String,
        kind: WhiteRiverEnemyKind,
        clip: SpriteAnimationClip = SpriteAnimationClip.WALK,
        frameCount: Int = clip.defaultFrames,
        style: PixelStyle = PixelStyle.CLASSIC_RPG
    ): AnimationSequence {
        val clipTag = clip.name.lowercase()
        val frames = (0 until frameCount).map { idx ->
            NamedTile("${name}_${clipTag}_f$idx", generateEnemyFrame(kind, idx, style, clip))
        }
        val scale = tileSize / 16
        val meta = frames.mapIndexed { idx, tile ->
            AnimationFrameMeta(
                frameIndex = idx,
                tileName = tile.name,
                durationMs = clip.defaultFrameDurationMs,
                pivotX = tileSize / 2,
                pivotY = 13 * scale
            )
        }
        return AnimationSequence(
            name = "${name}_$clipTag",
            clip = clip,
            direction = FacingDirection.DOWN,
            frames = frames,
            metadata = meta
        )
    }

    fun generateAllEnemiesSheet(
        frameCount: Int = 1,
        style: PixelStyle = PixelStyle.CLASSIC_RPG
    ): Pair<TileImage, List<NamedTile>> {
        val tiles = WhiteRiverEnemyKind.entries.flatMap { kind ->
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

    private fun palettesFor(kind: WhiteRiverEnemyKind, style: PixelStyle): Pair<PaletteRamp, PaletteRamp> {
        val pair = when (kind) {
            WhiteRiverEnemyKind.GRASS_BEETLE -> PalettePresets.ANCIENT_LEAF to PalettePresets.RELIC_AMBER
            WhiteRiverEnemyKind.WEASEL -> PalettePresets.RELIC_AMBER to PalettePresets.DARK_IRON
            WhiteRiverEnemyKind.IMP -> PalettePresets.DARK_IRON to PalettePresets.RIFT_CRIMSON
            WhiteRiverEnemyKind.RED_NOSE -> PalettePresets.RIFT_CRIMSON to PalettePresets.DARK_IRON
            WhiteRiverEnemyKind.ALTAR_IMP -> PalettePresets.ANCIENT_LEAF to PalettePresets.RIFT_CRIMSON
            WhiteRiverEnemyKind.SCARECROW -> PalettePresets.RELIC_AMBER to PalettePresets.ABYSSAL_OBSIDIAN
            WhiteRiverEnemyKind.FOGAN -> PalettePresets.ANCIENT_LEAF to PalettePresets.DARK_IRON
            WhiteRiverEnemyKind.NAGA -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.ANCIENT_LEAF
            WhiteRiverEnemyKind.ORC -> PalettePresets.RIFT_CRIMSON to PalettePresets.DARK_IRON
            WhiteRiverEnemyKind.SKELETON_WARRIOR -> PalettePresets.DARK_IRON to PalettePresets.ABYSSAL_OBSIDIAN
            WhiteRiverEnemyKind.HEXE_MARIE -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.RIFT_CRIMSON
            WhiteRiverEnemyKind.CATFISHMAN -> PalettePresets.DARK_IRON to PalettePresets.RELIC_AMBER
            WhiteRiverEnemyKind.TREANT -> PalettePresets.ANCIENT_LEAF to PalettePresets.RELIC_AMBER
            WhiteRiverEnemyKind.CYCLOPS -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.RELIC_AMBER
            WhiteRiverEnemyKind.SAUSAN_GUARD -> PalettePresets.DARK_IRON to PalettePresets.RELIC_AMBER
            WhiteRiverEnemyKind.HELMS_GOLEM -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.RIFT_CRIMSON
            WhiteRiverEnemyKind.CENTAUR -> PalettePresets.RELIC_AMBER to PalettePresets.DARK_IRON
            WhiteRiverEnemyKind.CRESCENT_GUARDIAN -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.RELIC_AMBER
            WhiteRiverEnemyKind.FOGAN_DESERT -> PalettePresets.RELIC_AMBER to PalettePresets.ANCIENT_LEAF
            WhiteRiverEnemyKind.KZARKA -> PalettePresets.RIFT_CRIMSON to PalettePresets.ABYSSAL_OBSIDIAN
            WhiteRiverEnemyKind.KARANDA -> PalettePresets.DARK_IRON to PalettePresets.RELIC_AMBER
            WhiteRiverEnemyKind.KUTUM -> PalettePresets.RELIC_AMBER to PalettePresets.ABYSSAL_OBSIDIAN
            WhiteRiverEnemyKind.NOUVER -> PalettePresets.RELIC_AMBER to PalettePresets.RIFT_CRIMSON
            WhiteRiverEnemyKind.VELL -> PalettePresets.OCEAN_WATER to PalettePresets.DARK_IRON
            WhiteRiverEnemyKind.GARMOTH -> PalettePresets.RIFT_CRIMSON to PalettePresets.RELIC_AMBER
            WhiteRiverEnemyKind.OFFIN -> PalettePresets.ANCIENT_LEAF to PalettePresets.RELIC_AMBER
            WhiteRiverEnemyKind.SHADOW_KNIGHT -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.RIFT_CRIMSON
            WhiteRiverEnemyKind.BANDIT_GAHAZ -> PalettePresets.RELIC_AMBER to PalettePresets.ABYSSAL_OBSIDIAN
        }
        return if (style == PixelStyle.RETRO_8BIT) {
            pair.first.limitColors(4) to pair.second.limitColors(4)
        } else {
            pair
        }
    }

    private fun templateFor(kind: WhiteRiverEnemyKind): List<String> = when (kind) {
        WhiteRiverEnemyKind.GRASS_BEETLE -> listOf(
            "................",
            "................",
            "......o..o......",
            ".....ohhhho.....",
            "....ohheeho.....",
            "...oodmmmmddo...",
            "..ohdmmAAmmdho..",
            "..odmmmAAmmmdo..",
            "...ooddddddoo...",
            "....o.o..o.o....",
            "...o..o..o..o...",
            "....________....",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.WEASEL -> listOf(
            "................",
            "....oo....oo....",
            "...ohho..ohho...",
            "....oohhhhoo....",
            "....omfeefmo....",
            ".....ommmmo.....",
            "...oodmllmmdo...",
            "...ohdmmmmmdho..",
            "....odmmmmmdo...",
            ".....dm..md.oo..",
            ".....oo..oo..oo.",
            "....________....",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.IMP -> listOf(
            "....oo....oo....",
            "...ohho..ohho...",
            "....oohhhhoo....",
            "....omReeRmo....",
            "....omWWWWmo....",
            "...oodmaamdoo...",
            "..w.odmmmmmdo...",
            "..w.odmmmmmdo...",
            "....odmmmmmo....",
            ".....dm..md.....",
            ".....oo..oo.....",
            "....________....",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.RED_NOSE -> listOf(
            "...oo......oo...",
            "...ohoooooooho..",
            "....ohhhhhhho...",
            "....omReRRReo...",
            "....ommmRRmmo...",
            "..oodmmWWWWddo..",
            ".SSommmmmmmmoSS.",
            ".SsommlaalmmoSs.",
            "..s.odmmmmmo.s..",
            "....odmddmdo....",
            "....odm..mdo....",
            "....ooo..ooo....",
            "..____________..",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.ALTAR_IMP -> listOf(
            "....oo....oo....",
            "...ohho..ohho...",
            "....oohhhhoo....",
            "....omReeRmo....",
            "....omaaaaao....",
            "...oodmllmmdo.S.",
            "..o.odmmmmmdo.s.",
            "..o.odmmmmmdo.w.",
            "....odmmmmmo..w.",
            ".....dm..md...w.",
            ".....oo..oo.....",
            "....________....",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.SCARECROW -> listOf(
            "......oooo......",
            "....oohhhhoo....",
            "...oohhhhhhoo...",
            "....omReeRmo....",
            "....ommmmmmo....",
            "..wwodmaamdoww..",
            "..ww.dmmmmmd.ww.",
            ".....dmmmmmo....",
            "......owwo......",
            "......owwo......",
            "......oooo......",
            "....________....",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.FOGAN -> listOf(
            "....oo....oo....",
            "...oWWo..oWWo...",
            "....oohhhhoo....",
            "....ommmmmmo....",
            "...oodmTTmdoo...",
            "..o.odmmmmmdo.S.",
            "..o.odmmmmmdo.w.",
            "....odmmmmmo..w.",
            ".....dm..md.....",
            "....oV....Vo....",
            "....________....",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.NAGA -> listOf(
            ".....oooooo.....",
            "....ohhhhhho....",
            "...oohReeRhoo...",
            "....odmmmmmo....",
            "...oodmaamdoo...",
            "..SSomllllmoSS..",
            "..SsommmmmmoSs..",
            "....odmmmmmo....",
            "....odmmddo.....",
            "...odmmoo.......",
            "...oddoo........",
            "....________....",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.ORC -> listOf(
            "....oooooooo....",
            "...ohhhhhhhho...",
            "...omdReeRdmo...",
            "...ommWWWWmmo...",
            "..oodmmmmmmddo..",
            ".SSomlaaaalmoSS.",
            ".SsomllllllmoSs.",
            "..s.odmmmmmo.s..",
            "....odmddmdo....",
            "....odm..mdo....",
            "....ooo..ooo....",
            "..____________..",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.SKELETON_WARRIOR -> listOf(
            "....o......o....",
            "....ozoooozo....",
            "....ozhhhhzo....",
            "....oWceecWo....",
            "....odWWWWdo.S..",
            "..oouuzzzzdo.S..",
            ".uwwuzWWWWzo.s..",
            ".uwguzddddzo.u..",
            ".uwwuoWWWWoo....",
            "..oo..W..W......",
            "......z..z......",
            ".....oo..oo.....",
            "....________....",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.HEXE_MARIE -> listOf(
            "......oooo......",
            ".....oddddo.....",
            "...ooddddddoo...",
            "....ohceecho....",
            "...oodmmmmddo...",
            "..oTTmllllmTTo..",
            "..oT.dmmmmmo.T..",
            ".....dmmmmmo....",
            ".....dmTTmd.....",
            ".....od..do.....",
            ".....oo..oo.....",
            "....________....",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.CATFISHMAN -> listOf(
            "....oooooooo....",
            "...ohhhhhhhho...",
            "..oohheeeehhoo..",
            "..ommmmmmmmmmo..",
            "..odmaaaaaamdo..",
            ".oodmmmmmmmmddo.",
            ".ohdmmmmmmmmdho.",
            "..ooddddddddoo..",
            "....odm..mdo....",
            "....ooo..ooo....",
            "..____________..",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.TREANT -> listOf(
            "...oooooooooo...",
            "..oVVVVVVVVVVo..",
            "..oVVhhhhhhVVo..",
            "...oohReeRhoo...",
            "...odmmmmmmmo...",
            "..oodmVVVVmdoo..",
            "..ohdmmmmmmhdo..",
            "...odmmmmmmmo...",
            "....odm..mdo....",
            "....odm..mdo....",
            "....ooo..ooo....",
            "..____________..",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.CYCLOPS -> listOf(
            "...oooooooooo...",
            "..ohhhhhhhhhho..",
            "..ohlllWWlllho..",
            "..ommmmRRmmmmo..",
            ".oommmmmmmmmmoo.",
            ".ohdmmmmmmmmdho.",
            ".odmmmmmmmmmmmo.",
            "..o.odmddmdo.o..",
            "....odm..mdo....",
            "....odm..mdo....",
            "....ooo..ooo....",
            "..____________..",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.SAUSAN_GUARD -> listOf(
            "......orRo......",
            ".....ozzzzo.....",
            "....ozhhhhzo.S..",
            "....omfeefmo.S..",
            "...oodrrrrdo.w..",
            "..zzomllllmd.w..",
            "..zgomlrrlmduw..",
            "..zzodmmmmmo.w..",
            "....odmddmdo....",
            ".....dm..md.....",
            ".....dz..zd.....",
            ".....oo..oo.....",
            "....________....",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.HELMS_GOLEM -> listOf(
            "..oooo....oooo..",
            ".oddddo..oddddo.",
            ".odhRRdoohRRhdo.",
            "..odmmRRRRmmdo..",
            ".ooddmmRRmmddoo.",
            "odhhdmRRRRmdhhdo",
            "odmmdmRRRRmdmmdo",
            "odmmodmmmmddmmdo",
            ".oo..oddddo..oo.",
            "....odm..mdo....",
            "....odR..Rdo....",
            "....ooo..ooo....",
            "..____________..",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.CENTAUR -> listOf(
            "......oooo......",
            ".....ohhhho.....",
            ".....omfeefo....",
            "....oodmmmdo.S..",
            "..ooommGGmmo.w..",
            ".ohdmmmmmmmmdw..",
            ".odmmmmmmmmmdo..",
            "..o.odm..mdo....",
            "....odm..mdo....",
            "....ooo..ooo....",
            "..____________..",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.CRESCENT_GUARDIAN -> listOf(
            "...oG......Go...",
            "....oGGGGGGoo...",
            "....odhhhhdo....",
            "....omReeRmo....",
            "...oodyyyyddo...",
            ".SSodmGGGGmdoSS.",
            "S..odmlGGlmdo..S",
            "s...odmmmmmo...s",
            ".....dmddmd.....",
            ".....dm..md.....",
            ".....dy..yd.....",
            ".....oo..oo.....",
            "....________....",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.FOGAN_DESERT -> listOf(
            "....oo....oo....",
            "...oWWo..oWWo...",
            "....oohhhhoo....",
            "....ommmmmmo....",
            "...oodmVVmdoo...",
            "..o.odmmmmmdo.S.",
            "..o.odmmmmmdo.w.",
            "....odmmmmmo..w.",
            ".....dm..md.....",
            "....oG....Go....",
            "....________....",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.KZARKA -> listOf(
            ".oR..oG..Go..Ro.",
            ".oRRoGooooGoRRo.",
            "..oRohhhhhhoRo..",
            "...omRReeRRmo...",
            "..oohmmRRmmhoo..",
            ".oRRdmmGGmmdRRo.",
            "oRG.ommmmmmo.GRo",
            "oG..odmRRmdo..Go",
            "...oodmmmmddo...",
            "..oqqrRRRRrqqo..",
            ".oqRRRRRRRRRRqo.",
            "..oooooooooooo..",
            "..____________..",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.KARANDA -> listOf(
            "......oooo......",
            ".....ohhhho.....",
            "....ohceecho....",
            "..ooohTTTTthooo.",
            ".oTTTmmmmmmTTTo.",
            ".oTTdmmmmmmTTo..",
            "..oodmmmmmdoo...",
            "....odmmmmmo....",
            ".....dm..md.....",
            ".....yg..gy.....",
            ".....oo..oo.....",
            "....________....",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.KUTUM -> listOf(
            "...oooooooooo...",
            "..ohhhhhhhhhho..",
            "..ohllTTTTllho..",
            "..omdTeeeeTdmo..",
            ".oommmmmmmmmmoo.",
            ".ohdmmTTTTmmdho.",
            ".odmmmmmmmmmmmo.",
            "..ooddddddddoo..",
            "...oddddddddo...",
            "....oooooooo....",
            "..____________..",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.NOUVER -> listOf(
            "...oG......Go...",
            "..oGGoo..ooGGo..",
            ".oGohhhhhhhhoGo.",
            "..oohRelleRhoo..",
            ".oAAdmhhhhmdAAo.",
            "oAAldmmGGmmddAAo",
            "oA.odmmmmmmmo.Ao",
            "...odmmGGmmmo...",
            "..oodmddddmdoo..",
            ".oGGo.m..m.oGGo.",
            "..oo..o..o..oo..",
            "...yyggggggyy...",
            "..____________..",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.VELL -> listOf(
            "...oT......To...",
            "..oTToooooTTo...",
            "...ohhhhhhhho...",
            "...ohmceecmho...",
            "..oohmmmmmmhoo..",
            ".oTTdmmTTmmdTTo.",
            ".oTTommmmmmoTTo.",
            "..oo.ommmmo.oo..",
            "....odmmmmmo....",
            "...ooddddddoo...",
            "..____________..",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.GARMOTH -> listOf(
            ".ox..........xo.",
            ".oxxo..oo..oxxo.",
            "..oxohhhhhhoxo..",
            "..oRRhEeeEhRRo..",
            ".oRRRmRRRRmRRRo.",
            "oxRRdmRRRRmdRRxo",
            "o.oodmRRRRmdoo.o",
            "...odmGGGGmmo...",
            "...odmmmmmmmo...",
            "..oodm....mdoo..",
            "..ox.dm..md.xo..",
            ".....oo..oo.....",
            "..____________..",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.OFFIN -> listOf(
            "...oooooooooo...",
            "..oVVhhhhhhVVo..",
            "..ohllTTTTllho..",
            "..omdTeeeeTdmo..",
            ".oommmmmmmmmmoo.",
            ".ohdmmTTTTmmdho.",
            ".odmmmmmmmmmmmo.",
            "..o.odmddmdo.o..",
            "....odm..mdo....",
            "....odm..mdo....",
            "....ooo..ooo....",
            "..____________..",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.SHADOW_KNIGHT -> listOf(
            ".....oooooo.....",
            "....oddddddo....",
            "...oddhhhhddoS..",
            "....odReeRdo.S..",
            "...oodPPPPdooS..",
            "..zzomllllmoPs..",
            "..zPomlRRlmo.u..",
            "..zzodmmmmmo....",
            "....odmddmdo....",
            ".....dm..md.....",
            ".....dz..zd.....",
            ".....oo..oo.....",
            "....________....",
            "................",
            "................",
            "................"
        )
        WhiteRiverEnemyKind.BANDIT_GAHAZ -> listOf(
            "....oooooooo....",
            "...ohhhhhhhho...",
            "...ohllllllho...",
            "....omfeeefo....",
            "...oorrrrrrro...",
            "..oodmGGGGmdoS..",
            "..u.omlrrlmoSS..",
            "..o.ommmmmmos...",
            "....odmmmmmo....",
            ".....dm..md.....",
            ".....du..ud.....",
            ".....oo..oo.....",
            "....________....",
            "................",
            "................",
            "................"
        )
    }
}
