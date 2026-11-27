package com.morgana.mapbuilder

import com.morgana.persistence.ContentLoader
import com.morgana.persistence.WorldContentRepository
import kotlinx.serialization.json.Json
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * `:map-builder:run "--args=build --game <game-dir> --recipes <folder|a.json,b.json> [--index <file>] [--only <id,id>]"`
 *
 * Compiles each recipe and writes `data/maps/map_<id>.json` in the game. It owns only the map ids its recipes name and replaces
 * those; every other map file is left alone. A map that has been edited since (revision above 1, which every `F1` edit raises) is
 * not overwritten unless `--force true` is given, so hand edits survive a re-run. Afterwards it loads the game's content and reports how many maps it holds.
 */
object MapBuilderCli {
    private val json = Json { classDiscriminator = "op"; ignoreUnknownKeys = false }

    fun run(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
        if (args.firstOrNull() != "build") {
            err.println("Usage: build --game <game folder> --recipes <folder|a.json,b.json> [--index <file>] [--only <id,id>] [--force true] [--analysis <folder>]")
            return 1
        }
        val flags = args.drop(1).chunked(2).associate { it[0].removePrefix("--") to (it.getOrNull(1) ?: "") }
        val gameArg = flags["game"] ?: run { err.println("Missing --game"); return 1 }
        val recipeArg = flags["recipes"] ?: run { err.println("Missing --recipes"); return 1 }
        val game = Paths.get(gameArg)
        val index = TileIndex.parse(Files.readString(flags["index"]?.let { Paths.get(it) } ?: game.resolve("tilesets.index.json")))
        val only = flags["only"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
        val files = recipeArg.split(',').map { Paths.get(it.trim()) }.flatMap { p ->
            if (Files.isDirectory(p)) Files.list(p).use { s -> s.filter { it.toString().endsWith(".json") }.sorted().toList() } else listOf(p)
        }
        val repo = WorldContentRepository(game.resolve("data"))
        var written = 0
        for (file in files) {
            val recipe = try {
                json.decodeFromString(MapRecipe.serializer(), Files.readString(file))
            } catch (e: Exception) {
                err.println("${file.fileName}: ${e.message}")
                return 1
            }
            if (only != null && recipe.id !in only) continue
            val built = try {
                MapCompiler.build(recipe, index)
            } catch (e: RecipeException) {
                err.println("${file.fileName}: ${e.message}")
                return 1
            }
            val map = built.map
            val existing = repo.maps.findById(map.id)
            if (existing != null && existing.revision > 1 && flags["force"] != "true") {
                err.println("${file.fileName}: map ${map.id} was edited by hand (revision ${existing.revision}); not overwritten. Use --force true to replace it.")
                return 1
            }
            repo.maps.save(map.id, map)
            flags["analysis"]?.takeIf { it.isNotBlank() }?.let { dir ->
                Files.createDirectories(Paths.get(dir))
                Files.writeString(Paths.get(dir).resolve("map_%03d.json".format(map.id)), json.encodeToString(MapAnalysis.serializer(), built.analysis))
            }
            written++
            out.println("map ${map.id} ${map.name}: ${map.width}x${map.height}, ${map.npcSpawns.size} spawns")
        }
        val total = ContentLoader.load(game.resolve("data")).catalog.maps.size
        out.println("Wrote $written maps ($total in the game now). Run the content validator for a full check.")
        return 0
    }
}

fun main(args: Array<String>) {
    val code = MapBuilderCli.run(args)
    if (code != 0) exitProcess(code)
}
