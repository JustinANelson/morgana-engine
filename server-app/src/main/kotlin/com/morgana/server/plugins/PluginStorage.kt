package com.morgana.server.plugins

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A plugin's own small key-value store, saved to `plugins/<id>/storage.json` after every change (written to a temporary file
 * and moved into place, so a crash cannot leave half a file). Keys and values are text; build per-character keys yourself,
 * for example `"gold:" + characterId`. It belongs to one plugin: another plugin cannot see it.
 *
 * Limits keep a buggy plugin from filling the disk: keys up to [MAX_KEY] characters, values up to [MAX_VALUE], [MAX_TOTAL]
 * characters in all. Going over throws [IllegalArgumentException].
 */
class PluginStorage(private val file: Path) {
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val json = Json { prettyPrint = true }
    private var data: MutableMap<String, String>? = null

    @Synchronized
    fun get(key: String): String? = load()[key]

    @Synchronized
    fun set(key: String, value: String) {
        require(key.isNotEmpty() && key.length <= MAX_KEY) { "Storage key must be 1-$MAX_KEY characters" }
        require(value.length <= MAX_VALUE) { "Storage value is longer than $MAX_VALUE characters" }
        val map = load()
        val total = map.entries.sumOf { (if (it.key == key) 0 else it.key.length + it.value.length) } + key.length + value.length
        require(total <= MAX_TOTAL) { "Plugin storage would exceed $MAX_TOTAL characters" }
        map[key] = value
        save(map)
    }

    @Synchronized
    fun remove(key: String): Boolean {
        val map = load()
        val had = map.remove(key) != null
        if (had) save(map)
        return had
    }

    @Synchronized
    fun keys(prefix: String = ""): List<String> = load().keys.filter { it.startsWith(prefix) }.sorted()

    private fun load(): MutableMap<String, String> {
        data?.let { return it }
        val loaded = if (Files.exists(file)) {
            try {
                json.decodeFromString(serializer, Files.readString(file)).toMutableMap()
            } catch (e: Exception) {
                // Keep the unreadable file for the author instead of overwriting it.
                Files.move(file, file.resolveSibling("storage.broken.json"), StandardCopyOption.REPLACE_EXISTING)
                mutableMapOf()
            }
        } else mutableMapOf()
        data = loaded
        return loaded
    }

    private fun save(map: Map<String, String>) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(serializer, map))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val MAX_KEY = 128
        const val MAX_VALUE = 65_536
        const val MAX_TOTAL = 4_000_000
    }
}
