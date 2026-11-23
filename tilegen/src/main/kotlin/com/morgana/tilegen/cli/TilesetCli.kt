package com.morgana.tilegen.cli

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.sprite.CartSprite
import com.morgana.tilegen.sprite.HorseCoat
import com.morgana.tilegen.sprite.MountSprite
import com.morgana.tilegen.sprite.PaperDollProportions
import com.morgana.tilegen.ThemedTilesetGenerator
import com.morgana.tilegen.TilesetTheme
import com.morgana.tilegen.autotile.AutotileGenerator
import com.morgana.tilegen.autotile.AutotileMode
import com.morgana.tilegen.autotile.TerrainPair
import com.morgana.tilegen.export.ExportResult
import com.morgana.tilegen.export.GameTilesetExporter
import com.morgana.tilegen.export.PaperDollExporter
import com.morgana.tilegen.export.PngEncoder
import com.morgana.tilegen.export.TilesetExporter
import com.morgana.tilegen.material.MaterialPattern
import com.morgana.tilegen.material.MaterialSpec
import com.morgana.tilegen.material.MaterialTileGenerator
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.spec.PaperDollSpecJson
import com.morgana.tilegen.spec.SpecPalettes
import com.morgana.tilegen.spec.TilesetSpecJson
import com.morgana.tilegen.spec.TilesetSpecRenderer
import com.morgana.tilegen.sprite.AttachmentKind
import com.morgana.tilegen.sprite.BodyAncestry
import com.morgana.tilegen.sprite.Direction8
import com.morgana.tilegen.sprite.EnemyKind
import com.morgana.tilegen.sprite.FacingDirection
import com.morgana.tilegen.sprite.JrpgMonsterKind
import com.morgana.tilegen.sprite.PaperDollAction
import com.morgana.tilegen.sprite.PaperDollJob
import com.morgana.tilegen.sprite.PaperDollLoadout
import com.morgana.tilegen.sprite.PaperDollSpriteGenerator
import com.morgana.tilegen.sprite.PaperDollTheme
import com.morgana.tilegen.sprite.PaperDollThemes
import com.morgana.tilegen.sprite.PlayerClass
import com.morgana.tilegen.sprite.PlayerSpriteGenerator
import com.morgana.tilegen.sprite.SpriteGenerator
import com.morgana.tilegen.structure.BuildingGenerator
import com.morgana.tilegen.structure.BuildingKind
import com.morgana.tilegen.structure.BuildingRoof
import com.morgana.tilegen.structure.BuildingTheme
import com.morgana.tilegen.structure.DungeonTheme
import com.morgana.tilegen.structure.DungeonTilesetGenerator
import com.morgana.tilegen.structure.GroundDetail
import com.morgana.tilegen.structure.GroundDetailGenerator
import com.morgana.tilegen.structure.PavingGenerator
import com.morgana.tilegen.structure.PlazaGenerator
import com.morgana.tilegen.structure.PavingKind
import com.morgana.tilegen.structure.PropGenerator
import com.morgana.tilegen.structure.PropKind
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Command-line interface for generating and exporting tilesets, autotile sheets, and materials.
 */
object TilesetCli {

    private val paletteMap = SpecPalettes.presets

    fun run(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
        if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
            printUsage(out)
            return 0
        }

        val cmd = args[0].lowercase()
        val flags = parseFlags(args.drop(1))

        return try {
            when (cmd) {
                "list" -> {
                    out.println("Themes: ${TilesetTheme.entries.joinToString { it.name.lowercase() }}")
                    out.println("Dungeon themes: ${DungeonTheme.entries.joinToString { it.name.lowercase() }}")
                    out.println("Terrain pairs: ${TerrainPair.entries.joinToString { it.name.lowercase() }}")
                    out.println("Autotile modes: blob47, cardinal16")
                    out.println("Material patterns: ${MaterialPattern.entries.joinToString { it.name.lowercase() }}")
                    out.println("Prop kinds: ${PropKind.entries.joinToString { it.name.lowercase() }}")
                    out.println("Enemy kinds: ${EnemyKind.entries.joinToString { it.name.lowercase() }}")
                    out.println("Player classes: ${PlayerClass.entries.joinToString { it.name.lowercase() }}")
                    out.println("Paper-doll jobs: ${PaperDollJob.entries.joinToString { it.name.lowercase() }}")
                    out.println("Paper-doll themes: ${PaperDollThemes.presets.keys.sorted().joinToString()}")
                    out.println("Paper-doll ancestries: ${BodyAncestry.entries.joinToString { it.name.lowercase() }}")
                    out.println("Paper-doll actions: ${PaperDollAction.entries.joinToString { it.name.lowercase() }}")
                    out.println("Paper-doll attachments: ${AttachmentKind.entries.joinToString { it.name.lowercase() }}")
                    out.println("JRPG monsters: ${JrpgMonsterKind.entries.joinToString { it.name.lowercase() }}")
                    out.println("Pixel styles: ${PixelStyle.entries.joinToString { it.name.lowercase() }}")
                    out.println("Palettes: ${paletteMap.keys.sorted().joinToString()}")
                    0
                }
                "theme" -> {
                    val target = args.getOrNull(1)?.takeIf { !it.startsWith("--") }
                        ?: error("Missing theme name. Use 'list' to see available themes.")
                    val theme = TilesetTheme.valueOf(target.uppercase())
                    val seed = flags["seed"]?.toLong() ?: 1337L
                    val columns = flags["columns"]?.toInt() ?: 16
                    val outDir = Paths.get(flags["out"] ?: "build/tilesets/${theme.name.lowercase()}")
                    val split = "split" in flags

                    val gen = ThemedTilesetGenerator(seed)
                    val tiles = gen.generateTiles(theme)
                    val sheet = gen.bundleTiles(tiles.map { it.image }, columns = columns)
                    val result = TilesetExporter.exportNamedSheet(
                        name = theme.name.lowercase(),
                        sheet = sheet,
                        tiles = tiles,
                        tileSize = gen.tileSize,
                        columns = columns,
                        outDir = outDir,
                        splitTiles = split
                    )
                    reportResult(out, result)
                    0
                }
                "autotile" -> {
                    val target = args.getOrNull(1)?.takeIf { !it.startsWith("--") }
                        ?: error("Missing terrain pair name. Use 'list' to see available terrain pairs.")
                    val pair = TerrainPair.valueOf(target.uppercase())
                    val mode = when ((flags["mode"] ?: "blob47").lowercase()) {
                        "blob47", "blob_47", "47" -> AutotileMode.BLOB_47
                        "cardinal16", "cardinal_16", "16" -> AutotileMode.CARDINAL_16
                        else -> error("Unknown mode '${flags["mode"]}'. Expected blob47 or cardinal16.")
                    }
                    val size = flags["size"]?.toInt() ?: 32
                    val seed = flags["seed"]?.toLong() ?: 1337L
                    val outDir = Paths.get(flags["out"] ?: "build/tilesets/autotile_${pair.name.lowercase()}")
                    val split = "split" in flags

                    val gen = AutotileGenerator(seed = seed, tileSize = size)
                    val sheet = gen.generateTerrainAutotile(pair, mode)
                    val result = TilesetExporter.exportAutotile(
                        name = "${pair.name.lowercase()}_${mode.name.lowercase()}",
                        autotile = sheet,
                        outDir = outDir,
                        splitTiles = split
                    )
                    reportResult(out, result)
                    0
                }
                "material" -> {
                    val target = args.getOrNull(1)?.takeIf { !it.startsWith("--") }
                        ?: error("Missing material pattern. Use 'list' to see available patterns.")
                    val pattern = MaterialPattern.valueOf(target.uppercase())
                    val size = flags["size"]?.toInt() ?: 32
                    val seed = flags["seed"]?.toLong() ?: 1337L
                    val frames = flags["frames"]?.toInt() ?: 1
                    val style = PixelStyle.valueOf((flags["style"] ?: "standard").uppercase())
                    val paletteName = (flags["palette"] ?: defaultPaletteFor(pattern)).lowercase()
                    val palette = paletteMap[paletteName]
                        ?: error("Unknown palette '$paletteName'. Use 'list' to see available palettes.")
                    val outDir = Paths.get(flags["out"] ?: "build/tilesets/material_${pattern.name.lowercase()}")
                    val split = "split" in flags

                    val gen = MaterialTileGenerator(seed = seed, tileSize = size)
                    val spec = MaterialSpec(name = pattern.name.lowercase(), pattern = pattern, palette = palette, style = style)
                    val namedTiles = if (frames > 1) {
                        gen.generateAnimatedFrames(spec, frames).mapIndexed { idx, img ->
                            NamedTile("${spec.name}_f$idx", img)
                        }
                    } else {
                        listOf(NamedTile(spec.name, gen.generateTile(spec)))
                    }
                    val columns = namedTiles.size.coerceAtMost(8)
                    val sheet = ThemedTilesetGenerator(seed).let {
                        val rows = (namedTiles.size + columns - 1) / columns
                        val img = com.morgana.tilegen.TileImage(columns * size, rows * size)
                        namedTiles.forEachIndexed { idx, nt ->
                            img.drawImage(nt.image, 0, 0, size, size, (idx % columns) * size, (idx / columns) * size)
                        }
                        img
                    }
                    val result = TilesetExporter.exportNamedSheet(
                        name = "${spec.name}_${paletteName}",
                        sheet = sheet,
                        tiles = namedTiles,
                        tileSize = size,
                        columns = columns,
                        outDir = outDir,
                        splitTiles = split
                    )
                    reportResult(out, result)
                    0
                }
                "schema" -> {
                    out.println(TilesetSpecJson.jsonSchema())
                    0
                }
                "prop" -> {
                    val target = args.getOrNull(1)?.takeIf { !it.startsWith("--") }
                        ?: error("Missing prop kind. Use 'list' to see available prop kinds.")
                    val kind = PropKind.valueOf(target.uppercase())
                    val size = flags["size"]?.toInt() ?: 32
                    val seed = flags["seed"]?.toLong() ?: 1337L
                    val style = PixelStyle.valueOf((flags["style"] ?: "standard").uppercase())
                    val outDir = Paths.get(flags["out"] ?: "build/tilesets/prop_${kind.name.lowercase()}")
                    val split = "split" in flags

                    val prop = PropGenerator(seed = seed, tileSize = size)
                        .generateProp(kind.name.lowercase(), kind, style = style)
                    val result = TilesetExporter.exportNamedSheet(
                        name = kind.name.lowercase(),
                        sheet = prop.fullImage,
                        tiles = prop.slicedTiles,
                        tileSize = size,
                        columns = prop.widthTiles,
                        outDir = outDir,
                        splitTiles = split
                    )
                    reportResult(out, result)
                    0
                }
                "enemy" -> {
                    val target = args.getOrNull(1)?.takeIf { !it.startsWith("--") }
                        ?: error("Missing enemy kind or 'all'. Use 'list' to see available enemy kinds.")
                    val size = flags["size"]?.toInt() ?: 16
                    val frames = flags["frames"]?.toInt() ?: 1
                    val seed = flags["seed"]?.toLong() ?: 1337L
                    val style = PixelStyle.valueOf((flags["style"] ?: "retro_8bit").uppercase())
                    val outDir = Paths.get(flags["out"] ?: "build/tilesets/enemies_${target.lowercase()}")
                    val split = "split" in flags

                    val gen = SpriteGenerator(seed = seed, tileSize = size)
                    val (sheet, tiles, name) = if (target.equals("all", ignoreCase = true)) {
                        val (s, t) = gen.generateAllEnemiesSheet(frameCount = frames, style = style)
                        Triple(s, t, "enemies_all")
                    } else {
                        val kind = EnemyKind.valueOf(target.uppercase())
                        val t = gen.generateEnemyFrames(kind.name.lowercase(), kind, frameCount = frames, style = style)
                        val cols = t.size.coerceAtMost(8)
                        val rows = (t.size + cols - 1) / cols
                        val s = com.morgana.tilegen.TileImage(cols * size, rows * size)
                        t.forEachIndexed { idx, nt ->
                            s.drawImage(nt.image, 0, 0, size, size, (idx % cols) * size, (idx / cols) * size)
                        }
                        Triple(s, t, kind.name.lowercase())
                    }
                    val cols = tiles.size.coerceAtMost(8)
                    val result = TilesetExporter.exportNamedSheet(
                        name = name,
                        sheet = sheet,
                        tiles = tiles,
                        tileSize = size,
                        columns = cols,
                        outDir = outDir,
                        splitTiles = split
                    )
                    reportResult(out, result)
                    0
                }
                "player" -> {
                    val target = args.getOrNull(1)?.takeIf { !it.startsWith("--") }
                        ?: error("Missing player class or 'all'. Use 'list' to see available player classes.")
                    val size = flags["size"]?.toInt() ?: 16
                    val frames = flags["frames"]?.toInt() ?: 1
                    val seed = flags["seed"]?.toLong() ?: 1337L
                    val style = PixelStyle.valueOf((flags["style"] ?: "classic_rpg").uppercase())
                    val directions = "directions" in flags
                    val outDir = Paths.get(flags["out"] ?: "build/tilesets/players_${target.lowercase()}")
                    val split = "split" in flags

                    val gen = PlayerSpriteGenerator(seed = seed, tileSize = size)
                    val (sheet, tiles, name) = if (target.equals("all", ignoreCase = true)) {
                        val (s, t) = gen.generateAllPlayersSheet(
                            includeDirections = directions,
                            frameCount = frames,
                            style = style
                        )
                        Triple(s, t, "players_all")
                    } else {
                        val cls = PlayerClass.valueOf(target.uppercase())
                        val dirs = if (directions) FacingDirection.entries else listOf(FacingDirection.DOWN)
                        val t = dirs.flatMap { dir ->
                            gen.generatePlayerFrames(cls.name.lowercase(), cls, dir, frames, style)
                        }
                        val cols = t.size.coerceAtMost(8)
                        val rows = (t.size + cols - 1) / cols
                        val s = com.morgana.tilegen.TileImage(cols * size, rows * size)
                        t.forEachIndexed { idx, nt ->
                            s.drawImage(nt.image, 0, 0, size, size, (idx % cols) * size, (idx / cols) * size)
                        }
                        Triple(s, t, cls.name.lowercase())
                    }
                    val cols = tiles.size.coerceAtMost(8)
                    val result = TilesetExporter.exportNamedSheet(
                        name = name,
                        sheet = sheet,
                        tiles = tiles,
                        tileSize = size,
                        columns = cols,
                        outDir = outDir,
                        splitTiles = split
                    )
                    reportResult(out, result)
                    0
                }
                "spec" -> {
                    val target = args.getOrNull(1)?.takeIf { !it.startsWith("--") }
                        ?: error("Missing spec JSON file path.")
                    val specPath = Paths.get(target)
                    require(Files.isRegularFile(specPath)) { "Spec file not found: $specPath" }
                    val json = Files.readString(specPath)
                    val rendered = TilesetSpecRenderer.renderJson(json)
                    val outDir = Paths.get(flags["out"] ?: "build/tilesets/${rendered.spec.name}")
                    val split = "split" in flags
                    val results = TilesetExporter.exportRenderedSpec(rendered, outDir, splitTiles = split)
                    results.forEach { reportResult(out, it) }
                    0
                }
                "gametilesets" -> {
                    // gametilesets --game <folder> --specs <dir|a.json,b.json> [--first-id 100]
                    val game = flags["game"] ?: error("Missing --game <game folder> for 'gametilesets'")
                    val specArg = flags["specs"] ?: error("Missing --specs <folder or comma-separated spec files> for 'gametilesets'")
                    val files = specArg.split(',').map { Paths.get(it.trim()) }.flatMap { p ->
                        if (Files.isDirectory(p)) Files.list(p).use { st -> st.filter { it.toString().endsWith(".json") }.sorted().toList() } else listOf(p)
                    }
                    require(files.isNotEmpty()) { "No spec files found in '$specArg'" }
                    val result = GameTilesetExporter.export(Paths.get(game), files, flags["first-id"]?.toInt() ?: 100)
                    out.println("Wrote ${result.written.size} tilesets (ids ${result.written.firstOrNull()?.fileName ?: "-"} ..) into $game/assets/tilesets and $game/tilesets.index.json")
                    if (result.removed.isNotEmpty()) out.println("Removed ${result.removed.size} stale owned tilesets")
                    out.println("${result.tiles.size} tiles, ${result.props.size} props, ${result.autotiles.size} autotiles indexed; next free id ${result.nextFreeId}")
                    0
                }
                "mount" -> {
                    // mount [job] [--cart] [--coat bay|chestnut|black|white|dun] [--rider none] [--out dir]: a horse sheet, four facings
                    // (S, W, E, N) by two idle frames and four walk frames, with or without a paper-doll rider.
                    val job = PaperDollJob.valueOf((args.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: "hunter").uppercase())
                    val coat = HorseCoat.valueOf((flags["coat"] ?: "bay").uppercase())
                    val rider = flags["rider"] != "none"
                    val style = PixelStyle.valueOf((flags["style"] ?: "morgana").uppercase())
                    val outDir = Paths.get(flags["out"] ?: "build/tilesets/mount")
                    Files.createDirectories(outDir)
                    val gen = PaperDollSpriteGenerator(seed = flags["seed"]?.toLong() ?: 1337L, tileSize = 32, tileHeight = 48)
                    val loadout = PaperDollLoadout.forJob(job).let { it.copy(theme = it.effectiveTheme().copy(proportions = PaperDollProportions.RMXP_32X48, style = style)) }
                    val dirs = listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N)
                    val actions = listOf(PaperDollAction.IDLE to 2, PaperDollAction.WALK to 4)
                    val columns = actions.sumOf { it.second }
                    val cart = "cart" in flags
                    val cellW = if (cart) CartSprite.WIDTH else MountSprite.WIDTH
                    val sheet = TileImage(columns * cellW, dirs.size * MountSprite.HEIGHT)
                    for ((row, d) in dirs.withIndex()) {
                        var col = 0
                        for ((action, n) in actions) for (f in 0 until n) {
                            val frame = when {
                                cart -> CartSprite.hitched(if (rider) gen else null, if (rider) loadout else null, d, action, f, coat, style)
                                rider -> MountSprite.mounted(gen, loadout, d, action, f, coat, style)
                                else -> MountSprite.horse(d, action, f, coat)
                            }
                            for (y in 0 until frame.height) for (x in 0 until frame.width) sheet.setPixel(col * cellW + x, row * MountSprite.HEIGHT + y, frame.getPixel(x, y))
                            col++
                        }
                    }
                    val file = outDir.resolve("${if (cart) "cart" else "mount"}_${coat.name.lowercase()}${if (rider) "_" + job.name.lowercase() else ""}.png")
                    PngEncoder.writeToFile(sheet, file)
                    out.println("Wrote $file (${sheet.width}x${sheet.height}, cells ${cellW}x${MountSprite.HEIGHT}, idle 2 + walk 4 columns, rows S W E N)")
                    0
                }
                "paperdoll" -> {
                    val target = (args.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: "all").lowercase()
                    val size = flags["size"]?.toInt() ?: 32
                    val height = flags["height"]?.toInt() ?: size
                    val seed = flags["seed"]?.toLong() ?: 1337L
                    val style = PixelStyle.valueOf((flags["style"] ?: "painted_25d").uppercase())
                    val frames = flags["frames"]?.toInt() ?: 2
                    val directions8 = "directions" in flags || target == "all" || target == "jobs"
                    val outRoot = Paths.get(flags["out"] ?: "build/tilesets/paperdoll")
                    val split = "split" in flags

                    val gen = PaperDollSpriteGenerator(seed = seed, tileSize = size, tileHeight = height)
                    when (target) {
                        "jobs", "all" -> {
                            val (sheet, tiles) = gen.generateAllJobsSheet(
                                include8Directions = directions8,
                                frameCount = frames,
                                style = style
                            )
                            val res = TilesetExporter.exportNamedSheet(
                                name = "paperdoll_jobs_all",
                                sheet = sheet,
                                tiles = tiles,
                                tileSize = size,
                                columns = 10,
                                outDir = if (target == "all") outRoot.resolve("jobs") else outRoot,
                                splitTiles = split
                            )
                            reportResult(out, res)
                        }
                    }
                    when (target) {
                        "monsters", "all" -> {
                            val (sheet, tiles) = gen.generateAllMonstersSheet(
                                frameCount = frames,
                                style = style
                            )
                            val res = TilesetExporter.exportNamedSheet(
                                name = "paperdoll_monsters_all",
                                sheet = sheet,
                                tiles = tiles,
                                tileSize = size,
                                columns = 8,
                                outDir = if (target == "all") outRoot.resolve("monsters") else outRoot,
                                splitTiles = split
                            )
                            reportResult(out, res)
                        }
                    }
                    if (target == "pack") {
                        val themeName = flags["theme"] ?: "highland_hero"
                        val baseTheme = PaperDollThemes.resolve(themeName)
                            ?: error("Unknown paper-doll theme '$themeName'")
                        val ancestry = flags["ancestry"]?.let { BodyAncestry.valueOf(it.uppercase()) } ?: baseTheme.ancestry
                        val theme = baseTheme.copy(
                            ancestry = ancestry,
                            skinPalette = if (flags["ancestry"] != null) PaperDollTheme.defaultSkinFor(ancestry) else baseTheme.skinPalette,
                            eyeColor = if (flags["ancestry"] != null) PaperDollTheme.defaultEyeFor(ancestry) else baseTheme.eyeColor,
                            style = style,
                            proportions = if (height == 48 && size == 32) com.morgana.tilegen.sprite.PaperDollProportions.RMXP_32X48 else baseTheme.proportions
                        )
                        val actions = parsePaperDollActions(flags["actions"])
                            ?: listOf(PaperDollAction.IDLE, PaperDollAction.WALK, PaperDollAction.SLASH)
                        val dirs = if (directions8) Direction8.entries else listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N)
                        val pack = gen.generateEquipmentPack(theme = theme, actions = actions, directions = dirs, style = style)
                        val cols = actions.maxOf { it.defaultFrames }
                        for ((key, pair) in pack) {
                            val (sheet, tiles) = pair
                            val res = TilesetExporter.exportNamedSheet(
                                name = key,
                                sheet = sheet,
                                tiles = tiles,
                                tileSize = size,
                                columns = cols,
                                outDir = outRoot.resolve("pack"),
                                splitTiles = split,
                                exportEngineFormats = false,
                                exportPreview = false,
                                cellHeight = height
                            )
                            reportResult(out, res)
                        }
                    } else if (target == "spec") {
                        val specPath = args.getOrNull(2)?.takeIf { !it.startsWith("--") }
                            ?: error("Missing spec JSON path for 'paperdoll spec <file.json>'")
                        val spec = PaperDollSpecJson.parse(Files.readString(Paths.get(specPath)))
                        val effectiveH = if ("height" in flags) height else spec.tileHeight
                        val effectiveW = if ("size" in flags) size else spec.tileSize
                        val specGen = PaperDollSpriteGenerator(seed = spec.seed, tileSize = effectiveW, tileHeight = effectiveH)
                        val loadout = spec.toLoadout()
                        val uni = specGen.generateUniversalSheet(
                            name = spec.name,
                            loadout = loadout,
                            actions = spec.actions,
                            directions = spec.resolvedDirections(),
                            style = loadout.effectiveTheme().style,
                            includeShadow = spec.includeShadow
                        )
                        val res = PaperDollExporter.exportUniversal(uni, outRoot, exportLayers = spec.exportLayers)
                        out.println("Exported paper-doll '${spec.name}' -> ${res.compositePng} (${res.layerPngs.size} layers, manifest=${res.manifestJson})")
                    } else if (target !in setOf("jobs", "monsters", "all")) {
                        val job = PaperDollJob.valueOf(target.uppercase())
                        val universalMode = "layers" in flags || "actions" in flags || "theme" in flags || "ancestry" in flags || "attachments" in flags
                        if (universalMode) {
                            val baseTheme = flags["theme"]?.let {
                                PaperDollThemes.resolve(it) ?: error("Unknown paper-doll theme '$it'")
                            } ?: PaperDollTheme.fromJob(job)
                            val ancestry = flags["ancestry"]?.let { BodyAncestry.valueOf(it.uppercase()) } ?: baseTheme.ancestry
                            val theme = baseTheme.copy(
                                ancestry = ancestry,
                                skinPalette = if (flags["ancestry"] != null && flags["theme"] == null) PaperDollTheme.defaultSkinFor(ancestry) else baseTheme.skinPalette,
                                eyeColor = if (flags["ancestry"] != null && flags["theme"] == null) PaperDollTheme.defaultEyeFor(ancestry) else baseTheme.eyeColor,
                                style = style
                            )
                            val attachments = flags["attachments"]?.split(',')
                                ?.map { it.trim() }?.filter { it.isNotEmpty() }
                                ?.map { AttachmentKind.valueOf(it.uppercase()) }
                                ?: PaperDollLoadout.defaultAttachmentsFor(job)
                            val loadout = PaperDollLoadout(
                                job = job,
                                theme = theme,
                                attachments = attachments
                            )
                            val actions = parsePaperDollActions(flags["actions"]) ?: PaperDollAction.ALL
                            val dirs = if (directions8) Direction8.entries else listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N)
                            val uni = gen.generateUniversalSheet(
                                name = job.name.lowercase(),
                                loadout = loadout,
                                actions = actions,
                                directions = dirs,
                                style = style
                            )
                            val res = PaperDollExporter.exportUniversal(uni, outRoot, exportLayers = true)
                            out.println("Exported paper-doll '${job.name.lowercase()}' -> ${res.compositePng} (${res.layerPngs.size} layers, manifest=${res.manifestJson})")
                        } else {
                            val loadout = PaperDollLoadout.forJob(job)
                            val dirs = if (directions8) Direction8.entries else listOf(Direction8.S)
                            val tiles = dirs.flatMap { dir ->
                                gen.generateCharacterFrames(job.name.lowercase(), loadout, dir, frames, style)
                            }
                            val cols = tiles.size.coerceAtMost(8)
                            val rows = (tiles.size + cols - 1) / cols
                            val sheet = com.morgana.tilegen.TileImage(cols * size, rows * size)
                            tiles.forEachIndexed { idx, nt ->
                                sheet.drawImage(nt.image, 0, 0, size, size, (idx % cols) * size, (idx / cols) * size)
                            }
                            val res = TilesetExporter.exportNamedSheet(
                                name = job.name.lowercase(),
                                sheet = sheet,
                                tiles = tiles,
                                tileSize = size,
                                columns = cols,
                                outDir = outRoot,
                                splitTiles = split
                            )
                            reportResult(out, res)
                        }
                    }
                    0
                }
                "dungeon" -> {
                    // dungeon [crypt|ember_forge|mossy_ruins] [--seed 1337] [--out build/tilesets/dungeon_<theme>] [--split]
                    val target = (args.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: "crypt").lowercase()
                    val theme = DungeonTheme.valueOf(target.uppercase())
                    val seed = flags["seed"]?.toLong() ?: 1337L
                    val outDir = Paths.get(flags["out"] ?: "build/tilesets/dungeon_${theme.name.lowercase()}")
                    val split = "split" in flags
                    val bundle = DungeonTilesetGenerator(seed = seed, theme = theme).generateBundle()
                    val res = TilesetExporter.exportNamedSheet(
                        name = "dungeon_${theme.name.lowercase()}",
                        sheet = bundle.sheet,
                        tiles = bundle.tiles,
                        tileSize = bundle.tileSize,
                        columns = bundle.columns,
                        outDir = outDir,
                        splitTiles = split
                    )
                    reportResult(out, res)
                    val propsDir = outDir.resolve("props")
                    Files.createDirectories(propsDir)
                    for ((propName, propImg) in bundle.props) {
                        val p = propsDir.resolve("$propName.png")
                        PngEncoder.writeToFile(propImg, p)
                    }
                    val showcasePng = outDir.resolve("dungeon_${theme.name.lowercase()}_showcase.png")
                    PngEncoder.writeToFile(bundle.showcase, showcasePng)
                    out.println("Wrote ${bundle.props.size} multi-tile props in $propsDir")
                    out.println("Wrote showcase scene: $showcasePng")
                    0
                }
                "plaza" -> {
                    // plaza [--seed 1337] [--out build/tilesets/plaza]: fountain, well, lamp post, bench, planter on one 7 x 4 sheet
                    val (sheet, at) = PlazaGenerator(flags["seed"]?.toLong() ?: 1337L).sheet()
                    val dir = Paths.get(flags["out"] ?: "build/tilesets/plaza")
                    java.nio.file.Files.createDirectories(dir)
                    PngEncoder.writeToFile(sheet, dir.resolve("plaza.png"))
                    out.println("Wrote ${dir.resolve("plaza.png")}")
                    for ((kind, pos) in at) out.println("  ${kind.label}: ${kind.widthTiles} x ${kind.heightTiles} tiles at (${pos.first}, ${pos.second}); blocked from tile row ${kind.blockedFromRow}, rows above are fringe")
                    0
                }
                "grounddetail" -> {
                    // grounddetail [--seed 1337] [--out build/tilesets/grounddetail]: 4 x 4 decal sheet
                    val dir = Paths.get(flags["out"] ?: "build/tilesets/grounddetail")
                    java.nio.file.Files.createDirectories(dir)
                    PngEncoder.writeToFile(GroundDetailGenerator(flags["seed"]?.toLong() ?: 1337L).sheet(), dir.resolve("grounddetail.png"))
                    out.println("Wrote ${dir.resolve("grounddetail.png")}")
                    GroundDetail.entries.forEachIndexed { i, g -> out.println("  (${i % 4}, ${i / 4}) ${g.name.lowercase()}: ${g.label}") }
                    0
                }
                "paving" -> {
                    // paving [--variants 4] [--seed 1337] [--out build/tilesets/paving]: one sheet, a row per kind
                    val variants = flags["variants"]?.toInt() ?: 4
                    val gen = PavingGenerator(flags["seed"]?.toLong() ?: 1337L)
                    val tiles = PavingKind.entries.flatMap { gen.generate(it, variants) }
                    val sheet = ThemedTilesetGenerator().bundleTiles(tiles.map { it.image }, columns = variants)
                    val res = TilesetExporter.exportNamedSheet(
                        name = "paving", sheet = sheet, tiles = tiles, tileSize = 32, columns = variants,
                        outDir = Paths.get(flags["out"] ?: "build/tilesets/paving"), exportEngineFormats = false, exportPreview = false
                    )
                    reportResult(out, res)
                    PavingKind.entries.forEachIndexed { i, k -> out.println("  row $i: ${k.label}") }
                    0
                }
                "building" -> {
                    // building <kind|all> [--seed 1337] [--out build/tilesets/buildings]
                    val target = (args.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: "all").lowercase()
                    val kinds = if (target == "all") BuildingKind.entries else listOf(BuildingKind.valueOf(target.uppercase()))
                    val gen = BuildingGenerator(flags["seed"]?.toLong() ?: 1337L, flags["theme"]?.let { BuildingTheme.valueOf(it.uppercase()) } ?: BuildingTheme.DEFAULT)
                    val outRoot = Paths.get(flags["out"] ?: "build/tilesets/buildings")
                    val roof = flags["roof"]?.let { BuildingRoof.valueOf(it.uppercase()) }
                    for (kind in kinds) {
                        val fileName = flags["name"] ?: kind.name.lowercase()
                        val built = gen.generate(kind, fileName, roof)
                        val res = TilesetExporter.exportNamedSheet(
                            name = fileName, sheet = built.image, tiles = built.tiles, tileSize = 32,
                            columns = kind.widthTiles, outDir = outRoot, exportEngineFormats = false, exportPreview = false
                        )
                        reportResult(out, res)
                        out.println("  ${kind.label}: ${kind.widthTiles} x ${kind.heightTiles} tiles, door in column ${built.doorColumn} of the bottom (blocked) row; rows above are fringe")
                    }
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

    private fun defaultPaletteFor(pattern: MaterialPattern): String = when (pattern) {
        MaterialPattern.ORGANIC_FBM -> "meadow_grass"
        MaterialPattern.COBBLESTONE -> "slate_stone"
        MaterialPattern.BRICK_MASONRY -> "terracotta_brick"
        MaterialPattern.WOOD_PLANKS -> "timber_wood"
        MaterialPattern.WATER_CAUSTICS -> "ocean_water"
        MaterialPattern.METAL_PLATING -> "void_metal"
        MaterialPattern.DIAGONAL_PAVER -> "ruins_marble"
        MaterialPattern.GLAZED_ROOF_TILE -> "lacquer_red"
        MaterialPattern.JADE_COURTYARD -> "jade_green"
        MaterialPattern.HERRINGBONE_COBBLE -> "dark_iron"
        MaterialPattern.OBSIDIAN_CRAG -> "abyssal_obsidian"
        MaterialPattern.LEAF_CANOPY_CLUSTER -> "highland_grass"
        MaterialPattern.HALF_TIMBER_PLASTER -> "pale_limestone"
        MaterialPattern.SHINGLE_THATCH_ROOF -> "scarlet_roof"
        MaterialPattern.SANCTUARY_COURTYARD -> "cream_marble"
        MaterialPattern.ASPHALT_ROAD -> "urban_asphalt"
        MaterialPattern.HAZARD_GRATE -> "gunmetal_steel"
        MaterialPattern.CYBER_CIRCUIT -> "crt_green"
        MaterialPattern.TUFTED_GROUND -> "meadow_grass"
        MaterialPattern.ROUNDED_STONES -> "earth_dirt"
        MaterialPattern.FLAT_WATER -> "calm_water"
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
            TilesetGen CLI
            Usage:
              list
              theme <classic|farm|dungeon|spaceship> [--seed 1337] [--columns 16] [--out <dir>] [--split]
              autotile <terrainPair> [--mode blob47|cardinal16] [--size 32] [--seed 1337] [--out <dir>] [--split]
              material <pattern> [--palette <name>] [--size 32] [--frames 1] [--style <style>] [--seed 1337] [--out <dir>] [--split]
              prop <kind> [--size 32] [--style <style>] [--seed 1337] [--out <dir>] [--split]
              enemy <kind|all> [--size 16] [--frames 2] [--style retro_8bit] [--seed 1337] [--out <dir>] [--split]
              player <class|all> [--size 16] [--frames 1] [--directions] [--style classic_rpg] [--seed 1337] [--out <dir>] [--split]
              plaza [--seed 1337] [--out <dir>]   (fountain, well, lamp post, bench, planter)
              grounddetail [--seed 1337] [--out <dir>]   (pebbles, weeds, worn dirt, puddles, ruts, hay, dirt spill)
              paving [--variants 4] [--seed 1337] [--out <dir>]   (town cobble and flagstone tiles, a row per kind)
              building <kind|all> [--theme default|desert|ember|port] [--roof red|brown|slate|green|thatch] [--name <file>] [--seed 1337] [--out <dir>]   (large town buildings: cottage_large inn smithy shop chapel guardhouse great_hall)
              dungeon [crypt|ember_forge|mossy_ruins] [--seed 1337] [--out <dir>] [--split]   (high-craft 2.5D dungeon tileset, sculpted props & showcase room)
              paperdoll <jobs|monsters|all|pack|spec|jobName> [--size 32] [--frames 2] [--directions] [--layers] [--theme <name>] [--ancestry <name>] [--actions <list|all|lpc>] [--attachments <list>] [--style painted_25d|jrpg_32bit] [--seed 1337] [--out <dir>] [--split]
              spec <file.json> [--out <dir>] [--split]
              gametilesets --game <game folder> --specs <folder|a.json,b.json> [--first-id 100]   (writes owned tilesets from the id up, plus tilesets.index.json)
              schema
            """.trimIndent()
        )
    }
}

fun main(args: Array<String>) {
    val code = TilesetCli.run(args)
    if (code != 0) exitProcess(code)
}
