package com.morgana.server

import com.morgana.persistence.AssetsConfig
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerConfig
import com.morgana.persistence.TlsMode
import com.morgana.server.admin.AdminApi
import com.morgana.server.admin.AdminHttpServer
import com.morgana.server.admin.AdminToken
import com.morgana.server.admin.CoordinatorAdminBackend
import com.morgana.server.plugins.PluginManager
import com.morgana.server.tls.ServerTls
import com.morgana.persistence.ReplayLogCodec
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * One place that assembles a running Morgana server: data store, coordinator, Netty transport, and (optionally)
 * the content file watcher. Used by the dedicated server entry point and by the desktop client's embedded server,
 * so both behave identically (including hot reload of edited content files).
 */
class MorganaServerRuntime private constructor(
    val coordinator: MorganaWorldCoordinator,
    private val server: MorganaNettyServer,
    private val watcher: ContentWatcher?,
    private val replayFile: Path? = null,
    /** The plugin loader, or null when this server does not load plugins. */
    val plugins: PluginManager? = null,
    /** The operator's web panel and REST API, or null when `admin.enabled` is off in `server.json`. */
    private val admin: AdminHttpServer? = null
) : AutoCloseable {

    /** The TCP port actually bound (useful when started with port 0). */
    val port: Int get() = server.boundPort

    /** The port of the admin web panel, or null when it is off. */
    val adminPort: Int? get() = admin?.boundPort

    /** The WebSocket port for browser clients, or null when it is not enabled. */
    val webSocketPort: Int? get() = server.boundWebSocketPort

    /** Per-address connection limits and bans (null if never created). */
    val connectionGate: ConnectionGate? get() = server.gate

    /** SHA-256 fingerprint of the server certificate when TLS is on (what invite codes carry), else null. */
    val tlsFingerprint: String? get() = server.tls?.fingerprint

    /** The effective TLS mode (`off` when no TLS is configured). */
    val tlsMode: TlsMode get() = server.tls?.mode ?: TlsMode.OFF

    override fun close() {
        admin?.close()
        watcher?.close()
        plugins?.close()
        server.close()
        coordinator.close()
        coordinator.dataStore.close()
        if (replayFile != null) {
            val log = coordinator.stopRecording()
            if (log != null) {
                ReplayLogCodec.write(replayFile, log)
                val note = if (log.gaps.isEmpty()) "complete" else "INCOMPLETE, unrecorded: ${log.gaps.joinToString()}"
                LoggerFactory.getLogger("Replay").info("Wrote replay log with {} inputs to {} ({})", log.inputs.size, replayFile, note)
            }
        }
    }

    companion object {
        /** Environment variable that disables the file watcher when set to `off`. */
        const val WATCH_ENV = "MORGANA_CONTENT_WATCH"

        /** When set to a file path, every simulation input is recorded and written there on shutdown. */
        const val RECORD_REPLAY_ENV = "MORGANA_RECORD_REPLAY"

        /** Environment variable pointing to a game project directory containing game.json, data/, and scripts/. */
        const val GAME_ENV = "MORGANA_GAME"

        /**
         * The URL clients download the game's art from: the operator's `assets.baseUrl` when set, otherwise this server's own
         * HTTP (WebSocket) port with `{host}` standing for the host the player connected to. Null when neither exists.
         * Over `https` only when TLS is required, because the same port also accepts plain connections in the other modes.
         */
        fun resolveAssetBaseUrl(config: AssetsConfig?, webSocketPort: Int?, tls: ServerTls?): String? {
            config?.baseUrl?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
            if (webSocketPort == null) return null
            val scheme = if (tls?.mode == TlsMode.REQUIRED) "https" else "http"
            return "$scheme://{host}:$webSocketPort/assets"
        }

        fun resolveDefaultGameDir(dataDir: Path): Path {
            System.getenv(GAME_ENV)?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
            return dataDir
        }

        fun start(
            dataDir: Path,
            port: Int,
            watchContent: Boolean = System.getenv(WATCH_ENV) != "off",
            recordReplayTo: Path? = System.getenv(RECORD_REPLAY_ENV)?.takeIf { it.isNotBlank() }?.let { Path.of(it) },
            /** TLS mode used when `server.json` has no `tls` section (dedicated servers: off; hosted worlds: remote). */
            defaultTlsMode: TlsMode = TlsMode.OFF,
            /** Port for browser (WebSocket) clients; null leaves it off. Defaults to the `MORGANA_WS_PORT` environment variable. */
            webSocketPort: Int? = System.getenv(MorganaNettyServer.WEBSOCKET_PORT_ENV)?.toIntOrNull(),
            gameDir: Path? = null,
            /** Folder of plugin jars to load (see [PluginManager]); null loads none. Only the dedicated server passes one. */
            pluginsDir: Path? = null,
            /** A built browser client to serve on [webSocketPort] (see [com.morgana.server.web.WebClientHttpHandler]); null serves none. */
            webClientDir: Path? = null,
            /** Accept one-time login tickets minted by the `dev-ticket` tool (for automation); off by default, see [DevTickets]. */
            devLogin: Boolean = DevTickets.enabledByEnvironment()
        ): MorganaServerRuntime {
            val effectiveGameDir = gameDir ?: resolveDefaultGameDir(dataDir)
            // Resolve TLS first: a broken certificate setup must stop startup, never fall back to plain text.
            val tls = ServerTls.load(dataDir, ServerConfig.load(dataDir).tls, defaultTlsMode)
            val serverConfig = ServerConfig.load(dataDir)
            val dataStore = MorganaDataStore(rootDir = dataDir, gameDir = effectiveGameDir)
            val coordinator = MorganaWorldCoordinator(dataStore)
            if (devLogin) {
                coordinator.devTickets = DevTickets(dataDir)
                org.slf4j.LoggerFactory.getLogger("Security").warn("Dev login is ON: one-time tickets from '{}' are accepted in place of a password. Do not leave this on for a public server.", dataDir.resolve(DevTickets.DIR))
            }
            val gate = ConnectionGate(ConnectionGateSettings.from(serverConfig.connections), banFile = dataDir.resolve("ip-bans.json"))
            coordinator.connectionGate = gate
            gate.onBan = { key -> coordinator.disconnectBanned(key) }
            if (recordReplayTo != null) coordinator.startRecording()
            val plugins = pluginsDir?.let { PluginManager(it, coordinator.hookRegistry, flags = { coordinator.simulation.modules }, messenger = coordinator::messagePlayer, ui = coordinator.pluginUi) }
            coordinator.plugins = plugins
            plugins?.loadAll()
            val admin = serverConfig.admin?.takeIf { it.enabled }?.let { a ->
                AdminHttpServer(AdminApi(CoordinatorAdminBackend(coordinator), AdminToken.loadOrCreate(dataDir)), a.bindAddress, a.port).also { it.start() }
            }
            val server = MorganaNettyServer(
                port = port,
                coordinator = coordinator,
                tls = tls,
                gate = gate,
                webSocketPort = webSocketPort,
                idleTimeoutSeconds = coordinator.sessionConfig.idleSeconds,
                webClientDir = webClientDir,
                wikiSource = if (serverConfig.wiki?.enabled == false) null else coordinator::gameWikiPages
            )
            server.start()
            coordinator.assetBaseUrl = resolveAssetBaseUrl(serverConfig.assets, server.boundWebSocketPort, tls)
            val watcher = if (watchContent) {
                val log = LoggerFactory.getLogger("ContentReload")
                val trees = listOf(
                    WatchedTree(ChangeKind.CONTENT, dataStore.contentDir) { it.endsWith(".json") },
                    WatchedTree(ChangeKind.SCRIPTS, dataStore.gameDir.resolve("scripts"), recursive = true) { it.endsWith(".lua") },
                    WatchedTree(ChangeKind.ASSETS, dataStore.gameDir.resolve(com.morgana.server.assets.GameAssets.FOLDER), recursive = true),
                    WatchedTree(ChangeKind.GAME, dataStore.gameDir) { it == com.morgana.persistence.GameDefinitionLoader.FILE_NAME }
                )
                ContentWatcher(trees) { kinds ->
                    if (ChangeKind.CONTENT in kinds || ChangeKind.SCRIPTS in kinds) {
                        val result = coordinator.reloadContent()
                        log.info(result.summary)
                        result.report.errors.forEach { log.warn(it.toString()) }
                    }
                    if (ChangeKind.ASSETS in kinds) {
                        val sent = coordinator.refreshAssets()
                        log.info("Game art refreshed: {} file(s) sent to players.", sent.size)
                    }
                    if (ChangeKind.GAME in kinds && coordinator.noteGameFileChanged()) {
                        log.warn("game.json changed on disk; the new rules take effect after a restart.")
                    }
                }.start()
            } else {
                null
            }
            return MorganaServerRuntime(coordinator, server, watcher, recordReplayTo, plugins, admin)
        }
    }
}
