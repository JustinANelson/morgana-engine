package com.morgana.server

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Dedicated authoritative server entrypoint for Morgana ORPG.
 *
 * Options:
 *   --port <n>, -p <n>     Bind TCP port (default: 7001)
 *   --game <dir>, -g <dir> Game directory with game.json, data/, and scripts/ (default: games/blank or server-data)
 *   --data <dir>, -d <dir> Server state directory with accounts, characters, social (default: server-data)
 *   --web [dir]            Also serve the browser client (phones and other computers open http://<this machine>:7002/).
 *                          Without a folder: web/ next to the server (the SDK) or this repository's built client-web.
 *                          Turns the WebSocket port on (7002 unless --ws-port or MORGANA_WS_PORT says otherwise).
 *   --ws-port <n>          WebSocket port for browser clients (default: the MORGANA_WS_PORT environment variable, else off)
 *   --admin-port <n>       Turn the admin REST API on at this loopback port (token in <data dir>/admin-token.txt); see `tools/morgana-ctl`.
 *   --agent                For an LLM or script driving the server: --dev-login plus --admin-port 7003. Never on a public server.
 *   --dev-login            Accept one-time login tickets from the `dev-ticket` tool (automation; also MORGANA_DEV_LOGIN=1). Off by default.
 *
 * Plugin jars in `<data dir>/plugins/` are loaded at start (trusted code; see `PluginManager`).
 * Content files under the active game's data directory are hot-reloaded when edited on disk
 * (disable with the environment variable `MORGANA_CONTENT_WATCH=off`).
 * Owner access is granted per [OwnerPolicy] (`MORGANA_OWNERS` / `server-data/owners.txt`, or the first local account).
 */
fun main(args: Array<String>) {
    var port = MorganaNettyServer.DEFAULT_PORT
    var dataDir = Paths.get("server-data")
    var gameDir: Path? = null
    var web = false
    var webDir: Path? = null
    var wsPort: Int? = System.getenv(MorganaNettyServer.WEBSOCKET_PORT_ENV)?.toIntOrNull()
    var devLogin = DevTickets.enabledByEnvironment()
    var adminPort: Int? = null

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--port", "-p" -> {
                args.getOrNull(i + 1)?.toIntOrNull()?.let { port = it }
                i += 2
            }
            "--data", "-d" -> {
                args.getOrNull(i + 1)?.let { dataDir = Paths.get(it) }
                i += 2
            }
            "--game", "-g" -> {
                args.getOrNull(i + 1)?.let { gameDir = Paths.get(it) }
                i += 2
            }
            "--web" -> {
                web = true
                val next = args.getOrNull(i + 1)
                if (next != null && !next.startsWith("-")) { webDir = Paths.get(next); i += 2 } else i++
            }
            "--admin-port" -> {
                args.getOrNull(i + 1)?.toIntOrNull()?.let { adminPort = it }
                i += 2
            }
            "--agent" -> {
                devLogin = true
                if (adminPort == null) adminPort = com.morgana.persistence.AdminConfig.DEFAULT_PORT
                i++
            }
            "--dev-login" -> {
                devLogin = true
                i++
            }
            "--ws-port" -> {
                args.getOrNull(i + 1)?.toIntOrNull()?.let { wsPort = it }
                i += 2
            }
            else -> {
                arg.toIntOrNull()?.let { port = it }
                i++
            }
        }
    }

    val resolvedGameDir = resolveServerGameDir(dataDir, gameDir, System.getenv(MorganaServerRuntime.GAME_ENV))
    val webClientDir = if (!web) null else (webDir ?: com.morgana.server.web.WebClientHttpHandler.findBuilt())
    if (web && (webClientDir == null || !java.nio.file.Files.isRegularFile(webClientDir.resolve("index.html")))) {
        System.err.println(
            if (webDir != null) "--web: no index.html in $webDir"
            else "--web: no built browser client found (web/ in the SDK, or build one with gradlew :client-web:gdx_teavm_web_js_release_build)"
        )
        kotlin.system.exitProcess(2)
    }
    if (web && wsPort == null) wsPort = DEFAULT_WEB_PORT
    val runtime = MorganaServerRuntime.start(
        dataDir = dataDir, port = port, gameDir = resolvedGameDir, pluginsDir = dataDir.resolve("plugins"),
        webSocketPort = wsPort, webClientDir = webClientDir, devLogin = devLogin, adminPort = adminPort
    )
    Runtime.getRuntime().addShutdownHook(Thread { runtime.close() })
    Thread.currentThread().join()
}

/** WebSocket port used by `--web` when none is given: the port the browser client tries by default. */
internal const val DEFAULT_WEB_PORT = 7002

/**
 * Which game a dedicated server serves: `--game`, else the `MORGANA_GAME` environment variable, else the data directory
 * itself when it holds its own `game.json` (a realm made by the New Realm Wizard or `world-new`), else `games/blank`
 * when present, else the data directory (whose `world/` is seeded with the blank game's content if empty).
 */
internal fun resolveServerGameDir(dataDir: Path, gameArg: Path?, gameEnv: String?): Path =
    gameArg
        ?: gameEnv?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        ?: dataDir.takeIf { java.nio.file.Files.exists(it.resolve("game.json")) }
        ?: Paths.get("games/blank").takeIf { java.nio.file.Files.exists(it.resolve("game.json")) }
        ?: dataDir
