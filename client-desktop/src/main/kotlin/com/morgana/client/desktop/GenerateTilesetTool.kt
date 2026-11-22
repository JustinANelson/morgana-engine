package com.morgana.client.desktop

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.GdxNativesLoader
import com.morgana.client.assets.toPixmap
import com.morgana.client.assets.toTileImage
import com.morgana.server.assets.GameAssets
import com.morgana.tilegen.ProceduralTilesetGenerator
import com.morgana.tilegen.ThemedTilesetGenerator
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.TilesetTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Command-line tool to procedurally generate 32x32 texture tilesets for a Morgana game project. The generators live in
 * the engine-free `:tilegen` module; this tool adds PNG I/O and validation through the [GameAssets] pipeline.
 *
 * ```
 * .\gradlew.bat :client-desktop:generateTileset -PtilesetArgs="--game games/blank --id 1 --rows 3 --seed 1337"
 * .\gradlew.bat :client-desktop:generateTileset -PtilesetArgs="--game games/blank --id 2 --theme farm --export-tiles build/tiles/farm"
 * .\gradlew.bat :client-desktop:generateTileset -PtilesetArgs="--game games/blank --id 3 --bundle build/tiles/dungeon"
 * ```
 */
object GenerateTilesetTool {

    data class Result(
        val writtenFile: Path?,
        val skipped: Boolean,
        val width: Int,
        val height: Int,
        val errors: List<String>,
        val exportedTiles: List<Path> = emptyList()
    )

    fun generateAndExport(
        gameDir: Path,
        tilesetId: Int = 1,
        columns: Int = 16,
        rows: Int = 3,
        seed: Long = 1337L,
        force: Boolean = false,
        theme: TilesetTheme = TilesetTheme.CLASSIC,
        exportTilesDir: Path? = null
    ): Result {
        GdxNativesLoader.load()

        val target = gameDir.resolve(GameAssets.FOLDER).resolve("tilesets/$tilesetId.png")
        if (Files.exists(target) && !force) {
            return Result(
                writtenFile = null,
                skipped = true,
                width = columns * 32,
                height = rows * 32,
                errors = emptyList()
            )
        }

        val exportedTiles = mutableListOf<Path>()
        val image = if (theme == TilesetTheme.CLASSIC && exportTilesDir == null) {
            ProceduralTilesetGenerator(seed).generateTileset(columns = columns, rows = rows)
        } else {
            val themed = ThemedTilesetGenerator(seed)
            val tiles = themed.generateTiles(theme)
            if (exportTilesDir != null) {
                exportedTiles += exportTiles(tiles.map { it.name to it.image }, exportTilesDir)
            }
            themed.bundleTiles(tiles.map { it.image }, columns = columns)
        }

        writePng(target, image)

        val scan = GameAssets.scan(gameDir, gameDir.fileName.toString())
        return Result(
            writtenFile = target,
            skipped = false,
            width = image.width,
            height = image.height,
            errors = scan.errors,
            exportedTiles = exportedTiles
        )
    }

    /**
     * Bundles all 32x32 `.png` files in [tilesDir] (sorted by filename) into a standard
     * [columns]-wide tileset PNG inside [gameDir].
     */
    fun bundleDirectory(
        gameDir: Path,
        tilesDir: Path,
        tilesetId: Int,
        columns: Int = 16,
        force: Boolean = false
    ): Result {
        GdxNativesLoader.load()
        val target = gameDir.resolve(GameAssets.FOLDER).resolve("tilesets/$tilesetId.png")
        if (Files.exists(target) && !force) {
            return Result(writtenFile = null, skipped = true, width = 0, height = 0, errors = emptyList())
        }

        val pngFiles = Files.list(tilesDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".png") }
                .sorted(Comparator.comparing { it.fileName.toString() })
                .toList()
        }
        if (pngFiles.isEmpty()) {
            return Result(null, skipped = false, width = 0, height = 0, errors = listOf("No .png files found in $tilesDir"))
        }

        val loaded = mutableListOf<TileImage>()
        val sizeErrors = mutableListOf<String>()
        for (file in pngFiles) {
            val pm = Pixmap(FileHandle(file.toFile()))
            try {
                if (pm.width != 32 || pm.height != 32) {
                    sizeErrors += "${file.fileName}: expected 32x32 px, got ${pm.width}x${pm.height}"
                } else {
                    loaded += pm.toTileImage()
                }
            } finally {
                pm.dispose()
            }
        }
        if (sizeErrors.isNotEmpty()) {
            return Result(null, skipped = false, width = 0, height = 0, errors = sizeErrors)
        }

        val sheet = ThemedTilesetGenerator().bundleTiles(loaded, columns = columns)
        writePng(target, sheet)

        val scan = GameAssets.scan(gameDir, gameDir.fileName.toString())
        return Result(target, skipped = false, width = sheet.width, height = sheet.height, errors = scan.errors)
    }

    /** Writes each named tile as `NN_name.png` into [dir] and returns the written paths. */
    private fun exportTiles(tiles: List<Pair<String, TileImage>>, dir: Path): List<Path> {
        Files.createDirectories(dir)
        return tiles.mapIndexed { idx, (name, image) ->
            dir.resolve("%02d_%s.png".format(idx, name)).also { writePng(it, image) }
        }
    }

    /** Writes [image] as a PNG at [path], creating parent folders. Needs the Gdx natives loaded. */
    private fun writePng(path: Path, image: TileImage) {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val pm = image.toPixmap()
        try {
            PixmapIO.writePNG(FileHandle(path.toFile()), pm)
        } finally {
            pm.dispose()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val gameArg = args.indexOf("--game").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val outArg = args.indexOf("--out").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        if (gameArg == null && outArg == null) {
            System.err.println(
                "Usage: generateTileset (--game <game folder> | --out <png file>) [--id <tilesetId>] [--theme ${TilesetTheme.entries.joinToString("|") { it.name.lowercase() }}] " +
                    "[--rows <rows>] [--seed <seed>] [--export-tiles <dir>] [--bundle <dir>] [--force]"
            )
            exitProcess(2)
        }

        val tilesetId = args.indexOf("--id").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() } ?: 1
        val rows = args.indexOf("--rows").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() } ?: 3
        val seed = args.indexOf("--seed").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toLongOrNull() } ?: 1337L
        val force = "--force" in args
        val themeArg = args.indexOf("--theme").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } ?: "classic"
        val theme = TilesetTheme.entries.firstOrNull { it.name.equals(themeArg, ignoreCase = true) }
        if (theme == null) {
            System.err.println("Unknown theme '$themeArg'; expected one of: ${TilesetTheme.entries.joinToString { it.name.lowercase() }}")
            exitProcess(2)
        }
        val exportTilesDir = args.indexOf("--export-tiles").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.let { Path.of(it) }
        val bundleDir = args.indexOf("--bundle").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.let { Path.of(it) }

        if (outArg != null) {
            GdxNativesLoader.load()
            val outPath = Path.of(outArg)
            val themed = ThemedTilesetGenerator(seed)
            val tiles = themed.generateTiles(theme)
            if (exportTilesDir != null) {
                exportTiles(tiles.map { it.name to it.image }, exportTilesDir)
                println("exported ${tiles.size} individual 32x32 tile PNGs to $exportTilesDir")
            }
            val sheet = themed.bundleTiles(tiles.map { it.image }, columns = 16)
            writePng(outPath, sheet)
            println("wrote   $outPath (${sheet.width}x${sheet.height} px, ${(sheet.width / 32) * (sheet.height / 32)} tiles)")
            return
        }

        val gameDir = Path.of(gameArg!!)
        if (!Files.exists(gameDir.resolve("game.json"))) {
            System.err.println("$gameDir has no game.json; pass a game project folder such as games/blank")
            exitProcess(2)
        }

        val result = if (bundleDir != null) {
            println("Bundling 32x32 tiles from $bundleDir into $gameDir (id: $tilesetId)...")
            bundleDirectory(gameDir = gameDir, tilesDir = bundleDir, tilesetId = tilesetId, force = force)
        } else {
            println("Generating 32x32 ${theme.name.lowercase()} tileset for $gameDir (id: $tilesetId, seed: $seed)...")
            generateAndExport(
                gameDir = gameDir,
                tilesetId = tilesetId,
                columns = 16,
                rows = rows,
                seed = seed,
                force = force,
                theme = theme,
                exportTilesDir = exportTilesDir
            )
        }

        if (result.exportedTiles.isNotEmpty()) {
            println("exported ${result.exportedTiles.size} individual 32x32 tile PNGs to $exportTilesDir")
        }
        if (result.skipped) {
            println("kept    assets/tilesets/$tilesetId.png (exists; use --force to overwrite)")
        } else if (result.writtenFile != null) {
            println("wrote   assets/tilesets/$tilesetId.png (${result.width}x${result.height} px, ${(result.width / 32) * (result.height / 32)} tiles)")
        }

        if (result.errors.isNotEmpty()) {
            result.errors.forEach { System.err.println("validation error: $it") }
            exitProcess(1)
        }
        println("Generated successfully and validated with GameAssets pipeline.")
    }
}
