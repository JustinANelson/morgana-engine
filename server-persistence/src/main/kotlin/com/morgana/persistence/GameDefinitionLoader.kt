package com.morgana.persistence

import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinition
import com.morgana.domain.game.GameDefinitionException
import com.morgana.domain.game.GameIssue
import com.morgana.domain.game.BlankGame
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** `game.json` could not be used; the message names the file and lists every problem. */
class GameDefinitionFileException(message: String) : Exception(message)

/** The game a data directory runs: [source] is its `game.json`, or null when the engine default [BlankGame] is used. */
class LoadedGame(val game: CompiledGame, val source: Path?)

/**
 * Reads `<data dir>/game.json`, next to `server.json`. Parsing is strict (unknown fields are errors, like `server.json`) and the
 * result is validated, so a broken game definition stops startup with a message instead of misbehaving in play.
 * With no file, the engine default [BlankGame] is used.
 */
object GameDefinitionLoader {
    const val FILE_NAME = "game.json"

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }

    fun fileIn(dataDir: Path): Path = dataDir.resolve(FILE_NAME)

    fun load(dataDir: Path): LoadedGame {
        val file = fileIn(dataDir)
        if (!Files.exists(file)) return LoadedGame(CompiledGame.compile(BlankGame.definition), null)
        return LoadedGame(compile(file, Files.readString(file)), file)
    }

    /**
     * The game for a data store: the `game.json` in [gameDir], else the one in [rootDir], else the engine default [BlankGame].
     * Coordinator startup and the replay tool both use this, so they always run the same rules.
     */
    fun loadFor(gameDir: Path, rootDir: Path): CompiledGame =
        listOf(gameDir, rootDir).firstOrNull { Files.exists(fileIn(it)) }?.let { load(it).game } ?: BlankGame.COMPILED

    /** Parses and validates [text] (the contents of [file], used only in messages). */
    fun compile(file: Path, text: String): CompiledGame {
        val definition = try {
            json.decodeFromString(GameDefinition.serializer(), text)
        } catch (e: Exception) {
            throw GameDefinitionFileException("Invalid $file: cannot be read: ${e.message?.lineSequence()?.first()}")
        }
        return try {
            CompiledGame.compile(definition)
        } catch (e: GameDefinitionException) {
            throw GameDefinitionFileException("Invalid $file:" + e.issues.joinToString(separator = "") { "\n  $it" })
        }
    }

    /** Every problem in [definition], without throwing (for tools that report rather than stop). */
    fun issues(definition: GameDefinition): List<GameIssue> = com.morgana.domain.game.GameDefinitionValidator.validate(definition)

    fun encode(definition: GameDefinition): String = json.encodeToString(GameDefinition.serializer(), definition) + "\n"

    /** Writes the default blank game definition as `game.json` unless one exists. Returns true when it wrote the file. */
    fun writeBuiltInIfMissing(dataDir: Path): Boolean {
        val file = fileIn(dataDir)
        if (Files.exists(file)) return false
        Files.createDirectories(dataDir)
        val tmp = file.resolveSibling("$FILE_NAME.tmp")
        Files.writeString(tmp, encode(BlankGame.definition))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        return true
    }
}
