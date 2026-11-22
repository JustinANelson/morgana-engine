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

/**
 * Iconic enhancing stones, boss gear, accessories, consumables, and lifeskill items
 * from Black Desert Online for the White River demake.
 */
enum class WhiteRiverItemKind {
    BLACK_STONE_WEAPON,
    BLACK_STONE_ARMOR,
    CONCENTRATED_BLACK_STONE,
    MEMORY_FRAGMENT,
    CRON_STONE,
    CAPHRAS_STONE,
    SILVER_COIN,
    GOLD_BAR,
    KZARKA_LONGSWORD,
    DANDELION_GREATSWORD,
    KUTUM_SHIELD,
    DIM_TREE_ARMOR,
    BHEG_GLOVES,
    URUGON_SHOES,
    GRIFFON_HELMET,
    OGRE_RING,
    TUNGRAD_EARRING,
    BASILISK_BELT,
    HP_POTION_EXTRA,
    MP_POTION_EXTRA,
    CRON_MEAL,
    BEER,
    ELIXIR_FRENZY,
    MAGIC_CRYSTAL,
    WHEAT_CRATE,
    FISH_COELACANTH,
    FISHING_ROD,
    MATCHLOCK,
    ALCHEMY_STONE,
    HORSE_EMBLEM,
    BLACK_SPIRIT_CLAW,
    ANCIENT_RELIC,
    VORTEX_SCROLL,
    ENERGY_TONIC,
    LUMBER_AXE,
    FLUID_COLLECTOR
}

/**
 * Deterministic generator for 16x16-native Time Fantasy item icons
 * for the White River (Black Desert Online demake).
 */
class WhiteRiverItemGenerator(
    val seed: Long = 1337L,
    val tileSize: Int = 16
) {
    init {
        require(tileSize >= 16 && tileSize % 2 == 0) {
            "tileSize must be an even integer >= 16, got $tileSize"
        }
    }

    fun generateItem(kind: WhiteRiverItemKind, style: PixelStyle = PixelStyle.CLASSIC_RPG): TileImage =
        generateItemFrame(kind, 0, style)

    fun generateItemFrame(
        kind: WhiteRiverItemKind,
        frameIndex: Int = 0,
        style: PixelStyle = PixelStyle.CLASSIC_RPG
    ): TileImage {
        val (primary, accent) = palettesFor(kind, style)
        val raw = templateFor(kind)
        val grid = WhiteRiverSpriteUtils.applyLootBobAndGlint(raw, frameIndex)
        val base16 = WhiteRiverSpriteUtils.renderGrid(grid, primary, accent)
        return WhiteRiverSpriteUtils.finalizeSprite(base16, tileSize, style, primary)
    }

    fun generateItemFrames(
        name: String,
        kind: WhiteRiverItemKind,
        frameCount: Int = 1,
        style: PixelStyle = PixelStyle.CLASSIC_RPG
    ): List<NamedTile> {
        require(name.isNotBlank()) { "Item name must not be blank" }
        require(frameCount >= 1) { "frameCount must be >= 1, got $frameCount" }
        return (0 until frameCount).map { idx ->
            val suffix = if (frameCount == 1) "" else "_f$idx"
            NamedTile("$name$suffix", generateItemFrame(kind, idx, style))
        }
    }

    fun generateAllItemsSheet(
        style: PixelStyle = PixelStyle.CLASSIC_RPG,
        frameCount: Int = 1
    ): Pair<TileImage, List<NamedTile>> {
        val tiles = WhiteRiverItemKind.entries.flatMap { kind ->
            generateItemFrames(kind.name.lowercase(), kind, frameCount, style)
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

    private fun palettesFor(kind: WhiteRiverItemKind, style: PixelStyle): Pair<PaletteRamp, PaletteRamp> {
        val pair = when (kind) {
            WhiteRiverItemKind.BLACK_STONE_WEAPON -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.RIFT_CRIMSON
            WhiteRiverItemKind.BLACK_STONE_ARMOR -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.ANCIENT_LEAF
            WhiteRiverItemKind.CONCENTRATED_BLACK_STONE -> PalettePresets.RIFT_CRIMSON to PalettePresets.RELIC_AMBER
            WhiteRiverItemKind.MEMORY_FRAGMENT -> PalettePresets.DARK_IRON to PalettePresets.RELIC_AMBER
            WhiteRiverItemKind.CRON_STONE -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.RELIC_AMBER
            WhiteRiverItemKind.CAPHRAS_STONE -> PalettePresets.ANCIENT_LEAF to PalettePresets.RIFT_CRIMSON
            WhiteRiverItemKind.SILVER_COIN -> PalettePresets.DARK_IRON to PalettePresets.ABYSSAL_OBSIDIAN
            WhiteRiverItemKind.GOLD_BAR -> PalettePresets.RELIC_AMBER to PalettePresets.DARK_IRON
            WhiteRiverItemKind.KZARKA_LONGSWORD -> PalettePresets.RIFT_CRIMSON to PalettePresets.RELIC_AMBER
            WhiteRiverItemKind.DANDELION_GREATSWORD -> PalettePresets.DARK_IRON to PalettePresets.RELIC_AMBER
            WhiteRiverItemKind.KUTUM_SHIELD -> PalettePresets.RELIC_AMBER to PalettePresets.ABYSSAL_OBSIDIAN
            WhiteRiverItemKind.DIM_TREE_ARMOR -> PalettePresets.ANCIENT_LEAF to PalettePresets.RELIC_AMBER
            WhiteRiverItemKind.BHEG_GLOVES -> PalettePresets.DARK_IRON to PalettePresets.RIFT_CRIMSON
            WhiteRiverItemKind.URUGON_SHOES -> PalettePresets.DARK_IRON to PalettePresets.RELIC_AMBER
            WhiteRiverItemKind.GRIFFON_HELMET -> PalettePresets.RELIC_AMBER to PalettePresets.DARK_IRON
            WhiteRiverItemKind.OGRE_RING -> PalettePresets.RIFT_CRIMSON to PalettePresets.RELIC_AMBER
            WhiteRiverItemKind.TUNGRAD_EARRING -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.RELIC_AMBER
            WhiteRiverItemKind.BASILISK_BELT -> PalettePresets.ANCIENT_LEAF to PalettePresets.RELIC_AMBER
            WhiteRiverItemKind.HP_POTION_EXTRA -> PalettePresets.RIFT_CRIMSON to PalettePresets.DARK_IRON
            WhiteRiverItemKind.MP_POTION_EXTRA -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.DARK_IRON
            WhiteRiverItemKind.CRON_MEAL -> PalettePresets.RELIC_AMBER to PalettePresets.RIFT_CRIMSON
            WhiteRiverItemKind.BEER -> PalettePresets.RELIC_AMBER to PalettePresets.DARK_IRON
            WhiteRiverItemKind.ELIXIR_FRENZY -> PalettePresets.RIFT_CRIMSON to PalettePresets.ABYSSAL_OBSIDIAN
            WhiteRiverItemKind.MAGIC_CRYSTAL -> PalettePresets.RIFT_CRIMSON to PalettePresets.ABYSSAL_OBSIDIAN
            WhiteRiverItemKind.WHEAT_CRATE -> PalettePresets.RELIC_AMBER to PalettePresets.DARK_IRON
            WhiteRiverItemKind.FISH_COELACANTH -> PalettePresets.OCEAN_WATER to PalettePresets.RELIC_AMBER
            WhiteRiverItemKind.FISHING_ROD -> PalettePresets.RELIC_AMBER to PalettePresets.DARK_IRON
            WhiteRiverItemKind.MATCHLOCK -> PalettePresets.DARK_IRON to PalettePresets.RELIC_AMBER
            WhiteRiverItemKind.ALCHEMY_STONE -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.RELIC_AMBER
            WhiteRiverItemKind.HORSE_EMBLEM -> PalettePresets.RELIC_AMBER to PalettePresets.RIFT_CRIMSON
            WhiteRiverItemKind.BLACK_SPIRIT_CLAW -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.RIFT_CRIMSON
            WhiteRiverItemKind.ANCIENT_RELIC -> PalettePresets.ABYSSAL_OBSIDIAN to PalettePresets.RELIC_AMBER
            WhiteRiverItemKind.VORTEX_SCROLL -> PalettePresets.RELIC_AMBER to PalettePresets.RIFT_CRIMSON
            WhiteRiverItemKind.ENERGY_TONIC -> PalettePresets.ANCIENT_LEAF to PalettePresets.RELIC_AMBER
            WhiteRiverItemKind.LUMBER_AXE -> PalettePresets.DARK_IRON to PalettePresets.RELIC_AMBER
            WhiteRiverItemKind.FLUID_COLLECTOR -> PalettePresets.DARK_IRON to PalettePresets.RIFT_CRIMSON
        }
        return if (style == PixelStyle.RETRO_8BIT) {
            pair.first.limitColors(4) to pair.second.limitColors(4)
        } else {
            pair
        }
    }

    private fun templateFor(kind: WhiteRiverItemKind): List<String> = when (kind) {
        WhiteRiverItemKind.BLACK_STONE_WEAPON -> stoneTemplate("rr", "pp")
        WhiteRiverItemKind.BLACK_STONE_ARMOR -> stoneTemplate("vv", "VV")
        WhiteRiverItemKind.CONCENTRATED_BLACK_STONE -> stoneTemplate("GG", "xx")
        WhiteRiverItemKind.MEMORY_FRAGMENT -> listOf(
            "................",
            ".......oo.......",
            "......ohho......",
            ".....ohxxho.....",
            "....ohTTTTmo....",
            "....omTTTTmo....",
            ".....odmmmo.....",
            "......oddo......",
            ".......oo.......",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.CRON_STONE -> stoneTemplate("TT", "xx")
        WhiteRiverItemKind.CAPHRAS_STONE -> stoneTemplate("VV", "PP")
        WhiteRiverItemKind.SILVER_COIN -> listOf(
            "................",
            ".....oooooo.....",
            "...oohhhhhhoo...",
            "..ohxxxxxxxxho..",
            "..omllSSSSllmo..",
            "..omllllllllmo..",
            "...oddddddddo...",
            ".....oooooo.....",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.GOLD_BAR -> listOf(
            "................",
            "................",
            "....oooooooo....",
            "...oGGGGGGGGo...",
            "..oGxxxxxxxxGo..",
            "..oGGllllllGGo..",
            "...oymmmmmmmyo..",
            "...oyyyyyyyyyo..",
            "....ooooooooo...",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.KZARKA_LONGSWORD -> listOf(
            "...........ooo..",
            "..........oSSo..",
            ".........oSrso..",
            "........oSrso...",
            ".......oSrso....",
            "......oSrso.....",
            ".....oSrso......",
            "....oGGGo.......",
            "...oGrrso.......",
            "..owwo..........",
            ".oGGo...........",
            "..oo............",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.DANDELION_GREATSWORD -> listOf(
            "..........oooo..",
            ".........oSSSo..",
            "........oSSsso..",
            ".......oSSsso...",
            "......oSGssso...",
            ".....oSGssso....",
            "....oSSsso......",
            "...oGGsso.......",
            "..oyGo..........",
            ".owwo...........",
            ".oGGo...........",
            "..oo............",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.KUTUM_SHIELD -> listOf(
            ".....oooooo.....",
            "...ooyGGGGyoo...",
            "..oyGhhhhhhGyo..",
            "..oGhmTTTTmhGo..",
            ".oyGhmTccTmhGyo.",
            "..oGhmTTTTmhGo..",
            "..oyGdmmmmGdyo..",
            "...ooyddddyoo...",
            "....ooyGGyoo....",
            "......oooo......",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.DIM_TREE_ARMOR -> listOf(
            "..oVoo....ooVo..",
            ".oVVuuooooouVVo.",
            "..ouwwwwwwwwuo..",
            "..owvvVVVVvvwo..",
            "..ouwwwwwwwwuo..",
            "...ouwwVVwwuo...",
            "...ouwwwwwwuo...",
            "...ouuuuuuuuo...",
            "....oooooooo....",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.BHEG_GLOVES -> listOf(
            "................",
            "..oooo....oooo..",
            ".ohhhho..ohhhho.",
            ".omrrro..orrrro.",
            ".ommmmo..ommmmo.",
            "..odddo..odddo..",
            "...ooo....ooo...",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.URUGON_SHOES -> listOf(
            "................",
            "...oooo..oooo...",
            "...oGGo..oGGo...",
            "...ommo..ommo...",
            "...ommo..ommo...",
            "..oommo..ommoo..",
            "..odddo..odddo..",
            "..ooooo..ooooo..",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.GRIFFON_HELMET -> listOf(
            "..oG........Go..",
            "..oGGooooooGGo..",
            "...ohhhhhhhho...",
            "...ohllllllho...",
            "...ommmmmmmmo...",
            "...omdooooodo...",
            "...omdo..omdo...",
            "....oo....oo....",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.OGRE_RING -> listOf(
            "................",
            "......oooo......",
            ".....orrrro.....",
            "....orxxxxro....",
            "...oGGorrooGGo..",
            "...oGo....oGo...",
            "...oGo....oGo...",
            "....oGooooGo....",
            ".....oGGGGo.....",
            "......oooo......",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.TUNGRAD_EARRING -> listOf(
            "................",
            "....oo....oo....",
            "...oGGo..oGGo...",
            "...oGo....oGo...",
            "...oGGo..oGGo...",
            "..odddo..odddo..",
            "..odGdo..odGdo..",
            "..odddo..odddo..",
            "...ooo....ooo...",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.BASILISK_BELT -> listOf(
            "................",
            "................",
            "..oooooooooooo..",
            ".ommmGGGGGGmmmo.",
            ".ommmGxxxxGmmmo.",
            ".odddGGGGGGdddo.",
            "..oooooooooooo..",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.HP_POTION_EXTRA -> listOf(
            ".....oooooo.....",
            ".....oGGGGoo....",
            "......owwo......",
            "....oozzzzoo....",
            "...ozrppxrrzo...",
            "...ozrrrrrrzo...",
            "...ozGGGGGGzo...",
            "...ozrrrrrrzo...",
            "...ozqqqqqqzo...",
            "....oooooooo....",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.MP_POTION_EXTRA -> listOf(
            ".....oooooo.....",
            ".....oSSSSoo....",
            "......owwo......",
            "....oozzzzoo....",
            "...oztTTxttzo...",
            "...ozttttttzo...",
            "...ozSSSSSSzo...",
            "...ozttttttzo...",
            "...ozddddddzo...",
            "....oooooooo....",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.CRON_MEAL -> listOf(
            "................",
            ".....oooooo.....",
            "....oGrrrrGo....",
            "...oGGrrrrGGo...",
            "..oSxxxxxxxxSo..",
            "..oSSllllllSSo..",
            "...oooooooooo...",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.BEER -> listOf(
            "................",
            "....ooooooo.....",
            "...oxxxxxxxo....",
            "...oxxxxxoo.....",
            "...oGGGGGGooo...",
            "...oGGGGGGGoGo..",
            "...oGGGGGGGoGo..",
            "...oGGGGGGGooo..",
            "...oyyyyyyyo....",
            "....ooooooo.....",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.ELIXIR_FRENZY -> listOf(
            "......oooo......",
            "......owwo......",
            ".....oGGGGoo....",
            "....orxxxxrro...",
            "....orrrrrrpo...",
            "....orrrrrppo...",
            ".....oqqqqqo....",
            "......ooooo.....",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.MAGIC_CRYSTAL -> listOf(
            "................",
            "......oooo......",
            "....ooPPPoo.....",
            "...oxPPPPPPo....",
            "..oPPPxxxxPPPo..",
            "..oPPPrPPPPrPo..",
            "...oPPPPPPPPo...",
            "....ooPPPoo.....",
            "......oooo......",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.WHEAT_CRATE -> listOf(
            "................",
            "..oooooooooooo..",
            "..owwwwwwwwwwo..",
            "..owGGwwwwGGwo..",
            "..owwwwwwwwwwo..",
            "..ouuwwwwwwuuo..",
            "..owwwwwwwwwwo..",
            "..owGGwwwwGGwo..",
            "..ouuuuuuuuuuo..",
            "..oooooooooooo..",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.FISH_COELACANTH -> listOf(
            "................",
            ".....oooooo.....",
            "...oohhhhhhoo...",
            ".ooohllTxxllho..",
            "oGGoTTTTTTTTmo..",
            ".ooohllTTTTmdo..",
            "...ooddddddoo...",
            ".....oooooo.....",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.FISHING_ROD -> listOf(
            "............ooo.",
            "...........o..x.",
            "..........ow..x.",
            ".........ow...x.",
            "........ow....x.",
            ".......ow.....o.",
            "......ow........",
            ".....ow.........",
            "....ow..........",
            "...ow...........",
            "..ou............",
            "...o............",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.MATCHLOCK -> listOf(
            "................",
            "................",
            "....oooooooooo..",
            "...oSSssssssss..",
            "..owwwGoooooo...",
            ".owwwo..........",
            ".ouuo...........",
            "..oo............",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.ALCHEMY_STONE -> listOf(
            "................",
            ".....oooooo.....",
            "....oGGGGGGoo...",
            "...oGTTTTTTGGo..",
            "...oGTxxxxTGGo..",
            "...oGTTTTTTGGo..",
            "....oGGGGGGoo...",
            ".....oooooo.....",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.HORSE_EMBLEM -> listOf(
            "................",
            "...oooooooooo...",
            "..oGGGGGGGGGGoo.",
            "..ohllllllllhho.",
            "..ohmouuuoomhho.",
            "..ohmuuuuGGmhho.",
            "..ohmlluuulmhho.",
            "..ohmllrrllmhho.",
            "..odmmmRRmmmddo.",
            "...oGGGGGGGGGGoo",
            "....ooooooooooo.",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.BLACK_SPIRIT_CLAW -> listOf(
            ".......oo.......",
            "......oRRo......",
            ".....oddddo.....",
            "....oddRRddo....",
            "...oddddddddo...",
            "...oddd..dddo...",
            "....oo....oo....",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.ANCIENT_RELIC -> listOf(
            "................",
            ".....oooooo.....",
            "....ohGhhGho....",
            "...ohGGxxGGho...",
            "...omGGGGGGmo...",
            "....odmmmmmdo...",
            ".....ooddddo....",
            ".......oooo.....",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.VORTEX_SCROLL -> listOf(
            ".....oooooo.....",
            "....ohhhhhho....",
            "....omrrrrmo....",
            "....omrxxrmo....",
            "....omrrrrmo....",
            "....ommmmmmo....",
            "....omrrrrmo....",
            "....oddddddo....",
            ".....oooooo.....",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.ENERGY_TONIC -> listOf(
            "......oooo......",
            "......owwo......",
            ".....oaaaao.....",
            "......oAAo......",
            "....ooVVVVoo....",
            "...oVxxVVVVVo...",
            "...oVVVVVVVVo...",
            "...ovvvvvvvvo...",
            "....oddddddo....",
            ".....oooooo.....",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.LUMBER_AXE -> listOf(
            ".........oooo...",
            "........oSSsso..",
            ".......oSSssso..",
            "........oowsso..",
            "........oww.o...",
            ".......owwo.....",
            "......owwo......",
            ".....owwo.......",
            "....owwo........",
            "...ouuo.........",
            "....oo..........",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        WhiteRiverItemKind.FLUID_COLLECTOR -> listOf(
            "...........ooo..",
            "..........oSSo..",
            ".........owwo...",
            "........orrxo...",
            ".......orrro....",
            "......orrro.....",
            ".....oSSo.......",
            "....oSo.........",
            "...oo...........",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
    }

    private fun stoneTemplate(core: String, hi: String): List<String> = listOf(
        "................",
        "......oooo......",
        ".....oo${hi}oo.....",
        "....o${hi}mm${hi}o....",
        "...o${core}mmmm${core}o...",
        "...o${core}mmmm${core}o...",
        "...o${core}dddd${core}o...",
        "....oddddddo....",
        ".....oooooo.....",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................"
    )
}
