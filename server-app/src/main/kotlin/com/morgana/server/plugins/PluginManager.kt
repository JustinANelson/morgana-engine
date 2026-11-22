package com.morgana.server.plugins

import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.ModuleRegistry
import com.morgana.protocol.PluginWindowDto
import com.morgana.server.EventHookRegistry
import com.morgana.server.MorganaServerHook
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

enum class PluginStatus { ENABLED, DISABLED, FAILED }

/** What the operator sees about one plugin jar. [id] is the file name when the manifest could not be read. */
data class PluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val status: PluginStatus,
    val file: String,
    val modules: List<String> = emptyList(),
    /** Why a failed plugin did not start, in plain words. */
    val problem: String? = null
)

/**
 * Finds plugin jars in a folder, checks them, and runs their lifecycle: load order follows `depends`, a plugin that fails
 * to start is rolled back and the rest carry on, and a plugin can be disabled, enabled or reloaded while the server runs.
 *
 * Each jar gets its own class loader (parent-first, so the engine's classes are shared). This is not a sandbox: plugins
 * are trusted code. See [MorganaPlugin].
 */
class PluginManager(
    private val dir: Path,
    private val hooks: EventHookRegistry,
    val modules: ModuleRegistry = ModuleRegistry(),
    private val flags: () -> ModuleFlags = { ModuleFlags.ALL_ENABLED },
    private val parentLoader: ClassLoader = PluginManager::class.java.classLoader,
    /** Delivers a server message to an online character (the coordinator's); returns false if they are not online. */
    private val messenger: (characterId: String, text: String) -> Boolean = { _, _ -> false },
    /** Reaches players' clients for plugin messages and windows. */
    private val ui: PluginUiBridge = PluginUiBridge.None
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(PluginManager::class.java)

    private class Entry(val jar: Path, val manifest: PluginManifest?, var problem: String?) {
        var status = if (problem == null) PluginStatus.DISABLED else PluginStatus.FAILED
        var loader: URLClassLoader? = null
        var instance: MorganaPlugin? = null
        var context: Context? = null
        val key: String get() = manifest?.id ?: jar.fileName.toString()
    }

    private val entries = LinkedHashMap<String, Entry>()

    /** Read every jar in the folder and enable what can be enabled. Safe to call once at startup. */
    @Synchronized
    fun loadAll(): List<PluginInfo> {
        entries.clear()
        for (entry in discover(dir)) {
            val clash = entries[entry.key]
            if (clash != null && entry.manifest != null) {
                val dup = Entry(entry.jar, null, "id '${entry.key}' is already used by ${clash.jar.fileName}")
                entries[entry.jar.fileName.toString()] = dup
            } else entries[entry.key] = entry
        }
        for (entry in startOrder()) if (entry.status == PluginStatus.DISABLED) enableEntry(entry)
        return list()
    }

    @Synchronized
    fun list(): List<PluginInfo> = entries.values.map { info(it) }

    @Synchronized
    fun enable(id: String): PluginInfo {
        val entry = entries[id] ?: throw IllegalArgumentException("No plugin '$id'")
        if (entry.manifest != null && entry.status != PluginStatus.ENABLED) {
            entry.problem = null
            entry.status = PluginStatus.DISABLED
            enableEntry(entry)
        }
        return info(entry)
    }

    /** Switch a plugin off. Plugins that depend on it are switched off first. Returns the ids switched off. */
    @Synchronized
    fun disable(id: String): List<String> {
        val entry = entries[id] ?: throw IllegalArgumentException("No plugin '$id'")
        val off = mutableListOf<String>()
        disableEntry(entry, off)
        return off
    }

    /** Re-read the jar and start it again, then restart the plugins that were switched off because they depend on it. */
    @Synchronized
    fun reload(id: String): PluginInfo {
        val old = entries[id] ?: throw IllegalArgumentException("No plugin '$id'")
        val off = mutableListOf<String>()
        disableEntry(old, off)
        val fresh = readJar(old.jar)
        entries.remove(old.key)
        entries[fresh.key] = fresh
        if (fresh.problem == null) enableEntry(fresh)
        for (dependent in off.filter { it != id }.reversed()) entries[dependent]?.let { if (it.manifest != null) enableEntry(it) }
        return info(fresh)
    }

    @Synchronized
    override fun close() {
        for (entry in entries.values.toList().asReversed()) disableEntry(entry, mutableListOf())
    }

    private fun info(e: Entry) = PluginInfo(
        e.key, e.manifest?.displayName ?: e.jar.fileName.toString(), e.manifest?.version ?: "", e.status, e.jar.fileName.toString(),
        e.context?.registeredModules?.toList().orEmpty(), e.problem
    )

    /** Entries ordered so every plugin follows the ones it depends on; a cycle or missing dependency marks the plugin failed. */
    private fun startOrder(): List<Entry> {
        val ordered = mutableListOf<Entry>()
        val state = HashMap<String, Int>() // 1 visiting, 2 done
        fun visit(e: Entry) {
            val m = e.manifest ?: return
            if (state[m.id] == 2 || e.status == PluginStatus.FAILED) return
            if (state[m.id] == 1) { e.problem = "its depends list loops back to itself"; e.status = PluginStatus.FAILED; return }
            state[m.id] = 1
            for (dep in m.depends) {
                val d = entries[dep]
                if (d == null || d.manifest == null) { e.problem = "it needs plugin '$dep', which is not installed"; e.status = PluginStatus.FAILED }
                else { visit(d); if (d.status == PluginStatus.FAILED && e.status != PluginStatus.FAILED) { e.problem = "it needs plugin '$dep', which did not start"; e.status = PluginStatus.FAILED } }
            }
            state[m.id] = 2
            if (e.status != PluginStatus.FAILED) ordered += e
        }
        entries.values.toList().forEach { visit(it) }
        entries.values.filter { it.status == PluginStatus.FAILED && it.problem != null }.forEach { log.warn("Plugin {} not started: {}", it.key, it.problem) }
        return ordered
    }

    private fun enableEntry(e: Entry) {
        val m = e.manifest ?: return
        val missing = m.depends.firstOrNull { entries[it]?.status != PluginStatus.ENABLED }
        if (missing != null) {
            e.problem = "it needs plugin '$missing', which is not running"
            e.status = PluginStatus.FAILED
            log.warn("Plugin {} not started: {}", m.id, e.problem)
            return
        }
        var loader: URLClassLoader? = null
        val context = Context(m, dir.resolve(m.id), LoggerFactory.getLogger("Plugin/${m.id}"))
        try {
            loader = URLClassLoader(arrayOf(e.jar.toUri().toURL()), parentLoader)
            val type = Class.forName(m.main, true, loader)
            require(MorganaPlugin::class.java.isAssignableFrom(type)) { "${m.main} does not implement MorganaPlugin" }
            val instance = type.getDeclaredConstructor().newInstance() as MorganaPlugin
            e.loader = loader; e.instance = instance; e.context = context
            instance.onEnable(context)
            e.status = PluginStatus.ENABLED
            e.problem = null
            log.info("Plugin {} {} enabled", m.displayName, m.version)
        } catch (t: Throwable) {
            val cause = if (t is java.lang.reflect.InvocationTargetException) t.targetException else t
            e.problem = when (cause) {
                is ClassNotFoundException -> "main class ${m.main} was not found in the jar"
                is NoSuchMethodException -> "main class ${m.main} needs a public no-argument constructor"
                else -> cause.message ?: cause.javaClass.simpleName
            }
            e.status = PluginStatus.FAILED
            log.error("Plugin {} failed to start: {}", m.id, e.problem, cause)
            context.undo()
            runCatching { loader?.close() }
            e.loader = null; e.instance = null; e.context = null
        }
    }

    private fun disableEntry(e: Entry, off: MutableList<String>) {
        if (e.status != PluginStatus.ENABLED) return
        val id = e.manifest?.id ?: return
        for (other in entries.values.toList()) {
            if (other.status == PluginStatus.ENABLED && id in (other.manifest?.depends ?: emptyList())) disableEntry(other, off)
        }
        try {
            e.instance?.onDisable()
        } catch (t: Throwable) {
            log.error("Plugin {} failed while stopping", id, t)
        }
        e.context?.undo()
        runCatching { e.loader?.close() }
        e.loader = null; e.instance = null; e.context = null
        e.status = PluginStatus.DISABLED
        off += id
        log.info("Plugin {} disabled", id)
    }

    /** What one plugin has registered, so it can all be taken back. */
    private inner class Context(private val manifest: PluginManifest, private val folder: Path, override val logger: Logger) : PluginContext {
        override val dataDir: Path get() = folder.also { Files.createDirectories(it) }
        override val storage: PluginStorage by lazy { PluginStorage(folder.resolve("storage.json")) }
        private val myHooks = mutableListOf<MorganaServerHook>()
        val registeredModules = mutableListOf<String>()
        private val openedWindows = mutableSetOf<Pair<String, String>>()
        override val pluginId: String get() = manifest.id
        override val version: String get() = manifest.version

        override fun registerHook(hook: MorganaServerHook) {
            hooks.register(hook)
            myHooks += hook
        }

        override fun registerModule(name: String, displayName: String, description: String): String {
            val d = modules.register(manifest.id, name, displayName, description)
            registeredModules += d.id
            return d.id
        }

        private fun ownId(kind: String, id: String) =
            require(id.startsWith("$pluginId:") && id.length > pluginId.length + 1 && id.length <= PluginWindowDto.MAX_ID) {
                "$kind '$id' must start with '$pluginId:' and be at most ${PluginWindowDto.MAX_ID} characters"
            }

        override fun sendMessage(characterId: String, channel: String, payload: Map<String, String>): Boolean {
            ownId("Channel", channel)
            require(payload.size <= 32 && payload.all { (k, v) -> k.length <= 64 && v.length <= 1000 }) { "Plugin message payload is too large" }
            return ui.sendMessage(characterId, channel, payload)
        }

        override fun openWindow(characterId: String, window: PluginWindowDto): Boolean {
            ownId("Window id", window.id)
            window.problem()?.let { throw IllegalArgumentException("Window '${window.id}': $it") }
            val shown = ui.openWindow(characterId, window)
            if (shown) synchronized(openedWindows) { openedWindows += characterId to window.id }
            return shown
        }

        override fun closeWindow(characterId: String, windowId: String): Boolean {
            ownId("Window id", windowId)
            synchronized(openedWindows) { openedWindows -= characterId to windowId }
            return ui.closeWindow(characterId, windowId)
        }

        override fun messagePlayer(characterId: String, text: String): Boolean = messenger(characterId, text)

        override fun isModuleEnabled(moduleId: String): Boolean = flags().isEnabled(moduleId)

        fun undo() {
            // Take the plugin's windows off players' screens too, so nothing is left that no plugin answers.
            synchronized(openedWindows) { openedWindows.toList().also { openedWindows.clear() } }.forEach { (character, window) -> runCatching { ui.closeWindow(character, window) } }
            myHooks.forEach { hooks.unregister(it) }
            registeredModules.forEach { modules.unregister(it) }
            myHooks.clear(); registeredModules.clear()
        }
    }

    companion object {
        /** The jars in [dir] with their manifests checked, without starting anything (what the `plugins` command prints). */
        fun inspect(dir: Path): List<PluginInfo> = discover(dir).map { e ->
            PluginInfo(e.key, e.manifest?.displayName ?: e.jar.fileName.toString(), e.manifest?.version ?: "", if (e.problem == null) PluginStatus.DISABLED else PluginStatus.FAILED, e.jar.fileName.toString(), problem = e.problem)
        }

        private fun discover(dir: Path): List<Entry> {
            if (!Files.isDirectory(dir)) return emptyList()
            val jars = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".jar", ignoreCase = true) }.sorted().toList() }
            return jars.map { readJar(it) }
        }

        private fun readJar(jar: Path): Entry {
            return try {
                JarFile(jar.toFile()).use { jf ->
                    val entry = jf.getJarEntry(PluginManifest.FILE_NAME)
                        ?: return Entry(jar, null, "${PluginManifest.FILE_NAME} is missing from the jar")
                    val text = jf.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                    val (manifest, problem) = PluginManifest.parse(text)
                    Entry(jar, manifest, problem)
                }
            } catch (e: Exception) {
                Entry(jar, null, "not a readable jar: ${e.message}")
            }
        }
    }
}
