package com.morgana.persistence

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Document-oriented collection port.
 * Backed by human-readable JSON files on disk by default, and 1:1 compatible with a MongoDB collection adapter.
 */
interface DocumentCollection<ID, T> {
    fun findById(id: ID): T?
    fun findAll(): List<T>
    fun save(id: ID, document: T)
    fun deleteById(id: ID): Boolean
    fun count(): Int
}

/**
 * Atomic, Git-friendly JSON file implementation of [DocumentCollection].
 * Each document is stored as `<directory>/<id>.json` using atomic temp-file replacement.
 */
class JsonFileDocumentCollection<ID, T>(
    private val directory: Path,
    private val serializer: KSerializer<T>,
    private val idToFileStem: (ID) -> String = { it.toString() }
) : DocumentCollection<ID, T> {

    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "_type"
    }

    init {
        if (!directory.exists()) {
            Files.createDirectories(directory)
        }
    }

    private fun filePathFor(id: ID): Path {
        val safeStem = idToFileStem(id).replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return directory.resolve("$safeStem.json")
    }

    @Synchronized
    override fun findById(id: ID): T? {
        val path = filePathFor(id)
        if (!path.exists()) return null
        return json.decodeFromString(serializer, path.readText())
    }

    @Synchronized
    override fun findAll(): List<T> {
        if (!directory.exists()) return emptyList()
        Files.list(directory).use { stream ->
            return stream
                .filter { it.isRegularFile() && it.extension == "json" }
                .sorted()
                .map { json.decodeFromString(serializer, it.readText()) }
                .toList()
        }
    }

    @Synchronized
    override fun save(id: ID, document: T) {
        if (!directory.exists()) {
            Files.createDirectories(directory)
        }
        val target = filePathFor(id)
        val tmp = directory.resolve("${target.fileName}.tmp")
        val payload = json.encodeToString(serializer, document)
        tmp.writeText(payload)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    override fun deleteById(id: ID): Boolean {
        val target = filePathFor(id)
        return Files.deleteIfExists(target)
    }

    @Synchronized
    override fun count(): Int {
        if (!directory.exists()) return 0
        Files.list(directory).use { stream ->
            return stream.filter { it.isRegularFile() && it.extension == "json" }.count().toInt()
        }
    }
}

/**
 * Thread-safe, zero-I/O in-memory implementation of [DocumentCollection] for rapid testing,
 * ephemeral server instances, and in-memory caches.
 */
class InMemoryDocumentCollection<ID : Any, T> : DocumentCollection<ID, T> {
    private val store = ConcurrentHashMap<ID, T>()

    override fun findById(id: ID): T? = store[id]

    override fun findAll(): List<T> = store.values.toList()

    override fun save(id: ID, document: T) {
        store[id] = document
    }

    override fun deleteById(id: ID): Boolean = store.remove(id) != null

    override fun count(): Int = store.size

    fun clear() {
        store.clear()
    }
}

