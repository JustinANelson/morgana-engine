package com.morgana.gameart.cli

import com.morgana.gameart.export.RoguelikeGameExporter
import com.morgana.gameart.export.LpcRpgArtExporter
import com.morgana.gameart.sprite.RpgEnemyKind
import com.morgana.gameart.sprite.RpgNpcKind
import com.morgana.gameart.sprite.RpgSpriteGenerator
import com.morgana.gameart.whiteriver.WhiteRiverEnemyGenerator
import com.morgana.gameart.whiteriver.WhiteRiverItemGenerator
import com.morgana.gameart.whiteriver.WhiteRiverPlayerGenerator
import com.morgana.gameart.whiteriver.WhiteRiverSkillGenerator
import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.export.ExportResult
import com.morgana.tilegen.export.TilesetExporter
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.sprite.PaperDollAction
import java.io.PrintStream
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Command-line interface for the art kits of the bundled games (`:game-art:run`). The generic generators live in
 * `:tilegen`; this only drives the per-game sprite kits and the exporters that write into a game's `assets/`.
 */
object GameArtCli {

    fun run(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
        if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
            printUsage(out)
            return 0
        }
        val cmd = args[0].lowercase()
        val flags = parseFlags(args.drop(1))
        return try {
            when (cmd) {
                "whiteriver" -> {
                    val target = (args.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: "all").lowercase()
                    val size = flags["size"]?.toInt() ?: 16
                    val seed = flags["seed"]?.toLong() ?: 1337L
                    val style = PixelStyle.valueOf((flags["style"] ?: "classic_rpg").uppercase())
                    val outRoot = Paths.get(flags["out"] ?: "assets/classic_rpg/white_river")
                    val split = "split" in flags
                    val frames = flags["frames"]?.toInt() ?: 2
                    val directions = "directions" in flags || target == "all"

                    fun exportGroup(sub: String, name: String, sheet: com.morgana.tilegen.TileImage, tiles: List<NamedTile>, cols: Int) {
                        val res = TilesetExporter.exportNamedSheet(
                            name = name,
                            sheet = sheet,
                            tiles = tiles,
                            tileSize = size,
                            columns = cols,
                            outDir = outRoot.resolve(sub),
                            splitTiles = split
                        )
                        reportResult(out, res)
                    }

                    when (target) {
                        "players", "all" -> {
                            val pGen = WhiteRiverPlayerGenerator(seed = seed, tileSize = size)
                            val (sheet, tiles) = pGen.generateAllPlayersSheet(
                                includeDirections = directions,
                                frameCount = frames,
                                style = style
                            )
                            exportGroup("players", "whiteriver_players_all", sheet, tiles, 6)
                        }
                    }
                    when (target) {
                        "enemies", "all" -> {
                            val eGen = WhiteRiverEnemyGenerator(seed = seed, tileSize = size)
                            val (sheet, tiles) = eGen.generateAllEnemiesSheet(frameCount = frames, style = style)
                            exportGroup("enemies", "whiteriver_enemies_all", sheet, tiles, 8)
                        }
                    }
                    when (target) {
                        "items", "all" -> {
                            val iGen = WhiteRiverItemGenerator(seed = seed, tileSize = size)
                            val (sheet, tiles) = iGen.generateAllItemsSheet(style = style)
                            exportGroup("items", "whiteriver_items_all", sheet, tiles, 8)
                        }
                    }
                    when (target) {
                        "skills", "all" -> {
                            val skillFrames = flags["frames"]?.toInt() ?: 4
                            val sGen = WhiteRiverSkillGenerator(seed = seed, tileSize = size)
                            val (sheet, tiles) = sGen.generateAllSkillsSheet(frameCount = skillFrames, style = style)
                            exportGroup("skills", "whiteriver_skills_all", sheet, tiles, skillFrames.coerceAtMost(8))
                        }
                    }
                    if (target !in setOf("players", "enemies", "items", "skills", "all")) {
                        error("Unknown whiteriver target '$target'. Expected players, enemies, items, skills, or all.")
                    }
                    0
                }
                "roguelike" -> {
                    val target = (args.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: "export").lowercase()
                    if (target != "export") error("Unknown roguelike target '$target'. Expected 'export'.")
                    val game = flags["game"] ?: error("Missing --game <game folder> for 'roguelike export'")
                    val parts = flags["only"]?.split(',')?.map { RoguelikeGameExporter.Part.valueOf(it.trim().uppercase()) }?.toSet()
                        ?: RoguelikeGameExporter.Part.entries.toSet()
                    val result = RoguelikeGameExporter.export(Paths.get(game), parts, flags["seed"]?.toLong() ?: 4040L)
                    out.println("Wrote ${result.written.size} files into $game/assets")
                    out.println("sprites.uiHeights: " + result.uiHeights.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" })
                    0
                }
                "sprites" -> {
                    val target = (args.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: "all").lowercase()
                    val size = flags["size"]?.toInt() ?: 32
                    val height = flags["height"]?.toInt() ?: 48
                    val seed = flags["seed"]?.toLong() ?: 1337L
                    val outRoot = Paths.get(flags["out"] ?: "build/tilesets/rpg_sprites")
                    val split = "split" in flags
                    val gen = RpgSpriteGenerator(seed = seed, cellWidth = size, cellHeight = height)
                    val actions = parsePaperDollActions(flags["actions"]) ?: PaperDollAction.EXTENDED
                    val cols = actions.maxOf { it.defaultFrames }

                    if (target == "npcs" || target == "all") {
                        for (npc in RpgNpcKind.entries) {
                            val (sheet, tiles) = gen.generateNpcSheet(npc, actions)
                            val res = TilesetExporter.exportNamedSheet(
                                name = "${npc.spriteId}",
                                sheet = sheet,
                                tiles = tiles,
                                tileSize = size,
                                columns = cols,
                                outDir = outRoot.resolve("npcs"),
                                splitTiles = split,
                                exportEngineFormats = false,
                                exportPreview = false,
                                cellHeight = height
                            )
                            reportResult(out, res)
                        }
                    }
                    if (target == "enemies" || target == "all") {
                        for (enemy in RpgEnemyKind.entries) {
                            val (sheet, tiles) = gen.generateEnemySheet(enemy, actions)
                            val res = TilesetExporter.exportNamedSheet(
                                name = "${enemy.spriteId}",
                                sheet = sheet,
                                tiles = tiles,
                                tileSize = size,
                                columns = cols,
                                outDir = outRoot.resolve("enemies"),
                                splitTiles = split,
                                exportEngineFormats = false,
                                exportPreview = false,
                                cellHeight = height
                            )
                            reportResult(out, res)
                        }
                    }
                    0
                }
                "lpc-rpg" -> {
                    val gameDir = Paths.get(flags["game"] ?: throw IllegalArgumentException("--game <dir> is required"))
                    val seed = flags["seed"]?.toLong() ?: 1337L
                    val written = LpcRpgArtExporter.exportAll(gameDir, seed)
                    out.println("Exported ${written.size} LPC RPG sprite/paperdoll/icon assets to $gameDir/assets")
                    0
                }
                else -> {
                    err.println("Unknown command: $cmd")
                    printUsage(err)
                    1
                }
            }
        } catch (e: IllegalArgumentException) {
            err.println("Error: ${e.message}")
            1
        } catch (e: IllegalStateException) {
            err.println("Error: ${e.message}")
            1
        }
    }

    private fun parseFlags(tokens: List<String>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok.startsWith("--")) {
                val key = tok.removePrefix("--")
                val next = tokens.getOrNull(i + 1)
                if (next != null && !next.startsWith("--")) {
                    map[key] = next
                    i += 2
                } else {
                    map[key] = "true"
                    i += 1
                }
            } else {
                i += 1
            }
        }
        return map
    }

    private fun parsePaperDollActions(raw: String?): List<PaperDollAction>? {
        if (raw.isNullOrBlank()) return null
        return raw.split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .flatMap { token ->
                when (token) {
                    "all" -> PaperDollAction.ALL
                    "lpc", "lpc_standard" -> PaperDollAction.LPC_STANDARD
                    "extended" -> PaperDollAction.EXTENDED
                    else -> listOf(PaperDollAction.valueOf(token.uppercase()))
                }
            }.distinct()
    }

    private fun reportResult(out: PrintStream, result: ExportResult) {
        out.println("Wrote sheet: ${result.sheetPng}")
        out.println("Wrote metadata: ${result.metadataJson}")
        result.tiledTsx?.let { out.println("Wrote Tiled TSX: $it") }
        result.godotTres?.let { out.println("Wrote Godot TRES: $it") }
        result.previewHtml?.let { out.println("Wrote Preview HTML: $it") }
        if (result.splitTileFiles.isNotEmpty()) {
            out.println("Wrote ${result.splitTileFiles.size} individual tile PNGs.")
        }
    }

    private fun printUsage(out: PrintStream) {
        out.println(
            """
            Game art CLI
            Usage:
              whiteriver <players|enemies|items|skills|all> [--size 16] [--frames 2] [--directions] [--style classic_rpg] [--seed 1337] [--out <dir>] [--split]
              roguelike export --game <game folder> [--only tilesets,bodies,gear,npcs,enemies,icons] [--seed 4040]
              lpc-rpg --game <game folder> [--seed 1337]
              sprites <npcs|enemies|all> [--size 32] [--height 48] [--actions <list>] [--out <dir>] [--split]
            """.trimIndent()
        )
    }
}

fun main(args: Array<String>) {
    val code = GameArtCli.run(args)
    if (code != 0) exitProcess(code)
}
