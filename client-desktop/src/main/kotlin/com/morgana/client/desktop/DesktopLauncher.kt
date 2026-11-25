package com.morgana.client.desktop

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.morgana.client.access.UiScales
import com.morgana.client.LocalWorldChoice
import com.morgana.client.LocalHostingStatus
import com.morgana.client.LocalWorldDetails
import com.morgana.client.LocalWorldHost
import com.morgana.client.RealmSpec
import com.morgana.client.RealmTemplateInfo
import com.morgana.domain.sim.OptionalModule
import com.morgana.client.MorganaGame
import com.morgana.client.assets.FileAssetCache
import com.morgana.client.net.FileResumeStore
import com.morgana.client.net.MorganaNetClient
import com.morgana.client.state.FilePerRealmStore
import com.morgana.persistence.TlsMode
import com.morgana.protocol.tls.FileKnownHosts
import com.morgana.protocol.tls.InviteCode
import com.morgana.server.MorganaServerRuntime
import com.morgana.server.worlds.RealmBlueprint
import com.morgana.server.worlds.RealmTemplate
import com.morgana.server.worlds.WorldLibrary
import java.util.logging.Logger
import java.nio.file.Paths

/**
 * Hosts worlds from the on-disk [WorldLibrary] with one embedded server at a time. Switching the selected
 * world stops the running server (saving through its normal shutdown path) and starts one for the new world.
 */
internal class EmbeddedWorldHost(
    private val library: WorldLibrary,
    private val port: Int
) : LocalWorldHost, AutoCloseable {
    private val log = Logger.getLogger(EmbeddedWorldHost::class.java.name)
    private var running: MorganaServerRuntime? = null
    private var runningSlug: String? = null

    /** Slug of the world the embedded server is currently hosting, or null when stopped. */
    internal val activeSlug: String? get() = runningSlug

    override var selectedSlug: String = WorldLibrary.DEFAULT_SLUG

    override fun worlds(): List<LocalWorldChoice> = library.list().map {
        LocalWorldChoice(it.slug, it.name, library.classesFor(it.slug))
    }

    override fun createWorld(name: String): LocalWorldChoice {
        val info = library.create(name)
        selectedSlug = info.slug
        return LocalWorldChoice(info.slug, info.name, library.classesFor(info.slug))
    }

    /** The wizard offers every template this machine can create except pack import (that needs a file chooser; use the CLI). */
    override val realmTemplates: List<RealmTemplateInfo>
        get() = library.availableTemplates().filter { it != RealmTemplate.PACK }
            .map { RealmTemplateInfo(it.id, it.title, it.description, library.templateDefaults(it).map { m -> m.configKey }.toSet()) }

    override fun createRealm(spec: RealmSpec): LocalWorldChoice {
        val template = library.templateFromId(spec.templateId) ?: throw IllegalArgumentException("Unknown starting point '${spec.templateId}'")
        val modules = spec.disabledModuleKeys.map {
            OptionalModule.fromConfigKey(it) ?: throw IllegalArgumentException("Unknown feature '$it'")
        }.toSet()
        val enabled = spec.enabledModuleKeys.map {
            OptionalModule.fromConfigKey(it) ?: throw IllegalArgumentException("Unknown feature '$it'")
        }.toSet()
        val info = library.create(RealmBlueprint(spec.name, template, modules, noCombat = spec.noCombat, modernSprites = spec.modernSprites, enabledModules = enabled))
        selectedSlug = info.slug
        return LocalWorldChoice(info.slug, info.name, library.classesFor(info.slug))
    }

    @Synchronized
    override fun inviteCode(publicHost: String): String? {
        ensureRunning()
        val server = running ?: return null
        val fingerprint = server.tlsFingerprint ?: return null
        val host = publicHost.trim().ifBlank { lanAddress() ?: return null }
        return InviteCode(host, server.port, fingerprint).format()
    }

    /** This machine's first private IPv4 address, for friends on the same network. */
    private fun lanAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    @Synchronized
    override fun ensureRunning() {
        val world = library.find(selectedSlug) ?: library.find(WorldLibrary.DEFAULT_SLUG)!!
        if (running != null && runningSlug == world.slug) return
        running?.close()
        running = null
        runningSlug = null
        try {
            // Worlds hosted from the desktop client encrypt every connection except this machine's own, with no setup.
            running = MorganaServerRuntime.start(world.dataDir, port, defaultTlsMode = TlsMode.REMOTE)
            runningSlug = world.slug
            library.markPlayed(world.slug)
            log.info("Embedded server started for world '${world.name}' (${world.dataDir})")
        } catch (e: Exception) {
            // Typically the port is taken by a dedicated server; the client then simply connects to that one.
            log.warning("Could not start embedded server for '${world.name}': ${e.message}")
        }
    }

    override fun details(slug: String): LocalWorldDetails? = library.summary(slug)?.let {
        LocalWorldDetails(
            slug = it.slug, name = it.name, origin = it.origin, folder = it.folder.toAbsolutePath().normalize().toString(),
            createdAtMillis = it.createdAtMillis, lastPlayedAtMillis = it.lastPlayedAtMillis,
            mapCount = it.mapCount, classCount = it.classCount, disabledFeatures = it.disabledModules, isDefault = it.isDefault
        )
    }

    @Synchronized
    override fun hostingStatus(): LocalHostingStatus? {
        val server = running ?: return null
        val slug = runningSlug ?: return null
        val name = library.find(slug)?.name ?: slug
        return LocalHostingStatus(slug, name, server.port, server.coordinator.onlineCount, lanAddress(), server.tlsFingerprint != null)
    }

    @Synchronized
    override fun stopHosting() {
        running?.close()
        running = null
        runningSlug = null
    }

    override val canManageWorlds: Boolean get() = true

    @Synchronized
    override fun renameWorld(slug: String, newName: String): LocalWorldChoice {
        val info = library.rename(slug, newName)
        return LocalWorldChoice(info.slug, info.name, library.classesFor(info.slug))
    }

    @Synchronized
    override fun deleteWorld(slug: String) {
        if (runningSlug == slug) stopHosting() // release the folder before removing it
        library.delete(slug)
        if (selectedSlug == slug) selectedSlug = WorldLibrary.DEFAULT_SLUG
    }

    override fun openFolder(slug: String): Boolean {
        val dir = library.find(slug)?.dataDir?.toFile() ?: return false
        return runCatching {
            if (!java.awt.Desktop.isDesktopSupported() || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) return false
            java.awt.Desktop.getDesktop().open(dir)
            true
        }.getOrDefault(false)
    }

    @Synchronized
    override fun close() {
        running?.close()
        running = null
    }
}

/**
 * LWJGL3 Desktop Launcher for the Morgana ORPG Client.
 * The login window offers a local world picker and "New World & Play": the chosen world is hosted by an embedded
 * authoritative 20 TPS server on this machine. Connecting to a remote host works as before.
 */
fun main(args: Array<String>) {
    val positionalHost = args.getOrNull(0)?.takeIf { !it.startsWith("--") } ?: "127.0.0.1"
    val positionalPort = args.getOrNull(1)?.toIntOrNull() ?: 7001
    // `--connect host:port [--tls] [--ticket dev_...]` (ticket also from MORGANA_TICKET) skips the login screen for automation.
    val direct = com.morgana.client.net.DirectConnect.fromArgs(args, System::getenv, positionalHost, positionalPort)
    val host = direct?.host ?: positionalHost
    val port = direct?.port ?: positionalPort
    val worldHost = EmbeddedWorldHost(WorldLibrary(Paths.get("worlds"), Paths.get("server-data")), port)

    val monitor = Lwjgl3ApplicationConfiguration.getDisplayMode()
    val (windowWidth, windowHeight) = DesktopWindow.initialSize(monitor.width, monitor.height)
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Morgana ORPG")
        setWindowedMode(windowWidth, windowHeight)
        useVsync(true)
        setForegroundFPS(60)
    }

    val game = MorganaGame(
        transport = MorganaNetClient(),
        defaultHost = host,
        defaultPort = port,
        onEnsureLocalServer = { worldHost.ensureRunning() },
        localWorlds = worldHost,
        knownHosts = FileKnownHosts(Paths.get("client-data", "known_hosts.txt")),
        perRealmStore = FilePerRealmStore(Paths.get("client-data", "realms_storage.json").toFile()),
        assetCache = FileAssetCache(Paths.get("client-data", "cache", "assets").toFile()),
        resumeStore = FileResumeStore(Paths.get("client-data", "resume.json").toFile()),
        directLogin = direct?.asResumeInfo(),
        pickPngFile = ::pickDesktopPngFile,
        autoUiScale = {
            val mode = Gdx.graphics.displayMode
            UiScales.auto(mode.width, mode.height, Gdx.graphics.density)
        }
    )

    try {
        Lwjgl3Application(game, config)
    } finally {
        worldHost.close()
    }
}

private fun pickDesktopPngFile(onPicked: (ByteArray?) -> Unit) {
    val t = Thread({
        val bytes = try {
            if (java.awt.GraphicsEnvironment.isHeadless()) {
                null
            } else {
                val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Import PNG Art", java.awt.FileDialog.LOAD).apply {
                    setFilenameFilter { _, name -> name.endsWith(".png", ignoreCase = true) }
                    isVisible = true
                }
                val file = dialog.file
                val dir = dialog.directory
                dialog.dispose()
                if (file != null && dir != null) java.nio.file.Files.readAllBytes(Paths.get(dir, file)) else null
            }
        } catch (_: Exception) {
            null
        }
        com.badlogic.gdx.Gdx.app?.postRunnable { onPicked(bytes) } ?: onPicked(bytes)
    }, "png-file-picker")
    t.isDaemon = true
    t.start()
}
