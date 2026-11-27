package com.morgana.server.tools

import com.morgana.domain.validation.ContentCollection
import com.morgana.domain.validation.IssueSeverity
import com.morgana.script.luak.LuakScriptValidator
import com.morgana.script.luak.ScriptValidationResult
import com.morgana.server.DevTickets
import com.morgana.server.OwnerPolicy
import com.morgana.persistence.ContentLoader
import com.morgana.persistence.ContentSchemaExporter
import com.morgana.persistence.GameDefinitionFileException
import com.morgana.persistence.GameDefinitionLoader
import com.morgana.persistence.GameScaffolder
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.PackImportMode
import com.morgana.persistence.TlsMode
import com.morgana.protocol.tls.Fingerprint
import com.morgana.protocol.tls.InviteCode
import com.morgana.server.tls.ServerTls
import com.morgana.server.tls.TlsSetupException
import com.morgana.persistence.PlayerDataBackend
import com.morgana.persistence.StorageMigration
import com.morgana.persistence.StorageMigrationException
import com.morgana.persistence.ReplayLogCodec
import com.morgana.persistence.ServerConfig
import com.morgana.persistence.ServerConfigException
import com.morgana.domain.sim.OptionalModule
import com.morgana.persistence.WorldPack
import com.morgana.persistence.WorldPackException
import com.morgana.server.worlds.RealmBlueprint
import com.morgana.server.worlds.RealmTemplate
import com.morgana.server.worlds.WorldLibrary
import com.morgana.domain.replay.ReplayPlayer
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/** Shared formatter for the `validate --json` report (creating a `Json` per call is slow). */
private val reportJson = Json { prettyPrint = true; encodeDefaults = true }

/**
 * Command-line content tooling for humans, scripts, and AI agents (no running server required).
 *
 * - `validate [--dir server-data/world] [--json]`: validate every content file; exit 1 on errors
 * - `schema [--out docs/schemas]`: export JSON Schemas for every content collection
 * - `seed [--dir server-data]`: write the starter world if the data directory is empty
 * - `replay --file <log.json> [--dir server-data]`: replay a recorded session against the data directory; exit 1 on divergence
 * - `pack-export --out <pack.zip|folder> --name <name> [--dir server-data/world] [--description ..] [--author ..]`: export a validated world pack
 * - `pack-import --file <pack> [--dir server-data/world] [--mode new|merge|replace]`: validate and import a world pack
 * - `worlds [--root worlds]` / `world-new --name <name> [--template blank|pack|<game id>] [--pack <pack>] [--genre rpg|roguelike|farming|roleplay|action] [--disable trading,guilds] [--enable arena] [--root worlds]`: list / create library worlds (the same choices as the New Realm Wizard)
 * - `modules [--dir server-data] [--enable a,b] [--disable c]`: show or change optional module switches in server.json
 * - `storage [--dir server-data] [--migrate json|sqlite]`: show the player-data backend, or migrate to another one
 * - `tls [--dir server-data] [--host <public address>] [--port 7001]`: show TLS mode, certificate fingerprint and an invite code (creates the certificate if needed)
 * - `owner --user <name> [--dir server-data]`: list a username as a server Owner (see OwnerPolicy)
 * - `game-wiki --game <dir> [--out build/game-wiki/<id>]`: write a browsable, cross-linked wiki of the game's items, monsters, spells, quests, maps and more, plus a balance report, as static files
 * - `game [--dir server-data] [--write]`: validate `game.json` (or show the built-in Morgana Classic); `--write` creates `game.json` from the built-in one so it can be edited
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull()
    val options = parseOptions(args.drop(1))
    val gameOpt = options["game"]?.let { Paths.get(it) }

    fun resolveWorldDir(): Path {
        if (options.containsKey("dir")) return Paths.get(options["dir"]!!)
        if (gameOpt != null) {
            val d = gameOpt.resolve("data")
            if (Files.isDirectory(d)) return d
            val w = gameOpt.resolve("world")
            if (Files.isDirectory(w)) return w
            return d
        }
        val defaultGame = Paths.get("games/blank/data")
        if (Files.isDirectory(defaultGame)) return defaultGame
        return Paths.get("server-data/world")
    }

    fun resolveGameDir(): Path {
        if (options.containsKey("dir")) return Paths.get(options["dir"]!!)
        if (gameOpt != null) return gameOpt
        val defaultGame = Paths.get("games/blank")
        if (Files.exists(defaultGame.resolve("game.json")) || Files.isDirectory(defaultGame.resolve("data"))) return defaultGame
        return Paths.get("server-data")
    }

    fun resolveScriptsDir(): Path {
        if (options.containsKey("dir")) return Paths.get(options["dir"]!!)
        if (gameOpt != null) return gameOpt.resolve("scripts")
        val defaultGameScripts = Paths.get("games/blank/scripts")
        if (Files.isDirectory(defaultGameScripts)) return defaultGameScripts
        return Paths.get("server-data/scripts")
    }

    val code = when (command) {
        "validate" -> validate(resolveWorldDir(), "json" in options)
        "schema" -> exportSchemas(Paths.get(options["out"] ?: "docs/schemas"))
        "seed" -> seed(resolveGameDir())
        "replay" -> replayLog(Paths.get(options["dir"] ?: "server-data"), options["file"].orEmpty())
        "pack-export" -> packExport(resolveWorldDir(), options["out"].orEmpty(), options["name"].orEmpty(), options["description"].orEmpty(), options["author"].orEmpty())
        "pack-import" -> packImport(resolveWorldDir(), options["file"].orEmpty(), options["mode"] ?: "new")
        "worlds" -> listWorlds(Paths.get(options["root"] ?: "worlds"))
        "world-new" -> newWorld(
            Paths.get(options["root"] ?: "worlds"), options["name"].orEmpty(), options["pack"],
            options["template"].orEmpty(), options["disable"].orEmpty(), options["enable"].orEmpty(), options["genre"].orEmpty()
        )
        "modules" -> modulesCommand(Paths.get(options["dir"] ?: "server-data"), options["enable"].orEmpty(), options["disable"].orEmpty())
        "plugins" -> pluginsCommand(Paths.get(options["dir"] ?: "server-data").resolve("plugins"))
        "storage" -> storageCommand(Paths.get(options["dir"] ?: "server-data"), options["migrate"].orEmpty())
        "tls" -> tlsCommand(Paths.get(options["dir"] ?: "server-data"), options["host"].orEmpty(), options["port"]?.toIntOrNull() ?: 7001)
        "dev-ticket" -> devTicket(Paths.get(options["dir"] ?: "server-data"), options["user"].orEmpty(), options["ttl"]?.toLongOrNull() ?: DevTickets.DEFAULT_TTL_SECONDS)
        "owner" -> grantOwner(Paths.get(options["dir"] ?: "server-data"), options["user"].orEmpty())
        "game" -> gameCommand(resolveGameDir(), "write" in options)
        "new-game" -> newGameCommand(options["name"].orEmpty(), options["dir"], options["id"])
        "game-wiki" -> gameWikiCommand(resolveGameDir(), options["out"])
        "validate-art" -> validateArt(if (gameOpt != null) listOf(gameOpt) else listOf(Paths.get("games")).filter { Files.isDirectory(it) }
            .flatMap { root -> Files.list(root).use { s -> s.filter { Files.exists(it.resolve("game.json")) }.sorted().toList() } })
        "validate-scripts" -> {
            val targets = if (options.containsKey("dir") || gameOpt != null) {
                listOf(resolveScriptsDir())
            } else {
                listOf(Paths.get("games/blank/scripts"), Paths.get("server-data/scripts")).filter { Files.isDirectory(it) }.ifEmpty { listOf(Paths.get("server-data/scripts")) }
            }
            val codes = targets.map { validateScripts(it) }
            if (codes.any { it != 0 }) 1 else 0
        }
        else -> {
            System.err.println("Usage: validate-art [--game <dir>] | validate [--game <dir> | --dir <world dir>] [--json] | schema [--out <dir>] | seed [--game <dir> | --dir <data dir>] | new-game --name <n> [--dir <dir>] [--id <id>] | pack-export --out <pack> --name <n> | pack-import --file <pack> [--mode new|merge|replace] | worlds | world-new --name <n> [--pack <pack>] | modules [--enable a,b] [--disable c] | plugins [--dir <data dir>] | storage [--migrate json|sqlite] | tls [--host <address>] | replay --file <log> [--dir <data dir>] | owner --user <name> [--dir <data dir>] | dev-ticket --user <name> [--ttl <seconds>] [--dir <data dir>]")
            2
        }
    }
    exitProcess(code)
}

internal fun parseOptions(args: List<String>): Map<String, String> {
    val out = linkedMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val key = a.removePrefix("--")
            val value = args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }
            out[key] = value ?: ""
            i += if (value != null) 2 else 1
        } else {
            i++
        }
    }
    return out
}

/**
 * The rules the content in [worldDir] should be checked against: the `game.json` next to a game's `data/` (or `world/`) folder,
 * or inside [worldDir] itself, falling back to the engine default blank game when there is none.
 */
internal fun gameDefinitionFor(worldDir: Path): com.morgana.domain.game.GameDefinition {
    val dir = listOfNotNull(worldDir.toAbsolutePath().parent, worldDir)
        .firstOrNull { Files.exists(GameDefinitionLoader.fileIn(it)) }
        ?: return com.morgana.domain.game.BlankGame.definition
    return GameDefinitionLoader.load(dir).game.definition
}

internal fun validate(worldDir: Path, asJson: Boolean): Int {
    if (!Files.isDirectory(worldDir)) {
        System.err.println("World directory '$worldDir' not found. Run the seed command or start the server once to create it.")
        return 2
    }
    val report = ContentLoader.load(worldDir).fullReport(gameDefinitionFor(worldDir))
    if (asJson) {
        println(reportJson.encodeToString(report))
    } else {
        report.issues.forEach { println(it) }
        println("${report.errors.size} error(s), ${report.warnings.size} warning(s) in $worldDir")
    }
    return if (report.issues.any { it.severity == IssueSeverity.ERROR }) 1 else 0
}

internal fun exportSchemas(outDir: Path): Int {
    Files.createDirectories(outDir)
    val json = Json { prettyPrint = true }
    for (col in ContentCollection.entries) {
        val file = outDir.resolve("${col.directoryName}.schema.json")
        Files.writeString(file, json.encodeToString(ContentSchemaExporter.schemaFor(col)) + "\n")
        println("Wrote $file")
    }
    val gameSchema = outDir.resolve("game.schema.json")
    Files.writeString(gameSchema, json.encodeToString(ContentSchemaExporter.gameDefinitionSchema()) + "\n")
    println("Wrote $gameSchema")
    return 0
}

internal fun gameCommand(dataDir: Path, write: Boolean): Int {
    if (write) {
        if (GameDefinitionLoader.writeBuiltInIfMissing(dataDir)) {
            println("Wrote ${GameDefinitionLoader.fileIn(dataDir)} (the built-in Morgana Classic definition); edit it to change the game.")
        } else {
            println("${GameDefinitionLoader.fileIn(dataDir)} already exists; nothing to do.")
        }
    }
    val loaded = try {
        GameDefinitionLoader.load(dataDir)
    } catch (e: GameDefinitionFileException) {
        System.err.println(e.message)
        return 1
    }
    val definition = loaded.game.definition
    println("Game: ${definition.name} (${definition.id}), from ${loaded.source ?: "the built-in definition (no game.json)"}")
    println("Attributes: " + definition.attributes.joinToString { "${it.id}${if (it.allocatable) "+" else ""}" }.ifEmpty { "(none)" } + "   (+ = allocatable)")
    println("Vitals: " + definition.vitals.joinToString { "${it.id}${if (it.id == loaded.game.lifeVitalId) "*" else ""}" } + "   (* = life)")
    println("Levels: 1 to ${definition.progression.maxLevel}")
    println("OK: every formula parsed and passed the trial evaluation.")
    return 0
}

internal fun seed(dataDir: Path): Int {
    val store = MorganaDataStore(dataDir)
    if (!store.world.isEmpty()) {
        println("Data directory '$dataDir' already contains a world; nothing to do.")
        return 0
    }
    GameScaffolder.seedIfEmpty(store.world)
    println("Seeded the blank game's content into ${dataDir.resolve("world")}")
    return 0
}

/** Mints a one-time login ticket for an existing account (see [DevTickets]); prints only the ticket on stdout so scripts can capture it. */
internal fun devTicket(dataDir: Path, username: String, ttlSeconds: Long): Int {
    if (username.isBlank()) {
        System.err.println("Missing --user <name>")
        return 2
    }
    val store = MorganaDataStore(dataDir)
    if (store.accounts.findByUsername(username.trim()) == null) {
        System.err.println("No account named '$username' in $dataDir. Register it once in the client first.")
        return 2
    }
    return try {
        val ticket = DevTickets(dataDir).mint(username, ttlSeconds)
        System.err.println("Ticket for '$username' (single use, expires in $ttlSeconds s; the server must run with --dev-login):")
        println(ticket)
        0
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        2
    }
}

internal fun grantOwner(dataDir: Path, username: String): Int {
    if (username.isBlank()) {
        System.err.println("Missing --user <name>")
        return 2
    }
    return try {
        val added = OwnerPolicy.grant(dataDir, username)
        println(if (added) "Added '$username' to ${dataDir.resolve(OwnerPolicy.OWNERS_FILE)}" else "'$username' is already listed as an owner.")
        0
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        2
    }
}

internal fun replayLog(dataDir: Path, file: String): Int {
    if (file.isBlank() || !Files.isRegularFile(Paths.get(file))) {
        System.err.println("Missing or unreadable --file <replay log>")
        return 2
    }
    val log = ReplayLogCodec.read(Paths.get(file))
    // Mirror the coordinator's startup: content from disk, persisted guilds, default seed.
    val store = MorganaDataStore(dataDir)
    val sim = WorldSimulation(SeededRandomSource(log.seed), game = GameDefinitionLoader.loadFor(store.gameDir, store.rootDir))
    sim.modules = ServerConfig.load(dataDir).moduleFlags()
    store.world.loadIntoSimulation(sim)
    store.social.guilds.findAll().forEach { sim.guilds[it.id] = it }
    if (log.gaps.isNotEmpty()) {
        println("WARNING: log is incomplete (unrecorded: ${log.gaps.joinToString()}); a divergence may be expected.")
    }
    val result = ReplayPlayer.replay(sim, log)
    println("Replayed ${log.inputs.size} inputs to tick ${result.finalTick}; content ${if (result.contentMatches) "matches" else "DIFFERS"}.")
    result.divergences.forEach { println("DIVERGED at tick ${it.tick}: expected ${it.expected}, got ${it.actual}") }
    if (result.ok) println("OK: final state hash ${result.finalHash}")
    return if (result.ok) 0 else 1
}

internal fun packExport(worldDir: Path, out: String, name: String, description: String, author: String): Int {
    if (out.isBlank() || name.isBlank()) {
        System.err.println("Usage: pack-export --out <pack.zip|folder> --name <name> [--dir <world dir>]")
        return 2
    }
    return try {
        val m = WorldPack.export(worldDir, Paths.get(out), name, description, author)
        println("Exported '${m.name}' (${m.counts.values.sum()} documents) to $out")
        0
    } catch (e: WorldPackException) {
        System.err.println(e.message)
        e.report?.errors?.forEach { System.err.println(it) }
        1
    }
}

internal fun packImport(worldDir: Path, file: String, mode: String): Int {
    val importMode = when (mode.lowercase()) {
        "new" -> PackImportMode.NEW_ONLY
        "merge" -> PackImportMode.MERGE
        "replace" -> PackImportMode.REPLACE
        else -> { System.err.println("Unknown --mode '$mode' (use new, merge or replace)"); return 2 }
    }
    if (file.isBlank()) { System.err.println("Missing --file <pack>"); return 2 }
    return try {
        val r = WorldPack.import(Paths.get(file), worldDir, importMode)
        println("Imported '${r.manifest.name}' (${r.filesWritten} files) into $worldDir; ${r.report.warnings.size} warning(s).")
        r.backup?.let { println("Previous content kept at $it") }
        0
    } catch (e: WorldPackException) {
        System.err.println(e.message)
        e.report?.errors?.forEach { System.err.println(it) }
        1
    }
}

internal fun listWorlds(root: Path): Int {
    WorldLibrary(root).list().forEach { println("${it.slug}\t${it.name}\t${it.origin}\t${it.dataDir}") }
    return 0
}

/**
 * `world-new`: the CLI twin of the New Realm Wizard. [disable] and [enable] are comma lists of system config keys (the
 * switches a template starts with can be turned back on with [enable]); [genre] starts from a genre (`rpg`, `roguelike`, ...)
 * by switching off every system not tagged for it, before [disable] and [enable] are applied.
 */
internal fun newWorld(root: Path, name: String, pack: String?, template: String = "", disable: String = "", enable: String = "", genre: String = ""): Int {
    if (name.isBlank()) { System.err.println("Missing --name <name>"); return 2 }
    val lib = WorldLibrary(root)
    val chosen = when {
        template.isNotBlank() -> lib.templateFromId(template) ?: run {
            System.err.println("Unknown template '$template'; valid: ${lib.availableTemplates().joinToString { it.id }}")
            return 2
        }
        pack != null -> RealmTemplate.PACK
        else -> RealmTemplate.BLANK
    }
    val off = mutableSetOf<OptionalModule>()
    val on = mutableSetOf<OptionalModule>()
    if (genre.isNotBlank()) {
        if (!com.morgana.domain.sim.SystemGroups.isGenre(genre.trim())) {
            System.err.println("Unknown genre '$genre'; valid: ${com.morgana.domain.sim.SystemTags.TITLES.keys.joinToString()}")
            return 2
        }
        com.morgana.domain.sim.SystemGroups.disabledKeysFor(genre.trim()).mapNotNullTo(off) { OptionalModule.fromConfigKey(it) }
    }
    for (key in enable.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
        on += OptionalModule.fromConfigKey(key) ?: run {
            System.err.println("Unknown module '$key'; valid: ${OptionalModule.entries.joinToString { it.configKey }}")
            return 2
        }
    }
    off -= on
    for (key in disable.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
        off += OptionalModule.fromConfigKey(key) ?: run {
            System.err.println("Unknown module '$key'; valid: ${OptionalModule.entries.joinToString { it.configKey }}")
            return 2
        }
    }
    val uiLayout = genre.trim().takeIf { it.isNotEmpty() }
        ?.let { com.morgana.domain.game.UiLayout.defaultForGenre(it) }
        ?.takeIf { it != com.morgana.domain.game.UiLayout.CLASSIC }
    return try {
        val blueprint = RealmBlueprint(name, chosen, off, pack?.let { Paths.get(it) }, enabledModules = on, uiLayout = uiLayout)
        blueprint.problems().firstOrNull()?.let { System.err.println(it); return 2 }
        val w = lib.create(blueprint)
        println("Created world '${w.name}' (${w.origin}) at ${w.dataDir}")
        0
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        2
    } catch (e: WorldPackException) {
        System.err.println(e.message)
        e.report?.errors?.forEach { System.err.println(it) }
        1
    }
}

/** `plugins`: list the jars in `<data dir>/plugins` and say whether each manifest is usable (nothing is started). Exit 1 if any is not. */
internal fun pluginsCommand(dir: Path): Int {
    val found = com.morgana.server.plugins.PluginManager.inspect(dir)
    if (found.isEmpty()) {
        println("No plugin jars in $dir")
        return 0
    }
    found.forEach { println("${it.file}\t${it.id}\t${it.version}\t${it.problem?.let { p -> "NOT USABLE: $p" } ?: "ok"}") }
    return if (found.any { it.problem != null }) 1 else 0
}

internal fun modulesCommand(dataDir: Path, enable: String, disable: String): Int {
    fun parse(csv: String): List<OptionalModule>? {
        val out = mutableListOf<OptionalModule>()
        for (key in csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            out += OptionalModule.fromConfigKey(key) ?: run {
                System.err.println("Unknown module '$key'; valid: ${OptionalModule.entries.joinToString { it.configKey }}")
                return null
            }
        }
        return out
    }
    return try {
        var config = ServerConfig.load(dataDir)
        if (enable.isNotBlank() || disable.isNotBlank()) {
            val on = parse(enable) ?: return 2
            val off = parse(disable) ?: return 2
            if (on.intersect(off.toSet()).isNotEmpty()) {
                System.err.println("A module cannot be both enabled and disabled")
                return 2
            }
            config = config.withModules(enable = on, disable = off)
            ServerConfig.save(dataDir, config)
            println("Saved ${ServerConfig.fileIn(dataDir)} (restart the server to apply).")
        }
        val flags = config.moduleFlags()
        OptionalModule.entries.forEach { println("${it.configKey}\t${if (flags.isEnabled(it)) "on" else "OFF"}\t${it.displayName}") }
        0
    } catch (e: ServerConfigException) {
        System.err.println(e.message)
        1
    }
}

internal fun storageCommand(dataDir: Path, migrateTo: String): Int {
    return try {
        if (migrateTo.isNotBlank()) {
            val target = PlayerDataBackend.fromConfigKey(migrateTo.lowercase()) ?: run {
                System.err.println("Unknown backend '$migrateTo'; valid: ${PlayerDataBackend.entries.joinToString { it.configKey }}")
                return 2
            }
            val report = StorageMigration.migrate(dataDir, target)
            println("Migrated player data ${report.from.configKey} -> ${report.to.configKey}: " +
                report.counts.entries.joinToString { "${it.key}=${it.value}" })
            println("The old ${report.from.configKey} data was left in place; delete it once you have verified the server. Stop the server before running this and restart it afterwards.")
        }
        println("Player data backend: ${ServerConfig.load(dataDir).playerDataBackend().configKey}")
        0
    } catch (e: StorageMigrationException) {
        System.err.println(e.message)
        1
    } catch (e: ServerConfigException) {
        System.err.println(e.message)
        1
    }
}

internal fun tlsCommand(dataDir: Path, publicHost: String, port: Int): Int {
    return try {
        val config = ServerConfig.load(dataDir).tls
        // Unconfigured dedicated servers are plain; show what enabling TLS would look like instead of failing.
        val mode = config?.let { TlsMode.fromConfigKey(it.mode) } ?: TlsMode.OFF
        println("TLS mode: ${mode.configKey}" + if (config == null) " (no tls section in server.json)" else "")
        val tls = ServerTls.load(dataDir, config, if (mode == TlsMode.OFF) TlsMode.REQUIRED else mode)
            ?: return 0
        println("Certificate fingerprint (SHA-256): ${Fingerprint.display(tls.fingerprint)}")
        println("Valid until: ${tls.certificate.notAfter}")
        if (publicHost.isNotBlank()) println("Invite code: ${InviteCode(publicHost, port, tls.fingerprint).format()}")
        else println("Invite code: morgana://<your-address>:$port#fp=${tls.fingerprint}   (or pass --host <address>)")
        if (mode == TlsMode.OFF) println("Note: TLS is still OFF. Set \"tls\": { \"mode\": \"required\" } in server.json to use this certificate.")
        0
    } catch (e: TlsSetupException) {
        System.err.println(e.message)
        1
    } catch (e: ServerConfigException) {
        System.err.println(e.message)
        1
    }
}

internal fun validateScripts(scriptsDir: Path): Int {
    if (!Files.isDirectory(scriptsDir)) {
        println("Scripts directory '$scriptsDir' not present (no scripts to validate).")
        return 0
    }
    var errors = 0
    var count = 0
    Files.walk(scriptsDir).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".lua") }.forEach { file ->
            count++
            val source = Files.readString(file)
            val relPath = scriptsDir.relativize(file).toString().replace('\\', '/')
            when (val res = LuakScriptValidator.validate(relPath, source)) {
                is ScriptValidationResult.Valid -> {}
                is ScriptValidationResult.Invalid -> {
                    System.err.println("ERROR $relPath:${res.line ?: 1}: ${res.message}")
                    errors++
                }
            }
        }
    }
    println("Validated $count script(s), $errors error(s) in $scriptsDir.")
    return if (errors > 0) 1 else 0
}

internal fun newGameCommand(name: String, dirArg: String?, idArg: String?): Int {
    val cleanName = name.trim().removeSurrounding("'").removeSurrounding("\"").trim()
    if (cleanName.isBlank()) {
        System.err.println("Error: --name <name> is required for new-game.")
        return 2
    }
    val cleanDir = dirArg?.trim()?.removeSurrounding("'")?.removeSurrounding("\"")?.trim()
    val cleanId = idArg?.trim()?.removeSurrounding("'")?.removeSurrounding("\"")?.trim()
    val slug = cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "custom-game" }
    val id = (cleanId?.takeIf { it.isNotBlank() } ?: cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')).ifBlank { "custom_game" }
    val targetDir = if (cleanDir.isNullOrBlank()) Paths.get("games", slug) else Paths.get(cleanDir)

    return try {
        GameScaffolder.scaffold(targetDir, name = cleanName, id = id)
        println("Created new game '$cleanName' (id: $id) at $targetDir")
        val unixPath = targetDir.toString().replace('\\', '/')
        println("To run this game with the SDK:")
        println("  bin/morgana-server --game $unixPath --data server-data/${targetDir.fileName}")
        println("From the engine source:")
        println("  .\\gradlew.bat :server-app:run --args=\"--game $unixPath\"")
        0
    } catch (e: Exception) {
        System.err.println("Failed to scaffold game: ${e.message}")
        1
    }
}

/** Writes the game wiki (see [com.morgana.server.tools.wiki.GameWiki]) for the game in [gameDir] to [out]; exit 1 when the game cannot be read. */
internal fun gameWikiCommand(gameDir: Path, out: String?): Int {
    val dataDir = listOf("data", "world").map { gameDir.resolve(it) }.firstOrNull { Files.isDirectory(it) }
    if (dataDir == null) { System.err.println("No data folder in $gameDir. Pass --game <folder>."); return 1 }
    val game = try {
        com.morgana.persistence.GameDefinitionLoader.load(gameDir).game
    } catch (e: com.morgana.persistence.GameDefinitionFileException) {
        System.err.println(e.message); return 1
    }
    val loaded = com.morgana.persistence.ContentLoader.load(dataDir)
    val target = Paths.get(out ?: "build/game-wiki/${game.definition.id}")
    val pages = com.morgana.server.tools.wiki.GameWiki(game, loaded.catalog).pages()
    Files.createDirectories(target)
    for ((name, bytes) in pages) Files.write(target.resolve(name), bytes)
    println("Wrote ${pages.size} files for ${game.definition.name} to ${target.toAbsolutePath()}")
    println("Open ${target.resolve("index.html").toAbsolutePath()} in a browser.")
    return 0
}

/** Prints the art report for each game and returns 1 if any game ships art the server would refuse. */
internal fun validateArt(games: List<Path>): Int {
    if (games.isEmpty()) { println("No game projects found. Pass --game <folder>."); return 2 }
    var failed = false
    for (game in games) {
        val report = com.morgana.server.assets.ArtCheck.check(game)
        val shipped = report.counts.entries.sortedBy { it.key.ordinal }.joinToString(", ") { "${it.value} ${it.key.name.lowercase()}" }.ifEmpty { "no art of its own (the built-in art is used)" }
        println("${game.fileName}: $shipped")
        report.errors.forEach { println("  ERROR   $it") }
        report.warnings.forEach { println("  warning $it") }
        if (!report.isClean) failed = true
    }
    println(if (failed) "Art check: errors found." else "Art check: ok.")
    return if (failed) 1 else 0
}
