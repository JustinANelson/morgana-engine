package com.morgana.server

import org.slf4j.LoggerFactory
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.util.concurrent.TimeUnit

/** What kind of change a [ContentWatcher] saw (the tag of the tree it happened in). */
object ChangeKind {
    const val CONTENT = "content"
    const val SCRIPTS = "scripts"
    const val ASSETS = "assets"
    const val GAME = "game"
}

/**
 * A directory to watch for one [kind] of change: [dir], whether to follow [recursive] subfolders, and which file names count
 * ([accepts]; dot-files and editor temp files never count).
 */
class WatchedTree(val kind: String, val dir: Path, val recursive: Boolean = false, val accepts: (String) -> Boolean = { true })

/**
 * Watches a game's content, scripts, art and `game.json` and calls [onChange] (debounced, once per burst) with the kinds that changed,
 * so hand, script and agent edits reach the running server without a restart.
 *
 * The one-tree form (`ContentWatcher(worldDir) { ... }`) watches `.json` files in the per-collection folders under [worldDir] and
 * calls [onChange] with `content`; it is the original behaviour.
 */
class ContentWatcher(
    trees: List<WatchedTree>,
    private val debounceMs: Long = 400L,
    private val onChange: (Set<String>) -> Unit
) : AutoCloseable {
    constructor(worldDir: Path, debounceMs: Long = 400L, onChange: () -> Unit) : this(
        listOf(WatchedTree(ChangeKind.CONTENT, worldDir, recursive = false) { it.endsWith(".json") }), debounceMs, { onChange() }
    )

    private val logger = LoggerFactory.getLogger(ContentWatcher::class.java)
    private val watchService = FileSystems.getDefault().newWatchService()
    private val keys = mutableMapOf<WatchKey, Pair<Path, WatchedTree>>()
    private val trees = trees.toList()

    @Volatile
    private var running = true
    private val thread = Thread(::loop, "morgana-content-watcher").apply { isDaemon = true }

    fun start(): ContentWatcher {
        for (tree in trees) registerTree(tree)
        thread.start()
        return this
    }

    private fun registerTree(tree: WatchedTree) {
        if (!Files.exists(tree.dir)) {
            try { Files.createDirectories(tree.dir) } catch (_: Exception) { return }
        }
        register(tree.dir, tree)
        // The content tree is the world folder with one level of collection folders; others follow every subfolder.
        if (tree.recursive) {
            Files.walk(tree.dir).use { s -> s.filter { Files.isDirectory(it) && it != tree.dir }.forEach { register(it, tree) } }
        } else {
            Files.list(tree.dir).use { s -> s.filter { Files.isDirectory(it) }.forEach { register(it, tree) } }
        }
    }

    private fun registerBelow(dir: Path, tree: WatchedTree) {
        register(dir, tree)
        if (tree.recursive) Files.walk(dir).use { s -> s.filter { Files.isDirectory(it) && it != dir }.forEach { register(it, tree) } }
    }

    private fun register(dir: Path, tree: WatchedTree) {
        if (keys.values.any { it.first == dir && it.second === tree }) return
        keys[dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)] = dir to tree
    }

    private fun counts(name: String, tree: WatchedTree): Boolean =
        !name.startsWith(".") && !name.endsWith("~") && !name.endsWith(".tmp") && tree.accepts(name)

    private fun loop() {
        var pendingSince = 0L
        val pendingKinds = linkedSetOf<String>()
        try {
            while (running) {
                val key = watchService.poll(100, TimeUnit.MILLISECONDS)
                if (key != null) {
                    val (dir, tree) = keys[key] ?: (null to null)
                    for (event in key.pollEvents()) {
                        val name = event.context() as? Path ?: continue
                        if (dir == null || tree == null) continue
                        val full = dir.resolve(name)
                        // A folder created later (a new collection, a new art subfolder) is watched too.
                        if (Files.isDirectory(full) && (tree.recursive || dir == tree.dir)) {
                            try { registerBelow(full, tree) } catch (_: Exception) { }
                        }
                        if (counts(name.toString(), tree)) {
                            pendingSince = System.currentTimeMillis()
                            pendingKinds += tree.kind
                        }
                    }
                    if (!key.reset()) keys.remove(key)
                }
                if (pendingSince != 0L && System.currentTimeMillis() - pendingSince >= debounceMs) {
                    pendingSince = 0L
                    val kinds = pendingKinds.toSet()
                    pendingKinds.clear()
                    try {
                        onChange(kinds)
                    } catch (e: Exception) {
                        logger.error("Content reload failed", e)
                    }
                }
            }
        } catch (_: ClosedWatchServiceException) {
            // shutting down
        }
    }

    override fun close() {
        running = false
        watchService.close()
    }
}
