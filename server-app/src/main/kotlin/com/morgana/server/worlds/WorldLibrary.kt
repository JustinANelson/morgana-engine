package com.morgana.server.worlds

import com.morgana.persistence.ContentLoader
import com.morgana.persistence.GameDefinitionLoader
import com.morgana.persistence.GameScaffolder
import com.morgana.domain.validation.IssueSeverity
import com.morgana.persistence.MorganaDataStore
import com.morgana.domain.sim.OptionalModule
import com.morgana.persistence.ServerConfig
import com.morgana.persistence.PackImportMode
import com.morgana.persistence.WorldPack
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/** Stored as `world.meta.json` in each library world's data directory. */
@Serializable
data class WorldMeta(val name: String, val origin: String, val createdAtMillis: Long, val lastPlayedAtMillis: Long? = null)

/** One playable world: a complete server data directory (`world/`, `accounts/`, `characters/`, `social/`). */
data class WorldInfo(val slug: String, val name: String, val dataDir: Path, val origin: String)

/** What the Local Worlds screen shows about one world (see [WorldLibrary.summary]). */
data class WorldSummary(
    val slug: String,
    val name: String,
    /** How it was made: `starter`, `blank`, `game:<folder>`, `pack:<name>` or `server-data` (the legacy default world). */
    val origin: String,
    val folder: Path,
    val createdAtMillis: Long?,
    val lastPlayedAtMillis: Long?,
    val mapCount: Int,
    val classCount: Int,
    /** Config keys of optional systems switched off in `server.json` (`trading`, `guilds`, ...). */
    val disabledModules: List<String>
) {
    val isDefault: Boolean get() = slug == WorldLibrary.DEFAULT_SLUG
}

/**
 * A folder of independent worlds for the one-click "New World / Play" flow. Every world is a normal server
 * data directory, so any of them can also be served headless (`MorganaServerMain`) or exported as a world pack.
 *
 * The legacy single-world directory (`server-data/`) is always listed as the `default` world so existing
 * installs keep working.
 */
class WorldLibrary(
    val root: Path = Path.of("worlds"),
    private val legacyDefaultDir: Path = Path.of("server-data"),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Game projects the templates copy from (see [RealmTemplate.gameFolder]); only those installed next to the app are present. */
    private val gameDirs: Map<RealmTemplate, Path> = findGameDirs()
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun list(): List<WorldInfo> {
        val out = mutableListOf(WorldInfo(DEFAULT_SLUG, "Default World", legacyDefaultDir, "server-data"))
        if (root.isDirectory()) {
            val dirs = Files.list(root).use { s -> s.filter { it.isDirectory() }.sorted().toList() }
            for (dir in dirs) {
                val meta = readMeta(dir) ?: continue
                out += WorldInfo(dir.fileName.toString(), meta.name, dir, meta.origin)
            }
        }
        return out
    }

    fun find(slug: String): WorldInfo? = list().firstOrNull { it.slug == slug }

    /**
     * Creates a world named [name], seeded with the starter world or imported from [pack] (folder or zip).
     * On any failure nothing is left behind.
     */
    fun create(name: String, pack: Path? = null): WorldInfo =
        create(RealmBlueprint(name.trim().take(RealmBlueprint.MAX_NAME), if (pack != null) RealmTemplate.PACK else RealmTemplate.BLANK, pack = pack))

    /** Templates this machine can actually create (a game template needs its folder to be present). */
    fun availableTemplates(): List<RealmTemplate> = buildList {
        add(RealmTemplate.BLANK)
        addAll(gameDirs.keys)
        add(RealmTemplate.PACK)
    }

    /** The systems [template] switches off in its own `server.json` (what the wizard starts its feature list from); empty for a built-in template. */
    fun templateDefaults(template: RealmTemplate): Set<OptionalModule> =
        gameDirs[template]?.let(::templateDisabledModules).orEmpty()

    /** Resolves a template ID (built-in or auto-discovered from `games/<folder>/game.json`). */
    fun templateFromId(id: String): RealmTemplate? = RealmTemplate.fromId(id, gameDirs.keys)

    /** Playable classes defined in the world at [slug], sorted by class ID. */
    fun classesFor(slug: String): List<com.morgana.protocol.realms.RealmClassSummary> {
        val info = find(slug) ?: return emptyList()
        val contentDir = listOf(info.dataDir.resolve("data"), info.dataDir.resolve("world"))
            .firstOrNull { it.isDirectory() } ?: return emptyList()
        return runCatching {
            ContentLoader.load(contentDir).catalog.classes.values.sortedBy { it.id }.map {
                com.morgana.protocol.realms.RealmClassSummary(
                    id = it.id,
                    name = it.name,
                    description = it.description,
                    spriteId = it.spriteIds.firstOrNull() ?: 1
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Creates a realm from the New Realm Wizard's [blueprint]: the chosen template's content, the module switches in
     * `server.json`, then a full content validation. On any failure nothing is left behind.
     */
    fun create(blueprint: RealmBlueprint): WorldInfo {
        blueprint.problems().firstOrNull()?.let { throw IllegalArgumentException(it) }
        if (blueprint.template.gameFolder != null && blueprint.template !in gameDirs) {
            throw IllegalArgumentException("The ${blueprint.template.title} game files were not found next to the application.")
        }
        val clean = blueprint.name.trim()
        val slug = uniqueSlug(slugify(clean))
        val dir = root.resolve(slug)
        Files.createDirectories(dir)
        try {
            val origin = when {
                blueprint.template.gameFolder != null -> {
                    copyGame(gameDirs.getValue(blueprint.template), dir)
                    "game:${blueprint.template.gameFolder}"
                }
                blueprint.template == RealmTemplate.BLANK -> {
                    GameScaffolder.scaffold(dir, clean)
                    "blank"
                }
                blueprint.template == RealmTemplate.PACK -> {
                    val manifest = WorldPack.import(blueprint.pack!!, dir.resolve("world"), PackImportMode.NEW_ONLY).manifest
                    "pack:${manifest.name}"
                }
                else -> throw IllegalArgumentException("Unsupported template '${blueprint.template.id}'")
            }
            // A template declares its systems in the game folder's own server.json (only what it switches off matters);
            // the wizard's choices are added on top, and any of it can be changed later in the F1 Systems panel.
            val templateOff = blueprint.template.gameFolder?.let { gameDirs[blueprint.template] }?.let(::templateDisabledModules).orEmpty()
            val allOff = (templateOff - blueprint.enabledModules) + blueprint.disabledModules
            if (allOff.isNotEmpty()) {
                ServerConfig.save(dir, ServerConfig.load(dir).withModules(disable = allOff))
            }
            if (blueprint.noCombat || blueprint.modernSprites || blueprint.uiLayout != null) {
                val baseGame = if (Files.exists(GameDefinitionLoader.fileIn(dir))) {
                    GameDefinitionLoader.load(dir).game.definition
                } else {
                    com.morgana.domain.game.BlankGame.definition
                }
                val targetSprites = if (blueprint.modernSprites) {
                    baseGame.sprites ?: com.morgana.domain.game.SpriteProfile.MODERN_TALL
                } else {
                    baseGame.sprites
                }
                val baseUi = baseGame.ui ?: com.morgana.domain.game.UiProfile()
                val updatedUi = when {
                    blueprint.noCombat && blueprint.uiLayout != null -> baseUi.copy(combat = false, spells = false, layout = blueprint.uiLayout)
                    blueprint.noCombat -> baseUi.copy(combat = false, spells = false)
                    blueprint.uiLayout != null -> baseUi.copy(layout = blueprint.uiLayout)
                    else -> baseGame.ui
                }
                val updatedGame = baseGame.copy(
                    combat = if (blueprint.noCombat) baseGame.combat.copy(module = "noop") else baseGame.combat,
                    ui = updatedUi,
                    sprites = targetSprites
                )
                Files.writeString(GameDefinitionLoader.fileIn(dir), GameDefinitionLoader.encode(updatedGame))
                if (blueprint.modernSprites && targetSprites != null && baseGame.sprites == null) {
                    val spritesDir = dir.resolve("assets").resolve("sprites")
                    if (Files.isDirectory(spritesDir)) {
                        Files.list(spritesDir).use { stream ->
                            stream.filter { it.fileName.toString().endsWith(".png") }.forEach { pngFile ->
                                val rescaled = com.morgana.server.assets.GameAssets.rescaleLegacySpriteSheet(
                                    Files.readAllBytes(pngFile),
                                    targetSprites
                                )
                                Files.write(pngFile, rescaled)
                            }
                        }
                    }
                }
            }
            val contentDir = listOf(dir.resolve("data"), dir.resolve("world")).firstOrNull { it.isDirectory() } ?: dir.resolve("world")
            val game = if (Files.exists(GameDefinitionLoader.fileIn(dir))) GameDefinitionLoader.load(dir).game.definition
            else com.morgana.domain.game.BlankGame.definition
            val errors = ContentLoader.load(contentDir).fullReport(game).issues.filter { it.severity == IssueSeverity.ERROR }
            check(errors.isEmpty()) { "The new realm's content has ${errors.size} error(s): ${errors.first()}" }
            Files.writeString(
                dir.resolve(META_FILE),
                json.encodeToString(WorldMeta.serializer(), WorldMeta(clean, origin, clock())) + "\n"
            )
            return WorldInfo(slug, clean, dir, origin)
        } catch (e: Exception) {
            Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            throw e
        }
    }

    /** Details of the world at [slug] for the Local Worlds screen, or null when there is no such world. */
    fun summary(slug: String): WorldSummary? {
        val info = find(slug) ?: return null
        val meta = if (slug == DEFAULT_SLUG) null else readMeta(info.dataDir)
        val contentDir = listOf(info.dataDir.resolve("data"), info.dataDir.resolve("world")).firstOrNull { it.isDirectory() }
        val catalog = contentDir?.let { runCatching { ContentLoader.load(it).catalog }.getOrNull() }
        val disabled = runCatching { ServerConfig.load(info.dataDir).modules.filterValues { !it }.keys.sorted() }.getOrDefault(emptyList())
        return WorldSummary(
            slug = info.slug, name = info.name, origin = info.origin, folder = info.dataDir,
            createdAtMillis = meta?.createdAtMillis, lastPlayedAtMillis = meta?.lastPlayedAtMillis,
            mapCount = catalog?.maps?.size ?: 0, classCount = catalog?.classes?.size ?: 0, disabledModules = disabled
        )
    }

    /** Records that the world was just opened for play (shown as "last played"); the default world keeps no such record. */
    fun markPlayed(slug: String) {
        if (slug == DEFAULT_SLUG) return
        val dir = libraryDir(slug) ?: return
        val meta = readMeta(dir) ?: return
        writeMeta(dir, meta.copy(lastPlayedAtMillis = clock()))
    }

    /** Renames the world's display name. The folder (and so the slug and all saved characters) stays as it is. */
    fun rename(slug: String, newName: String): WorldInfo {
        require(slug != DEFAULT_SLUG) { "The default world cannot be renamed." }
        val clean = newName.trim()
        require(clean.isNotEmpty()) { "Give the realm a name." }
        require(clean.length <= RealmBlueprint.MAX_NAME) { "Keep the name to ${RealmBlueprint.MAX_NAME} characters or fewer." }
        val dir = libraryDir(slug) ?: throw IllegalArgumentException("No such world '$slug'.")
        val meta = readMeta(dir) ?: throw IllegalArgumentException("No such world '$slug'.")
        writeMeta(dir, meta.copy(name = clean))
        return WorldInfo(slug, clean, dir, meta.origin)
    }

    /**
     * Permanently deletes the world and everything in it: its content, accounts and characters. Only worlds this library
     * made (a folder under [root] holding a [META_FILE]) can be deleted; the default world never can. The caller must stop
     * any server using the folder first.
     */
    fun delete(slug: String) {
        require(slug != DEFAULT_SLUG) { "The default world cannot be deleted." }
        val dir = libraryDir(slug) ?: throw IllegalArgumentException("No such world '$slug'.")
        check(readMeta(dir) != null) { "'$slug' is not a world made by this library." }
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    /** The folder of library world [slug], only when [slug] is a plain folder name directly under [root]. */
    private fun libraryDir(slug: String): Path? {
        if (slug.isEmpty() || slug.contains('/') || slug.contains('\\') || slug == "." || slug == "..") return null
        val dir = root.resolve(slug)
        return dir.takeIf { it.isDirectory() && it.parent?.toAbsolutePath()?.normalize() == root.toAbsolutePath().normalize() }
    }

    private fun writeMeta(dir: Path, meta: WorldMeta) {
        val tmp = dir.resolve("$META_FILE.tmp")
        Files.writeString(tmp, json.encodeToString(WorldMeta.serializer(), meta) + "\n")
        Files.move(tmp, dir.resolve(META_FILE), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun readMeta(dir: Path): WorldMeta? {
        val file = dir.resolve(META_FILE)
        if (!file.exists()) return null
        return try {
            json.decodeFromString(WorldMeta.serializer(), Files.readString(file))
        } catch (e: Exception) {
            null
        }
    }

    private fun uniqueSlug(base: String): String {
        val onDisk = if (root.isDirectory()) Files.list(root).use { s -> s.map { it.fileName.toString() }.toList() } else emptyList()
        val taken = list().map { it.slug }.toSet() + onDisk
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    companion object {
        const val DEFAULT_SLUG = "default"
        const val META_FILE = "world.meta.json"

        /** Finds a game project under `games/` from the working directory or one level up (running from a module folder). */
        fun findGame(folder: String): Path? =
            listOf(Path.of("games/$folder"), Path.of("../games/$folder"))
                .firstOrNull { Files.exists(it.resolve("game.json")) && Files.isDirectory(it.resolve("data")) }

        /** Every installed game project a template can copy from, auto-discovering any `games/<folder>/game.json`. */
        fun findGameDirs(gamesRoot: Path? = null): Map<RealmTemplate, Path> {
            val out = linkedMapOf<RealmTemplate, Path>()
            if (gamesRoot == null) {
                for (t in RealmTemplate.entries) {
                    val folder = t.gameFolder ?: continue
                    val path = findGame(folder) ?: continue
                    out[t] = path
                }
            }
            val rootDir = gamesRoot ?: listOf(Path.of("games"), Path.of("../games")).firstOrNull { Files.isDirectory(it) }
            if (rootDir != null && Files.isDirectory(rootDir)) {
                val subDirs = Files.list(rootDir).use { s -> s.filter { Files.isDirectory(it) }.sorted().toList() }
                for (dir in subDirs) {
                    if (!Files.exists(dir.resolve("game.json")) || !Files.isDirectory(dir.resolve("data"))) continue
                    val folder = dir.fileName.toString()
                    val known = RealmTemplate.entries.firstOrNull { it.gameFolder == folder }
                    if (known != null) {
                        out.putIfAbsent(known, dir)
                    } else {
                        val def = runCatching { GameDefinitionLoader.load(dir).game.definition }.getOrNull()
                        // A game whose id is a built-in template's (games/blank is what the Blank template scaffolds) is not listed twice.
                        if (RealmTemplate.entries.any { it.id == (def?.id?.ifBlank { folder } ?: folder) }) continue
                        val template = RealmTemplate(
                            id = def?.id?.ifBlank { folder } ?: folder,
                            title = def?.name?.ifBlank { folder } ?: folder,
                            description = def?.description?.ifBlank { "Custom game template in games/$folder." }
                                ?: "Custom game template in games/$folder.",
                            gameFolder = folder
                        )
                        out[template] = dir
                    }
                }
            }
            return out
        }

        /** Copies a game project's `game.json`, `data/`, `scripts/` and `assets/` into a realm folder so the realm owns an editable copy. */
        /** The built-in systems a game folder's own `server.json` switches off (none when it has no file or it cannot be read). */
        fun templateDisabledModules(gameDir: Path): Set<OptionalModule> =
            runCatching { ServerConfig.load(gameDir).modules.filterValues { !it }.keys.mapNotNull(OptionalModule::fromConfigKey).toSet() }
                .getOrDefault(emptySet())

        private fun copyGame(source: Path, target: Path) {
            for (name in listOf("game.json", "data", "scripts", "assets")) {
                val from = source.resolve(name)
                if (!Files.exists(from)) continue
                Files.walk(from).use { stream ->
                    stream.forEach { path ->
                        val dest = target.resolve(source.relativize(path).toString())
                        if (Files.isDirectory(path)) Files.createDirectories(dest) else {
                            dest.parent?.let { Files.createDirectories(it) }
                            Files.copy(path, dest)
                        }
                    }
                }
            }
        }

        /** Lowercase `[a-z0-9-]` identifier; never empty and never the reserved `default`. */
        fun slugify(name: String): String {
            val s = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-')
            return when {
                s.isEmpty() -> "world"
                s == DEFAULT_SLUG -> "world-default"
                else -> s
            }
        }
    }
}
