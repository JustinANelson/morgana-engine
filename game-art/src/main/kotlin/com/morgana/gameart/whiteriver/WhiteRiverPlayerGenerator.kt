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
 * Classic Black Desert Online classes and iconic White River NPCs for a 2D Time Fantasy demake.
 */
enum class WhiteRiverPlayerClass {
    WARRIOR,
    RANGER,
    SORCERESS,
    BERSERKER,
    VALKYRIE,
    WIZARD,
    MUSA,
    DARK_KNIGHT,
    SHAI,
    BLACK_SPIRIT,
    STORAGE_KEEPER,
    STABLE_KEEPER
}

/**
 * Deterministic generator for 16x16-native Time Fantasy player & NPC sprites
 * for the White River (Black Desert Online demake).
 */
class WhiteRiverPlayerGenerator(
    val seed: Long = 1337L,
    val tileSize: Int = 16
) {
    init {
        require(tileSize >= 16 && tileSize % 2 == 0) {
            "tileSize must be an even integer >= 16, got $tileSize"
        }
    }

    fun generatePlayerFrames(
        name: String,
        playerClass: WhiteRiverPlayerClass,
        direction: FacingDirection = FacingDirection.DOWN,
        frameCount: Int = 1,
        style: PixelStyle = PixelStyle.CLASSIC_RPG,
        clip: SpriteAnimationClip = SpriteAnimationClip.WALK
    ): List<NamedTile> {
        require(name.isNotBlank()) { "Player sprite name must not be blank" }
        require(frameCount >= 1) { "frameCount must be >= 1, got $frameCount" }
        return (0 until frameCount).map { frameIdx ->
            val img = generatePlayerFrame(playerClass, direction, frameIdx, style, clip)
            val suffix = if (frameCount == 1) "" else "_f$frameIdx"
            NamedTile("${name}_${direction.name.lowercase()}$suffix", img)
        }
    }

    fun generatePlayerFrame(
        playerClass: WhiteRiverPlayerClass,
        direction: FacingDirection = FacingDirection.DOWN,
        frameIndex: Int = 0,
        style: PixelStyle = PixelStyle.CLASSIC_RPG,
        clip: SpriteAnimationClip = SpriteAnimationClip.WALK
    ): TileImage {
        val (primary, accent) = palettesFor(playerClass, style)
        val rawGrid = templateFor(playerClass, direction)
        val animatedGrid = if (clip == SpriteAnimationClip.WALK) {
            applyWalkStep(rawGrid, direction, frameIndex)
        } else {
            WhiteRiverSpriteUtils.applyHumanoidClipFrame(rawGrid, direction, clip, frameIndex)
        }
        val base16 = WhiteRiverSpriteUtils.renderGrid(animatedGrid, primary, accent)
        return WhiteRiverSpriteUtils.finalizeSprite(base16, tileSize, style, primary)
    }

    /**
     * Generates a complete [AnimationSequence] with per-frame timing, ground pivot, and weapon hand-socket metadata.
     */
    fun generateAnimationSequence(
        name: String,
        playerClass: WhiteRiverPlayerClass,
        clip: SpriteAnimationClip,
        direction: FacingDirection = FacingDirection.DOWN,
        frameCount: Int = clip.defaultFrames,
        style: PixelStyle = PixelStyle.CLASSIC_RPG
    ): AnimationSequence {
        val clipTag = clip.name.lowercase()
        val dirTag = direction.name.lowercase()
        val frames = (0 until frameCount).map { frameIdx ->
            val img = generatePlayerFrame(playerClass, direction, frameIdx, style, clip)
            NamedTile("${name}_${clipTag}_${dirTag}_f$frameIdx", img)
        }
        val scale = tileSize / 16
        val meta = frames.mapIndexed { idx, tile ->
            val handOffsetY = if (clip == SpriteAnimationClip.ATTACK && idx == 1) -scale else 0
            AnimationFrameMeta(
                frameIndex = idx,
                tileName = tile.name,
                durationMs = clip.defaultFrameDurationMs,
                pivotX = tileSize / 2,
                pivotY = 14 * scale,
                handSocketX = (if (direction == FacingDirection.LEFT) 3 else 12) * scale,
                handSocketY = 8 * scale + handOffsetY
            )
        }
        return AnimationSequence(
            name = "${name}_${clipTag}_$dirTag",
            clip = clip,
            direction = direction,
            frames = frames,
            metadata = meta
        )
    }

    fun generateAllPlayersSheet(
        includeDirections: Boolean = true,
        frameCount: Int = 1,
        style: PixelStyle = PixelStyle.CLASSIC_RPG
    ): Pair<TileImage, List<NamedTile>> {
        val dirs = if (includeDirections) FacingDirection.entries else listOf(FacingDirection.DOWN)
        val tiles = mutableListOf<NamedTile>()
        for (dir in dirs) {
            for (frame in 0 until frameCount) {
                for (cls in WhiteRiverPlayerClass.entries) {
                    val img = generatePlayerFrame(cls, dir, frame, style)
                    val frameSuffix = if (frameCount == 1) "" else "_f$frame"
                    val dirSuffix = if (includeDirections) "_${dir.name.lowercase()}" else ""
                    tiles += NamedTile("${cls.name.lowercase()}$dirSuffix$frameSuffix", img)
                }
            }
        }
        val columns = 6
        val rows = (tiles.size + columns - 1) / columns
        val sheet = TileImage(columns * tileSize, rows * tileSize)
        tiles.forEachIndexed { idx, nt ->
            val col = idx % columns
            val row = idx / columns
            sheet.drawImage(nt.image, 0, 0, tileSize, tileSize, col * tileSize, row * tileSize)
        }
        return sheet to tiles
    }

    private fun palettesFor(playerClass: WhiteRiverPlayerClass, style: PixelStyle): Pair<PaletteRamp, PaletteRamp> {
        val pair = when (playerClass) {
            WhiteRiverPlayerClass.WARRIOR -> PalettePresets.DARK_IRON to PalettePresets.RIFT_CRIMSON
            WhiteRiverPlayerClass.RANGER -> PalettePresets.ANCIENT_LEAF to PalettePresets.RELIC_AMBER
            WhiteRiverPlayerClass.SORCERESS -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.RIFT_CRIMSON
            WhiteRiverPlayerClass.BERSERKER -> PalettePresets.RELIC_AMBER to PalettePresets.DARK_IRON
            WhiteRiverPlayerClass.VALKYRIE -> PalettePresets.DARK_IRON to PalettePresets.RELIC_AMBER
            WhiteRiverPlayerClass.WIZARD -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.RELIC_AMBER
            WhiteRiverPlayerClass.MUSA -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.RIFT_CRIMSON
            WhiteRiverPlayerClass.DARK_KNIGHT -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.RIFT_CRIMSON
            WhiteRiverPlayerClass.SHAI -> PalettePresets.ANCIENT_LEAF to PalettePresets.RELIC_AMBER
            WhiteRiverPlayerClass.BLACK_SPIRIT -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.RIFT_CRIMSON
            WhiteRiverPlayerClass.STORAGE_KEEPER -> PalettePresets.DARK_IRON to PalettePresets.RELIC_AMBER
            WhiteRiverPlayerClass.STABLE_KEEPER -> PalettePresets.RELIC_AMBER to PalettePresets.ANCIENT_LEAF
        }
        return if (style == PixelStyle.RETRO_8BIT) {
            pair.first.limitColors(4) to pair.second.limitColors(4)
        } else {
            pair
        }
    }

    private fun templateFor(playerClass: WhiteRiverPlayerClass, direction: FacingDirection): List<String> {
        val down = when (playerClass) {
            WhiteRiverPlayerClass.WARRIOR -> listOf(
                ".....oooooo.....",
                "....ozsssszo....",
                "...ozshhhhszS...",
                "....ozeffezoS...",
                "....ozddddzoS...",
                "..oozrrrrrrzSo..",
                ".zsszmllllmdu...",
                ".zsgzmraarmd....",
                ".zsszdmmmmmd....",
                "..zzo.mmmmmo....",
                "....o.dmddmd....",
                ".....dm..md.....",
                ".....dz..zd.....",
                ".....oo..oo.....",
                "....________....",
                "................"
            )
            WhiteRiverPlayerClass.RANGER -> listOf(
                "....oo...vvo....",
                "...odhhvVvvdo...",
                "...ohmmllllho...",
                "....dfeffefd.U..",
                "....dffFFffd.w..",
                "...odvvvvvvduw..",
                "..ovmlaaaaalmu..",
                "..ovmdaaaaamduw.",
                "...o.dmmmmmd..U.",
                ".....ommmmmo....",
                ".....dm..md.....",
                ".....du..ud.....",
                ".....du..ud.....",
                ".....oo..oo.....",
                "....________....",
                "................"
            )
            WhiteRiverPlayerClass.SORCERESS -> listOf(
                ".....oooooo.....",
                "....oddddddo....",
                "...odhllllhdo...",
                "....dfeffefd....",
                "....dffFFffd....",
                "..oodPPPPPPdoo..",
                ".QPodmllllmdo...",
                ".PPodmPaaPmdo.R.",
                "..o.odmmmmmo..r.",
                "....odmmmmmo....",
                ".....dm..md.....",
                ".....dm..md.....",
                ".....dP..Pd.....",
                ".....oo..oo.....",
                "....________....",
                "................"
            )
            WhiteRiverPlayerClass.BERSERKER -> listOf(
                "..oo........oo..",
                "..oSSooooooSSo..",
                "...ohhhhhhhho...",
                "...omfReeRfmo...",
                "...omfFFFFfmo...",
                "..oodmmmmmmddo..",
                ".SSomllllllmoSS.",
                ".SsomlaaaalmosS.",
                ".s.odmmmmmmmo.s.",
                "....odmddmdo....",
                "....odm..mdo....",
                "....odm..mdo....",
                "....ozu..uzo....",
                "....ooo..ooo....",
                "...__________...",
                "................"
            )
            WhiteRiverPlayerClass.VALKYRIE -> listOf(
                "..oG...SS...Go..",
                "..oGGhhhhhhGGo..",
                "...ohllllllhoS..",
                "....omfFFfmo.S..",
                "....omfeeefo.s..",
                "..oogGGGGGGgoS..",
                ".gGGgmllllmd.s..",
                ".gGxGmlaalmduw..",
                ".gGGgdmmmmmd.w..",
                "..ggo.mmmmmo.w..",
                "....o.dmddmd.o..",
                ".....dm..md.....",
                ".....dg..gd.....",
                ".....oo..oo.....",
                "....________....",
                "................"
            )
            WhiteRiverPlayerClass.WIZARD -> listOf(
                ".......dd.......",
                "......dhhd......",
                ".....dhhmmd.....",
                "...ddmmmmmmdd...",
                "..dggggggggggo..",
                "....dfeffefo.c..",
                "....dxxxxxxo.G..",
                "...ddmggggmd.w..",
                "..dhhmllllmddw..",
                "..dmmmlgglmmmw..",
                "..dmomllllmomw..",
                "..do.dmmmmmo.o..",
                ".....dmmmmmo....",
                ".....du..uo.....",
                "....________....",
                "................"
            )
            WhiteRiverPlayerClass.MUSA -> listOf(
                "...oooooooooo...",
                "..oddddddddddo..",
                "....oorrrrroo...",
                "....omfFFfmo.S..",
                "....omfeeefo.S..",
                "...oodrrrrrdoS..",
                "..odmmllllmdds..",
                "..odmmlrrlmduw..",
                "..o..dmmmmmd.w..",
                ".....ommmmmo.o..",
                ".....dmddmd.....",
                ".....dm..md.....",
                ".....dz..zd.....",
                ".....oo..oo.....",
                "....________....",
                "................"
            )
            WhiteRiverPlayerClass.DARK_KNIGHT -> listOf(
                "...oP......Po...",
                "...oPhhhhhhPo...",
                "...ohllllllhoQ..",
                "....dfeffefd.P..",
                "....dffFFffd.S..",
                "...ooPPPPPPooS..",
                "..oddmllllmdPS..",
                "..oddmPaaPmdQs..",
                "..o..dmmmmmo.u..",
                ".....dmmmmmo.o..",
                ".....dm..md.....",
                ".....dm..md.....",
                ".....dP..Pd.....",
                ".....oo..oo.....",
                "....________....",
                "................"
            )
            WhiteRiverPlayerClass.SHAI -> listOf(
                ".....vvvvvv.....",
                "....vVVVVVVv....",
                "...ohhhhhhhho...",
                "...ohllllllhoU..",
                "....dfeffefd.w..",
                "....dffFFffd.v..",
                "...ooaaaaaaaoU..",
                "..ohhmllllmhhw..",
                "..o.ommmmmmo.U..",
                "....odmmmmmo....",
                ".....dm..md.....",
                ".....du..ud.....",
                ".....du..ud.....",
                ".....oo..oo.....",
                "....________....",
                "................"
            )
            WhiteRiverPlayerClass.BLACK_SPIRIT -> listOf(
                ".....o....o.....",
                "...ooddddddoo...",
                "..oddddddddddo..",
                ".ooddRddddRddoo.",
                ".oddeReeedReedo.",
                ".oddeRReddRRedo.",
                ".oddddddddddddo.",
                "..oddWddddWddo..",
                "..odddWWWWdddo..",
                "..oddddddddddo..",
                "...ooddRdddoo...",
                "....oo.dd.oo....",
                ".....o.oo.o.....",
                "................",
                "....________....",
                "................"
            )
            WhiteRiverPlayerClass.STORAGE_KEEPER -> listOf(
                "....oooooooo....",
                "...oddddddddo...",
                "...odggggggdo...",
                "....dfeffefd....",
                "....dffFFffd....",
                "...ooduuuuudoo..",
                "..odmmuuuuummo..",
                "..odmUGGGGUmdg..",
                "..o.omuuuuummG..",
                "....odmmmmmo.y..",
                ".....dmddmd.....",
                ".....dm..md.....",
                ".....du..ud.....",
                ".....oo..oo.....",
                "....________....",
                "................"
            )
            WhiteRiverPlayerClass.STABLE_KEEPER -> listOf(
                "...oooooooooo...",
                "..ouuuuuuuuuuo..",
                "....ohhhhhho....",
                "....omfFFfmo....",
                "....omfeeefo....",
                "...oouuuuuuuoo..",
                "..ohhmllllmhws..",
                "..ohhmwllwmhw...",
                "..o.ommmmmmoU...",
                "....odmmmmmo....",
                ".....dm..md.....",
                ".....du..ud.....",
                ".....du..ud.....",
                ".....oo..oo.....",
                "....________....",
                "................"
            )
        }

        return when (direction) {
            FacingDirection.DOWN -> down
            FacingDirection.UP -> toBackView(down)
            FacingDirection.LEFT -> toSideView(down, playerClass)
            FacingDirection.RIGHT -> WhiteRiverSpriteUtils.mirrorGridX(toSideView(down, playerClass))
        }
    }

    private fun toBackView(down: List<String>): List<String> = down.mapIndexed { y, row ->
        if (y in 3..5) {
            row.replace('e', 'd').replace('W', 'd').replace('f', 'm').replace('F', 'm').replace('R', 'd')
        } else {
            row
        }
    }

    private fun toSideView(down: List<String>, cls: WhiteRiverPlayerClass): List<String> = down.mapIndexed { y, row ->
        if (cls == WhiteRiverPlayerClass.BLACK_SPIRIT) {
            row
        } else {
            when (y) {
                in 1..2 -> ".....${row.substring(5, 11)}....."
                3 -> ".....dmfefd....."
                4 -> ".....dmfFFd....."
                in 6..10 -> {
                    val core = row.substring(4, 12)
                    val weapon = when (cls) {
                        WhiteRiverPlayerClass.WARRIOR -> "S"
                        WhiteRiverPlayerClass.RANGER -> "w"
                        WhiteRiverPlayerClass.SORCERESS -> "P"
                        WhiteRiverPlayerClass.BERSERKER -> "s"
                        WhiteRiverPlayerClass.VALKYRIE -> "G"
                        WhiteRiverPlayerClass.WIZARD -> "G"
                        WhiteRiverPlayerClass.MUSA -> "S"
                        WhiteRiverPlayerClass.DARK_KNIGHT -> "P"
                        WhiteRiverPlayerClass.SHAI -> "g"
                        WhiteRiverPlayerClass.BLACK_SPIRIT -> "d"
                        WhiteRiverPlayerClass.STORAGE_KEEPER -> "g"
                        WhiteRiverPlayerClass.STABLE_KEEPER -> "w"
                    }
                    "...$weapon$core...."
                }
                else -> row
            }
        }
    }

    private fun applyWalkStep(grid: List<String>, dir: FacingDirection, frameIndex: Int): List<String> {
        val phase = frameIndex % 4
        if (phase == 0) return grid
        if (phase == 2) return WhiteRiverSpriteUtils.applyHumanoidClipFrame(grid, dir, SpriteAnimationClip.WALK, 2)
        val out = grid.toMutableList()
        if (dir == FacingDirection.LEFT || dir == FacingDirection.RIGHT) {
            for (y in 11..13) {
                val r = out[y].toCharArray()
                val tmp = r[5]
                r[5] = r[6]
                r[6] = tmp
                val tmp2 = r[9]
                r[9] = r[10]
                r[10] = tmp2
                out[y] = String(r)
            }
        } else {
            out[11] = grid[12]
            out[12] = grid[11]
            if (phase == 3) {
                out[10] = out[10].reversed()
            }
        }
        return out
    }
}
