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

/**
 * Watches the per-collection content directories under the world directory and calls [onChange]
 * (debounced) after JSON files are created, modified, or deleted, so hand/script/agent edits
 * hot-reload without a server restart.
 */
class ContentWatcher(
    private val worldDir: Path,
    private val debounceMs: Long = 400L,
    private val onChange: () -> Unit
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(ContentWatcher::class.java)
    private val watchService = FileSystems.getDefault().newWatchService()
    private val keys = mutableMapOf<WatchKey, Path>()

    @Volatile
    private var running = true
    private val thread = Thread(::loop, "morgana-content-watcher").apply { isDaemon = true }

    fun start(): ContentWatcher {
        registerTree()
        thread.start()
        return this
    }

    private fun registerTree() {
        if (!Files.exists(worldDir)) Files.createDirectories(worldDir)
        register(worldDir)
        Files.list(worldDir).use { s -> s.filter { Files.isDirectory(it) }.forEach { register(it) } }
    }

    private fun register(dir: Path) {
        if (keys.containsValue(dir)) return
        keys[dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)] = dir
    }

    private fun loop() {
        var pendingSince = 0L
        try {
            while (running) {
                val key = watchService.poll(100, TimeUnit.MILLISECONDS)
                if (key != null) {
                    val dir = keys[key]
                    for (event in key.pollEvents()) {
                        val name = event.context() as? Path ?: continue
                        val full = dir?.resolve(name)
                        if (full != null && dir == worldDir && Files.isDirectory(full)) register(full)
                        // Dot-files (the reload status file, editor temp files) are not content.
                        if (name.toString().endsWith(".json") && !name.toString().startsWith(".")) pendingSince = System.currentTimeMillis()
                    }
                    if (!key.reset()) keys.remove(key)
                }
                if (pendingSince != 0L && System.currentTimeMillis() - pendingSince >= debounceMs) {
                    pendingSince = 0L
                    try {
                        onChange()
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
