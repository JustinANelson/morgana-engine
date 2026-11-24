package com.morgana.persistence

import com.morgana.domain.entity.WorldObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** One map's world objects as saved on disk. */
@Serializable
data class WorldObjectsDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val mapId: Int,
    val objects: List<WorldObject> = emptyList()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * Saves the things placed on maps (see [WorldObject]) as one JSON file per map, `<data dir>/world-objects/map_<id>.json`, so a
 * save rewrites only the maps that changed and a large base on one map never slows another. Writes are atomic (a temporary file
 * moved into place), and an emptied map's file is deleted. A file that cannot be read is skipped with a message rather than stopping
 * the server (the file is left as it is, so nothing is lost by a mistake in it).
 */
class WorldObjectRepository(private val dir: Path) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private fun fileFor(mapId: Int): Path = dir.resolve("map_$mapId.json")

    /** Every saved object, map by map in id order. */
    fun loadAll(onProblem: (String) -> Unit = {}): List<WorldObject> {
        if (!Files.isDirectory(dir)) return emptyList()
        val out = ArrayList<WorldObject>()
        Files.list(dir).use { files ->
            files.filter { it.fileName.toString().matches(Regex("map_-?\\d+\\.json")) }.sorted().forEach { file ->
                try {
                    val doc = json.decodeFromString(WorldObjectsDocument.serializer(), Files.readString(file))
                    out += doc.objects
                } catch (e: Exception) {
                    onProblem("Could not read ${file.fileName}: ${e.message}")
                }
            }
        }
        return out
    }

    /** Saves [objects] as map [mapId]'s file, or removes the file when there are none. */
    fun save(mapId: Int, objects: Collection<WorldObject>) {
        val file = fileFor(mapId)
        if (objects.isEmpty()) {
            Files.deleteIfExists(file)
            return
        }
        Files.createDirectories(dir)
        val temp = dir.resolve("map_$mapId.json.tmp")
        Files.writeString(temp, json.encodeToString(WorldObjectsDocument.serializer(), WorldObjectsDocument(mapId = mapId, objects = objects.toList())))
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
