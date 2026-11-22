package com.morgana.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Server runtime state persisted across restarts (distinct from operator configuration in `server.json`).
 * Stored in `<data dir>/server-state.json`.
 *
 * @property worldEpochTicks Total elapsed simulation ticks across previous server runs.
 */
@Serializable
data class ServerState(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val worldEpochTicks: Long = 0L,
    val worldSwitches: Map<String, Boolean> = emptyMap(),
    val worldVariables: Map<String, Int> = emptyMap(),
    val worldStringVariables: Map<String, String> = emptyMap()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val FILE_NAME = "server-state.json"

        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        fun load(rootDir: Path): ServerState {
            val file = rootDir.resolve(FILE_NAME)
            if (!Files.isRegularFile(file)) return ServerState()
            return runCatching {
                json.decodeFromString<ServerState>(Files.readString(file))
            }.getOrElse { ServerState() }
        }

        fun save(rootDir: Path, state: ServerState) {
            Files.createDirectories(rootDir)
            val file = rootDir.resolve(FILE_NAME)
            val temp = rootDir.resolve("$FILE_NAME.tmp")
            val text = json.encodeToString(ServerState.serializer(), state)
            Files.writeString(temp, text)
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

