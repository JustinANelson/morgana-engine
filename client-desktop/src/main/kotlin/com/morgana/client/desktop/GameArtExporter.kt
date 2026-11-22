package com.morgana.client.desktop

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.GdxNativesLoader
import com.morgana.client.assets.ProceduralRetroAssets
import com.morgana.server.assets.GameAssets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Writes the client's built-in procedural art into a game's `assets/` folder as editable PNGs, so a game can own and
 * customise its art without touching Kotlin. Only the tileset and the sprites the game's content actually uses are exported.
 *
 * ```
 * .\gradlew.bat :client-desktop:exportGameArt -PartArgs="--game games/blank"
 * ```
 * Existing files are kept unless `--force` is given. The result is validated with the same [GameAssets] rules the server applies.
 */
object GameArtExporter {

    /** What an export wrote and what it left alone. */
    data class Result(val written: List<String>, val skipped: List<String>, val errors: List<String>)

    private val SPRITE_FIELDS = Regex("\"(?:spriteId|spriteSheetId)\"\\s*:\\s*(\\d+)")

    private val ICON_FIELDS = Regex("\"iconId\"\\s*:\\s*(\\d+)")
    private val PAPERDOLL_FIELDS = Regex("\"paperdoll(?:OffHand)?SpriteId\"\\s*:\\s*(\\d+)")
    private val PROJECTILE_FIELDS = Regex("\"projectileSpriteId\"\\s*:\\s*(\\d+)")
    private val ANIMATION_FIELDS = Regex("\"id\"\\s*:\\s*(\\d+)")

    /** Sprite ids that a game's `data/` content refers to (NPC `spriteId`, class `spriteSheetId`); 0 means none. */
    fun usedSpriteIds(gameDir: Path): Set<Int> = usedIds(gameDir, SPRITE_FIELDS)

    /** Ids matched by [field] in the game's `data/` JSON files (`sub` limits it to one collection folder); 0 means none. */
    fun usedIds(gameDir: Path, field: Regex, sub: String? = null): Set<Int> {
        val data = gameDir.resolve("data").let { if (sub != null) it.resolve(sub) else it }
        if (!Files.isDirectory(data)) return emptySet()
        val ids = sortedSetOf<Int>()
        Files.walk(data).use { stream ->
            stream.filter { it.toString().endsWith(".json") }.forEach { file ->
                field.findAll(Files.readString(file)).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it > 0 }.forEach { ids += it }
            }
        }
        return ids
    }

    fun export(gameDir: Path, force: Boolean = false): Result {
        GdxNativesLoader.load()
        val spritesProfile = runCatching {
            val file = com.morgana.persistence.GameDefinitionLoader.fileIn(gameDir)
            if (Files.exists(file)) com.morgana.persistence.GameDefinitionLoader.load(gameDir).game.definition.sprites else null
        }.getOrNull()
        val assets = ProceduralRetroAssets()
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        fun write(rel: String, pixmap: com.badlogic.gdx.graphics.Pixmap) {
            val target = gameDir.resolve(GameAssets.FOLDER).resolve(rel)
            if (Files.exists(target) && !force) { skipped += rel; pixmap.dispose(); return }
            Files.createDirectories(target.parent)
            PixmapIO.writePNG(FileHandle(target.toFile()), pixmap)
            pixmap.dispose()
            written += rel
        }

        write("tilesets/1.png", assets.buildStarterTilesetPixmap())
        val sprites = assets.buildStarterSpritePixmaps(spritesProfile).toMutableMap()
        val wanted = usedSpriteIds(gameDir)
        for ((id, pixmap) in sprites) {
            if (id in wanted) write("sprites/$id.png", pixmap) else pixmap.dispose()
        }
        val unknown = (wanted - sprites.keys).sorted().map { "sprite $it has no built-in art to export" }.toMutableList()

        // Single images: only those the game's content refers to (an id with no built-in image is reported, as for sprites).
        fun exportSingles(folder: String, label: String, built: Map<Int, com.badlogic.gdx.graphics.Pixmap>, wantedIds: Set<Int>) {
            for ((id, pixmap) in built) if (id in wantedIds) write("$folder/$id.png", pixmap) else pixmap.dispose()
            unknown += (wantedIds - built.keys).sorted().map { "$label $it has no built-in art to export" }
        }
        exportSingles("icons", "icon", assets.buildStarterIconPixmaps(), usedIds(gameDir, ICON_FIELDS, "items"))
        exportSingles("paperdolls", "paperdoll", assets.buildStarterPaperdollPixmaps(), usedIds(gameDir, PAPERDOLL_FIELDS, "items"))
        exportSingles("projectiles", "projectile", assets.buildStarterProjectilePixmaps(), usedIds(gameDir, PROJECTILE_FIELDS, "items"))
        exportSingles("animations", "animation", assets.buildStarterAnimationPixmaps(), usedIds(gameDir, ANIMATION_FIELDS, "animations"))

        val scan = GameAssets.scan(gameDir, gameDir.fileName.toString(), sprites = spritesProfile)
        return Result(written, skipped, scan.errors + unknown)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val gameArg = args.indexOf("--game").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        if (gameArg == null) {
            System.err.println("Usage: exportGameArt --game <game folder> [--force]")
            exitProcess(2)
        }
        val gameDir = Path.of(gameArg)
        if (!Files.exists(gameDir.resolve("game.json"))) {
            System.err.println("$gameDir has no game.json; pass a game project folder such as games/blank")
            exitProcess(2)
        }
        val result = export(gameDir, force = "--force" in args)
        result.written.forEach { println("wrote   assets/$it") }
        result.skipped.forEach { println("kept    assets/$it (exists; use --force to overwrite)") }
        result.errors.forEach { System.err.println("problem $it") }
        if (result.errors.isNotEmpty()) exitProcess(1)
    }
}
